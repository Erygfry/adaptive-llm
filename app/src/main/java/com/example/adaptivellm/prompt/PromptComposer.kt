package com.example.adaptivellm.prompt

import com.example.adaptivellm.storage.FactsRepository

/**
 * Сборка user-сообщения в B-dirty form (Stage 4 / architecture.md Фаза 2).
 *
 * **Зачем "dirty"**: один system блок (static prompt + summary) стабилен между
 * turn'ами → cache_prompt prefix match'ит весь префикс → re-decode только нового
 * last user message. Если instructions/facts были бы в system блоке, они бы
 * менялись каждый turn (новый retrieval) → префикс инвалидируется → re-decode
 * всего prompt'а. B-dirty даёт ~2× ускорение на prefill (G6 v2 device test).
 *
 * **Формат** (architecture.md Фаза 2):
 * ```
 * [USER INSTRUCTIONS]
 * - {instruction_1}
 * - {instruction_2}
 *
 * [Relevant memories:]
 * - {fact_1} (importance: N)
 * - {fact_2} (importance: N)
 *
 * {user query}
 * ```
 *
 * Блоки опциональны:
 *   - Пустые instructions → блок `[USER INSTRUCTIONS]` пропущен целиком
 *   - Пустые memories → блок `[Relevant memories:]` пропущен целиком
 *
 * **stripDirty** делает обратное — для display'а в UI и для extraction prompt'а
 * в Phase 4 eviction (architecture.md Шаг 4.2 — нужно убрать обёртки из evicted
 * user messages чтобы модель не повторно извлекала их как новые facts).
 */
object PromptComposer {

    const val INSTRUCTIONS_HEADER = "[USER INSTRUCTIONS]"
    const val MEMORIES_HEADER = "[Relevant memories:]"

    /**
     * Собирает dirty form. instructions и memories могут быть пустыми списками —
     * соответствующий блок будет skip'нут.
     *
     * @param userQuery исходный текст пользователя (без обёрток)
     * @param instructions активные instruction-факты для текущего chat'а (Step 1.3)
     * @param memories top-K semantic retrieval результат (Steps 1.1, 1.2)
     */
    fun compose(
        userQuery: String,
        instructions: List<FactsRepository.Fact>,
        memories: List<FactsRepository.Fact>,
    ): String {
        val sb = StringBuilder()
        if (instructions.isNotEmpty()) {
            sb.append(INSTRUCTIONS_HEADER).append('\n')
            for (i in instructions) sb.append("- ").append(i.content).append('\n')
            sb.append('\n')
        }
        if (memories.isNotEmpty()) {
            sb.append(MEMORIES_HEADER).append('\n')
            for (m in memories) {
                sb.append("- ").append(m.content)
                    .append(" (importance: ").append(m.importance).append(")\n")
            }
            sb.append('\n')
        }
        sb.append(userQuery)
        return sb.toString()
    }

    // Anchored к началу строки чтобы false-positive не сматчился если пользователь
    // буквально написал "[Relevant memories:]" в своём сообщении.
    private val INSTRUCTIONS_REGEX = Regex(
        "(?s)^\\Q$INSTRUCTIONS_HEADER\\E[\\s\\S]*?\\n\\n"
    )
    private val MEMORIES_REGEX = Regex(
        "(?s)^\\Q$MEMORIES_HEADER\\E[\\s\\S]*?\\n\\n"
    )

    /**
     * Strip dirty wrappers с начала content. Покрывает все варианты:
     *   - INSTRUCTIONS + memories + query → strip оба → query
     *   - Только INSTRUCTIONS + query (memories пусты) → strip → query
     *   - Только memories + query (instructions пусты) → strip → query
     *   - Чистый query (very early chat) → no match, return as-is
     *
     * Assistant-content не трогаем — там нет наших обёрток.
     */
    fun stripDirty(content: String): String =
        content
            .replace(INSTRUCTIONS_REGEX, "")
            .replace(MEMORIES_REGEX, "")
}
