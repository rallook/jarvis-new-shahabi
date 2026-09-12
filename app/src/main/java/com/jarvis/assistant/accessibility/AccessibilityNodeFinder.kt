package com.jarvis.assistant.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reusable accessibility node helpers. Prefer text, contentDescription, view IDs,
 * and clickable ancestors over screen coordinates.
 */
object AccessibilityNodeFinder {

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
        val target = if (node.isClickable) node else clickableAncestor(node)
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
}
