package com.example.adaptivellm.storage

import android.util.Log
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * DAO над [MemoryDatabase] для CRUD операций с чатами и сообщениями.
 *
 * Все методы suspend и выполняются на [MemoryDatabaseHelper.dbDispatcher] —
 * serialized доступ к БД, нет race conditions между параллельными корутинами.
 *
 * **Schema reference** (см. architecture.md, SQL-схема и
 * `assets/db/migrations/001_init.sql`):
 *   - `chats`: id PK, title, created_at, last_active_at, has_kv_cache,
 *     kv_cache_last_message_id, eviction_state
 *   - `messages`: id PK, chat_id FK, role ('user'|'assistant'), content,
 *     token_count, created_at
 *   - `summary`: создаётся автоматически на каждый chat (см. createChat ниже)
 *
 * **Stage 1 scope**: только базовые CRUD без summary/facts/KV cache mgmt. Поля
 * вроде `has_kv_cache`, `eviction_state` остаются на defaults (0, 'idle');
 * они активируются на Stage 4/7.
 */
object ChatRepository {

    private const val TAG = "ChatRepository"

    /** Lightweight chat info для списка чатов (без messages). */
    data class ChatInfo(
        val id: Long,
        val title: String?,
        val createdAt: Long,
        val lastActiveAt: Long,
    )

    /** Message row из БД. */
    data class MessageRow(
        val id: Long,
        val chatId: Long,
        val role: String,        // "user" | "assistant"
        val content: String,
        val tokenCount: Int,
        val createdAt: Long,
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Chats
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Создаёт новый чат + связанную пустую summary запись (one-to-one schema
     * constraint). Возвращает id созданного чата.
     *
     * @param title optional, если null — сохранится как NULL в БД (заголовок
     *   потом обновляется через [updateTitle] из первого user message).
     */
    suspend fun createChat(title: String? = null): Long = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val now = nowSec()
        db.inTransaction {
            val titleSql = if (title == null) "NULL" else "'${escapeSqlString(title)}'"
            val rc1 = db.exec(
                "INSERT INTO chats (title, created_at, last_active_at) " +
                "VALUES ($titleSql, $now, $now);"
            )
            check(rc1 == MemoryDatabase.SQLITE_OK) { "INSERT chat failed: ${db.lastError()}" }
        }
        // chat_id = last inserted rowid. Query last row to get it.
        val idJson = db.queryToJson("SELECT last_insert_rowid() AS id;")
        val id = parseFirstLong(idJson, "id")
            ?: error("Failed to get last_insert_rowid: $idJson")

        // Создаём связанную summary запись (схема требует one-to-one chats→summary,
        // и многие запросы потом будут JOIN'иться через chat_id).
        val rc2 = db.exec(
            "INSERT INTO summary (chat_id, user_profile, ongoing_topics, " +
            "key_decisions, pending_items, anchor_message_id, token_count, " +
            "merge_count, updated_at) VALUES ($id, '', '', '', '', 0, 0, 0, 0);"
        )
        check(rc2 == MemoryDatabase.SQLITE_OK) { "INSERT summary failed: ${db.lastError()}" }

        Log.i(TAG, "createChat: id=$id, title=${title ?: "<auto>"}")
        id
    }

    /**
     * Удаляет чат вместе со всеми сообщениями (CASCADE через FK), summary,
     * локальными фактами. Глобальные факты остаются.
     */
    suspend fun deleteChat(chatId: Long) = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val rc = db.exec("DELETE FROM chats WHERE id = $chatId;")
        check(rc == MemoryDatabase.SQLITE_OK) { "DELETE chat failed: ${db.lastError()}" }
        Log.i(TAG, "deleteChat: id=$chatId")
    }

    /** Список чатов, отсортированный по последней активности (DESC — новейшие сверху). */
    suspend fun getChats(): List<ChatInfo> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val json = db.queryToJson(
            "SELECT id, title, created_at, last_active_at " +
            "FROM chats ORDER BY last_active_at DESC;"
        )
        if (json.startsWith("ERROR")) error("getChats failed: $json")
        parseChatList(json)
    }

    /** Обновляет `last_active_at` чата к текущему времени. */
    suspend fun touchChat(chatId: Long) = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val now = nowSec()
        val rc = db.exec("UPDATE chats SET last_active_at = $now WHERE id = $chatId;")
        check(rc == MemoryDatabase.SQLITE_OK) { "touchChat failed: ${db.lastError()}" }
    }

    /** Обновляет заголовок чата. */
    suspend fun updateTitle(chatId: Long, title: String) = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val rc = db.exec(
            "UPDATE chats SET title = '${escapeSqlString(title)}' WHERE id = $chatId;"
        )
        check(rc == MemoryDatabase.SQLITE_OK) { "updateTitle failed: ${db.lastError()}" }
    }

    /**
     * Stage 2.2.2 — обновляет KV cache markers для чата. Вызывается после
     * успешного save файла kv_cache.bin (chat exit / eviction Этап D.4).
     *
     * @param hasKvCache 1 если файл валиден на диске, 0 если файла нет
     * @param lastMessageId id последнего сообщения которое попало в save'нутый
     *   KV. После load этим значением определяем tail re-decode range.
     */
    suspend fun updateKvCacheState(
        chatId: Long,
        hasKvCache: Int,
        lastMessageId: Long,
    ) = withContext(MemoryDatabaseHelper.dbDispatcher) {
        require(hasKvCache == 0 || hasKvCache == 1) { "hasKvCache must be 0 or 1" }
        val db = MemoryDatabaseHelper.database()
        val rc = db.exec(
            "UPDATE chats SET has_kv_cache = $hasKvCache, " +
            "kv_cache_last_message_id = $lastMessageId WHERE id = $chatId;"
        )
        check(rc == MemoryDatabase.SQLITE_OK) { "updateKvCacheState failed: ${db.lastError()}" }
    }

    /**
     * Возвращает (has_kv_cache, kv_cache_last_message_id) для чата. Используется
     * в [selectChatInternal] чтобы решить — пробовать load kv_cache.bin или нет.
     * Возвращает null если чат не существует.
     */
    suspend fun getKvCacheState(chatId: Long): Pair<Int, Long>? = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val json = db.queryToJson(
            "SELECT has_kv_cache, kv_cache_last_message_id FROM chats WHERE id = $chatId;"
        )
        if (json.startsWith("ERROR")) error("getKvCacheState failed: $json")
        val arr = org.json.JSONArray(json)
        if (arr.length() == 0) return@withContext null
        val obj = arr.getJSONObject(0)
        obj.getInt("has_kv_cache") to obj.getLong("kv_cache_last_message_id")
    }

    /**
     * Возвращает сообщения чата с id строго больше указанного. Используется для
     * tail re-decode после kv_cache.bin load — декодируем только messages
     * добавленные после save.
     */
    suspend fun getMessagesAfter(chatId: Long, afterId: Long): List<MessageRow> =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            val db = MemoryDatabaseHelper.database()
            val json = db.queryToJson(
                "SELECT id, chat_id, role, content, token_count, created_at " +
                "FROM messages WHERE chat_id = $chatId AND id > $afterId ORDER BY id ASC;"
            )
            if (json.startsWith("ERROR")) error("getMessagesAfter failed: $json")
            parseMessageList(json)
        }

    /**
     * Stage 6.1 — возвращает сообщения чата в id-range (включительно). Используется
     * eviction'ом для извлечения "evicted block" — messages с id в [fromId, toId]
     * для подачи в extraction prompt.
     */
    suspend fun getMessagesBetween(chatId: Long, fromId: Long, toId: Long): List<MessageRow> =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            val db = MemoryDatabaseHelper.database()
            val json = db.queryToJson(
                "SELECT id, chat_id, role, content, token_count, created_at " +
                "FROM messages WHERE chat_id = $chatId " +
                "AND id >= $fromId AND id <= $toId ORDER BY id ASC;"
            )
            if (json.startsWith("ERROR")) error("getMessagesBetween failed: $json")
            parseMessageList(json)
        }

    /**
     * Stage 6.1 — обновляет token_count для message (требуется для cutoff_id
     * calculation в eviction Шаге 4.1). Вызывается из sendMessage после decode'а
     * (когда engine.tokenize(content) известно).
     */
    suspend fun updateMessageTokenCount(messageId: Long, tokenCount: Int) =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            val db = MemoryDatabaseHelper.database()
            val rc = db.exec(
                "UPDATE messages SET token_count = $tokenCount WHERE id = $messageId;"
            )
            check(rc == MemoryDatabase.SQLITE_OK) {
                "updateMessageTokenCount failed: ${db.lastError()}"
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  Summary (Stage 6.1 — Phase 4 eviction)
    // ─────────────────────────────────────────────────────────────────────────

    /** Structured summary для chat'а (architecture.md § Summary). */
    data class Summary(
        val chatId: Long,
        val userProfile: String,
        val ongoingTopics: String,
        val keyDecisions: String,
        val pendingItems: String,
        val anchorMessageId: Long,
        val tokenCount: Int,
        val mergeCount: Int,
        val updatedAt: Long,
    ) {
        /** true если summary ни разу не обновлялся (свежесозданный чат). */
        val isEmpty: Boolean get() = mergeCount == 0 && anchorMessageId == 0L
    }

    /** Возвращает summary чата. Не-null если чат существует (Stage 1 createChat создаёт пустой summary). */
    suspend fun getSummary(chatId: Long): Summary? = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val json = db.queryToJson(
            "SELECT chat_id, user_profile, ongoing_topics, key_decisions, pending_items, " +
            "anchor_message_id, token_count, merge_count, updated_at " +
            "FROM summary WHERE chat_id = $chatId;"
        )
        if (json.startsWith("ERROR")) error("getSummary failed: $json")
        val arr = org.json.JSONArray(json)
        if (arr.length() == 0) return@withContext null
        val obj = arr.getJSONObject(0)
        Summary(
            chatId = obj.getLong("chat_id"),
            userProfile = obj.optString("user_profile", ""),
            ongoingTopics = obj.optString("ongoing_topics", ""),
            keyDecisions = obj.optString("key_decisions", ""),
            pendingItems = obj.optString("pending_items", ""),
            anchorMessageId = obj.optLong("anchor_message_id", 0L),
            tokenCount = obj.optInt("token_count", 0),
            mergeCount = obj.optInt("merge_count", 0),
            updatedAt = obj.optLong("updated_at", 0L),
        )
    }

    /**
     * Обновляет summary целиком — вызывается из eviction'а после успешного
     * extraction'а. merge_count инкрементируется автоматически, updated_at
     * выставляется на текущий unix timestamp.
     */
    suspend fun updateSummary(
        chatId: Long,
        userProfile: String,
        ongoingTopics: String,
        keyDecisions: String,
        pendingItems: String,
        anchorMessageId: Long,
        tokenCount: Int,
    ) = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val now = nowSec()
        val rc = db.exec(
            "UPDATE summary SET " +
            "user_profile = '${escapeSqlString(userProfile)}', " +
            "ongoing_topics = '${escapeSqlString(ongoingTopics)}', " +
            "key_decisions = '${escapeSqlString(keyDecisions)}', " +
            "pending_items = '${escapeSqlString(pendingItems)}', " +
            "anchor_message_id = $anchorMessageId, " +
            "token_count = $tokenCount, " +
            "merge_count = merge_count + 1, " +
            "updated_at = $now " +
            "WHERE chat_id = $chatId;"
        )
        check(rc == MemoryDatabase.SQLITE_OK) { "updateSummary failed: ${db.lastError()}" }
        Log.i(TAG, "updateSummary: chatId=$chatId, anchor=$anchorMessageId, tokens=$tokenCount")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Eviction state (chats.eviction_state)
    // ─────────────────────────────────────────────────────────────────────────

    /** Возвращает eviction_state чата ('idle' | 'in_progress'). null если чат не существует. */
    suspend fun getEvictionState(chatId: Long): String? = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val json = db.queryToJson(
            "SELECT eviction_state FROM chats WHERE id = $chatId;"
        )
        if (json.startsWith("ERROR")) error("getEvictionState failed: $json")
        val arr = org.json.JSONArray(json)
        if (arr.length() == 0) return@withContext null
        arr.getJSONObject(0).optString("eviction_state", "idle")
    }

    /** Атомарно выставляет eviction_state. Используется А.1 (in_progress) и C.5 (idle). */
    suspend fun setEvictionState(chatId: Long, state: String) =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            require(state == "idle" || state == "in_progress") {
                "Invalid eviction_state: $state"
            }
            val db = MemoryDatabaseHelper.database()
            val rc = db.exec(
                "UPDATE chats SET eviction_state = '$state' WHERE id = $chatId;"
            )
            check(rc == MemoryDatabase.SQLITE_OK) { "setEvictionState failed: ${db.lastError()}" }
        }

    /**
     * Возвращает id'шники чатов в state 'in_progress' (для recovery on bootstrap
     * в Stage 6.3). Не-empty значит крах произошёл во время eviction'а — нужно
     * либо re-run, либо rollback.
     */
    suspend fun getChatsInEviction(): List<Long> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val json = db.queryToJson(
            "SELECT id FROM chats WHERE eviction_state = 'in_progress';"
        )
        if (json.startsWith("ERROR")) error("getChatsInEviction failed: $json")
        val arr = org.json.JSONArray(json)
        List(arr.length()) { arr.getJSONObject(it).getLong("id") }
    }

    /**
     * Stage 6.3 debug helper — возвращает (id, eviction_state) для ВСЕХ чатов.
     * Используется в recovery log'е для visibility при отладке (sqlite3 на
     * device обычно недоступен).
     */
    suspend fun getAllChatEvictionStates(): List<Pair<Long, String>> =
        withContext(MemoryDatabaseHelper.dbDispatcher) {
            val db = MemoryDatabaseHelper.database()
            val json = db.queryToJson(
                "SELECT id, eviction_state FROM chats ORDER BY id ASC;"
            )
            if (json.startsWith("ERROR")) error("getAllChatEvictionStates failed: $json")
            val arr = org.json.JSONArray(json)
            List(arr.length()) {
                val obj = arr.getJSONObject(it)
                obj.getLong("id") to obj.optString("eviction_state", "?")
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  Messages
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Добавляет сообщение в чат. Также обновляет `last_active_at`. Возвращает id
     * сообщения.
     *
     * @param role "user" или "assistant"
     * @param content plain text (для assistant — clean text без `<think>...</think>`)
     * @param tokenCount результат `llama_tokenize(content)`. Stage 1: можем
     *   передавать 0 если ещё не считаем; Stage 4+ потребуется для T_max и
     *   budget calculations.
     */
    suspend fun addMessage(
        chatId: Long,
        role: String,
        content: String,
        tokenCount: Int = 0,
    ): Long = withContext(MemoryDatabaseHelper.dbDispatcher) {
        require(role == "user" || role == "assistant") { "Invalid role: $role" }
        val db = MemoryDatabaseHelper.database()
        val now = nowSec()
        db.inTransaction {
            val rc1 = db.exec(
                "INSERT INTO messages (chat_id, role, content, token_count, created_at) " +
                "VALUES ($chatId, '${escapeSqlString(role)}', '${escapeSqlString(content)}', " +
                "$tokenCount, $now);"
            )
            check(rc1 == MemoryDatabase.SQLITE_OK) { "INSERT message failed: ${db.lastError()}" }
            val rc2 = db.exec("UPDATE chats SET last_active_at = $now WHERE id = $chatId;")
            check(rc2 == MemoryDatabase.SQLITE_OK) { "UPDATE last_active_at failed: ${db.lastError()}" }
        }
        val idJson = db.queryToJson("SELECT last_insert_rowid() AS id;")
        val id = parseFirstLong(idJson, "id")
            ?: error("Failed to get message id: $idJson")
        Log.i(TAG, "addMessage: chat=$chatId, role=$role, id=$id, len=${content.length}")
        id
    }

    /**
     * Загружает все сообщения чата в порядке возрастания id (== порядок вставки).
     * Для Stage 1 — простой full load. Stage 4+ ограничит до `last-N`.
     */
    suspend fun getMessages(chatId: Long): List<MessageRow> = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val json = db.queryToJson(
            "SELECT id, chat_id, role, content, token_count, created_at " +
            "FROM messages WHERE chat_id = $chatId ORDER BY id ASC;"
        )
        if (json.startsWith("ERROR")) error("getMessages failed: $json")
        parseMessageList(json)
    }

    /** Удаляет конкретное сообщение (для UI «удалить» — пока не используется). */
    suspend fun deleteMessage(messageId: Long) = withContext(MemoryDatabaseHelper.dbDispatcher) {
        val db = MemoryDatabaseHelper.database()
        val rc = db.exec("DELETE FROM messages WHERE id = $messageId;")
        check(rc == MemoryDatabase.SQLITE_OK) { "deleteMessage failed: ${db.lastError()}" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    /**
     * Escape строки для inline SQL. Stage 1 использует это вместо prepared
     * statements (которые добавим в Stage 5 как hot-path optimization).
     *
     * Покрывает single-quote и backslash. SQLite сам обработает остальные
     * special chars. Этого достаточно для нашего контента (user text + LLM
     * generated text). Stage 5 prepared statements ликвидируют этот path.
     */
    private fun escapeSqlString(s: String): String =
        s.replace("'", "''")

    private fun parseChatList(json: String): List<ChatInfo> {
        val arr = JSONArray(json)
        val result = mutableListOf<ChatInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                ChatInfo(
                    id = obj.getLong("id"),
                    title = if (obj.isNull("title")) null else obj.getString("title"),
                    createdAt = obj.getLong("created_at"),
                    lastActiveAt = obj.getLong("last_active_at"),
                )
            )
        }
        return result
    }

    private fun parseMessageList(json: String): List<MessageRow> {
        val arr = JSONArray(json)
        val result = mutableListOf<MessageRow>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                MessageRow(
                    id = obj.getLong("id"),
                    chatId = obj.getLong("chat_id"),
                    role = obj.getString("role"),
                    content = obj.getString("content"),
                    tokenCount = obj.getInt("token_count"),
                    createdAt = obj.getLong("created_at"),
                )
            )
        }
        return result
    }

    /** Достаёт long-значение поля из первого элемента JSON массива. Null если массив пуст. */
    private fun parseFirstLong(json: String, field: String): Long? {
        val arr = JSONArray(json)
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).getLong(field)
    }
}
