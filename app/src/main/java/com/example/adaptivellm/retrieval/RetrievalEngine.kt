package com.example.adaptivellm.retrieval

import android.util.Log
import com.example.adaptivellm.embedding.EmbeddingModel
import com.example.adaptivellm.storage.FactsRepository
import kotlin.math.pow

/**
 * Orchestrator для Phase 1 retrieval (Stage 3.2). Реализует Шаги 1.1, 1.2, 1.4
 * из architecture.md:
 *   1.1 — двойной поиск (FTS5 BM25 + sqlite-vec KNN) → RRF merge
 *   1.2 — triple scoring (relevance + recency + importance) → top-K
 *   1.4 — reinforcement (UPDATE access_count + last_access на selected)
 *
 * Шаг 1.3 (безусловная подгрузка инструкций) — отдельный вызов
 * [FactsRepository.getActiveInstructions], делается на уровне prompt assembly
 * (Stage 4).
 *
 * **НЕ хранит state**, чистая stateless логика. Может вызываться с любого
 * dispatcher'а — внутри `FactsRepository` сам уходит на `dbDispatcher`.
 */
object RetrievalEngine {

    private const val TAG = "RetrievalEngine"

    /** Параметры RRF / scoring — захардкожено по architecture.md § Фаза 1. */
    private const val SEARCH_K = 20         // top-K из каждого поиска (FTS5 + vec) до merge
    private const val RRF_K = 60            // RRF параметр сглаживания (стандарт)

    /**
     * Per-fact composite threshold (Stage 7 — отступление от arch.md all-or-none).
     *
     * Старая логика: top-1 ≥ 0.3 → возвращаем ВСЕ top-K (включая weak match'и).
     * Новая: каждый факт проходит фильтр composite ≥ этого значения индивидуально.
     *
     * Почему 0.5: composite = 0.4·rel + 0.35·rec + 0.25·imp. Свежий факт даёт
     * baseline recency ≈ 0.35. Чтобы пробиться через 0.5, нужен хоть какой-то
     * вклад от relevance/importance — отсекает «случайные fresh-но-нерелевантные»
     * факты, не теряя topical hits.
     */
    private const val PER_FACT_THRESHOLD = 0.5f

    // Веса triple scoring (architecture: 0.4 / 0.35 / 0.25)
    private const val W_RELEVANCE = 0.4f
    private const val W_RECENCY = 0.35f
    private const val W_IMPORTANCE = 0.25f

    /** Базис экспоненциального decay recency: 0.995^hours_since_access. */
    private const val RECENCY_DECAY = 0.995

    /**
     * true если retrieval pipeline можно использовать — то есть embedding модель
     * успешно инициализирована. Возвращает false на момент bootstrap'а пока
     * EmbeddingModel.initialize не отработала, и при init failure (degraded mode).
     */
    fun isReady(): Boolean = EmbeddingModel.isInitialized

    /**
     * Скоринг факта после RRF merge. relevance, recency, importance — компоненты
     * (для debug), score — взвешенная сумма.
     */
    data class ScoredFact(
        val fact: FactsRepository.Fact,
        val score: Float,
        val relevance: Float,   // cosine similarity к query (0..1), 0 если факт только из FTS5
        val recency: Float,     // 0.995^hours_since_last_access (0..1)
        val importanceNorm: Float, // importance / 10 (0..1)
        val rrfScore: Float,    // RRF результат до triple scoring (для debug)
    )

    /**
     * Главный entry-point: retrieve top-K фактов релевантных query.
     *
     * Pipeline:
     *   1. Compute query embedding (EmbeddingModel, ~10ms)
     *   2. Parallel-conceptually (serialized на dbDispatcher): FTS5 + vec search,
     *      each top-20
     *   3. RRF merge → ~15-20 unique candidates
     *   4. Fetch full Fact metadata batch
     *   5. Compute cosines для vec-hits (для FTS-only relevance=0)
     *   6. Triple scoring + sort desc
     *   7. Per-fact threshold filter (composite ≥ 0.5)
     *   8. Tier-priority: local-chat facts first, cross-chat filler в хвост
     *   9. Top-K + reinforcement (UPDATE access_count, last_access)
     *
     * @param query пользовательское сообщение / поисковая фраза
     * @param currentChatId scope для локальных фактов; null = только глобальные
     * @param topK сколько фактов вернуть (6/8/12 по RAM tier)
     */
    suspend fun retrieveFacts(
        query: String,
        currentChatId: Long?,
        topK: Int,
    ): List<ScoredFact> {
        if (query.isBlank() || topK <= 0) return emptyList()
        if (!EmbeddingModel.isInitialized) {
            Log.w(TAG, "EmbeddingModel not initialized — retrieval disabled")
            return emptyList()
        }

        // 1. Encode query
        val qEmb = EmbeddingModel.encode(query)

        // 2. Dual search
        val ftsResults = FactsRepository.searchFTS5(query, SEARCH_K, currentChatId)
        val vecResults = FactsRepository.searchVec(qEmb, SEARCH_K, currentChatId)

        if (ftsResults.isEmpty() && vecResults.isEmpty()) {
            Log.i(TAG, "retrieveFacts: no candidates from either search")
            return emptyList()
        }

        // 3. RRF merge
        val rrfScores = mergeRRF(ftsResults, vecResults, RRF_K)

        // 4. Fetch full metadata (по убыванию RRF score — порядок будет полезен
        // для debug log даже если потом resort'ируем)
        val orderedIds = rrfScores.entries
            .sortedByDescending { it.value }
            .map { it.key }
        val factsById = FactsRepository.getByIds(orderedIds)

        // 5. Cosines из vec results (distance → cosine для unit-norm векторов)
        val cosines = vecResults.associate { (id, dist) ->
            id to l2DistToCosine(dist)
        }

        // 6. Triple scoring
        val now = System.currentTimeMillis() / 1000L
        val scored = orderedIds.mapNotNull { id ->
            val fact = factsById[id] ?: return@mapNotNull null
            val relevance = cosines[id] ?: 0f
            val recency = computeRecency(fact, now)
            val importanceNorm = fact.importance / 10f
            val composite = W_RELEVANCE * relevance +
                            W_RECENCY * recency +
                            W_IMPORTANCE * importanceNorm
            ScoredFact(
                fact = fact,
                score = composite,
                relevance = relevance,
                recency = recency,
                importanceNorm = importanceNorm,
                rrfScore = rrfScores[id] ?: 0f,
            )
        }.sortedByDescending { it.score }

        // 7. Per-fact threshold filter (Stage 7): каждый факт фильтруется
        // индивидуально, не all-or-none. Свежие но weak-relevance факты
        // (например importance=10 событие из другой темы) отсекаются здесь.
        val passing = scored.filter { it.score >= PER_FACT_THRESHOLD }
        if (passing.isEmpty()) {
            Log.i(TAG, "retrieveFacts: no facts passed threshold ${PER_FACT_THRESHOLD} " +
                       "(top score=${scored.firstOrNull()?.score}) → return empty")
            return emptyList()
        }

        // 8. Tier-priority (Stage 7): сначала факты с origin == currentChatId
        // (отсортированные по composite score внутри своей группы), потом
        // факты остальных чатов (тоже по score). Так topical context из
        // current чата идёт первым, а cross-chat факты заполняют оставшиеся
        // слоты только если у них достаточно высокий score чтобы пробиться
        // через per-fact threshold выше.
        val (local, crossChat) = if (currentChatId != null) {
            passing.partition { it.fact.chatId == currentChatId }
        } else {
            // Нет current чата (необычный случай) — все факты в одной группе.
            emptyList<ScoredFact>() to passing
        }
        val tiered = local + crossChat
        val topResults = tiered.take(topK)
        FactsRepository.reinforce(topResults.map { it.fact.id }, now)

        Log.i(TAG, "retrieveFacts: query.len=${query.length}, fts=${ftsResults.size}, " +
                   "vec=${vecResults.size}, merged=${rrfScores.size}, " +
                   "scored=${scored.size}, passing=${passing.size} " +
                   "(local=${local.size}, cross=${crossChat.size}), " +
                   "returned=${topResults.size}, top1_score=${topResults[0].score}")
        return topResults
    }

    /**
     * Reciprocal Rank Fusion merge двух ranking'ов.
     *   rrf_score(d) = sum over ranking [1 / (k + rank(d))]
     * rank — 1-based позиция в каждом списке. Если документ только в одном
     * списке — единственная компонента.
     */
    fun mergeRRF(
        fts: List<Pair<Long, Float>>,
        vec: List<Pair<Long, Float>>,
        k: Int = RRF_K,
    ): Map<Long, Float> {
        val scores = HashMap<Long, Float>()
        fts.forEachIndexed { rank, (id, _) ->
            scores[id] = (scores[id] ?: 0f) + 1f / (k + (rank + 1))
        }
        vec.forEachIndexed { rank, (id, _) ->
            scores[id] = (scores[id] ?: 0f) + 1f / (k + (rank + 1))
        }
        return scores
    }

    /**
     * Конвертирует L2 distance в cosine similarity для unit-norm векторов.
     * Для нормализованных a, b: ||a-b||² = 2 - 2·cos(a,b)
     * → cos = 1 - L2²/2
     *
     * Зажимаем в [0, 1] — для безопасности от floating-point drift и случайных
     * negative cosines (теоретически возможно при padding effects).
     */
    private fun l2DistToCosine(l2: Float): Float {
        val cosine = 1f - (l2 * l2) / 2f
        return cosine.coerceIn(0f, 1f)
    }

    /**
     * Recency component triple score'а. Экспоненциальный decay по часам с
     * последнего обращения. Если access_count = 0 (никогда не использовался) —
     * используется created_at (свежесозданный факт получает максимальный recency).
     */
    private fun computeRecency(fact: FactsRepository.Fact, nowSec: Long): Float {
        val refSec = fact.lastAccess ?: fact.createdAt
        val hours = (nowSec - refSec) / 3600.0
        if (hours < 0) return 1f  // clock skew protection
        return RECENCY_DECAY.pow(hours).toFloat()
    }
}
