package com.jarvis.assistant.ai

import com.jarvis.assistant.commands.JarvisCommand

/**
 * Abstraction over the language-understanding backend.
 *
 * Local development may call OpenAI directly via [OpenAIClient].
 * Production should set USE_SECURE_BACKEND and route through a server that
 * holds the API key — never embed production secrets in the APK.
 */
interface AiService {
    suspend fun understandCommand(transcription: String): Result<JarvisCommand>
}
