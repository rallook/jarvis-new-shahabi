package com.jarvis.assistant.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spoken Jarvis replies. Exposes speak start/end so conversation silence
 * tracking and mic ownership can pause while Jarvis is speaking.
 */
class JarvisTTS(context: Context) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onTtsStarted()
        fun onTtsFinished()
    }

    private val ready = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var activeUtteranceId: String? = null

    @Volatile
    var listener: Listener? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != null && activeUtteranceId != null &&
                        utteranceId != activeUtteranceId
                    ) {
                        return
                    }
                    speaking.set(true)
                    mainHandler.post {
                        Log.i(TAG, "TTS_STARTED")
                        listener?.onTtsStarted()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null && activeUtteranceId != null &&
                        utteranceId != activeUtteranceId
                    ) {
                        return
                    }
                    notifyFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null && activeUtteranceId != null &&
                        utteranceId != activeUtteranceId
                    ) {
                        return
                    }
                    notifyFinished()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId != null && activeUtteranceId != null &&
                        utteranceId != activeUtteranceId
                    ) {
                        return
                    }
                    notifyFinished()
                }
            })
            ready.set(true)
        } else {
            Log.w(TAG, "TTS init failed: $status")
        }
    }

    fun isSpeaking(): Boolean = speaking.get()

    fun speak(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank() || !ready.get()) {
            Log.w(TAG, "TTS speak skipped (blank or not ready)")
            return false
        }
        val utteranceId = "jarvis-${System.currentTimeMillis()}"
        activeUtteranceId = utteranceId
        speaking.set(true)
        val result = tts?.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == null || result == TextToSpeech.ERROR) {
            speaking.set(false)
            activeUtteranceId = null
            return false
        }
        return true
    }

    /** Immediately stop speech. [notify] false when a new speak() follows immediately. */
    fun stop(notify: Boolean = true) {
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
        if (notify) {
            notifyFinished()
        } else {
            speaking.set(false)
            activeUtteranceId = null
        }
    }

    fun shutdown() {
        listener = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        speaking.set(false)
        ready.set(false)
        activeUtteranceId = null
    }

    private fun notifyFinished() {
        val wasSpeaking = speaking.getAndSet(false)
        activeUtteranceId = null
        if (!wasSpeaking) return
        mainHandler.post {
            Log.i(TAG, "TTS_FINISHED")
            listener?.onTtsFinished()
        }
    }

    companion object {
        private const val TAG = "JarvisTTS"
    }
}
