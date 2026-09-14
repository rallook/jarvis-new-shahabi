package com.jarvis.assistant.ai

/**
 * Abstraction over the language-understanding backend.
 *
 * Local development may call OpenAI directly via [OpenAIClient].
 * Production should set USE_SECURE_BACKEND and route through a server that
 * holds the API key — never embed production secrets in the APK.
 */
interface AiService {
    /** Legacy single-command path (still used by heuristic callers). */
    suspend fun understandCommand(transcription: String): Result<com.jarvis.assistant.commands.JarvisCommand>

    /** Unified conversational turn with optional session history. */
    suspend fun understandTurn(
        transcription: String,
        history: List<ConversationMemoryTurn> = emptyList()
    ): Result<AssistantTurnResult>
}

data class ConversationMemoryTurn(
    val role: String,
    val content: String
)
