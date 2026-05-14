package com.example.adaptivellm.storage

import android.content.Context
import android.util.Log

/**
 * Schema migration runner.
 *
 * Реализует hybrid подход из architecture.md (Schema migration: гибридный подход
 * DDL + Kotlin):
 *   - Декларативный DDL в .sql файлах под `app/src/main/assets/db/migrations/NNN_*.sql`
 *   - Императивные data migrations в [DataMigrations] (Kotlin функции)
 *
 * SQL файлы парсятся по маркеру `-- @@STATEMENT_END@@` на отдельной строке (нельзя
 * использовать наивный `split(";")` потому что внутри CREATE TRIGGER ... END;
 * содержатся внутренние `;` которые ломают парсинг).
 *
 * Все statements одного файла выполняются в одной транзакции (atomic upgrade).
 * После успешного применения migration N, выставляется `PRAGMA user_version = N`.
 *
 * **PRAGMA не входят в .sql файлы**:
 *   - `journal_mode = WAL` нельзя менять внутри транзакции → bootstrap-код
 *     (см. [MemoryDatabaseHelper.initialize]).
 *   - `foreign_keys = ON` per-connection → JNI nativeDbOpen.
 *   - `user_version` → setUserVersion после успешного commit.
 */
internal object MigrationRunner {

    private const val TAG = "MigrationRunner"

    /** Маркер для разделения SQL statements (на отдельной строке). */
    const val STATEMENT_DELIMITER = "-- @@STATEMENT_END@@"

    /**
     * Mapping schema version → SQL asset path. CODE_VERSION = max key.
     * 001 = initial schema (architecture.md, SQL-схема section).
     */
    private val MIGRATION_FILES: Map<Int, String> = mapOf(
        1 to "db/migrations/001_init.sql",
        // 2 to "db/migrations/002_xxx.sql",
    )

    /**
     * Optional Kotlin-side data migrations. Run AFTER the corresponding SQL DDL
     * within the same transaction. Empty в MVP.
     */
    private val DATA_MIGRATIONS: Map<Int, (MemoryDatabase) -> Unit> = mapOf(
        // 2 to ::migrateDataV1ToV2,
    )

    /** Текущая целевая версия схемы (max key из MIGRATION_FILES). */
    val CODE_VERSION: Int = MIGRATION_FILES.keys.max()

    /**
     * Migrates the database from its current `user_version` to [CODE_VERSION].
     * No-op if already at CODE_VERSION.
     *
     * @throws IllegalStateException если current > CODE_VERSION (downgrade
     *   detected — обычно значит installed APK старее DB файла; не поддерживаем).
     * @throws RuntimeException на любую SQL-ошибку при migration'е.
     */
    fun migrate(db: MemoryDatabase, context: Context) {
        val current = db.userVersion()
        Log.i(TAG, "Current schema version: $current, target: $CODE_VERSION")

        if (current == CODE_VERSION) {
            Log.i(TAG, "Schema up to date, no migration needed")
            return
        }
        check(current <= CODE_VERSION) {
            "Database schema downgrade detected (user_version=$current, " +
            "code expects $CODE_VERSION). Refusing to run — typically means " +
            "older APK opening newer DB file. Clear app data or upgrade APK."
        }

        for (target in (current + 1)..CODE_VERSION) {
            applyMigration(db, context, target)
        }
        Log.i(TAG, "Migration complete: schema now at version $CODE_VERSION")
    }

    private fun applyMigration(db: MemoryDatabase, context: Context, version: Int) {
        Log.i(TAG, "Applying migration to v$version")
        val asset = MIGRATION_FILES[version]
            ?: error("No migration file for version $version")

        val sqlText = try {
            context.assets.open(asset).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            error("Failed to read migration asset '$asset': ${e.message}")
        }

        // Split по строкам где ВСЯ строка (после trim) равна маркеру. Без этого
        // упоминание маркера в комментарии (например в header'е файла) было бы
        // ложно сматчено как разделитель — SQL ломался бы на куски в произвольных
        // местах.
        val statements = sqlText.lineSequence()
            .fold(mutableListOf(StringBuilder())) { acc, line ->
                if (line.trim() == STATEMENT_DELIMITER) {
                    acc.add(StringBuilder())
                } else {
                    acc.last().append(line).append('\n')
                }
                acc
            }
            .map { it.toString().trim() }
            .filter { it.isNotBlank() }

        Log.i(TAG, "Migration v$version: ${statements.size} statements from '$asset'")

        // Все statements одного migration файла + data migration → одна транзакция.
        db.inTransaction {
            for ((idx, stmt) in statements.withIndex()) {
                val rc = db.exec(stmt)
                if (rc != MemoryDatabase.SQLITE_OK) {
                    val errMsg = db.lastError()
                    error(
                        "Migration v$version failed at statement $idx (rc=$rc): " +
                        "${errMsg}\n--- SQL ---\n${stmt.take(500)}"
                    )
                }
            }
            // Data migration после DDL (если есть)
            DATA_MIGRATIONS[version]?.let { dataFn ->
                Log.i(TAG, "Running data migration for v$version")
                dataFn(db)
            }
        }

        // user_version после успешного commit (PRAGMA не работает внутри транзакции
        // для некоторых SQLite сборок, плюс семантически — версия меняется
        // только после атомарного применения всех изменений).
        val rc = db.setUserVersion(version)
        check(rc == MemoryDatabase.SQLITE_OK) {
            "Failed to set user_version=$version: rc=$rc, msg=${db.lastError()}"
        }
        Log.i(TAG, "Migration v$version applied successfully")
    }
}
