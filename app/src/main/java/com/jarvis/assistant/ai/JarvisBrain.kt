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
     * Offline fallback used when the API is unavailable.
     */
    fun parseHeuristic(transcription: String): JarvisCommand? {
        val text = transcription.trim().trimEnd('.', '!', '?')
        val lower = text.lowercase()

        // Timer: "set a timer for 10 minutes"
        parseTimer(text)?.let { return it }

        // Alarm: "set an alarm for 7 AM"
        parseAlarm(text)?.let { return it }

        // Google search (explicit google)
        parseGoogleSearch(text, lower)?.let { return it }

        // WhatsApp send
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

        // YouTube search (explicit)
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

        // YouTube play — only when YouTube is named
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
                    return JarvisCommand.YouTubePlay(songName = song)
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

        // Spotify play — explicit Spotify OR bare "play <song>"
        parseSpotify(text, lower)?.let { return it }

        // Open app (no play/search/send)
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
                return JarvisCommand.OpenApp(appName = app, packageName = pkg)
            }
        }

        return null
    }

    private fun parseSpotify(text: String, lower: String): JarvisCommand.PlaySpotifySong? {
        // "Open/go to Spotify and play X"
        Regex(
            """(?:open|go\s+to)\s+spotify\s+and\s+play\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            return spotifySongArtist(match.groupValues[1])
        }
        // "Play X on Spotify"
        Regex(
            """play\s+(.+?)\s+on\s+spotify$""",
            RegexOption.IGNORE_CASE
        ).find(text)?.let { match ->
            return spotifySongArtist(match.groupValues[1])
        }
        // Bare "Play X" (not youtube/whatsapp) → Spotify
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
        // "1 hour 30 minutes"
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
        // Require AM/PM for ambiguous 12h forms; also accept 24h "19:30"
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
            // 24h only when hour > 12 or explicitly 00–23 without am/pm
            if (hour !in 0..23 || minute !in 0..59) return null
            if (hour in 1..12) {
                // Ambiguous without AM/PM — do not guess
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
        private val SYSTEM_PROMPT = """
            You are Jarvis, an Android assistant command parser.
            Convert the user's voice command into a single JSON object.
            Supported actions only:
            - OPEN_APP with fields: action, appName, packageName (optional)
            - SEND_WHATSAPP_MESSAGE with fields: action, contact, message
            - YOUTUBE_PLAY with fields: action, songName
            - YOUTUBE_SEARCH with fields: action, query
            - PLAY_SPOTIFY_SONG with fields: action, song, artist (optional, only if user said it)
            - SET_TIMER with fields: action, durationSeconds (integer seconds)
            - SET_ALARM with fields: action, hour (0-23), minute (0-59)
            - GOOGLE_SEARCH with fields: action, query

            Rules:
            - "Open Spotify" → OPEN_APP (not PLAY_SPOTIFY_SONG; no song).
            - "Play Kalyani" / "Play Kalyani on Spotify" / "Open Spotify and play Kalyani" → PLAY_SPOTIFY_SONG with song extracted dynamically.
            - "Play X on YouTube" / "Open YouTube and play X" → YOUTUBE_PLAY.
            - Do not invent an artist; omit artist unless the user said "by …".
            - "Set a timer for 10 minutes" → SET_TIMER with durationSeconds=600.
            - "Set an alarm for 7 AM" → SET_ALARM with hour=7, minute=0 (24-hour hour field).
            - Do not guess AM/PM when ambiguous.
            - "Open Google and search for Kerala weather" → GOOGLE_SEARCH query="Kerala weather".

            Examples:
            User: Open WhatsApp and send 'I will come tomorrow' to Rahul.
            Output: {"action":"SEND_WHATSAPP_MESSAGE","contact":"Rahul","message":"I will come tomorrow"}

            User: Open Spotify
            Output: {"action":"OPEN_APP","appName":"Spotify","packageName":"com.spotify.music"}

            User: Play Kalyani
            Output: {"action":"PLAY_SPOTIFY_SONG","song":"Kalyani"}

            User: Open Spotify and play Kalyani by A.R. Rahman
            Output: {"action":"PLAY_SPOTIFY_SONG","song":"Kalyani","artist":"A.R. Rahman"}

            User: Open YouTube and play Shape of You
            Output: {"action":"YOUTUBE_PLAY","songName":"Shape of You"}

            User: Open YouTube and search lo-fi hip hop
            Output: {"action":"YOUTUBE_SEARCH","query":"lo-fi hip hop"}

            User: Set a timer for 10 minutes
            Output: {"action":"SET_TIMER","durationSeconds":600}

            User: Set an alarm for 7 AM
            Output: {"action":"SET_ALARM","hour":7,"minute":0}

            User: Open Google and search for Kerala weather
            Output: {"action":"GOOGLE_SEARCH","query":"Kerala weather"}

            If unsupported, return:
            {"action":"UNSUPPORTED","reason":"brief reason"}

            Return JSON only. No markdown.
        """.trimIndent()
    }
}
