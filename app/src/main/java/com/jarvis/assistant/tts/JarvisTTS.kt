package com.jarvis.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short spoken acknowledgements only — never long technical dumps.
 */
class JarvisTTS(context: Context) : TextToSpeech.OnInitListener {

    private val ready = AtomicBoolean(false)
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
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
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    companion object {
        private const val TAG = "JarvisTTS"
    }
}
