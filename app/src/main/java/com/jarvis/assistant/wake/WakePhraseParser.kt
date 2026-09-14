package com.jarvis.assistant.wake

/**
 * Wake-phrase helpers for stripping optional leading “Jarvis” from STT text,
 * and detecting interrupt phrases like “Jarvis stop”.
 */
object WakePhraseParser {
    private val wakePrefix = Regex(
        """^(?:hey\s+)?jarvis[\s,]+""",
        RegexOption.IGNORE_CASE
    )
    private val wakeOnly = Regex(
        """^(?:hey\s+)?jarvis[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val stopCommand = Regex(
        """^(?:hey\s+)?jarvis[\s,]+stop(?:\s+please)?[.!]?$|^stop(?:\s+please)?[.!]?$|^jarvis[\s,]+stop(?:\s+.+)?$""",
        RegexOption.IGNORE_CASE
    )

    fun containsWakePhrase(text: String): Boolean {
        val trimmed = text.trim()
        return wakePrefix.containsMatchIn(trimmed) ||
            wakeOnly.matches(trimmed)
    }

    /** True when the utterance is only the wake phrase (no command). */
    fun isWakeOnly(text: String): Boolean = wakeOnly.matches(text.trim())

    /** True for interrupt phrases such as “Jarvis stop” / “stop”. */
    fun isStopCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (stopCommand.matches(trimmed)) return true
        val lower = trimmed.lowercase()
        return lower == "stop" ||
            lower == "jarvis stop" ||
            lower == "hey jarvis stop" ||
            lower.startsWith("jarvis stop") ||
            lower == "stop jarvis"
    }

    /**
     * Removes a leading wake phrase. Returns blank when the utterance was
     * wake-only or empty after stripping.
     */
    fun stripWakePhrase(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        if (isWakeOnly(trimmed)) return ""
        return wakePrefix.replaceFirst(trimmed, "").trim()
    }

    /**
     * If the transcript starts with the wake phrase, return the remainder
     * (may be blank). Null if this is not a wake-prefixed utterance.
     */
    fun extractAfterWake(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        if (isWakeOnly(trimmed)) return ""
        if (!wakePrefix.containsMatchIn(trimmed)) {
            return null
        }
        return stripWakePhrase(trimmed)
    }
}
