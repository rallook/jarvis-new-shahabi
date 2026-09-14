package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.JarvisCommand

/**
 * Unified brain output for one user turn — chat, device action, or both.
 */
sealed class AssistantTurnResult {
    data class Chat(val response: String) : AssistantTurnResult()

    data class SystemQuery(
        val queryId: String,
        val spokenFallback: String? = null
    ) : AssistantTurnResult()

    data class Action(val command: JarvisCommand) : AssistantTurnResult()

    data class MultiStep(
        val steps: List<JarvisCommand>,
        val response: String? = null
    ) : AssistantTurnResult()

    data class ChatWithAction(
        val response: String,
        val command: JarvisCommand
    ) : AssistantTurnResult()
}
