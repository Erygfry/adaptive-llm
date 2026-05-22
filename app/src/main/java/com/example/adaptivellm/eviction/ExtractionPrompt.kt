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

Categories — pick exactly one per fact. Read definitions carefully:

  personal_info — Stable identity facts about the user (location, occupation,
                  age, family situation). Things that ARE TRUE about who they are.
                  Examples: "User lives in Moscow.", "User is a software engineer."

  preference    — What the user LIKES, dislikes, or prefers. Tastes and tendencies,
                  NOT commands to the assistant.
                  Examples: "User prefers tea over coffee.",
                            "User likes concise explanations." [NOT instruction!]

  goal          — What the user wants to achieve or is actively working on.
                  Examples: "User is learning Spanish.",
                            "User wants to visit Japan in 2026."

  event         — A specific dated occurrence (past or future). event_date REQUIRED.
                  Examples: "User defends diploma on 2026-06-15.",
                            "Flight to Tokyo on 2026-05-25."

  relationship  — User's connection with another named person.
                  Examples: "User's daughter Anna is 7 years old.",
                            "User's colleague Maria works at Google."

  instruction   — VERY NARROW. ONLY explicit commands the user gave about HOW
                  THE ASSISTANT MUST BEHAVE. Behavioral rules, not facts.
                  VALID examples:
                    - "Always respond in English."
                    - "Don't use emojis."
                    - "Be brief, no long explanations."
                    - "Address me formally with 'вы'."
                  INVALID (these belong in OTHER categories):
                    - "User likes brief responses." → preference
                    - "User mentioned working in Python." → skip, not a fact
                    - "User lives in Berlin." → personal_info
                  Rule of thumb: if the user said "do X" / "don't do Y" /
                  "always/never do Z" to the assistant — it's instruction.
                  Everything else describing the user — is NOT instruction.

For each fact provide:
- content: the atomic fact statement
- keywords: 3-5 search keywords
- context: brief description of WHY this fact matters and how it relates to the situation
- category: one of personal_info | preference | goal | instruction | event | relationship
- importance: integer 1-10 — calibrate using the rubric below
- event_date: ISO date "YYYY-MM-DD" if category is "event" AND a specific date is mentioned in content. Otherwise null.

Importance rubric — anchor against these levels, DO NOT default everything to 9-10:

  1-2  Trivia / passing mentions. Forgettable. Casual remarks barely worth storing.
       Examples: "User mentioned the weather is nice today." (preference, importance: 2)
                 "User briefly said they tried sushi once." (preference, importance: 2)

  3-4  Background information. Mildly useful context but rarely actionable.
       Examples: "User sometimes uses Android phones." (preference, importance: 3)
                 "User has visited Paris before." (event without date, importance: 4)

  5-6  Standard everyday facts. Useful for personalization but not critical.
       Default level for typical facts when uncertain.
       Examples: "User enjoys reading sci-fi books." (preference, importance: 5)
                 "User commutes by bike." (personal_info, importance: 5)
                 "User is learning Kotlin." (goal, importance: 6)

  7-8  Important stable facts. Affect how the assistant should help long-term.
       Examples: "User is a software engineer." (personal_info, importance: 7)
                 "User lives in Moscow." (personal_info, importance: 7)
                 "User is writing a master's thesis on LLMs." (goal, importance: 8)

  9-10 Critical / time-sensitive / explicit behavior commands.
       Examples: "User defends diploma on 2026-06-15." (event, importance: 9)
                 "User flies to Tokyo on 2026-05-25." (event, importance: 10 if soon)
                 "Always respond in English." (instruction, importance: 10)
                 "Don't use emojis." (instruction, importance: 9)

Calibration tips:
- Most facts should land in 4-7 range. If you're tempted to give 9-10 to everything
  — re-read the rubric. Reserve 9-10 for facts that genuinely require urgent
  attention or are explicit instructions.
- A user's "favorite color" is a 4-5, NOT a 9.
- A user being "interested in some topic" is a 4-6, NOT a 9.

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
