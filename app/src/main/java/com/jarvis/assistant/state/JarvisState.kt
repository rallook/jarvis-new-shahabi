package com.jarvis.assistant.state

/**
 * High-level assistant lifecycle. UI and executors react to these states;
 * they must never leave the UI frozen without feedback.
 */
enum class JarvisPhase {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    EXECUTING,
    CONFIRMATION,
    SENDING,
    VERIFYING,
    COMPLETED,
    ERROR
}

data class ContactChoice(
    val displayName: String,
    val nodeIdHint: String? = null
)

data class PendingWhatsAppSend(
    val contact: String,
    val message: String,
    val packageName: String
)

data class JarvisUiState(
    val phase: JarvisPhase = JarvisPhase.IDLE,
    val statusText: String = "Ready",
    val liveTranscription: String = "",
    val finalTranscription: String = "",
    val assistantMessage: String = "",
    val previewMessage: String? = null,
    val confirmationTitle: String? = null,
    val pendingSend: PendingWhatsAppSend? = null,
    val contactChoices: List<ContactChoice> = emptyList(),
    val errorMessage: String? = null,
    val isListening: Boolean = false,
    val audioLevel: Float = 0f,
    val needsMicrophone: Boolean = false,
    val needsAccessibility: Boolean = false,
    val setupComplete: Boolean = false
)
