package com.example.adaptivellm.eviction

import com.example.adaptivellm.prompt.PromptComposer
import com.example.adaptivellm.storage.ChatRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Builds the extraction prompt для eviction LLM call (architecture.md § Шаг 4.2).
 * Combined TASK 1 (update summary) + TASK 2 (extract facts) в одном вызове.
 *
 * **Evicted user messages** проходят через [PromptComposer.stripDirty] — нужно
 * убрать обёртки `[USER INSTRUCTIONS]` / `[Relevant memories:]`, иначе модель
 * повторно извлечёт их как новые instruction-факты на каждом evicted сообщении
 * (architecture.md § «Подготовка evicted_messages — strip dirty wrappers»).
 *
 * Output модели ограничен через [ExtractionGbnf.GRAMMAR] — гарантированно
 * валидный JSON. Thinking mode = NEVER (Phase 4 systemic calls).
 */
object ExtractionPrompt {

    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE  // "YYYY-MM-DD"

    /**
     * @param summaryCap target размер итогового summary в токенах
     *   (architecture.md: 500/700/1000 для MID/UPPER_MID/HIGH)
     * @param summary текущий summary (если впервые eviction — секции пустые)
     * @param evictedMessages список evicted сообщений (anchor+1..cutoff)
     */
    fun build(
        summaryCap: Int,
        summary: ChatRepository.Summary,
        evictedMessages: List<ChatRepository.MessageRow>,
    ): String {
        val today = LocalDate.now().format(ISO_DATE)
        val evictedText = formatEvictedMessages(evictedMessages)

        return """
You are a conversation memory manager.

TASK 1: Update the conversation summary.
Each section has a strict token budget. Total summary must be ≤ $summaryCap tokens.
If a section has no new information — keep it unchanged.
If information is outdated — update or clear the section.
If a pending item was resolved — remove it from PENDING, add result to KEY DECISIONS.

Current summary (covered up to message #${summary.anchorMessageId}):
[USER PROFILE] ${summary.userProfile.ifBlank { "(empty)" }}
[ONGOING TOPICS] ${summary.ongoingTopics.ifBlank { "(empty)" }}
[KEY DECISIONS] ${summary.keyDecisions.ifBlank { "(empty)" }}
[PENDING ITEMS] ${summary.pendingItems.ifBlank { "(empty)" }}

New messages being evicted (messages #${summary.anchorMessageId + 1} to #${evictedMessages.lastOrNull()?.id ?: summary.anchorMessageId}):
$evictedText

TASK 2: Extract important facts from the evicted messages.
Rules:
- Replace ALL pronouns with actual names (he/she/they → specific name)
- Convert relative dates to absolute (today is $today)
- Skip greetings, small talk, "ok", "thanks", general questions
- Each fact must be self-contained and understandable without context
- Pay SPECIAL attention to user instructions about communication style —
  extract these with category "instruction" and importance 9-10

For each fact provide:
- content: the atomic fact statement
- keywords: 3-5 search keywords
- context: brief description of WHY this fact matters and how it relates to the situation
- category: one of personal_info | preference | goal | instruction | event | relationship
- importance: 1-10
- event_date: ISO date "YYYY-MM-DD" if category is "event" AND a specific date is mentioned in content. Otherwise null.

Return JSON:
{
  "summary": {
    "user_profile": "...",
    "ongoing_topics": "...",
    "key_decisions": "...",
    "pending_items": "..."
  },
  "facts": [
    {"content": "...", "keywords": ["..."], "context": "...", "category": "...", "importance": N, "event_date": "YYYY-MM-DD" | null}
  ]
}
""".trimIndent()
    }

    /**
     * Форматирует evicted messages в чел-readable текст для prompt'а. User content
     * strip'ается от B-dirty обёрток (architecture-mandated, чтобы модель не
     * извлекала inline instructions/memories как новые facts).
     */
    private fun formatEvictedMessages(messages: List<ChatRepository.MessageRow>): String {
        if (messages.isEmpty()) return "(no messages)"
        return messages.joinToString("\n") { msg ->
            val text = if (msg.role == "user") PromptComposer.stripDirty(msg.content) else msg.content
            "#${msg.id} [${msg.role}]: $text"
        }
    }
}
