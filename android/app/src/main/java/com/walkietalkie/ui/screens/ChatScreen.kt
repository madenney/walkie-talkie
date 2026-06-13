package com.walkietalkie.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.walkietalkie.ui.components.MessageBubble
import com.walkietalkie.ui.components.PushToTalkButton
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

    Box(Modifier.fillMaxSize()) {
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

        // Tool-permission prompt overlays everything until answered.
        uiState.pendingApproval?.let { pending ->
            ApprovalOverlay(
                pending = pending,
                isRecording = uiState.isRecording,
                onApprove = { viewModel.respondToApproval(true) },
                onDeny = { viewModel.respondToApproval(false) },
                onSpeakStart = { viewModel.startPushToTalk() },
                onSpeakEnd = { viewModel.stopPushToTalk() },
            )
        }
    }
}

@Composable
private fun ApprovalOverlay(
    pending: com.walkietalkie.ui.viewmodel.PendingApproval,
    isRecording: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onSpeakStart: () -> Unit,
    onSpeakEnd: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        // Cap the dialog so it never runs off-screen; the detail area scrolls instead.
        val maxDialogHeight = maxHeight * 0.85f
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxDialogHeight)
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = pending.summary,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (pending.detail.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        Text(
                            text = pending.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Text("Deny", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(
                        onClick = onApprove,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Text("Approve", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Hands-free: hold and say "yes" or "no".
                PushToTalkButton(
                    isRecording = isRecording,
                    isEnabled = true,
                    onPressStart = onSpeakStart,
                    onPressEnd = onSpeakEnd,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isRecording) "Listening — say yes or no" else "or hold to say yes / no",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    val keyboardController = LocalSoftwareKeyboardController.current

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

                    // Connection indicator doubles as the connect/disconnect toggle.
                    ConnectionIndicator(
                        isConnected = uiState.isConnected,
                        onClick = {
                            if (uiState.isConnected) viewModel.disconnect()
                            else viewModel.connect()
                        },
                    )

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
                    keyboardController?.hide()
                },
                isConnected = uiState.isConnected,
                isRecording = uiState.isRecording,
                isPlayingAudio = uiState.isPlayingAudio,
                isResponding = page.isResponding,
                onPttStart = { viewModel.startPushToTalk() },
                onPttEnd = { viewModel.stopPushToTalk() },
                onPickImage = { imagePicker.launch("image/*") },
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // `padding` already includes the bottom bar's height, which grows
                // with the keyboard — no separate imePadding() needed here.
                .padding(padding),
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
private fun ConnectionIndicator(isConnected: Boolean, onClick: () -> Unit) {
    val color = if (isConnected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error

    Surface(
        onClick = onClick,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomInputBar(
    textInput: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isConnected: Boolean,
    isRecording: Boolean,
    isPlayingAudio: Boolean,
    isResponding: Boolean,
    onPttStart: () -> Unit,
    onPttEnd: () -> Unit,
    onPickImage: () -> Unit,
) {
    Surface(
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Sit above the keyboard when it's open, above the nav bar when it's not.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            // Status line (recording / speaking / thinking)
            StatusIndicator(
                isRecording = isRecording,
                isPlayingAudio = isPlayingAudio,
                isResponding = isResponding,
            )

            // Compact text row — typing is secondary to voice now.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Image picker button
                IconButton(
                    onClick = onPickImage,
                    enabled = isConnected,
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Send image")
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    maxLines = 4,
                    enabled = isConnected,
                )

                Spacer(Modifier.width(8.dp))

                // Send button — blank message pings the server (health check).
                FilledIconButton(
                    onClick = onSend,
                    enabled = isConnected,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }

            // Primary control: big hold-to-talk button. Hide it while the keyboard
            // is open so it doesn't crowd the typing experience.
            if (!WindowInsets.isImeVisible) {
                PushToTalkButton(
                    isRecording = isRecording,
                    isEnabled = isConnected,
                    onPressStart = onPttStart,
                    onPressEnd = onPttEnd,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    isRecording: Boolean,
    isPlayingAudio: Boolean,
    isResponding: Boolean,
) {
    val isSpeech = isRecording

    val statusText: String
    val statusColor: Color

    when {
        isRecording -> {
            statusText = "Listening..."
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
