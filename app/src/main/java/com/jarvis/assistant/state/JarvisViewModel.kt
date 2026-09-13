package com.jarvis.assistant.state

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ai.JarvisBrain
import com.jarvis.assistant.android.AppLauncher
import com.jarvis.assistant.commands.CommandExecutor
import com.jarvis.assistant.commands.CommandResult
import com.jarvis.assistant.commands.JarvisCommand
import com.jarvis.assistant.overlay.JarvisOverlayController
import com.jarvis.assistant.settings.JarvisSettings
import com.jarvis.assistant.settings.SettingsRepository
import com.jarvis.assistant.tts.JarvisTTS
import com.jarvis.assistant.ui.SettingsScreenState
import com.jarvis.assistant.utils.AccessibilityUtils
import com.jarvis.assistant.voice.AndroidSpeechRecognizerManager
import com.jarvis.assistant.voice.SpeechRecognitionForegroundService
import com.jarvis.assistant.voice.SpeechRecognizerManager
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

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val _settingsState = MutableStateFlow(SettingsScreenState())
    val settingsState: StateFlow<SettingsScreenState> = _settingsState.asStateFlow()

    private var speechCollectJob: Job? = null
    private var pipelineJob: Job? = null
    private var lastHandledTranscription: String? = null

    init {
        overlay.hostCallbacks = object : JarvisOverlayController.HostCallbacks {
            override fun onToggleListening() = toggleListening()
            override fun onConfirmSend() = confirmSend()
            override fun onCancelSend() = cancelSend()
            override fun onSelectContact(choice: String) = selectContact(choice)
            override fun onSubmitTextCommand(text: String) = submitTextCommand(text)
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
            _uiState.collect { overlay.publishState(it) }
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
                        errorMessage = speechState.error ?: current.errorMessage,
                        phase = when {
                            speechState.isListening -> JarvisPhase.LISTENING
                            current.phase == JarvisPhase.LISTENING &&
                                speechState.finalTranscription.isNotBlank() -> JarvisPhase.TRANSCRIBING
                            else -> current.phase
                        },
                        statusText = when {
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
                    finalText != lastHandledTranscription &&
                    _uiState.value.phase == JarvisPhase.TRANSCRIBING
                ) {
                    lastHandledTranscription = finalText
                    handleFinalTranscription(finalText)
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
        // Dismiss UI only — do not stop speech recognition or pipelines.
        overlay.dismissOverlay()
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
        _settingsState.update {
            it.copy(
                settings = settingsRepository.get(),
                accessibilityEnabled = accessibility,
                microphoneGranted = micGranted,
                overlayGranted = overlayGranted
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
        val trimmed = text.trim()
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

        pipelineJob?.cancel()
        lastHandledTranscription = null
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
        speech.stopListening()
        stopMicForeground()
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
                    delay(2200)
                    resetToIdle()
                }
                is CommandResult.Failure -> fail(result.message)
                else -> fail("Unexpected send result.")
            }
        }
    }

    fun cancelSend() {
        pipelineJob?.cancel()
        _uiState.update {
            it.copy(
                phase = JarvisPhase.IDLE,
                statusText = "Ready",
                assistantMessage = "Cancelled.",
                confirmationTitle = null,
                previewMessage = null,
                pendingSend = null
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
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = JarvisPhase.THINKING,
                    statusText = "Thinking…",
                    finalTranscription = text,
                    liveTranscription = text,
                    assistantMessage = "Understanding command…"
                )
            }

            val command = brain.understandCommand(text).getOrElse { error ->
                brain.parseHeuristic(text)
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
            is JarvisCommand.OpenApp -> {
                setPhase(JarvisPhase.EXECUTING, "Working…", "Opening ${command.appName}…")
                when (val result = executor.execute(command) { msg ->
                    _uiState.update { it.copy(assistantMessage = msg) }
                }) {
                    CommandResult.Success -> {
                        speak("${command.appName} is open.")
                        setPhase(JarvisPhase.COMPLETED, "Completed", "${command.appName} opened.")
                        delay(1800)
                        resetToIdle()
                    }
                    is CommandResult.Failure -> fail(result.message)
                    else -> fail("Unexpected result.")
                }
            }
            is JarvisCommand.Unsupported -> fail(command.reason)
        }
    }

    private suspend fun runYouTubePlay(command: JarvisCommand.YouTubePlay) {
        setPhase(JarvisPhase.EXECUTING, "Working…", "Opening YouTube…")
        speak("Opening YouTube.")
        when (val result = executor.execute(command) { progress ->
            _uiState.update {
                it.copy(assistantMessage = progress, statusText = "Working…")
            }
        }) {
            CommandResult.Success -> {
                setPhase(JarvisPhase.COMPLETED, "Completed", "Playing ${command.songName}.")
                speak("Playing ${command.songName}.")
                delay(2000)
                resetToIdle()
            }
            is CommandResult.Failure -> fail(result.message)
            else -> fail("Unexpected YouTube result.")
        }
    }

    private suspend fun runYouTubeSearch(command: JarvisCommand.YouTubeSearch) {
        setPhase(JarvisPhase.EXECUTING, "Working…", "Opening YouTube…")
        speak("Searching YouTube.")
        when (val result = executor.execute(command) { progress ->
            _uiState.update {
                it.copy(assistantMessage = progress, statusText = "Working…")
            }
        }) {
            CommandResult.Success -> {
                setPhase(
                    JarvisPhase.COMPLETED,
                    "Completed",
                    "Showing YouTube results for ${command.query}."
                )
                speak("Here are the results.")
                delay(2000)
                resetToIdle()
            }
            is CommandResult.Failure -> fail(result.message)
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
                        statusText = "Waiting for confirmation",
                        assistantMessage = "Ready to send",
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
                speak("Ready to send. Please confirm.")
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
                delay(1500)
                resetToIdle()
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
        viewModelScope.launch {
            delay(3500)
            if (_uiState.value.phase == JarvisPhase.ERROR) resetToIdle()
        }
    }

    private fun resetToIdle() {
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

    private fun startMicForeground() {
        val app = getApplication<Application>()
        val intent = Intent(app, SpeechRecognitionForegroundService::class.java).apply {
            action = SpeechRecognitionForegroundService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Throwable) {
            // Older devices / denied FGS — recognition can still work in foreground activity.
        }
    }

    private fun stopMicForeground() {
        val app = getApplication<Application>()
        val intent = Intent(app, SpeechRecognitionForegroundService::class.java).apply {
            action = SpeechRecognitionForegroundService.ACTION_STOP
        }
        try {
            app.startService(intent)
        } catch (_: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.stopService(intent)
            }
        }
    }

    override fun onCleared() {
        speechCollectJob?.cancel()
        pipelineJob?.cancel()
        speech.destroy()
        tts.shutdown()
        stopMicForeground()
        // Remove floating panel to avoid duplicates; leave Accessibility / FG service alone.
        overlay.dismissOverlay()
        overlay.hostCallbacks = null
        super.onCleared()
    }
}
