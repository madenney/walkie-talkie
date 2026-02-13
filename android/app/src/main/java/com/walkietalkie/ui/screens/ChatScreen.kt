package com.walkietalkie.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.walkietalkie.audio.VadState
import com.walkietalkie.ui.components.MessageBubble
import com.walkietalkie.ui.components.RecordButton
import com.walkietalkie.ui.viewmodel.ChatPage
import com.walkietalkie.ui.viewmodel.ChatUiState
import com.walkietalkie.ui.viewmodel.ChatViewModel
import com.walkietalkie.ui.viewmodel.Workspace

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAmbient: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { uiState.pages.size })

    // Sync settled page to ViewModel
    LaunchedEffect(pagerState.settledPage) {
        viewModel.onPageChanged(pagerState.settledPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
    ) { pageIndex ->
        val page = uiState.pages[pageIndex]
        ChatPageContent(
            page = page,
            uiState = uiState,
            viewModel = viewModel,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToAmbient = onNavigateToAmbient,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPageContent(
    page: ChatPage,
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAmbient: () -> Unit,
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(page.messages.size) {
        if (page.messages.isNotEmpty()) {
            listState.animateScrollToItem(page.messages.size - 1)
        }
    }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendImage(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.workspaces.isNotEmpty()) {
                        WorkspaceSelector(
                            workspaces = uiState.workspaces,
                            currentWorkspace = page.currentWorkspace,
                            onSelect = { viewModel.selectWorkspace(it) },
                        )
                    } else {
                        Text("Walkie Talkie")
                    }
                },
                actions = {
                    // Ambient mode
                    IconButton(onClick = onNavigateToAmbient) {
                        Icon(Icons.Default.FiberManualRecord, contentDescription = "Ambient mode")
                    }

                    // Connection indicator
                    ConnectionIndicator(uiState.isConnected)

                    IconButton(onClick = {
                        if (uiState.isConnected) viewModel.disconnect()
                        else viewModel.connect()
                    }) {
                        Icon(
                            imageVector = if (uiState.isConnected) Icons.Default.LinkOff
                            else Icons.Default.Link,
                            contentDescription = if (uiState.isConnected) "Disconnect"
                            else "Connect"
                        )
                    }

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            BottomInputBar(
                textInput = textInput,
                onTextChange = { textInput = it },
                onSend = {
                    viewModel.sendText(textInput)
                    textInput = ""
                },
                isConnected = uiState.isConnected,
                isMuted = uiState.isMuted,
                listeningState = uiState.listeningState,
                isPlayingAudio = uiState.isPlayingAudio,
                isResponding = page.isResponding,
                onToggleMute = { viewModel.toggleMute() },
                onPickImage = { imagePicker.launch("image/*") },
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(page.messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }
    }
}

@Composable
private fun WorkspaceSelector(
    workspaces: List<Workspace>,
    currentWorkspace: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = currentWorkspace ?: "Select project",
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Switch workspace",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            workspaces.forEach { ws ->
                DropdownMenuItem(
                    text = { Text(ws.name) },
                    onClick = {
                        expanded = false
                        onSelect(ws.name)
                    },
                    leadingIcon = {
                        if (ws.name == currentWorkspace) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(isConnected: Boolean) {
    val color = if (isConnected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = if (isConnected) "Connected" else "Offline",
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun BottomInputBar(
    textInput: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isConnected: Boolean,
    isMuted: Boolean,
    listeningState: VadState,
    isPlayingAudio: Boolean,
    isResponding: Boolean,
    onToggleMute: () -> Unit,
    onPickImage: () -> Unit,
) {
    Surface(
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Status indicator
            if (isConnected && !isMuted) {
                ListeningIndicator(
                    listeningState = listeningState,
                    isPlayingAudio = isPlayingAudio,
                    isResponding = isResponding,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Image picker button
                IconButton(
                    onClick = onPickImage,
                    enabled = isConnected,
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Send image")
                }

                // Text input
                OutlinedTextField(
                    value = textInput,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message...") },
                    maxLines = 4,
                    enabled = isConnected,
                    trailingIcon = {
                        if (textInput.isNotBlank()) {
                            IconButton(onClick = onSend) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                )

                Spacer(Modifier.width(8.dp))

                // Mute/unmute toggle (interrupt button removed — voice interrupts automatically)
                RecordButton(
                    isMuted = isMuted,
                    isEnabled = isConnected,
                    listeningState = listeningState,
                    isPlayingAudio = isPlayingAudio,
                    onToggleMute = onToggleMute,
                )
            }
        }
    }
}

@Composable
private fun ListeningIndicator(
    listeningState: VadState,
    isPlayingAudio: Boolean,
    isResponding: Boolean,
) {
    val isSpeech = listeningState == VadState.SPEECH || listeningState == VadState.COOLDOWN
    val isListening = listeningState == VadState.LISTENING

    val statusText: String
    val statusColor: androidx.compose.ui.graphics.Color

    when {
        isSpeech -> {
            statusText = "Hearing you..."
            statusColor = MaterialTheme.colorScheme.error
        }
        isPlayingAudio -> {
            statusText = "Speaking..."
            statusColor = MaterialTheme.colorScheme.tertiary
        }
        isResponding -> {
            statusText = "Thinking..."
            statusColor = MaterialTheme.colorScheme.secondary
        }
        isListening -> {
            statusText = "Listening..."
            statusColor = MaterialTheme.colorScheme.primary
        }
        else -> return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSpeech) 0.5f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeech) 400 else 1000,
                easing = EaseInOut,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    val dotSize = if (isSpeech) 10.dp else 8.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSpeech) {
                    Modifier.background(statusColor.copy(alpha = 0.1f))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(statusColor.copy(alpha = dotAlpha))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = statusText,
            style = if (isSpeech) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
    }
}
