package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Accessibility foundation for WhatsApp and YouTube workflows.
 * Exposes the live service instance to executors without hard-coding
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
        if (rootForPackage(packageName) != null) return true
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        return rootPkg == packageName || _activePackage.value == packageName
    }

    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Tap the center of [node]'s on-screen bounds via [dispatchGesture].
     * Used only as a fallback when ACTION_CLICK fails (e.g. YouTube Search).
     * Coordinates come from [AccessibilityNodeInfo.getBoundsInScreen] — never hard-coded.
     */
    suspend fun dispatchTapOnNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            Log.w(TAG, "dispatchTapOnNode: empty bounds")
            return false
        }
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        return dispatchTap(x, y)
    }

    suspend fun dispatchTap(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture returned false for tap at ($x,$y)")
                if (cont.isActive) cont.resume(false)
            }
        }

    /**
     * Prefer the interactive window whose package matches [packageName].
     * Critical when a TYPE_APPLICATION_OVERLAY (Jarvis panel) is also present —
     * [rootInActiveWindow] alone can miss the target app.
     */
    fun rootForPackage(packageName: String): AccessibilityNodeInfo? {
        val windowRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val windowList: List<AccessibilityWindowInfo>? = windows
            if (!windowList.isNullOrEmpty()) {
                for (window in windowList) {
                    val root = try {
                        window.root
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    val pkg = root.packageName?.toString()
                    if (pkg == packageName) {
                        windowRoots += root
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "windows enumeration failed", t)
        }

        // Prefer the largest application window (skip tiny overlay chrome).
        windowRoots.maxByOrNull { root ->
            val bounds = android.graphics.Rect()
            root.getBoundsInScreen(bounds)
            bounds.width() * bounds.height()
        }?.let { return it }

        val active = rootInActiveWindow
        if (active?.packageName?.toString() == packageName) return active
        return null
    }

    fun logWindowDiagnostics(tag: String, packageName: String? = null) {
        val active = rootInActiveWindow
        Log.d(
            tag,
            "diag activePackage=${_activePackage.value} " +
                "activeRootPkg=${active?.packageName} activeRoot=${active != null}"
        )
        try {
            windows?.forEachIndexed { index, window ->
                val root = try {
                    window.root
                } catch (_: Throwable) {
                    null
                }
                Log.d(
                    tag,
                    "diag window[$index] type=${window.type} title=${window.title} " +
                        "pkg=${root?.packageName} root=${root != null} " +
                        "active=${window.isActive} focused=${window.isFocused}"
                )
            }
        } catch (t: Throwable) {
            Log.w(tag, "diag windows failed", t)
        }
        if (packageName != null) {
            val targeted = rootForPackage(packageName)
            Log.d(tag, "diag rootForPackage($packageName)=${targeted != null}")
            if (targeted != null) {
                AccessibilityNodeFinder.logHierarchy(tag, targeted, limit = 60)
            }
        }
    }

    companion object {
        private const val TAG = "JarvisA11y"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
