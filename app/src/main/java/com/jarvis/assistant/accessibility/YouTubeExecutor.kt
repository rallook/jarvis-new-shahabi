package com.jarvis.assistant.accessibility

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

sealed class YouTubeStepResult {
    data object Success : YouTubeStepResult()
    data class Failure(val message: String) : YouTubeStepResult()
}

/**
 * YouTube accessibility workflows. Prefers view IDs, text, and content
 * descriptions — never fixed screen coordinates as the primary method.
 */
class YouTubeExecutor(
    private val serviceProvider: () -> JarvisAccessibilityService?
) {
    suspend fun waitUntilYouTubeVisible(packageName: String, timeoutMs: Long = 10_000): YouTubeStepResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val service = serviceProvider()
            if (service?.isPackageActive(packageName) == true) {
                return YouTubeStepResult.Success
            }
            delay(250)
        }
        return YouTubeStepResult.Failure("YouTube did not become visible.")
    }

    suspend fun openSearchField(): YouTubeStepResult {
        val service = serviceProvider()
            ?: return YouTubeStepResult.Failure("Jarvis Accessibility Service is not connected.")

        // Already on search UI?
        if (findSearchInput(service.rootInActiveWindow) != null) {
            return YouTubeStepResult.Success
        }

        val root = service.rootInActiveWindow
            ?: return YouTubeStepResult.Failure("No accessibility window content for YouTube.")

        val searchEntry = findSearchEntry(root)
            ?: return YouTubeStepResult.Failure("Could not find YouTube search field or search button.")

        if (!AccessibilityNodeFinder.performClick(searchEntry)) {
            return YouTubeStepResult.Failure("Failed to open YouTube search.")
        }
        delay(700)

        val input = waitForSearchInput(service, timeoutMs = 5_000)
            ?: return YouTubeStepResult.Failure("YouTube search field did not appear after opening search.")
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return YouTubeStepResult.Success
    }

    suspend fun enterSearchQuery(query: String): YouTubeStepResult {
        val service = serviceProvider()
            ?: return YouTubeStepResult.Failure("Jarvis Accessibility Service is not connected.")
        val input = waitForSearchInput(service, timeoutMs = 4_000)
            ?: return YouTubeStepResult.Failure("Could not find YouTube search field.")

        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        val set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(400)
        val verified = verifySearchInputContains(service, query)
        return if (set || verified) {
            if (verified) YouTubeStepResult.Success
            else YouTubeStepResult.Failure("Failed to enter YouTube search text.")
        } else {
            YouTubeStepResult.Failure("Failed to enter YouTube search text.")
        }
    }

    suspend fun submitSearch(): YouTubeStepResult {
        val service = serviceProvider()
            ?: return YouTubeStepResult.Failure("Jarvis Accessibility Service is not connected.")
        val root = service.rootInActiveWindow
            ?: return YouTubeStepResult.Failure("No accessibility window content for YouTube.")

        val input = findSearchInput(root)
        if (input != null) {
            // ACTION_IME_ENTER (API 30+) = 0x00000101 — submit search when available.
            val imeEnterId = 0x00000101
            if (input.performAction(imeEnterId)) {
                delay(900)
                return YouTubeStepResult.Success
            }
            // Fallback: click a Search / suggestion submit control.
            val submit = AccessibilityNodeFinder.findClickableWithDesc(
                root,
                "Search",
                "Search YouTube",
                "Go"
            )
            if (submit != null && submit != input && AccessibilityNodeFinder.performClick(submit)) {
                delay(900)
                return YouTubeStepResult.Success
            }
            delay(500)
            val suggestion = findFirstSuggestion(service.rootInActiveWindow)
            if (suggestion != null && AccessibilityNodeFinder.performClick(suggestion)) {
                delay(900)
                return YouTubeStepResult.Success
            }
        }

        // Tap first search suggestion matching typed query if search submit is unavailable.
        val suggestion = findFirstSuggestion(service.rootInActiveWindow)
        if (suggestion != null && AccessibilityNodeFinder.performClick(suggestion)) {
            delay(900)
            return YouTubeStepResult.Success
        }

        return YouTubeStepResult.Failure("Could not execute YouTube search.")
    }

    suspend fun verifyResultsPage(query: String, timeoutMs: Long = 8_000): YouTubeStepResult {
        val service = serviceProvider()
            ?: return YouTubeStepResult.Failure("Jarvis Accessibility Service is not connected.")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow
            if (looksLikeResultsPage(root, query)) {
                return YouTubeStepResult.Success
            }
            delay(350)
        }
        return YouTubeStepResult.Failure("YouTube results page was not displayed.")
    }

    suspend fun selectAppropriateResult(query: String): YouTubeStepResult {
        val service = serviceProvider()
            ?: return YouTubeStepResult.Failure("Jarvis Accessibility Service is not connected.")
        val root = service.rootInActiveWindow
            ?: return YouTubeStepResult.Failure("No accessibility window content for YouTube.")

        val result = findBestVideoResult(root, query)
            ?: return YouTubeStepResult.Failure("Could not find a suitable YouTube result for \"$query\".")

        if (!AccessibilityNodeFinder.performClick(result)) {
            return YouTubeStepResult.Failure("Failed to select YouTube search result.")
        }
        delay(1200)
        return YouTubeStepResult.Success
    }

    suspend fun verifyPlayback(query: String, timeoutMs: Long = 12_000): YouTubeStepResult {
        val service = serviceProvider()
            ?: return YouTubeStepResult.Failure("Jarvis Accessibility Service is not connected.")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow
            if (looksLikePlayer(root, query)) {
                return YouTubeStepResult.Success
            }
            delay(400)
        }
        return YouTubeStepResult.Failure("Could not verify that YouTube is playing the selected result.")
    }

    private suspend fun waitForSearchInput(
        service: JarvisAccessibilityService,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findSearchInput(service.rootInActiveWindow)?.let { return it }
            delay(250)
        }
        return null
    }

    private fun findSearchEntry(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val byId = SEARCH_BUTTON_IDS.flatMap { AccessibilityNodeFinder.findByViewId(root, it) }
            .firstOrNull()
        if (byId != null) return AccessibilityNodeFinder.clickableAncestor(byId) ?: byId

        return AccessibilityNodeFinder.findClickableWithDesc(
            root,
            "Search",
            "Search YouTube",
            "Search…"
        )
    }

    private fun findSearchInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        for (id in SEARCH_INPUT_IDS) {
            AccessibilityNodeFinder.findByViewId(root, id).firstOrNull()?.let { return it }
        }
        val editable = AccessibilityNodeFinder.findEditable(root) ?: return null
        val hint = editable.text?.toString().orEmpty() +
            editable.contentDescription?.toString().orEmpty() +
            (editable.hintText?.toString().orEmpty())
        return if (
            hint.contains("search", ignoreCase = true) ||
            editable.isFocused ||
            AccessibilityNodeFinder.flatten(root).any {
                it.isEditable && (it.isFocused || it.className?.contains("EditText") == true)
            }
        ) {
            editable
        } else {
            AccessibilityNodeFinder.flatten(root)
                .firstOrNull { it.isEditable || it.className?.contains("EditText") == true }
        }
    }

    private fun verifySearchInputContains(service: JarvisAccessibilityService, query: String): Boolean {
        val input = findSearchInput(service.rootInActiveWindow) ?: return false
        val text = input.text?.toString().orEmpty()
        return text.contains(query, ignoreCase = true)
    }

    private fun findFirstSuggestion(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        // Prefer recycler / suggestion rows with query-like text.
        val nodes = AccessibilityNodeFinder.flatten(root)
        val suggestion = nodes.firstOrNull { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val label = text.ifBlank { desc }
            label.length in 2..80 &&
                !label.equals("Search", ignoreCase = true) &&
                !label.equals("Clear", ignoreCase = true) &&
                (node.isClickable || node.parent?.isClickable == true) &&
                node.className?.contains("EditText") != true
        }
        return suggestion?.let { AccessibilityNodeFinder.clickableAncestor(it) ?: it }
    }

    private fun looksLikeResultsPage(root: AccessibilityNodeInfo?, query: String): Boolean {
        if (root == null) return false
        val texts = AccessibilityNodeFinder.collectVisibleTexts(root)
        val hasQueryEcho = texts.any { it.contains(query, ignoreCase = true) }
        val hasResultSignals = texts.any {
            it.contains("views", ignoreCase = true) ||
                it.contains("ago", ignoreCase = true) ||
                it.contains("Subscribe", ignoreCase = true) ||
                it.contains("Shorts", ignoreCase = true)
        }
        val hasVideoRows = RESULT_CONTAINER_IDS.any {
            AccessibilityNodeFinder.findByViewId(root, it).isNotEmpty()
        } || AccessibilityNodeFinder.findClickableWithDesc(root, "Video", "Play") != null ||
            texts.any { VIDEO_DURATION.matches(it.trim()) }

        return (hasQueryEcho && (hasResultSignals || hasVideoRows)) || hasVideoRows
    }

    private fun findBestVideoResult(root: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = AccessibilityNodeFinder.flatten(root)
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }

        // Prefer nodes whose text/desc overlaps the query and look like video titles.
        val scored = nodes.mapNotNull { node ->
            val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim()
            if (label.length < 4 || label.length > 120) return@mapNotNull null
            if (label.equals("Search", ignoreCase = true)) return@mapNotNull null
            if (label.contains("Shorts", ignoreCase = true) && label.length < 16) return@mapNotNull null
            if (label.contains("Ad", ignoreCase = true) && label.length <= 4) return@mapNotNull null
            val clickable = AccessibilityNodeFinder.clickableAncestor(node) ?: return@mapNotNull null
            val overlap = queryWords.count { label.lowercase().contains(it) }
            val bonus = when {
                label.contains(query, ignoreCase = true) -> 5
                overlap > 0 -> overlap
                else -> 0
            }
            if (bonus == 0 && !node.isClickable) return@mapNotNull null
            Triple(clickable, label, bonus + if (node.isClickable) 1 else 0)
        }.sortedByDescending { it.third }

        scored.firstOrNull { it.third >= 1 }?.let { return it.first }

        // Fallback: first clickable video-ish row in results.
        for (id in RESULT_TITLE_IDS) {
            AccessibilityNodeFinder.findByViewId(root, id).firstOrNull()?.let { title ->
                return AccessibilityNodeFinder.clickableAncestor(title) ?: title
            }
        }

        return AccessibilityNodeFinder.findClickableWithDesc(root, "play video", "Video")
            ?: scored.firstOrNull()?.first
    }

    private fun looksLikePlayer(root: AccessibilityNodeInfo?, query: String): Boolean {
        if (root == null) return false
        val texts = AccessibilityNodeFinder.collectVisibleTexts(root)
        val playerControls = AccessibilityNodeFinder.findClickableWithDesc(
            root,
            "Pause video",
            "Pause",
            "Play video",
            "Play",
            "Enter fullscreen",
            "More",
            "Seek"
        ) != null
        val playerIds = PLAYER_IDS.any { AccessibilityNodeFinder.findByViewId(root, it).isNotEmpty() }
        val titleNearby = texts.any { text ->
            query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
                .count { word -> text.lowercase().contains(word) } >= 1
        }
        val watchSignals = texts.any {
            it.contains("Subscribe", ignoreCase = true) ||
                it.contains("likes", ignoreCase = true) ||
                it.contains("Dislike", ignoreCase = true) ||
                it.contains("Share", ignoreCase = true)
        }
        return (playerControls || playerIds) && (titleNearby || watchSignals)
    }

    companion object {
        private const val TAG = "YouTubeExecutor"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        private val SEARCH_BUTTON_IDS = listOf(
            "$PACKAGE_YOUTUBE:id/menu_item_1",
            "$PACKAGE_YOUTUBE:id/menu_search",
            "$PACKAGE_YOUTUBE:id/search_button",
            "$PACKAGE_YOUTUBE:id/ibb"
        )
        private val SEARCH_INPUT_IDS = listOf(
            "$PACKAGE_YOUTUBE:id/search_edit_text",
            "$PACKAGE_YOUTUBE:id/search_box",
            "$PACKAGE_YOUTUBE:id/search_src_text"
        )
        private val RESULT_CONTAINER_IDS = listOf(
            "$PACKAGE_YOUTUBE:id/results",
            "$PACKAGE_YOUTUBE:id/results_container"
        )
        private val RESULT_TITLE_IDS = listOf(
            "$PACKAGE_YOUTUBE:id/title",
            "$PACKAGE_YOUTUBE:id/video_title"
        )
        private val PLAYER_IDS = listOf(
            "$PACKAGE_YOUTUBE:id/player_view",
            "$PACKAGE_YOUTUBE:id/watch_player",
            "$PACKAGE_YOUTUBE:id/player_fragment_container"
        )
        private val VIDEO_DURATION = Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""")

        init {
            Log.d(TAG, "YouTubeExecutor ready")
        }
    }
}
