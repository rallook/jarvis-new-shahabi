package com.jarvis.assistant.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Lightweight system voice-interaction entry. Heavy UI / mic work is handled by
 * [JarvisVoiceInteractionSession] and the existing Jarvis pipeline — not here.
 *
 * Note: Android does not give third-party apps Google's proprietary hotword DSP.
 * Continuous “Jarvis” detection is owned by [com.jarvis.assistant.wake.WakeWordEngine].
 * This service enables the official assistant role / assist-gesture path.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "VoiceInteractionService ready")
    }

    override fun onShutdown() {
        Log.i(TAG, "VoiceInteractionService shutdown")
        super.onShutdown()
    }

    companion object {
        private const val TAG = "JarvisVIS"
    }
}
