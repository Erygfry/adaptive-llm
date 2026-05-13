package com.example.adaptivellm.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-level помощник для bootstrapping и доступа к memory database.
 *
 * **Bootstrap flow** (см. architecture.md, Bootstrap section):
 *   1. Определить путь к DB-файлу (`/data/data/<package>/databases/memory.db`)
 *   2. First-run или subsequent: открыть DB
 *   3. WAL journal mode — на first-run только, до открытия транзакций
 *   4. Запустить [MigrationRunner.migrate] для применения миграций
 *   5. БД готова к работе
 *
 * **Threading**: все методы suspend, выполняются на [dbDispatcher] —
 * limitedParallelism(1) IO dispatcher. Гарантирует serialized доступ к БД даже
 * при concurrent suspend-вызовах из разных корутин. Аналогично паттерну
 * llamaDispatcher в InferenceEngineImpl.
 *
 * **Singleton lifecycle**: одна инициализация на процесс. Повторный вызов
 * [initialize] возвращает существующий instance.
 */
object MemoryDatabaseHelper {

    private const val TAG = "MemoryDbHelper"
    private const val DB_FILENAME = "memory.db"

    @OptIn(ExperimentalCoroutinesApi::class)
    val dbDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Coroutine scope для background задач связанных с БД (cleanup, eviction). */
    val scope = CoroutineScope(dbDispatcher + SupervisorJob())

    @Volatile private var initialized: Boolean = false

    /**
     * Initialize the memory database. Idempotent — second+ calls return immediately.
     * MUST be called before any other DB operation.
     *
     * Runs on [dbDispatcher]. Полное время — секунды (создание schema, ~30 statements
     * в 001_init.sql), на subsequent runs — миллисекунды (PRAGMA check, migration
     * no-op).
     */
    suspend fun initialize(context: Context) = withContext(dbDispatcher) {
        if (initialized) {
            Log.i(TAG, "initialize: already initialized")
            return@withContext
        }

        val dbPath = resolveDbPath(context)
        val isFirstRun = !File(dbPath).exists()
        Log.i(TAG, "initialize: dbPath=$dbPath, firstRun=$isFirstRun")

        val db = MemoryDatabase.getInstance()
        val opened = db.open(dbPath)
        check(opened) { "Failed to open DB at $dbPath" }
        Log.i(TAG, "initialize: opened, SQLite ${db.sqliteVersion()}")

        // WAL journal mode — критично для надёжности при OOM kill и
        // concurrent reads. PRAGMA нельзя менять внутри транзакции, поэтому
        // выставляем ДО migration runner'а. Mode персистентен на уровне DB-файла,
        // на subsequent runs повторять не обязательно — но safer it idempotent.
        val walRc = db.exec("PRAGMA journal_mode = WAL;")
        check(walRc == MemoryDatabase.SQLITE_OK) {
            "Failed to set WAL journal mode: rc=$walRc, msg=${db.lastError()}"
        }

        // foreign_keys = ON выставляется в native при open(), per-connection.

        // Run migrations (no-op if up to date).
        try {
            MigrationRunner.migrate(db, context)
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            db.close()
            throw e
        }

        initialized = true
        Log.i(TAG, "initialize: complete, schema v${MigrationRunner.CODE_VERSION}")
    }

    /**
     * Возвращает open instance. [initialize] должен быть вызван до этого.
     * Не suspend — для использования внутри других suspend-функций на dbDispatcher.
     */
    fun database(): MemoryDatabase {
        check(initialized) { "MemoryDatabaseHelper.initialize() must be called first" }
        return MemoryDatabase.getInstance()
    }

    /**
     * Закрывает БД. Вызывается на app shutdown. После этого все операции с БД
     * упадут с IllegalStateException.
     */
    suspend fun shutdown() = withContext(dbDispatcher) {
        if (!initialized) return@withContext
        try {
            MemoryDatabase.getInstance().close()
            Log.i(TAG, "shutdown: closed")
        } catch (e: Exception) {
            Log.w(TAG, "shutdown: close failed", e)
        } finally {
            initialized = false
        }
    }

    private fun resolveDbPath(context: Context): String {
        // Standard Android databases directory:
        // /data/data/<package>/databases/<filename>
        val dbDir = File(context.applicationInfo.dataDir, "databases")
        if (!dbDir.exists()) {
            val created = dbDir.mkdirs()
            Log.i(TAG, "resolveDbPath: created dir $dbDir (success=$created)")
        }
        return File(dbDir, DB_FILENAME).absolutePath
    }
}
