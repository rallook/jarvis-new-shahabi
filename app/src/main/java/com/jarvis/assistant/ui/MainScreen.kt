package com.jarvis.assistant.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.state.JarvisPhase
import com.jarvis.assistant.state.JarvisUiState
import com.jarvis.assistant.ui.components.AudioLevelIndicator
import com.jarvis.assistant.ui.components.JarvisIdentity
import com.jarvis.assistant.ui.components.MicButton
import com.jarvis.assistant.ui.theme.ButtonShape
import com.jarvis.assistant.ui.theme.PanelShape

@Composable
fun MainScreen(
    state: JarvisUiState,
    onToggleListening: () -> Unit,
    onConfirmSend: () -> Unit,
    onCancelSend: () -> Unit,
    onSelectContact: (String) -> Unit,
    onSubmitTextCommand: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshSetup: () -> Unit,
    showSetup: Boolean
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Open settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            JarvisIdentity(
                statusText = state.statusText,
                isListening = state.isListening
            )

            Spacer(modifier = Modifier.weight(1f))

            if (showSetup) {
                SetupCard(
                    needsMicrophone = state.needsMicrophone,
                    needsAccessibility = state.needsAccessibility,
                    onOpenAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRefresh = onRefreshSetup
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            AssistantBottomPanel(
                state = state,
                onToggleListening = onToggleListening,
                onConfirmSend = onConfirmSend,
                onCancelSend = onCancelSend,
                onSelectContact = onSelectContact,
                onSubmitTextCommand = onSubmitTextCommand,
                micEnabled = !state.needsMicrophone &&
                    state.phase !in setOf(JarvisPhase.THINKING, JarvisPhase.SENDING, JarvisPhase.VERIFYING)
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SetupCard(
    needsMicrophone: Boolean,
    needsAccessibility: Boolean,
    onOpenAccessibility: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Setup required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (needsMicrophone) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Allow microphone access so Jarvis can hear voice commands.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (needsAccessibility) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Rounded.AccessibilityNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Manually enable Jarvis in Android Accessibility settings. Jarvis cannot turn this on for you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onOpenAccessibility,
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Accessibility settings")
                }
                OutlinedButton(
                    onClick = onRefresh,
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("I've enabled it")
                }
            }
        }
    }
}

@Composable
private fun AssistantBottomPanel(
    state: JarvisUiState,
    onToggleListening: () -> Unit,
    onConfirmSend: () -> Unit,
    onCancelSend: () -> Unit,
    onSelectContact: (String) -> Unit,
    onSubmitTextCommand: (String) -> Unit,
    micEnabled: Boolean
) {
    var typedCommand by remember { mutableStateOf("") }
    val canType = state.phase in setOf(
        JarvisPhase.IDLE,
        JarvisPhase.COMPLETED,
        JarvisPhase.ERROR,
        JarvisPhase.CONFIRMATION
    ) && !state.isListening

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .animateContentSize(),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = if (state.isListening) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (state.isListening) "Listening" else "Voice command",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AudioLevelIndicator(
                level = state.audioLevel,
                active = state.isListening
            )

            AnimatedContent(
                targetState = panelBodyKey(state),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "panelBody"
            ) {
                PanelBody(
                    state = state,
                    onConfirmSend = onConfirmSend,
                    onCancelSend = onCancelSend,
                    onSelectContact = onSelectContact
                )
            }

            OutlinedTextField(
                value = typedCommand,
                onValueChange = { typedCommand = it },
                enabled = canType,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Or type a command") },
                singleLine = true,
                shape = ButtonShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (typedCommand.isNotBlank()) {
                            onSubmitTextCommand(typedCommand)
                            typedCommand = ""
                        }
                    }
                ),
                trailingIcon = {
                    IconButton(
                        enabled = canType && typedCommand.isNotBlank(),
                        onClick = {
                            onSubmitTextCommand(typedCommand)
                            typedCommand = ""
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Submit command"
                        )
                    }
                }
            )

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                MicButton(
                    isListening = state.isListening,
                    enabled = micEnabled || state.isListening,
                    onClick = onToggleListening
                )
            }
        }
    }
}

private fun panelBodyKey(state: JarvisUiState): String {
    return listOf(
        state.phase.name,
        state.liveTranscription,
        state.assistantMessage,
        state.confirmationTitle.orEmpty(),
        state.contactChoices.joinToString { it.displayName }
    ).joinToString("|")
}

@Composable
private fun PanelBody(
    state: JarvisUiState,
    onConfirmSend: () -> Unit,
    onCancelSend: () -> Unit,
    onSelectContact: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val transcription = state.liveTranscription.ifBlank { state.finalTranscription }
        when (state.phase) {
            JarvisPhase.IDLE, JarvisPhase.LISTENING, JarvisPhase.TRANSCRIBING -> {
                Text(
                    text = transcription.ifBlank { "Tap the microphone and speak" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (transcription.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            JarvisPhase.THINKING,
            JarvisPhase.EXECUTING,
            JarvisPhase.SENDING,
            JarvisPhase.VERIFYING -> {
                if (transcription.isNotBlank()) {
                    Text(
                        text = transcription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = state.assistantMessage.ifBlank { "Working…" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            JarvisPhase.CONFIRMATION -> {
                if (state.contactChoices.isNotEmpty()) {
                    Text(
                        text = state.assistantMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    state.contactChoices.forEach { choice ->
                        FilledTonalButton(
                            onClick = { onSelectContact(choice.displayName) },
                            shape = ButtonShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(choice.displayName)
                        }
                    }
                    OutlinedButton(
                        onClick = onCancelSend,
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                } else {
                    Text(
                        text = state.confirmationTitle ?: "Send this message?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                    state.previewMessage?.let { msg ->
                        Text(
                            text = "Message:\n$msg",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelSend,
                            shape = ButtonShape,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = onConfirmSend,
                            shape = ButtonShape,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Send")
                        }
                    }
                }
            }

            JarvisPhase.COMPLETED -> {
                Text(
                    text = state.assistantMessage.ifBlank { "Done." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            JarvisPhase.ERROR -> {
                Text(
                    text = state.errorMessage ?: state.assistantMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
