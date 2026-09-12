package com.jarvis.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.state.JarvisViewModel
import com.jarvis.assistant.ui.MainScreen
import com.jarvis.assistant.ui.SettingsScreen
import com.jarvis.assistant.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onMicrophonePermissionResult(micGranted)
        viewModel.refreshSetupFlags()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNeededPermissions()

        setContent {
            JarvisTheme {
                val navController = rememberNavController()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    viewModel.refreshSetupFlags()
                }

                NavHost(
                    navController = navController,
                    startDestination = "main"
                ) {
                    composable("main") {
                        MainScreen(
                            state = state,
                            onToggleListening = viewModel::toggleListening,
                            onConfirmSend = viewModel::confirmSend,
                            onCancelSend = viewModel::cancelSend,
                            onSelectContact = viewModel::selectContact,
                            onSubmitTextCommand = viewModel::submitTextCommand,
                            onOpenSettings = { navController.navigate("settings") },
                            onRefreshSetup = viewModel::refreshSetupFlags,
                            showSetup = state.needsMicrophone || state.needsAccessibility
                        )
                    }
                    composable("settings") {
                        LaunchedEffect(Unit) {
                            viewModel.refreshSettingsState()
                        }
                        SettingsScreen(
                            state = settingsState,
                            onBack = { navController.popBackStack() },
                            onSaveApiKey = viewModel::saveOpenAiApiKey,
                            onClearApiKey = viewModel::clearOpenAiApiKey,
                            onUpdateModel = viewModel::updateOpenAiModel,
                            onUpdateBaseUrl = viewModel::updateOpenAiBaseUrl,
                            onToggleSecureBackend = viewModel::setUseSecureBackend,
                            onUpdateBackendUrl = viewModel::updateSecureBackendUrl,
                            onToggleHeuristic = viewModel::setHeuristicFallback,
                            onToggleTts = viewModel::setTtsEnabled,
                            onRefreshStatus = viewModel::refreshSettingsState,
                            onRequestMicrophone = { requestNeededPermissions() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSetupFlags()
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onMicrophonePermissionResult(micGranted)
    }

    private fun requestNeededPermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.onMicrophonePermissionResult(true)
        }
    }
}
