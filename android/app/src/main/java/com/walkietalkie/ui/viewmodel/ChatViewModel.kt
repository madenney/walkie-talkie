package com.walkietalkie.ui.viewmodel

import android.app.Application
import android.content.Context
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

private val Context.settingsStore by preferencesDataStore("settings")
private val KEY_SERVER_URL = stringPreferencesKey("server_url")
private val KEY_MUTED = booleanPreferencesKey("muted")

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

data class ChatPage(
    val id: String = System.nanoTime().toString(),
    val currentWorkspace: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isResponding: Boolean = false,
)

data class ChatUiState(
    val pages: List<ChatPage> = listOf(ChatPage()),
    val activePageIndex: Int = 0,
    val isConnected: Boolean = false,
    val isMuted: Boolean = false,
    val listeningState: VadState = VadState.IDLE,
    val isPlayingAudio: Boolean = false,
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val workspaces: List<Workspace> = emptyList(),
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
                    addSystemMessage("Connected")
                    // Auto-start listening on connect (if not muted)
                    if (!_uiState.value.isMuted) {
                        startListening()
                    }
                } else if (wasConnected) {
                    // Disconnected — stop listening
                    stopListening()
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
                    state.isMuted -> "Muted"
                    state.isPlayingAudio -> "Speaking"
                    activePage?.isResponding == true -> "Thinking..."
                    isSpeech -> "Hearing you..."
                    state.listeningState == VadState.LISTENING -> "Listening"
                    else -> "Connected"
                }
                WalkieTalkieService.instance?.updateNotification(status)
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
        if (state.isPlayingAudio || state.pages[state.activePageIndex].isResponding) {
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

    fun sendText(text: String) {
        if (text.isBlank()) return
        addUserMessage(text)
        wsClient.sendJson(TextMsg(text = text))
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

    fun selectWorkspace(name: String) {
        val state = _uiState.value
        val currentPage = state.pages[state.activePageIndex]
        if (currentPage.currentWorkspace == name) return

        _uiState.update { s ->
            val pages = s.pages.toMutableList()
            val i = s.activePageIndex
            pages[i] = pages[i].copy(currentWorkspace = name, messages = emptyList())

            // Ensure a blank sentinel page at the end
            if (i == pages.lastIndex) {
                pages.add(ChatPage())
            }

            s.copy(pages = pages)
        }
        streamingMessageId = null
        wsClient.sendJson(SelectWorkspaceMsg(name = name))
    }

    fun onPageChanged(index: Int) {
        val state = _uiState.value
        if (index == state.activePageIndex) return
        if (index !in state.pages.indices) return

        // Cancel in-flight response on old page
        val oldPage = state.pages[state.activePageIndex]
        if (oldPage.isResponding) {
            interrupt()
        }
        streamingMessageId = null

        _uiState.update { it.copy(activePageIndex = index) }

        // Switch workspace on server if new page has one
        val newPage = state.pages[index]
        if (newPage.currentWorkspace != null) {
            wsClient.sendJson(SelectWorkspaceMsg(name = newPage.currentWorkspace))
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.Connecting -> {
                val msg = if (event.attempt == 1) "Connecting to ${event.url}..."
                    else "Reconnecting (attempt ${event.attempt})..."
                addSystemMessage(msg)
            }
            is WsEvent.TextReceived -> handleServerMessage(event.text)
            is WsEvent.BinaryReceived -> handleBinaryMessage(event.data)
            is WsEvent.Failure -> addSystemMessage("Connection error: ${event.error.message}")
            is WsEvent.Disconnected -> addSystemMessage("Disconnected")
            is WsEvent.Connected -> {} // handled by isConnected flow
        }
    }

    private fun handleServerMessage(json: String) {
        when (val msg = parseServerMessage(json)) {
            is ServerMessage.Transcription -> {
                addUserMessage(msg.msg.text)
            }

            is ServerMessage.ResponseDelta -> {
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
                updateActivePage { it.copy(isResponding = false) }
                streamingMessageId?.let { id ->
                    updateActiveMessage(id) { it.copy(isStreaming = false) }
                }
                streamingMessageId = null
            }

            is ServerMessage.ToolUse -> {
                addMessage(ChatMessage(
                    role = Role.TOOL,
                    text = "Using ${msg.msg.toolName}...",
                    toolName = msg.msg.toolName,
                ))
            }

            is ServerMessage.ToolResult -> {
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

            is ServerMessage.Error -> addSystemMessage("Error: ${msg.msg.message}")

            is ServerMessage.WorkspaceList -> {
                val workspaces = msg.msg.workspaces.map { Workspace(it.name, it.path) }
                _uiState.update { it.copy(workspaces = workspaces) }
            }

            is ServerMessage.WorkspaceSelected -> {
                addSystemMessage("Workspace: ${msg.msg.name}")
            }

            is ServerMessage.Pong -> {} // heartbeat
            is ServerMessage.Unknown -> Log.w(TAG, "Unknown message type: ${msg.type}")
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

    private fun updatePage(index: Int, transform: (ChatPage) -> ChatPage) {
        _uiState.update { state ->
            val pages = state.pages.toMutableList()
            if (index in pages.indices) {
                pages[index] = transform(pages[index])
            }
            state.copy(pages = pages)
        }
    }

    private fun updatePageMessage(pageIndex: Int, msgId: String, transform: (ChatMessage) -> ChatMessage) {
        updatePage(pageIndex) { page ->
            page.copy(messages = page.messages.map {
                if (it.id == msgId) transform(it) else it
            })
        }
    }

    override fun onCleared() {
        stopListening()
        wsClient.disconnect()
        audioPlayer.release()
        super.onCleared()
    }
}
