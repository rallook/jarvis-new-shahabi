package com.jarvis.assistant.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.state.JarvisPhase
import com.jarvis.assistant.state.JarvisUiState
import com.jarvis.assistant.ui.components.JarvisIdentity
import com.jarvis.assistant.ui.components.VoiceCommandPanel
import com.jarvis.assistant.ui.theme.ButtonShape

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
    onRequestOverlayPermission: () -> Unit,
    showSetup: Boolean,
    needsOverlayPermission: Boolean
) {
    val context = LocalContext.current

    Column(
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

        if (showSetup || needsOverlayPermission) {
            SetupCard(
                needsMicrophone = state.needsMicrophone,
                needsAccessibility = state.needsAccessibility,
                needsOverlay = needsOverlayPermission,
                onOpenAccessibility = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onRequestOverlay = onRequestOverlayPermission,
                onRefresh = onRefreshSetup
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        VoiceCommandPanel(
            state = state,
            onToggleListening = onToggleListening,
            onConfirmSend = onConfirmSend,
            onCancelSend = onCancelSend,
            onSelectContact = onSelectContact,
            onSubmitTextCommand = onSubmitTextCommand,
            micEnabled = !state.needsMicrophone &&
                state.phase !in setOf(JarvisPhase.THINKING, JarvisPhase.SENDING, JarvisPhase.VERIFYING),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SetupCard(
    needsMicrophone: Boolean,
    needsAccessibility: Boolean,
    needsOverlay: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
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
            }
            if (needsOverlay) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Rounded.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Allow “Display over other apps” so the Jarvis panel can float above WhatsApp, YouTube, and Chrome. Jarvis will open the system permission screen — it cannot grant this automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onRequestOverlay,
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Allow display over other apps")
                }
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
