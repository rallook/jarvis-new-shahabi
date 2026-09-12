package com.jarvis.assistant.utils

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.jarvis.assistant.accessibility.JarvisAccessibilityService

object AccessibilityUtils {
    fun isJarvisAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, JarvisAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }
}

object ContactMatcher {
    /**
     * Returns matching contact labels. Does not pick randomly when multiple
     * plausible matches exist — callers must ask the user to choose.
     */
    fun findMatches(query: String, candidates: List<String>): List<String> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        val exact = candidates.filter { it.trim().lowercase() == q }
        if (exact.size == 1) return exact
        if (exact.size > 1) return exact.distinct()

        val starts = candidates.filter { it.trim().lowercase().startsWith(q) }.distinct()
        if (starts.size == 1) return starts
        if (starts.size > 1) return starts

        val contains = candidates.filter {
            val name = it.trim().lowercase()
            name.contains(q) || q.contains(name)
        }.distinct()
        return contains
    }
}
