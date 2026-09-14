package com.jarvis.assistant.commands

/**
 * Parses spoken confirmation while Jarvis is waiting to send a WhatsApp message.
 * Tap Send / Cancel remain available; this only maps natural phrases to the same actions.
 */
object ConfirmationPhraseParser {

    enum class Decision {
        SEND,
        CANCEL,
        UNKNOWN
    }

    private val sendPatterns = listOf(
        Regex("""^\s*(?:hey\s+)?(?:jarvis[,:]?\s*)?(?:please\s+)?(?:just\s+)?send(?:\s+(?:it|the\s+message|message|now))?\s*[.!]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:hey\s+)?(?:jarvis[,:]?\s*)?(?:yes|yeah|yep|ok|okay|confirm|confirmed|go\s*ahead|do\s+it)(?:\s*,?\s*send(?:\s+(?:it|the\s+message|message))?)?\s*[.!]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:hey\s+)?(?:jarvis[,:]?\s*)?yes\s*,?\s*(?:please\s+)?send(?:\s+(?:it|the\s+message|message))?\s*[.!]?\s*$""", RegexOption.IGNORE_CASE)
    )

    private val cancelPatterns = listOf(
        Regex("""^\s*(?:hey\s+)?(?:jarvis[,:]?\s*)?(?:please\s+)?cancel(?:\s+(?:it|the\s+message|message|send|sending))?\s*[.!]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:hey\s+)?(?:jarvis[,:]?\s*)?(?:don'?t\s+send|do\s+not\s+send|never\s*mind|nevermind|abort|stop|no)\s*[.!]?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:hey\s+)?(?:jarvis[,:]?\s*)?no\s*,?\s*(?:don'?t\s+send|cancel)?\s*[.!]?\s*$""", RegexOption.IGNORE_CASE)
    )

    fun parse(text: String): Decision {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Decision.UNKNOWN
        if (cancelPatterns.any { it.matches(trimmed) }) return Decision.CANCEL
        if (sendPatterns.any { it.matches(trimmed) }) return Decision.SEND
        // Soft contains fallback for slightly longer utterances.
        val lower = trimmed.lowercase()
        if (lower.contains("don't send") || lower.contains("do not send")) {
            return Decision.CANCEL
        }
        if (lower == "cancel" || lower.startsWith("cancel ")) {
            return Decision.CANCEL
        }
        if (lower.contains("send the message") || lower == "send" || lower == "send it" ||
            lower.startsWith("send it") || lower.startsWith("send the message")
        ) {
            return Decision.SEND
        }
        return Decision.UNKNOWN
    }
}
