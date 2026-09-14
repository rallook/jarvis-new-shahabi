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

/**
 * Idle-only wake listener: runs only when Jarvis is not in an active conversation.
 * Detects “Jarvis” / “Hey Jarvis” to re-open the mic after silence ends the session.
 *
 * This is not a proprietary hotword DSP — it uses SpeechRecognizer in short bursts
 * while idle, and must be suspended during conversation / TTS.
 */
class IdleWakeWordEngine(context: Context) {

    data class WakeEvent(
        val rawTranscript: String,
        val commandAfterWake: String
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val desiredRunning = AtomicBoolean(false)
    private val suspended = AtomicBoolean(false)
    private var speechRecognizer: SpeechRecognizer? = null
    private var onWake: ((WakeEvent) -> Unit)? = null
    private var restartRunnable: Runnable? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            scheduleRestart(delayMs = restartDelayFor(error))
        }

        override fun onResults(results: Bundle?) {
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty().trim()
            if (best.isNotBlank()) {
                val after = WakePhraseParser.extractAfterWake(best)
                if (after != null) {
                    Log.i(TAG, "JARVIS_WAKE_DETECTED")
                    val callback = onWake
                    stopInternal(keepDesired = false)
                    callback?.invoke(WakeEvent(rawTranscript = best, commandAfterWake = after))
                    return
                }
            }
            scheduleRestart(delayMs = 350L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (partial.isBlank()) return
            val after = WakePhraseParser.extractAfterWake(partial)
            if (after != null && WakePhraseParser.isWakeOnly(partial)) {
                Log.i(TAG, "JARVIS_WAKE_DETECTED")
                val callback = onWake
                stopInternal(keepDesired = false)
                callback?.invoke(WakeEvent(rawTranscript = partial, commandAfterWake = ""))
            }
        }
    }

    fun start(onWake: (WakeEvent) -> Unit) {
        this.onWake = onWake
        desiredRunning.set(true)
        suspended.set(false)
        mainHandler.post { beginListening() }
    }

    fun stop() {
        desiredRunning.set(false)
        stopInternal(keepDesired = false)
    }

    fun suspend() {
        if (!desiredRunning.get()) return
        suspended.set(true)
        stopInternal(keepDesired = true)
    }

    fun resume() {
        if (!desiredRunning.get()) return
        suspended.set(false)
        mainHandler.post { beginListening() }
    }

    fun isRunning(): Boolean = desiredRunning.get() && !suspended.get()

    fun destroy() {
        stop()
        onWake = null
    }

    private fun beginListening() {
        if (!desiredRunning.get() || suspended.get()) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return
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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    900L
                )
            }
            recognizer.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "wake listen failed", t)
            scheduleRestart(delayMs = 1200L)
        }
    }

    private fun stopInternal(keepDesired: Boolean) {
        if (!keepDesired) desiredRunning.set(false)
        cancelRestart()
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Throwable) {
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

    private fun restartDelayFor(error: Int): Long = when (error) {
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
        SpeechRecognizer.ERROR_CLIENT -> 500L
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 5000L
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 1500L
        else -> 400L
    }

    companion object {
        private const val TAG = "JarvisIdleWake"
    }
}
