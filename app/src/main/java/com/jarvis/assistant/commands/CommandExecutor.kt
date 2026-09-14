package com.jarvis.assistant.commands

import com.jarvis.assistant.accessibility.GoogleSearchExecutor
import com.jarvis.assistant.accessibility.GoogleSearchStepResult
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.SpotifyAccessibilityExecutor
import com.jarvis.assistant.accessibility.SpotifyStepResult
import com.jarvis.assistant.accessibility.WhatsAppExecutor
import com.jarvis.assistant.accessibility.WhatsAppStepResult
import com.jarvis.assistant.accessibility.YouTubeAccessibilityExecutor
import com.jarvis.assistant.accessibility.YouTubeStepResult
import com.jarvis.assistant.android.AppLauncher
import com.jarvis.assistant.android.TimerAlarmExecutor
import com.jarvis.assistant.android.TimerAlarmResult
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
    },
    private val youTubeExecutor: YouTubeAccessibilityExecutor = YouTubeAccessibilityExecutor {
        JarvisAccessibilityService.instance
    },
    private val spotifyExecutor: SpotifyAccessibilityExecutor = SpotifyAccessibilityExecutor {
        JarvisAccessibilityService.instance
    },
    private val timerAlarmExecutor: TimerAlarmExecutor = TimerAlarmExecutor(appLauncher.context),
    private val googleSearchExecutor: GoogleSearchExecutor = GoogleSearchExecutor(
        context = appLauncher.context,
        serviceProvider = { JarvisAccessibilityService.instance }
    )
) {
    suspend fun execute(
        command: JarvisCommand,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        return when (command) {
            is JarvisCommand.OpenApp -> openApp(command, onProgress)
            is JarvisCommand.SendWhatsAppMessage -> prepareWhatsAppSend(command, onProgress)
            is JarvisCommand.YouTubePlay -> playYouTube(command, onProgress)
            is JarvisCommand.YouTubeSearch -> searchYouTube(command, onProgress)
            is JarvisCommand.PlaySpotifySong -> playSpotify(command, onProgress)
            is JarvisCommand.SetTimer -> setTimer(command, onProgress)
            is JarvisCommand.SetAlarm -> setAlarm(command, onProgress)
            is JarvisCommand.GoogleSearch -> googleSearch(command, onProgress)
            is JarvisCommand.CloseJarvis -> CommandResult.Success
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
        if (command.appName.contains("youtube", ignoreCase = true) ||
            result.packageName == YouTubeAccessibilityExecutor.PACKAGE_YOUTUBE
        ) {
            val pkg = result.packageName ?: YouTubeAccessibilityExecutor.PACKAGE_YOUTUBE
            when (val visible = youTubeExecutor.waitForYouTube(pkg)) {
                is YouTubeStepResult.Failure -> return youtubeFailure(visible)
                else -> Unit
            }
        }
        if (command.appName.contains("spotify", ignoreCase = true) ||
            result.packageName == SpotifyAccessibilityExecutor.PACKAGE_SPOTIFY
        ) {
            val pkg = result.packageName ?: SpotifyAccessibilityExecutor.PACKAGE_SPOTIFY
            when (val visible = spotifyExecutor.waitForSpotify(pkg)) {
                is SpotifyStepResult.Failure -> return spotifyFailure(visible)
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

    private suspend fun playYouTube(
        command: JarvisCommand.YouTubePlay,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        if (!JarvisAccessibilityService.isConnected()) {
            return CommandResult.Failure(
                "Enable the Jarvis Accessibility Service in Android Settings before controlling YouTube."
            )
        }

        onProgress("Opening YouTube…")
        val launch = appLauncher.openYouTube()
        if (!launch.success || launch.packageName == null) {
            return CommandResult.Failure(launch.message)
        }
        when (val visible = youTubeExecutor.openYouTube(launch.packageName)) {
            is YouTubeStepResult.Failure -> return youtubeFailure(visible)
            else -> Unit
        }
        delay(800)

        onProgress("Finding YouTube search…")
        when (val search = youTubeExecutor.openSearch()) {
            is YouTubeStepResult.Failure -> return youtubeFailure(search)
            else -> Unit
        }

        onProgress("Entering search: ${command.songName}")
        when (val typed = youTubeExecutor.enterSearchQuery(command.songName)) {
            is YouTubeStepResult.Failure -> return youtubeFailure(typed)
            else -> Unit
        }
        onProgress("Submitting YouTube search…")
        when (val submitted = youTubeExecutor.submitSearch()) {
            is YouTubeStepResult.Failure -> return youtubeFailure(submitted)
            else -> Unit
        }
        onProgress("Verifying YouTube results…")
        when (val results = youTubeExecutor.waitForResults(command.songName)) {
            is YouTubeStepResult.Failure -> return youtubeFailure(results)
            else -> Unit
        }

        onProgress("Selecting a result…")
        when (val selected = youTubeExecutor.startPlayback(command.songName)) {
            is YouTubeStepResult.Failure -> return youtubeFailure(selected)
            else -> Unit
        }

        onProgress("Starting playback…")
        when (val playing = youTubeExecutor.verifyPlayback(command.songName)) {
            is YouTubeStepResult.Failure -> return youtubeFailure(playing)
            YouTubeStepResult.Success -> {
                onProgress("Playing ${command.songName}.")
                return CommandResult.Success
            }
        }
    }

    private suspend fun searchYouTube(
        command: JarvisCommand.YouTubeSearch,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        if (!JarvisAccessibilityService.isConnected()) {
            return CommandResult.Failure(
                "Enable the Jarvis Accessibility Service in Android Settings before controlling YouTube."
            )
        }

        onProgress("Opening YouTube…")
        val launch = appLauncher.openYouTube()
        if (!launch.success || launch.packageName == null) {
            return CommandResult.Failure(launch.message)
        }
        when (val visible = youTubeExecutor.openYouTube(launch.packageName)) {
            is YouTubeStepResult.Failure -> return youtubeFailure(visible)
            else -> Unit
        }
        delay(800)

        onProgress("Finding YouTube search…")
        when (val search = youTubeExecutor.openSearch()) {
            is YouTubeStepResult.Failure -> return youtubeFailure(search)
            else -> Unit
        }

        onProgress("Entering search: ${command.query}")
        when (val typed = youTubeExecutor.enterSearchQuery(command.query)) {
            is YouTubeStepResult.Failure -> return youtubeFailure(typed)
            else -> Unit
        }
        onProgress("Submitting YouTube search…")
        when (val submitted = youTubeExecutor.submitSearch()) {
            is YouTubeStepResult.Failure -> return youtubeFailure(submitted)
            else -> Unit
        }
        onProgress("Verifying YouTube results…")
        when (val results = youTubeExecutor.verifyResults(command.query)) {
            is YouTubeStepResult.Failure -> return youtubeFailure(results)
            YouTubeStepResult.Success -> {
                onProgress("Showing YouTube results for ${command.query}.")
                return CommandResult.Success
            }
        }
    }

    private fun youtubeFailure(failure: YouTubeStepResult.Failure): CommandResult.Failure {
        return CommandResult.Failure("${failure.step}: ${failure.message}")
    }

    private suspend fun playSpotify(
        command: JarvisCommand.PlaySpotifySong,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        if (!JarvisAccessibilityService.isConnected()) {
            return CommandResult.Failure(
                "Enable the Jarvis Accessibility Service in Android Settings before controlling Spotify."
            )
        }
        onProgress("Opening Spotify…")
        val launch = appLauncher.openSpotify()
        if (!launch.success || launch.packageName == null) {
            return CommandResult.Failure(launch.message)
        }
        when (val visible = spotifyExecutor.waitForSpotify(launch.packageName)) {
            is SpotifyStepResult.Failure -> return spotifyFailure(visible)
            else -> Unit
        }
        delay(700)

        onProgress("Searching for ${command.song}…")
        when (val typed = spotifyExecutor.openSearchAndEnterQuery(command.song, command.artist)) {
            is SpotifyStepResult.Failure -> return spotifyFailure(typed)
            else -> Unit
        }

        onProgress("Playing ${command.song}…")
        when (val played = spotifyExecutor.selectAndPlay(command.song, command.artist)) {
            is SpotifyStepResult.Failure -> return spotifyFailure(played)
            SpotifyStepResult.Success -> {
                onProgress("Playing ${command.song}.")
                return CommandResult.Success
            }
        }
    }

    private suspend fun setTimer(
        command: JarvisCommand.SetTimer,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        onProgress("Setting timer…")
        return when (val result = timerAlarmExecutor.setTimer(command.durationSeconds)) {
            TimerAlarmResult.Success -> {
                onProgress("Timer set.")
                CommandResult.Success
            }
            is TimerAlarmResult.Failure -> CommandResult.Failure("${result.step}: ${result.message}")
        }
    }

    private suspend fun setAlarm(
        command: JarvisCommand.SetAlarm,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        onProgress("Setting alarm…")
        return when (val result = timerAlarmExecutor.setAlarm(command.hour, command.minute)) {
            TimerAlarmResult.Success -> {
                onProgress("Alarm set.")
                CommandResult.Success
            }
            is TimerAlarmResult.Failure -> CommandResult.Failure("${result.step}: ${result.message}")
        }
    }

    private suspend fun googleSearch(
        command: JarvisCommand.GoogleSearch,
        onProgress: suspend (String) -> Unit
    ): CommandResult {
        onProgress("Searching Google…")
        return when (val result = googleSearchExecutor.search(command.query)) {
            GoogleSearchStepResult.Success -> {
                onProgress("Search completed.")
                CommandResult.Success
            }
            is GoogleSearchStepResult.Failure ->
                CommandResult.Failure("${result.step}: ${result.message}")
        }
    }

    private fun spotifyFailure(failure: SpotifyStepResult.Failure): CommandResult.Failure {
        return CommandResult.Failure("${failure.step}: ${failure.message}")
    }
}
