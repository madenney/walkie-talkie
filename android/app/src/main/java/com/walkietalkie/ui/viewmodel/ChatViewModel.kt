package com.walkietalkie.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.walkietalkie.audio.PlaybackState
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    // Highest event-log seq applied for this workspace (-1 = nothing yet). The
    // page's whole transcript is a fold of the server's event log up to here;
    // re-selecting/reconnecting sends this as `since` to catch up.
    val cursor: Int = -1,
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
    // The assistant message currently being spoken aloud (null when nothing is).
    // Its bubble shows an animated highlight and is tappable to pause/resume.
    val speakingMessageId: String? = null,
    // Whether that spoken response is paused (the user tapped the bubble).
    val audioPaused: Boolean = false,
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

    // Monotonic counter for optimistic local message ids (the user's own bubble,
    // shown instantly before the server's user_msg event echoes back and adopts
    // it). Kept distinct from "ev-<seq>" log ids.
    private var localIdCounter = 0L

    // True once we've told the user the server is unavailable, so the silent
    // reconnect loop doesn't spam the chat. Reset on a successful connect.
    private var unavailableAnnounced = false

    // Set when the user (or a settings change) deliberately disconnects, so the
    // resulting Disconnected event isn't mislabelled as "server unavailable".
    private var intentionalDisconnect = false

    // Timestamp (ms) of an outstanding user-initiated ping, for RTT reporting.
    // 0 means no ping is in flight.
    private var pingSentAt = 0L

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
                    // last-known count/dots/prompt would be misleading. On
                    // reconnect, the event-log sync re-surfaces any still-open
                    // approval. (FCM, later, restores awareness while away.)
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

        // Drive the speaking-bubble highlight from the (stable) playback lifecycle.
        // PLAYING/PAUSED keep the highlight up (paused just flips the affordance);
        // IDLE/ENDED drop it. speakingMessageId itself is set when TTS begins.
        viewModelScope.launch {
            audioPlayer.playback.collect { st ->
                _uiState.update {
                    when (st) {
                        PlaybackState.PLAYING -> it.copy(audioPaused = false)
                        PlaybackState.PAUSED -> it.copy(audioPaused = true)
                        PlaybackState.IDLE, PlaybackState.ENDED ->
                            it.copy(speakingMessageId = null, audioPaused = false)
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
        // If TTS is playing/paused or the server is responding, interrupt first.
        val state = _uiState.value
        val responding = state.pages.getOrNull(state.activePageIndex)?.isResponding == true
        if (state.isPlayingAudio || state.speakingMessageId != null || responding) {
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

        // Barge-in: pressing to talk while Claude is speaking/paused/thinking
        // interrupts it (a paused utterance has no live audio but is still "open").
        val state = _uiState.value
        val responding = state.pages.getOrNull(state.activePageIndex)?.isResponding == true
        if (state.isPlayingAudio || state.speakingMessageId != null || responding) {
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
        // Local snappy feedback; the server's turn_state(false) event will
        // confirm this through the log shortly after.
        updateActivePage { p ->
            p.copy(
                isResponding = false,
                messages = p.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it },
            )
        }
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
            // Keep any existing messages on screen; the server's sync replaces or
            // appends in place, so reopening never flashes empty before it loads.
            s.copy(pages = pages, activePageIndex = idx, onHome = false)
        }
        // Select for audio routing + catch this page's event log up from its
        // cursor (-1 for a freshly-opened page → server replays scrollback).
        val since = _uiState.value.pages.firstOrNull { it.currentWorkspace == name }?.cursor ?: -1
        wsClient.sendJson(SelectWorkspaceMsg(name = name, since = since))
        persistPages()
    }

    /** Leave the conversation and return to the sessions list. The session keeps
     * running on the server but goes quiet (nothing streams) until reopened. */
    fun goHome() {
        if (_uiState.value.onHome) return
        // Cut local audio so the session we're leaving doesn't keep talking at us
        // on the list, and detach the streaming cursor.
        audioPlayer.stop()
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
        // Catch up from our cursor: the server sends just what we missed while
        // disconnected (or a full reset if it restarted with a fresh log).
        wsClient.sendJson(SelectWorkspaceMsg(name = ws, since = page.cursor))
    }

    /** A swipe between open sessions inside the conversation view. */
    fun onPageChanged(index: Int) {
        val state = _uiState.value
        if (state.onHome) return
        if (index == state.activePageIndex) return
        if (index !in state.pages.indices) return

        // Switching away does NOT interrupt the old workspace — it keeps running
        // in the background on the server (and its events keep streaming to us).
        // Just stop local audio so its TTS doesn't bleed into the page we move to.
        audioPlayer.stop()

        val page = state.pages[index]
        _uiState.update { it.copy(activePageIndex = index) }

        // Tell the server which workspace now routes audio, and catch this page's
        // log up from its cursor (usually already current, since background pages
        // stream live too — so the switch is instant, no repaint).
        page.currentWorkspace?.let {
            wsClient.sendJson(SelectWorkspaceMsg(name = it, since = page.cursor))
        }
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
        when (val msg = parseServerMessage(json)) {
            is ServerMessage.Event -> applyEvent(msg.msg.workspace, msg.msg.event)
            is ServerMessage.EventDelta -> applyEventDelta(msg.msg.workspace, msg.msg.seq, msg.msg.text)
            is ServerMessage.Sync -> applySync(msg.msg)

            is ServerMessage.TtsStart -> {
                audioPlayer.onTtsStart()
                // Highlight the bubble the server says it's reading (by event
                // seq), falling back to the last assistant reply (e.g. a replay).
                val id = if (msg.msg.targetSeq >= 0) "ev-${msg.msg.targetSeq}"
                         else lastAssistantMessageId()
                _uiState.update { it.copy(speakingMessageId = id, audioPaused = false) }
            }
            is ServerMessage.TtsEnd -> audioPlayer.onTtsEnd()

            is ServerMessage.Error -> {
                val ws = msg.msg.workspace
                val idx = _uiState.value.pages.indexOfFirst { it.currentWorkspace == ws }
                val sys = ChatMessage(
                    id = "local-${localIdCounter++}", role = Role.SYSTEM,
                    text = "Error: ${msg.msg.message}",
                )
                if (idx >= 0) updatePage(idx) { it.copy(messages = it.messages + sys) }
                else addSystemMessage("Error: ${msg.msg.message}")
            }

            // The selection ack — audio routing only; scrollback + responding
            // state arrive through the event log (`sync`), so nothing to do here.
            is ServerMessage.WorkspaceSelected -> {}

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
            is ServerMessage.Unknown -> Log.w(TAG, "Unknown message type: ${msg.type}")
        }
    }

    // ---- Event-log reducer ----------------------------------------------------
    // Each open page's transcript is a fold of its workspace's server-side event
    // log. Apply is idempotent by seq (dups ignored), and a non-contiguous seq
    // triggers a re-subscribe from our cursor — so a dropped/reordered frame
    // self-heals instead of corrupting the transcript.

    private fun JsonObject.int(k: String, d: Int = -1) = this[k]?.jsonPrimitive?.intOrNull ?: d
    private fun JsonObject.str(k: String, d: String = "") = this[k]?.jsonPrimitive?.contentOrNull ?: d
    private fun JsonObject.bool(k: String, d: Boolean = false) = this[k]?.jsonPrimitive?.booleanOrNull ?: d

    private fun applyEvent(ws: String, ev: JsonObject) {
        val state = _uiState.value
        val idx = state.pages.indexOfFirst { it.currentWorkspace == ws }
        if (idx < 0) return  // not an open page — ignore
        val cursor = state.pages[idx].cursor
        val seq = ev.int("seq")
        if (seq <= cursor) return                       // duplicate
        if (seq > cursor + 1) {                          // gap → catch up
            wsClient.sendJson(SubscribeMsg(workspace = ws, since = cursor))
            return
        }
        // A live (not replayed) permission request is what triggers the buzz.
        if (ev.str("kind") == "permission_req") vibrateForApproval()
        _uiState.update { foldEvent(it, idx, ev) }
    }

    private fun applyEventDelta(ws: String, seq: Int, text: String) {
        val state = _uiState.value
        val idx = state.pages.indexOfFirst { it.currentWorkspace == ws }
        if (idx < 0) return
        val id = "ev-$seq"
        if (state.pages[idx].messages.none { it.id == id }) {
            // We don't have the assistant_text event this delta extends — gap.
            wsClient.sendJson(SubscribeMsg(workspace = ws, since = state.pages[idx].cursor))
            return
        }
        updatePage(idx) { p ->
            p.copy(messages = p.messages.map {
                if (it.id == id) it.copy(text = it.text + text) else it
            })
        }
    }

    private fun applySync(m: SyncMsg) {
        val ws = m.workspace
        _uiState.update { st ->
            val idx = st.pages.indexOfFirst { it.currentWorkspace == ws }
            if (idx < 0) return@update st
            var state = st
            if (m.reset) {
                // We had a gap (or fresh page / server restart): rebuild from
                // scratch. Clear this page's messages, cursor, and any approval.
                val pages = state.pages.toMutableList()
                pages[idx] = pages[idx].copy(messages = emptyList(), cursor = -1, isResponding = false)
                state = state.copy(pages = pages, pendingApprovals = state.pendingApprovals - ws)
            }
            for (ev in m.events) {
                val curIdx = state.pages.indexOfFirst { it.currentWorkspace == ws }
                if (curIdx < 0) break
                if (ev.int("seq") <= state.pages[curIdx].cursor) continue
                state = foldEvent(state, curIdx, ev)
            }
            state
        }
    }

    /** Apply one contiguous event to the page at [idx]: advance its cursor and
     * mutate messages / approvals, then recompute the streaming flag. */
    private fun foldEvent(state: ChatUiState, idx: Int, ev: JsonObject): ChatUiState {
        val ws = state.pages[idx].currentWorkspace ?: ""
        val seq = ev.int("seq")
        val kind = ev.str("kind")
        var approvals = state.pendingApprovals
        var page = state.pages[idx]

        when (kind) {
            "permission_req" -> approvals = approvals + (ws to PendingApproval(
                id = ev.str("id"), toolName = ev.str("tool_name"),
                summary = ev.str("summary"), detail = ev.str("detail"), workspace = ws,
            ))
            "permission_res" -> if (approvals[ws]?.id == ev.str("id")) approvals = approvals - ws
            else -> page = applyLogMessage(page, seq, kind, ev)
        }

        page = page.copy(cursor = seq)
        if (kind != "permission_req" && kind != "permission_res") page = page.withStreamingFlags()
        val pages = state.pages.toMutableList().also { it[idx] = page }
        return state.copy(pages = pages, pendingApprovals = approvals)
    }

    /** Map a transcript event to the page's message list (no cursor handling). */
    private fun applyLogMessage(page: ChatPage, seq: Int, kind: String, ev: JsonObject): ChatPage {
        val id = "ev-$seq"
        return when (kind) {
            "user_msg" -> {
                val text = ev.str("text")
                // Adopt a matching optimistic local echo rather than duplicating.
                val local = page.messages.indexOfLast {
                    it.role == Role.USER && it.id.startsWith("local-") && it.text == text
                }
                if (local >= 0) {
                    val msgs = page.messages.toMutableList()
                    msgs[local] = msgs[local].copy(id = id)
                    page.copy(messages = msgs)
                } else {
                    page.copy(messages = page.messages + ChatMessage(id = id, role = Role.USER, text = text))
                }
            }
            "assistant_text" -> page.copy(messages = page.messages +
                ChatMessage(id = id, role = Role.ASSISTANT, text = ev.str("text"), isStreaming = true))
            "tool_use" -> page.copy(messages = page.messages + ChatMessage(
                id = id, role = Role.TOOL,
                text = "Using ${ev.str("tool_name")}...",
                toolName = ev.str("tool_name").ifEmpty { null },
            ))
            "tool_result" -> {
                val ok = ev.bool("success")
                val tn = ev.str("tool_name")
                page.copy(messages = page.messages + ChatMessage(
                    id = id, role = Role.TOOL,
                    text = "${if (ok) "Done" else "Failed"}: $tn",
                    toolName = tn.ifEmpty { null },
                    toolOutput = ev.str("output").ifEmpty { null },
                ))
            }
            "turn_state" -> page.copy(isResponding = ev.bool("responding"))
            else -> page
        }
    }

    /** The last message is "streaming" (blinking cursor) only while the turn is
     * responding and that last message is an assistant bubble. */
    private fun ChatPage.withStreamingFlags(): ChatPage {
        val last = messages.lastOrNull()
        val streamingId = if (isResponding && last?.role == Role.ASSISTANT) last.id else null
        return copy(messages = messages.map { it.copy(isStreaming = it.id == streamingId) })
    }

    private fun updatePage(index: Int, transform: (ChatPage) -> ChatPage) {
        _uiState.update { state ->
            if (index !in state.pages.indices) return@update state
            val pages = state.pages.toMutableList()
            pages[index] = transform(pages[index])
            state.copy(pages = pages)
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

    /** Tap on the bubble that's currently being spoken: pause it, or resume it
     * if already paused. Leaves the highlight up so you can toggle back. */
    fun toggleSpeakingPause() {
        audioPlayer.togglePause()
    }

    private fun lastAssistantMessageId(): String? =
        _uiState.value.run { pages.getOrNull(activePageIndex) }
            ?.messages?.lastOrNull { it.role == Role.ASSISTANT }?.id

    /** Ask the server to speak the current project's last response again — for
     * hearing a reply that finished while you were on another project. */
    fun replayLastResponse() {
        if (!_uiState.value.isConnected) return
        wsClient.sendJson(ReplayLastMsg())
    }

    /** User tapped Approve/Deny on the on-screen project's pending prompt.
     * Optimistically dismiss it; the server's permission_res event confirms. */
    fun respondToApproval(approved: Boolean) {
        val ws = _uiState.value.run { pages.getOrNull(activePageIndex)?.currentWorkspace } ?: return
        val pending = _uiState.value.pendingApprovals[ws] ?: return
        wsClient.sendJson(PermissionResponseMsg(id = pending.id, approved = approved))
        _uiState.update { it.copy(pendingApprovals = it.pendingApprovals - ws) }
    }

    /** Short double-buzz to flag that a tool is waiting on approval — a quiet
     * alternative to speaking the prompt aloud. */
    private fun vibrateForApproval() {
        val ctx = getApplication<Application>()
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        // timings: wait 0, buzz 60, pause 90, buzz 60 — a light "tick-tick".
        val pattern = longArrayOf(0, 60, 90, 60)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
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
        // Optimistic local echo. When the server's user_msg event arrives, the
        // reducer matches this by text and adopts it (rewrites the id to the
        // log's "ev-<seq>"), so it isn't duplicated.
        addMessage(ChatMessage(
            id = "local-${localIdCounter++}", role = Role.USER, text = text, imageUri = imageUri,
        ))
    }

    private fun addSystemMessage(text: String) {
        addMessage(ChatMessage(role = Role.SYSTEM, text = text))
    }

    private fun addMessage(msg: ChatMessage) {
        updateActivePage { page ->
            page.copy(messages = page.messages + msg)
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
