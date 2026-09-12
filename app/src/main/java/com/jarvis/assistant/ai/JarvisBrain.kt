package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.CommandParser
import com.jarvis.assistant.commands.JarvisCommand
import com.jarvis.assistant.settings.SettingsRepository

/**
 * JarvisBrain understands natural language and returns structured actions only.
 * It must never execute Android UI or Accessibility operations.
 */
class JarvisBrain(
    private val settingsRepository: SettingsRepository,
    private val openAIClient: OpenAIClient = OpenAIClient(settingsRepository),
    private val parser: CommandParser = CommandParser()
) : AiService {

    override suspend fun understandCommand(transcription: String): Result<JarvisCommand> {
        if (transcription.isBlank()) {
            return Result.failure(IllegalArgumentException("Empty transcription."))
        }

        val settings = settingsRepository.get()
        val hasKey = settingsRepository.effectiveApiKey().isNotBlank()
        val useBackend = settingsRepository.useSecureBackend()

        // Fast path when no AI credentials are configured.
        if (!hasKey && !useBackend) {
            if (settings.allowHeuristicFallback) {
                parseHeuristic(transcription)?.let { return Result.success(it) }
            }
            return Result.failure(
                IllegalStateException("Add an OpenAI API key in Settings, or enable heuristic fallback.")
            )
        }

        val result = openAIClient.completeJson(SYSTEM_PROMPT, transcription)
        return result.mapCatching { raw -> parser.parseBrainJson(raw) }
            .recoverCatching { error ->
                if (settings.allowHeuristicFallback) {
                    parseHeuristic(transcription) ?: throw error
                } else {
                    throw error
                }
            }
    }

    /**
     * Offline fallback used when the API is unavailable so the WhatsApp
     * prototype path can still be exercised during development.
     */
    fun parseHeuristic(transcription: String): JarvisCommand? {
        val text = transcription.trim().trimEnd('.', '!', '?')
        val lower = text.lowercase()

        val sendPatterns = listOf(
            Regex(
                """(?:open\s+whatsapp\s+and\s+)?send\s+['"](.+?)['"]\s+to\s+([A-Za-z][\w\s.]*)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:open\s+whatsapp\s+and\s+)?send\s+(.+?)\s+to\s+([A-Za-z][\w\s.]*)$""",
                RegexOption.IGNORE_CASE
            )
        )
        for (pattern in sendPatterns) {
            val match = pattern.find(text) ?: continue
            if ("whatsapp" !in lower && !lower.startsWith("send ")) continue
            val message = match.groupValues[1].trim().trim('"', '\'')
            val contact = match.groupValues[2].trim().trim('.', ' ')
            if (message.isNotBlank() && contact.isNotBlank()) {
                return JarvisCommand.SendWhatsAppMessage(contact = contact, message = message)
            }
        }

        if (lower.startsWith("open ") && !lower.contains("send")) {
            val app = text.removePrefix("Open ").removePrefix("open ").trim()
            return JarvisCommand.OpenApp(appName = app)
        }

        return null
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are Jarvis, an Android assistant command parser.
            Convert the user's voice command into a single JSON object.
            Supported actions only:
            - OPEN_APP with fields: action, appName, packageName (optional)
            - SEND_WHATSAPP_MESSAGE with fields: action, contact, message

            Examples:
            User: Open WhatsApp and send 'I will come tomorrow' to Rahul.
            Output: {"action":"SEND_WHATSAPP_MESSAGE","contact":"Rahul","message":"I will come tomorrow"}

            User: Open WhatsApp
            Output: {"action":"OPEN_APP","appName":"WhatsApp","packageName":"com.whatsapp"}

            If unsupported, return:
            {"action":"UNSUPPORTED","reason":"brief reason"}

            Return JSON only. No markdown.
        """.trimIndent()
    }
}
