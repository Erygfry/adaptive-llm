-- ============================================================================
-- Memory architecture v1 — initial schema
-- ============================================================================
-- Соответствует architecture.md, секция "SQL-схема".
--
-- Format: каждый statement отделяется специальным маркером на отдельной строке
-- (см. MigrationRunner.STATEMENT_DELIMITER). Это нужно потому что наивный
-- split на точку с запятой ломается на CREATE TRIGGER ... BEGIN ... END;
-- (внутренние точки с запятой в теле триггера).
--
-- PRAGMA НЕ входят в этот файл:
--   - journal_mode = WAL → bootstrap-код до открытия первой транзакции
--   - foreign_keys = ON → per-connection через JNI nativeDbOpen
--   - user_version → MigrationRunner explicit setUserVersion после commit
--
-- ============================================================================

-- ── Таблицы ─────────────────────────────────────────────────────────────────

CREATE TABLE chats (
    id INTEGER PRIMARY KEY,
    title TEXT,                                       -- автогенерируется из первого user message
    created_at INTEGER NOT NULL,
    last_active_at INTEGER NOT NULL,                  -- для LRU sorting и cleanup KV files
    has_kv_cache INTEGER NOT NULL DEFAULT 0           -- 0 = нет файла /data/chats/{id}/kv_cache.bin, 1 = есть
        CHECK(has_kv_cache IN (0,1)),
    kv_cache_last_message_id INTEGER NOT NULL DEFAULT 0,  -- id последнего сообщения, декодированного в kv_cache.bin
                                                          -- (0 = файл отражает [system+summary]; tail re-decode = все messages anchor+1..)
    eviction_state TEXT NOT NULL DEFAULT 'idle'       -- 'idle' | 'in_progress'
        CHECK(eviction_state IN ('idle','in_progress'))
);
-- @@STATEMENT_END@@

CREATE TABLE summary (
    chat_id INTEGER PRIMARY KEY                       -- одна summary на чат, гарантировано схемой
        REFERENCES chats(id) ON DELETE CASCADE,
    user_profile TEXT DEFAULT '',
    ongoing_topics TEXT DEFAULT '',
    key_decisions TEXT DEFAULT '',
    pending_items TEXT DEFAULT '',
    anchor_message_id INTEGER DEFAULT 0,
    token_count INTEGER DEFAULT 0,
    merge_count INTEGER DEFAULT 0,
    updated_at INTEGER DEFAULT 0                      -- 0 = ни один eviction ещё не выполнен
);
-- @@STATEMENT_END@@

CREATE TABLE facts (
    id INTEGER PRIMARY KEY,
    content TEXT NOT NULL,
    keywords TEXT,                                    -- JSON array: ["работа", "Сбер", "карьера"]
    context TEXT,                                     -- LLM-generated context description
    category TEXT NOT NULL CHECK(category IN (
        'personal_info','preference','goal',
        'instruction','event','relationship'
    )),
    importance INTEGER NOT NULL CHECK(importance BETWEEN 1 AND 10),
    access_count INTEGER DEFAULT 0,
    last_access INTEGER,
    valid_from INTEGER NOT NULL,
    valid_to INTEGER,                                 -- NULL = актуален
    superseded_by INTEGER REFERENCES facts(id) ON DELETE SET NULL,  -- audit chain обрывается при CASCADE удалении предка
    chat_id INTEGER REFERENCES chats(id) ON DELETE CASCADE,  -- NULL = глобальный, локальные удаляются с чатом
    source_message_id INTEGER,                        -- без FK constraint в MVP; обрабатывается на уровне приложения
    event_date INTEGER,                               -- unix timestamp parsed event date for category='event' (NULL otherwise)
    created_at INTEGER NOT NULL,
    CHECK (category = 'event' OR event_date IS NULL)  -- event_date только для events
);
-- @@STATEMENT_END@@

-- FTS5 external content table с trigram tokenizer.
-- Trigram нужен для русского морфологического матчинга без stemmer'а: MATCH 'москве'
-- матчит docs содержащие подстроку "москве" (на trigram уровне). Не полная
-- лемматизация но достаточно для substring queries ("Kotlin" → goal fact с "Kotlin",
-- "Москв" → "Москве/Москва" подстроки). Лемматизация — future work.
-- Синхронизация с facts через triggers (см. ниже).
CREATE VIRTUAL TABLE facts_fts USING fts5(
    content, keywords, context,
    content='facts', content_rowid='id',
    tokenize='trigram'
);
-- @@STATEMENT_END@@

-- sqlite-vec virtual table с partition key (category) и metadata column (valid_to).
-- ВАЖНО: sqlite-vec по default rejects NULL в metadata column'е. Чтобы избежать
-- рискованной migration (DROP TABLE на vec0 падает), используем sentinel valid_to=0
-- = «валидный». Invalidated rows удаляются триггером facts_invalidate (DELETE),
-- так что колонка в facts_vec фактически redundant — но оставлена под architecture
-- compatibility. Searches не фильтруют по valid_to (trigger handles).
CREATE VIRTUAL TABLE facts_vec USING vec0(
    fact_id INTEGER PRIMARY KEY,
    category TEXT PARTITION KEY,                      -- 6 значений → 6 partitions, быстрый conflict check
    valid_to INTEGER,                                 -- sentinel: всегда 0 (см. FactsRepository.insertFact)
    embedding FLOAT[384]
);
-- @@STATEMENT_END@@

CREATE TABLE messages (
    id INTEGER PRIMARY KEY,
    chat_id INTEGER NOT NULL
        REFERENCES chats(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK(role IN ('user','assistant')),
    content TEXT NOT NULL,
    token_count INTEGER NOT NULL,                     -- обязательное для budget calculations
    created_at INTEGER NOT NULL
);
-- @@STATEMENT_END@@

-- ── Индексы ─────────────────────────────────────────────────────────────────

CREATE INDEX idx_facts_valid_chat ON facts(valid_to, chat_id);
-- @@STATEMENT_END@@

CREATE INDEX idx_facts_category ON facts(category);
-- @@STATEMENT_END@@

CREATE INDEX idx_facts_created ON facts(created_at);
-- @@STATEMENT_END@@

CREATE INDEX idx_messages_chat ON messages(chat_id);
-- @@STATEMENT_END@@

CREATE INDEX idx_chats_active ON chats(has_kv_cache, last_active_at);
-- @@STATEMENT_END@@

-- ── Triggers ────────────────────────────────────────────────────────────────

-- FTS5 sync (стандартный паттерн external content):
CREATE TRIGGER facts_ai AFTER INSERT ON facts BEGIN
    INSERT INTO facts_fts(rowid, content, keywords, context)
    VALUES (new.id, new.content, new.keywords, new.context);
END;
-- @@STATEMENT_END@@

CREATE TRIGGER facts_ad AFTER DELETE ON facts BEGIN
    INSERT INTO facts_fts(facts_fts, rowid, content, keywords, context)
    VALUES ('delete', old.id, old.content, old.keywords, old.context);
END;
-- @@STATEMENT_END@@

-- При UPDATE на facts — re-sync FTS5 row.
-- WHEN clause: trigger срабатывает ТОЛЬКО если изменились content/keywords/context.
-- Метаданные типа access_count/last_access (Шаг 1.4 reinforcement) не вызывают
-- ненужный FTS re-sync.
CREATE TRIGGER facts_au AFTER UPDATE ON facts
WHEN OLD.content IS NOT NEW.content
  OR OLD.keywords IS NOT NEW.keywords
  OR OLD.context IS NOT NEW.context
BEGIN
    INSERT INTO facts_fts(facts_fts, rowid, content, keywords, context)
    VALUES ('delete', old.id, old.content, old.keywords, old.context);
    INSERT INTO facts_fts(rowid, content, keywords, context)
    VALUES (new.id, new.content, new.keywords, new.context);
END;
-- @@STATEMENT_END@@

-- При invalidation факта (valid_to NULL → NOT NULL) убираем embedding из vec индекса.
-- Факт остаётся в `facts` для аудита, но не появляется в поиске и не занимает место в индексе.
CREATE TRIGGER facts_invalidate AFTER UPDATE OF valid_to ON facts
WHEN OLD.valid_to IS NULL AND NEW.valid_to IS NOT NULL
BEGIN
    DELETE FROM facts_vec WHERE fact_id = OLD.id;
END;
-- @@STATEMENT_END@@

-- При полном удалении факта (CASCADE при удалении чата) — также убрать из vec
CREATE TRIGGER facts_delete_vec AFTER DELETE ON facts BEGIN
    DELETE FROM facts_vec WHERE fact_id = OLD.id;
END;
-- @@STATEMENT_END@@
