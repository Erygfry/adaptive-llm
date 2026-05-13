// =============================================================================
// native_db.cpp — JNI bindings для SQLite + sqlite-vec (memory architecture)
// -----------------------------------------------------------------------------
// API минималистичный, покрывает Stage 0 (migration runner, schema verify) и
// базовый CRUD для Stage 1-4. На Stage 5 (retrieval hot path) дополним
// prepared-statements и cursor-iteration API для эмбеддингов / больших result
// sets.
//
// Каждая открытая БД получает sqlite-vec extension зарегистрированный через
// `sqlite3_vec_init` сразу после `sqlite3_open_v2`. Это даёт доступ к virtual
// table `vec0` без необходимости `sqlite3_load_extension` (который мы отключили
// через SQLITE_OMIT_LOAD_EXTENSION для безопасности).
// =============================================================================

#include <android/log.h>
#include <jni.h>
#include <string>
#include <sstream>
#include <vector>

#include "sqlite3.h"
#include "sqlite-vec.h"

#define TAG_DB "AdaptiveLLM-DB"
#define LOGI_DB(...) __android_log_print(ANDROID_LOG_INFO, TAG_DB, __VA_ARGS__)
#define LOGE_DB(...) __android_log_print(ANDROID_LOG_ERROR, TAG_DB, __VA_ARGS__)
#define LOGW_DB(...) __android_log_print(ANDROID_LOG_WARN, TAG_DB, __VA_ARGS__)

// Конвертация jlong handle ↔ sqlite3* указатель.
static inline sqlite3* db_from_handle(jlong handle) {
    return reinterpret_cast<sqlite3*>(handle);
}
static inline jlong handle_from_db(sqlite3* db) {
    return reinterpret_cast<jlong>(db);
}

// Безопасное чтение jstring → std::string с handle null'а.
static std::string jstr_to_std(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s = c ? c : "";
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

// Экранирование строки для JSON output (минимальное, для query results).
// Покрывает: backslash, double-quote, control chars. Достаточно для наших
// типов данных (factS content, summaries, etc). Не полный RFC 8259, но
// безопасное подмножество.
static void json_escape(std::string& out, const char* s) {
    if (!s) { out += "null"; return; }
    out += '"';
    for (const unsigned char* p = (const unsigned char*)s; *p; p++) {
        switch (*p) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (*p < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", *p);
                    out += buf;
                } else {
                    out += (char)*p;
                }
        }
    }
    out += '"';
}

extern "C" {

// -----------------------------------------------------------------------------
// nativeDbOpen — открывает БД по path, регистрирует sqlite-vec, выставляет
// необходимые PRAGMA, возвращает jlong handle. Возвращает 0 при ошибке.
// -----------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbOpen(
    JNIEnv* env, jobject, jstring jPath)
{
    std::string path = jstr_to_std(env, jPath);
    if (path.empty()) {
        LOGE_DB("nativeDbOpen: empty path");
        return 0;
    }

    sqlite3* db = nullptr;
    int rc = sqlite3_open_v2(
        path.c_str(), &db,
        SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX,
        nullptr);
    if (rc != SQLITE_OK) {
        LOGE_DB("nativeDbOpen: sqlite3_open_v2 failed (rc=%d): %s",
                rc, sqlite3_errmsg(db));
        if (db) sqlite3_close(db);
        return 0;
    }

    // Регистрация sqlite-vec extension. Должна быть выполнена ДО любого CREATE
    // VIRTUAL TABLE USING vec0 (т.е. до migration runner'а).
    char* errmsg = nullptr;
    rc = sqlite3_vec_init(db, &errmsg, nullptr);
    if (rc != SQLITE_OK) {
        LOGE_DB("nativeDbOpen: sqlite3_vec_init failed (rc=%d): %s",
                rc, errmsg ? errmsg : "unknown");
        if (errmsg) sqlite3_free(errmsg);
        sqlite3_close(db);
        return 0;
    }

    // Per-connection PRAGMA. Архитектура (см. architecture.md, SQL-схема секция)
    // требует foreign_keys=ON на каждой connection (SQLite не enforce'ит по
    // умолчанию). journal_mode=WAL устанавливается отдельно через bootstrap до
    // первой транзакции (Kotlin сторона).
    rc = sqlite3_exec(db, "PRAGMA foreign_keys = ON;", nullptr, nullptr, &errmsg);
    if (rc != SQLITE_OK) {
        LOGW_DB("nativeDbOpen: PRAGMA foreign_keys failed: %s",
                errmsg ? errmsg : "?");
        if (errmsg) { sqlite3_free(errmsg); errmsg = nullptr; }
    }

    LOGI_DB("nativeDbOpen: opened '%s' (sqlite %s, vec %s)",
            path.c_str(), sqlite3_libversion(), SQLITE_VEC_VERSION);
    return handle_from_db(db);
}

// -----------------------------------------------------------------------------
// nativeDbClose — закрывает БД. Idempotent (safe для нулевого handle).
// -----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbClose(
    JNIEnv*, jobject, jlong handle)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) return;
    int rc = sqlite3_close_v2(db);
    if (rc != SQLITE_OK) {
        LOGW_DB("nativeDbClose: close_v2 returned %d", rc);
    } else {
        LOGI_DB("nativeDbClose: closed");
    }
}

// -----------------------------------------------------------------------------
// nativeDbExec — выполняет SQL (DDL или DML без result rows). Поддерживает
// multi-statement через sqlite3_exec. Возвращает SQLite return code (0=OK).
// -----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbExec(
    JNIEnv* env, jobject, jlong handle, jstring jSql)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) {
        LOGE_DB("nativeDbExec: null db handle");
        return SQLITE_MISUSE;
    }
    std::string sql = jstr_to_std(env, jSql);
    if (sql.empty()) return SQLITE_OK;

    char* errmsg = nullptr;
    int rc = sqlite3_exec(db, sql.c_str(), nullptr, nullptr, &errmsg);
    if (rc != SQLITE_OK) {
        LOGE_DB("nativeDbExec: rc=%d, msg=%s, sql=%.200s",
                rc, errmsg ? errmsg : "?", sql.c_str());
        if (errmsg) sqlite3_free(errmsg);
    }
    return rc;
}

// -----------------------------------------------------------------------------
// nativeDbQueryToJson — выполняет SELECT, возвращает результат как JSON
// массив объектов. ВНИМАНИЕ: для Stage 0 / 1-4. Не подходит для больших
// result set'ов или blob columns — везде string-encoded values.
// Формат: '[{"col1":"val1","col2":42},{"col1":"val3","col2":null}]'
// На ошибке возвращает строку начинающуюся с "ERROR: ".
// -----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbQueryToJson(
    JNIEnv* env, jobject, jlong handle, jstring jSql)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) return env->NewStringUTF("ERROR: null db handle");

    std::string sql = jstr_to_std(env, jSql);
    if (sql.empty()) return env->NewStringUTF("[]");

    sqlite3_stmt* stmt = nullptr;
    int rc = sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        std::string err = "ERROR: prepare failed: ";
        err += sqlite3_errmsg(db);
        LOGE_DB("nativeDbQueryToJson: %s (sql=%.200s)", err.c_str(), sql.c_str());
        return env->NewStringUTF(err.c_str());
    }

    std::string out;
    out += '[';
    bool first_row = true;
    int n_cols = sqlite3_column_count(stmt);

    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        if (!first_row) out += ',';
        first_row = false;
        out += '{';
        for (int i = 0; i < n_cols; i++) {
            if (i > 0) out += ',';
            const char* col_name = sqlite3_column_name(stmt, i);
            json_escape(out, col_name);
            out += ':';
            int col_type = sqlite3_column_type(stmt, i);
            switch (col_type) {
                case SQLITE_INTEGER: {
                    char buf[32];
                    snprintf(buf, sizeof(buf), "%lld",
                             (long long)sqlite3_column_int64(stmt, i));
                    out += buf;
                    break;
                }
                case SQLITE_FLOAT: {
                    char buf[32];
                    snprintf(buf, sizeof(buf), "%.17g",
                             sqlite3_column_double(stmt, i));
                    out += buf;
                    break;
                }
                case SQLITE_TEXT: {
                    const char* s = (const char*)sqlite3_column_text(stmt, i);
                    json_escape(out, s);
                    break;
                }
                case SQLITE_BLOB: {
                    // Stage 0 не работает с blob'ами напрямую — placeholder "<blob>"
                    // чтобы запросы вроде SELECT * FROM facts_vec не падали.
                    int n = sqlite3_column_bytes(stmt, i);
                    char buf[32];
                    snprintf(buf, sizeof(buf), "\"<blob:%dB>\"", n);
                    out += buf;
                    break;
                }
                case SQLITE_NULL:
                default:
                    out += "null";
                    break;
            }
        }
        out += '}';
    }
    sqlite3_finalize(stmt);

    if (rc != SQLITE_DONE) {
        std::string err = "ERROR: step failed: ";
        err += sqlite3_errmsg(db);
        LOGE_DB("nativeDbQueryToJson: %s", err.c_str());
        return env->NewStringUTF(err.c_str());
    }
    out += ']';
    return env->NewStringUTF(out.c_str());
}

// -----------------------------------------------------------------------------
// nativeDbUserVersion / nativeDbSetUserVersion — для migration runner. PRAGMA
// user_version — это int в БД-файле, обозначает schema version.
// -----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbUserVersion(
    JNIEnv*, jobject, jlong handle)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) return -1;
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, "PRAGMA user_version;", -1, &stmt, nullptr) != SQLITE_OK) {
        return -1;
    }
    int version = -1;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        version = sqlite3_column_int(stmt, 0);
    }
    sqlite3_finalize(stmt);
    return version;
}

JNIEXPORT jint JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbSetUserVersion(
    JNIEnv*, jobject, jlong handle, jint version)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) return SQLITE_MISUSE;
    // PRAGMA user_version не принимает параметры через bind, нужно literal.
    char sql[64];
    snprintf(sql, sizeof(sql), "PRAGMA user_version = %d;", (int)version);
    char* errmsg = nullptr;
    int rc = sqlite3_exec(db, sql, nullptr, nullptr, &errmsg);
    if (rc != SQLITE_OK) {
        LOGE_DB("nativeDbSetUserVersion: %s", errmsg ? errmsg : "?");
        if (errmsg) sqlite3_free(errmsg);
    }
    return rc;
}

// -----------------------------------------------------------------------------
// Transactions. BEGIN IMMEDIATE acquires write lock сразу (вместо DEFERRED
// которое только при первом WRITE) — это нужно для migration runner и Phase 4
// eviction чтобы избежать "database is locked" в multi-thread сценариях.
// -----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbBeginTransaction(
    JNIEnv*, jobject, jlong handle)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) return SQLITE_MISUSE;
    char* errmsg = nullptr;
    int rc = sqlite3_exec(db, "BEGIN IMMEDIATE;", nullptr, nullptr, &errmsg);
    if (rc != SQLITE_OK) {
        LOGE_DB("BEGIN IMMEDIATE: %s", errmsg ? errmsg : "?");
        if (errmsg) sqlite3_free(errmsg);
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbCommit(
    JNIEnv*, jobject, jlong handle)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) return SQLITE_MISUSE;
    char* errmsg = nullptr;
    int rc = sqlite3_exec(db, "COMMIT;", nullptr, nullptr, &errmsg);
    if (rc != SQLITE_OK) {
        LOGE_DB("COMMIT: %s", errmsg ? errmsg : "?");
        if (errmsg) sqlite3_free(errmsg);
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbRollback(
    JNIEnv*, jobject, jlong handle)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) return SQLITE_MISUSE;
    char* errmsg = nullptr;
    int rc = sqlite3_exec(db, "ROLLBACK;", nullptr, nullptr, &errmsg);
    if (rc != SQLITE_OK) {
        LOGE_DB("ROLLBACK: %s", errmsg ? errmsg : "?");
        if (errmsg) sqlite3_free(errmsg);
    }
    return rc;
}

// -----------------------------------------------------------------------------
// nativeDbErrMsg — возвращает текст последней ошибки на этой connection. Для
// диагностики в логах или передачи в exception message на Kotlin стороне.
// -----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeDbErrMsg(
    JNIEnv* env, jobject, jlong handle)
{
    sqlite3* db = db_from_handle(handle);
    if (!db) return env->NewStringUTF("(no db)");
    return env->NewStringUTF(sqlite3_errmsg(db));
}

// -----------------------------------------------------------------------------
// nativeSqliteVersion — diagnostic helper, возвращает версию SQLite библиотеки.
// Можно убрать после Stage 0.5 если не пригодится.
// -----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_example_adaptivellm_storage_MemoryDatabase_nativeSqliteVersion(
    JNIEnv* env, jobject)
{
    return env->NewStringUTF(sqlite3_libversion());
}

} // extern "C"
