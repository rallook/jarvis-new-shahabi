package com.jarvis.assistant.commands

import com.jarvis.assistant.ai.AssistantTurnResult
import kotlinx.serialization.json.Json

/**
 * Converts structured AI brain output into typed [AssistantTurnResult] / [JarvisCommand] values.
 * The AI never executes Android actions directly.
 */
class CommandParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    fun parseBrainJson(raw: String): JarvisCommand {
        val turn = parseAssistantTurn(raw)
        return when (turn) {
            is AssistantTurnResult.Action -> turn.command
            is AssistantTurnResult.ChatWithAction -> turn.command
            is AssistantTurnResult.MultiStep -> turn.steps.firstOrNull()
                ?: JarvisCommand.Unsupported("Empty multi-step plan.")
            is AssistantTurnResult.Chat -> JarvisCommand.Unsupported(turn.response)
            is AssistantTurnResult.SystemQuery -> JarvisCommand.Unsupported(
                turn.spokenFallback ?: "System query: ${turn.queryId}"
            )
        }
    }

    fun parseAssistantTurn(raw: String): AssistantTurnResult {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val response = json.decodeFromString<BrainResponse>(cleaned)
        val type = response.type?.uppercase()?.trim().orEmpty()

        when (type) {
            "CHAT", "NORMAL_CHAT" -> {
                val text = response.response?.trim().orEmpty()
                    .ifBlank { response.message?.trim().orEmpty() }
                    .ifBlank { response.reason?.trim().orEmpty() }
                return AssistantTurnResult.Chat(
                    text.ifBlank { "I'm not sure how to answer that." }
                )
            }
            "SYSTEM_QUERY" -> {
                val id = response.systemQuery?.trim().orEmpty()
                    .ifBlank { response.query?.trim().orEmpty() }
                    .ifBlank { response.id?.trim().orEmpty() }
                    .ifBlank { "UNKNOWN" }
                return AssistantTurnResult.SystemQuery(
                    queryId = id.uppercase(),
                    spokenFallback = response.response?.trim()
                )
            }
            "MULTI_STEP_ACTION", "MULTI_STEP" -> {
                val steps = response.steps.orEmpty().mapNotNull { stepToCommand(it) }
                if (steps.isEmpty()) {
                    return AssistantTurnResult.Chat(
                        response.response?.trim()
                            ?: "I couldn't build a multi-step plan for that."
                    )
                }
                return AssistantTurnResult.MultiStep(
                    steps = steps,
                    response = response.response?.trim()
                )
            }
            "CHAT_WITH_ACTION" -> {
                val command = mapActionFields(response)
                val text = response.response?.trim().orEmpty()
                if (command is JarvisCommand.Unsupported && text.isNotBlank()) {
                    return AssistantTurnResult.Chat(text)
                }
                if (text.isBlank()) {
                    return AssistantTurnResult.Action(command)
                }
                return AssistantTurnResult.ChatWithAction(response = text, command = command)
            }
            "ACTION", "SINGLE_ACTION", "" -> {
                // Legacy: no type, only action field.
                if (type.isEmpty() && response.action.isNullOrBlank() &&
                    !response.response.isNullOrBlank()
                ) {
                    return AssistantTurnResult.Chat(response.response.trim())
                }
                return AssistantTurnResult.Action(mapActionFields(response))
            }
            else -> {
                if (!response.response.isNullOrBlank() && response.action.isNullOrBlank()) {
                    return AssistantTurnResult.Chat(response.response.trim())
                }
                return AssistantTurnResult.Action(mapActionFields(response))
            }
        }
    }

    private fun stepToCommand(step: BrainStepResponse): JarvisCommand? {
        val action = (step.action ?: step.id)?.trim().orEmpty()
        if (action.isBlank()) return null
        val asBrain = BrainResponse(
            action = action,
            contact = step.contact,
            message = step.message,
            appName = step.appName,
            packageName = step.packageName,
            songName = step.songName,
            song = step.song,
            artist = step.artist,
            query = step.query,
            searchContent = step.searchContent,
            durationSeconds = step.durationSeconds,
            hour = step.hour,
            minute = step.minute,
            reason = step.reason
        )
        val mapped = mapActionFields(asBrain)
        return if (mapped is JarvisCommand.Unsupported) null else mapped
    }

    private fun mapActionFields(response: BrainResponse): JarvisCommand {
        val actionKey = (response.action ?: response.id)?.uppercase()?.trim().orEmpty()
        return when (actionKey) {
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
            "CLOSE_JARVIS", "DISMISS_JARVIS", "HIDE_JARVIS" -> JarvisCommand.CloseJarvis
            "UNSUPPORTED", "" -> JarvisCommand.Unsupported(
                reason = response.reason
                    ?: response.response
                    ?: if (actionKey.isBlank()) {
                        "No action specified."
                    } else {
                        "Action '$actionKey' is not supported yet."
                    },
                requestedAction = actionKey.ifBlank { response.action }
            )
            else -> JarvisCommand.Unsupported(
                reason = response.reason ?: "Action '$actionKey' is not supported yet.",
                requestedAction = actionKey
            )
        }
    }
}
