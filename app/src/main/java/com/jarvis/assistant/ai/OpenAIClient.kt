package com.jarvis.assistant.ai

import android.util.Log
import com.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin OpenAI Chat Completions client.
 *
 * SECURITY:
 * - API keys must never be logged.
 * - Prefer the in-app Settings key (encrypted prefs), then BuildConfig debug fallback.
 * - Release builds should eventually route through a secure backend.
 */
class OpenAIClient(
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    suspend fun completeJson(systemPrompt: String, userPrompt: String): Result<String> =
        completeJson(systemPrompt, history = emptyList(), userPrompt = userPrompt)

    suspend fun completeJson(
        systemPrompt: String,
        history: List<ConversationMemoryTurn>,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (settingsRepository.useSecureBackend()) {
            return@withContext callSecureBackend(systemPrompt, history, userPrompt)
        }

        val apiKey = settingsRepository.effectiveApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "OpenAI API key missing. Add it in Jarvis Settings."
                )
            )
        }

        val model = settingsRepository.effectiveModel()
        val baseUrl = settingsRepository.effectiveBaseUrl()

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (turn in history) {
            val role = when (turn.role.lowercase()) {
                "assistant" -> "assistant"
                else -> "user"
            }
            messages.put(JSONObject().put("role", role).put("content", turn.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)
            .put("response_format", JSONObject().put("type", "json_object"))
            .toString()

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            Log.i(TAG, "AI_REQUEST")
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "OpenAI HTTP ${response.code}")
                    return@withContext Result.failure(
                        IllegalStateException("OpenAI request failed (${response.code}).")
                    )
                }
                val content = JSONObject(payload)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Log.i(TAG, "AI_RESPONSE")
                Result.success(content)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "OpenAI request error", t)
            Result.failure(t)
        }
    }

    /**
     * Placeholder for production: POST to your backend which holds the OpenAI key.
     * Wire auth headers / session tokens here — never the OpenAI secret.
     */
    private fun callSecureBackend(
        systemPrompt: String,
        history: List<ConversationMemoryTurn>,
        userPrompt: String
    ): Result<String> {
        val url = settingsRepository.secureBackendUrl()
        if (url.isBlank()) {
            return Result.failure(
                IllegalStateException("Secure backend URL is not configured in Settings.")
            )
        }
        val historyJson = JSONArray()
        for (turn in history) {
            historyJson.put(
                JSONObject()
                    .put("role", turn.role)
                    .put("content", turn.content)
            )
        }
        val body = JSONObject()
            .put("system", systemPrompt)
            .put("user", userPrompt)
            .put("history", historyJson)
            .toString()
        val request = Request.Builder()
            .url(url)
            // TODO(security): Attach user/session auth, not an OpenAI key.
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            Log.i(TAG, "AI_REQUEST")
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(IllegalStateException("Backend request failed (${response.code})."))
                } else {
                    val json = JSONObject(payload)
                    Log.i(TAG, "AI_RESPONSE")
                    Result.success(json.optString("content", payload))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    companion object {
        private const val TAG = "OpenAIClient"
    }
}
