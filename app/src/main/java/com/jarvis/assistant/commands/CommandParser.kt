package com.jarvis.assistant.commands

import kotlinx.serialization.json.Json

/**
 * Converts structured AI brain output into typed [JarvisCommand] values.
 * The AI never executes Android actions directly.
 */
class CommandParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    fun parseBrainJson(raw: String): JarvisCommand {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val response = json.decodeFromString<BrainResponse>(cleaned)
        return when (response.action.uppercase()) {
            "OPEN_APP" -> JarvisCommand.OpenApp(
                appName = response.appName.orEmpty().ifBlank { "unknown" },
                packageName = response.packageName
            )
            "SEND_WHATSAPP_MESSAGE" -> {
                val contact = response.contact?.trim().orEmpty()
                val message = response.message?.trim().orEmpty()
                if (contact.isBlank() || message.isBlank()) {
                    JarvisCommand.Unsupported("Missing contact or message for WhatsApp send.")
                } else {
                    JarvisCommand.SendWhatsAppMessage(contact = contact, message = message)
                }
            }
            "YOUTUBE_PLAY" -> {
                val song = response.songName?.trim().orEmpty()
                    .ifBlank { response.song?.trim().orEmpty() }
                    .ifBlank { response.query?.trim().orEmpty() }
                if (song.isBlank()) {
                    JarvisCommand.Unsupported("Missing song name for YouTube play.")
                } else {
                    JarvisCommand.YouTubePlay(songName = song)
                }
            }
            "YOUTUBE_SEARCH" -> {
                val query = response.query?.trim().orEmpty()
                    .ifBlank { response.searchContent?.trim().orEmpty() }
                if (query.isBlank()) {
                    JarvisCommand.Unsupported("Missing search content for YouTube search.")
                } else {
                    JarvisCommand.YouTubeSearch(query = query)
                }
            }
            "PLAY_SPOTIFY_SONG" -> {
                val song = response.song?.trim().orEmpty()
                    .ifBlank { response.songName?.trim().orEmpty() }
                    .ifBlank { response.query?.trim().orEmpty() }
                val artist = response.artist?.trim()?.takeIf { it.isNotBlank() }
                if (song.isBlank()) {
                    JarvisCommand.Unsupported("Missing song for Spotify play.")
                } else {
                    JarvisCommand.PlaySpotifySong(song = song, artist = artist)
                }
            }
            "SET_TIMER" -> {
                val seconds = response.durationSeconds
                if (seconds == null || seconds <= 0) {
                    JarvisCommand.Unsupported("Missing or invalid durationSeconds for timer.")
                } else {
                    JarvisCommand.SetTimer(durationSeconds = seconds)
                }
            }
            "SET_ALARM" -> {
                val hour = response.hour
                val minute = response.minute ?: 0
                if (hour == null || hour !in 0..23 || minute !in 0..59) {
                    JarvisCommand.Unsupported("Missing or invalid hour/minute for alarm.")
                } else {
                    JarvisCommand.SetAlarm(hour = hour, minute = minute)
                }
            }
            "GOOGLE_SEARCH" -> {
                val query = response.query?.trim().orEmpty()
                    .ifBlank { response.searchContent?.trim().orEmpty() }
                if (query.isBlank()) {
                    JarvisCommand.Unsupported("Missing query for Google search.")
                } else {
                    JarvisCommand.GoogleSearch(query = query)
                }
            }
            else -> JarvisCommand.Unsupported(
                reason = response.reason ?: "Action '${response.action}' is not supported yet.",
                requestedAction = response.action
            )
        }
    }
}
