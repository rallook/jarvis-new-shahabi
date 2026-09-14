package com.jarvis.assistant.wake

/**
 * Explicit hands-free state machine. Prevents duplicate mic sessions and
 * recursive wake activation while Jarvis is speaking or already listening.
 */
enum class HandsFreeState {
    DISABLED,
    IDLE,
    WAKE_DETECTED,
    LISTENING,
    PROCESSING,
    COMMAND_COMPLETED,
    TTS_ACTIVE,
    ERROR
}
