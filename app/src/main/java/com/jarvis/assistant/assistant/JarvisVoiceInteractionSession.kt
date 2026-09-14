package com.jarvis.assistant.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.jarvis.assistant.wake.JarvisHandsFreeController

/**
 * Assist-gesture / default-assistant session. Activates the existing conversation
 * pipeline through [JarvisHandsFreeController] — does not run AI or mic logic itself.
 */
class JarvisVoiceInteractionSession(
    context: Context
) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "ASSISTANT_INVOCATION")
        try {
            JarvisHandsFreeController.getInstance(context).onAssistantInvoked()
        } catch (t: Throwable) {
            Log.e(TAG, "ASSISTANT_ERROR", t)
        }
        // Hide the system session UI immediately; Jarvis floating UI / activity handles UX.
        try {
            hide()
        } catch (_: Throwable) {
            // ignore
        }
    }

    companion object {
        private const val TAG = "JarvisVISession"
    }
}
