package com.jarvis.assistant.accessibility

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

sealed class YouTubeStepResult {
    data object Success : YouTubeStepResult()
    data class Failure(val step: String, val message: String) : YouTubeStepResult() {
        constructor(message: String) : this(step = "YOUTUBE_SEARCH_FAILED", message = message)
    }
}

/**
 * YouTube accessibility workflows (search + play). Uses the shared
 * [JarvisAccessibilityService]. Never uses fixed screen coordinates.
 *
 * Critical: after clicking Search, wait for the UI transition, then call
 * [JarvisAccessibilityService.rootInActiveWindow] / [freshYouTubeRoot] again —
 * never reuse AccessibilityNodeInfo instances from before the transition.
 */
class YouTubeAccessibilityExecutor(
    private val serviceProvider: () -> JarvisAccessibilityService?
) {
    suspend fun openYouTube(packageName: String = PACKAGE_YOUTUBE): YouTubeStepResult {
        logStep("YOUTUBE_OPEN_STARTED", "package=$packageName")
        return waitForYouTube(packageName)
    }

    suspend fun waitForYouTube(
        packageName: String = PACKAGE_YOUTUBE,
        timeoutMs: Long = 12_000
    ): YouTubeStepResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val service = serviceProvider() ?: break
            if (service.isPackageActive(packageName) && freshYouTubeRoot(service) != null) {
                logStep("YOUTUBE_WINDOW_DETECTED", "package=$packageName")
                return YouTubeStepResult.Success
            }
            delay(250)
        }
        serviceProvider()?.logWindowDiagnostics(TAG, packageName)
        return fail("YOUTUBE_WINDOW_DETECTED", "YouTube did not become the active accessibility window.")
    }

    suspend fun waitUntilYouTubeVisible(packageName: String, timeoutMs: Long = 12_000): YouTubeStepResult {
        return waitForYouTube(packageName, timeoutMs)
    }

    suspend fun openSearch(): YouTubeStepResult = openSearchField()

    /**
     * Stages: FIND_SEARCH_BUTTON → CLICK → WAIT_FOR_SEARCH_UI →
     * REFRESH tree → FIND_SEARCH_INPUT.
     */
    suspend fun openSearchField(): YouTubeStepResult {
        val service = serviceProvider()
            ?: return fail("YOUTUBE_SEARCH_FAILED", "Jarvis Accessibility Service is not connected.")

        // Already on search UI?
        freshYouTubeRoot(service)?.let { root ->
            if (findSearchInput(root) != null) {
                logStep("YOUTUBE_SEARCH_INPUT_FOUND", "already on search UI")
                return YouTubeStepResult.Success
            }
        }

        logStep("YOUTUBE_SEARCH_BUTTON_SEARCHING", "scanning main YouTube UI")
        val searchButton = waitForSearchButton(service, timeoutMs = 8_000)
            ?: run {
                service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
                return fail(
                    "YOUTUBE_SEARCH_BUTTON_SEARCHING",
                    "Search button not found on YouTube main screen."
                )
            }

        logStep(
            "YOUTUBE_SEARCH_BUTTON_FOUND",
            summarizeNode(searchButton)
        )

        val clicked = AccessibilityNodeFinder.performClick(searchButton)
        if (clicked) {
            logStep("YOUTUBE_SEARCH_BUTTON_CLICKED", "ACTION_CLICK accepted")
        } else {
            logStep("YOUTUBE_ACTION_CLICK_FAILED", "ACTION_CLICK returned false; trying gesture fallback")
            val bounds = android.graphics.Rect()
            searchButton.getBoundsInScreen(bounds)
            if (bounds.isEmpty) {
                service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
                return fail(
                    "YOUTUBE_GESTURE_FALLBACK_FAILED",
                    "ACTION_CLICK failed and Search button bounds were empty."
                )
            }
            logStep(
                "YOUTUBE_GESTURE_FALLBACK_STARTED",
                "tap center=(${bounds.exactCenterX()},${bounds.exactCenterY()}) bounds=$bounds"
            )
            val gestured = service.dispatchTapOnNode(searchButton)
            if (!gestured) {
                service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
                return fail(
                    "YOUTUBE_GESTURE_FALLBACK_FAILED",
                    "dispatchGesture tap at Search button center failed."
                )
            }
            logStep("YOUTUBE_GESTURE_FALLBACK_SUCCEEDED", "gesture completed")
        }

        // Critical transition: do NOT reuse the pre-click tree / nodes.
        logStep("YOUTUBE_SEARCH_UI_WAITING", "waiting for search UI transition")
        delay(600)

        if (!ensureYouTubeStillActive(service)) {
            return fail(
                "YOUTUBE_SEARCH_UI_WAITING",
                "YouTube is no longer the active package after opening Search."
            )
        }

        // Fresh tree after click / gesture.
        service.rootInActiveWindow
        logStep("YOUTUBE_SEARCH_INPUT_SEARCHING", "refreshing accessibility tree")
        val input = waitForSearchInputAfterTransition(service, timeoutMs = 8_000)
        if (input == null) {
            service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
            freshYouTubeRoot(service)?.let {
                AccessibilityNodeFinder.logHierarchy(TAG, it, limit = 80)
            }
            return fail(
                "YOUTUBE_SEARCH_INPUT_SEARCHING",
                "Search control activated but no editable search input is exposed in the accessibility tree."
            )
        }

        logStep("YOUTUBE_SEARCH_INPUT_FOUND", summarizeNode(input))
        logStep("YOUTUBE_SEARCH_UI_VERIFIED", "editable search field present after fresh tree read")
        return YouTubeStepResult.Success
    }

    suspend fun enterSearchQuery(query: String): YouTubeStepResult {
        val service = serviceProvider()
            ?: return fail("YOUTUBE_SEARCH_FAILED", "Jarvis Accessibility Service is not connected.")

        val input = waitForSearchInputAfterTransition(service, timeoutMs = 5_000)
            ?: run {
                service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
                return fail(
                    "YOUTUBE_SEARCH_INPUT_SEARCHING",
                    "Could not find editable YouTube search input before typing."
                )
            }

        val focused = input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        logStep(
            "YOUTUBE_SEARCH_INPUT_FOCUSED",
            "focusAction=$focused editable=${input.isEditable} focusable=${input.isFocusable}"
        )
        if (!focused && !input.isFocused) {
            // Still attempt set-text; some fields accept text without reporting focus.
            Log.w(TAG, "YOUTUBE_SEARCH_INPUT_FOCUSED — ACTION_FOCUS returned false; continuing")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        val setOk = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(350)

        // Re-read tree — never trust the pre-set-text node for verification.
        val verified = verifySearchInputContainsFresh(service, query)
        if (verified) {
            logStep("YOUTUBE_QUERY_ENTERED", "verified length=${query.length} setText=$setOk")
            return YouTubeStepResult.Success
        }

        // One more best-effort via helper (paste fallback) on a FRESH node.
        val freshInput = findSearchInput(freshYouTubeRoot(service))
        val entered = AccessibilityNodeFinder.enterText(freshInput, query, service.applicationContext)
        delay(350)
        if (verifySearchInputContainsFresh(service, query)) {
            logStep("YOUTUBE_QUERY_ENTERED", "verified via fallback entered=$entered")
            return YouTubeStepResult.Success
        }

        service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
        return fail(
            "YOUTUBE_QUERY_ENTERED",
            "Search input found/focused but query could not be entered (ACTION_SET_TEXT failed verification)."
        )
    }

    suspend fun submitSearch(): YouTubeStepResult {
        val service = serviceProvider()
            ?: return fail("YOUTUBE_SEARCH_FAILED", "Jarvis Accessibility Service is not connected.")

        val root = freshYouTubeRoot(service)
            ?: return fail("YOUTUBE_SEARCH_SUBMITTED", "No fresh YouTube accessibility root for submit.")

        val input = findSearchInput(root)
        if (input != null) {
            if (AccessibilityNodeFinder.performImeEnter(input)) {
                delay(900)
                logStep("YOUTUBE_SEARCH_SUBMITTED", "via IME enter / keyboard action")
                return YouTubeStepResult.Success
            }

            val submit = findSearchSubmitControl(freshYouTubeRoot(service), input)
            if (submit != null && AccessibilityNodeFinder.performClick(submit)) {
                delay(900)
                logStep("YOUTUBE_SEARCH_SUBMITTED", "via search/submit control")
                return YouTubeStepResult.Success
            }
            delay(400)
        }

        val suggestion = findFirstSuggestion(freshYouTubeRoot(service))
        if (suggestion != null && AccessibilityNodeFinder.performClick(suggestion)) {
            delay(900)
            logStep("YOUTUBE_SEARCH_SUBMITTED", "via suggestion click")
            return YouTubeStepResult.Success
        }

        service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
        return fail(
            "YOUTUBE_SEARCH_SUBMITTED",
            "Could not submit search (IME enter, submit control, and suggestions all failed)."
        )
    }

    suspend fun waitForResults(query: String, timeoutMs: Long = 10_000): YouTubeStepResult =
        verifyResultsPage(query, timeoutMs)

    suspend fun verifyResults(query: String, timeoutMs: Long = 10_000): YouTubeStepResult =
        verifyResultsPage(query, timeoutMs)

    suspend fun verifyResultsPage(query: String, timeoutMs: Long = 10_000): YouTubeStepResult {
        val service = serviceProvider()
            ?: return fail("YOUTUBE_SEARCH_FAILED", "Jarvis Accessibility Service is not connected.")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = freshYouTubeRoot(service)
            if (looksLikeResultsPage(root, query)) {
                logStep("YOUTUBE_RESULTS_VERIFIED", "results UI visible")
                return YouTubeStepResult.Success
            }
            delay(350)
        }
        service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
        return fail(
            "YOUTUBE_RESULTS_VERIFIED",
            "Search was submitted but results screen could not be verified."
        )
    }

    suspend fun selectAppropriateResult(query: String): YouTubeStepResult {
        val service = serviceProvider()
            ?: return fail("YOUTUBE_SEARCH_FAILED", "Jarvis Accessibility Service is not connected.")
        val root = freshYouTubeRoot(service)
            ?: return fail("PLAYBACK_SELECT", "No accessibility window content for YouTube.")

        val result = findBestVideoResult(root, query)
            ?: run {
                service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
                return fail("PLAYBACK_SELECT", "Could not find a suitable YouTube result.")
            }

        if (!AccessibilityNodeFinder.performClick(result)) {
            return fail("PLAYBACK_SELECT", "Failed to select YouTube search result.")
        }
        delay(1200)
        logStep("PLAYBACK_SELECT", "result clicked")
        return YouTubeStepResult.Success
    }

    suspend fun startPlayback(query: String): YouTubeStepResult = selectAppropriateResult(query)

    suspend fun verifyPlayback(query: String, timeoutMs: Long = 12_000): YouTubeStepResult {
        val service = serviceProvider()
            ?: return fail("YOUTUBE_SEARCH_FAILED", "Jarvis Accessibility Service is not connected.")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = freshYouTubeRoot(service)
            if (looksLikePlayer(root, query)) {
                logStep("PLAYBACK_VERIFIED", "player UI visible")
                return YouTubeStepResult.Success
            }
            delay(400)
        }
        service.logWindowDiagnostics(TAG, PACKAGE_YOUTUBE)
        return fail("PLAYBACK_VERIFIED", "Could not verify that YouTube is playing the selected result.")
    }

    // --- fresh tree helpers -------------------------------------------------

    /**
     * Always ask the service for a current root. Prefer [AccessibilityService.getRootInActiveWindow]
     * when it is YouTube; otherwise fall back to package-targeted windows.
     */
    private fun freshYouTubeRoot(service: JarvisAccessibilityService): AccessibilityNodeInfo? {
        val active = service.rootInActiveWindow
        if (active?.packageName?.toString() == PACKAGE_YOUTUBE) {
            return active
        }
        return service.rootForPackage(PACKAGE_YOUTUBE)
    }

    private fun ensureYouTubeStillActive(service: JarvisAccessibilityService): Boolean {
        val active = service.rootInActiveWindow
        val pkg = active?.packageName?.toString()
        if (pkg == PACKAGE_YOUTUBE) return true
        if (service.isPackageActive(PACKAGE_YOUTUBE) && service.rootForPackage(PACKAGE_YOUTUBE) != null) {
            return true
        }
        Log.w(TAG, "ensureYouTubeStillActive failed activePkg=$pkg tracked=${service.activePackage.value}")
        return false
    }

    private suspend fun waitForSearchButton(
        service: JarvisAccessibilityService,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = freshYouTubeRoot(service)
            if (root != null) {
                findSearchEntry(root)?.let { return it }
            }
            delay(250)
        }
        return null
    }

    /**
     * After Search click: wait, re-check package, refresh root each attempt.
     */
    private suspend fun waitForSearchInputAfterTransition(
        service: JarvisAccessibilityService,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (!ensureYouTubeStillActive(service)) {
                delay(250)
                continue
            }
            // Explicit fresh read every loop — never cache the root across iterations.
            val root = service.rootInActiveWindow?.takeIf {
                it.packageName?.toString() == PACKAGE_YOUTUBE
            } ?: service.rootForPackage(PACKAGE_YOUTUBE)

            if (root != null) {
                findSearchInput(root)?.let { node ->
                    logStep(
                        "YOUTUBE_SEARCH_INPUT_SEARCHING",
                        "found on attempt=$attempt ${summarizeNode(node)}"
                    )
                    return node
                }
                if (attempt == 1 || attempt % 4 == 0) {
                    Log.d(
                        TAG,
                        "YOUTUBE_SEARCH_INPUT_SEARCHING attempt=$attempt no editable yet; " +
                            "sampleNodes=${AccessibilityNodeFinder.flatten(root).size}"
                    )
                }
            }
            delay(300)
        }
        return null
    }

    private fun findSearchEntry(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        for (id in SEARCH_BUTTON_IDS) {
            AccessibilityNodeFinder.findByViewId(root, id).firstOrNull()?.let { node ->
                return AccessibilityNodeFinder.clickableAncestor(node) ?: node
            }
        }

        // Exact-ish toolbar Search control first (avoid "Search filters", etc.).
        val exactSearch = AccessibilityNodeFinder.flatten(root).firstOrNull { node ->
            val desc = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val label = desc.ifBlank { text }
            label.equals("Search", ignoreCase = true) &&
                (node.isClickable || node.isFocusable ||
                    AccessibilityNodeFinder.clickableAncestor(node) != null)
        }
        if (exactSearch != null) {
            return AccessibilityNodeFinder.clickableAncestor(exactSearch) ?: exactSearch
        }

        val strategies = listOf(
            AccessibilityNodeFinder.NodeMatch(
                descContains = listOf("Search"),
                clickable = true
            ),
            AccessibilityNodeFinder.NodeMatch(
                descContains = listOf("Search YouTube")
            ),
            AccessibilityNodeFinder.NodeMatch(
                viewIdContains = listOf("menu_item_1", "menu_search", "search"),
                clickable = true
            ),
            AccessibilityNodeFinder.NodeMatch(
                classNameContains = listOf("ImageButton", "ImageView", "Button"),
                descContains = listOf("Search")
            ),
            AccessibilityNodeFinder.NodeMatch(
                textContains = listOf("Search"),
                clickable = true
            )
        )
        for (strategy in strategies) {
            val match = AccessibilityNodeFinder.findFirstMatching(root, strategy) ?: continue
            val label = (match.contentDescription?.toString() ?: match.text?.toString()).orEmpty()
            if (label.contains("filter", ignoreCase = true)) continue
            if (label.contains("history", ignoreCase = true)) continue
            return AccessibilityNodeFinder.clickableAncestor(match) ?: match
        }

        return AccessibilityNodeFinder.findClickableWithDesc(
            root,
            "Search",
            "Search YouTube"
        )
    }

    private fun findSearchInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        for (id in SEARCH_INPUT_IDS) {
            AccessibilityNodeFinder.findByViewId(root, id).firstOrNull()?.let { return it }
        }

        // Prefer focused editable.
        AccessibilityNodeFinder.flatten(root).firstOrNull {
            it.isEditable && it.isFocused
        }?.let { return it }

        AccessibilityNodeFinder.findFirstMatching(
            root,
            AccessibilityNodeFinder.NodeMatch(
                editable = true,
                viewIdContains = listOf("search", "edit", "query", "auto")
            )
        )?.let { return it }

        AccessibilityNodeFinder.findFirstMatching(
            root,
            AccessibilityNodeFinder.NodeMatch(
                editable = true,
                descContains = listOf("Search")
            )
        )?.let { return it }

        AccessibilityNodeFinder.findFirstMatching(
            root,
            AccessibilityNodeFinder.NodeMatch(
                editable = true,
                textContains = listOf("Search")
            )
        )?.let { return it }

        // Android 10 YouTube sometimes exposes AutoCompleteTextView / EditText without "search" hint.
        val editClass = AccessibilityNodeFinder.flatten(root).firstOrNull { node ->
            val clazz = node.className?.toString().orEmpty()
            (node.isEditable ||
                clazz.contains("EditText", ignoreCase = true) ||
                clazz.contains("AutoComplete", ignoreCase = true)) &&
                (node.isFocusable || node.isFocused || node.isEditable)
        }
        if (editClass != null) return editClass

        return AccessibilityNodeFinder.findEditable(root)
    }

    private fun findSearchSubmitControl(
        root: AccessibilityNodeInfo?,
        input: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val candidates = AccessibilityNodeFinder.findMatching(
            root,
            AccessibilityNodeFinder.NodeMatch(
                descContains = listOf("Search", "Search YouTube", "Go", "Submit")
            )
        ) + AccessibilityNodeFinder.findMatching(
            root,
            AccessibilityNodeFinder.NodeMatch(
                textContains = listOf("Search", "Go")
            )
        )
        return candidates.firstOrNull { node ->
            node != input && (node.isClickable || AccessibilityNodeFinder.clickableAncestor(node) != null)
        }?.let { AccessibilityNodeFinder.clickableAncestor(it) ?: it }
    }

    private fun verifySearchInputContainsFresh(
        service: JarvisAccessibilityService,
        query: String
    ): Boolean {
        val input = findSearchInput(freshYouTubeRoot(service)) ?: return false
        return AccessibilityNodeFinder.nodeContainsText(input, query)
    }

    private fun findFirstSuggestion(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val suggestion = AccessibilityNodeFinder.flatten(root).firstOrNull { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val label = text.ifBlank { desc }
            label.length in 2..80 &&
                !label.equals("Search", ignoreCase = true) &&
                !label.equals("Clear", ignoreCase = true) &&
                !label.equals("Back", ignoreCase = true) &&
                (node.isClickable || node.parent?.isClickable == true) &&
                node.className?.contains("EditText") != true &&
                !node.isEditable
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
                it.contains("Shorts", ignoreCase = true) ||
                it.contains("subscribers", ignoreCase = true)
        }
        val hasVideoRows = RESULT_CONTAINER_IDS.any {
            AccessibilityNodeFinder.findByViewId(root, it).isNotEmpty()
        } || AccessibilityNodeFinder.findClickableWithDesc(root, "Video", "Play") != null ||
            texts.any { VIDEO_DURATION.matches(it.trim()) } ||
            AccessibilityNodeFinder.findMatching(
                root,
                AccessibilityNodeFinder.NodeMatch(viewIdContains = listOf("video", "result", "thumbnail"))
            ).isNotEmpty()

        return (hasQueryEcho && (hasResultSignals || hasVideoRows)) || hasVideoRows
    }

    private fun findBestVideoResult(root: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = AccessibilityNodeFinder.flatten(root)
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }

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
        val playerIds = PLAYER_IDS.any { AccessibilityNodeFinder.findByViewId(root, it).isNotEmpty() } ||
            AccessibilityNodeFinder.findMatching(
                root,
                AccessibilityNodeFinder.NodeMatch(viewIdContains = listOf("player", "watch"))
            ).isNotEmpty()
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

    private fun summarizeNode(node: AccessibilityNodeInfo): String {
        return "class=${node.className} id=${node.viewIdResourceName} " +
            "desc=${node.contentDescription?.toString()?.take(40)} " +
            "click=${node.isClickable} focus=${node.isFocusable} edit=${node.isEditable}"
    }

    private fun logStep(step: String, detail: String) {
        Log.i(TAG, "$step — $detail")
    }

    private fun fail(step: String, message: String): YouTubeStepResult.Failure {
        Log.e(TAG, "YOUTUBE_SEARCH_FAILED at $step — $message")
        return YouTubeStepResult.Failure(step, message)
    }

    companion object {
        private const val TAG = "YouTubeA11y"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        private val SEARCH_BUTTON_IDS = listOf(
            "$PACKAGE_YOUTUBE:id/menu_item_1",
            "$PACKAGE_YOUTUBE:id/menu_search",
            "$PACKAGE_YOUTUBE:id/search_button",
            "$PACKAGE_YOUTUBE:id/ibb",
            "$PACKAGE_YOUTUBE:id/search"
        )
        private val SEARCH_INPUT_IDS = listOf(
            "$PACKAGE_YOUTUBE:id/search_edit_text",
            "$PACKAGE_YOUTUBE:id/search_box",
            "$PACKAGE_YOUTUBE:id/search_src_text",
            "$PACKAGE_YOUTUBE:id/search_edit",
            "$PACKAGE_YOUTUBE:id/query_edit"
        )
        private val RESULT_CONTAINER_IDS = listOf(
            "$PACKAGE_YOUTUBE:id/results",
            "$PACKAGE_YOUTUBE:id/results_container",
            "$PACKAGE_YOUTUBE:id/results_list"
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
    }
}

typealias YouTubeExecutor = YouTubeAccessibilityExecutor
