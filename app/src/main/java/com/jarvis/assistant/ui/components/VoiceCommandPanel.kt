package com.jarvis.assistant.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.state.JarvisPhase
import com.jarvis.assistant.state.JarvisUiState
import com.jarvis.assistant.ui.theme.ButtonShape
import com.jarvis.assistant.ui.theme.PanelShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared Material voice-command panel used by the in-app home screen and the
 * floating overlay over other apps. Keep visual design identical in both places.
 */
@Composable
fun VoiceCommandPanel(
    state: JarvisUiState,
    onToggleListening: () -> Unit,
    onConfirmSend: () -> Unit,
    onCancelSend: () -> Unit,
    onSelectContact: (String) -> Unit,
    onSubmitTextCommand: (String) -> Unit,
    micEnabled: Boolean,
    modifier: Modifier = Modifier,
    showDismissControls: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    onDismissDragActive: ((Boolean) -> Unit)? = null,
    compact: Boolean = false
) {
    var typedCommand by remember { mutableStateOf("") }
    val canType = state.phase in setOf(
        JarvisPhase.IDLE,
        JarvisPhase.COMPLETED,
        JarvisPhase.ERROR,
        JarvisPhase.CONFIRMATION
    ) && !state.isListening

    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dismissing by remember { mutableStateOf(false) }

    fun animateDismiss() {
        if (dismissing || onDismiss == null) return
        dismissing = true
        onDismissDragActive?.invoke(false)
        scope.launch {
            val target = with(density) { 420.dp.toPx() }
            while (dragOffsetPx < target) {
                dragOffsetPx += target / 10f
                delay(16)
            }
            onDismiss()
        }
    }

    fun cancelDrag() {
        dragOffsetPx = 0f
        onDismissDragActive?.invoke(false)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
            .alpha(1f - (dragOffsetPx / with(density) { 420.dp.toPx() }).coerceIn(0f, 0.85f))
            .animateContentSize()
            .then(
                if (showDismissControls && onDismiss != null) {
                    Modifier.pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        val dismissThreshold = with(density) { 96.dp.toPx() }
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var dragging = false
                            var totalDown = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    if (dragging) {
                                        if (dragOffsetPx > dismissThreshold) {
                                            animateDismiss()
                                        } else {
                                            cancelDrag()
                                        }
                                    }
                                    break
                                }
                                val delta = change.positionChange()
                                // Prefer vertical downward dismiss; leave taps/buttons alone.
                                if (!dragging) {
                                    totalDown += delta.y
                                    if (totalDown > touchSlop && abs(delta.y) >= abs(delta.x)) {
                                        dragging = true
                                        onDismissDragActive?.invoke(true)
                                    }
                                }
                                if (dragging) {
                                    change.consume()
                                    dragOffsetPx =
                                        (dragOffsetPx + delta.y).coerceAtLeast(0f)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
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
            if (showDismissControls) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(2.dp)
                        )
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
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
                    text = phaseLabel(state),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (showDismissControls && onDismiss != null) {
                    IconButton(
                        onClick = { animateDismiss() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss Jarvis panel",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

            if (!compact) {
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
            }

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

private fun phaseLabel(state: JarvisUiState): String {
    return when (state.phase) {
        JarvisPhase.LISTENING -> "Listening"
        JarvisPhase.THINKING -> "Thinking"
        JarvisPhase.EXECUTING, JarvisPhase.SENDING, JarvisPhase.VERIFYING -> "Working"
        JarvisPhase.COMPLETED -> "Completed"
        JarvisPhase.CONFIRMATION -> "Waiting for confirmation"
        JarvisPhase.ERROR -> "Error"
        JarvisPhase.TRANSCRIBING -> "Transcribing"
        JarvisPhase.IDLE -> if (state.isListening) "Listening" else "Voice command"
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
