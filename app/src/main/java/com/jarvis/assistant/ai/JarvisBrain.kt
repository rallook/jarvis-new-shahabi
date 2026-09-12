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
     * Offline fallback used when the API is unavailable so WhatsApp / YouTube
     * prototype paths can still be exercised during development.
     */
    fun parseHeuristic(transcription: String): JarvisCommand? {
        val text = transcription.trim().trimEnd('.', '!', '?')
        val lower = text.lowercase()

        // YouTube play: "Open YouTube and play [song]"
        Regex(
            """(?:open\s+youtube\s+and\s+)?play\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            if ("youtube" in lower || lower.startsWith("play ")) {
                val song = match.groupValues[1].trim().trim('"', '\'')
                    .removePrefix("song ").removePrefix("the song ")
                    .trim()
                if (song.isNotBlank() && "whatsapp" !in lower) {
                    // Prefer explicit YouTube phrasing; bare "play X" maps to YouTube.
                    if ("youtube" in lower || lower.startsWith("play ")) {
                        return JarvisCommand.YouTubePlay(songName = song)
                    }
                }
            }
        }
        Regex(
            """open\s+youtube\s+and\s+play\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            val song = match.groupValues[1].trim().trim('"', '\'')
            if (song.isNotBlank()) return JarvisCommand.YouTubePlay(songName = song)
        }

        // YouTube search: "Open YouTube and search [content]"
        Regex(
            """open\s+youtube\s+and\s+search(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            val query = match.groupValues[1].trim().trim('"', '\'')
            if (query.isNotBlank()) return JarvisCommand.YouTubeSearch(query = query)
        }
        Regex(
            """(?:youtube\s+)?search(?:\s+youtube)?(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            if ("youtube" in lower) {
                val query = match.groupValues[1].trim().trim('"', '\'')
                if (query.isNotBlank()) return JarvisCommand.YouTubeSearch(query = query)
            }
        }

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

        if (lower.startsWith("open ") && !lower.contains("send") && !lower.contains(" play ") &&
            !lower.contains(" search")
        ) {
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
            - YOUTUBE_PLAY with fields: action, songName
            - YOUTUBE_SEARCH with fields: action, query

            Examples:
            User: Open WhatsApp and send 'I will come tomorrow' to Rahul.
            Output: {"action":"SEND_WHATSAPP_MESSAGE","contact":"Rahul","message":"I will come tomorrow"}

            User: Open WhatsApp
            Output: {"action":"OPEN_APP","appName":"WhatsApp","packageName":"com.whatsapp"}

            User: Open YouTube and play Shape of You
            Output: {"action":"YOUTUBE_PLAY","songName":"Shape of You"}

            User: Open YouTube and search lo-fi hip hop
            Output: {"action":"YOUTUBE_SEARCH","query":"lo-fi hip hop"}

            If unsupported, return:
            {"action":"UNSUPPORTED","reason":"brief reason"}

            Return JSON only. No markdown.
        """.trimIndent()
    }
}
