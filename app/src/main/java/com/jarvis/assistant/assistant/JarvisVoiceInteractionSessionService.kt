package com.jarvis.assistant.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Creates [JarvisVoiceInteractionSession] instances when the system invokes
 * the selected voice interaction / assistant.
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "ASSISTANT_SESSION_CREATED")
        return JarvisVoiceInteractionSession(this)
    }

    companion object {
        private const val TAG = "JarvisVISessionSvc"
    }
}
