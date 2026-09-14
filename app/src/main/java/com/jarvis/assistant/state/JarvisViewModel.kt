package com.jarvis.assistant.state

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ai.JarvisBrain
import com.jarvis.assistant.android.AppLauncher
import com.jarvis.assistant.assistant.AssistantRoleHelper
import com.jarvis.assistant.commands.CommandExecutor
import com.jarvis.assistant.commands.CommandResult
import com.jarvis.assistant.commands.ConfirmationPhraseParser
import com.jarvis.assistant.commands.JarvisCommand
import com.jarvis.assistant.overlay.JarvisOverlayController
import com.jarvis.assistant.settings.JarvisSettings
import com.jarvis.assistant.settings.SettingsRepository
import com.jarvis.assistant.tts.JarvisTTS
import com.jarvis.assistant.ui.SettingsScreenState
import com.jarvis.assistant.utils.AccessibilityUtils
import com.jarvis.assistant.voice.AndroidSpeechRecognizerManager
import com.jarvis.assistant.voice.MicForegroundGate
import com.jarvis.assistant.voice.SpeechRecognizerManager
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
            override fun activateMicrophone() = startListening()
            override fun submitCommand(text: String) = submitTextCommand(text)
            override fun isListening(): Boolean = _uiState.value.isListening
            override fun currentPhase(): JarvisPhase = _uiState.value.phase
        })
        tts.listener = object : JarvisTTS.Listener {
            override fun onTtsStarted() = handsFree.onTtsStarted()
            override fun onTtsFinished() {
                handsFree.onTtsFinished()
                if (pendingConfirmationListen && isAwaitingSendConfirmation()) {
                    pendingConfirmationListen = false
                    startConfirmationListening()
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
                _uiState.update { current ->
                    current.copy(
                        isListening = speechState.isListening,
                        liveTranscription = speechState.liveTranscription.ifBlank {
                            current.liveTranscription
                        },
                        audioLevel = speechState.rmsLevel,
                        errorMessage = if (listeningForConfirmation) {
                            null
                        } else {
                            speechState.error ?: current.errorMessage
                        },
                        phase = when {
                            speechState.isListening && listeningForConfirmation ->
                                JarvisPhase.CONFIRMATION
                            speechState.isListening -> JarvisPhase.LISTENING
                            current.phase == JarvisPhase.LISTENING &&
                                speechState.finalTranscription.isNotBlank() -> JarvisPhase.TRANSCRIBING
                            else -> current.phase
                        },
                        statusText = when {
                            speechState.isListening && listeningForConfirmation ->
                                "Say Send or Cancel…"
                            speechState.isListening -> "Listening…"
                            current.phase == JarvisPhase.LISTENING &&
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
                    if (listeningForConfirmation && isAwaitingSendConfirmation()) {
                        lastHandledTranscription = finalText
                        listeningForConfirmation = false
                        stopMicForeground()
                        handleConfirmationVoice(finalText)
                    } else if (_uiState.value.phase == JarvisPhase.TRANSCRIBING) {
                        lastHandledTranscription = finalText
                        handleFinalTranscription(finalText)
                    }
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
                    _uiState.value.phase == JarvisPhase.LISTENING
                ) {
                    stopMicForeground()
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
        // User-initiated only (X / swipe). Do not stop speech pipelines here.
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
            Log.e("JarvisViewModel", "JARVIS_MIC_PERMISSION_MISSING")
            handsFree.onMicPermissionLost()
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
                "Hands-free Jarvis enabled."
            } else {
                "Hands-free Jarvis off. Microphone button still works."
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

    private fun speak(text: String) {
        if (settingsRepository.get().ttsEnabled) {
            tts.speak(text)
        }
    }

    fun submitTextCommand(text: String) {
        val trimmedInput = text.trim()
        if (trimmedInput.isBlank()) return
        if (isAwaitingSendConfirmation()) {
            handleConfirmationVoice(trimmedInput)
            return
        }
        val trimmed = WakePhraseParser.stripWakePhrase(trimmedInput)
        if (trimmed.isBlank()) return
        stopListening()
        lastHandledTranscription = trimmed
        _uiState.update {
            it.copy(
                liveTranscription = trimmed,
                finalTranscription = trimmed,
                phase = JarvisPhase.TRANSCRIBING,
                statusText = "Transcribing…",
                errorMessage = null
            )
        }
        handleFinalTranscription(trimmed)
    }

    fun toggleListening() {
        if (_uiState.value.isListening) {
            stopListening()
        } else if (isAwaitingSendConfirmation()) {
            startConfirmationListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        val phase = _uiState.value.phase
        val canStart = phase in setOf(
            JarvisPhase.IDLE,
            JarvisPhase.COMPLETED,
            JarvisPhase.ERROR,
            JarvisPhase.CONFIRMATION,
            JarvisPhase.LISTENING
        )
        if (!canStart) return
        if (_uiState.value.isListening && phase == JarvisPhase.LISTENING) {
            // Duplicate protection — do not start a second recognizer session.
            return
        }

        pipelineJob?.cancel()
        lastHandledTranscription = null
        handsFree.onManualListeningStarted()
        startMicForeground()
        _uiState.update {
            it.copy(
                phase = JarvisPhase.LISTENING,
                statusText = "Listening…",
                liveTranscription = "",
                finalTranscription = "",
                assistantMessage = "",
                previewMessage = null,
                confirmationTitle = null,
                pendingSend = null,
                contactChoices = emptyList(),
                errorMessage = null,
                isListening = true
            )
        }
        speech.startListening()
    }

    fun stopListening() {
        val wasConfirmation = listeningForConfirmation || isAwaitingSendConfirmation()
        listeningForConfirmation = false
        speech.stopListening()
        stopMicForeground()
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

    fun confirmSend() {
        val pending = _uiState.value.pendingSend ?: return
        stopConfirmationListeningOnly()
        pendingConfirmationListen = false
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            // Return to WhatsApp so Accessibility can click Send on the prepared draft.
            appLauncher.openWhatsApp()
            delay(700)
            setPhase(JarvisPhase.SENDING, "Sending…", "Sending message…")
            val result = executor.confirmAndSend(pending.contact, pending.message) { progress ->
                _uiState.update {
                    it.copy(assistantMessage = progress, statusText = "Working…")
                }
            }
            presentJarvisUiAfterAutomation()
            delay(300)
            when (result) {
                CommandResult.Success -> {
                    setPhase(
                        JarvisPhase.COMPLETED,
                        "Completed",
                        "Message sent to ${pending.contact}."
                    )
                    speak("Message sent.")
                    // Keep COMPLETED visible until the user dismisses the floating panel.
                }
                is CommandResult.Failure -> fail(result.message)
                else -> fail("Unexpected send result.")
            }
        }
    }

    fun cancelSend() {
        stopConfirmationListeningOnly()
        pendingConfirmationListen = false
        pipelineJob?.cancel()
        _uiState.update {
            it.copy(
                phase = JarvisPhase.IDLE,
                statusText = "Ready",
                assistantMessage = "Cancelled.",
                confirmationTitle = null,
                previewMessage = null,
                pendingSend = null,
                isListening = false
            )
        }
        speak("Cancelled.")
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

    private fun handleFinalTranscription(text: String) {
        stopMicForeground()
        // Never send a wake-only utterance ("Jarvis") to OpenAI.
        val commandText = WakePhraseParser.stripWakePhrase(text)
        if (commandText.isBlank()) {
            Log.i("JarvisViewModel", "Wake-only utterance ignored; listening again")
            startListening()
            return
        }
        Log.i("JarvisViewModel", "JARVIS_COMMAND_RECEIVED")
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = JarvisPhase.THINKING,
                    statusText = "Thinking…",
                    finalTranscription = commandText,
                    liveTranscription = commandText,
                    assistantMessage = "Understanding command…"
                )
            }

            val command = brain.understandCommand(commandText).getOrElse { error ->
                brain.parseHeuristic(commandText)
                    ?: return@launch fail(
                        error.message ?: "Could not understand the command."
                    )
            }.let { parsed ->
                // Prefer structured API result; heuristic already used as fallback above.
                parsed
            }

            // If OpenAI is missing, understandCommand fails fast — heuristic covers WhatsApp phrases.
            runCommand(command)
        }
    }

    private suspend fun runCommand(command: JarvisCommand) {
        when (command) {
            is JarvisCommand.SendWhatsAppMessage -> runWhatsAppPipeline(command)
            is JarvisCommand.YouTubePlay -> runYouTubePlay(command)
            is JarvisCommand.YouTubeSearch -> runYouTubeSearch(command)
            is JarvisCommand.PlaySpotifySong -> runSimpleAutomation(
                speaking = "Opening Spotify.",
                executingMessage = "Opening Spotify…",
                command = command,
                successSpeak = "Playing ${command.song}.",
                successMessage = "Playing ${command.song}."
            )
            is JarvisCommand.SetTimer -> runSimpleAutomation(
                speaking = "Setting a timer.",
                executingMessage = "Setting timer…",
                command = command,
                successSpeak = "Timer set.",
                successMessage = "Timer set."
            )
            is JarvisCommand.SetAlarm -> runSimpleAutomation(
                speaking = "Setting an alarm.",
                executingMessage = "Setting alarm…",
                command = command,
                successSpeak = "Alarm set.",
                successMessage = "Alarm set."
            )
            is JarvisCommand.GoogleSearch -> runSimpleAutomation(
                speaking = "Searching Google.",
                executingMessage = "Searching Google…",
                command = command,
                successSpeak = "Search completed.",
                successMessage = "Search completed."
            )
            is JarvisCommand.OpenApp -> {
                setPhase(JarvisPhase.EXECUTING, "Working…", "Opening ${command.appName}…")
                when (val result = executor.execute(command) { msg ->
                    _uiState.update { it.copy(assistantMessage = msg) }
                }) {
                    CommandResult.Success -> {
                        speak("${command.appName} is open.")
                        setPhase(JarvisPhase.COMPLETED, "Completed", "${command.appName} opened.")
                    }
                    is CommandResult.Failure -> fail(result.message)
                    else -> fail("Unexpected result.")
                }
            }
            is JarvisCommand.Unsupported -> fail(command.reason)
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
                setPhase(JarvisPhase.COMPLETED, "Completed", successMessage)
                speak(successSpeak)
            }
            is CommandResult.Failure -> {
                presentJarvisUiAfterAutomation()
                fail(result.message)
            }
            else -> fail("Unexpected result.")
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
                setPhase(JarvisPhase.COMPLETED, "Completed", "Playing ${command.songName}.")
                speak("Playing ${command.songName}.")
                // Keep COMPLETED visible until the user dismisses the floating panel.
            }
            is CommandResult.Failure -> {
                presentJarvisUiAfterAutomation()
                fail(result.message)
            }
            else -> fail("Unexpected YouTube result.")
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
                setPhase(
                    JarvisPhase.COMPLETED,
                    "Completed",
                    "Showing YouTube results for ${command.query}."
                )
                speak("Here are the results.")
                // Keep COMPLETED visible until the user dismisses the floating panel.
            }
            is CommandResult.Failure -> {
                presentJarvisUiAfterAutomation()
                fail(result.message)
            }
            else -> fail("Unexpected YouTube result.")
        }
    }

    private suspend fun runWhatsAppPipeline(command: JarvisCommand.SendWhatsAppMessage) {
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
            }
            is CommandResult.Failure -> {
                presentJarvisUiAfterAutomation()
                fail(result.message)
            }
            CommandResult.Success -> {
                setPhase(JarvisPhase.COMPLETED, "Completed", "Done.")
                // Keep COMPLETED visible until the user dismisses the floating panel.
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
            // Keep WhatsApp / YouTube in front; overlay shows confirmation / status.
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
                errorMessage = null
            )
        }
    }

    private fun fail(message: String) {
        stopMicForeground()
        _uiState.update {
            it.copy(
                phase = JarvisPhase.ERROR,
                statusText = "Error",
                assistantMessage = message,
                errorMessage = message,
                confirmationTitle = null,
                pendingSend = null
            )
        }
        speak("Something went wrong.")
        // Keep ERROR visible until the user dismisses the floating panel.
    }

    private fun resetToIdle() {
        pendingConfirmationListen = false
        listeningForConfirmation = false
        _uiState.update {
            JarvisUiState(
                phase = JarvisPhase.IDLE,
                statusText = "Ready",
                needsMicrophone = it.needsMicrophone,
                needsAccessibility = it.needsAccessibility,
                needsOverlayPermission = it.needsOverlayPermission,
                setupComplete = it.setupComplete
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

    /**
     * Listen for spoken Send / Cancel without leaving CONFIRMATION or clearing pendingSend.
     */
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
            Log.e("JarvisViewModel", "Confirmation listen failed", t)
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
                Log.i("JarvisViewModel", "Voice confirmation: SEND")
                confirmSend()
            }
            ConfirmationPhraseParser.Decision.CANCEL -> {
                Log.i("JarvisViewModel", "Voice confirmation: CANCEL")
                cancelSend()
            }
            ConfirmationPhraseParser.Decision.UNKNOWN -> {
                Log.i("JarvisViewModel", "Voice confirmation unclear: $commandText")
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
        pendingConfirmationListen = false
        listeningForConfirmation = false
        speech.destroy()
        tts.shutdown()
        stopMicForeground()
        // Do not dismiss the floating panel or stop AccessibilityService here.
        // Activity recreation must not permanently tear down Jarvis overlay state;
        // the next ViewModel rebinds hostCallbacks. User dismisses via X / swipe.
        // Keep hands-free wake running across Activity recreation — only unbind host.
        handsFree.bindHost(null)
        overlay.hostCallbacks = null
        super.onCleared()
    }
}
