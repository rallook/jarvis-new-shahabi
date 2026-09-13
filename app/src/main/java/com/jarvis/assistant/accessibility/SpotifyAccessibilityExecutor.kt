package com.jarvis.assistant.accessibility

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

sealed class SpotifyStepResult {
    data object Success : SpotifyStepResult()
    data class Failure(val step: String, val message: String) : SpotifyStepResult()
}

/**
 * Spotify play-song workflow via the shared [JarvisAccessibilityService].
 * Mirrors YouTube’s click → bounds gesture fallback pattern.
 */
class SpotifyAccessibilityExecutor(
    private val serviceProvider: () -> JarvisAccessibilityService?
) {
    suspend fun waitForSpotify(packageName: String = PACKAGE_SPOTIFY, timeoutMs: Long = 12_000): SpotifyStepResult {
        logStep("SPOTIFY_OPEN_STARTED", "waiting for $packageName")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val service = serviceProvider() ?: break
            if (service.isPackageActive(packageName) && freshRoot(service, packageName) != null) {
                logStep("SPOTIFY_WINDOW_DETECTED", "package=$packageName")
                return SpotifyStepResult.Success
            }
            delay(250)
        }
        serviceProvider()?.logWindowDiagnostics(TAG, packageName)
        return fail("SPOTIFY_WINDOW_DETECTED", "Spotify did not become visible.")
    }

    suspend fun openSearchAndEnterQuery(
        song: String,
        artist: String?,
        packageName: String = PACKAGE_SPOTIFY
    ): SpotifyStepResult {
        val service = serviceProvider()
            ?: return fail("SPOTIFY_SEARCH_FAILED", "Jarvis Accessibility Service is not connected.")
        val query = buildQuery(song, artist)

        // Already on editable search UI?
        refreshSpotifyRoot(service, packageName)?.let { root ->
            findSearchInput(root)?.let {
                logStep("SPOTIFY_SEARCH_INPUT_FOUND", "already on search UI — ${summarize(it)}")
                return focusEnterAndSubmit(service, packageName, song, artist, query)
            }
        }

        val button = waitForSearchButton(service, packageName, 8_000)
            ?: run {
                service.logWindowDiagnostics(TAG, packageName)
                return fail("SPOTIFY_SEARCH_BUTTON_FOUND", "Spotify search control not found.")
            }
        logStep("SPOTIFY_SEARCH_BUTTON_FOUND", summarize(button))

        if (!AccessibilityNodeFinder.clickOrGesture(service, button)) {
            return fail(
                "SPOTIFY_SEARCH_BUTTON_CLICKED",
                "ACTION_CLICK and dispatchGesture both failed on Search."
            )
        }
        logStep("SPOTIFY_SEARCH_BUTTON_CLICKED", "click/gesture accepted")

        // Critical: do not reuse pre-click nodes; wait for Spotify search UI transition.
        logStep("SPOTIFY_SEARCH_UI_WAITING", "waiting for search UI transition")
        delay(700)

        if (!ensureSpotifyActive(service, packageName)) {
            return fail(
                "SPOTIFY_SEARCH_UI_WAITING",
                "Spotify is no longer the active package after opening Search."
            )
        }

        val firstRoot = service.rootInActiveWindow
        logStep(
            "SPOTIFY_SEARCH_TREE_REFRESHED",
            "activePkg=${firstRoot?.packageName} hasRoot=${firstRoot != null}"
        )

        val input = waitForSearchInputAfterTransition(service, packageName, timeoutMs = 10_000)
            ?: run {
                service.logWindowDiagnostics(TAG, packageName)
                refreshSpotifyRoot(service, packageName)?.let {
                    AccessibilityNodeFinder.logHierarchy(TAG, it, limit = 80)
                }
                return fail(
                    "SPOTIFY_SEARCH_INPUT_SEARCHING",
                    "Search opened but no editable search input is exposed in the accessibility tree."
                )
            }

        logStep("SPOTIFY_SEARCH_INPUT_FOUND", summarize(input))
        return focusEnterAndSubmit(service, packageName, song, artist, query)
    }

    private suspend fun focusEnterAndSubmit(
        service: JarvisAccessibilityService,
        packageName: String,
        song: String,
        artist: String?,
        query: String
    ): SpotifyStepResult {
        // Always re-resolve input from a fresh tree before focus/type.
        val input = findSearchInput(refreshSpotifyRoot(service, packageName))
            ?: return fail("SPOTIFY_SEARCH_INPUT_SEARCHING", "Editable search input disappeared before focus.")

        val focused = input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        logStep(
            "SPOTIFY_SEARCH_INPUT_FOCUSED",
            "focusAction=$focused editable=${input.isEditable} focusable=${input.isFocusable}"
        )

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        val set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(400)
        var verified = findSearchInput(refreshSpotifyRoot(service, packageName))
            ?.let { AccessibilityNodeFinder.nodeContainsText(it, song) } == true
        if (!set && !verified) {
            val entered = AccessibilityNodeFinder.enterText(
                findSearchInput(refreshSpotifyRoot(service, packageName)),
                query,
                service.applicationContext
            )
            delay(350)
            verified = findSearchInput(refreshSpotifyRoot(service, packageName))
                ?.let { AccessibilityNodeFinder.nodeContainsText(it, song) } == true
            if (!entered && !verified) {
                return fail("SPOTIFY_QUERY_ENTERED", "Failed to enter Spotify search query.")
            }
        }
        logStep("SPOTIFY_QUERY_ENTERED", "query length=${query.length}")

        val submitInput = findSearchInput(refreshSpotifyRoot(service, packageName))
        var submitted = submitInput != null && AccessibilityNodeFinder.performImeEnter(submitInput)
        if (!submitted) {
            val suggestion = findFirstResultRow(refreshSpotifyRoot(service, packageName), song, artist)
            if (suggestion != null && AccessibilityNodeFinder.clickOrGesture(service, suggestion)) {
                logStep("SPOTIFY_SEARCH_SUBMITTED", "via result/suggestion")
                delay(900)
                return SpotifyStepResult.Success
            }
        }
        if (!submitted) {
            return fail("SPOTIFY_SEARCH_SUBMITTED", "Could not submit Spotify search.")
        }
        logStep("SPOTIFY_SEARCH_SUBMITTED", "via IME enter")
        delay(900)
        return SpotifyStepResult.Success
    }

    suspend fun selectAndPlay(
        song: String,
        artist: String?,
        packageName: String = PACKAGE_SPOTIFY
    ): SpotifyStepResult {
        val service = serviceProvider()
            ?: return fail("SPOTIFY_PLAY_FAILED", "Jarvis Accessibility Service is not connected.")

        val deadline = System.currentTimeMillis() + 10_000
        var match: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < deadline) {
            val root = freshRoot(service, packageName)
            if (root != null) {
                logStep("SPOTIFY_RESULTS_DETECTED", "scanning results")
                match = findBestMatch(root, song, artist)
                if (match != null) break
            }
            delay(350)
        }
        if (match == null) {
            service.logWindowDiagnostics(TAG, packageName)
            return fail(
                "SPOTIFY_MATCH_FOUND",
                "Could not confidently match \"$song\" in Spotify results."
            )
        }
        logStep("SPOTIFY_MATCH_FOUND", summarize(match))

        if (!AccessibilityNodeFinder.clickOrGesture(service, match)) {
            return fail("SPOTIFY_PLAY_CLICKED", "Failed to open matched Spotify result.")
        }
        logStep("SPOTIFY_PLAY_CLICKED", "result opened")
        delay(1000)

        // Prefer an explicit Play control if still on a list/detail without autoplay.
        val playBtn = findPlayControl(freshRoot(service, packageName))
        if (playBtn != null) {
            AccessibilityNodeFinder.clickOrGesture(service, playBtn)
            delay(800)
        }

        return verifyPlayback(service, packageName, song, artist)
    }

    private suspend fun verifyPlayback(
        service: JarvisAccessibilityService,
        packageName: String,
        song: String,
        artist: String?,
        timeoutMs: Long = 10_000
    ): SpotifyStepResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val queryWords = song.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        while (System.currentTimeMillis() < deadline) {
            val root = freshRoot(service, packageName)
            if (root == null) {
                delay(350)
                continue
            }
            val texts = AccessibilityNodeFinder.collectVisibleTexts(root)
            val titleHit = texts.any { t ->
                t.contains(song, ignoreCase = true) ||
                    queryWords.count { t.lowercase().contains(it) } >= (queryWords.size.coerceAtLeast(1))
            }
            val artistHit = artist.isNullOrBlank() || texts.any { it.contains(artist, ignoreCase = true) }
            val playerSignals = AccessibilityNodeFinder.findClickableWithDesc(
                root,
                "Pause",
                "Play",
                "Next",
                "Previous",
                "Shuffle"
            ) != null || texts.any {
                it.contains("Playing", ignoreCase = true) ||
                    it.equals("Pause", ignoreCase = true)
            }
            if (titleHit && artistHit && playerSignals) {
                logStep("SPOTIFY_PLAYBACK_VERIFIED", "player UI + title evidence")
                return SpotifyStepResult.Success
            }
            // Softer verify: title + any player control
            if (titleHit && playerSignals) {
                logStep("SPOTIFY_PLAYBACK_VERIFIED", "player UI + song title")
                return SpotifyStepResult.Success
            }
            delay(400)
        }
        service.logWindowDiagnostics(TAG, packageName)
        return fail(
            "SPOTIFY_PLAYBACK_VERIFIED",
            "Opened a result but could not verify playback of \"$song\"."
        )
    }

    private fun buildQuery(song: String, artist: String?): String {
        return if (artist.isNullOrBlank()) song else "$song $artist"
    }

    /**
     * Prefer [AccessibilityService.getRootInActiveWindow] when Spotify is active.
     * Always call anew after UI transitions — never cache across waits.
     */
    private fun refreshSpotifyRoot(
        service: JarvisAccessibilityService,
        packageName: String
    ): AccessibilityNodeInfo? {
        val active = service.rootInActiveWindow
        if (active?.packageName?.toString() == packageName) return active
        return service.rootForPackage(packageName)
    }

    private fun freshRoot(service: JarvisAccessibilityService, packageName: String): AccessibilityNodeInfo? {
        return refreshSpotifyRoot(service, packageName)
    }

    private fun ensureSpotifyActive(service: JarvisAccessibilityService, packageName: String): Boolean {
        val active = service.rootInActiveWindow
        if (active?.packageName?.toString() == packageName) return true
        return service.isPackageActive(packageName) && service.rootForPackage(packageName) != null
    }

    private suspend fun waitForSearchButton(
        service: JarvisAccessibilityService,
        packageName: String,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = refreshSpotifyRoot(service, packageName)
            if (root != null) findSearchEntry(root)?.let { return it }
            delay(250)
        }
        return null
    }

    /**
     * After Search tab/button: wait, confirm package, refresh tree each attempt.
     * Spotify often needs a second tap on the search field before an EditText appears.
     */
    private suspend fun waitForSearchInputAfterTransition(
        service: JarvisAccessibilityService,
        packageName: String,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var tappedSearchField = false
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (!ensureSpotifyActive(service, packageName)) {
                delay(250)
                continue
            }

            logStep("SPOTIFY_SEARCH_INPUT_SEARCHING", "attempt=$attempt refreshing tree")
            val root = service.rootInActiveWindow?.takeIf {
                it.packageName?.toString() == packageName
            } ?: service.rootForPackage(packageName)

            if (root != null) {
                findSearchInput(root)?.let { node ->
                    logStep(
                        "SPOTIFY_SEARCH_INPUT_SEARCHING",
                        "found editable on attempt=$attempt — ${summarize(node)}"
                    )
                    return node
                }

                // Search landing page: "What do you want to listen to?" is often clickable, not editable yet.
                if (!tappedSearchField) {
                    val field = findSearchFieldActivator(root)
                    if (field != null) {
                        logStep(
                            "SPOTIFY_SEARCH_INPUT_SEARCHING",
                            "tapping search field activator — ${summarize(field)}"
                        )
                        if (AccessibilityNodeFinder.clickOrGesture(service, field)) {
                            tappedSearchField = true
                            delay(600)
                            continue
                        }
                    }
                }
            }
            delay(300)
        }
        return null
    }

    private fun findSearchEntry(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in SEARCH_IDS) {
            AccessibilityNodeFinder.findByViewId(root, id).firstOrNull()?.let {
                return AccessibilityNodeFinder.clickableAncestor(it) ?: it
            }
        }
        val exact = AccessibilityNodeFinder.flatten(root).firstOrNull { node ->
            val label = (node.contentDescription?.toString() ?: node.text?.toString()).orEmpty()
            label.equals("Search", ignoreCase = true)
        }
        if (exact != null) return AccessibilityNodeFinder.clickableAncestor(exact) ?: exact
        return AccessibilityNodeFinder.findClickableWithDesc(root, "Search", "Search Spotify")
    }

    /**
     * Non-editable search bar / hint that must be activated to reveal the EditText.
     */
    private fun findSearchFieldActivator(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val hints = listOf(
            "What do you want to listen to",
            "Search songs",
            "Search artists",
            "Search Spotify",
            "Search"
        )
        val nodes = AccessibilityNodeFinder.flatten(root)
        for (hint in hints) {
            val match = nodes.firstOrNull { node ->
                if (node.isEditable) return@firstOrNull false
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val id = node.viewIdResourceName.orEmpty()
                val label = "$text $desc"
                (label.contains(hint, ignoreCase = true) ||
                    id.contains("search", ignoreCase = true)) &&
                    (node.isClickable || node.isFocusable ||
                        AccessibilityNodeFinder.clickableAncestor(node) != null)
            }
            if (match != null) {
                return AccessibilityNodeFinder.clickableAncestor(match) ?: match
            }
        }
        return null
    }

    private fun findSearchInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        for (id in SEARCH_INPUT_IDS) {
            AccessibilityNodeFinder.findByViewId(root, id).firstOrNull()?.let { return it }
        }

        // Focused editable first.
        AccessibilityNodeFinder.flatten(root).firstOrNull {
            it.isEditable && it.isFocused
        }?.let { return it }

        // Multi-property strategies — do not rely on a single resource ID.
        val strategies = listOf(
            AccessibilityNodeFinder.NodeMatch(
                editable = true,
                viewIdContains = listOf("search", "query", "edit", "input")
            ),
            AccessibilityNodeFinder.NodeMatch(
                editable = true,
                descContains = listOf("Search", "listen")
            ),
            AccessibilityNodeFinder.NodeMatch(
                editable = true,
                textContains = listOf("Search", "listen")
            ),
            AccessibilityNodeFinder.NodeMatch(
                editable = true,
                focusable = true
            ),
            AccessibilityNodeFinder.NodeMatch(
                classNameContains = listOf("EditText", "AutoComplete"),
                focusable = true
            )
        )
        for (strategy in strategies) {
            AccessibilityNodeFinder.findFirstMatching(root, strategy)?.let { return it }
        }

        // Any editable / EditText-like node in the Spotify search UI.
        return AccessibilityNodeFinder.flatten(root).firstOrNull { node ->
            val clazz = node.className?.toString().orEmpty()
            node.isEditable ||
                clazz.contains("EditText", ignoreCase = true) ||
                clazz.contains("AutoComplete", ignoreCase = true)
        }
    }

    private fun findBestMatch(
        root: AccessibilityNodeInfo?,
        song: String,
        artist: String?
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val songWords = song.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        val scored = AccessibilityNodeFinder.flatten(root).mapNotNull { node ->
            val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim()
            if (label.length < 2 || label.length > 120) return@mapNotNull null
            if (label.equals("Search", ignoreCase = true)) return@mapNotNull null
            if (label.equals("Home", ignoreCase = true)) return@mapNotNull null
            val clickable = AccessibilityNodeFinder.clickableAncestor(node) ?: return@mapNotNull null
            var score = 0
            if (label.equals(song, ignoreCase = true)) score += 10
            if (label.contains(song, ignoreCase = true)) score += 5
            score += songWords.count { label.lowercase().contains(it) }
            if (!artist.isNullOrBlank() && label.contains(artist, ignoreCase = true)) score += 4
            if (score <= 0) return@mapNotNull null
            Triple(clickable, label, score)
        }.sortedByDescending { it.third }

        // Require a confident match (at least one strong signal).
        return scored.firstOrNull { it.third >= 3 }?.first
            ?: scored.firstOrNull { it.second.contains(song, ignoreCase = true) }?.first
    }

    private fun findFirstResultRow(
        root: AccessibilityNodeInfo?,
        song: String,
        artist: String?
    ): AccessibilityNodeInfo? = findBestMatch(root, song, artist)

    private fun findPlayControl(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return AccessibilityNodeFinder.findClickableWithDesc(
            root,
            "Play",
            "Play song",
            "Play track"
        )
    }

    private fun summarize(node: AccessibilityNodeInfo): String {
        return "class=${node.className} id=${node.viewIdResourceName} " +
            "desc=${node.contentDescription?.toString()?.take(40)} click=${node.isClickable}"
    }

    private fun logStep(step: String, detail: String) {
        Log.i(TAG, "$step — $detail")
    }

    private fun fail(step: String, message: String): SpotifyStepResult.Failure {
        Log.e(TAG, "SPOTIFY_SEARCH_FAILED at $step — $message")
        return SpotifyStepResult.Failure(step, message)
    }

    companion object {
        private const val TAG = "SpotifyA11y"
        const val PACKAGE_SPOTIFY = "com.spotify.music"

        private val SEARCH_IDS = listOf(
            "$PACKAGE_SPOTIFY:id/search_tab",
            "$PACKAGE_SPOTIFY:id/search_icon",
            "$PACKAGE_SPOTIFY:id/find_search_field",
            "$PACKAGE_SPOTIFY:id/search"
        )
        private val SEARCH_INPUT_IDS = listOf(
            "$PACKAGE_SPOTIFY:id/query",
            "$PACKAGE_SPOTIFY:id/search_src_text",
            "$PACKAGE_SPOTIFY:id/edit_text",
            "$PACKAGE_SPOTIFY:id/search_input",
            "$PACKAGE_SPOTIFY:id/search_edit_text",
            "$PACKAGE_SPOTIFY:id/find_search_field_text"
        )
    }
}
