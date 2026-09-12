package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class SpeechRecognitionState(
    val isListening: Boolean = false,
    val liveTranscription: String = "",
    val finalTranscription: String = "",
    val error: String? = null,
    val rmsLevel: Float = 0f
)

/**
 * Dedicated speech recognition layer. UI and command systems depend on this
 * interface only, so the implementation can later swap to OpenAI Whisper
 * without changing callers.
 */
interface SpeechRecognizerManager {
    val state: StateFlow<SpeechRecognitionState>
    fun startListening()
    fun stopListening()
    fun destroy()
}

class AndroidSpeechRecognizerManager(
    context: Context
) : SpeechRecognizerManager {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(SpeechRecognitionState())
    override val state: StateFlow<SpeechRecognitionState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.update { it.copy(isListening = true, error = null) }
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _state.update { it.copy(rmsLevel = normalized) }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _state.update { it.copy(isListening = false) }
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Speech client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during speech recognition"
                SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand speech"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Speech server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                else -> "Speech recognition error ($error)"
            }
            Log.w(TAG, message)
            _state.update {
                it.copy(isListening = false, error = message, rmsLevel = 0f)
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull().orEmpty()
            _state.update {
                it.copy(
                    isListening = false,
                    liveTranscription = best,
                    finalTranscription = best,
                    rmsLevel = 0f,
                    error = null
                )
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = texts?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) {
                _state.update { it.copy(liveTranscription = partial) }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.update {
                it.copy(error = "Speech recognition is not available on this device.")
            }
            return
        }

        stopListening()
        _state.update {
            it.copy(
                isListening = true,
                liveTranscription = "",
                finalTranscription = "",
                error = null,
                rmsLevel = 0f
            )
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
            speechRecognizer = it
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        recognizer.startListening(intent)
    }

    override fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "stopListening", t)
        } finally {
            speechRecognizer = null
            _state.update { it.copy(isListening = false, rmsLevel = 0f) }
        }
    }

    override fun destroy() {
        stopListening()
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
    }
}
