package com.jarvis.assistant.accessibility

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class GoogleSearchStepResult {
    data object Success : GoogleSearchStepResult()
    data class Failure(val step: String, val message: String) : GoogleSearchStepResult()
}

/**
 * Opens Google search via native intents first, then verifies (and if needed
 * completes) the query using the shared [JarvisAccessibilityService].
 */
class GoogleSearchExecutor(
    private val context: Context,
    private val serviceProvider: () -> JarvisAccessibilityService?
) {
    suspend fun search(query: String): GoogleSearchStepResult {
        Log.i(TAG, "GOOGLE_SEARCH_STARTED — queryLength=${query.length}")
        if (query.isBlank()) {
            return fail("GOOGLE_SEARCH_FAILED", "Empty Google search query.")
        }

        if (!openGoogleSearch(query)) {
            return fail("GOOGLE_TARGET_OPENED", "Could not open Google search.")
        }
        logStep("GOOGLE_TARGET_OPENED", "search intent dispatched")
        delay(900)

        val service = serviceProvider()
        if (service == null) {
            // Intent may still have opened results; without a11y we cannot verify.
            return fail(
                "GOOGLE_RESULTS_VERIFIED",
                "Google search opened but Accessibility Service is not connected for verification."
            )
        }

        val root = waitForSearchableWindow(service, timeoutMs = 10_000)
            ?: run {
                service.logWindowDiagnostics(TAG)
                return fail("GOOGLE_TARGET_OPENED", "No active window after opening Google search.")
            }

        // If results already show the query, we're done (ACTION_WEB_SEARCH often lands on results).
        if (looksLikeResults(root, query)) {
            logStep("GOOGLE_RESULTS_VERIFIED", "results visible after intent")
            return GoogleSearchStepResult.Success
        }

        // Otherwise find an input, type, submit.
        var input = findSearchInput(root)
        if (input == null) {
            val searchControl = findSearchControl(root)
            if (searchControl != null) {
                AccessibilityNodeFinder.clickOrGesture(service, searchControl)
                delay(500)
                input = waitForInput(service, 5_000)
            }
        }
        if (input == null) {
            // Soft success if query appears anywhere in the tree after open.
            val refreshed = service.rootInActiveWindow
            if (looksLikeResults(refreshed, query)) {
                logStep("GOOGLE_RESULTS_VERIFIED", "query visible without explicit input")
                return GoogleSearchStepResult.Success
            }
            service.logWindowDiagnostics(TAG)
            return fail("GOOGLE_SEARCH_INPUT_FOUND", "Could not find Google search input.")
        }
        logStep("GOOGLE_SEARCH_INPUT_FOUND", summarize(input))

        val focused = input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        logStep("GOOGLE_SEARCH_INPUT_FOCUSED", "focus=$focused")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        var entered = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(350)
        if (!entered && !AccessibilityNodeFinder.nodeContainsText(input, query)) {
            entered = AccessibilityNodeFinder.enterText(
                findSearchInput(service.rootInActiveWindow),
                query,
                context
            )
            delay(300)
        }
        val hasText = findSearchInput(service.rootInActiveWindow)
            ?.let { AccessibilityNodeFinder.nodeContainsText(it, query) } == true ||
            looksLikeResults(service.rootInActiveWindow, query)
        if (!entered && !hasText) {
            return fail("GOOGLE_QUERY_ENTERED", "Failed to enter Google search query.")
        }
        logStep("GOOGLE_QUERY_ENTERED", "query length=${query.length}")

        val freshInput = findSearchInput(service.rootInActiveWindow)
        var submitted = freshInput != null && AccessibilityNodeFinder.performImeEnter(freshInput)
        if (!submitted) {
            val go = AccessibilityNodeFinder.findClickableWithDesc(
                service.rootInActiveWindow,
                "Search",
                "Go",
                "Google Search"
            )
            submitted = go != null && AccessibilityNodeFinder.clickOrGesture(service, go)
        }
        if (!submitted && looksLikeResults(service.rootInActiveWindow, query)) {
            submitted = true
        }
        if (!submitted) {
            return fail("GOOGLE_SEARCH_SUBMITTED", "Could not submit Google search.")
        }
        logStep("GOOGLE_SEARCH_SUBMITTED", "submit accepted")
        delay(900)

        val verifyDeadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < verifyDeadline) {
            if (looksLikeResults(service.rootInActiveWindow, query)) {
                logStep("GOOGLE_RESULTS_VERIFIED", "results UI verified")
                return GoogleSearchStepResult.Success
            }
            delay(350)
        }
        service.logWindowDiagnostics(TAG)
        return fail("GOOGLE_RESULTS_VERIFIED", "Google results UI could not be verified.")
    }

    private fun openGoogleSearch(query: String): Boolean {
        // Prefer platform web search.
        try {
            val webSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (webSearch.resolveActivity(context.packageManager) != null) {
                context.startActivity(webSearch)
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ACTION_WEB_SEARCH failed", t)
        }
        return try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val view = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=$encoded")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open Google URL", t)
            false
        }
    }

    private suspend fun waitForSearchableWindow(
        service: JarvisAccessibilityService,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            service.rootInActiveWindow?.let { return it }
            delay(250)
        }
        return null
    }

    private suspend fun waitForInput(
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

    private fun findSearchControl(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return AccessibilityNodeFinder.findClickableWithDesc(
            root,
            "Search",
            "Google Search",
            "Search or type URL"
        )
    }

    private fun findSearchInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        AccessibilityNodeFinder.flatten(root).firstOrNull { it.isEditable && it.isFocused }?.let { return it }
        AccessibilityNodeFinder.findFirstMatching(
            root,
            AccessibilityNodeFinder.NodeMatch(
                editable = true,
                descContains = listOf("Search", "Google")
            )
        )?.let { return it }
        AccessibilityNodeFinder.findFirstMatching(
            root,
            AccessibilityNodeFinder.NodeMatch(editable = true)
        )?.let { return it }
        return AccessibilityNodeFinder.findEditable(root)
    }

    private fun looksLikeResults(root: AccessibilityNodeInfo?, query: String): Boolean {
        if (root == null) return false
        val texts = AccessibilityNodeFinder.collectVisibleTexts(root)
        val queryHit = texts.any { it.contains(query, ignoreCase = true) } ||
            query.split(Regex("\\s+")).filter { it.length > 2 }.let { words ->
                words.isNotEmpty() && texts.any { t -> words.count { t.lowercase().contains(it.lowercase()) } >= 1 }
            }
        val resultSignals = texts.any {
            it.contains("All", ignoreCase = true) ||
                it.contains("Images", ignoreCase = true) ||
                it.contains("News", ignoreCase = true) ||
                it.contains("Videos", ignoreCase = true) ||
                it.contains("Results", ignoreCase = true) ||
                it.contains("About", ignoreCase = true)
        }
        return queryHit && (resultSignals || texts.size > 8)
    }

    private fun summarize(node: AccessibilityNodeInfo): String {
        return "class=${node.className} edit=${node.isEditable} focus=${node.isFocusable}"
    }

    private fun logStep(step: String, detail: String) {
        Log.i(TAG, "$step — $detail")
    }

    private fun fail(step: String, message: String): GoogleSearchStepResult.Failure {
        Log.e(TAG, "GOOGLE_SEARCH_FAILED at $step — $message")
        return GoogleSearchStepResult.Failure(step, message)
    }

    companion object {
        private const val TAG = "GoogleSearchA11y"
    }
}
