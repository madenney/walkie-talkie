package com.walkietalkie.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.walkietalkie.BuildConfig
import com.walkietalkie.audio.AudioCapture
import com.walkietalkie.audio.AudioPlayer
import com.walkietalkie.camera.ImageCapture
import com.walkietalkie.data.websocket.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    val isRecording: Boolean = false,
    val isPlayingAudio: Boolean = false,
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val workspaces: List<Workspace> = emptyList(),
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val wsClient = WsClient(viewModelScope)
    val audioCapture = AudioCapture(application)
    val audioPlayer = AudioPlayer(application)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    // Currently streaming assistant message ID (for the active page)
    private var streamingMessageId: String? = null

    init {
        audioPlayer.initialize()

        // Auto-connect on launch
        addSystemMessage("Starting up...")
        connect()

        viewModelScope.launch {
            wsClient.isConnected.collect { connected ->
                _uiState.update { it.copy(isConnected = connected) }
                if (connected) {
                    addSystemMessage("Connected")
                }
            }
        }

        viewModelScope.launch {
            wsClient.events.collect { event ->
                handleWsEvent(event)
            }
        }

        viewModelScope.launch {
            audioCapture.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }

        viewModelScope.launch {
            audioPlayer.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlayingAudio = playing) }
            }
        }
    }

    fun connect(url: String? = null) {
        val serverUrl = url ?: _uiState.value.serverUrl
        _uiState.update { it.copy(serverUrl = serverUrl) }
        wsClient.connect(serverUrl)
    }

    fun disconnect() {
        wsClient.disconnect()
        _uiState.update { it.copy(isConnected = false) }
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

    fun startRecording() {
        if (_uiState.value.isRecording) return
        wsClient.sendJson(AudioStartMsg())
        audioCapture.start(viewModelScope) { chunk ->
            wsClient.sendMicAudio(chunk)
        }
    }

    fun stopRecording() {
        audioCapture.stop()
        wsClient.sendJson(AudioEndMsg())
    }

    fun interrupt() {
        wsClient.sendJson(InterruptMsg())
        audioPlayer.stop()
        updateActivePage { it.copy(isResponding = false) }
        streamingMessageId?.let { id ->
            updateActiveMessage(id) { it.copy(isStreaming = false) }
        }
        streamingMessageId = null
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
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
            wsClient.sendJson(InterruptMsg())
            audioPlayer.stop()
            updatePage(state.activePageIndex) { it.copy(isResponding = false) }
            streamingMessageId?.let { id ->
                updatePageMessage(state.activePageIndex, id) { it.copy(isStreaming = false) }
            }
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
        wsClient.disconnect()
        audioCapture.stop()
        audioPlayer.release()
        super.onCleared()
    }
}
