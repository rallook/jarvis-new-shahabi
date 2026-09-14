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

/**
 * Bridges Android assistant invocations + idle “Jarvis” wake into the conversation pipeline.
 *
 * Idle wake runs only when no conversation is active. It is suspended during TTS / listening.
 */
class JarvisHandsFreeController private constructor(
    private val appContext: Context
) {
    interface Host {
        /**
         * Enter listening. [acknowledge] speaks a short “Yes, sir” first.
         * [commandAfterAck] is processed after the acknowledgment finishes.
         */
        fun activateMicrophone(acknowledge: Boolean = false, commandAfterAck: String = "")

        fun submitCommand(text: String)

        fun isListening(): Boolean

        fun currentPhase(): JarvisPhase

        fun isConversationActive(): Boolean
    }

    private val settings = SettingsRepository.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val state = java.util.concurrent.atomic.AtomicReference(HandsFreeState.DISABLED)
    private val wakeEngine = IdleWakeWordEngine(appContext)

    @Volatile
    private var host: Host? = null

    @Volatile
    private var ttsActive = false

    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        Log.i(TAG, "ASSISTANT_SERVICE_READY (hands-free bridge)")
        applyEnabledFromSettings(settings.get().handsFreeEnabled)
    }

    fun bindHost(host: Host?) {
        this.host = host
        if (host != null && settings.get().handsFreeEnabled) {
            ensureIdleWakeIfNeeded()
        }
    }

    fun setHandsFreeEnabled(enabled: Boolean) {
        settings.update { it.copy(handsFreeEnabled = enabled) }
        applyEnabledFromSettings(enabled)
    }

    fun isHandsFreeEnabled(): Boolean = settings.get().handsFreeEnabled

    fun currentState(): HandsFreeState = state.get()

    fun onAssistantInvoked() {
        if (!settings.get().handsFreeEnabled) {
            Log.i(TAG, "ASSISTANT_INVOCATION ignored — hands-free off")
            return
        }
        Log.i(TAG, "ASSISTANT_INVOCATION")
        mainHandler.post { handleActivation(commandAfterWake = "", acknowledge = true) }
    }

    fun onManualListeningStarted() {
        if (state.get() == HandsFreeState.DISABLED) return
        transition(HandsFreeState.LISTENING)
        wakeEngine.suspend()
        MicForegroundGate.setWakeHolding(appContext, false)
    }

    fun onMicPermissionGranted() {
        if (settings.get().handsFreeEnabled) {
            ensureIdleWakeIfNeeded()
        }
    }

    fun onPipelinePhase(phase: JarvisPhase, isListening: Boolean) {
        if (state.get() == HandsFreeState.DISABLED) return

        when (phase) {
            JarvisPhase.LISTENING, JarvisPhase.WAITING_FOR_USER -> {
                if (isListening) {
                    transition(HandsFreeState.LISTENING)
                    wakeEngine.suspend()
                }
            }
            JarvisPhase.TRANSCRIBING,
            JarvisPhase.THINKING,
            JarvisPhase.EXECUTING,
            JarvisPhase.SENDING,
            JarvisPhase.VERIFYING,
            JarvisPhase.SPEAKING -> {
                transition(HandsFreeState.PROCESSING)
                wakeEngine.suspend()
            }
            JarvisPhase.CONFIRMATION -> {
                transition(HandsFreeState.PROCESSING)
                wakeEngine.suspend()
            }
            JarvisPhase.ENDING -> {
                transition(HandsFreeState.COMMAND_COMPLETED)
            }
            JarvisPhase.COMPLETED, JarvisPhase.ERROR -> {
                transition(HandsFreeState.COMMAND_COMPLETED)
                scheduleIdleWake()
            }
            JarvisPhase.IDLE -> {
                if (!isListening && !ttsActive) {
                    transition(HandsFreeState.IDLE)
                    scheduleIdleWake()
                }
            }
        }
    }

    fun onTtsStarted() {
        ttsActive = true
        if (state.get() == HandsFreeState.DISABLED) return
        wakeEngine.suspend()
        transition(HandsFreeState.TTS_ACTIVE)
    }

    fun onTtsFinished() {
        ttsActive = false
        if (state.get() == HandsFreeState.DISABLED) return
        if (state.get() == HandsFreeState.TTS_ACTIVE) {
            transition(HandsFreeState.IDLE)
        }
        scheduleIdleWake(delayMs = 400L)
    }

    fun onMicPermissionLost() {
        Log.e(TAG, "JARVIS_MIC_PERMISSION_MISSING")
        wakeEngine.stop()
        MicForegroundGate.setWakeHolding(appContext, false)
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
            Log.i(TAG, "Hands-free assistant bridge disabled")
            return
        }
        transition(HandsFreeState.IDLE)
        Log.i(TAG, "Hands-free assistant bridge enabled")
        ensureIdleWakeIfNeeded()
    }

    private fun scheduleIdleWake(delayMs: Long = 500L) {
        mainHandler.removeCallbacks(idleWakeRunnable)
        mainHandler.postDelayed(idleWakeRunnable, delayMs)
    }

    private val idleWakeRunnable = Runnable {
        ensureIdleWakeIfNeeded()
    }

    private fun ensureIdleWakeIfNeeded() {
        if (!settings.get().handsFreeEnabled) return
        if (!hasMicPermission()) return
        if (ttsActive) return
        val h = host
        if (h?.isListening() == true) return
        if (h?.isConversationActive() == true) return
        val phase = h?.currentPhase()
        if (phase != null &&
            phase != JarvisPhase.IDLE &&
            phase != JarvisPhase.COMPLETED &&
            phase != JarvisPhase.ERROR
        ) {
            return
        }
        transition(HandsFreeState.IDLE)
        MicForegroundGate.setWakeHolding(appContext, true)
        if (!wakeEngine.isRunning()) {
            wakeEngine.start { event ->
                mainHandler.post {
                    handleActivation(
                        commandAfterWake = event.commandAfterWake,
                        acknowledge = true
                    )
                }
            }
        } else {
            wakeEngine.resume()
        }
    }

    private fun handleActivation(commandAfterWake: String, acknowledge: Boolean) {
        if (!settings.get().handsFreeEnabled) return

        val current = state.get()
        if (current == HandsFreeState.LISTENING ||
            current == HandsFreeState.PROCESSING ||
            current == HandsFreeState.WAKE_DETECTED ||
            current == HandsFreeState.TTS_ACTIVE
        ) {
            Log.d(TAG, "Ignoring activation while state=$current")
            return
        }

        val activeHost = host
        if (activeHost == null) {
            Log.e(TAG, "ASSISTANT_ERROR no host bound")
            transition(HandsFreeState.ERROR)
            scheduleIdleWake(delayMs = 1500L)
            return
        }

        if (activeHost.isListening() || activeHost.isConversationActive()) {
            Log.d(TAG, "Ignoring activation — conversation already active")
            return
        }

        if (!hasMicPermission()) {
            Log.e(TAG, "JARVIS_MIC_PERMISSION_MISSING")
            transition(HandsFreeState.ERROR)
            return
        }

        transition(HandsFreeState.WAKE_DETECTED)
        wakeEngine.suspend()
        MicForegroundGate.setWakeHolding(appContext, false)
        JarvisOverlayController.getInstance(appContext).setHostInForeground(false)

            try {
                val command = commandAfterWake.trim()
                transition(HandsFreeState.LISTENING)
                activeHost.activateMicrophone(
                    acknowledge = acknowledge,
                    commandAfterAck = command
                )
            } catch (t: Throwable) {
                Log.e(TAG, "ASSISTANT_ERROR", t)
                transition(HandsFreeState.ERROR)
                scheduleIdleWake(delayMs = 1200L)
            }
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
