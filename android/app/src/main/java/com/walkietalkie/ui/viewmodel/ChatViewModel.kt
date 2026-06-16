package com.walkietalkie.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.walkietalkie.BuildConfig
import com.walkietalkie.audio.AudioCapture
import com.walkietalkie.audio.AudioPlayer
import com.walkietalkie.audio.VadState
import com.walkietalkie.audio.VoiceActivityDetector
import com.walkietalkie.camera.ImageCapture
import com.walkietalkie.data.websocket.*
import com.walkietalkie.service.WalkieTalkieService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val Context.settingsStore by preferencesDataStore("settings")
private val KEY_SERVER_URL = stringPreferencesKey("server_url")
private val KEY_MUTED = booleanPreferencesKey("muted")
private val KEY_PAUSE_MEDIA = booleanPreferencesKey("pause_media")
// The "spinning plates": which projects are open, so reopening the app restores
// them (the server keeps each one running). JSON array of workspace names.
private val KEY_PAGES = stringPreferencesKey("pages")

private const val TAG = "ChatViewModel"

data class ChatMessage(
    val id: String = System.nanoTime().toString(),
    val role: Role,
    val text: String,
    val isStreaming: Boolean = false,
    val toolName: String? = null,
    val toolOutput: String? = null,
    val imageUri: Uri? = null,
)

enum class Role { USER, ASSISTANT, TOOL, SYSTEM }

data class Workspace(
    val name: String,
    val path: String,
)

/** Live status of one Claude session on the server, for the dashboard. */
data class SessionStatus(
    val name: String,
    val responding: Boolean = false,
    val blocked: Boolean = false,
)

data class ChatPage(
    val id: String = System.nanoTime().toString(),
    val currentWorkspace: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isResponding: Boolean = false,
)

/** A tool the server is asking us to approve before it runs (e.g. a shell command). */
data class PendingApproval(
    val id: String,
    val toolName: String,
    val summary: String,
    val detail: String,
    val workspace: String = "",
)

data class ChatUiState(
    // The open (live) sessions, one per page, swipeable once you're inside one.
    // Empty until you open a project from the home list.
    val pages: List<ChatPage> = emptyList(),
    val activePageIndex: Int = 0,
    // True when the sessions list (home) is on screen rather than a conversation.
    // Home is the landing screen and the live dashboard; tapping a project opens
    // it into a session, the back arrow returns here.
    val onHome: Boolean = true,
    val isConnected: Boolean = false,
    val isMuted: Boolean = false,
    // True while the user is holding the push-to-talk button (main screen).
    val isRecording: Boolean = false,
    val listeningState: VadState = VadState.IDLE,
    val isPlayingAudio: Boolean = false,
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val workspaces: List<Workspace> = emptyList(),
    val pauseMediaDuringTts: Boolean = false,
    // Pending tool approvals, keyed by the workspace they belong to — so an
    // approval stays pinned to its own project's page instead of following you
    // when you swipe to another. The overlay shows the active page's entry.
    val pendingApprovals: Map<String, PendingApproval> = emptyMap(),
    // Live dashboard of every Claude session on the server (the "spinning
    // plates"), pushed by the server whenever any session's status changes.
    val sessionStatuses: List<SessionStatus> = emptyList(),
    // All live sessions on the server (the notification badge number).
    val liveSessionCount: Int = 0,
    // How many sessions are blocked waiting on an approval from you.
    val needsYouCount: Int = 0,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val wsClient = WsClient(viewModelScope)
    val audioCapture = AudioCapture(application)
    val audioPlayer = AudioPlayer(application)

    private lateinit var vad: VoiceActivityDetector
    // Track whether we've sent AudioStart (to pair with AudioEnd)
    private var audioSessionActive = false

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    // Currently streaming assistant message ID (for the active page)
    private var streamingMessageId: String? = null

    // True once we've told the user the server is unavailable, so the silent
    // reconnect loop doesn't spam the chat. Reset on a successful connect.
    private var unavailableAnnounced = false

    // Set when the user (or a settings change) deliberately disconnects, so the
    // resulting Disconnected event isn't mislabelled as "server unavailable".
    private var intentionalDisconnect = false

    // Timestamp (ms) of an outstanding user-initiated ping, for RTT reporting.
    // 0 means no ping is in flight.
    private var pingSentAt = 0L

    // Phone-side auto-deny timers for outstanding approval prompts, keyed by the
    // workspace each belongs to (approvals are per-project now).
    private val approvalTimeoutJobs = mutableMapOf<String, Job>()

    init {
        audioPlayer.initialize()

        vad = VoiceActivityDetector(
            audioCapture = audioCapture,
            // VAD callbacks fire on Dispatchers.IO (audio thread).
            // ExoPlayer and UI state must be touched on Main, so dispatch.
            onSpeechStart = { viewModelScope.launch { onVadSpeechStart() } },
            onAudioChunk = { chunk -> onVadAudioChunk(chunk) },
            onSpeechEnd = { viewModelScope.launch { onVadSpeechEnd() } },
        )

        // Load saved settings, then auto-connect
        viewModelScope.launch {
            getApplication<Application>().settingsStore.data.first().let { prefs ->
                val saved = prefs[KEY_SERVER_URL]
                if (!saved.isNullOrBlank()) {
                    _uiState.update { it.copy(serverUrl = saved) }
                }
                val muted = prefs[KEY_MUTED] ?: false
                _uiState.update { it.copy(isMuted = muted) }
                val pauseMedia = prefs[KEY_PAUSE_MEDIA] ?: false
                _uiState.update { it.copy(pauseMediaDuringTts = pauseMedia) }
                audioPlayer.setPauseOtherApps(pauseMedia)

                // Restore the spinning plates (the set of open sessions) from last
                // run, so their live dots show on the home list right away. We land
                // on home, not inside a session — the list is the orienting screen.
                // Messages are left empty; a page repaints when you open it.
                val savedPages = prefs[KEY_PAGES]
                if (!savedPages.isNullOrBlank()) {
                    val names = runCatching {
                        WsJson.decodeFromString<List<String>>(savedPages)
                    }.getOrDefault(emptyList())
                    if (names.isNotEmpty()) {
                        val restored = names.map { ChatPage(currentWorkspace = it) }
                        _uiState.update { it.copy(pages = restored, onHome = true) }
                    }
                }
            }
            if (isServerUrlConfigured()) {
                addSystemMessage("Starting up...")
                connect()
            } else {
                addSystemMessage("Set your server URL in Settings to get started.")
            }
        }

        viewModelScope.launch {
            wsClient.isConnected.collect { connected ->
                val wasConnected = _uiState.value.isConnected
                _uiState.update { it.copy(isConnected = connected) }
                if (connected) {
                    unavailableAnnounced = false
                    // Connected — show the notification (the badge means "connected").
                    startConnectionService()
                    addSystemMessage("Connected")
                    // If we already had a workspace selected (a reconnect after a
                    // drop/server-restart), re-select it so the server repaints its
                    // scrollback + any turn that kept running while we were gone.
                    // First-ever connect has no workspace yet — WorkspaceList will
                    // auto-select the default instead.
                    resyncActiveWorkspace()
                    // No auto-listen: the main screen is push-to-talk, so the mic
                    // only opens while the user holds the button. Ambient mode
                    // handles hands-free VAD listening on its own.
                } else {
                    // Not connected — stop any capture and tear down the notification.
                    if (wasConnected) {
                        stopPushToTalk()
                        stopListening()
                    }
                    // Drop the live dashboard + any pending approvals: with no
                    // socket we can't know the server's state, so showing the
                    // last-known count/dots/prompt would be misleading. (FCM, later,
                    // restores awareness while away.)
                    approvalTimeoutJobs.values.forEach { it.cancel() }
                    approvalTimeoutJobs.clear()
                    _uiState.update {
                        it.copy(
                            sessionStatuses = emptyList(),
                            liveSessionCount = 0,
                            needsYouCount = 0,
                            pendingApprovals = emptyMap(),
                        )
                    }
                    stopConnectionService()
                }
            }
        }

        viewModelScope.launch {
            wsClient.events.collect { event ->
                handleWsEvent(event)
            }
        }

        // Observe VAD state for UI
        viewModelScope.launch {
            vad.state.collect { vadState ->
                _uiState.update { it.copy(listeningState = vadState) }
            }
        }

        viewModelScope.launch {
            var unsuppressJob: Job? = null
            audioPlayer.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlayingAudio = playing) }
                unsuppressJob?.cancel()
                if (playing) {
                    // Suppress VAD while TTS is playing to prevent echo detection
                    vad.suppressed = true
                } else {
                    // Delay before unsuppressing to avoid catching playback tail
                    unsuppressJob = viewModelScope.launch {
                        delay(500)
                        vad.suppressed = false
                    }
                }
            }
        }

        // Update foreground service notification with current status
        viewModelScope.launch {
            uiState.collect { state ->
                val activePage = state.pages.getOrNull(state.activePageIndex)
                val isSpeech = state.listeningState == VadState.SPEECH ||
                        state.listeningState == VadState.COOLDOWN

                val status = when {
                    !state.isConnected -> "Disconnected"
                    state.isRecording -> "Listening..."          // push-to-talk held
                    state.isPlayingAudio -> "Speaking"
                    activePage?.isResponding == true -> "Thinking..."
                    state.isMuted -> "Muted"                     // ambient, muted
                    isSpeech -> "Hearing you..."                 // ambient VAD
                    state.listeningState == VadState.LISTENING -> "Listening"
                    else -> "Connected"
                }
                // The status-bar icon shows the live session count; the shade shows
                // count + how many need an approval, with connection state as detail.
                WalkieTalkieService.instance?.updateNotification(
                    status = status,
                    sessionCount = state.liveSessionCount,
                    needsYou = state.needsYouCount,
                )
            }
        }
    }

    fun connect(url: String? = null) {
        val serverUrl = url ?: _uiState.value.serverUrl
        _uiState.update { it.copy(serverUrl = serverUrl) }
        if (!isServerUrlConfigured(serverUrl)) {
            addSystemMessage("Set your server URL in Settings to get started.")
            return
        }
        wsClient.connect(serverUrl)
    }

    private fun isServerUrlConfigured(url: String? = null): Boolean {
        val u = url ?: _uiState.value.serverUrl
        return u.isNotBlank() && !u.contains("your-server-ip")
    }

    fun disconnect() {
        intentionalDisconnect = true
        stopListening()
        wsClient.disconnect()
        _uiState.update { it.copy(isConnected = false) }
    }

    fun toggleMute() {
        val nowMuted = !_uiState.value.isMuted
        _uiState.update { it.copy(isMuted = nowMuted) }

        // Persist preference
        viewModelScope.launch {
            getApplication<Application>().settingsStore.edit { prefs ->
                prefs[KEY_MUTED] = nowMuted
            }
        }

        if (nowMuted) {
            stopListening()
        } else if (_uiState.value.isConnected) {
            startListening()
        }
    }

    fun togglePauseMedia() {
        val nowPaused = !_uiState.value.pauseMediaDuringTts
        _uiState.update { it.copy(pauseMediaDuringTts = nowPaused) }
        audioPlayer.setPauseOtherApps(nowPaused)

        // Persist preference
        viewModelScope.launch {
            getApplication<Application>().settingsStore.edit { prefs ->
                prefs[KEY_PAUSE_MEDIA] = nowPaused
            }
        }
    }

    private fun startListening() {
        vad.startListening(viewModelScope)
    }

    private fun stopListening() {
        val wasInSpeech = audioSessionActive
        vad.stopListening()
        if (wasInSpeech) {
            wsClient.sendJson(AudioEndMsg())
            audioSessionActive = false
        }
    }

    private fun onVadSpeechStart() {
        // If TTS is playing or server is responding, interrupt first
        val state = _uiState.value
        val responding = state.pages.getOrNull(state.activePageIndex)?.isResponding == true
        if (state.isPlayingAudio || responding) {
            interrupt(sendInterruptMsg = true)
        }

        wsClient.sendJson(AudioStartMsg())
        audioSessionActive = true
    }

    private fun onVadAudioChunk(chunk: ByteArray) {
        wsClient.sendMicAudio(chunk)
    }

    private fun onVadSpeechEnd() {
        if (audioSessionActive) {
            wsClient.sendJson(AudioEndMsg())
            audioSessionActive = false
        }
    }

    // ---- Push-to-talk (main screen) ----
    // The mic is closed by default. Holding the button opens it: press → stream
    // every chunk, release → end the utterance. No VAD, no thresholds — the user
    // decides exactly when they're talking, like a real walkie-talkie.

    fun startPushToTalk() {
        if (!_uiState.value.isConnected || _uiState.value.isRecording) return

        // Barge-in: pressing to talk while Claude is speaking/thinking interrupts it.
        val state = _uiState.value
        val responding = state.pages.getOrNull(state.activePageIndex)?.isResponding == true
        if (state.isPlayingAudio || responding) {
            interrupt(sendInterruptMsg = true)
        }

        wsClient.sendJson(AudioStartMsg())
        audioSessionActive = true
        _uiState.update { it.copy(isRecording = true) }
        audioCapture.start(viewModelScope) { pcmBytes, _ ->
            wsClient.sendMicAudio(pcmBytes)
        }
    }

    fun stopPushToTalk() {
        if (!_uiState.value.isRecording) return
        audioCapture.stop()
        if (audioSessionActive) {
            wsClient.sendJson(AudioEndMsg())
            audioSessionActive = false
        }
        _uiState.update { it.copy(isRecording = false) }
    }

    // ---- Ambient mode (hands-free VAD) ----
    // Ambient is the one place that still uses voice-activity detection. Start it
    // on entry and stop on exit so the mic stays closed on the main screen.

    fun onEnterAmbient() {
        if (_uiState.value.isConnected && !_uiState.value.isMuted) startListening()
    }

    fun onExitAmbient() {
        stopListening()
    }

    fun sendText(text: String) {
        if (text.isBlank()) {
            // Empty send = a "you there?" health check rather than a no-op.
            pingServer()
            return
        }
        addUserMessage(text)
        wsClient.sendJson(TextMsg(text = text))
    }

    /** Start the foreground service so its notification shows we're connected. */
    private fun startConnectionService() {
        val ctx = getApplication<Application>()
        try {
            ctx.startForegroundService(Intent(ctx, WalkieTalkieService::class.java))
        } catch (e: Exception) {
            // Android forbids starting a foreground service from the background
            // (e.g. a reconnect while the app is backgrounded). Skip the
            // notification in that case rather than crash.
            Log.w(TAG, "Couldn't start connection service", e)
        }
    }

    /** Stop the foreground service, removing its notification. */
    private fun stopConnectionService() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, WalkieTalkieService::class.java))
    }

    /** Ping the server to check it's alive and measure round-trip time. */
    fun pingServer() {
        if (!_uiState.value.isConnected) {
            addSystemMessage("Not connected — server unreachable.")
            return
        }
        pingSentAt = System.currentTimeMillis()
        addSystemMessage("Pinging server…")
        wsClient.sendJson(PingMsg())
    }

    fun sendImage(uri: Uri, text: String? = null) {
        val encoded = ImageCapture.encodeImageFromUri(getApplication(), uri) ?: return
        addUserMessage(text ?: "Sent an image", imageUri = uri)
        wsClient.sendJson(ImageMsg(data = encoded, text = text))
    }

    fun interrupt(sendInterruptMsg: Boolean = true) {
        if (sendInterruptMsg) {
            wsClient.sendJson(InterruptMsg())
        }
        audioPlayer.stop()
        updateActivePage { it.copy(isResponding = false) }
        streamingMessageId?.let { id ->
            updateActiveMessage(id) { it.copy(isStreaming = false) }
        }
        streamingMessageId = null
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
        viewModelScope.launch {
            getApplication<Application>().settingsStore.edit { prefs ->
                prefs[KEY_SERVER_URL] = url
            }
        }
    }

    /** Open a project from the home list (or focus it if it's already open) and
     * enter its conversation. The server repaints scrollback + any in-flight turn
     * that kept running in the background. */
    fun openWorkspace(name: String) {
        _uiState.update { s ->
            val pages = s.pages.toMutableList()
            var idx = pages.indexOfFirst { it.currentWorkspace == name }
            if (idx < 0) {
                pages.add(ChatPage(currentWorkspace = name))
                idx = pages.lastIndex
            }
            // Keep any existing messages on screen; the server's repaint replaces
            // them in place, so reopening never flashes empty before it loads.
            s.copy(pages = pages, activePageIndex = idx, onHome = false)
        }
        streamingMessageId = null
        wsClient.sendJson(SelectWorkspaceMsg(name = name))
        persistPages()
    }

    /** Leave the conversation and return to the sessions list. The session keeps
     * running on the server but goes quiet (nothing streams) until reopened. */
    fun goHome() {
        if (_uiState.value.onHome) return
        // Cut local audio so the session we're leaving doesn't keep talking at us
        // on the list, and detach the streaming cursor.
        audioPlayer.stop()
        streamingMessageId = null
        _uiState.update { it.copy(onHome = true) }
        wsClient.sendJson(DeactivateMsg())
        persistPages()
    }

    /** Save the spinning plates (each page's workspace + the on-screen page) so a
     * reopen restores them. The work itself lives on the server (Part A); this is
     * just the phone remembering which viewports to re-open. */
    private fun persistPages() {
        val names = _uiState.value.pages.mapNotNull { it.currentWorkspace }
        viewModelScope.launch {
            getApplication<Application>().settingsStore.edit { prefs ->
                prefs[KEY_PAGES] = WsJson.encodeToString(names)
            }
        }
    }

    /**
     * After a reconnect, re-select whatever workspace is on screen so the server
     * repaints it. The server reset its "active workspace" when the old socket
     * dropped, and any turn that kept running while we were gone has progress to
     * replay. We clear the page's messages first so the server's repaint
     * (scrollback + in-flight progress) is authoritative rather than doubling up
     * with the stale bubbles from before the drop. No-op until a workspace has
     * been chosen (first connect defers to WorkspaceList's auto-select).
     */
    private fun resyncActiveWorkspace() {
        val state = _uiState.value
        // On the home list nothing is selected — stay deactivated until the user
        // opens a project.
        if (state.onHome) return
        val page = state.pages.getOrNull(state.activePageIndex) ?: return
        val ws = page.currentWorkspace ?: return
        // Leave the page's messages up; the server repaint replaces them in place.
        streamingMessageId = null
        wsClient.sendJson(SelectWorkspaceMsg(name = ws))
    }

    /** A swipe between open sessions inside the conversation view. */
    fun onPageChanged(index: Int) {
        val state = _uiState.value
        if (state.onHome) return
        if (index == state.activePageIndex) return
        if (index !in state.pages.indices) return

        // Switching away does NOT interrupt the old workspace — it keeps running
        // in the background on the server. Just stop local audio so its TTS
        // doesn't bleed into the page we're moving to, and detach the streaming
        // cursor so the new page's deltas land in a fresh bubble.
        audioPlayer.stop()
        streamingMessageId = null

        // Keep the page's current messages on screen; the server's repaint
        // (ConversationHistory, sent on selection) replaces them in place. Not
        // blanking first means flipping to an already-open project shows its chat
        // continuously rather than flashing empty for a frame before it repaints.
        val ws = state.pages[index].currentWorkspace
        _uiState.update { it.copy(activePageIndex = index) }

        // Tell the server which workspace is now on screen. It replays scrollback
        // and any in-flight progress for that workspace (without interrupting it).
        ws?.let { wsClient.sendJson(SelectWorkspaceMsg(name = it)) }
        persistPages()
    }

    private fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.Connecting -> {
                // Only announce the very first attempt; stay quiet while the
                // background reconnect loop keeps retrying.
                if (event.attempt == 1) addSystemMessage("Connecting…")
            }
            is WsEvent.TextReceived -> handleServerMessage(event.text)
            is WsEvent.BinaryReceived -> handleBinaryMessage(event.data)
            is WsEvent.Failure -> {
                // Couldn't reach the server. Say so once and then let the
                // reconnect loop retry silently — no per-attempt spam.
                if (!unavailableAnnounced) {
                    unavailableAnnounced = true
                    addSystemMessage("Server unavailable — retrying in the background…")
                }
            }
            is WsEvent.Disconnected -> when {
                intentionalDisconnect -> {
                    intentionalDisconnect = false
                    addSystemMessage("Disconnected")
                }
                // Lost an established connection (e.g. server restarted). Announce
                // once; the reconnect loop takes it from here.
                !unavailableAnnounced -> {
                    unavailableAnnounced = true
                    addSystemMessage("Server unavailable — retrying in the background…")
                }
            }
            is WsEvent.Connected -> {} // handled by isConnected flow
        }
    }

    private fun handleServerMessage(json: String) {
        val msg = parseServerMessage(json)
        // The workspace currently on screen (null when we're on the home list).
        // A page-bound message tagged for any other workspace is a stale repaint
        // for a project we're not looking at — drop it, or it lands in the wrong
        // window (or paints under the home list).
        val activeWs = _uiState.value.run {
            if (onHome) null else pages.getOrNull(activePageIndex)?.currentWorkspace
        }
        fun mismatched(ws: String) = ws.isNotEmpty() && ws != activeWs

        when (msg) {
            is ServerMessage.Transcription -> {
                addUserMessage(msg.msg.text)
            }

            is ServerMessage.ResponseDelta -> {
                if (mismatched(msg.msg.workspace)) return
                updateActivePage { it.copy(isResponding = true) }
                val id = streamingMessageId
                if (id != null) {
                    updateActiveMessage(id) { it.copy(text = it.text + msg.msg.text) }
                } else {
                    val newMsg = ChatMessage(
                        role = Role.ASSISTANT,
                        text = msg.msg.text,
                        isStreaming = true,
                    )
                    streamingMessageId = newMsg.id
                    addMessage(newMsg)
                }
            }

            is ServerMessage.ResponseEnd -> {
                if (mismatched(msg.msg.workspace)) return
                updateActivePage { it.copy(isResponding = false) }
                streamingMessageId?.let { id ->
                    updateActiveMessage(id) { it.copy(isStreaming = false) }
                }
                streamingMessageId = null
            }

            is ServerMessage.ToolUse -> {
                if (mismatched(msg.msg.workspace)) return
                addMessage(ChatMessage(
                    role = Role.TOOL,
                    text = "Using ${msg.msg.toolName}...",
                    toolName = msg.msg.toolName,
                ))
            }

            is ServerMessage.ToolResult -> {
                if (mismatched(msg.msg.workspace)) return
                val summary = if (msg.msg.success) "Done" else "Failed"
                addMessage(ChatMessage(
                    role = Role.TOOL,
                    text = "$summary: ${msg.msg.toolName}",
                    toolName = msg.msg.toolName,
                    toolOutput = msg.msg.output,
                ))
            }

            is ServerMessage.TtsStart -> audioPlayer.onTtsStart()
            is ServerMessage.TtsEnd -> audioPlayer.onTtsEnd()

            is ServerMessage.Error -> {
                if (mismatched(msg.msg.workspace)) return
                addSystemMessage("Error: ${msg.msg.message}")
            }

            is ServerMessage.WorkspaceList -> {
                val workspaces = msg.msg.workspaces.map { Workspace(it.name, it.path) }
                val validNames = workspaces.map { it.name }.toSet()
                _uiState.update { s ->
                    // Drop any restored open sessions whose workspace no longer
                    // exists on the server (renamed/removed in config).
                    val kept = s.pages.filter { it.currentWorkspace in validNames }
                    val active = s.activePageIndex.coerceIn(0, (kept.size - 1).coerceAtLeast(0))
                    s.copy(
                        workspaces = workspaces,
                        pages = kept,
                        activePageIndex = active,
                        // If everything we had open is gone, fall back to the list.
                        onHome = if (kept.isEmpty()) true else s.onHome,
                    )
                }
                // No auto-select: the home list is the landing screen. The user
                // picks which project to open.
            }

            is ServerMessage.WorkspaceSelected -> {
                // Only the repaint for the page we're actually on (a stale one for
                // a workspace we've swiped past would flip the wrong page's state).
                if (mismatched(msg.msg.name)) return
                // Reflect whether this workspace still has a turn running in the
                // background, so the "Thinking…" indicator is right on switch-back.
                updateActivePage { it.copy(isResponding = msg.msg.responding) }
            }

            is ServerMessage.ConversationHistory -> {
                if (mismatched(msg.msg.workspace)) return
                // Replayed scrollback for a resumed session — repaint the page so
                // a continued conversation isn't visually blank on reopen.
                val restored = msg.msg.messages.mapIndexed { i, h ->
                    val role = when (h.role) {
                        "user" -> Role.USER
                        "assistant" -> Role.ASSISTANT
                        "tool" -> Role.TOOL
                        else -> Role.SYSTEM
                    }
                    ChatMessage(
                        // Stable, position-based id so re-painting the SAME
                        // scrollback (which happens on every flip to an already-open
                        // project) yields identical list keys. Otherwise each
                        // ChatMessage gets a fresh random id, the LazyColumn sees all
                        // keys change, and it tears down + rebuilds every row — the
                        // blink that showed ~half a second after each swipe.
                        id = "hist-$i",
                        role = role,
                        text = h.text,
                        toolName = h.toolName,
                        toolOutput = h.toolOutput,
                    )
                }
                if (restored.isNotEmpty()) {
                    updateActivePage { it.copy(messages = restored) }
                }
            }

            is ServerMessage.SessionsStatus -> {
                val statuses = msg.msg.sessions.map {
                    SessionStatus(name = it.name, responding = it.responding, blocked = it.blocked)
                }
                _uiState.update {
                    it.copy(
                        sessionStatuses = statuses,
                        liveSessionCount = msg.msg.total,
                        needsYouCount = msg.msg.needsYou,
                    )
                }
            }

            is ServerMessage.Pong -> {
                // Only report pongs we asked for (ignore background heartbeats).
                if (pingSentAt > 0L) {
                    val rtt = System.currentTimeMillis() - pingSentAt
                    pingSentAt = 0L
                    addSystemMessage("Server responsive ✓ (${rtt} ms)")
                }
            }
            is ServerMessage.PermissionRequest -> {
                // Pin the approval to its own workspace so it shows only on that
                // project's page (and stays put when you swipe elsewhere).
                val m = msg.msg
                val ws = m.workspace
                approvalTimeoutJobs.remove(ws)?.cancel()
                _uiState.update {
                    it.copy(pendingApprovals = it.pendingApprovals +
                        (ws to PendingApproval(m.id, m.toolName, m.summary, m.detail, ws)))
                }
                startApprovalTimeout(m.id, ws)
            }
            is ServerMessage.PermissionResolved -> {
                // Settled server-side (often a spoken yes/no). Dismiss whichever
                // workspace's approval carries this id.
                val entry = _uiState.value.pendingApprovals.entries.firstOrNull { it.value.id == msg.msg.id }
                if (entry != null) {
                    approvalTimeoutJobs.remove(entry.key)?.cancel()
                    _uiState.update { it.copy(pendingApprovals = it.pendingApprovals - entry.key) }
                }
            }
            is ServerMessage.Unknown -> Log.w(TAG, "Unknown message type: ${msg.type}")
        }
    }

    /** Close the on-screen project's live session: stops the agent server-side
     * (frees the process, drops it from the running count) and removes the page.
     * History is kept — reopening the project resumes where you left off. */
    /** Close the session on screen (from the session view's overflow), then
     * settle the view: drop to the list if nothing's left, else repaint whatever
     * page slid in. History is kept — reopening resumes. */
    fun closeWorkspace() {
        val name = _uiState.value.run { pages.getOrNull(activePageIndex)?.currentWorkspace } ?: return
        streamingMessageId = null
        closeWorkspace(name)
        if (_uiState.value.onHome) {
            // Nothing left on screen — make sure the server stops streaming too.
            wsClient.sendJson(DeactivateMsg())
        } else {
            // A different open session slid into view; re-select it so it repaints
            // (and its in-flight turn streams again).
            resyncActiveWorkspace()
        }
    }

    /** Close a session by name — e.g. straight from the home list's "Currently
     * open" section. Stops the agent server-side and drops its page; the
     * conversation transcript is kept so reopening resumes it. */
    fun closeWorkspace(name: String) {
        wsClient.sendJson(CloseWorkspaceMsg(name = name))
        approvalTimeoutJobs.remove(name)?.cancel()
        _uiState.update { s ->
            val idx = s.pages.indexOfFirst { it.currentWorkspace == name }
            val pages = s.pages.toMutableList()
            if (idx >= 0) pages.removeAt(idx)
            // Keep the active page index valid if we removed something before it.
            val active = s.activePageIndex
                .let { if (idx in 0 until it) it - 1 else it }
                .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            s.copy(
                pages = pages,
                activePageIndex = active,
                onHome = if (pages.isEmpty()) true else s.onHome,
                pendingApprovals = s.pendingApprovals - name,
            )
        }
        persistPages()
    }

    /** Ask the server to speak the current project's last response again — for
     * hearing a reply that finished while you were on another project. */
    fun replayLastResponse() {
        if (!_uiState.value.isConnected) return
        wsClient.sendJson(ReplayLastMsg())
    }

    /** User tapped Approve/Deny on the on-screen project's pending prompt. */
    fun respondToApproval(approved: Boolean) {
        val ws = _uiState.value.run { pages.getOrNull(activePageIndex)?.currentWorkspace } ?: return
        val pending = _uiState.value.pendingApprovals[ws] ?: return
        approvalTimeoutJobs.remove(ws)?.cancel()
        wsClient.sendJson(PermissionResponseMsg(id = pending.id, approved = approved))
        _uiState.update { it.copy(pendingApprovals = it.pendingApprovals - ws) }
    }

    /** Auto-deny if the user doesn't respond, so a forgotten prompt can't hang. */
    private fun startApprovalTimeout(id: String, ws: String) {
        approvalTimeoutJobs.remove(ws)?.cancel()
        approvalTimeoutJobs[ws] = viewModelScope.launch {
            delay(30_000)
            if (_uiState.value.pendingApprovals[ws]?.id == id) {
                wsClient.sendJson(PermissionResponseMsg(id = id, approved = false))
                _uiState.update { it.copy(pendingApprovals = it.pendingApprovals - ws) }
            }
        }
    }

    private fun handleBinaryMessage(data: ByteArray) {
        if (data.isEmpty()) return
        val prefix = data[0]
        val payload = data.copyOfRange(1, data.size)

        if (prefix == AudioPrefix.TTS) {
            audioPlayer.onTtsChunk(payload)
        }
    }

    private fun addUserMessage(text: String, imageUri: Uri? = null) {
        addMessage(ChatMessage(role = Role.USER, text = text, imageUri = imageUri))
    }

    private fun addSystemMessage(text: String) {
        addMessage(ChatMessage(role = Role.SYSTEM, text = text))
    }

    private fun addMessage(msg: ChatMessage) {
        updateActivePage { page ->
            page.copy(messages = page.messages + msg)
        }
    }

    private fun updateActiveMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        updateActivePage { page ->
            page.copy(messages = page.messages.map {
                if (it.id == id) transform(it) else it
            })
        }
    }

    private fun updateActivePage(transform: (ChatPage) -> ChatPage) {
        _uiState.update { state ->
            val pages = state.pages.toMutableList()
            val i = state.activePageIndex
            if (i in pages.indices) {
                pages[i] = transform(pages[i])
            }
            state.copy(pages = pages)
        }
    }

    override fun onCleared() {
        stopListening()
        wsClient.disconnect()
        audioPlayer.release()
        super.onCleared()
    }
}
