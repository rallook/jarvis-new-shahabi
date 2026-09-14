package com.jarvis.assistant.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jarvis.assistant.state.JarvisUiState
import com.jarvis.assistant.ui.components.VoiceCommandPanel
import com.jarvis.assistant.ui.theme.JarvisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Floating Jarvis voice panel over other apps.
 *
 * Visibility is intentionally SEPARATE from mic / TTS / task / conversation state.
 * Once shown ([ensureFloatingUiVisible]), the panel stays until an explicit dismissal:
 * X button, swipe-down, or CLOSE_JARVIS — never because silence, TTS, or tasks end.
 *
 * While the host Activity is in the foreground the window is temporarily hidden so
 * the in-app panel is not duplicated; [jarvisFloatingUiVisible] remains true and the
 * overlay returns as soon as the host leaves the foreground.
 */
class JarvisOverlayController private constructor(
    private val appContext: Context
) {
    interface HostCallbacks {
        fun onToggleListening()
        fun onConfirmSend()
        fun onCancelSend()
        fun onSelectContact(choice: String)
        fun onSubmitTextCommand(text: String)
        fun onDismissPanel()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val _hostInForeground = MutableStateFlow(true)
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    /**
     * Persistent user-facing visibility intent.
     * True after Jarvis is intentionally shown; false only after explicit dismiss.
     */
    private val _jarvisFloatingUiVisible = MutableStateFlow(false)
    val jarvisFloatingUiVisible: StateFlow<Boolean> = _jarvisFloatingUiVisible.asStateFlow()

    var hostCallbacks: HostCallbacks? = null

    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var collectJob: Job? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var draggingPanel = false

    fun publishState(state: JarvisUiState) {
        _uiState.value = state
        // Never toggle visibility from phase / listening / conversation flags.
        reconcile()
    }

    /** Mark Jarvis as shown and keep it until explicit dismiss. */
    fun ensureFloatingUiVisible() {
        if (!_jarvisFloatingUiVisible.value) {
            Log.i(TAG, "jarvisFloatingUiVisible=true")
        }
        _jarvisFloatingUiVisible.value = true
        reconcile()
    }

    fun setHostInForeground(inForeground: Boolean) {
        _hostInForeground.value = inForeground
        reconcile()
    }

    /**
     * Explicit dismissal only (X / swipe / CLOSE_JARVIS).
     */
    fun dismissOverlay() {
        Log.i(TAG, "jarvisFloatingUiVisible=false (explicit dismiss)")
        _jarvisFloatingUiVisible.value = false
        draggingPanel = false
        hideOverlayWindow()
        reconcile()
    }

    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    fun overlayPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            combine(_hostInForeground, _jarvisFloatingUiVisible) { hostFg, floatingDesired ->
                hostFg to floatingDesired
            }.collect { (hostFg, floatingDesired) ->
                val shouldShow = !hostFg &&
                    floatingDesired &&
                    canDrawOverlays()
                if (shouldShow) {
                    showOverlayWindow()
                } else {
                    // Hide window while host is foreground or user dismissed —
                    // never because of task/mic/silence state.
                    hideOverlayWindow()
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        hideOverlayWindow()
        hostCallbacks = null
    }

    private fun reconcile() {
        _hostInForeground.value = _hostInForeground.value
        _jarvisFloatingUiVisible.value = _jarvisFloatingUiVisible.value
    }

    private fun showOverlayWindow() {
        if (!canDrawOverlays()) return
        if (composeView != null) {
            _overlayVisible.value = true
            return
        }
        try {
            val owner = OverlayLifecycleOwner().also { it.onCreate() }
            lifecycleOwner = owner
            val view = ComposeView(appContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    val state by uiState.collectAsState()
                    JarvisTheme {
                        VoiceCommandPanel(
                            state = state,
                            onToggleListening = { hostCallbacks?.onToggleListening() },
                            onConfirmSend = { hostCallbacks?.onConfirmSend() },
                            onCancelSend = { hostCallbacks?.onCancelSend() },
                            onSelectContact = { hostCallbacks?.onSelectContact(it) },
                            onSubmitTextCommand = { hostCallbacks?.onSubmitTextCommand(it) },
                            micEnabled = !state.needsMicrophone &&
                                state.phase !in setOf(
                                    com.jarvis.assistant.state.JarvisPhase.THINKING,
                                    com.jarvis.assistant.state.JarvisPhase.SENDING,
                                    com.jarvis.assistant.state.JarvisPhase.VERIFYING,
                                    com.jarvis.assistant.state.JarvisPhase.ENDING
                                ),
                            showDismissControls = true,
                            onDismiss = {
                                // User X / swipe only.
                                hostCallbacks?.onDismissPanel() ?: dismissOverlay()
                            },
                            onDismissDragActive = { active -> setPanelDragging(active) },
                            compact = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                baseOverlayFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            layoutParams = params
            windowManager.addView(view, params)
            owner.onStart()
            owner.onResume()
            composeView = view
            _overlayVisible.value = true
            Log.i(TAG, "Floating Jarvis panel shown")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to show overlay", t)
            composeView = null
            lifecycleOwner = null
            layoutParams = null
            _overlayVisible.value = false
        }
    }

    private fun setPanelDragging(active: Boolean) {
        if (draggingPanel == active) return
        draggingPanel = active
        val view = composeView ?: return
        val params = layoutParams ?: return
        params.flags = if (active) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        } else {
            baseOverlayFlags()
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update overlay drag flags", t)
        }
    }

    private fun baseOverlayFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    private fun hideOverlayWindow() {
        val view = composeView ?: run {
            _overlayVisible.value = false
            return
        }
        try {
            lifecycleOwner?.onPause()
            lifecycleOwner?.onStop()
            lifecycleOwner?.onDestroy()
            windowManager.removeView(view)
        } catch (t: Throwable) {
            Log.w(TAG, "Overlay remove failed", t)
        } finally {
            composeView = null
            lifecycleOwner = null
            layoutParams = null
            draggingPanel = false
            _overlayVisible.value = false
            Log.i(TAG, "Floating Jarvis panel hidden")
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun onStart() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        fun onResume() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        fun onPause() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        fun onStop() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        fun onDestroy() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    companion object {
        private const val TAG = "JarvisOverlay"

        @Volatile
        private var instance: JarvisOverlayController? = null

        fun getInstance(context: Context): JarvisOverlayController {
            return instance ?: synchronized(this) {
                instance ?: JarvisOverlayController(context.applicationContext).also {
                    instance = it
                    it.start()
                }
            }
        }
    }
}
