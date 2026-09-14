package com.jarvis.assistant.commands

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Extensible action catalog. WhatsApp + YouTube remain implemented;
 * Spotify, timer/alarm, and Google search extend the same surface.
 */
@Serializable
enum class ActionType {
    OPEN_APP,
    SEND_WHATSAPP_MESSAGE,
    YOUTUBE_PLAY,
    YOUTUBE_SEARCH,
    PLAY_SPOTIFY_SONG,
    SET_TIMER,
    SET_ALARM,
    GOOGLE_SEARCH,
    CLOSE_JARVIS,
    CLICK,
    LONG_CLICK,
    TYPE,
    SCROLL,
    SWIPE,
    BACK,
    HOME,
    FIND_ELEMENT,
    WAIT,
    VERIFY
}

@Serializable
sealed class JarvisCommand {
    abstract val action: ActionType

    @Serializable
    @SerialName("OPEN_APP")
    data class OpenApp(
        val appName: String,
        val packageName: String? = null
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.OPEN_APP
    }

    @Serializable
    @SerialName("SEND_WHATSAPP_MESSAGE")
    data class SendWhatsAppMessage(
        val contact: String,
        val message: String
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.SEND_WHATSAPP_MESSAGE
    }

    @Serializable
    @SerialName("YOUTUBE_PLAY")
    data class YouTubePlay(
        val songName: String
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.YOUTUBE_PLAY
    }

    @Serializable
    @SerialName("YOUTUBE_SEARCH")
    data class YouTubeSearch(
        val query: String
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.YOUTUBE_SEARCH
    }

    @Serializable
    @SerialName("PLAY_SPOTIFY_SONG")
    data class PlaySpotifySong(
        val song: String,
        val artist: String? = null
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.PLAY_SPOTIFY_SONG
    }

    @Serializable
    @SerialName("SET_TIMER")
    data class SetTimer(
        val durationSeconds: Int
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.SET_TIMER
    }

    @Serializable
    @SerialName("SET_ALARM")
    data class SetAlarm(
        val hour: Int,
        val minute: Int
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.SET_ALARM
    }

    @Serializable
    @SerialName("GOOGLE_SEARCH")
    data class GoogleSearch(
        val query: String
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.GOOGLE_SEARCH
    }

    @Serializable
    @SerialName("CLOSE_JARVIS")
    data object CloseJarvis : JarvisCommand() {
        override val action: ActionType = ActionType.CLOSE_JARVIS
    }

    @Serializable
    @SerialName("UNSUPPORTED")
    data class Unsupported(
        val reason: String,
        val requestedAction: String? = null
    ) : JarvisCommand() {
        override val action: ActionType = ActionType.VERIFY
    }
}

@Serializable
data class BrainResponse(
    /** CHAT | SYSTEM_QUERY | ACTION | SINGLE_ACTION | MULTI_STEP_ACTION | CHAT_WITH_ACTION — optional for legacy */
    val type: String? = null,
    val response: String? = null,
    val action: String? = null,
    val id: String? = null,
    val contact: String? = null,
    val message: String? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val songName: String? = null,
    val song: String? = null,
    val artist: String? = null,
    val query: String? = null,
    val searchContent: String? = null,
    val durationSeconds: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val reason: String? = null,
    val systemQuery: String? = null,
    val steps: List<BrainStepResponse>? = null
)

@Serializable
data class BrainStepResponse(
    val id: String? = null,
    val action: String? = null,
    val contact: String? = null,
    val message: String? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val songName: String? = null,
    val song: String? = null,
    val artist: String? = null,
    val query: String? = null,
    val searchContent: String? = null,
    val durationSeconds: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val reason: String? = null
)

sealed class CommandResult {
    data object Success : CommandResult()
    data class NeedsConfirmation(val pending: JarvisCommand.SendWhatsAppMessage, val packageName: String) : CommandResult()
    data class NeedsContactChoice(val contactQuery: String, val choices: List<String>, val message: String) : CommandResult()
    data class Failure(val message: String) : CommandResult()
    data class Progress(val message: String) : CommandResult()
}
