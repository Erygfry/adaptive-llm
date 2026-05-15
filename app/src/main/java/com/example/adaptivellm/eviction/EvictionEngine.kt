package com.example.adaptivellm.eviction

import android.util.Log
import com.example.adaptivellm.device.RamTier
import com.example.adaptivellm.embedding.EmbeddingModel
import com.example.adaptivellm.inference.InferenceEngine
import com.example.adaptivellm.storage.ChatRepository
import com.example.adaptivellm.storage.FactsRepository
import com.example.adaptivellm.storage.SnapshotBaseManager
import kotlinx.coroutines.flow.toList
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Phase 4 eviction orchestrator (Stage 6.1).
 *
 * Триггерится после успешного завершения turn'а в [com.example.adaptivellm.ui.MainViewModel.sendMessage]
 * когда KV usage превышает [tMaxFor]. Сжимает старые сообщения в обновлённый
 * summary + извлекает atomic facts, освобождая место для новых turn'ов.
 *
 * **State machine** (architecture.md § Полная последовательность eviction):
 *   - A: UPDATE chats.eviction_state = 'in_progress' + UI block
 *   - B: switch context (snapshot_base load OR setSystemPrompt) →
 *        decode extraction prompt → LLM call (GBNF JSON, thinking=NEVER)
 *   - C: parse JSON → UPDATE summary + INSERT facts (Stage 6.1: naive insert,
 *        без conflict resolution — это Stage 6.2)
 *   - D: rebuild KV = [system + last-N messages] (без summary в system block —
 *        упрощение Stage 6.1; facts покрывают gap через retrieval)
 *
 * Recovery on crash mid-eviction — Stage 6.3.
 */
object EvictionEngine {

    private const val TAG = "EvictionEngine"

    /** Триггер eviction: trigger когда сумма KV > T_max */
    fun tMaxFor(tier: RamTier): Int = when (tier) {
        RamTier.HIGH ->  14000
        RamTier.UPPER_MID -> 6000
        RamTier.MID -> 4000
        RamTier.LOW, RamTier.VERY_LOW -> 4000
    }

    /** Target размер KV после eviction (architecture.md) */
    private fun tRetainedFor(tier: RamTier): Int = when (tier) {
        RamTier.HIGH -> 8000
        RamTier.UPPER_MID -> 3500
        RamTier.MID -> 2400
        RamTier.LOW, RamTier.VERY_LOW -> 2400
    }

    /** Token budget для summary (target после extraction'а) */
    private fun summaryCapFor(tier: RamTier): Int = when (tier) {
        RamTier.HIGH -> 1000
        RamTier.UPPER_MID -> 700
        RamTier.MID -> 500
        RamTier.LOW, RamTier.VERY_LOW -> 500
    }

    /** Сколько последних сообщений оставлять в KV после eviction */
    private fun lastNFor(tier: RamTier): Int = when (tier) {
        RamTier.HIGH -> 8
        RamTier.UPPER_MID -> 6
        RamTier.MID -> 4
        RamTier.LOW, RamTier.VERY_LOW -> 4
    }

    /** Лимит output токенов на extraction LLM call. Summary (~500) + facts (~10×80) + JSON overhead. */
    private const val EXTRACTION_MAX_TOKENS = 2048

    /**
     * Архитектурный минимум last-N (architecture.md § Минимум сообщений в контексте).
     * Используется в overflow case (Шаг 4.3) когда tier last-N не помещается в
     * T_retained budget — сокращаем до этого минимума.
     */
    private const val MIN_LAST_N = 3

    /**
     * Проверяет нужна ли eviction и запускает её если да. Поток:
     *   1. Get current KV usage from engine.getCurrentPos
     *   2. If < T_max → return false (no eviction)
     *   3. Mark in_progress
     *   4. Run extraction LLM call (B), parse, apply to DB (C), rebuild KV (D)
     *   5. Mark idle
     *
     * @return true если eviction действительно прошла, false иначе.
     */
    suspend fun checkAndRun(
        chatId: Long,
        engine: InferenceEngine,
        ramTier: RamTier,
        systemPrompt: String,
        snapshotBase: SnapshotBaseManager,
        modelKey: String,
        nCtx: Int,
        currentThinkingMode: Int,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        val currentKvTokens = engine.getCurrentPos()
        val tMax = tMaxFor(ramTier)
        if (currentKvTokens < tMax) return false

        Log.i(TAG, "Eviction triggered: chatId=$chatId, current_pos=$currentKvTokens, T_max=$tMax")
        onProgress("Анализ контекста...")

        // Этап A: mark in_progress
        ChatRepository.setEvictionState(chatId, "in_progress")
        try {
            runEvictionInternal(
                chatId, engine, ramTier, systemPrompt, snapshotBase,
                modelKey, nCtx, currentThinkingMode, onProgress,
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Eviction failed for chatId=$chatId — rolling back state to idle", e)
            try { ChatRepository.setEvictionState(chatId, "idle") } catch (_: Exception) {}
            // Restore thinking mode + clear grammar — на случай если упали посередине
            try { engine.clearGrammar() } catch (_: Exception) {}
            try { engine.setThinkingMode(currentThinkingMode) } catch (_: Exception) {}
            return false
        }
    }

    private suspend fun runEvictionInternal(
        chatId: Long,
        engine: InferenceEngine,
        ramTier: RamTier,
        systemPrompt: String,
        snapshotBase: SnapshotBaseManager,
        modelKey: String,
        nCtx: Int,
        currentThinkingMode: Int,
        onProgress: (String) -> Unit,
    ) {
        // ─── Шаг 4.1 — определение evicted блока ─────────────────────────
        val summary = ChatRepository.getSummary(chatId)
            ?: error("No summary row for chatId=$chatId — Stage 1 createChat should have created it")
        val allInKv = ChatRepository.getMessagesAfter(chatId, summary.anchorMessageId)
        val tierLastN = lastNFor(ramTier)

        // Overflow handling (частичная импл Шага 4.3): мы попали сюда только если
        // T_max превышен (checkAndRun уже проверил). Если messages ≤ tier last-N,
        // обычная политика "сохранять last-N" заблокировала бы eviction. Architecture
        // mandates минимум last-N = 3, поэтому в overflow case сокращаем до 3.
        // В production T_max >> last-N, этот path активируется редко.
        val effectiveLastN = if (allInKv.size <= tierLastN) MIN_LAST_N else tierLastN
        if (allInKv.size <= effectiveLastN) {
            Log.i(TAG, "Skipping eviction: only ${allInKv.size} messages ≤ min last-N=$effectiveLastN")
            ChatRepository.setEvictionState(chatId, "idle")
            return
        }
        if (effectiveLastN != tierLastN) {
            Log.w(TAG, "Шаг 4.3 overflow: ${allInKv.size} messages ≤ tier last-N=$tierLastN, " +
                       "reducing to min last-N=$effectiveLastN")
        }

        val evictedBlock = allInKv.dropLast(effectiveLastN)
        val keptLastN = allInKv.takeLast(effectiveLastN)
        val newAnchorId = evictedBlock.last().id

        Log.i(TAG, "Шаг 4.1: evict ${evictedBlock.size} messages " +
                   "(ids ${evictedBlock.first().id}..${newAnchorId}), keep last-${keptLastN.size}")

        // ─── Этап B — switch context + extraction LLM call ───────────────
        onProgress("Извлечение фактов и обновление сводки...")

        // B.1: snapshot_base restore → KV в state [system_only]. Если Strategy B
        // (Vulkan где snapshot_base не работает) — fall back на setSystemPrompt.
        val snapshotLoaded = snapshotBase.load(engine, systemPrompt, modelKey, nCtx)
        if (!snapshotLoaded) {
            engine.setSystemPrompt(systemPrompt)
        }

        // B.2-B.3: prepare prompt + decode + generate JSON
        val extractionPrompt = ExtractionPrompt.build(
            summaryCap = summaryCapFor(ramTier),
            summary = summary,
            evictedMessages = evictedBlock,
        )

        var insertedFacts = 0
        var updatedFacts = 0
        var skippedFacts = 0
        var totalParsed = 0

        // Architecture: thinking_mode=NEVER для ВСЕХ system LLM calls в eviction'е
        // (extraction Шаг 4.2 + conflict resolution Шаг 4.4 + summary compression
        // Шаг 4.3 если бы был). Один setThinkingMode на всё, restore в finally.
        engine.setThinkingMode(2)  // NEVER
        try {
            // GBNF JSON constraint только на extraction call'е, у conflict
            // resolution свои отдельные grammar'ы (DECISION / INSTRUCTION_GRAMMAR).
            engine.setGrammar(ExtractionGbnf.GRAMMAR)
            val rawOutput: String = try {
                val sb = StringBuilder()
                engine.sendMessage(extractionPrompt, EXTRACTION_MAX_TOKENS).toList().forEach { sb.append(it) }
                sb.toString()
            } finally {
                engine.clearGrammar()
            }
            Log.i(TAG, "Шаг 4.2: extraction output ${rawOutput.length} chars")

            // ─── Этап C — parse + apply to DB ────────────────────────────────
            // Защита на случай если GBNF не примен'нулся (parse error в grammar):
            // модель часто оборачивает JSON в markdown-fence ```json ... ```.
            val cleanedJson = stripMarkdownFence(rawOutput.trim())
            val json = try {
                JSONObject(cleanedJson)
            } catch (e: Exception) {
                error("Extraction output is not valid JSON (GBNF should prevent this): ${e.message}, " +
                      "raw=${rawOutput.take(500)}")
            }

            val summaryObj = json.getJSONObject("summary")
            val newUserProfile = summaryObj.optString("user_profile", summary.userProfile)
            val newOngoing = summaryObj.optString("ongoing_topics", summary.ongoingTopics)
            val newDecisions = summaryObj.optString("key_decisions", summary.keyDecisions)
            val newPending = summaryObj.optString("pending_items", summary.pendingItems)

            val summaryText = buildSummaryText(newUserProfile, newOngoing, newDecisions, newPending)
            val summaryTokens = engine.tokenize(summaryText)

            ChatRepository.updateSummary(
                chatId = chatId,
                userProfile = newUserProfile,
                ongoingTopics = newOngoing,
                keyDecisions = newDecisions,
                pendingItems = newPending,
                anchorMessageId = newAnchorId,
                tokenCount = summaryTokens,
            )

            // Стейдж 6.2: parse + conflict resolution (Шаг 4.4) per fact.
            val factsArr = json.getJSONArray("facts")
            val parsed: List<ExtractedFact> = (0 until factsArr.length()).mapNotNull { i ->
                try {
                    parseFactFromJson(factsArr.getJSONObject(i))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse fact #$i: ${e.message}")
                    null
                }
            }
            totalParsed = parsed.size
            for ((idx, factObj) in parsed.withIndex()) {
                if (parsed.size > 1) {
                    onProgress("Дедупликация фактов (${idx + 1}/${parsed.size})...")
                }
                try {
                    val result = resolveAndInsertFact(
                        fact = factObj,
                        chatId = chatId,
                        engine = engine,
                        snapshotBase = snapshotBase,
                        systemPrompt = systemPrompt,
                        modelKey = modelKey,
                        nCtx = nCtx,
                        sourceMessageId = evictedBlock.lastOrNull()?.id,
                    )
                    when (result) {
                        ResolveResult.ADDED -> insertedFacts++
                        ResolveResult.UPDATED -> updatedFacts++
                        ResolveResult.NOOP -> skippedFacts++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed conflict-resolve for fact '${factObj.content}': ${e.message}")
                }
            }
            Log.i(TAG, "Этап C: summary updated (anchor=$newAnchorId, tokens=$summaryTokens), " +
                       "facts: $insertedFacts added, $updatedFacts updated, $skippedFacts noop " +
                       "($totalParsed total parsed)")
        } finally {
            // Restore thinking mode в любом случае — даже если extraction или
            // conflict resolution упали посередине. Grammar уже cleared в своих
            // inner finally'ях.
            engine.setThinkingMode(currentThinkingMode)
        }

        // C.5: mark idle (architecture делает это атомарно с transaction'ом; у
        // нас raw DB ops, идемпотентность через recovery в Stage 6.3)
        ChatRepository.setEvictionState(chatId, "idle")

        // ─── Этап D — rebuild KV = [system + last-N messages] ───────────
        // Stage 6.1 упрощение: summary НЕ в system block. Stage 6.x optimization —
        // включать summary в system для cache_prompt стабильности.
        onProgress("Восстановление контекста...")

        val baseRebuilt = snapshotBase.load(engine, systemPrompt, modelKey, nCtx)
        if (!baseRebuilt) {
            engine.setSystemPrompt(systemPrompt)
        }
        for (msg in keptLastN) {
            val rc = engine.addMessageToHistory(msg.role, msg.content)
            if (rc != 0) {
                Log.w(TAG, "Этап D: addMessageToHistory failed (rc=$rc) for msg ${msg.id} — " +
                           "KV truncated, future turns will work but with shorter history")
                break
            }
        }

        val finalPos = engine.getCurrentPos()
        val tRetained = tRetainedFor(ramTier)
        if (finalPos > tRetained) {
            Log.w(TAG, "Этап D: post-eviction KV ($finalPos tokens) exceeds T_retained ($tRetained) — " +
                       "next eviction may trigger sooner than expected")
        }
        Log.i(TAG, "Eviction complete: chatId=$chatId, evicted=${evictedBlock.size}, " +
                   "kept=${keptLastN.size}, facts +$insertedFacts/~$updatedFacts/=$skippedFacts " +
                   "(added/updated/noop), final_kv=$finalPos")
    }

    /** Распарсенный fact из extraction JSON (до conflict resolution). */
    private data class ExtractedFact(
        val content: String,
        val keywords: List<String>,
        val context: String?,
        val category: String,
        val importance: Int,
        val eventDate: Long?,
    )

    /** Результат [resolveAndInsertFact]. */
    private enum class ResolveResult { ADDED, UPDATED, NOOP }

    /** Cosine similarity порог при котором запускаем LLM conflict decision. */
    private const val CONFLICT_SIMILARITY_THRESHOLD = 0.7f

    /** Сколько кандидатов брать из vec search для conflict check. */
    private const val CONFLICT_TOP_K = 5

    /** Максимум output tokens для conflict decision LLM call (ADD/UPDATE/NOOP — 6 tokens max). */
    private const val CONFLICT_DECISION_MAX_TOKENS = 16

    /** Максимум output tokens для instruction conflict (id number или "none" — несколько digits). */
    private const val INSTRUCTION_CONFLICT_MAX_TOKENS = 16

    /**
     * Парсит fact из JSON object'а от extraction LLM call'а. Throws при невалидной
     * структуре (GBNF должна предотвратить, но defensive parse на всякий).
     */
    private fun parseFactFromJson(obj: JSONObject): ExtractedFact {
        val content = obj.getString("content").trim()
        require(content.isNotBlank()) { "fact.content is blank" }

        val keywordsArr = obj.getJSONArray("keywords")
        val keywords = List(keywordsArr.length()) { keywordsArr.getString(it) }
        val context = obj.optString("context", "").trim().ifBlank { null }
        val category = obj.getString("category")
        val importance = obj.getInt("importance").coerceIn(1, 10)
        val eventDateRaw = obj.opt("event_date")
        val eventDateUnix: Long? = when {
            eventDateRaw == null || eventDateRaw == JSONObject.NULL -> null
            eventDateRaw is String && eventDateRaw != "null" -> parseIsoDateToUnix(eventDateRaw)
            else -> null
        }
        // Schema constraint: event_date only for category='event'
        val finalEventDate = if (category == "event") eventDateUnix else null
        return ExtractedFact(content, keywords, context, category, importance, finalEventDate)
    }

    /**
     * Conflict resolution + insert (Шаг 4.4 architecture).
     *
     * General path (все категории кроме instruction):
     *   1. Composite embedding нового факта
     *   2. Cross-category vec search → top-K кандидатов
     *   3. Filter по cosine ≥ 0.7
     *   4. Если высоко-similar пусто → ADD direct
     *   5. Иначе: per-candidate LLM call с GBNF (ADD|UPDATE|NOOP)
     *      - UPDATE на первом hit'е → invalidate старого + insert нового → DONE
     *      - NOOP → skip new fact entirely (duplicate)
     *      - ADD → continue к следующему кандидату
     *   6. Если все ADD → finally insert новый
     *
     * Instruction path (special, architecture.md § «Special case для instruction»):
     *   1. Load all active instructions
     *   2. LLM call с GBNF (id | "none"): какая из existing вытесняется
     *   3. Если id → invalidate тот + insert новый. Если "none" → просто insert.
     */
    private suspend fun resolveAndInsertFact(
        fact: ExtractedFact,
        chatId: Long,
        engine: InferenceEngine,
        snapshotBase: SnapshotBaseManager,
        systemPrompt: String,
        modelKey: String,
        nCtx: Int,
        sourceMessageId: Long?,
    ): ResolveResult {
        val embText = buildString {
            append(fact.content)
            if (fact.keywords.isNotEmpty()) append(" Keywords: ").append(fact.keywords.joinToString(", "))
            if (!fact.context.isNullOrBlank()) append(" Context: ").append(fact.context)
        }
        val embedding = EmbeddingModel.encode(embText)

        // Special path для instruction
        if (fact.category == "instruction") {
            return resolveInstructionConflict(
                fact, embedding, chatId, engine, snapshotBase,
                systemPrompt, modelKey, nCtx, sourceMessageId,
            )
        }

        // General path: cross-category vec search
        val candidates = FactsRepository.searchVec(embedding, k = CONFLICT_TOP_K, chatId = null)
        val highSimilar = candidates.mapNotNull { (id, dist) ->
            // For unit-norm vectors: cos = 1 - L2² / 2
            val cosine = (1f - (dist * dist) / 2f).coerceIn(0f, 1f)
            if (cosine >= CONFLICT_SIMILARITY_THRESHOLD) id to cosine else null
        }

        if (highSimilar.isEmpty()) {
            // No conflict — ADD direct
            insertNewFact(fact, embedding, chatId = null, sourceMessageId = sourceMessageId)
            return ResolveResult.ADDED
        }

        // Per-candidate LLM decision
        val factsById = FactsRepository.getByIds(highSimilar.map { it.first })
        var sawNoopOrUpdate = false
        for ((existingId, cosine) in highSimilar) {
            val existing = factsById[existingId] ?: continue
            val decision = runConflictDecision(
                existing, fact, engine, snapshotBase,
                systemPrompt, modelKey, nCtx,
            )
            Log.i(TAG, "Conflict: new='${fact.content.take(40)}' vs existing#${existing.id}='${existing.content.take(40)}' " +
                       "(cos=${"%.3f".format(cosine)}) → $decision")
            when (decision) {
                "UPDATE" -> {
                    val newId = insertNewFact(fact, embedding, chatId = null, sourceMessageId = sourceMessageId)
                    FactsRepository.invalidateFact(existing.id, supersededBy = newId)
                    return ResolveResult.UPDATED
                }
                "NOOP" -> {
                    sawNoopOrUpdate = true
                    return ResolveResult.NOOP
                }
                else -> {
                    // ADD — continue к следующему кандидату
                }
            }
        }
        // Все decisions = ADD (или пустые) → добавляем новый факт
        if (!sawNoopOrUpdate) {
            insertNewFact(fact, embedding, chatId = null, sourceMessageId = sourceMessageId)
            return ResolveResult.ADDED
        }
        return ResolveResult.NOOP  // unreachable но defensive
    }

    /**
     * Special path для category='instruction' (architecture.md § Шаг 4.4).
     */
    private suspend fun resolveInstructionConflict(
        fact: ExtractedFact,
        embedding: FloatArray,
        chatId: Long,
        engine: InferenceEngine,
        snapshotBase: SnapshotBaseManager,
        systemPrompt: String,
        modelKey: String,
        nCtx: Int,
        sourceMessageId: Long?,
    ): ResolveResult {
        val activeInstructions = FactsRepository.getActiveInstructions(chatId = null)
        if (activeInstructions.isEmpty()) {
            insertNewFact(fact, embedding, chatId = null, sourceMessageId = sourceMessageId)
            return ResolveResult.ADDED
        }

        // Switch to clean context + GBNF
        val snapshotLoaded = snapshotBase.load(engine, systemPrompt, modelKey, nCtx)
        if (!snapshotLoaded) engine.setSystemPrompt(systemPrompt)
        engine.setGrammar(ConflictGbnf.INSTRUCTION_GRAMMAR)
        val output = try {
            val prompt = ConflictPrompt.buildInstructionConflict(activeInstructions, fact.content)
            val sb = StringBuilder()
            engine.sendMessage(prompt, INSTRUCTION_CONFLICT_MAX_TOKENS).toList().forEach { sb.append(it) }
            sb.toString().trim()
        } finally {
            engine.clearGrammar()
        }

        Log.i(TAG, "InstructionConflict: new='${fact.content.take(40)}', " +
                   "active=${activeInstructions.size}, decision='$output'")

        if (output == "none" || output.isBlank()) {
            insertNewFact(fact, embedding, chatId = null, sourceMessageId = sourceMessageId)
            return ResolveResult.ADDED
        }
        val supersededId = output.toLongOrNull()
        val target = activeInstructions.firstOrNull { it.id == supersededId }
        if (target == null) {
            // LLM выдала id'шник которого нет в active list (hallucination) →
            // defensive: просто insert новый
            Log.w(TAG, "InstructionConflict: LLM returned id=$output not in active list — just adding")
            insertNewFact(fact, embedding, chatId = null, sourceMessageId = sourceMessageId)
            return ResolveResult.ADDED
        }

        val newId = insertNewFact(fact, embedding, chatId = null, sourceMessageId = sourceMessageId)
        FactsRepository.invalidateFact(target.id, supersededBy = newId)
        return ResolveResult.UPDATED
    }

    /**
     * Single LLM call: NEW vs EXISTING decision (ADD|UPDATE|NOOP). Switches
     * context, applies GBNF, restores after.
     */
    private suspend fun runConflictDecision(
        existing: FactsRepository.Fact,
        newFact: ExtractedFact,
        engine: InferenceEngine,
        snapshotBase: SnapshotBaseManager,
        systemPrompt: String,
        modelKey: String,
        nCtx: Int,
    ): String {
        val snapshotLoaded = snapshotBase.load(engine, systemPrompt, modelKey, nCtx)
        if (!snapshotLoaded) engine.setSystemPrompt(systemPrompt)
        engine.setGrammar(ConflictGbnf.DECISION_GRAMMAR)
        val output = try {
            val prompt = ConflictPrompt.buildDecision(
                existing = existing,
                newContent = newFact.content,
                newCategory = newFact.category,
                newImportance = newFact.importance,
            )
            val sb = StringBuilder()
            engine.sendMessage(prompt, CONFLICT_DECISION_MAX_TOKENS).toList().forEach { sb.append(it) }
            sb.toString().trim()
        } finally {
            engine.clearGrammar()
        }
        // Normalize — GBNF гарантирует ADD/UPDATE/NOOP, но если parsing был skipped
        // (failed grammar — see Stage 6.1 fence-strip), нужен fallback
        return when {
            output.contains("UPDATE", ignoreCase = true) -> "UPDATE"
            output.contains("NOOP", ignoreCase = true) -> "NOOP"
            output.contains("ADD", ignoreCase = true) -> "ADD"
            else -> {
                Log.w(TAG, "ConflictDecision: unexpected output '$output' → defaulting to ADD")
                "ADD"
            }
        }
    }

    /** Wrapper над FactsRepository.insertFact с composite embedding'ом уже посчитанным. */
    private suspend fun insertNewFact(
        fact: ExtractedFact,
        embedding: FloatArray,
        chatId: Long?,
        sourceMessageId: Long?,
    ): Long = FactsRepository.insertFact(
        content = fact.content,
        keywords = fact.keywords,
        context = fact.context,
        category = fact.category,
        importance = fact.importance,
        embedding = embedding,
        chatId = chatId,
        sourceMessageId = sourceMessageId,
        eventDate = fact.eventDate,
    )

    private fun buildSummaryText(
        userProfile: String,
        ongoing: String,
        decisions: String,
        pending: String,
    ): String = buildString {
        if (userProfile.isNotBlank()) append("User Profile: ").append(userProfile).append('\n')
        if (ongoing.isNotBlank()) append("Ongoing Topics: ").append(ongoing).append('\n')
        if (decisions.isNotBlank()) append("Key Decisions: ").append(decisions).append('\n')
        if (pending.isNotBlank()) append("Pending Items: ").append(pending).append('\n')
    }

    /**
     * Убирает markdown fence ```json ... ``` если модель обернула JSON в него.
     * Защита для случая когда GBNF не применился (parse error grammar'а →
     * unconstrained output).
     */
    private fun stripMarkdownFence(s: String): String {
        val trimmed = s.trim()
        if (!trimmed.startsWith("```")) return trimmed
        // Snip первую строку (```json или просто ```)
        val afterFirstFence = trimmed.substringAfter('\n', "")
        // Snip последний ``` если есть
        val closeIdx = afterFirstFence.lastIndexOf("```")
        return if (closeIdx >= 0) afterFirstFence.substring(0, closeIdx).trim()
               else afterFirstFence.trim()
    }

    /** ISO YYYY-MM-DD → unix timestamp в UTC. Защита от malformed dates (NaN, год 0, etc.) */
    private fun parseIsoDateToUnix(iso: String): Long? = try {
        LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    } catch (e: Exception) {
        Log.w(TAG, "Invalid event_date '$iso' from extraction → null")
        null
    }
}
