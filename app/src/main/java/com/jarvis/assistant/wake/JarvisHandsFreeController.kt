package com.jarvis.assistant.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.overlay.JarvisOverlayController
import com.jarvis.assistant.settings.SettingsRepository
import com.jarvis.assistant.state.JarvisPhase
import com.jarvis.assistant.voice.MicForegroundGate
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands-free activation layer only.
 *
 * wake detected → existing microphone ON → existing command pipeline → mic OFF
 *
 * Does not contain WhatsApp / YouTube / Spotify / Google / Timer / Alarm logic.
 */
class JarvisHandsFreeController private constructor(
    private val appContext: Context
) {
    interface Host {
        /** Enter the existing mic / STT listening path (same as tapping the mic). */
        fun activateMicrophone()

        /** Feed a command that arrived in the same utterance as the wake phrase. */
        fun submitCommand(text: String)

        fun isListening(): Boolean

        fun currentPhase(): JarvisPhase
    }

    private val settings = SettingsRepository.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val state = AtomicReference(HandsFreeState.DISABLED)
    private val wakeEngine: WakeWordEngine = SpeechRecognizerWakeWordEngine(appContext)

    @Volatile
    private var host: Host? = null

    @Volatile
    private var ttsActive = false

    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        Log.i(TAG, "JARVIS_WAKE_INITIALIZING")
        applyEnabledFromSettings(settings.get().handsFreeEnabled)
    }

    fun bindHost(host: Host?) {
        this.host = host
        if (host != null && settings.get().handsFreeEnabled) {
            ensureWakeRunningIfIdle()
        }
    }

    fun setHandsFreeEnabled(enabled: Boolean) {
        settings.update { it.copy(handsFreeEnabled = enabled) }
        applyEnabledFromSettings(enabled)
    }

    fun isHandsFreeEnabled(): Boolean = settings.get().handsFreeEnabled

    fun currentState(): HandsFreeState = state.get()

    /**
     * System assistant / VoiceInteractionSession assist trigger — same path as wake word.
     */
    fun onAssistantInvoked() {
        if (!settings.get().handsFreeEnabled) return
        Log.i(TAG, "JARVIS_WAKE_DETECTED")
        mainHandler.post {
            handleWake(WakeEvent(rawTranscript = "Jarvis", commandAfterWake = ""))
        }
    }

    fun onManualListeningStarted() {
        if (state.get() == HandsFreeState.DISABLED) return
        transition(HandsFreeState.LISTENING)
        wakeEngine.suspend()
    }

    fun onMicPermissionGranted() {
        if (settings.get().handsFreeEnabled) {
            ensureWakeRunningIfIdle()
        }
    }

    fun onPipelinePhase(phase: JarvisPhase, isListening: Boolean) {
        if (state.get() == HandsFreeState.DISABLED) return

        when (phase) {
            JarvisPhase.LISTENING -> {
                if (isListening) {
                    transition(HandsFreeState.LISTENING)
                    wakeEngine.suspend()
                }
            }
            JarvisPhase.TRANSCRIBING,
            JarvisPhase.THINKING,
            JarvisPhase.EXECUTING,
            JarvisPhase.SENDING,
            JarvisPhase.VERIFYING -> {
                transition(HandsFreeState.PROCESSING)
                wakeEngine.suspend()
            }
            JarvisPhase.CONFIRMATION -> {
                transition(HandsFreeState.PROCESSING)
                wakeEngine.suspend()
            }
            JarvisPhase.COMPLETED -> {
                transition(HandsFreeState.COMMAND_COMPLETED)
                Log.i(TAG, "JARVIS_COMMAND_COMPLETED")
                Log.i(TAG, "JARVIS_HANDS_FREE_MIC_OFF")
                scheduleReturnToIdle()
            }
            JarvisPhase.ERROR -> {
                transition(HandsFreeState.ERROR)
                Log.i(TAG, "JARVIS_COMMAND_COMPLETED")
                Log.i(TAG, "JARVIS_HANDS_FREE_MIC_OFF")
                scheduleReturnToIdle()
            }
            JarvisPhase.IDLE -> {
                if (!isListening && !ttsActive) {
                    scheduleReturnToIdle()
                }
            }
        }
    }

    fun onTtsStarted() {
        ttsActive = true
        if (state.get() == HandsFreeState.DISABLED) return
        wakeEngine.suspend()
        if (state.get() == HandsFreeState.IDLE || state.get() == HandsFreeState.COMMAND_COMPLETED) {
            transition(HandsFreeState.TTS_ACTIVE)
        }
    }

    fun onTtsFinished() {
        ttsActive = false
        if (state.get() == HandsFreeState.DISABLED) return
        val current = state.get()
        if (current == HandsFreeState.TTS_ACTIVE ||
            current == HandsFreeState.COMMAND_COMPLETED ||
            current == HandsFreeState.ERROR ||
            current == HandsFreeState.IDLE
        ) {
            scheduleReturnToIdle(delayMs = 350L)
        }
    }

    fun onMicPermissionLost() {
        Log.e(TAG, "JARVIS_MIC_PERMISSION_MISSING")
        stopWakeEngineAndFgs()
        if (state.get() != HandsFreeState.DISABLED) {
            transition(HandsFreeState.ERROR)
        }
    }

    fun shutdown() {
        wakeEngine.destroy()
        MicForegroundGate.setWakeHolding(appContext, false)
        host = null
        transition(HandsFreeState.DISABLED)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun applyEnabledFromSettings(enabled: Boolean) {
        if (!enabled) {
            wakeEngine.stop()
            MicForegroundGate.setWakeHolding(appContext, false)
            transition(HandsFreeState.DISABLED)
            Log.i(TAG, "JARVIS_WAKE_DISABLED")
            return
        }
        transition(HandsFreeState.IDLE)
        ensureWakeRunningIfIdle()
    }

    private fun ensureWakeRunningIfIdle() {
        if (!settings.get().handsFreeEnabled) return
        if (!hasMicPermission()) {
            Log.e(TAG, "JARVIS_MIC_PERMISSION_MISSING")
            MicForegroundGate.setWakeHolding(appContext, false)
            return
        }
        if (ttsActive) return
        val hostRef = host
        if (hostRef?.isListening() == true) return
        val phase = hostRef?.currentPhase()
        if (phase != null && phase != JarvisPhase.IDLE &&
            phase != JarvisPhase.COMPLETED &&
            phase != JarvisPhase.ERROR
        ) {
            return
        }
        if (!JarvisOverlayController.getInstance(appContext).canDrawOverlays()) {
            Log.w(TAG, "JARVIS_OVERLAY_PERMISSION_MISSING")
        }
        transition(HandsFreeState.IDLE)
        MicForegroundGate.setWakeHolding(appContext, true)
        if (!wakeEngine.isRunning()) {
            wakeEngine.start { event ->
                mainHandler.post { handleWake(event) }
            }
        } else {
            wakeEngine.resume()
        }
    }

    private fun handleWake(event: WakeEvent) {
        if (!settings.get().handsFreeEnabled) return

        val current = state.get()
        if (current == HandsFreeState.LISTENING ||
            current == HandsFreeState.PROCESSING ||
            current == HandsFreeState.WAKE_DETECTED ||
            current == HandsFreeState.TTS_ACTIVE
        ) {
            Log.d(TAG, "Ignoring wake while state=$current")
            return
        }

        val activeHost = host
        if (activeHost == null) {
            Log.e(TAG, "JARVIS_WAKE_ERROR no host bound")
            transition(HandsFreeState.ERROR)
            scheduleReturnToIdle(delayMs = 1500L)
            return
        }

        if (activeHost.isListening()) {
            Log.d(TAG, "Ignoring wake — microphone already listening")
            return
        }

        transition(HandsFreeState.WAKE_DETECTED)
        Log.i(TAG, "JARVIS_WAKE_ACTIVATING_MIC")

        JarvisOverlayController.getInstance(appContext).setHostInForeground(false)

        val command = event.commandAfterWake.trim()
        try {
            if (command.isNotEmpty()) {
                Log.i(TAG, "JARVIS_COMMAND_RECEIVED")
                transition(HandsFreeState.PROCESSING)
                wakeEngine.suspend()
                MicForegroundGate.setWakeHolding(appContext, false)
                activeHost.submitCommand(command)
            } else {
                transition(HandsFreeState.LISTENING)
                Log.i(TAG, "JARVIS_HANDS_FREE_LISTENING")
                wakeEngine.suspend()
                MicForegroundGate.setWakeHolding(appContext, false)
                activeHost.activateMicrophone()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "JARVIS_WAKE_ERROR", t)
            transition(HandsFreeState.ERROR)
            scheduleReturnToIdle(delayMs = 1200L)
        }
    }

    private fun scheduleReturnToIdle(delayMs: Long = 500L) {
        mainHandler.removeCallbacks(returnToIdleRunnable)
        mainHandler.postDelayed(returnToIdleRunnable, delayMs)
    }

    private val returnToIdleRunnable = Runnable {
        if (!settings.get().handsFreeEnabled) {
            transition(HandsFreeState.DISABLED)
            stopWakeEngineAndFgs()
            return@Runnable
        }
        if (ttsActive) {
            transition(HandsFreeState.TTS_ACTIVE)
            return@Runnable
        }
        val h = host
        if (h?.isListening() == true) return@Runnable
        val phase = h?.currentPhase()
        if (phase == JarvisPhase.CONFIRMATION ||
            phase == JarvisPhase.THINKING ||
            phase == JarvisPhase.EXECUTING ||
            phase == JarvisPhase.SENDING ||
            phase == JarvisPhase.VERIFYING ||
            phase == JarvisPhase.TRANSCRIBING ||
            phase == JarvisPhase.LISTENING
        ) {
            return@Runnable
        }
        ensureWakeRunningIfIdle()
    }

    private fun stopWakeEngineAndFgs() {
        wakeEngine.stop()
        MicForegroundGate.setWakeHolding(appContext, false)
    }

    private fun transition(next: HandsFreeState) {
        state.set(next)
    }

    companion object {
        private const val TAG = "JarvisHandsFree"

        @Volatile
        private var instance: JarvisHandsFreeController? = null

        fun getInstance(context: Context): JarvisHandsFreeController {
            return instance ?: synchronized(this) {
                instance ?: JarvisHandsFreeController(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
