package com.jarvis.assistant.wake

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class WakeEvent(
    /** Original STT transcript. */
    val rawTranscript: String,
    /** Command text after stripping the wake phrase; blank when wake-only. */
    val commandAfterWake: String
)

/**
 * Isolates wake-phrase detection so Jarvis does not depend on a particular
 * hotword vendor. Android does not expose Google's proprietary “Hey Google”
 * engine to third-party apps; this implementation uses on-device
 * [SpeechRecognizer] in a restart loop while the mic FGS is held.
 */
interface WakeWordEngine {
    fun start(onWake: (WakeEvent) -> Unit)
    fun stop()
    fun suspend()
    fun resume()
    fun isRunning(): Boolean
    fun destroy()
}

class SpeechRecognizerWakeWordEngine(
    context: Context
) : WakeWordEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val desiredRunning = AtomicBoolean(false)
    private val suspended = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)

    private var speechRecognizer: SpeechRecognizer? = null
    private var onWake: ((WakeEvent) -> Unit)? = null
    private var restartRunnable: Runnable? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening.set(true)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening.set(false)
        }

        override fun onError(error: Int) {
            listening.set(false)
            // Expected during continuous wake listening — restart quietly.
            if (error != SpeechRecognizer.ERROR_CLIENT) {
                Log.d(TAG, "wake recognizer error=$error")
            }
            scheduleRestart(delayMs = restartDelayFor(error))
        }

        override fun onResults(results: Bundle?) {
            listening.set(false)
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull().orEmpty().trim()
            if (best.isNotBlank()) {
                val after = WakePhraseParser.extractAfterWake(best)
                if (after != null) {
                    Log.i(TAG, "JARVIS_WAKE_DETECTED")
                    val callback = onWake
                    // Stop before handing off so command STT can own the mic.
                    stopInternal(keepDesired = false)
                    callback?.invoke(
                        WakeEvent(
                            rawTranscript = best,
                            commandAfterWake = after
                        )
                    )
                    return
                }
            }
            scheduleRestart(delayMs = 280L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = texts?.firstOrNull().orEmpty()
            if (partial.isBlank()) return
            // Fast-path: clear wake-only partials can activate early.
            val after = WakePhraseParser.extractAfterWake(partial)
            if (after != null && WakePhraseParser.isWakeOnly(partial)) {
                Log.i(TAG, "JARVIS_WAKE_DETECTED")
                val callback = onWake
                stopInternal(keepDesired = false)
                callback?.invoke(
                    WakeEvent(rawTranscript = partial, commandAfterWake = "")
                )
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun start(onWake: (WakeEvent) -> Unit) {
        this.onWake = onWake
        desiredRunning.set(true)
        suspended.set(false)
        Log.i(TAG, "JARVIS_WAKE_ENABLED")
        mainHandler.post { beginListening() }
    }

    override fun stop() {
        desiredRunning.set(false)
        stopInternal(keepDesired = false)
        Log.i(TAG, "JARVIS_WAKE_DISABLED")
    }

    override fun suspend() {
        if (!desiredRunning.get()) return
        suspended.set(true)
        Log.i(TAG, "JARVIS_WAKE_SUSPENDED_TTS")
        stopInternal(keepDesired = true)
    }

    override fun resume() {
        if (!desiredRunning.get()) return
        suspended.set(false)
        Log.i(TAG, "JARVIS_WAKE_RESUMED")
        mainHandler.post { beginListening() }
    }

    override fun isRunning(): Boolean = desiredRunning.get() && !suspended.get()

    override fun destroy() {
        stop()
        onWake = null
    }

    private fun beginListening() {
        if (!desiredRunning.get() || suspended.get()) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.e(TAG, "JARVIS_WAKE_ERROR recognition unavailable")
            return
        }
        cancelRestart()
        destroyRecognizer()
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(listener)
                speechRecognizer = it
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                // Prefer shorter utterances for wake detection.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    900L
                )
            }
            recognizer.startListening(intent)
            listening.set(true)
        } catch (t: Throwable) {
            Log.e(TAG, "JARVIS_WAKE_ERROR", t)
            listening.set(false)
            scheduleRestart(delayMs = 1200L)
        }
    }

    private fun stopInternal(keepDesired: Boolean) {
        if (!keepDesired) {
            desiredRunning.set(false)
        }
        cancelRestart()
        destroyRecognizer()
        listening.set(false)
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "destroyRecognizer", t)
        } finally {
            speechRecognizer = null
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!desiredRunning.get() || suspended.get()) return
        cancelRestart()
        val runnable = Runnable { beginListening() }
        restartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelRestart() {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun restartDelayFor(error: Int): Long {
        return when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
            SpeechRecognizer.ERROR_CLIENT -> 500L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 5000L
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 1500L
            else -> 350L
        }
    }

    companion object {
        private const val TAG = "JarvisWake"
    }
}
