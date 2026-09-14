package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Coordinates the shared microphone foreground service between conversation STT
 * and idle wake listening so only one FGS notification is shown.
 */
object MicForegroundGate {

    @Volatile
    private var commandHolding = false

    @Volatile
    private var wakeHolding = false

    fun setCommandHolding(context: Context, holding: Boolean) {
        commandHolding = holding
        sync(context.applicationContext)
    }

    fun setWakeHolding(context: Context, holding: Boolean) {
        wakeHolding = holding
        sync(context.applicationContext)
    }

    fun isHeld(): Boolean = commandHolding || wakeHolding

    private fun sync(appContext: Context) {
        if (commandHolding || wakeHolding) {
            val mode = when {
                commandHolding -> SpeechRecognitionForegroundService.MODE_COMMAND
                else -> SpeechRecognitionForegroundService.MODE_WAKE
            }
            val intent = Intent(appContext, SpeechRecognitionForegroundService::class.java).apply {
                action = SpeechRecognitionForegroundService.ACTION_START
                putExtra(SpeechRecognitionForegroundService.EXTRA_MODE, mode)
            }
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to start mic FGS", t)
            }
        } else {
            val intent = Intent(appContext, SpeechRecognitionForegroundService::class.java).apply {
                action = SpeechRecognitionForegroundService.ACTION_STOP
            }
            try {
                appContext.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to stop mic FGS", t)
            }
        }
    }

    private const val TAG = "MicForegroundGate"
}
