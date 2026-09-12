package com.jarvis.assistant.accessibility

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.utils.ContactMatcher
import kotlinx.coroutines.delay

sealed class WhatsAppStepResult {
    data object Success : WhatsAppStepResult()
    data class AmbiguousContacts(val matches: List<String>) : WhatsAppStepResult()
    data class Failure(val message: String) : WhatsAppStepResult()
}

/**
 * WhatsApp-specific accessibility workflow executor.
 * Built as a reusable step API rather than one monolithic click script.
 */
class WhatsAppExecutor(
    private val serviceProvider: () -> JarvisAccessibilityService?
) {
    suspend fun waitUntilWhatsAppVisible(packageName: String, timeoutMs: Long = 8_000): WhatsAppStepResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val service = serviceProvider()
            if (service?.isPackageActive(packageName) == true) {
                return WhatsAppStepResult.Success
            }
            delay(250)
        }
        return WhatsAppStepResult.Failure("WhatsApp did not become visible.")
    }

    suspend fun openChat(contact: String, timeoutMs: Long = 12_000): WhatsAppStepResult {
        val service = serviceProvider()
            ?: return WhatsAppStepResult.Failure("Jarvis Accessibility Service is not connected.")

        // Prefer search entry points when available.
        openSearchIfPossible(service)

        val typed = typeIntoFocusedOrSearch(service, contact)
        if (!typed) {
            Log.w(TAG, "Could not type into search; scanning visible chats.")
        } else {
            delay(900)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastRoot: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < deadline) {
            lastRoot = service.rootInActiveWindow
            val candidates = collectContactCandidates(lastRoot)
            val matches = ContactMatcher.findMatches(contact, candidates)
            when {
                matches.size == 1 -> {
                    val node = findContactNode(lastRoot, matches.first())
                    if (AccessibilityNodeFinder.performClick(node)) {
                        delay(700)
                        return if (verifyChatOpen(service, matches.first())) {
                            WhatsAppStepResult.Success
                        } else {
                            WhatsAppStepResult.Failure("Opened a chat but could not verify \"$contact\".")
                        }
                    }
                }
                matches.size > 1 -> return WhatsAppStepResult.AmbiguousContacts(matches)
            }
            delay(400)
        }

        val remaining = ContactMatcher.findMatches(contact, collectContactCandidates(lastRoot))
        return when {
            remaining.size > 1 -> WhatsAppStepResult.AmbiguousContacts(remaining)
            else -> WhatsAppStepResult.Failure("Could not find WhatsApp contact \"$contact\".")
        }
    }

    suspend fun typeMessage(message: String): WhatsAppStepResult {
        val service = serviceProvider()
            ?: return WhatsAppStepResult.Failure("Jarvis Accessibility Service is not connected.")
        val root = service.rootInActiveWindow
            ?: return WhatsAppStepResult.Failure("No accessibility window content.")

        val input = findMessageInput(root)
            ?: return WhatsAppStepResult.Failure("Could not find WhatsApp message input.")

        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(350)
        val verified = verifyInputContains(service, message)
        return if (set && verified) {
            WhatsAppStepResult.Success
        } else if (verified) {
            WhatsAppStepResult.Success
        } else {
            WhatsAppStepResult.Failure("Failed to enter message text.")
        }
    }

    suspend fun sendMessage(): WhatsAppStepResult {
        val service = serviceProvider()
            ?: return WhatsAppStepResult.Failure("Jarvis Accessibility Service is not connected.")
        val root = service.rootInActiveWindow
            ?: return WhatsAppStepResult.Failure("No accessibility window content.")

        val sendButton = findSendButton(root)
            ?: return WhatsAppStepResult.Failure("Could not find WhatsApp send button.")

        if (!AccessibilityNodeFinder.performClick(sendButton)) {
            return WhatsAppStepResult.Failure("Send button click failed.")
        }
        delay(600)
        return WhatsAppStepResult.Success
    }

    suspend fun verifyMessageSent(message: String): WhatsAppStepResult {
        val service = serviceProvider()
            ?: return WhatsAppStepResult.Failure("Jarvis Accessibility Service is not connected.")
        delay(500)
        val root = service.rootInActiveWindow
        val texts = AccessibilityNodeFinder.collectVisibleTexts(root)
        val found = texts.any { it.contains(message, ignoreCase = true) }
        val inputEmpty = findMessageInput(root)?.text.isNullOrBlank()
        return if (found || inputEmpty) {
            WhatsAppStepResult.Success
        } else {
            WhatsAppStepResult.Failure("Could not verify that the message was sent.")
        }
    }

    private fun openSearchIfPossible(service: JarvisAccessibilityService) {
        val root = service.rootInActiveWindow ?: return
        val search = AccessibilityNodeFinder.findClickableWithDesc(
            root,
            "Search",
            "Search…",
            "Search...",
            "بحث"
        ) ?: AccessibilityNodeFinder.findByViewId(root, "$PKG:id/menuitem_search").firstOrNull()
            ?: AccessibilityNodeFinder.findByViewId(root, "$PKG_BIZ:id/menuitem_search").firstOrNull()
        AccessibilityNodeFinder.performClick(search)
    }

    private fun typeIntoFocusedOrSearch(service: JarvisAccessibilityService, text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val editable = AccessibilityNodeFinder.findEditable(root)
            ?: AccessibilityNodeFinder.findByViewId(root, "$PKG:id/search_input").firstOrNull()
            ?: AccessibilityNodeFinder.findByViewId(root, "$PKG:id/search_src_text").firstOrNull()
            ?: return false
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun collectContactCandidates(root: AccessibilityNodeInfo?): List<String> {
        val nodes = AccessibilityNodeFinder.flatten(root)
        val fromIds = (
            AccessibilityNodeFinder.findByViewId(root, "$PKG:id/conversations_row_contact_name") +
                AccessibilityNodeFinder.findByViewId(root, "$PKG_BIZ:id/conversations_row_contact_name") +
                AccessibilityNodeFinder.findByViewId(root, "$PKG:id/conversation_contact_name") +
                AccessibilityNodeFinder.findByViewId(root, "$PKG:id/contact_row_name")
            ).mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotBlank() }

        val clickableTexts = nodes.filter { it.isClickable || it.parent?.isClickable == true }
            .mapNotNull { it.text?.toString()?.trim() }
            .filter { candidate ->
                candidate.length in 2..48 &&
                    !candidate.contains("\n") &&
                    !LOOKS_LIKE_MESSAGE.matches(candidate) &&
                    candidate.none { it.isDigit() && candidate.count(Char::isDigit) > 4 }
            }

        return (fromIds + clickableTexts).distinct()
    }

    private fun findContactNode(root: AccessibilityNodeInfo?, name: String): AccessibilityNodeInfo? {
        val exact = AccessibilityNodeFinder.findByText(root, name, exact = true).firstOrNull()
        if (exact != null) return AccessibilityNodeFinder.clickableAncestor(exact) ?: exact
        return AccessibilityNodeFinder.findByText(root, name, exact = false)
            .firstOrNull()
            ?.let { AccessibilityNodeFinder.clickableAncestor(it) ?: it }
    }

    private fun verifyChatOpen(service: JarvisAccessibilityService, contact: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val header = AccessibilityNodeFinder.findByViewId(root, "$PKG:id/conversation_contact_name").firstOrNull()
            ?: AccessibilityNodeFinder.findByViewId(root, "$PKG_BIZ:id/conversation_contact_name").firstOrNull()
        val headerText = header?.text?.toString().orEmpty()
        if (headerText.contains(contact, ignoreCase = true)) return true
        return findMessageInput(root) != null
    }

    private fun findMessageInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return AccessibilityNodeFinder.findByViewId(root, "$PKG:id/entry").firstOrNull()
            ?: AccessibilityNodeFinder.findByViewId(root, "$PKG_BIZ:id/entry").firstOrNull()
            ?: AccessibilityNodeFinder.findByViewId(root, "$PKG:id/conversation_entry").firstOrNull()
            ?: AccessibilityNodeFinder.findEditable(root)
    }

    private fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return AccessibilityNodeFinder.findByViewId(root, "$PKG:id/send").firstOrNull()
            ?: AccessibilityNodeFinder.findByViewId(root, "$PKG_BIZ:id/send").firstOrNull()
            ?: AccessibilityNodeFinder.findClickableWithDesc(root, "Send", "ارسال")
    }

    private fun verifyInputContains(service: JarvisAccessibilityService, message: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val input = findMessageInput(root) ?: return false
        val text = input.text?.toString().orEmpty()
        return text.contains(message)
    }

    companion object {
        private const val TAG = "WhatsAppExecutor"
        private const val PKG = "com.whatsapp"
        private const val PKG_BIZ = "com.whatsapp.w4b"
        private val LOOKS_LIKE_MESSAGE = Regex(""".*[.!?].{8,}""")
    }
}
