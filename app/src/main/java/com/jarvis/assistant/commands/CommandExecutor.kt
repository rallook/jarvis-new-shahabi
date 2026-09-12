package com.jarvis.assistant.commands

import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.WhatsAppExecutor
import com.jarvis.assistant.accessibility.WhatsAppStepResult
import com.jarvis.assistant.android.AppLauncher
import kotlinx.coroutines.delay

/**
 * Executes structured [JarvisCommand] values. Confirmation for WhatsApp send
 * is enforced by the ViewModel — this class prepares the chat and types text,
 * then performs the final send only when explicitly asked.
 */
class CommandExecutor(
    private val appLauncher: AppLauncher,
    private val whatsAppExecutor: WhatsAppExecutor = WhatsAppExecutor {
        JarvisAccessibilityService.instance
    }
) {
    suspend fun execute(
        command: JarvisCommand,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        return when (command) {
            is JarvisCommand.OpenApp -> openApp(command, onProgress)
            is JarvisCommand.SendWhatsAppMessage -> prepareWhatsAppSend(command, onProgress)
            is JarvisCommand.Unsupported -> CommandResult.Failure(command.reason)
        }
    }

    /**
     * Called only after the user confirms Send in the UI.
     */
    suspend fun confirmAndSend(
        contact: String,
        message: String,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        if (!JarvisAccessibilityService.isConnected()) {
            return CommandResult.Failure(
                "Enable the Jarvis Accessibility Service in Android Settings, then try again."
            )
        }
        onProgress("Sending…")
        when (val send = whatsAppExecutor.sendMessage()) {
            is WhatsAppStepResult.Failure -> return CommandResult.Failure(send.message)
            is WhatsAppStepResult.AmbiguousContacts -> {
                return CommandResult.Failure("Unexpected contact ambiguity while sending.")
            }
            WhatsAppStepResult.Success -> Unit
        }

        onProgress("Verifying…")
        when (val verify = whatsAppExecutor.verifyMessageSent(message)) {
            is WhatsAppStepResult.Failure -> return CommandResult.Failure(verify.message)
            is WhatsAppStepResult.AmbiguousContacts -> {
                return CommandResult.Failure("Unexpected contact ambiguity while verifying.")
            }
            WhatsAppStepResult.Success -> {
                onProgress("Message sent to $contact.")
                return CommandResult.Success
            }
        }
    }

    private suspend fun openApp(
        command: JarvisCommand.OpenApp,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        onProgress("Opening ${command.appName}…")
        val result = appLauncher.openApp(command.appName, command.packageName)
        if (!result.success) return CommandResult.Failure(result.message)

        if (command.appName.contains("whatsapp", ignoreCase = true) ||
            result.packageName?.contains("whatsapp") == true
        ) {
            val pkg = result.packageName ?: return CommandResult.Failure("WhatsApp package unknown.")
            when (val visible = whatsAppExecutor.waitUntilWhatsAppVisible(pkg)) {
                is WhatsAppStepResult.Failure -> return CommandResult.Failure(visible.message)
                else -> Unit
            }
        }
        return CommandResult.Success
    }

    private suspend fun prepareWhatsAppSend(
        command: JarvisCommand.SendWhatsAppMessage,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        if (!JarvisAccessibilityService.isConnected()) {
            return CommandResult.Failure(
                "Enable the Jarvis Accessibility Service in Android Settings before sending WhatsApp messages."
            )
        }

        onProgress("Opening WhatsApp…")
        val launch = appLauncher.openWhatsApp()
        if (!launch.success || launch.packageName == null) {
            return CommandResult.Failure(launch.message)
        }
        val pkg = launch.packageName

        when (val visible = whatsAppExecutor.waitUntilWhatsAppVisible(pkg)) {
            is WhatsAppStepResult.Failure -> return CommandResult.Failure(visible.message)
            else -> Unit
        }
        onProgress("WhatsApp is open.")
        delay(400)

        onProgress("Finding ${command.contact}…")
        when (val chat = whatsAppExecutor.openChat(command.contact)) {
            is WhatsAppStepResult.Failure -> return CommandResult.Failure(chat.message)
            is WhatsAppStepResult.AmbiguousContacts -> {
                return CommandResult.NeedsContactChoice(
                    contactQuery = command.contact,
                    choices = chat.matches,
                    message = command.message
                )
            }
            WhatsAppStepResult.Success -> Unit
        }

        onProgress("Typing message…")
        when (val typed = whatsAppExecutor.typeMessage(command.message)) {
            is WhatsAppStepResult.Failure -> return CommandResult.Failure(typed.message)
            is WhatsAppStepResult.AmbiguousContacts -> {
                return CommandResult.Failure("Unexpected contact ambiguity while typing.")
            }
            WhatsAppStepResult.Success -> Unit
        }

        onProgress("Ready to send")
        return CommandResult.NeedsConfirmation(command, pkg)
    }
}
