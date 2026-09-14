package com.jarvis.assistant.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class JarvisSettings(
    val openAiApiKey: String = "",
    val openAiModel: String = DEFAULT_MODEL,
    val openAiBaseUrl: String = DEFAULT_BASE_URL,
    val useSecureBackend: Boolean = false,
    val secureBackendUrl: String = "",
    val allowHeuristicFallback: Boolean = true,
    val ttsEnabled: Boolean = true,
    val requireSendConfirmation: Boolean = true,
    /** When true, listen for “Jarvis” / “Hey Jarvis” to activate the mic. */
    val handsFreeEnabled: Boolean = true
) {
    val hasOpenAiKey: Boolean
        get() = openAiApiKey.isNotBlank() || BuildConfig.OPENAI_API_KEY.isNotBlank()

    /** Masked preview for UI — never show the full key. */
    fun maskedApiKey(): String {
        val key = openAiApiKey.ifBlank { BuildConfig.OPENAI_API_KEY }
        if (key.isBlank()) return "Not set"
        if (key.length <= 8) return "••••••••"
        return key.take(3) + "••••" + key.takeLast(4)
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"
    }
}

/**
 * Persists app configuration. The OpenAI API key is stored in
 * EncryptedSharedPreferences when available and is never logged.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = createPrefs(appContext)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<JarvisSettings> = _settings.asStateFlow()

    fun get(): JarvisSettings = _settings.value

    fun update(transform: (JarvisSettings) -> JarvisSettings) {
        val next = transform(_settings.value)
        write(next)
        _settings.value = next
    }

    fun setOpenAiApiKey(key: String) {
        update { it.copy(openAiApiKey = key.trim()) }
    }

    fun clearOpenAiApiKey() {
        update { it.copy(openAiApiKey = "") }
    }

    fun effectiveApiKey(): String {
        val stored = get().openAiApiKey.trim()
        if (stored.isNotBlank()) return stored
        return BuildConfig.OPENAI_API_KEY.trim()
    }

    fun effectiveBaseUrl(): String {
        val url = get().openAiBaseUrl.trim()
        return url.ifBlank { BuildConfig.OPENAI_BASE_URL }
    }

    fun effectiveModel(): String {
        return get().openAiModel.trim().ifBlank { JarvisSettings.DEFAULT_MODEL }
    }

    fun useSecureBackend(): Boolean {
        return get().useSecureBackend || BuildConfig.USE_SECURE_BACKEND
    }

    fun secureBackendUrl(): String {
        return get().secureBackendUrl.trim().ifBlank { BuildConfig.SECURE_BACKEND_URL }
    }

    private fun read(): JarvisSettings {
        return JarvisSettings(
            openAiApiKey = prefs.getString(KEY_API, "").orEmpty(),
            openAiModel = prefs.getString(KEY_MODEL, JarvisSettings.DEFAULT_MODEL)
                ?: JarvisSettings.DEFAULT_MODEL,
            openAiBaseUrl = prefs.getString(KEY_BASE_URL, JarvisSettings.DEFAULT_BASE_URL)
                ?: JarvisSettings.DEFAULT_BASE_URL,
            useSecureBackend = prefs.getBoolean(KEY_USE_BACKEND, false),
            secureBackendUrl = prefs.getString(KEY_BACKEND_URL, "").orEmpty(),
            allowHeuristicFallback = prefs.getBoolean(KEY_HEURISTIC, true),
            ttsEnabled = prefs.getBoolean(KEY_TTS, true),
            requireSendConfirmation = prefs.getBoolean(KEY_CONFIRM, true),
            handsFreeEnabled = prefs.getBoolean(KEY_HANDS_FREE, true)
        )
    }

    private fun write(settings: JarvisSettings) {
        prefs.edit {
            putString(KEY_API, settings.openAiApiKey)
            putString(KEY_MODEL, settings.openAiModel)
            putString(KEY_BASE_URL, settings.openAiBaseUrl)
            putBoolean(KEY_USE_BACKEND, settings.useSecureBackend)
            putString(KEY_BACKEND_URL, settings.secureBackendUrl)
            putBoolean(KEY_HEURISTIC, settings.allowHeuristicFallback)
            putBoolean(KEY_TTS, settings.ttsEnabled)
            // Confirmation is always enforced in v1.
            putBoolean(KEY_CONFIRM, true)
            putBoolean(KEY_HANDS_FREE, settings.handsFreeEnabled)
        }
    }

    companion object {
        private const val PREFS_NAME = "jarvis_secure_settings"
        private const val FALLBACK_PREFS = "jarvis_settings"
        private const val KEY_API = "openai_api_key"
        private const val KEY_MODEL = "openai_model"
        private const val KEY_BASE_URL = "openai_base_url"
        private const val KEY_USE_BACKEND = "use_secure_backend"
        private const val KEY_BACKEND_URL = "secure_backend_url"
        private const val KEY_HEURISTIC = "allow_heuristic_fallback"
        private const val KEY_TTS = "tts_enabled"
        private const val KEY_CONFIRM = "require_send_confirmation"
        private const val KEY_HANDS_FREE = "hands_free_enabled"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }

        private fun createPrefs(context: Context): SharedPreferences {
            return try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Throwable) {
                context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
            }
        }
    }
}
