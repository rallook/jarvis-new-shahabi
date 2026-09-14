package com.jarvis.assistant.conversation

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Ends a conversation only after both user and Jarvis have been inactive
 * for [silenceMs] continuously. Activity on either side cancels the timer.
 * Re-arming while already idle does not reset the countdown.
 */
class ConversationSilenceController(
    private val silenceMs: Long = DEFAULT_SILENCE_MS,
    private val onSilenceElapsed: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var userActive = false
    private var jarvisActive = false
    private var enabled = false
    private var timerArmed = false

    private val silenceRunnable = Runnable {
        timerArmed = false
        if (!enabled) return@Runnable
        if (userActive || jarvisActive) return@Runnable
        Log.i(TAG, "CONVERSATION_ENDED (silence)")
        enabled = false
        onSilenceElapsed()
    }

    fun startSession() {
        enabled = true
        userActive = false
        jarvisActive = false
        cancelTimer()
    }

    fun stopSession() {
        enabled = false
        userActive = false
        jarvisActive = false
        cancelTimer()
    }

    fun onUserActivity() {
        if (!enabled) return
        userActive = true
        cancelTimer()
    }

    fun onUserIdle() {
        if (!enabled) return
        userActive = false
        maybeArmTimer()
    }

    fun onJarvisActivity() {
        if (!enabled) return
        jarvisActive = true
        cancelTimer()
    }

    fun onJarvisIdle() {
        if (!enabled) return
        jarvisActive = false
        maybeArmTimer()
    }

    /** Pause while WhatsApp confirmation (or similar) is awaiting explicit input. */
    fun pause() {
        cancelTimer()
    }

    fun resumeIfIdle() {
        if (!enabled) return
        maybeArmTimer()
    }

    private fun maybeArmTimer() {
        if (!enabled) return
        if (userActive || jarvisActive) return
        if (timerArmed) return
        timerArmed = true
        Log.i(TAG, "SILENCE_TIMER_STARTED")
        handler.postDelayed(silenceRunnable, silenceMs)
    }

    private fun cancelTimer() {
        handler.removeCallbacks(silenceRunnable)
        if (timerArmed) {
            Log.d(TAG, "SILENCE_TIMER_CANCELLED")
        }
        timerArmed = false
    }

    companion object {
        const val DEFAULT_SILENCE_MS = 3_000L
        private const val TAG = "JarvisSilence"
    }
}
