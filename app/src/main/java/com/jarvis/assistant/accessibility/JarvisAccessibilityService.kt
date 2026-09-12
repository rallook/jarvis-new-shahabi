package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Accessibility foundation for WhatsApp workflows.
 * Exposes the live service instance to [WhatsAppExecutor] without hard-coding
 * UI gestures into this class.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val _activePackage = MutableStateFlow<String?>(null)
    val activePackage: StateFlow<String?> = _activePackage.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Log.i(TAG, "Jarvis Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _activePackage.value = pkg
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Jarvis Accessibility Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun isPackageActive(packageName: String): Boolean {
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        return rootPkg == packageName || _activePackage.value == packageName
    }

    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    companion object {
        private const val TAG = "JarvisA11y"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
