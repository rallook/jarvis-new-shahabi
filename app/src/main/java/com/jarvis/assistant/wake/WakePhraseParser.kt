package com.jarvis.assistant.wake

/**
 * Detects and strips “Jarvis” / “Hey Jarvis” wake phrases so only the real
 * command reaches the existing OpenAI / command pipeline.
 */
object WakePhraseParser {

    private val wakePrefix = Regex(
        pattern = """^\s*(?:hey\s+)?jarvis\b[\s,.:!\-]*""",
        option = RegexOption.IGNORE_CASE
    )

    private val wakeOnly = Regex(
        pattern = """^\s*(?:hey\s+)?jarvis\b[\s,.:!\-]*$""",
        option = RegexOption.IGNORE_CASE
    )

    fun containsWakePhrase(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return wakePrefix.containsMatchIn(trimmed) ||
            trimmed.contains("jarvis", ignoreCase = true)
    }

    /** True when the utterance is only the wake phrase (no command). */
    fun isWakeOnly(text: String): Boolean = wakeOnly.matches(text.trim())

    /**
     * Removes a leading wake phrase. Returns blank when the utterance was
     * wake-only or empty after stripping.
     */
    fun stripWakePhrase(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (isWakeOnly(trimmed)) return ""
        val stripped = wakePrefix.replaceFirst(trimmed, "").trim()
        // If wake word appears mid-sentence without a clean prefix, leave text as-is
        // unless it still starts with jarvis after soft cleanup.
        return stripped.ifBlank {
            if (containsWakePhrase(trimmed) && !trimmed.contains(',')) {
                trimmed.replace(Regex("""(?i)\b(?:hey\s+)?jarvis\b[\s,.:!\-]*"""), "")
                    .trim()
            } else {
                trimmed
            }
        }
    }

    /**
     * For wake-engine results: if the transcript starts with the wake phrase,
     * return the remainder (may be blank). Null if this is not a wake event.
     */
    fun extractAfterWake(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (!wakePrefix.containsMatchIn(trimmed)) {
            // Also accept "… Jarvis …" only when jarvis is near the start
            val lower = trimmed.lowercase()
            val idx = lower.indexOf("jarvis")
            if (idx < 0 || idx > 12) return null
            if (idx > 0) {
                val before = lower.substring(0, idx).trim()
                if (before.isNotEmpty() && before != "hey") return null
            }
        }
        return stripWakePhrase(trimmed)
    }
}
