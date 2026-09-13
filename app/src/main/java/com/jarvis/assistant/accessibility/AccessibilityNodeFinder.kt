package com.jarvis.assistant.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reusable accessibility node helpers. Prefer text, contentDescription, view IDs,
 * and clickable ancestors over screen coordinates.
 */
object AccessibilityNodeFinder {

    data class NodeMatch(
        val textContains: List<String> = emptyList(),
        val descContains: List<String> = emptyList(),
        val viewIdContains: List<String> = emptyList(),
        val classNameContains: List<String> = emptyList(),
        val clickable: Boolean? = null,
        val editable: Boolean? = null,
        val focusable: Boolean? = null,
        val enabled: Boolean? = null
    )

    fun flatten(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            out += node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return out
    }

    fun findByViewId(root: AccessibilityNodeInfo?, viewId: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return root.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
    }

    fun findByText(root: AccessibilityNodeInfo?, text: String, exact: Boolean = false): List<AccessibilityNodeInfo> {
        if (root == null || text.isBlank()) return emptyList()
        val direct = root.findAccessibilityNodeInfosByText(text).orEmpty()
        if (exact) {
            return direct.filter {
                it.text?.toString().equals(text, ignoreCase = true) ||
                    it.contentDescription?.toString().equals(text, ignoreCase = true)
            }
        }
        return direct
    }

    fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return flatten(root).firstOrNull { it.isEditable || it.className?.contains("EditText") == true }
    }

    fun findClickableWithDesc(root: AccessibilityNodeInfo?, vararg descriptions: String): AccessibilityNodeInfo? {
        val nodes = flatten(root)
        for (desc in descriptions) {
            val match = nodes.firstOrNull { node ->
                val cd = node.contentDescription?.toString().orEmpty()
                val tx = node.text?.toString().orEmpty()
                (cd.contains(desc, ignoreCase = true) || tx.contains(desc, ignoreCase = true)) &&
                    (node.isClickable || node.isEnabled)
            }
            if (match != null) return clickableAncestor(match) ?: match
        }
        return null
    }

    /**
     * Multi-strategy finder: match any combination of text, contentDescription,
     * viewIdResourceName, className, and boolean flags.
     */
    fun findMatching(root: AccessibilityNodeInfo?, match: NodeMatch): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return flatten(root).filter { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val clazz = node.className?.toString().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString().orEmpty()
            } else {
                ""
            }

            if (match.textContains.isNotEmpty()) {
                val hay = "$text $hint"
                if (match.textContains.none { hay.contains(it, ignoreCase = true) }) return@filter false
            }
            if (match.descContains.isNotEmpty()) {
                if (match.descContains.none { desc.contains(it, ignoreCase = true) }) return@filter false
            }
            if (match.viewIdContains.isNotEmpty()) {
                if (match.viewIdContains.none { viewId.contains(it, ignoreCase = true) }) return@filter false
            }
            if (match.classNameContains.isNotEmpty()) {
                if (match.classNameContains.none { clazz.contains(it, ignoreCase = true) }) return@filter false
            }
            if (match.clickable != null && node.isClickable != match.clickable) return@filter false
            if (match.editable != null) {
                val editable = node.isEditable || clazz.contains("EditText", ignoreCase = true)
                if (editable != match.editable) return@filter false
            }
            if (match.focusable != null && node.isFocusable != match.focusable) return@filter false
            if (match.enabled != null && node.isEnabled != match.enabled) return@filter false
            true
        }
    }

    fun findFirstMatching(root: AccessibilityNodeInfo?, match: NodeMatch): AccessibilityNodeInfo? {
        return findMatching(root, match).firstOrNull()
    }

    fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    fun collectVisibleTexts(root: AccessibilityNodeInfo?): List<String> {
        return flatten(root).mapNotNull { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                else -> null
            }
        }.distinct()
    }

    fun performClick(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val target = clickableAncestor(node)
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // Some YouTube controls report clickable=false but still accept ACTION_CLICK.
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Best-effort text entry: ACTION_SET_TEXT first, then clipboard paste.
     * Does not log [text] contents (callers decide what to log).
     */
    fun enterText(node: AccessibilityNodeInfo?, text: String, context: Context?): Boolean {
        if (node == null) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            return true
        }

        // Some fields need a click before accepting text.
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            return true
        }

        // Retry after clear.
        val clearArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            return true
        }

        if (context != null) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("jarvis_query", text))
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "clipboard paste failed", t)
            }
        }
        return false
    }

    fun nodeContainsText(node: AccessibilityNodeInfo?, expected: String): Boolean {
        if (node == null || expected.isBlank()) return false
        val text = node.text?.toString().orEmpty()
        return text.contains(expected, ignoreCase = true)
    }

    fun performImeEnter(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (node.actionList?.any { it.id == action.id } == true) {
                if (node.performAction(action.id)) return true
            }
            // Some nodes accept the action even when not advertised.
            if (node.performAction(action.id)) return true
        }
        // Pre-R constant used by some IMEs / WebViews.
        return node.performAction(0x00000101)
    }

    fun logHierarchy(tag: String, root: AccessibilityNodeInfo?, limit: Int = 80) {
        if (root == null) {
            Log.d(tag, "hierarchy: root=null")
            return
        }
        val nodes = flatten(root).take(limit)
        Log.d(tag, "hierarchy: nodes=${nodes.size} (showing up to $limit)")
        nodes.forEachIndexed { index, node ->
            val text = node.text?.toString()?.take(80).orEmpty()
            val desc = node.contentDescription?.toString()?.take(80).orEmpty()
            Log.d(
                tag,
                "n[$index] class=${node.className} id=${node.viewIdResourceName} " +
                    "text=\"$text\" desc=\"$desc\" " +
                    "click=${node.isClickable} edit=${node.isEditable} " +
                    "focus=${node.isFocusable} focused=${node.isFocused} enabled=${node.isEnabled}"
            )
        }
    }

    private const val TAG = "A11yNodeFinder"
}
