package com.jarvis.assistant.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Lightweight system voice-interaction entry. Heavy UI / mic / AI work is handled by
 * [JarvisVoiceInteractionSession] and the existing Jarvis conversation pipeline.
 *
 * Android does not give third-party apps Google's proprietary hotword DSP.
 * This service enables the official assistant role / assist-gesture path only.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "ASSISTANT_SERVICE_READY")
    }

    override fun onShutdown() {
        Log.i(TAG, "ASSISTANT_SERVICE_SHUTDOWN")
        super.onShutdown()
    }

    companion object {
        private const val TAG = "JarvisVIS"
    }
}
