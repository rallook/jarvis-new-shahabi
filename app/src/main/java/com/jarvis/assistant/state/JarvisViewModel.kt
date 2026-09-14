package com.jarvis.assistant.state

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ai.AssistantTurnResult
import com.jarvis.assistant.ai.ConversationMemoryTurn
import com.jarvis.assistant.ai.JarvisBrain
import com.jarvis.assistant.android.AppLauncher
import com.jarvis.assistant.android.SystemInfoHelper
import com.jarvis.assistant.assistant.AssistantRoleHelper
import com.jarvis.assistant.commands.CommandExecutor
import com.jarvis.assistant.commands.CommandResult
import com.jarvis.assistant.commands.ConfirmationPhraseParser
import com.jarvis.assistant.commands.JarvisCommand
import com.jarvis.assistant.conversation.ConversationMemory
import com.jarvis.assistant.conversation.ConversationSilenceController
import com.jarvis.assistant.overlay.JarvisOverlayController
import com.jarvis.assistant.settings.JarvisSettings
import com.jarvis.assistant.settings.SettingsRepository
import com.jarvis.assistant.tts.JarvisTTS
import com.jarvis.assistant.ui.SettingsScreenState
import com.jarvis.assistant.utils.AccessibilityUtils
import com.jarvis.assistant.voice.AndroidSpeechRecognizerManager
import com.jarvis.assistant.voice.MicForegroundGate
import com.jarvis.assistant.voice.MicrophoneSessionManager
import com.jarvis.assistant.voice.SpeechRecognizerManager
import com.jarvis.assistant.wake.JarvisAckPhrases
import com.jarvis.assistant.wake.JarvisHandsFreeController
import com.jarvis.assistant.wake.WakePhraseParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository.getInstance(application)
    private val speech: SpeechRecognizerManager = AndroidSpeechRecognizerManager(application)
    private val brain = JarvisBrain(settingsRepository)
    private val appLauncher = AppLauncher(application)
    private val executor = CommandExecutor(appLauncher)
    private val tts = JarvisTTS(application)
    private val overlay = JarvisOverlayController.getInstance(application)
    private val handsFree = JarvisHandsFreeController.getInstance(application)
    private val conversationMemory = ConversationMemory()
    private val micSession = MicrophoneSessionManager()
    private val silenceController = ConversationSilenceController {
        viewModelScope.launch { endConversation(reason = "silence") }
    }

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val _settingsState = MutableStateFlow(SettingsScreenState())
    val settingsState: StateFlow<SettingsScreenState> = _settingsState.asStateFlow()

    private var speechCollectJob: Job? = null
    private var pipelineJob: Job? = null
    private var lastHandledTranscription: String? = null
    /** True while STT is capturing Send/Cancel for a pending WhatsApp confirmation. */
    private var listeningForConfirmation = false
    /** Start confirmation mic after TTS finishes so Jarvis does not hear itself. */
    private var pendingConfirmationListen = false
    /** After TTS, resume conversational listening (not confirmation). */
    private var pendingConversationResume = false
    private var conversationActive = false
    /** Prevent TTS audio from being treated as a user command. */
    private var suppressSttWhileSpeaking = false
    /** True while listening only for “Jarvis stop” during TTS. */
    private var listeningForInterrupt = false
    /** Command to run after a wake acknowledgment finishes speaking. */
    private var pendingAfterAckCommand: String? = null
    private var bargeInJob: Job? = null

    init {
        overlay.hostCallbacks = object : JarvisOverlayController.HostCallbacks {
            override fun onToggleListening() = toggleListening()
            override fun onConfirmSend() = confirmSend()
            override fun onCancelSend() = cancelSend()
            override fun onSelectContact(choice: String) = selectContact(choice)
            override fun onSubmitTextCommand(text: String) = submitTextCommand(text)
            override fun onDismissPanel() = dismissFloatingPanel()
        }
        handsFree.bindHost(object : JarvisHandsFreeController.Host {
            override fun activateMicrophone(acknowledge: Boolean, commandAfterAck: String) =
                startListening(acknowledge = acknowledge, commandAfterAck = commandAfterAck)
            override fun submitCommand(text: String) = submitTextCommand(text)
            override fun isListening(): Boolean = _uiState.value.isListening
            override fun currentPhase(): JarvisPhase = _uiState.value.phase
            override fun isConversationActive(): Boolean = conversationActive
        })
        tts.listener = object : JarvisTTS.Listener {
            override fun onTtsStarted() {
                Log.i(TAG, "TTS_STARTED")
                suppressSttWhileSpeaking = true
                silenceController.onJarvisActivity()
                micSession.setSpeaking()
                handsFree.onTtsStarted()
                if (_uiState.value.phase != JarvisPhase.CONFIRMATION &&
                    _uiState.value.phase != JarvisPhase.SENDING
                ) {
                    _uiState.update {
                        it.copy(phase = JarvisPhase.SPEAKING, statusText = "Speaking…")
                    }
                }
                // Allow “Jarvis stop” barge-in while speaking.
                scheduleBargeInListening()
            }

            override fun onTtsFinished() {
                Log.i(TAG, "TTS_FINISHED")
                cancelBargeInListening()
                listeningForInterrupt = false
                suppressSttWhileSpeaking = false
                silenceController.onJarvisIdle()
                handsFree.onTtsFinished()
                // Leave SPEAKING ownership so mic can be acquired immediately.
                micSession.setWaitingForUser()

                val afterAck = pendingAfterAckCommand
                if (!afterAck.isNullOrBlank()) {
                    pendingAfterAckCommand = null
                    pendingConversationResume = false
                    handleFinalTranscription(afterAck)
                    return
                }
                if (pendingConfirmationListen && isAwaitingSendConfirmation()) {
                    pendingConfirmationListen = false
                    pendingConversationResume = false
                    startConfirmationListening()
                    return
                }
                val shouldResumeMic = conversationActive &&
                    !isAwaitingSendConfirmation() &&
                    (
                        pendingConversationResume ||
                            (
                                pipelineJob?.isActive != true &&
                                    _uiState.value.phase in setOf(
                                        JarvisPhase.SPEAKING,
                                        JarvisPhase.COMPLETED,
                                        JarvisPhase.WAITING_FOR_USER
                                    )
                                )
                        )
                if (shouldResumeMic) {
                    pendingConversationResume = false
                    resumeConversationListening()
                    return
                }
                // Intermediate status TTS during an action — keep working, don't open mic yet.
                if (_uiState.value.phase == JarvisPhase.SPEAKING && pipelineJob?.isActive == true) {
                    _uiState.update {
                        it.copy(phase = JarvisPhase.EXECUTING, statusText = "Working…")
                    }
                }
            }
        }
        speech.activityListener = object : SpeechRecognizerManager.ActivityListener {
            override fun onSpeechBeginning() {
                if (conversationActive && !listeningForConfirmation) {
                    silenceController.onUserActivity()
                }
            }

            override fun onSpeechEnding() {
                if (conversationActive && !listeningForConfirmation) {
                    silenceController.onUserIdle()
                }
            }
        }
        refreshSetupFlags()
        refreshSettingsState()
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _settingsState.update {
                    it.copy(settings = settings, savedMessage = it.savedMessage)
                }
            }
        }
        viewModelScope.launch {
            _uiState.collect { state ->
                overlay.publishState(state)
                handsFree.onPipelinePhase(state.phase, state.isListening)
            }
        }
        speechCollectJob = viewModelScope.launch {
            speech.state.collect { speechState ->
                if (suppressSttWhileSpeaking && !listeningForInterrupt && !listeningForConfirmation) {
                    // Ignore STT updates while Jarvis is speaking (anti echo-loop),
                    // unless we are in barge-in interrupt mode.
                    return@collect
                }
                _uiState.update { current ->
                    current.copy(
                        isListening = speechState.isListening || listeningForInterrupt,
                        liveTranscription = if (listeningForInterrupt) {
                            speechState.liveTranscription.ifBlank { current.liveTranscription }
                        } else {
                            speechState.liveTranscription.ifBlank { current.liveTranscription }
                        },
                        audioLevel = speechState.rmsLevel,
                        errorMessage = if (listeningForConfirmation || listeningForInterrupt) {
                            null
                        } else {
                            speechState.error ?: current.errorMessage
                        },
                        phase = when {
                            speechState.isListening && listeningForConfirmation ->
                                JarvisPhase.CONFIRMATION
                            listeningForInterrupt && current.phase == JarvisPhase.SPEAKING ->
                                JarvisPhase.SPEAKING
                            speechState.isListening -> JarvisPhase.LISTENING
                            current.phase == JarvisPhase.LISTENING &&
                                speechState.finalTranscription.isNotBlank() -> JarvisPhase.TRANSCRIBING
                            current.phase == JarvisPhase.WAITING_FOR_USER &&
                                speechState.finalTranscription.isNotBlank() -> JarvisPhase.TRANSCRIBING
                            else -> current.phase
                        },
                        statusText = when {
                            speechState.isListening && listeningForConfirmation ->
                                "Say Send or Cancel…"
                            listeningForInterrupt -> "Speaking… (say Jarvis stop)"
                            speechState.isListening -> "Listening…"
                            (current.phase == JarvisPhase.LISTENING ||
                                current.phase == JarvisPhase.WAITING_FOR_USER) &&
                                speechState.finalTranscription.isNotBlank() -> "Transcribing…"
                            else -> current.statusText
                        }
                    )
                }

                val finalText = speechState.finalTranscription.trim()
                if (!speechState.isListening &&
                    finalText.isNotBlank() &&
                    finalText != lastHandledTranscription
                ) {
                    if (listeningForInterrupt) {
                        lastHandledTranscription = finalText
                        listeningForInterrupt = false
                        if (WakePhraseParser.isStopCommand(finalText)) {
                            handleStopInterrupt()
                        } else {
                            // Ignore non-stop phrases during barge-in (likely TTS echo).
                            Log.d(TAG, "Barge-in ignored non-stop phrase")
                        }
                    } else if (listeningForConfirmation && isAwaitingSendConfirmation()) {
                        lastHandledTranscription = finalText
                        listeningForConfirmation = false
                        stopMicForeground()
                        handleConfirmationVoice(finalText)
                    } else if (_uiState.value.phase == JarvisPhase.TRANSCRIBING ||
                        conversationActive
                    ) {
                        lastHandledTranscription = finalText
                        handleFinalTranscription(finalText)
                    }
                } else if (!speechState.isListening &&
                    !speechState.error.isNullOrBlank() &&
                    listeningForInterrupt
                ) {
                    listeningForInterrupt = false
                    // Soft fail — keep TTS / wait for TTS finish to resume normal mic.
                } else if (!speechState.isListening &&
                    !speechState.error.isNullOrBlank() &&
                    listeningForConfirmation
                ) {
                    listeningForConfirmation = false
                    stopMicForeground()
                    _uiState.update {
                        it.copy(
                            isListening = false,
                            phase = JarvisPhase.CONFIRMATION,
                            statusText = "Say Send or Cancel",
                            errorMessage = null
                        )
                    }
                    viewModelScope.launch {
                        delay(450)
                        if (isAwaitingSendConfirmation() && !_uiState.value.isListening) {
                            startConfirmationListening()
                        }
                    }
                } else if (!speechState.isListening &&
                    !speechState.error.isNullOrBlank() &&
                    conversationActive &&
                    !listeningForConfirmation &&
                    _uiState.value.phase in setOf(
                        JarvisPhase.LISTENING,
                        JarvisPhase.WAITING_FOR_USER
                    )
                ) {
                    // Soft STT errors during conversation: keep session, let silence timer decide.
                    stopMicForeground()
                    micSession.setWaitingForUser()
                    silenceController.onUserIdle()
                    _uiState.update {
                        it.copy(
                            isListening = false,
                            phase = JarvisPhase.WAITING_FOR_USER,
                            statusText = "Waiting for you…",
                            errorMessage = null
                        )
                    }
                    // Restart listening shortly so conversation can continue; silence still counts.
                    viewModelScope.launch {
                        delay(400)
                        if (conversationActive &&
                            !_uiState.value.isListening &&
                            !suppressSttWhileSpeaking &&
                            !isAwaitingSendConfirmation()
                        ) {
                            resumeConversationListening()
                        }
                    }
                } else if (!speechState.isListening &&
                    !speechState.error.isNullOrBlank() &&
                    !conversationActive &&
                    _uiState.value.phase == JarvisPhase.LISTENING
                ) {
                    stopMicForeground()
                    micSession.release()
                    _uiState.update {
                        it.copy(
                            phase = JarvisPhase.ERROR,
                            statusText = "Error",
                            assistantMessage = speechState.error,
                            errorMessage = speechState.error,
                            isListening = false
                        )
                    }
                }
            }
        }
    }

    fun onHostForegroundChanged(inForeground: Boolean) {
        overlay.setHostInForeground(inForeground)
    }

    fun requestOverlayPermissionIntent(): android.content.Intent = overlay.overlayPermissionIntent()

    fun dismissFloatingPanel() {
        // Explicit only: X / swipe / CLOSE_JARVIS.
        endConversation(reason = "dismiss")
        overlay.dismissOverlay()
        resetToIdle()
    }

    fun refreshSetupFlags() {
        val app = getApplication<Application>()
        val accessibility = AccessibilityUtils.isJarvisAccessibilityEnabled(app) ||
            JarvisAccessibilityService.isConnected()
        val overlayGranted = overlay.canDrawOverlays()
        _uiState.update {
            it.copy(
                needsAccessibility = !accessibility,
                needsOverlayPermission = !overlayGranted,
                setupComplete = accessibility && !it.needsMicrophone
            )
        }
        refreshSettingsState()
    }

    fun refreshSettingsState() {
        val app = getApplication<Application>()
        val accessibility = AccessibilityUtils.isJarvisAccessibilityEnabled(app) ||
            JarvisAccessibilityService.isConnected()
        val micGranted = !_uiState.value.needsMicrophone
        val overlayGranted = overlay.canDrawOverlays()
        val assistantAvailable = AssistantRoleHelper.isAssistantRoleAvailable(app)
        val assistantActive = AssistantRoleHelper.isAssistantRoleHeld(app)
        _settingsState.update {
            it.copy(
                settings = settingsRepository.get(),
                accessibilityEnabled = accessibility,
                microphoneGranted = micGranted,
                overlayGranted = overlayGranted,
                assistantRoleAvailable = assistantAvailable,
                assistantRoleActive = assistantActive
            )
        }
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                needsMicrophone = !granted,
                setupComplete = granted && !it.needsAccessibility
            )
        }
        _settingsState.update { it.copy(microphoneGranted = granted) }
        if (!granted) {
            Log.e(TAG, "JARVIS_MIC_PERMISSION_MISSING")
            handsFree.onMicPermissionLost()
            if (conversationActive) {
                endConversation(reason = "mic_permission")
            }
        } else {
            handsFree.onMicPermissionGranted()
        }
    }

    fun saveOpenAiApiKey(key: String) {
        settingsRepository.setOpenAiApiKey(key)
        flashSettingsMessage("API key saved.")
    }

    fun clearOpenAiApiKey() {
        settingsRepository.clearOpenAiApiKey()
        flashSettingsMessage("API key cleared.")
    }

    fun updateOpenAiModel(model: String) {
        settingsRepository.update {
            it.copy(openAiModel = model.trim().ifBlank { JarvisSettings.DEFAULT_MODEL })
        }
        flashSettingsMessage("Model saved.")
    }

    fun updateOpenAiBaseUrl(url: String) {
        settingsRepository.update {
            it.copy(openAiBaseUrl = url.trim().ifBlank { JarvisSettings.DEFAULT_BASE_URL })
        }
        flashSettingsMessage("Base URL saved.")
    }

    fun setUseSecureBackend(enabled: Boolean) {
        settingsRepository.update { it.copy(useSecureBackend = enabled) }
        flashSettingsMessage(if (enabled) "Secure backend enabled." else "Direct OpenAI mode.")
    }

    fun updateSecureBackendUrl(url: String) {
        settingsRepository.update { it.copy(secureBackendUrl = url.trim()) }
        flashSettingsMessage("Backend URL saved.")
    }

    fun setHeuristicFallback(enabled: Boolean) {
        settingsRepository.update { it.copy(allowHeuristicFallback = enabled) }
    }

    fun setTtsEnabled(enabled: Boolean) {
        settingsRepository.update { it.copy(ttsEnabled = enabled) }
    }

    fun setHandsFreeEnabled(enabled: Boolean) {
        handsFree.setHandsFreeEnabled(enabled)
        flashSettingsMessage(
            if (enabled) {
                "System assistant invocations enabled."
            } else {
                "System assistant invocations off. Microphone button still works."
            }
        )
        refreshSettingsState()
    }

    private fun flashSettingsMessage(message: String) {
        _settingsState.update { it.copy(savedMessage = message) }
        viewModelScope.launch {
            delay(2200)
            _settingsState.update {
                if (it.savedMessage == message) it.copy(savedMessage = null) else it
            }
        }
    }

    private fun speak(text: String, resumeConversationAfter: Boolean = false) {
        if (resumeConversationAfter && conversationActive) {
            pendingConversationResume = true
        }
        if (settingsRepository.get().ttsEnabled) {
            // Stop normal mic before speaking so Jarvis does not hear itself.
            if (_uiState.value.isListening && !listeningForInterrupt) {
                try {
                    speech.stopListening()
                } catch (_: Throwable) {
                }
                stopMicForeground()
            }
            val started = tts.speak(text)
            if (!started) {
                // TTS unavailable — clear speaking state and resume mic immediately.
                suppressSttWhileSpeaking = false
                micSession.setWaitingForUser()
                if (pendingAfterAckCommand != null) {
                    val cmd = pendingAfterAckCommand
                    pendingAfterAckCommand = null
                    if (!cmd.isNullOrBlank()) handleFinalTranscription(cmd)
                } else if (resumeConversationAfter && conversationActive) {
                    pendingConversationResume = false
                    resumeConversationListening()
                }
            } else if (resumeConversationAfter && conversationActive) {
                // Safety net if onDone never arrives.
                viewModelScope.launch {
                    delay(20_000)
                    if (pendingConversationResume && conversationActive &&
                        !tts.isSpeaking() &&
                        !isAwaitingSendConfirmation()
                    ) {
                        pendingConversationResume = false
                        suppressSttWhileSpeaking = false
                        silenceController.onJarvisIdle()
                        resumeConversationListening()
                    }
                }
            }
        } else if (resumeConversationAfter && conversationActive) {
            pendingConversationResume = false
            viewModelScope.launch {
                delay(150)
                resumeConversationListening()
            }
        }
    }

    fun submitTextCommand(text: String) {
        val trimmedInput = text.trim()
        if (trimmedInput.isBlank()) return
        if (isAwaitingSendConfirmation()) {
            handleConfirmationVoice(trimmedInput)
            return
        }
        if (WakePhraseParser.isStopCommand(trimmedInput)) {
            handleStopInterrupt()
            return
        }
        val trimmed = WakePhraseParser.stripWakePhrase(trimmedInput)
        if (trimmed.isBlank()) {
            // User only said “Jarvis”.
            beginConversationSessionIfNeeded()
            acknowledgeCallThenListen()
            return
        }
        beginConversationSessionIfNeeded()
        stopListeningInternal(keepConversation = true)
        lastHandledTranscription = trimmed
        _uiState.update {
            it.copy(
                liveTranscription = trimmed,
                finalTranscription = trimmed,
                phase = JarvisPhase.TRANSCRIBING,
                statusText = "Transcribing…",
                errorMessage = null,
                conversationActive = true
            )
        }
        handleFinalTranscription(trimmed)
    }

    fun toggleListening() {
        // Tap mic while speaking → treat as stop / interrupt.
        if (_uiState.value.phase == JarvisPhase.SPEAKING || tts.isSpeaking()) {
            handleStopInterrupt()
            return
        }
        if (_uiState.value.isListening) {
            if (conversationActive) {
                endConversation(reason = "manual_stop")
            } else {
                stopListening()
            }
        } else if (isAwaitingSendConfirmation()) {
            startConfirmationListening()
        } else {
            startListening(acknowledge = false)
        }
    }

    fun startListening(acknowledge: Boolean = false, commandAfterAck: String = "") {
        val phase = _uiState.value.phase
        val canStart = phase in setOf(
            JarvisPhase.IDLE,
            JarvisPhase.COMPLETED,
            JarvisPhase.ERROR,
            JarvisPhase.CONFIRMATION,
            JarvisPhase.LISTENING,
            JarvisPhase.WAITING_FOR_USER,
            JarvisPhase.ENDING,
            JarvisPhase.SPEAKING
        )
        if (!canStart) return
        if (_uiState.value.isListening && phase == JarvisPhase.LISTENING && !acknowledge) {
            return
        }

        beginConversationSessionIfNeeded()
        pipelineJob?.cancel()
        lastHandledTranscription = null
        pendingConversationResume = false
        cancelBargeInListening()
        listeningForInterrupt = false

        if (acknowledge) {
            if (commandAfterAck.isNotBlank()) {
                pendingAfterAckCommand = commandAfterAck.trim()
            }
            acknowledgeCallThenListen()
            return
        }

        handsFree.onManualListeningStarted()
        if (!micSession.forceAcquireListening()) {
            return
        }
        startMicForeground()
        silenceController.onUserActivity()
        _uiState.update {
            it.copy(
                phase = JarvisPhase.LISTENING,
                statusText = "Listening…",
                liveTranscription = "",
                finalTranscription = "",
                assistantMessage = if (conversationMemory.isEmpty()) "" else it.assistantMessage,
                previewMessage = null,
                confirmationTitle = null,
                pendingSend = null,
                contactChoices = emptyList(),
                errorMessage = null,
                isListening = true,
                conversationActive = true
            )
        }
        try {
            speech.startListening()
        } catch (t: Throwable) {
            Log.e(TAG, "ASSISTANT_ERROR startListening", t)
            stopMicForeground()
            micSession.release()
            fail("Your microphone permission is required for voice interaction.")
        }
    }

    fun stopListening() {
        val wasConfirmation = listeningForConfirmation || isAwaitingSendConfirmation()
        listeningForConfirmation = false
        speech.stopListening()
        stopMicForeground()
        micSession.release()
        if (wasConfirmation && _uiState.value.pendingSend != null) {
            _uiState.update {
                it.copy(
                    isListening = false,
                    phase = JarvisPhase.CONFIRMATION,
                    statusText = "Say Send or Cancel"
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isListening = false,
                phase = if (it.liveTranscription.isBlank() && it.finalTranscription.isBlank()) {
                    JarvisPhase.IDLE
                } else {
                    it.phase
                },
                statusText = if (it.liveTranscription.isBlank() && it.finalTranscription.isBlank()) {
                    "Ready"
                } else {
                    it.statusText
                }
            )
        }
    }

    private fun stopListeningInternal(keepConversation: Boolean) {
        listeningForConfirmation = false
        try {
            speech.stopListening()
        } catch (_: Throwable) {
        }
        stopMicForeground()
        if (keepConversation) {
            micSession.setProcessing()
        } else {
            micSession.release()
        }
        _uiState.update { it.copy(isListening = false) }
    }

    fun confirmSend() {
        val pending = _uiState.value.pendingSend ?: return
        stopConfirmationListeningOnly()
        pendingConfirmationListen = false
        silenceController.pause()
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            appLauncher.openWhatsApp()
            delay(700)
            setPhase(JarvisPhase.SENDING, "Sending…", "Sending message…")
            Log.i(TAG, "ACTION_STARTED")
            val result = executor.confirmAndSend(pending.contact, pending.message) { progress ->
                _uiState.update {
                    it.copy(assistantMessage = progress, statusText = "Working…")
                }
            }
            Log.i(TAG, "ACTION_RESULT")
            presentJarvisUiAfterAutomation()
            delay(300)
            when (result) {
                CommandResult.Success -> {
                    val msg = "Message sent to ${pending.contact}."
                    conversationMemory.addAssistant(msg)
                    setPhase(JarvisPhase.COMPLETED, "Completed", msg)
                    speak("Message sent.", resumeConversationAfter = conversationActive)
                }
                is CommandResult.Failure -> fail(result.message, keepConversation = conversationActive)
                else -> fail("Unexpected send result.", keepConversation = conversationActive)
            }
        }
    }

    fun cancelSend() {
        stopConfirmationListeningOnly()
        pendingConfirmationListen = false
        pipelineJob?.cancel()
        conversationMemory.addAssistant("Cancelled.")
        _uiState.update {
            it.copy(
                phase = if (conversationActive) JarvisPhase.WAITING_FOR_USER else JarvisPhase.IDLE,
                statusText = if (conversationActive) "Waiting for you…" else "Ready",
                assistantMessage = "Cancelled.",
                confirmationTitle = null,
                previewMessage = null,
                pendingSend = null,
                isListening = false
            )
        }
        speak("Cancelled.", resumeConversationAfter = conversationActive)
    }

    fun selectContact(choice: String) {
        val pending = _uiState.value.pendingSend ?: return
        val message = pending.message
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            runWhatsAppPipeline(
                JarvisCommand.SendWhatsAppMessage(contact = choice, message = message)
            )
        }
    }

    private fun beginConversationSessionIfNeeded() {
        if (conversationActive) {
            overlay.ensureFloatingUiVisible()
            return
        }
        conversationActive = true
        conversationMemory.clear()
        silenceController.startSession()
        overlay.ensureFloatingUiVisible()
        Log.i(TAG, "ASSISTANT_SESSION_CREATED")
    }

    private fun endConversation(reason: String) {
        if (!conversationActive && _uiState.value.phase == JarvisPhase.IDLE) {
            stopListeningInternal(keepConversation = false)
            return
        }
        Log.i(TAG, "CONVERSATION_ENDED reason=$reason")
        conversationActive = false
        pendingConversationResume = false
        pendingConfirmationListen = false
        pendingAfterAckCommand = null
        listeningForConfirmation = false
        listeningForInterrupt = false
        suppressSttWhileSpeaking = false
        cancelBargeInListening()
        try {
            tts.stop(notify = false)
        } catch (_: Throwable) {
        }
        silenceController.stopSession()
        conversationMemory.clear()
        micSession.setEnding()
        try {
            speech.stopListening()
        } catch (_: Throwable) {
        }
        stopMicForeground()
        micSession.release()
        pipelineJob?.cancel()
        // Mic/conversation stop — floating UI stays unless this was an explicit dismiss.
        _uiState.update {
            it.copy(
                phase = JarvisPhase.IDLE,
                statusText = "Ready",
                isListening = false,
                conversationActive = false,
                liveTranscription = "",
                confirmationTitle = null,
                previewMessage = null,
                pendingSend = null,
                contactChoices = emptyList(),
                errorMessage = null
            )
        }
    }

    private fun resumeConversationListening() {
        if (!conversationActive) return
        if (isAwaitingSendConfirmation()) return
        if (tts.isSpeaking()) return
        cancelBargeInListening()
        listeningForInterrupt = false
        suppressSttWhileSpeaking = false

        if (!micSession.forceAcquireListening()) {
            Log.w(TAG, "Could not force-acquire mic after TTS")
            return
        }
        lastHandledTranscription = null
        handsFree.onManualListeningStarted()
        startMicForeground()
        silenceController.onUserIdle()
        _uiState.update {
            it.copy(
                phase = JarvisPhase.LISTENING,
                statusText = "Listening…",
                isListening = true,
                liveTranscription = "",
                errorMessage = null,
                conversationActive = true
            )
        }
        try {
            speech.startListening()
            Log.i(TAG, "MIC_STARTED after TTS")
        } catch (t: Throwable) {
            Log.e(TAG, "ASSISTANT_ERROR resumeListening", t)
            stopMicForeground()
            micSession.setWaitingForUser()
            silenceController.onUserIdle()
            // Retry once shortly.
            viewModelScope.launch {
                delay(350)
                if (conversationActive && !tts.isSpeaking() && !_uiState.value.isListening) {
                    resumeConversationListening()
                }
            }
        }
    }

    private fun acknowledgeCallThenListen() {
        beginConversationSessionIfNeeded()
        handsFree.onManualListeningStarted()
        cancelBargeInListening()
        listeningForInterrupt = false
        try {
            speech.stopListening()
        } catch (_: Throwable) {
        }
        stopMicForeground()
        val ack = JarvisAckPhrases.forCall()
        conversationMemory.addAssistant(ack)
        _uiState.update {
            it.copy(
                phase = JarvisPhase.SPEAKING,
                statusText = "Speaking…",
                assistantMessage = ack,
                conversationActive = true,
                isListening = false
            )
        }
        speak(ack, resumeConversationAfter = true)
    }

    private fun handleStopInterrupt() {
        Log.i(TAG, "STOP interrupt")
        cancelBargeInListening()
        listeningForInterrupt = false
        pendingAfterAckCommand = null
        pipelineJob?.cancel()
        try {
            tts.stop(notify = false)
        } catch (_: Throwable) {
        }
        suppressSttWhileSpeaking = false
        beginConversationSessionIfNeeded()
        val ack = JarvisAckPhrases.forStopInterrupt()
        conversationMemory.addAssistant(ack)
        _uiState.update {
            it.copy(
                phase = JarvisPhase.SPEAKING,
                statusText = "Speaking…",
                assistantMessage = ack,
                conversationActive = true,
                isListening = false,
                errorMessage = null
            )
        }
        speak(ack, resumeConversationAfter = true)
    }

    private fun scheduleBargeInListening() {
        cancelBargeInListening()
        if (!conversationActive) return
        if (isAwaitingSendConfirmation()) return
        bargeInJob = viewModelScope.launch {
            // Give TTS audio focus a moment, then listen only for stop.
            delay(450)
            if (!tts.isSpeaking() || !conversationActive) return@launch
            listeningForInterrupt = true
            suppressSttWhileSpeaking = false
            try {
                startMicForeground()
                speech.startListening()
                Log.i(TAG, "BARGE_IN listening for stop")
            } catch (t: Throwable) {
                Log.d(TAG, "Barge-in listen unavailable", t)
                listeningForInterrupt = false
                suppressSttWhileSpeaking = true
            }
        }
    }

    private fun cancelBargeInListening() {
        bargeInJob?.cancel()
        bargeInJob = null
        if (listeningForInterrupt) {
            listeningForInterrupt = false
            try {
                speech.stopListening()
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleFinalTranscription(text: String) {
        stopMicForeground()
        micSession.setProcessing()
        silenceController.onUserIdle()
        cancelBargeInListening()

        if (WakePhraseParser.isStopCommand(text)) {
            handleStopInterrupt()
            return
        }

        val commandText = WakePhraseParser.stripWakePhrase(text)
        if (commandText.isBlank()) {
            Log.i(TAG, "Wake-only utterance — acknowledge")
            acknowledgeCallThenListen()
            return
        }
        Log.i(TAG, "STT_RESULT")
        conversationMemory.addUser(commandText)
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = JarvisPhase.THINKING,
                    statusText = "Thinking…",
                    finalTranscription = commandText,
                    liveTranscription = commandText,
                    assistantMessage = "Thinking…"
                )
            }

            val history = conversationMemory.snapshot()
                .dropLast(1) // current user turn is sent separately
                .map {
                    ConversationMemoryTurn(
                        role = when (it.role) {
                            ConversationMemory.Role.USER -> "user"
                            ConversationMemory.Role.ASSISTANT -> "assistant"
                        },
                        content = it.content
                    )
                }

            val turn = brain.understandTurn(commandText, history).getOrElse { error ->
                brain.parseHeuristicTurn(commandText)
                    ?: return@launch fail(
                        error.message ?: "I couldn't understand that.",
                        keepConversation = conversationActive
                    )
            }

            handleAssistantTurn(turn)
        }
    }

    private suspend fun handleAssistantTurn(turn: AssistantTurnResult) {
        when (turn) {
            is AssistantTurnResult.Chat -> {
                conversationMemory.addAssistant(turn.response)
                setPhase(JarvisPhase.SPEAKING, "Speaking…", turn.response)
                speak(turn.response, resumeConversationAfter = true)
            }
            is AssistantTurnResult.SystemQuery -> {
                val spoken = when (turn.queryId.uppercase()) {
                    "BATTERY", "BATTERY_LEVEL", "BATTERY_STATUS" ->
                        SystemInfoHelper.batteryStatusSpeech(getApplication())
                    else -> turn.spokenFallback
                        ?: "I don't have that system information yet."
                }
                conversationMemory.addAssistant(spoken)
                setPhase(JarvisPhase.SPEAKING, "Speaking…", spoken)
                speak(spoken, resumeConversationAfter = true)
            }
            is AssistantTurnResult.Action -> {
                Log.i(TAG, "ACTION_STARTED")
                runCommand(turn.command)
            }
            is AssistantTurnResult.ChatWithAction -> {
                conversationMemory.addAssistant(turn.response)
                Log.i(TAG, "ACTION_STARTED")
                runCommand(turn.command, preSpeak = turn.response)
            }
            is AssistantTurnResult.MultiStep -> {
                turn.response?.let { conversationMemory.addAssistant(it) }
                Log.i(TAG, "ACTION_STARTED")
                runMultiStep(turn.steps, turn.response)
            }
        }
    }

    private suspend fun runMultiStep(steps: List<JarvisCommand>, intro: String?) {
        if (!intro.isNullOrBlank()) {
            speak(intro)
            delay(400)
        }
        for ((index, step) in steps.withIndex()) {
            setPhase(
                JarvisPhase.EXECUTING,
                "Working…",
                "Step ${index + 1} of ${steps.size}…"
            )
            // WhatsApp confirmation must interrupt the multi-step chain.
            if (step is JarvisCommand.SendWhatsAppMessage) {
                runWhatsAppPipeline(step)
                return
            }
            if (step is JarvisCommand.CloseJarvis) {
                runCommand(step)
                return
            }
            val ok = executeAutomationStep(step)
            if (!ok) return
        }
        val done = "All steps completed."
        conversationMemory.addAssistant(done)
        setPhase(JarvisPhase.COMPLETED, "Completed", done)
        speak(done, resumeConversationAfter = conversationActive)
        Log.i(TAG, "ACTION_RESULT")
    }

    private suspend fun executeAutomationStep(command: JarvisCommand): Boolean {
        presentJarvisUiAfterAutomation()
        when (val result = executor.execute(command) { progress ->
            presentJarvisUiAfterAutomation()
            _uiState.update {
                it.copy(assistantMessage = progress, statusText = "Working…")
            }
        }) {
            CommandResult.Success -> {
                Log.i(TAG, "ACTION_RESULT")
                return true
            }
            is CommandResult.NeedsConfirmation,
            is CommandResult.NeedsContactChoice -> {
                // Should not happen for non-WhatsApp; treat as failure for chain.
                fail("That step needs confirmation and stopped the multi-step plan.", keepConversation = true)
                return false
            }
            is CommandResult.Failure -> {
                Log.i(TAG, "ACTION_RESULT")
                fail(result.message, keepConversation = conversationActive)
                return false
            }
            is CommandResult.Progress -> return true
        }
    }

    private suspend fun runCommand(command: JarvisCommand, preSpeak: String? = null) {
        when (command) {
            is JarvisCommand.CloseJarvis -> {
                speak("Closing, sir.")
                delay(400)
                dismissFloatingPanel()
            }
            is JarvisCommand.SendWhatsAppMessage -> {
                if (!preSpeak.isNullOrBlank()) speak(preSpeak)
                runWhatsAppPipeline(command)
            }
            is JarvisCommand.YouTubePlay -> {
                if (!preSpeak.isNullOrBlank()) {
                    conversationMemory.addAssistant(preSpeak)
                    speak(preSpeak)
                }
                runYouTubePlay(command)
            }
            is JarvisCommand.YouTubeSearch -> {
                if (!preSpeak.isNullOrBlank()) {
                    conversationMemory.addAssistant(preSpeak)
                    speak(preSpeak)
                }
                runYouTubeSearch(command)
            }
            is JarvisCommand.PlaySpotifySong -> runSimpleAutomation(
                speaking = preSpeak ?: "Opening Spotify.",
                executingMessage = "Opening Spotify…",
                command = command,
                successSpeak = "Playing ${command.song}.",
                successMessage = "Playing ${command.song}."
            )
            is JarvisCommand.SetTimer -> runSimpleAutomation(
                speaking = preSpeak ?: "Setting a timer.",
                executingMessage = "Setting timer…",
                command = command,
                successSpeak = "Timer set.",
                successMessage = "Timer set."
            )
            is JarvisCommand.SetAlarm -> runSimpleAutomation(
                speaking = preSpeak ?: "Setting an alarm.",
                executingMessage = "Setting alarm…",
                command = command,
                successSpeak = "Alarm set.",
                successMessage = "Alarm set."
            )
            is JarvisCommand.GoogleSearch -> runSimpleAutomation(
                speaking = preSpeak ?: "Searching Google.",
                executingMessage = "Searching Google…",
                command = command,
                successSpeak = "Search completed.",
                successMessage = "Search completed."
            )
            is JarvisCommand.OpenApp -> {
                if (!preSpeak.isNullOrBlank()) speak(preSpeak)
                setPhase(JarvisPhase.EXECUTING, "Working…", "Opening ${command.appName}…")
                when (val result = executor.execute(command) { msg ->
                    _uiState.update { it.copy(assistantMessage = msg) }
                }) {
                    CommandResult.Success -> {
                        val msg = "${command.appName} opened."
                        conversationMemory.addAssistant(msg)
                        setPhase(JarvisPhase.COMPLETED, "Completed", msg)
                        speak("${command.appName} is open.", resumeConversationAfter = conversationActive)
                        Log.i(TAG, "ACTION_RESULT")
                    }
                    is CommandResult.Failure -> fail(result.message, keepConversation = conversationActive)
                    else -> fail("Unexpected result.", keepConversation = conversationActive)
                }
            }
            is JarvisCommand.Unsupported -> {
                // Treat unsupported as chat so conversation continues.
                val msg = command.reason
                conversationMemory.addAssistant(msg)
                setPhase(JarvisPhase.SPEAKING, "Speaking…", msg)
                speak(msg, resumeConversationAfter = conversationActive)
            }
        }
    }

    private suspend fun runSimpleAutomation(
        speaking: String,
        executingMessage: String,
        command: JarvisCommand,
        successSpeak: String,
        successMessage: String
    ) {
        presentJarvisUiAfterAutomation()
        setPhase(JarvisPhase.EXECUTING, "Working…", executingMessage)
        speak(speaking)
        when (val result = executor.execute(command) { progress ->
            presentJarvisUiAfterAutomation()
            _uiState.update {
                it.copy(assistantMessage = progress, statusText = "Working…")
            }
        }) {
            CommandResult.Success -> {
                presentJarvisUiAfterAutomation()
                conversationMemory.addAssistant(successMessage)
                setPhase(JarvisPhase.COMPLETED, "Completed", successMessage)
                speak(successSpeak, resumeConversationAfter = conversationActive)
                Log.i(TAG, "ACTION_RESULT")
            }
            is CommandResult.Failure -> {
                presentJarvisUiAfterAutomation()
                fail(result.message, keepConversation = conversationActive)
            }
            else -> fail("Unexpected result.", keepConversation = conversationActive)
        }
    }

    private suspend fun runYouTubePlay(command: JarvisCommand.YouTubePlay) {
        presentJarvisUiAfterAutomation()
        setPhase(JarvisPhase.EXECUTING, "Working…", "Opening YouTube…")
        speak("Opening YouTube.")
        when (val result = executor.execute(command) { progress ->
            presentJarvisUiAfterAutomation()
            _uiState.update {
                it.copy(assistantMessage = progress, statusText = "Working…")
            }
        }) {
            CommandResult.Success -> {
                presentJarvisUiAfterAutomation()
                val msg = "Playing ${command.songName}."
                conversationMemory.addAssistant(msg)
                setPhase(JarvisPhase.COMPLETED, "Completed", msg)
                speak(msg, resumeConversationAfter = conversationActive)
                Log.i(TAG, "ACTION_RESULT")
            }
            is CommandResult.Failure -> {
                presentJarvisUiAfterAutomation()
                fail(result.message, keepConversation = conversationActive)
            }
            else -> fail("Unexpected YouTube result.", keepConversation = conversationActive)
        }
    }

    private suspend fun runYouTubeSearch(command: JarvisCommand.YouTubeSearch) {
        presentJarvisUiAfterAutomation()
        setPhase(JarvisPhase.EXECUTING, "Working…", "Opening YouTube…")
        speak("Searching YouTube.")
        when (val result = executor.execute(command) { progress ->
            presentJarvisUiAfterAutomation()
            _uiState.update {
                it.copy(assistantMessage = progress, statusText = "Working…")
            }
        }) {
            CommandResult.Success -> {
                presentJarvisUiAfterAutomation()
                val msg = "Showing YouTube results for ${command.query}."
                conversationMemory.addAssistant(msg)
                setPhase(JarvisPhase.COMPLETED, "Completed", msg)
                speak("Here are the results.", resumeConversationAfter = conversationActive)
                Log.i(TAG, "ACTION_RESULT")
            }
            is CommandResult.Failure -> {
                presentJarvisUiAfterAutomation()
                fail(result.message, keepConversation = conversationActive)
            }
            else -> fail("Unexpected YouTube result.", keepConversation = conversationActive)
        }
    }

    private suspend fun runWhatsAppPipeline(command: JarvisCommand.SendWhatsAppMessage) {
        silenceController.pause()
        setPhase(JarvisPhase.EXECUTING, "Working…", "Opening WhatsApp…")
        speak("Opening WhatsApp.")

        when (val result = executor.execute(command) { progress ->
            _uiState.update {
                it.copy(assistantMessage = progress, statusText = "Working…")
            }
            when {
                progress.contains("Finding", ignoreCase = true) -> speak("Finding ${command.contact}.")
                progress.contains("WhatsApp is open", ignoreCase = true) -> speak("WhatsApp is open.")
            }
        }) {
            is CommandResult.NeedsConfirmation -> {
                presentJarvisUiAfterAutomation()
                delay(350)
                _uiState.update {
                    it.copy(
                        phase = JarvisPhase.CONFIRMATION,
                        statusText = "Say Send or Cancel",
                        assistantMessage = "Say “Send the message” or “Cancel”, or tap a button.",
                        confirmationTitle = "Send this message to ${command.contact}?",
                        previewMessage = command.message,
                        pendingSend = PendingWhatsAppSend(
                            contact = command.contact,
                            message = command.message,
                            packageName = result.packageName
                        ),
                        contactChoices = emptyList()
                    )
                }
                speak("Ready to send. Say send or cancel.")
                scheduleConfirmationListeningAfterPrompt()
                Log.i(TAG, "ACTION_RESULT")
            }
            is CommandResult.NeedsContactChoice -> {
                presentJarvisUiAfterAutomation()
                delay(350)
                _uiState.update {
                    it.copy(
                        phase = JarvisPhase.CONFIRMATION,
                        statusText = "Waiting for confirmation",
                        assistantMessage = "Multiple contacts match \"${result.contactQuery}\". Choose one:",
                        confirmationTitle = null,
                        previewMessage = result.message,
                        pendingSend = PendingWhatsAppSend(
                            contact = result.contactQuery,
                            message = result.message,
                            packageName = appLauncher.resolveWhatsAppPackage().orEmpty()
                        ),
                        contactChoices = result.choices.map { ContactChoice(it) }
                    )
                }
                speak("Multiple contacts found.")
                Log.i(TAG, "ACTION_RESULT")
            }
            is CommandResult.Failure -> {
                presentJarvisUiAfterAutomation()
                fail(result.message, keepConversation = conversationActive)
            }
            CommandResult.Success -> {
                conversationMemory.addAssistant("Done.")
                setPhase(JarvisPhase.COMPLETED, "Completed", "Done.")
                speak("Done.", resumeConversationAfter = conversationActive)
                Log.i(TAG, "ACTION_RESULT")
            }
            is CommandResult.Progress -> Unit
        }
    }

    /**
     * Prefer the floating overlay while other apps stay in front. Only bring
     * Jarvis Activity forward when overlay permission is not available.
     */
    private fun presentJarvisUiAfterAutomation() {
        if (overlay.canDrawOverlays()) {
            overlay.setHostInForeground(false)
            return
        }
        appLauncher.bringJarvisToForeground()
    }

    private fun setPhase(phase: JarvisPhase, status: String, message: String) {
        _uiState.update {
            it.copy(
                phase = phase,
                statusText = status,
                assistantMessage = message,
                errorMessage = null,
                conversationActive = conversationActive
            )
        }
    }

    private fun fail(message: String, keepConversation: Boolean = false) {
        stopMicForeground()
        if (!keepConversation) {
            micSession.release()
        } else {
            micSession.setWaitingForUser()
        }
        conversationMemory.addAssistant(message)
        _uiState.update {
            it.copy(
                phase = if (keepConversation) JarvisPhase.WAITING_FOR_USER else JarvisPhase.ERROR,
                statusText = if (keepConversation) "Waiting for you…" else "Error",
                assistantMessage = message,
                errorMessage = if (keepConversation) null else message,
                confirmationTitle = null,
                pendingSend = null,
                conversationActive = conversationActive && keepConversation
            )
        }
        speak(
            if (keepConversation) message else "Something went wrong.",
            resumeConversationAfter = keepConversation && conversationActive
        )
        if (!keepConversation) {
            // Keep ERROR visible until the user dismisses the floating panel.
        }
    }

    private fun resetToIdle() {
        pendingConfirmationListen = false
        pendingConversationResume = false
        pendingAfterAckCommand = null
        listeningForConfirmation = false
        listeningForInterrupt = false
        cancelBargeInListening()
        conversationActive = false
        silenceController.stopSession()
        conversationMemory.clear()
        micSession.release()
        _uiState.update {
            JarvisUiState(
                phase = JarvisPhase.IDLE,
                statusText = "Ready",
                needsMicrophone = it.needsMicrophone,
                needsAccessibility = it.needsAccessibility,
                needsOverlayPermission = it.needsOverlayPermission,
                setupComplete = it.setupComplete,
                conversationActive = false
            )
        }
    }

    private fun isAwaitingSendConfirmation(): Boolean {
        val state = _uiState.value
        return state.phase == JarvisPhase.CONFIRMATION &&
            state.pendingSend != null &&
            state.contactChoices.isEmpty()
    }

    private fun scheduleConfirmationListeningAfterPrompt() {
        if (settingsRepository.get().ttsEnabled) {
            pendingConfirmationListen = true
        } else {
            viewModelScope.launch {
                delay(350)
                if (isAwaitingSendConfirmation()) {
                    startConfirmationListening()
                }
            }
        }
    }

    private fun startConfirmationListening() {
        if (!isAwaitingSendConfirmation()) return
        if (_uiState.value.isListening) return

        listeningForConfirmation = true
        lastHandledTranscription = null
        handsFree.onManualListeningStarted()
        startMicForeground()
        _uiState.update {
            it.copy(
                phase = JarvisPhase.CONFIRMATION,
                statusText = "Say Send or Cancel…",
                isListening = true,
                liveTranscription = "",
                errorMessage = null
            )
        }
        try {
            speech.startListening()
        } catch (t: Throwable) {
            Log.e(TAG, "Confirmation listen failed", t)
            listeningForConfirmation = false
            stopMicForeground()
            _uiState.update {
                it.copy(isListening = false, statusText = "Say Send or Cancel")
            }
        }
    }

    private fun stopConfirmationListeningOnly() {
        listeningForConfirmation = false
        try {
            speech.stopListening()
        } catch (_: Throwable) {
            // ignore
        }
        stopMicForeground()
        _uiState.update { it.copy(isListening = false) }
    }

    private fun handleConfirmationVoice(text: String) {
        val commandText = WakePhraseParser.stripWakePhrase(text).ifBlank { text.trim() }
        when (ConfirmationPhraseParser.parse(commandText)) {
            ConfirmationPhraseParser.Decision.SEND -> {
                Log.i(TAG, "Voice confirmation: SEND")
                confirmSend()
            }
            ConfirmationPhraseParser.Decision.CANCEL -> {
                Log.i(TAG, "Voice confirmation: CANCEL")
                cancelSend()
            }
            ConfirmationPhraseParser.Decision.UNKNOWN -> {
                Log.i(TAG, "Voice confirmation unclear")
                _uiState.update {
                    it.copy(
                        phase = JarvisPhase.CONFIRMATION,
                        statusText = "Say Send or Cancel",
                        assistantMessage = "I heard “$commandText”. Say “Send the message” or “Cancel”.",
                        isListening = false,
                        liveTranscription = commandText
                    )
                }
                viewModelScope.launch {
                    delay(500)
                    if (isAwaitingSendConfirmation() && !_uiState.value.isListening) {
                        startConfirmationListening()
                    }
                }
            }
        }
    }

    private fun startMicForeground() {
        MicForegroundGate.setCommandHolding(getApplication(), true)
    }

    private fun stopMicForeground() {
        MicForegroundGate.setCommandHolding(getApplication(), false)
    }

    override fun onCleared() {
        speechCollectJob?.cancel()
        pipelineJob?.cancel()
        bargeInJob?.cancel()
        pendingConfirmationListen = false
        pendingConversationResume = false
        pendingAfterAckCommand = null
        listeningForConfirmation = false
        listeningForInterrupt = false
        silenceController.stopSession()
        conversationMemory.clear()
        conversationActive = false
        speech.destroy()
        tts.shutdown()
        stopMicForeground()
        micSession.release()
        handsFree.bindHost(null)
        overlay.hostCallbacks = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "JarvisViewModel"
    }
}
