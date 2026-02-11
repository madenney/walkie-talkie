package com.walkietalkie.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.walkietalkie.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var serverUrl by remember { mutableStateOf(uiState.serverUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Server Connection",
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("ws://your-server-ip:8765/ws") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = {
                    viewModel.updateServerUrl(serverUrl)
                    viewModel.disconnect()
                    viewModel.connect(serverUrl)
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save & Connect")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = if (uiState.isConnected) "Connected" else "Disconnected",
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.isConnected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}
