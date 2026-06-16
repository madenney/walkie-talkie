package com.walkietalkie

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.walkietalkie.ui.screens.AmbientScreen
import com.walkietalkie.ui.screens.ChatScreen
import com.walkietalkie.ui.screens.FilesScreen
import com.walkietalkie.ui.screens.SettingsScreen
import com.walkietalkie.ui.viewmodel.ChatViewModel

/** Turn the configured ws:// URL into the http:// origin the file API lives on. */
private fun httpBase(wsUrl: String): String = wsUrl
    .replace("wss://", "https://")
    .replace("ws://", "http://")
    .removeSuffix("/ws")
    .trimEnd('/')

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Permissions handled — recording will check at point of use
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions up front
        permissionLauncher.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
        ))

        // The foreground service (and its notification) is started/stopped by the
        // ViewModel based on connection state — connected shows the notification,
        // disconnected removes it.

        enableEdgeToEdge()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    val chatViewModel: ChatViewModel = viewModel()

                    NavHost(navController = navController, startDestination = "chat") {
                        composable("chat") {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                },
                                onNavigateToAmbient = {
                                    navController.navigate("ambient")
                                },
                                onNavigateToFiles = {
                                    navController.navigate("files")
                                },
                            )
                        }
                        composable("files") {
                            val uiState by chatViewModel.uiState.collectAsState()
                            val base = remember(uiState.serverUrl) { httpBase(uiState.serverUrl) }
                            FilesScreen(
                                serverBaseUrl = base,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = chatViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable("ambient") {
                            AmbientScreen(
                                viewModel = chatViewModel,
                                onExit = {
                                    navController.popBackStack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // While the app is in the foreground, let it show over the lock screen so
        // waking the phone drops you straight back into the app — past security.
        setShowWhenLocked(true)
    }

    override fun onStop() {
        super.onStop()
        // Decide whether to keep that lock-screen bypass armed. If the screen is
        // still on, the user deliberately left the app (home / another app), so
        // disarm and let the next lock be fully secure. If the screen went off
        // (power button or timeout) while we were foreground, keep it armed so
        // waking returns straight to the app.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            setShowWhenLocked(false)
        }
    }
}
