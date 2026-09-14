package com.jarvis.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short spoken acknowledgements only — never long technical dumps.
 * Exposes speak start/end so hands-free wake detection can suspend during TTS.
 */
class JarvisTTS(context: Context) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onTtsStarted()
        fun onTtsFinished()
    }

    private val ready = AtomicBoolean(false)
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile
    var listener: Listener? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    listener?.onTtsStarted()
                }

                override fun onDone(utteranceId: String?) {
                    listener?.onTtsFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    listener?.onTtsFinished()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    listener?.onTtsFinished()
                }
            })
            ready.set(true)
        } else {
            Log.w(TAG, "TTS init failed: $status")
        }
    }

    fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || !ready.get()) return
        tts?.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${trimmed.hashCode()}")
    }

    fun shutdown() {
        listener = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    companion object {
        private const val TAG = "JarvisTTS"
    }
}
