package com.example.adaptivellm.storage

import java.io.Closeable

/**
 * Low-level synchronous wrapper над JNI bindings к SQLite + sqlite-vec.
 *
 * **Threading**: SQLite собран с `SQLITE_THREADSAFE=1` (serialized mode) поэтому
 * формально thread-safe, НО для предсказуемого ordering все DB операции должны
 * идти через `dbDispatcher` (см. [MemoryDatabaseHelper]). Прямые вызовы методов
 * этого класса из произвольного потока работать будут, но грозят интерливингом
 * транзакций.
 *
 * **Lifecycle**: singleton ([getInstance]). `open()` идемпотентен (повторный вызов
 * на открытом handle — ошибка), `close()` идемпотентен. Engine .so library
 * загружается в companion init.
 *
 * **Error reporting**: все методы возвращают SQLite return codes (0 = SQLITE_OK,
 * 1+ = error). Использовать [lastError] для текста ошибки. Высокоуровневые
 * suspending обёртки в [MemoryDatabaseHelper] кидают исключения.
 */
class MemoryDatabase private constructor() : Closeable {

    @Volatile private var handle: Long = 0L

    // -------------------- JNI declarations --------------------
    // Все методы — instance methods. JNI symbols matching:
    //   Java_com_example_adaptivellm_storage_MemoryDatabase_<name>
    private external fun nativeDbOpen(path: String): Long
    private external fun nativeDbClose(handle: Long)
    private external fun nativeDbExec(handle: Long, sql: String): Int
    private external fun nativeDbQueryToJson(handle: Long, sql: String): String
    private external fun nativeDbUserVersion(handle: Long): Int
    private external fun nativeDbSetUserVersion(handle: Long, version: Int): Int
    private external fun nativeDbBeginTransaction(handle: Long): Int
    private external fun nativeDbCommit(handle: Long): Int
    private external fun nativeDbRollback(handle: Long): Int
    private external fun nativeDbErrMsg(handle: Long): String
    private external fun nativeSqliteVersion(): String

    // -------------------- Lifecycle --------------------
    fun isOpen(): Boolean = handle != 0L

    /**
     * Opens database at the given path. Creates the file if not exists.
     * Registers sqlite-vec extension on the new connection.
     * Returns true on success.
     * @throws IllegalStateException если уже open (повторный open без close).
     */
    fun open(path: String): Boolean {
        check(handle == 0L) { "MemoryDatabase already open (handle=$handle)" }
        val h = nativeDbOpen(path)
        if (h != 0L) {
            handle = h
            return true
        }
        return false
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeDbClose(h)
        }
    }

    // -------------------- SQL operations --------------------
    /** Execute SQL (DDL, INSERT/UPDATE/DELETE без RETURNING). Возвращает SQLite rc (0=OK). */
    fun exec(sql: String): Int {
        ensureOpen()
        return nativeDbExec(handle, sql)
    }

    /**
     * Execute SELECT, return rows as JSON array of objects:
     * `[{"col1":"val","col2":42},...]`.
     * On error returns string starting with `ERROR: `. For Stage 0-4 — упрощённое
     * API; для hot path (Phase 1 retrieval) добавим cursor-based в Stage 5.
     */
    fun queryToJson(sql: String): String {
        ensureOpen()
        return nativeDbQueryToJson(handle, sql)
    }

    // -------------------- Schema version (migration runner) --------------------
    fun userVersion(): Int {
        ensureOpen()
        return nativeDbUserVersion(handle)
    }

    fun setUserVersion(version: Int): Int {
        ensureOpen()
        return nativeDbSetUserVersion(handle, version)
    }

    // -------------------- Transactions --------------------
    fun beginTransaction(): Int {
        ensureOpen()
        return nativeDbBeginTransaction(handle)
    }

    fun commit(): Int {
        ensureOpen()
        return nativeDbCommit(handle)
    }

    fun rollback(): Int {
        ensureOpen()
        return nativeDbRollback(handle)
    }

    /**
     * Helper для выполнения блока кода внутри transaction. На любой exception
     * делает rollback и пробрасывает; на success — commit. Если коммит/rollback
     * сами падают — логируется, но не пробрасывается (state БД может быть undefined,
     * но процесс не падает).
     */
    inline fun <T> inTransaction(block: () -> T): T {
        val begin = beginTransaction()
        check(begin == SQLITE_OK) { "BEGIN failed: rc=$begin, msg=${lastError()}" }
        val result = try {
            block()
        } catch (e: Throwable) {
            rollback()
            throw e
        }
        val commit = commit()
        check(commit == SQLITE_OK) { "COMMIT failed: rc=$commit, msg=${lastError()}" }
        return result
    }

    // -------------------- Diagnostics --------------------
    fun lastError(): String {
        ensureOpen()
        return nativeDbErrMsg(handle)
    }

    fun sqliteVersion(): String = nativeSqliteVersion()

    private fun ensureOpen() {
        check(handle != 0L) { "MemoryDatabase is not open" }
    }

    companion object {
        const val SQLITE_OK = 0

        @Volatile private var instance: MemoryDatabase? = null

        init {
            // Lib может быть уже загружена через InferenceEngineImpl init (тот же .so).
            // System.loadLibrary идемпотентен — повторный вызов no-op.
            System.loadLibrary("adaptive-llm")
        }

        /**
         * Singleton accessor. Возвращает не-open instance — нужно вызвать [open]
         * (типично делается [MemoryDatabaseHelper.initialize]).
         */
        fun getInstance(): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: MemoryDatabase().also { instance = it }
            }
        }
    }
}
