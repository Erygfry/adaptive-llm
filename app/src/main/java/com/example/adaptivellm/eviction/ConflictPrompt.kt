package com.example.adaptivellm.eviction

import com.example.adaptivellm.storage.FactsRepository

/**
 * Prompts for conflict resolution LLM calls (Stage 6.2 / architecture.md § Шаг 4.4).
 */
object ConflictPrompt {

    /**
     * General decision prompt: NEW vs EXISTING → ADD | UPDATE | NOOP.
     * Используется для всех категорий кроме `instruction` (там — отдельный
     * [buildInstructionConflict]).
     */
    fun buildDecision(
        existing: FactsRepository.Fact,
        newContent: String,
        newCategory: String,
        newImportance: Int,
    ): String = """
You compare two facts about the same user.

EXISTING fact (already stored):
"${existing.content}"
(category: ${existing.category}, importance: ${existing.importance})

NEW fact (just extracted from recent conversation):
"$newContent"
(category: $newCategory, importance: $newImportance)

Decide one of three options:
- ADD: both facts are independently valid (e.g., two distinct preferences, two skills)
- UPDATE: NEW supersedes EXISTING — they describe the SAME aspect of the user but the new value replaces the old (e.g., job change, location change, status change)
- NOOP: NEW is a duplicate or trivial rephrasing of EXISTING, nothing to store

Output exactly one word: ADD, UPDATE, or NOOP.
""".trimIndent()

    /**
     * Category validation для факта, который extraction LLM пометил как
     * `instruction`. Маленькие модели часто промахиваются и записывают в
     * `instruction` обычные personal_info / preference / goal факты («User
     * lives in Moscow», «User likes blue»). Этот prompt просит модель
     * перепроверить категорию с явными определениями и примерами.
     *
     * Output ограничен через [ConflictGbnf.CATEGORY_GRAMMAR] — ровно одно из
     * 6 названий категории.
     */
    fun buildCategoryValidation(factContent: String): String = """
You are validating the category assigned to a fact extracted from a conversation.

A fact was classified as "instruction". Instructions are EXPLICIT commands the
user gave about how the assistant must behave. They are behavior RULES, not
descriptions of the user.

Valid examples of "instruction":
  - "Always respond in English."
  - "Don't use emojis."
  - "Be brief, no long explanations."
  - "Address user informally with 'ты'."
  - "Respond only in formal style."

INVALID examples (these should be in OTHER categories):
  - "User likes brief responses" → preference (it's a taste, not a command)
  - "User lives in Moscow" → personal_info (it's a fact about who user IS)
  - "User is learning Spanish" → goal (it's an activity)
  - "User's daughter Anna is 7" → relationship (it's about a connection)
  - "User flies to Tokyo on 2026-05-25" → event (dated occurrence)
  - "User mentioned Python" → not a fact at all, skip

The fact in question:
"$factContent"

What is the CORRECT category for this fact? Reply with EXACTLY one word:
instruction, preference, personal_info, goal, event, or relationship.
""".trimIndent()

    /**
     * Instruction conflict prompt: вытесняет ли новая instruction какую-то
     * из активных? Возвращает id вытесняемой или "none".
     *
     * Architecture mandates этот специальный path для instructions потому что
     * семантически противоречивые инструкции часто имеют далёкие embeddings
     * («отвечай на английском» vs «отвечай на русском»). Cross-category vec
     * search их не поймает как близкие.
     */
    fun buildInstructionConflict(
        activeInstructions: List<FactsRepository.Fact>,
        newContent: String,
    ): String {
        val numbered = activeInstructions.joinToString("\n") { f ->
            "#${f.id}: \"${f.content}\""
        }
        return """
You manage user communication style instructions for an AI assistant.

EXISTING active instructions:
$numbered

NEW instruction extracted from recent conversation:
"$newContent"

Identify which EXISTING instruction (by its #id number) is directly contradicted, made obsolete, or replaced by the NEW instruction. Two instructions can coexist if they address different aspects (e.g., language vs verbosity vs tone).

Output:
- the id number of the conflicting existing instruction (e.g., 42)
- or "none" if no conflict exists
""".trimIndent()
    }
}
