package com.jarvis.assistant.ai

import android.util.Log
import com.jarvis.assistant.commands.CommandParser
import com.jarvis.assistant.commands.JarvisCommand
import com.jarvis.assistant.settings.SettingsRepository

/**
 * JarvisBrain understands natural language and returns chat and/or structured actions.
 * It must never execute Android UI or Accessibility operations.
 */
class JarvisBrain(
    private val settingsRepository: SettingsRepository,
    private val openAIClient: OpenAIClient = OpenAIClient(settingsRepository),
    private val parser: CommandParser = CommandParser()
) : AiService {

    override suspend fun understandCommand(transcription: String): Result<JarvisCommand> {
        return understandTurn(transcription).mapCatching { turn ->
            when (turn) {
                is AssistantTurnResult.Action -> turn.command
                is AssistantTurnResult.ChatWithAction -> turn.command
                is AssistantTurnResult.MultiStep -> turn.steps.firstOrNull()
                    ?: JarvisCommand.Unsupported("Empty multi-step plan.")
                is AssistantTurnResult.Chat -> JarvisCommand.Unsupported(turn.response)
                is AssistantTurnResult.SystemQuery -> JarvisCommand.Unsupported(
                    turn.spokenFallback ?: turn.queryId
                )
            }
        }
    }

    override suspend fun understandTurn(
        transcription: String,
        history: List<ConversationMemoryTurn>
    ): Result<AssistantTurnResult> {
        if (transcription.isBlank()) {
            return Result.failure(IllegalArgumentException("Empty transcription."))
        }

        val settings = settingsRepository.get()
        val hasKey = settingsRepository.effectiveApiKey().isNotBlank()
        val useBackend = settingsRepository.useSecureBackend()

        if (!hasKey && !useBackend) {
            if (settings.allowHeuristicFallback) {
                parseHeuristicTurn(transcription)?.let { return Result.success(it) }
            }
            return Result.failure(
                IllegalStateException("Add an OpenAI API key in Settings, or enable heuristic fallback.")
            )
        }

        Log.i(TAG, "AI_REQUEST")
        val result = openAIClient.completeJson(SYSTEM_PROMPT, history, transcription)
        return result.mapCatching { raw ->
            Log.i(TAG, "AI_RESPONSE")
            parser.parseAssistantTurn(raw)
        }.recoverCatching { error ->
            if (settings.allowHeuristicFallback) {
                parseHeuristicTurn(transcription) ?: throw error
            } else {
                throw error
            }
        }
    }

    /**
     * Offline fallback used when the API is unavailable.
     */
    fun parseHeuristic(transcription: String): JarvisCommand? {
        return when (val turn = parseHeuristicTurn(transcription)) {
            is AssistantTurnResult.Action -> turn.command
            is AssistantTurnResult.ChatWithAction -> turn.command
            is AssistantTurnResult.MultiStep -> turn.steps.firstOrNull()
            is AssistantTurnResult.SystemQuery -> null
            is AssistantTurnResult.Chat -> null
            null -> null
        }
    }

    fun parseHeuristicTurn(transcription: String): AssistantTurnResult? {
        val text = transcription.trim().trimEnd('.', '!', '?')
        val lower = text.lowercase()

        if (looksLikeCloseJarvis(lower)) {
            return AssistantTurnResult.Action(JarvisCommand.CloseJarvis)
        }

        if (looksLikeBatteryQuery(lower)) {
            return AssistantTurnResult.SystemQuery(queryId = "BATTERY")
        }

        parseTimer(text)?.let { return AssistantTurnResult.Action(it) }
        parseAlarm(text)?.let { return AssistantTurnResult.Action(it) }
        parseGoogleSearch(text, lower)?.let { return AssistantTurnResult.Action(it) }

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
                return AssistantTurnResult.Action(
                    JarvisCommand.SendWhatsAppMessage(contact = contact, message = message)
                )
            }
        }

        Regex(
            """open\s+youtube\s+and\s+search(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            val query = match.groupValues[1].trim().trim('"', '\'')
            if (query.isNotBlank()) {
                return AssistantTurnResult.Action(JarvisCommand.YouTubeSearch(query = query))
            }
        }
        Regex(
            """(?:youtube\s+)?search(?:\s+youtube)?(?:\s+for)?\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            if ("youtube" in lower) {
                val query = match.groupValues[1].trim().trim('"', '\'')
                if (query.isNotBlank()) {
                    return AssistantTurnResult.Action(JarvisCommand.YouTubeSearch(query = query))
                }
            }
        }

        Regex(
            """(?:open\s+youtube\s+and\s+)?play\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            if ("youtube" in lower) {
                val song = match.groupValues[1].trim().trim('"', '\'')
                    .removePrefix("song ").removePrefix("the song ")
                    .trim()
                    .removeSuffix(" on youtube").removeSuffix(" on YouTube")
                    .trim()
                if (song.isNotBlank() && "whatsapp" !in lower) {
                    return AssistantTurnResult.Action(JarvisCommand.YouTubePlay(songName = song))
                }
            }
        }
        Regex(
            """open\s+youtube\s+and\s+play\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            val song = match.groupValues[1].trim().trim('"', '\'')
            if (song.isNotBlank()) {
                return AssistantTurnResult.Action(JarvisCommand.YouTubePlay(songName = song))
            }
        }

        parseSpotify(text, lower)?.let { return AssistantTurnResult.Action(it) }

        if (lower.startsWith("open ") && !lower.contains("send") &&
            !lower.contains(" play ") && !lower.contains(" search")
        ) {
            val app = text.removePrefix("Open ").removePrefix("open ").trim()
            if (app.isNotBlank()) {
                val pkg = when {
                    app.contains("spotify", ignoreCase = true) -> "com.spotify.music"
                    app.contains("whatsapp", ignoreCase = true) -> "com.whatsapp"
                    app.contains("youtube", ignoreCase = true) -> "com.google.android.youtube"
                    else -> null
                }
                return AssistantTurnResult.Action(
                    JarvisCommand.OpenApp(appName = app, packageName = pkg)
                )
            }
        }

        if (looksLikeGeneralChat(lower)) {
            return AssistantTurnResult.Chat(
                "I need an OpenAI API key in Settings to answer general questions. " +
                    "I can still run device commands like timers, WhatsApp, YouTube, and Spotify."
            )
        }

        return null
    }

    private fun looksLikeCloseJarvis(lower: String): Boolean {
        val normalized = lower.trim().trimEnd('.', '!', '?')
        return normalized in setOf(
            "close jarvis",
            "close the jarvis",
            "dismiss jarvis",
            "hide jarvis",
            "close jarvis panel",
            "dismiss the jarvis",
            "hide the jarvis"
        ) || Regex("""^(?:please\s+)?(?:close|dismiss|hide)\s+(?:the\s+)?jarvis(?:\s+panel)?$""")
            .matches(normalized)
    }

    private fun looksLikeBatteryQuery(lower: String): Boolean {
        return ("battery" in lower || "charge" in lower) &&
            ("how" in lower || "what" in lower || "check" in lower || "percent" in lower || "%" in lower)
    }

    private fun looksLikeGeneralChat(lower: String): Boolean {
        val starters = listOf(
            "what is", "what's", "who is", "who's", "why ", "how do", "how does",
            "explain", "tell me", "define ", "when was", "where is", "where was"
        )
        return starters.any { lower.startsWith(it) || " $it" in " $lower" }
    }

    private fun parseSpotify(text: String, lower: String): JarvisCommand.PlaySpotifySong? {
        Regex(
            """(?:open|go\s+to)\s+spotify\s+and\s+play\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            return spotifySongArtist(match.groupValues[1])
        }
        Regex(
            """play\s+(.+?)\s+on\s+spotify$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            return spotifySongArtist(match.groupValues[1])
        }
        if (lower.startsWith("play ") && "youtube" !in lower && "whatsapp" !in lower) {
            val rest = text.removePrefix("Play ").removePrefix("play ").trim()
            if (rest.isNotBlank()) return spotifySongArtist(rest)
        }
        return null
    }

    private fun spotifySongArtist(raw: String): JarvisCommand.PlaySpotifySong? {
        var body = raw.trim().trim('"', '\'')
            .removePrefix("song ").removePrefix("the song ")
            .trim()
        if (body.isBlank()) return null
        var artist: String? = null
        Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).find(body)?.let { m ->
            body = m.groupValues[1].trim()
            artist = m.groupValues[2].trim().takeIf { it.isNotBlank() }
        }
        if (body.isBlank()) return null
        return JarvisCommand.PlaySpotifySong(song = body, artist = artist)
    }

    private fun parseTimer(text: String): JarvisCommand.SetTimer? {
        val match = Regex(
            """(?:set\s+(?:a\s+)?)?timer\s+for\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        val duration = parseDurationToSeconds(match.groupValues[1].trim()) ?: return null
        return JarvisCommand.SetTimer(durationSeconds = duration)
    }

    private fun parseDurationToSeconds(raw: String): Int? {
        val lower = raw.lowercase().trim()
        Regex("""^(\d+)\s*(seconds?|secs?|s)$""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""^(\d+)\s*(minutes?|mins?|m)$""").find(lower)?.let {
            val m = it.groupValues[1].toIntOrNull() ?: return null
            return m * 60
        }
        Regex("""^(\d+)\s*(hours?|hrs?|h)$""").find(lower)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: return null
            return h * 3600
        }
        var total = 0
        var matched = false
        Regex("""(\d+)\s*hours?""").find(lower)?.let {
            total += (it.groupValues[1].toIntOrNull() ?: 0) * 3600
            matched = true
        }
        Regex("""(\d+)\s*minutes?""").find(lower)?.let {
            total += (it.groupValues[1].toIntOrNull() ?: 0) * 60
            matched = true
        }
        Regex("""(\d+)\s*seconds?""").find(lower)?.let {
            total += it.groupValues[1].toIntOrNull() ?: 0
            matched = true
        }
        return if (matched && total > 0) total else null
    }

    private fun parseAlarm(text: String): JarvisCommand.SetAlarm? {
        val match = Regex(
            """(?:set\s+(?:an?\s+)?)?alarm\s+for\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        val timeRaw = match.groupValues[1].trim()
        return parseClockTime(timeRaw)
    }

    private fun parseClockTime(raw: String): JarvisCommand.SetAlarm? {
        val cleaned = raw.trim().lowercase()
            .replace(".", "")
            .replace("  ", " ")
        Regex("""^(\d{1,2}):(\d{2})\s*(a\.?m\.?|p\.?m\.?)$""").find(cleaned)?.let { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].toIntOrNull() ?: return null
            val ampm = m.groupValues[3]
            if (minute !in 0..59 || hour !in 1..12) return null
            hour = to24Hour(hour, ampm.startsWith("p"))
            return JarvisCommand.SetAlarm(hour = hour, minute = minute)
        }
        Regex("""^(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)$""").find(cleaned)?.let { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: return null
            val ampm = m.groupValues[2]
            if (hour !in 1..12) return null
            hour = to24Hour(hour, ampm.startsWith("p"))
            return JarvisCommand.SetAlarm(hour = hour, minute = 0)
        }
        Regex("""^(\d{1,2}):(\d{2})$""").find(cleaned)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            if (hour in 1..12) {
                return null
            }
            return JarvisCommand.SetAlarm(hour = hour, minute = minute)
        }
        return null
    }

    private fun to24Hour(hour12: Int, isPm: Boolean): Int {
        return when {
            isPm && hour12 == 12 -> 12
            isPm -> hour12 + 12
            hour12 == 12 -> 0
            else -> hour12
        }
    }

    private fun parseGoogleSearch(text: String, lower: String): JarvisCommand.GoogleSearch? {
        Regex(
            """(?:open\s+google\s+and\s+search(?:\s+for)?|go\s+to\s+google\s+and\s+search(?:\s+for)?|search\s+google\s+for|google\s+search)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            val query = match.groupValues[1].trim().trim('"', '\'')
            if (query.isNotBlank()) return JarvisCommand.GoogleSearch(query = query)
        }
        if ("google" in lower && "search" in lower) {
            Regex(
                """search(?:\s+for)?\s+(.+)$""",
                RegexOption.IGNORE_CASE
            ).find(text)?.let { match ->
                val query = match.groupValues[1].trim().trim('"', '\'')
                    .removeSuffix(" on google").removeSuffix(" in google").trim()
                if (query.isNotBlank() && "youtube" !in lower) {
                    return JarvisCommand.GoogleSearch(query = query)
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "JarvisBrain"

        private val SYSTEM_PROMPT = """
            You are Jarvis, a general-purpose Android AI assistant.
            Decide the user's intent and return ONE JSON object only (no markdown).

            Intent types:
            - CHAT — normal conversation / knowledge questions. Do NOT invent device actions.
            - SYSTEM_QUERY — device state such as battery. Use systemQuery field.
            - ACTION / SINGLE_ACTION — one device automation.
            - MULTI_STEP_ACTION — several automations in order (steps array).
            - CHAT_WITH_ACTION — speak briefly AND run one action.

            JSON shapes:
            {"type":"CHAT","response":"natural spoken answer"}
            {"type":"SYSTEM_QUERY","systemQuery":"BATTERY","response":"optional short phrase"}
            {"type":"ACTION","action":"SET_TIMER","durationSeconds":600}
            {"type":"MULTI_STEP_ACTION","steps":[{"action":"YOUTUBE_SEARCH","query":"..."},{"action":"OPEN_APP","appName":"YouTube"}],"response":"optional"}
            {"type":"CHAT_WITH_ACTION","response":"...","action":"SET_TIMER","durationSeconds":600}

            Supported actions:
            - OPEN_APP: appName, packageName (optional)
            - SEND_WHATSAPP_MESSAGE: contact, message
            - YOUTUBE_PLAY: songName
            - YOUTUBE_SEARCH: query
            - PLAY_SPOTIFY_SONG: song, artist (optional)
            - SET_TIMER: durationSeconds
            - SET_ALARM: hour (0-23), minute (0-59)
            - GOOGLE_SEARCH: query
            - CLOSE_JARVIS: only when the user explicitly asks to close/dismiss/hide Jarvis UI

            Rules:
            - Prefer CHAT for questions like "What is photosynthesis?" or follow-ups ("explain more", "how old is he?").
            - Use conversation history for pronouns and follow-ups.
            - Never force an Android action for a normal question.
            - "Open Spotify" → OPEN_APP. "Play Kalyani" → PLAY_SPOTIFY_SONG.
            - "Play X on YouTube" → YOUTUBE_PLAY.
            - Do not invent artists.
            - Do not guess AM/PM when ambiguous.
            - response text must be natural speech for TTS — never raw JSON.
            - If unsupported automation: {"type":"CHAT","response":"brief honest explanation"}
            - CLOSE_JARVIS only for explicit phrases like "Close Jarvis", "Dismiss Jarvis", "Hide Jarvis".
            - Do NOT use CLOSE_JARVIS for "stop", "stop listening", "cancel", "I'm done", or "go back".

            Examples:
            User: What is photosynthesis?
            {"type":"CHAT","response":"Photosynthesis is how plants turn light into chemical energy..."}

            User: How much battery do I have?
            {"type":"SYSTEM_QUERY","systemQuery":"BATTERY"}

            User: Set a timer for 10 minutes
            {"type":"ACTION","action":"SET_TIMER","durationSeconds":600}

            User: Send Rahul a WhatsApp saying I'll come tomorrow
            {"type":"ACTION","action":"SEND_WHATSAPP_MESSAGE","contact":"Rahul","message":"I'll come tomorrow"}

            User: Search YouTube for Kerala travel videos
            {"type":"ACTION","action":"YOUTUBE_SEARCH","query":"Kerala travel videos"}

            User: Set a timer for 10 minutes and tell me what to do while I wait
            {"type":"CHAT_WITH_ACTION","response":"Timer set. While you wait, stretch or drink some water.","action":"SET_TIMER","durationSeconds":600}

            User: Close Jarvis
            {"type":"ACTION","action":"CLOSE_JARVIS"}
        """.trimIndent()
    }
}
