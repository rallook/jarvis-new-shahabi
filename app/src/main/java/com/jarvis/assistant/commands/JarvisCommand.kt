package com.jarvis.assistant.commands

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Extensible action catalog. Only OPEN_APP and SEND_WHATSAPP_MESSAGE are
 * implemented in v1; remaining types reserve the future agent surface.
 */
@Serializable
enum class ActionType {
    OPEN_APP,
    SEND_WHATSAPP_MESSAGE,
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
    val action: String,
    val contact: String? = null,
    val message: String? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val reason: String? = null
)

sealed class CommandResult {
    data object Success : CommandResult()
    data class NeedsConfirmation(val pending: JarvisCommand.SendWhatsAppMessage, val packageName: String) : CommandResult()
    data class NeedsContactChoice(val contactQuery: String, val choices: List<String>, val message: String) : CommandResult()
    data class Failure(val message: String) : CommandResult()
    data class Progress(val message: String) : CommandResult()
}
