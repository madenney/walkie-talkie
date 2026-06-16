package com.walkietalkie.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.walkietalkie.ui.components.MessageBubble
import com.walkietalkie.ui.components.PushToTalkButton
import com.walkietalkie.ui.viewmodel.ChatPage
import com.walkietalkie.ui.viewmodel.ChatUiState
import com.walkietalkie.ui.viewmodel.ChatViewModel
import com.walkietalkie.ui.viewmodel.Role
import com.walkietalkie.ui.viewmodel.SessionStatus
import com.walkietalkie.ui.viewmodel.Workspace

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAmbient: () -> Unit = {},
    onNavigateToFiles: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    // Two levels: the sessions list (home) and a single conversation. The list is
    // the landing screen and the dashboard; tapping a project opens it, the back
    // arrow (or system back) returns. There's no "blank page" to get lost on.
    if (uiState.onHome || uiState.pages.isEmpty()) {
        HomeScreen(
            uiState = uiState,
            viewModel = viewModel,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToAmbient = onNavigateToAmbient,
            onNavigateToFiles = onNavigateToFiles,
        )
    } else {
        BackHandler { viewModel.goHome() }
        SessionScreen(
            uiState = uiState,
            viewModel = viewModel,
        )
    }
}

// --------------------------------------------------------------------- Home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAmbient: () -> Unit,
    onNavigateToFiles: () -> Unit,
) {
    val byName = remember(uiState.sessionStatuses) {
        uiState.sessionStatuses.associateBy { it.name }
    }
    // Live sessions float to the top in their own section ("needs you" first, then
    // running, then ready — alphabetical within). The rest are projects you can
    // open. A workspace is "open" when the server reports a live status for it.
    val open = remember(uiState.workspaces, byName) {
        uiState.workspaces
            .filter { byName.containsKey(it.name) }
            .sortedWith(
                compareByDescending<Workspace> { byName[it.name]?.blocked == true }
                    .thenByDescending { byName[it.name]?.responding == true }
                    .thenBy { it.name.lowercase() }
            )
    }
    val closed = remember(uiState.workspaces, byName) {
        uiState.workspaces
            .filter { !byName.containsKey(it.name) }
            .sortedBy { it.name.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                actions = {
                    ConnectionIndicator(
                        isConnected = uiState.isConnected,
                        onClick = {
                            if (uiState.isConnected) viewModel.disconnect()
                            else viewModel.connect()
                        },
                    )
                    IconButton(onClick = onNavigateToFiles) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse files")
                    }
                    IconButton(onClick = onNavigateToAmbient) {
                        Icon(Icons.Default.FiberManualRecord, contentDescription = "Ambient mode")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // One-line summary so the count is visible without reading every dot.
            if (uiState.liveSessionCount > 0) {
                val needs = uiState.needsYouCount
                Text(
                    text = buildString {
                        append("${uiState.liveSessionCount} running")
                        if (needs > 0) append(" · $needs need you")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (needs > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (uiState.workspaces.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (uiState.isConnected) "No projects configured."
                        else "Connecting…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (open.isNotEmpty()) {
                        item(key = "hdr-open") { SectionHeader("Currently open") }
                        items(open, key = { "open-${it.name}" }) { ws ->
                            SessionRow(
                                workspace = ws,
                                status = byName[ws.name],
                                onClick = { viewModel.openWorkspace(ws.name) },
                                onClose = { viewModel.closeWorkspace(ws.name) },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (closed.isNotEmpty()) {
                        item(key = "hdr-projects") {
                            SectionHeader(if (open.isNotEmpty()) "Projects" else "All projects")
                        }
                        items(closed, key = { "proj-${it.name}" }) { ws ->
                            SessionRow(
                                workspace = ws,
                                status = byName[ws.name],
                                onClick = { viewModel.openWorkspace(ws.name) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** One project in the home list: status dot, name, and what it's doing. Live
 * sessions (running on the server) read as bright; projects not yet opened are
 * dimmed. "Needs you" is called out in red. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SessionRow(
    workspace: Workspace,
    status: SessionStatus?,
    onClick: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val (dotColor, subtitle, subtitleColor) = when {
        status == null -> Triple(
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
            "Tap to open",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status.blocked -> Triple(
            StatusBlocked,
            "Needs you — approval",
            StatusBlocked,
        )
        status.responding -> Triple(
            StatusRunning,
            "Working…",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(
            StatusReady,
            "Ready",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(workspace.name, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        // Only live sessions can be closed (stops the agent; keeps history).
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Session

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SessionScreen(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
) {
    var textInput by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val swipeScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = uiState.activePageIndex.coerceIn(0, (uiState.pages.size - 1).coerceAtLeast(0)),
        pageCount = { uiState.pages.size },
    )

    // A settled swipe selects that session.
    LaunchedEffect(pagerState.settledPage) {
        viewModel.onPageChanged(pagerState.settledPage)
    }
    // Keep the pager in step when the active page is driven from the ViewModel.
    LaunchedEffect(uiState.activePageIndex) {
        val target = uiState.activePageIndex
        if (target in 0 until uiState.pages.size && pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }

    // GNOME-style workspace switcher: a row of dots that flashes up while you
    // slide between open sessions (and briefly after settling) so you can see at a
    // glance which one of N you've landed on. Pointless with a single session.
    var showSwitcher by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.isScrollInProgress, pagerState.currentPage) {
        if (uiState.pages.size <= 1) {
            showSwitcher = false
            return@LaunchedEffect
        }
        showSwitcher = true
        if (!pagerState.isScrollInProgress) {
            delay(900)
            showSwitcher = false
        }
    }

    val currentPage = uiState.pages.getOrNull(uiState.activePageIndex)
        ?: uiState.pages.first()
    val currentWs = currentPage.currentWorkspace
    val status = uiState.sessionStatuses.firstOrNull { it.name == currentWs }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.sendImage(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.goHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sessions")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(status)
                        Spacer(Modifier.width(8.dp))
                        Text(currentWs ?: "", style = MaterialTheme.typography.titleMedium)
                    }
                },
                actions = {
                    // Replay this project's last response as audio — e.g. to hear a
                    // reply that finished while you were on another project.
                    if (uiState.isConnected && currentPage.messages.any { it.role == Role.ASSISTANT }) {
                        IconButton(onClick = { viewModel.replayLastResponse() }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "Play last response")
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Close session") },
                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.closeWorkspace()
                                },
                            )
                        }
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
                hasWorkspace = currentWs != null,
                isRecording = uiState.isRecording,
                isPlayingAudio = uiState.isPlayingAudio,
                isResponding = currentPage.isResponding,
                onPttStart = { viewModel.startPushToTalk() },
                onPttEnd = { viewModel.stopPushToTalk() },
                onPickImage = { imagePicker.launch("image/*") },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .pageSwipeTakeover(pagerState, swipeScope),
                beyondViewportPageCount = 1,
                // We drive horizontal paging ourselves (see pageSwipeTakeover) so a
                // swipe still works even after a vertical scroll has begun; the
                // pager's own drag handler would axis-lock to that scroll.
                userScrollEnabled = false,
            ) { pageIndex ->
                TranscriptList(
                    page = uiState.pages[pageIndex],
                    isActive = pageIndex == pagerState.currentPage,
                )
            }

            // The transient workspace-switch indicator (uses the live page index so
            // the active dot moves as you cross, like the desktop overlay).
            WorkspaceSwitchOverlay(
                names = uiState.pages.mapNotNull { it.currentWorkspace },
                statuses = uiState.sessionStatuses.associateBy { it.name },
                activeIndex = pagerState.currentPage,
                visible = showSwitcher,
            )

            // Tool-permission prompt for the project on screen — pinned to it, so
            // swiping away hides it and swiping back shows it again.
            currentWs?.let { uiState.pendingApprovals[it] }?.let { pending ->
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
}

@Composable
private fun TranscriptList(page: ChatPage, isActive: Boolean) {
    val listState = rememberLazyListState()
    // Sit at the very bottom whenever this page is the one on screen — both as new
    // messages arrive and the moment you flip to it (its scrollback repaints from
    // the server on selection). The large scroll offset lands past the end of the
    // last message, so a long final reply shows its *bottom* rather than leaving
    // you parked at the top of it.
    LaunchedEffect(isActive, page.messages.size) {
        if (isActive && page.messages.isNotEmpty()) {
            listState.scrollToItem(page.messages.lastIndex, scrollOffset = 100_000)
        }
    }

    val isEmpty = page.messages.isEmpty()
    // Defer the empty-state by a short grace period: when you flip to an already-
    // open project we blank it and wait for the server to repaint its scrollback,
    // and showing "nothing going on yet" during that brief window flashes before
    // the chat appears. Only show it once a page has genuinely stayed empty.
    var showEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(isEmpty, isActive) {
        if (isEmpty && isActive) {
            delay(500)
            showEmpty = true
        } else {
            showEmpty = false
        }
    }

    if (isEmpty) {
        if (showEmpty) EmptyTranscript()
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(page.messages, key = { it.id }) { message ->
            MessageBubble(message = message)
        }
    }
}

/** Shown in a freshly opened (or resumed-but-empty) session before anything has
 * been said — a calm placeholder rather than a blank void. */
@Composable
private fun EmptyTranscript() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Forum,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Nothing going on yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Hold the mic to talk, or type below to kick things off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Drive the pager's horizontal paging from the *initial* pointer pass so a
 * sideways swipe still registers even if the gesture began as a vertical scroll.
 *
 * Compose's built-in scroll/pager gestures axis-lock: whichever orientation wins
 * the touch slop owns the whole gesture, so a slight up/down before pulling left
 * means the pager never gets it. Here we watch the raw pointer stream, and the
 * moment horizontal travel dominates we take over — consuming the events (which
 * stops the vertical list) and scrolling the pager 1:1, then settling to the
 * nearest page on release. Vertical-dominant gestures are never consumed, so the
 * transcript still scrolls normally. (The pager has userScrollEnabled = false so
 * this is the only thing paging it — no competing handler.)
 */
private fun Modifier.pageSwipeTakeover(
    pagerState: PagerState,
    scope: CoroutineScope,
): Modifier = pointerInput(pagerState) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var accumX = 0f
        var accumY = 0f
        var taken = false
        var deltas: Channel<Float>? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            val moved = change.positionChange()
            if (!taken) {
                accumX += moved.x
                accumY += moved.y
                // Clearly-horizontal and past slop → this is a page swipe. Works
                // even if a little vertical scrolling already happened first.
                if (pagerState.pageCount > 1 &&
                    abs(accumX) > slop && abs(accumX) > abs(accumY)
                ) {
                    taken = true
                    val ch = Channel<Float>(Channel.UNLIMITED)
                    deltas = ch
                    scope.launch {
                        pagerState.scroll {
                            for (d in ch) scrollBy(-d)
                        }
                        // Finger lifted (channel closed) — settle to the nearest
                        // page, flipping if dragged more than ~a fifth of the way.
                        val offset = pagerState.currentPageOffsetFraction
                        val target = (pagerState.currentPage + when {
                            offset > 0.2f -> 1
                            offset < -0.2f -> -1
                            else -> 0
                        }).coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                        pagerState.animateScrollToPage(target)
                    }
                }
            }
            if (taken) {
                change.consume()
                deltas?.trySend(moved.x)
            }
        }
        deltas?.close()
    }
}

/** GNOME-style workspace switcher. A centered pill showing the current session's
 * name above a row of dots — one per open session — with the active one stretched
 * into a bright pill. Fades in on a swipe and out shortly after, just long enough
 * to orient you among the "spinning plates." */
@Composable
private fun WorkspaceSwitchOverlay(
    names: List<String>,
    statuses: Map<String, SessionStatus>,
    activeIndex: Int,
    visible: Boolean,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(350)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Color.Black.copy(alpha = 0.78f),
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = names.getOrNull(activeIndex) ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        names.forEachIndexed { i, name ->
                            val active = i == activeIndex
                            // Each dot carries its session's live status color
                            // (yellow running / green ready / red blocked); the
                            // active one stretches into a pill and shows full
                            // strength, the rest are dimmed.
                            val statusColor = sessionColor(statuses[name])
                                ?: Color.White.copy(alpha = 0.35f)
                            val width by animateDpAsState(
                                targetValue = if (active) 28.dp else 10.dp,
                                animationSpec = tween(220),
                                label = "switcherDot",
                            )
                            Box(
                                Modifier
                                    .height(10.dp)
                                    .width(width)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) statusColor
                                        else statusColor.copy(alpha = 0.5f)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ shared

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

// Shelf-style session status colors: yellow = running (a turn in flight), green =
// ready (live but idle), red = blocked (waiting on an approval from you).
private val StatusRunning = Color(0xFFF1C40F)
private val StatusReady = Color(0xFF2ECC71)
private val StatusBlocked = Color(0xFFE74C3C)

/** The status color for a session, or null when it isn't a live session (so the
 * caller can pick its own "not started" tint). */
private fun sessionColor(status: SessionStatus?): Color? = when {
    status == null -> null
    status.blocked -> StatusBlocked
    status.responding -> StatusRunning
    else -> StatusReady
}

/** A small colored dot for a session's live status (see [sessionColor]);
 * invisible when it isn't a live session. */
@Composable
private fun StatusDot(status: SessionStatus?) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(sessionColor(status) ?: Color.Transparent)
    )
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
    hasWorkspace: Boolean,
    isRecording: Boolean,
    isPlayingAudio: Boolean,
    isResponding: Boolean,
    onPttStart: () -> Unit,
    onPttEnd: () -> Unit,
    onPickImage: () -> Unit,
) {
    val canInput = isConnected && hasWorkspace

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
                    enabled = canInput,
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Send image")
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    maxLines = 4,
                    enabled = canInput,
                )

                Spacer(Modifier.width(8.dp))

                // Send button — blank message pings the server (health check).
                FilledIconButton(
                    onClick = onSend,
                    enabled = canInput,
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
                    isEnabled = canInput,
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
