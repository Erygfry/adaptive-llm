package com.example.adaptivellm.eviction

/**
 * GBNF grammars for conflict resolution LLM calls (Stage 6.2, architecture.md
 * § Шаг 4.4).
 *
 * Два отдельных prompt'а — общий («NEW vs EXISTING»: ADD / UPDATE / NOOP) и
 * специальный для category='instruction' («какая из активных instruction
 * superseded'нута новой»: id или "none").
 */
object ConflictGbnf {

    /**
     * Decision для cross-category conflict resolution.
     *   - ADD    — оба факта валидны независимо
     *   - UPDATE — новый supersede'ит старый
     *   - NOOP   — duplicate / nothing to do
     */
    val DECISION_GRAMMAR: String = """
root ::= "ADD" | "UPDATE" | "NOOP"
""".trimIndent()

    /**
     * Instruction conflict resolution: id вытесняемой instruction или "none".
     * Ограничиваем id максимум 4 цифрами (до 9999 — far более чем production
     * soft cap 15 active instructions).
     */
    val INSTRUCTION_GRAMMAR: String = """
root ::= "none" | digit | digit digit | digit digit digit | digit digit digit digit
digit ::= [0-9]
""".trimIndent()

    /**
     * Category validation grammar. Используется для второго LLM-вызова, который
     * проверяет факты с category="instruction" — действительно ли это команда
     * ассистенту, или модель ошиблась и это preference / personal_info / etc.
     *
     * Output: ровно одно из 6 названий категорий.
     */
    val CATEGORY_GRAMMAR: String = """
root ::= "instruction" | "preference" | "personal_info" | "goal" | "event" | "relationship"
""".trimIndent()
}
