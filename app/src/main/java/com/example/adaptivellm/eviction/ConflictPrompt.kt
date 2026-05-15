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
