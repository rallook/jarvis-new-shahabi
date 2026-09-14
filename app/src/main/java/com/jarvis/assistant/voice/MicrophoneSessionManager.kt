package com.jarvis.assistant.voice

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-owner mic session gate. Prevents duplicate listening sessions.
 * Does not own SpeechRecognizer itself — callers still use [SpeechRecognizerManager].
 */
class MicrophoneSessionManager {

    enum class State {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING,
        WAITING_FOR_USER,
        ENDING
    }

    private val state = AtomicReference(State.IDLE)

    fun current(): State = state.get()

    fun tryAcquireListening(): Boolean {
        val current = state.get()
        if (current == State.LISTENING) {
            Log.d(TAG, "MIC acquire denied — already LISTENING")
            return false
        }
        // SPEAKING/PROCESSING must be released first via forceAcquireListening / setWaitingForUser.
        if (current == State.SPEAKING || current == State.PROCESSING || current == State.ENDING) {
            Log.d(TAG, "MIC acquire denied — state=$current")
            return false
        }
        state.set(State.LISTENING)
        Log.i(TAG, "MIC_STARTED")
        return true
    }

    /**
     * Force transition into LISTENING from SPEAKING / PROCESSING / WAITING / IDLE.
     * Used the instant TTS ends so the mic always comes back on.
     */
    fun forceAcquireListening(): Boolean {
        val current = state.get()
        if (current == State.LISTENING) {
            Log.d(TAG, "MIC already LISTENING")
            return true
        }
        if (current == State.ENDING) {
            Log.d(TAG, "MIC acquire denied — ENDING")
            return false
        }
        state.set(State.LISTENING)
        Log.i(TAG, "MIC_STARTED")
        return true
    }

    fun setProcessing() {
        state.set(State.PROCESSING)
    }

    fun setSpeaking() {
        state.set(State.SPEAKING)
    }

    fun setWaitingForUser() {
        state.set(State.WAITING_FOR_USER)
    }

    fun setEnding() {
        state.set(State.ENDING)
        Log.i(TAG, "MIC_STOPPED")
    }

    fun release() {
        state.set(State.IDLE)
        Log.i(TAG, "MIC_STOPPED")
    }

    fun isBusy(): Boolean {
        val s = state.get()
        return s == State.LISTENING || s == State.PROCESSING || s == State.SPEAKING
    }

    companion object {
        private const val TAG = "JarvisMicSession"
    }
}
