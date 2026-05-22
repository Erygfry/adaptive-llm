package com.example.adaptivellm.storage

import android.util.Log
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * DAO для таблицы `facts` (+ связанных `facts_fts`, `facts_vec`) — Stage 3.2.
 *
 * Все методы suspend на [MemoryDatabaseHelper.dbDispatcher] (single-threaded).
 *
 * **Schema reference** (см. architecture.md § Структура данных, `001_init.sql`):
 *   - `facts`: основная таблица с метаданными
 *   - `facts_fts`: FTS5 индекс (sync через triggers facts_ai/au/ad)
 *   - `facts_vec`: sqlite-vec0 индекс (manual INSERT, embedding из ONNX runtime)
 *
 * **Stage 3.2 scope**: чистый CRUD + поисковые методы. Orchestration (RRF merge,
 * triple scoring) живёт в `RetrievalEngine`.
 */
object FactsRepository {

    private const val TAG = "FactsRepository"

    /** Valid categories — должны совпадать с CHECK constraint в schema. */
    val CATEGORIES = setOf(
        "personal_info", "preference", "goal",
        "instruction", "event", "relationship"
    )

    /**
     * Полная запись факта (как лежит в БД). keywords хранится как JSON array,
     * декодируется в List<String> для convenience.
     */
    data class Fact(
        val id: Long,
        val content: String,
        val keywords: List<String>,
        val context: String?,
        val category: String,
        val importance: Int,
        val accessCount: Int,
        val lastAccess: Long?,
        val validFrom: Long,
        val validTo: Long?,
        val supersededBy: Long?,
        val chatId: Long?,         // null = global, non-null = local to chat
        val sourceMessageId: Long?,
        val eventDate: Long?,      // только для category='event'
        val createdAt: Long,
    )

    /**
     * Создаёт новый факт. Вставка идёт в две таблицы атомарно:
     *   1. INSERT INTO facts — основная запись
     *   2. INSERT INTO facts_vec — embedding (manual, не триггером)
     * `facts_fts` обновляется триггером `facts_ai` автоматически.
     *
     * @param embedding L2-нормализованный 384-dim вектор от EmbeddingModel.encode()
     * @return id созданного факта
     */
    suspend fun insertFact(
        content: String,
        keywords: List<String>,
        context: String?,
        category: String,
        importance: Int,
        embedding: FloatArray,
        validFrom: Long = nowSec(),
        chatId: Long? = null,
        sourceMessageId: Long? = null,
        eventDate: Long? = null,
    ): Long = withContext(MemoryDatabaseHelper.dbDispatcher) {
        require(category in CATEGORIES) { "Invalid category: $category" }
        require(importance in 1..10) { "importance must be 1..10, got $importance" }
        require(embedding.size == 384) { "embedding must be 384-dim, got ${embedding.size}" }
        require(category == "event" || eventDate == null) {
            "event_date только для category='event' (schema CHECK constraint)"
        }

        val db = MemoryDatabaseHelper.database()
        val now = nowSec()
        val keywordsJson = JSONArray(keywords).toString()
        val embeddingJson = embeddingToJson(embedding)

        var id: Long = -1L
        db.inTransaction {
            // 1. INSERT INTO facts
            val rc1 = db.exec(
                "INSERT INTO facts (content, keywords, context, category, importance, " +
                "valid_from, chat_id, source_message_id, event_date, created_at) VALUES (" +
                "'${escape(content)}', " +
                "'${escape(keywordsJson)}', " +
                "${if (context == null) "NULL" else "'${escape(context)}'"}, " +
                "'${escape(category)}', " +
                "$importance, " +
                "$validFrom, " +
                "${chatId ?: "NULL"}, " +
                "${sourceMessageId ?: "NULL"}, " +
                "${eventDate ?: "NULL"}, " +
                "$now);"
            )
            check(rc1 == MemoryDatabase.SQLITE_OK) { "INSERT facts failed: ${db.lastError()}" }

            val idJson = db.queryToJson("SELECT last_insert_rowid() AS id;")
            id = parseFirstLong(idJson, "id")
                ?: error("Failed to get last_insert_rowid: $idJson")

            // 2. INSERT INTO facts_vec — embedding передаём как JSON string,
            // sqlite-vec автоматически парсит в float32 array.
            // valid_to=0 — sentinel «факт активен». NULL нельзя (sqlite-vec
            // rejects), но и фильтрация по этому полю не используется — trigger
            // facts_invalidate удалит row при UPDATE valid_to в основной facts.
            val rc2 = db.exec(
                "INSERT INTO facts_vec (fact_id, category, valid_to, embedding) VALUES (" +
                "$id, '${escape(category)}', 0, '$embeddingJson');"
            )
            check(rc2 == MemoryDatabase.SQLITE_OK) { "INSERT facts_vec failed: ${db.lastError()}" }
        }
        Log.i(TAG, "insertFact: id=$id, category=$category, importance=$importance, len=${content.length}")
        id
    }

    /**
     * Ручное редактирование content факта пользователем (Stage 7 fact mgmt).
     *
     * Обновляет `facts.content` и пересинхронизирует `facts_vec` — sqlite-vec
     * не поддерживает UPDATE embedding'а (partition column), поэтому DELETE+INSERT.
     * `facts_au` trigger автоматически re-sync'ит FTS5 при UPDATE content/keywords/context.
     *
     * Keywords и context не меняются — только content. Embedding пересчитывается
     * caller'ом (ViewModel) из новой content + старых keywords + старого context.
     */
    suspend fun updateContent(
        id: Long,
        newContent: String,
        newEmbedding: FloatArray,
    ): Unit = withContext(MemoryDatabaseHelper.dbDispatcher) {
        require(newEmbedding.size == 384) { "embedding must be 384-dim, got ${newEmbedding.size}" }
        val db = MemoryDatabaseHelper.database()
        val embJson = embeddingToJson(newEmbedding)
        db.inTransaction {
            // 1. UPDATE content в facts → trigger facts_au автоматически re-sync'ит FTS5
            val rc1 = db.exec(
                "UPDATE facts SET content = '${escape(newContent)}' WHERE id = $id;"
            )
            check(rc1 == MemoryDatabase.SQLITE_OK) { "UPDATE facts.content failed: ${db.lastError()}" }

            // 2. Получить category для re-insert в facts_vec
            val catJson = db.queryToJson("SELECT category FROM facts WHERE id = $id;")
            if (catJson.startsWith("ERROR")) error("read category failed: $catJson")
            val arr = JSONArray(catJson)
            if (arr.length() == 0) error("fact id=$id not found")
            val category = arr.getJSONObject(0).getString("category")

            // 3. DELETE+INSERT в facts_vec для нового embedding
            val rc2 = db.exec("DELETE FROM facts_vec WHERE fact_id = $id;")
            check(rc2 == MemoryDatabase.SQLITE_OK) { "DELETE facts_vec failed: ${db.lastError()}" }

            val rc3 = db.exec(
                "INSERT INTO facts_vec (fact_id, category, valid_to, embedding) VALUES (" +
                "$id, '${escape(category)}', 0, '$embJson');"
            )
            check(rc3 == MemoryDatabase.SQLITE_OK) { "INSERT facts_vec failed: ${db.lastError()}" }
        }
        Log.i(TAG, "updateContent: id=$id, new_len=${newContent.length}")
    }

    /**
     * Инвалидирует факт (UPDATE valid_to). Trigger `facts_invalidate`
     * автоматически удаляет embedding из `facts_vec`. FTS5 row остаётся
     * (фильтруется через JOIN с `facts.valid_to IS NULL`).
     */
    suspend fun invalidateFact(id: Long, supersededBy: Long? = null, now: Long = nowSec()) =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            val db = MemoryDatabaseHelper.database()
            val rc = db.exec(
                "UPDATE facts SET valid_to = $now, " +
                "superseded_by = ${supersededBy ?: "NULL"} " +
                "WHERE id = $id AND valid_to IS NULL;"
            )
            check(rc == MemoryDatabase.SQLITE_OK) { "invalidateFact failed: ${db.lastError()}" }
            Log.i(TAG, "invalidateFact: id=$id, supersededBy=$supersededBy")
        }

    /** Возвращает факт по id (без фильтра по valid_to — для audit chain lookup). */
    suspend fun getById(id: Long): Fact? = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val json = db.queryToJson("SELECT * FROM facts WHERE id = $id;")
        if (json.startsWith("ERROR")) error("getById failed: $json")
        val arr = JSONArray(json)
        if (arr.length() == 0) null else parseFact(arr.getJSONObject(0))
    }

    /**
     * Возвращает несколько фактов по списку id. Используется после RRF merge —
     * получили top-K id'ов из FTS5 + vec, тянем metadata пачкой. Сохраняет порядок
     * исходного списка [ids] в результате (Map iteration order = insertion order).
     */
    suspend fun getByIds(ids: List<Long>): Map<Long, Fact> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val db = MemoryDatabaseHelper.database()
        val idList = ids.joinToString(",")
        val json = db.queryToJson("SELECT * FROM facts WHERE id IN ($idList);")
        if (json.startsWith("ERROR")) error("getByIds failed: $json")
        val arr = JSONArray(json)
        val byId = HashMap<Long, Fact>(arr.length())
        for (i in 0 until arr.length()) {
            val f = parseFact(arr.getJSONObject(i))
            byId[f.id] = f
        }
        // Возвращаем в исходном порядке ids
        val result = LinkedHashMap<Long, Fact>(ids.size)
        for (id in ids) byId[id]?.let { result[id] = it }
        result
    }

    /**
     * Безусловная подгрузка активных инструкций для текущего чата (Фаза 1.3).
     * Глобальные (chat_id IS NULL) и локальные (chat_id = :current) — обе.
     */
    suspend fun getActiveInstructions(chatId: Long?): List<Fact> =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            val db = MemoryDatabaseHelper.database()
            val chatFilter = if (chatId == null) {
                "chat_id IS NULL"
            } else {
                "(chat_id IS NULL OR chat_id = $chatId)"
            }
            val json = db.queryToJson(
                "SELECT * FROM facts " +
                "WHERE category = 'instruction' AND valid_to IS NULL " +
                "  AND $chatFilter " +
                "ORDER BY importance DESC, created_at DESC;"
            )
            if (json.startsWith("ERROR")) error("getActiveInstructions failed: $json")
            val arr = JSONArray(json)
            List(arr.length()) { parseFact(arr.getJSONObject(it)) }
        }

    /**
     * FTS5 BM25 search по content/keywords/context. Возвращает (fact_id, bm25_rank).
     * Меньший ранк = более релевантно (BM25 negate convention в SQLite FTS5).
     *
     * **Семантика chat_id (Stage 7):** chat_id трактуется как **origin** факта,
     * не как scope. Поэтому возвращаем все валидные факты со всех чатов;
     * tier-priority по origin делается в [RetrievalEngine] после scoring'а.
     * Параметр chatId оставлен в сигнатуре для будущей фильтрации (например
     * privacy mode), сейчас не используется.
     *
     * @param query текст запроса — должен быть FTS5-safe (escape кавычек выполнен внутри)
     * @param k максимум кандидатов вернуть (top-K по BM25)
     * @param chatId currently unused — см. описание семантики выше
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun searchFTS5(
        query: String,
        k: Int,
        chatId: Long?,
    ): List<Pair<Long, Float>> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        // FTS5 trigram tokenizer treats the index as 3-char substrings.
        // MATCH 'люблю' = `LIKE %люблю%` — точная подстрока, не помогает с
        // морфологией ("люблю" не подстрока "любит"). Хитрость: декомпозируем
        // query на индивидуальные 3-граммы вручную и OR'им их → каждая 3-грамма
        // ищется как substring отдельно, что находит общие части у морфологических
        // вариантов ("люб" из "люблю" matches "любит" из факта).
        //
        // Single-char и 2-char токены пропускаем (trigram tokenizer их не индексирует).
        val tokens = query
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
        if (tokens.isEmpty()) return@withContext emptyList()
        // Все sliding-window trigrams со всех токенов, дедуп'нутые через Set
        val trigrams = tokens.flatMapTo(LinkedHashSet()) { token ->
            (0..token.length - 3).map { i -> token.substring(i, i + 3) }
        }
        if (trigrams.isEmpty()) return@withContext emptyList()
        val ftsQuery = trigrams.joinToString(" OR ") { "\"${it.replace("\"", "")}\"" }
        val escapedFtsQuery = ftsQuery.replace("'", "''")

        // Architecture-aligned: instructions подгружаются отдельно в Step 1.3,
        // в semantic retrieval не участвуют (иначе дублируются в prompt).
        val json = db.queryToJson(
            "SELECT f.id AS id, bm25(facts_fts) AS score " +
            "FROM facts_fts JOIN facts f ON f.id = facts_fts.rowid " +
            "WHERE facts_fts MATCH '$escapedFtsQuery' " +
            "  AND f.valid_to IS NULL " +
            "  AND f.category != 'instruction' " +
            "ORDER BY score LIMIT $k;"
        )
        if (json.startsWith("ERROR")) {
            Log.w(TAG, "searchFTS5 failed (likely malformed query syntax): $json")
            return@withContext emptyList()
        }
        val arr = JSONArray(json)
        List(arr.length()) {
            val obj = arr.getJSONObject(it)
            obj.getLong("id") to obj.getDouble("score").toFloat()
        }
    }

    /**
     * sqlite-vec KNN search по embedding. Возвращает (fact_id, distance).
     * Меньший distance = ближе по L2 (для unit-norm векторов — больше cosine).
     *
     * **Семантика chat_id (Stage 7):** chat_id трактуется как **origin** факта,
     * не как scope. Возвращаем все валидные факты со всех чатов; tier-priority
     * по origin делается в [RetrievalEngine] после scoring'а. Также используется
     * conflict resolution в EvictionEngine — cross-chat dedup корректно работает
     * только если поиск видит ВСЕ факты.
     *
     * Фильтрация по valid_to: trigger facts_invalidate удаляет row из facts_vec
     * при UPDATE facts.valid_to (см. 001_init.sql). Дополнительный safety filter
     * через JOIN (на случай рассинхрона).
     *
     * @param chatId currently unused — см. описание семантики выше
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun searchVec(
        embedding: FloatArray,
        k: Int,
        chatId: Long?,
    ): List<Pair<Long, Float>> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        require(embedding.size == 384) { "embedding must be 384-dim, got ${embedding.size}" }
        val db = MemoryDatabaseHelper.database()
        val embJson = embeddingToJson(embedding)
        // sqlite-vec syntax: WHERE embedding MATCH '...' AND k = N.
        // Architecture-aligned: instructions подгружаются отдельно в Step 1.3,
        // в semantic retrieval не участвуют.
        val json = db.queryToJson(
            "SELECT v.fact_id AS id, v.distance AS distance " +
            "FROM facts_vec v JOIN facts f ON f.id = v.fact_id " +
            "WHERE v.embedding MATCH '$embJson' " +
            "  AND v.k = $k " +
            "  AND f.valid_to IS NULL " +
            "  AND f.category != 'instruction' " +
            "ORDER BY v.distance;"
        )
        if (json.startsWith("ERROR")) error("searchVec failed: $json")
        val arr = JSONArray(json)
        List(arr.length()) {
            val obj = arr.getJSONObject(it)
            obj.getLong("id") to obj.getDouble("distance").toFloat()
        }
    }

    /**
     * Reinforcement (Шаг 1.4): обновляет access_count + last_access для фактов,
     * попавших в top-K через семантический retrieval. Инструкции исключаются
     * (defensive — они и не должны прилетать в top-K, но фильтр на случай регрессий).
     */
    suspend fun reinforce(ids: List<Long>, now: Long = nowSec()) =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            if (ids.isEmpty()) return@withContext
            val db = MemoryDatabaseHelper.database()
            val idList = ids.joinToString(",")
            val rc = db.exec(
                "UPDATE facts SET access_count = access_count + 1, last_access = $now " +
                "WHERE id IN ($idList) AND category != 'instruction';"
            )
            check(rc == MemoryDatabase.SQLITE_OK) { "reinforce failed: ${db.lastError()}" }
        }

    /**
     * Все активные факты (valid_to IS NULL) для просмотра в UI «Память».
     * Не используется в retrieval — там hybrid search через FTS+vec.
     *
     * @param category если задан — фильтр по конкретной категории
     * @param limit защитный лимит (обычно фактов мало, но на длинной истории
     *              чата может быть много). Default 500 покрывает реалистичный
     *              max до того как periodic cleanup начнёт инвалидировать старые.
     */
    suspend fun getAllActive(
        category: String? = null,
        limit: Int = 500,
    ): List<Fact> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val categoryFilter = if (category == null) ""
                             else " AND category = '${escape(category)}'"
        val json = db.queryToJson(
            "SELECT * FROM facts " +
            "WHERE valid_to IS NULL$categoryFilter " +
            "ORDER BY created_at DESC LIMIT $limit;"
        )
        if (json.startsWith("ERROR")) error("getAllActive failed: $json")
        val arr = JSONArray(json)
        List(arr.length()) { parseFact(arr.getJSONObject(it)) }
    }

    /** Количество активных фактов (для UI / debug). */
    suspend fun countActive(chatId: Long? = null): Int = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val chatFilter = if (chatId == null) "chat_id IS NULL"
                         else "(chat_id IS NULL OR chat_id = $chatId)"
        val json = db.queryToJson(
            "SELECT COUNT(*) AS n FROM facts WHERE valid_to IS NULL AND $chatFilter;"
        )
        if (json.startsWith("ERROR")) error("countActive failed: $json")
        JSONArray(json).getJSONObject(0).getInt("n")
    }

    /**
     * Результат periodic cleanup'а — по сколько фактов инвалидировано по каждому
     * правилу. Используется в WorkManager logging'е.
     */
    data class CleanupStats(
        val rule1: Int,  // low importance + never accessed + old
        val rule2: Int,  // medium-low importance + rarely accessed + idle
        val rule3: Int,  // not very important + idle very long
        val rule4: Int,  // expired events
    ) {
        val total: Int get() = rule1 + rule2 + rule3 + rule4
    }

    /**
     * Periodic cleanup (Stage 7 — architecture.md § Periodic cleanup). Инвалидирует
     * факты по 4 правилам:
     *   1. importance ≤ 3 + access_count=0 + created > 30 дней назад
     *   2. importance ≤ 5 + access_count ≤ 2 + idle > 90 дней
     *   3. importance < 8 + idle > 6 месяцев (~180 дней)
     *   4. category='event' + event_date < now - 30 дней (просроченные события)
     *
     * idle = now - COALESCE(last_access, created_at).
     *
     * Каждое правило — отдельный UPDATE в одной транзакции. Trigger
     * facts_invalidate каскадно удаляет embedding'и из facts_vec.
     *
     * Не запускается из UI-флоу — вызывается WorkManager'ом (раз в сутки).
     */
    suspend fun invalidateExpired(now: Long = nowSec()): CleanupStats =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            val db = MemoryDatabaseHelper.database()
            val day = 86_400L
            val cutoff30  = now - 30L  * day
            val cutoff90  = now - 90L  * day
            val cutoff180 = now - 180L * day

            var n1 = 0; var n2 = 0; var n3 = 0; var n4 = 0
            db.inTransaction {
                // Rule 1: trash facts (low importance, never accessed, old)
                val rc1 = db.exec(
                    "UPDATE facts SET valid_to = $now WHERE valid_to IS NULL " +
                    "AND importance <= 3 AND access_count = 0 AND created_at < $cutoff30;"
                )
                check(rc1 == MemoryDatabase.SQLITE_OK) { "rule1 cleanup failed: ${db.lastError()}" }
                n1 = readChanges(db)

                // Rule 2: low-priority idle
                val rc2 = db.exec(
                    "UPDATE facts SET valid_to = $now WHERE valid_to IS NULL " +
                    "AND importance <= 5 AND access_count <= 2 " +
                    "AND COALESCE(last_access, created_at) < $cutoff90;"
                )
                check(rc2 == MemoryDatabase.SQLITE_OK) { "rule2 cleanup failed: ${db.lastError()}" }
                n2 = readChanges(db)

                // Rule 3: medium-priority very idle (semi-permanent only выживают)
                val rc3 = db.exec(
                    "UPDATE facts SET valid_to = $now WHERE valid_to IS NULL " +
                    "AND importance < 8 " +
                    "AND COALESCE(last_access, created_at) < $cutoff180;"
                )
                check(rc3 == MemoryDatabase.SQLITE_OK) { "rule3 cleanup failed: ${db.lastError()}" }
                n3 = readChanges(db)

                // Rule 4: expired events
                val rc4 = db.exec(
                    "UPDATE facts SET valid_to = $now WHERE valid_to IS NULL " +
                    "AND category = 'event' AND event_date IS NOT NULL " +
                    "AND event_date < $cutoff30;"
                )
                check(rc4 == MemoryDatabase.SQLITE_OK) { "rule4 cleanup failed: ${db.lastError()}" }
                n4 = readChanges(db)
            }
            val stats = CleanupStats(n1, n2, n3, n4)
            Log.i(TAG, "invalidateExpired: rule1=$n1, rule2=$n2, rule3=$n3, rule4=$n4 " +
                       "(total=${stats.total} invalidated)")
            stats
        }

    /** Helper — читает sqlite3_changes() через `SELECT changes()`. */
    private fun readChanges(db: MemoryDatabase): Int {
        val json = db.queryToJson("SELECT changes() AS n;")
        if (json.startsWith("ERROR")) return 0
        return JSONArray(json).getJSONObject(0).getInt("n")
    }

    /** Удаляет ВСЕ факты (используется в test/dev cleanup). НЕ для production. */
    suspend fun deleteAll() = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        db.inTransaction {
            // facts_vec / facts_fts очистятся каскадно через триггеры на DELETE
            val rc = db.exec("DELETE FROM facts;")
            check(rc == MemoryDatabase.SQLITE_OK) { "deleteAll failed: ${db.lastError()}" }
        }
        Log.w(TAG, "deleteAll: ALL FACTS deleted")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    private fun escape(s: String): String = s.replace("'", "''")

    /** Конвертирует FloatArray в JSON string `[0.1, 0.2, ...]` для sqlite-vec parsing. */
    private fun embeddingToJson(emb: FloatArray): String {
        val sb = StringBuilder(emb.size * 12)
        sb.append('[')
        for (i in emb.indices) {
            if (i > 0) sb.append(',')
            sb.append(emb[i])
        }
        sb.append(']')
        return sb.toString()
    }

    private fun parseFact(obj: JSONObject): Fact {
        val kwRaw = if (obj.isNull("keywords")) null else obj.getString("keywords")
        val keywords = if (kwRaw.isNullOrBlank()) emptyList() else parseStringList(kwRaw)
        return Fact(
            id = obj.getLong("id"),
            content = obj.getString("content"),
            keywords = keywords,
            context = if (obj.isNull("context")) null else obj.getString("context"),
            category = obj.getString("category"),
            importance = obj.getInt("importance"),
            accessCount = obj.getInt("access_count"),
            lastAccess = if (obj.isNull("last_access")) null else obj.getLong("last_access"),
            validFrom = obj.getLong("valid_from"),
            validTo = if (obj.isNull("valid_to")) null else obj.getLong("valid_to"),
            supersededBy = if (obj.isNull("superseded_by")) null else obj.getLong("superseded_by"),
            chatId = if (obj.isNull("chat_id")) null else obj.getLong("chat_id"),
            sourceMessageId = if (obj.isNull("source_message_id")) null else obj.getLong("source_message_id"),
            eventDate = if (obj.isNull("event_date")) null else obj.getLong("event_date"),
            createdAt = obj.getLong("created_at"),
        )
    }

    private fun parseStringList(json: String): List<String> = try {
        val arr = JSONArray(json)
        List(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        Log.w(TAG, "parseStringList failed for '$json'", e)
        emptyList()
    }

    private fun parseFirstLong(json: String, field: String): Long? {
        val arr = JSONArray(json)
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).getLong(field)
    }
}
