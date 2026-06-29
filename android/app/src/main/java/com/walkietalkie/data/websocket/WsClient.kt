package com.walkietalkie.data.websocket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

private const val TAG = "WsClient"

sealed class WsEvent {
    data object Connected : WsEvent()
    data object Disconnected : WsEvent()
    data class Connecting(val attempt: Int, val url: String) : WsEvent()
    data class TextReceived(val text: String) : WsEvent()
    data class BinaryReceived(val data: ByteArray) : WsEvent()
    data class Failure(val error: Throwable) : WsEvent()
}

class WsClient(
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    // Unbounded channel, not a buffered SharedFlow: under a burst (a big sync
    // plus streaming deltas plus TTS audio) a fixed buffer would silently drop
    // frames. Dropped text would corrupt the transcript; here nothing drops, and
    // the event-log cursor re-syncs anything lost across a reconnect anyway.
    // Single-consumer (ChatViewModel) — receiveAsFlow is collected exactly once.
    private val _events = Channel<WsEvent>(Channel.UNLIMITED)
    val events: Flow<WsEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _connectionState

    private var reconnectJob: Job? = null
    private var serverUrl: String = ""
    private var connectAttempt = 0

    fun connect(url: String) {
        serverUrl = url
        connectAttempt = 0
        reconnectJob?.cancel()
        doConnect(url)
    }

    private fun doConnect(url: String) {
        if (_connectionState.value) return
        webSocket?.cancel()
        connectAttempt++
        _events.trySend(WsEvent.Connecting(connectAttempt, url))
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $url")
                connectAttempt = 0
                reconnectJob?.cancel()
                reconnectJob = null
                _connectionState.value = true
                _events.trySend(WsEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _events.trySend(WsEvent.TextReceived(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _events.trySend(WsEvent.BinaryReceived(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Disconnected: $code $reason")
                _connectionState.value = false
                _events.trySend(WsEvent.Disconnected)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                _connectionState.value = false
                _events.trySend(WsEvent.Failure(t))
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (serverUrl.isEmpty()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delay = 1000L
            val maxDelay = 30000L
            while (isActive && !_connectionState.value) {
                Log.i(TAG, "Reconnecting in ${delay}ms...")
                delay(delay)
                if (!_connectionState.value) {
                    doConnect(serverUrl)
                    // Wait a bit to see if connection succeeds
                    delay(2000)
                }
                delay = (delay * 2).coerceAtMost(maxDelay)
            }
        }
    }

    fun sendText(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }

    fun sendBinary(data: ByteArray): Boolean {
        return webSocket?.send(data.toByteString()) ?: false
    }

    fun sendJson(msg: Any): Boolean {
        val json = when (msg) {
            is TextMsg -> WsJson.encodeToString(TextMsg.serializer(), msg)
            is AudioStartMsg -> WsJson.encodeToString(AudioStartMsg.serializer(), msg)
            is AudioEndMsg -> WsJson.encodeToString(AudioEndMsg.serializer(), msg)
            is ImageMsg -> WsJson.encodeToString(ImageMsg.serializer(), msg)
            is InterruptMsg -> WsJson.encodeToString(InterruptMsg.serializer(), msg)
            is SelectWorkspaceMsg -> WsJson.encodeToString(SelectWorkspaceMsg.serializer(), msg)
            is SubscribeMsg -> WsJson.encodeToString(SubscribeMsg.serializer(), msg)
            is PingMsg -> WsJson.encodeToString(PingMsg.serializer(), msg)
            is PermissionResponseMsg -> WsJson.encodeToString(PermissionResponseMsg.serializer(), msg)
            is ReplayLastMsg -> WsJson.encodeToString(ReplayLastMsg.serializer(), msg)
            is CloseWorkspaceMsg -> WsJson.encodeToString(CloseWorkspaceMsg.serializer(), msg)
            is DeactivateMsg -> WsJson.encodeToString(DeactivateMsg.serializer(), msg)
            else -> return false
        }
        return sendText(json)
    }

    fun sendMicAudio(pcmData: ByteArray) {
        val frame = ByteArray(1 + pcmData.size)
        frame[0] = AudioPrefix.MIC
        pcmData.copyInto(frame, 1)
        sendBinary(frame)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        serverUrl = ""
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = false
    }
}
