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
     * Фильтрует невалидированные факты через JOIN с `facts` (valid_to IS NULL)
     * и chat scope.
     *
     * @param query текст запроса — должен быть FTS5-safe (escape кавычек выполнен внутри)
     * @param k максимум кандидатов вернуть (top-K по BM25)
     * @param chatId фильтр scope — null = только глобальные, иначе глобальные + локальные данного chatа
     */
    suspend fun searchFTS5(
        query: String,
        k: Int,
        chatId: Long?,
    ): List<Pair<Long, Float>> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val chatFilter = if (chatId == null) {
            "f.chat_id IS NULL"
        } else {
            "(f.chat_id IS NULL OR f.chat_id = $chatId)"
        }
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
            "  AND $chatFilter " +
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
     * Фильтрация по valid_to внутри vec0 partition (column валидирована при INSERT).
     * Фильтрация по chat scope — JOIN с facts (vec0 не хранит chat_id).
     */
    suspend fun searchVec(
        embedding: FloatArray,
        k: Int,
        chatId: Long?,
    ): List<Pair<Long, Float>> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        require(embedding.size == 384) { "embedding must be 384-dim, got ${embedding.size}" }
        val db = MemoryDatabaseHelper.database()
        val embJson = embeddingToJson(embedding)
        val chatFilter = if (chatId == null) {
            "f.chat_id IS NULL"
        } else {
            "(f.chat_id IS NULL OR f.chat_id = $chatId)"
        }
        // sqlite-vec syntax: WHERE embedding MATCH '...' AND k = N.
        // valid_to не фильтруем — trigger facts_invalidate удалит row из
        // facts_vec при UPDATE facts.valid_to (см. 001_init.sql). То есть в
        // facts_vec автоматически живут ТОЛЬКО валидные факты. Дополнительный
        // safety filter — f.valid_to IS NULL через JOIN (на случай рассинхрона).
        // Architecture-aligned: instructions подгружаются отдельно в Step 1.3,
        // в semantic retrieval не участвуют.
        val json = db.queryToJson(
            "SELECT v.fact_id AS id, v.distance AS distance " +
            "FROM facts_vec v JOIN facts f ON f.id = v.fact_id " +
            "WHERE v.embedding MATCH '$embJson' " +
            "  AND v.k = $k " +
            "  AND f.valid_to IS NULL " +
            "  AND f.category != 'instruction' " +
            "  AND $chatFilter " +
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
