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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Assistant
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.settings.JarvisSettings
import com.jarvis.assistant.ui.theme.ButtonShape

data class SettingsScreenState(
    val settings: JarvisSettings = JarvisSettings(),
    val accessibilityEnabled: Boolean = false,
    val microphoneGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val assistantRoleAvailable: Boolean = false,
    val assistantRoleActive: Boolean = false,
    val savedMessage: String? = null
)

@Composable
fun SettingsScreen(
    state: SettingsScreenState,
    onBack: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateBaseUrl: (String) -> Unit,
    onToggleSecureBackend: (Boolean) -> Unit,
    onUpdateBackendUrl: (String) -> Unit,
    onToggleHeuristic: (Boolean) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onToggleHandsFree: (Boolean) -> Unit,
    onRefreshStatus: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAssistantRole: () -> Unit
) {
    val context = LocalContext.current
    var apiKeyDraft by remember { mutableStateOf("") }
    var modelDraft by remember(state.settings.openAiModel) { mutableStateOf(state.settings.openAiModel) }
    var baseUrlDraft by remember(state.settings.openAiBaseUrl) {
        mutableStateOf(state.settings.openAiBaseUrl)
    }
    var backendUrlDraft by remember(state.settings.secureBackendUrl) {
        mutableStateOf(state.settings.secureBackendUrl)
    }
    var showApiKey by remember { mutableStateOf(false) }
    var keyDirty by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefreshStatus()
        apiKeyDraft = ""
        keyDirty = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "Configure Jarvis for voice commands, AI understanding, WhatsApp / YouTube automation, and the floating panel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingsSection(title = "Permissions & services") {
                StatusRow(
                    icon = Icons.Rounded.Mic,
                    title = "Microphone",
                    subtitle = if (state.microphoneGranted) {
                        "Granted — voice commands available"
                    } else {
                        "Required for speech recognition"
                    },
                    ok = state.microphoneGranted
                )
                if (!state.microphoneGranted) {
                    Button(
                        onClick = onRequestMicrophone,
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Request microphone permission")
                    }
                }

                StatusRow(
                    icon = Icons.Rounded.AccessibilityNew,
                    title = "Accessibility service",
                    subtitle = if (state.accessibilityEnabled) {
                        "Enabled — WhatsApp & YouTube automation ready"
                    } else {
                        "Must be enabled manually in Android Settings"
                    },
                    ok = state.accessibilityEnabled
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Accessibility settings")
                }

                StatusRow(
                    icon = Icons.Rounded.Layers,
                    title = "Display over other apps",
                    subtitle = if (state.overlayGranted) {
                        "Granted — floating Jarvis panel can appear above other apps"
                    } else {
                        "Required for the floating voice panel over WhatsApp, YouTube, Chrome, etc."
                    },
                    ok = state.overlayGranted
                )
                if (!state.overlayGranted) {
                    Button(
                        onClick = onRequestOverlayPermission,
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Allow display over other apps")
                    }
                }

                StatusRow(
                    icon = Icons.Rounded.Hearing,
                    title = "Assistant invocations",
                    subtitle = if (state.settings.handsFreeEnabled) {
                        if (state.microphoneGranted) {
                            "On — system assist gesture / default assistant can open Jarvis"
                        } else {
                            "On — needs microphone permission"
                        }
                    } else {
                        "Off — only the microphone button starts Jarvis"
                    },
                    ok = !state.settings.handsFreeEnabled || state.microphoneGranted
                )

                if (state.assistantRoleAvailable) {
                    StatusRow(
                        icon = Icons.Rounded.Assistant,
                        title = "Default assistant",
                        subtitle = if (state.assistantRoleActive) {
                            "Jarvis is the default assistant"
                        } else {
                            "Optional — set Jarvis as the system assistant"
                        },
                        ok = state.assistantRoleActive
                    )
                    if (!state.assistantRoleActive) {
                        OutlinedButton(
                            onClick = onRequestAssistantRole,
                            shape = ButtonShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Set Jarvis as default assistant")
                        }
                    }
                }

                OutlinedButton(
                    onClick = onRefreshStatus,
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh status")
                }
            }

            SettingsSection(title = "Voice Assistant") {
                SettingSwitchRow(
                    icon = Icons.Rounded.Hearing,
                    title = "Use Jarvis as your Android voice assistant",
                    subtitle = if (state.settings.handsFreeEnabled) {
                        "Allow system assistant / assist-gesture invocations to start Jarvis. Does not add a custom “Hey Jarvis” hotword."
                    } else {
                        "System assistant invocations are off. The microphone button continues to work normally."
                    },
                    checked = state.settings.handsFreeEnabled,
                    onCheckedChange = onToggleHandsFree
                )
                Text(
                    text = "Manual microphone — always available. Set Jarvis as default assistant above when you want the system assist gesture.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsSection(title = "OpenAI") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "API key",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Stored on device (encrypted). Never logged.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Current: ${state.settings.maskedApiKey()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.settings.hasOpenAiKey) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )

                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = {
                        apiKeyDraft = it
                        keyDirty = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI API key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    shape = ButtonShape,
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (showApiKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    colors = fieldColors()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveApiKey(apiKeyDraft)
                            apiKeyDraft = ""
                            keyDirty = false
                        },
                        enabled = keyDirty && apiKeyDraft.isNotBlank(),
                        shape = ButtonShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save key")
                    }
                    OutlinedButton(
                        onClick = {
                            onClearApiKey()
                            apiKeyDraft = ""
                            keyDirty = false
                        },
                        enabled = state.settings.openAiApiKey.isNotBlank(),
                        shape = ButtonShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                }

                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model") },
                    placeholder = { Text(JarvisSettings.DEFAULT_MODEL) },
                    singleLine = true,
                    shape = ButtonShape,
                    colors = fieldColors()
                )
                Button(
                    onClick = { onUpdateModel(modelDraft) },
                    enabled = modelDraft.trim() != state.settings.openAiModel,
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save model")
                }

                OutlinedTextField(
                    value = baseUrlDraft,
                    onValueChange = { baseUrlDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API base URL") },
                    placeholder = { Text(JarvisSettings.DEFAULT_BASE_URL) },
                    singleLine = true,
                    shape = ButtonShape,
                    colors = fieldColors()
                )
                Button(
                    onClick = { onUpdateBaseUrl(baseUrlDraft) },
                    enabled = baseUrlDraft.trim() != state.settings.openAiBaseUrl,
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save base URL")
                }

                Text(
                    text = "Leave base URL as the OpenAI default unless you use a compatible proxy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsSection(title = "Secure backend (optional)") {
                SettingSwitchRow(
                    icon = Icons.Rounded.Security,
                    title = "Use secure backend",
                    subtitle = "Route AI requests through your server instead of calling OpenAI from the device",
                    checked = state.settings.useSecureBackend,
                    onCheckedChange = onToggleSecureBackend
                )
                OutlinedTextField(
                    value = backendUrlDraft,
                    onValueChange = { backendUrlDraft = it },
                    enabled = state.settings.useSecureBackend,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backend URL") },
                    placeholder = { Text("https://your-api.example.com/jarvis/brain") },
                    singleLine = true,
                    shape = ButtonShape,
                    colors = fieldColors()
                )
                Button(
                    onClick = { onUpdateBackendUrl(backendUrlDraft) },
                    enabled = state.settings.useSecureBackend &&
                        backendUrlDraft.trim() != state.settings.secureBackendUrl,
                    shape = ButtonShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save backend URL")
                }
            }

            SettingsSection(title = "Assistant behavior") {
                SettingSwitchRow(
                    icon = Icons.Rounded.Psychology,
                    title = "Heuristic fallback",
                    subtitle = "If OpenAI fails or no key is set, parse WhatsApp / YouTube phrases locally",
                    checked = state.settings.allowHeuristicFallback,
                    onCheckedChange = onToggleHeuristic
                )
                SettingSwitchRow(
                    icon = Icons.Rounded.RecordVoiceOver,
                    title = "Spoken responses",
                    subtitle = "Short TTS acknowledgements such as “Message sent.”",
                    checked = state.settings.ttsEnabled,
                    onCheckedChange = onToggleTts
                )
                SettingSwitchRow(
                    icon = Icons.Rounded.CheckCircle,
                    title = "Confirm before send",
                    subtitle = "Always required in this version — WhatsApp messages are never auto-sent",
                    checked = true,
                    onCheckedChange = {},
                    enabled = false
                )
            }

            state.savedMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        content()
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    ok: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline
)
