package com.walkietalkie.data.websocket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val WsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// Binary frame prefixes
object AudioPrefix {
    const val MIC: Byte = 0x01
    const val TTS: Byte = 0x02
}

// --- Outgoing (phone → server) ---

@Serializable
data class AudioStartMsg(
    val type: String = "audio_start",
    @SerialName("sample_rate") val sampleRate: Int = 16000,
    val channels: Int = 1,
    val encoding: String = "pcm_s16le"
)

@Serializable
data class AudioEndMsg(val type: String = "audio_end")

@Serializable
data class TextMsg(
    val type: String = "text_message",
    val text: String
)

@Serializable
data class ImageMsg(
    val type: String = "image_message",
    val data: String, // base64
    @SerialName("media_type") val mediaType: String = "image/jpeg",
    val text: String? = null
)

@Serializable
data class InterruptMsg(val type: String = "interrupt")

@Serializable
data class SelectWorkspaceMsg(
    val type: String = "select_workspace",
    val name: String,
    // The phone's current event-log cursor for this workspace (-1 = none yet).
    // The server answers with a `sync` from here.
    val since: Int = -1
)

@Serializable
data class SubscribeMsg(
    val type: String = "subscribe",
    val workspace: String,
    val since: Int = -1
)

@Serializable
data class PingMsg(val type: String = "ping")

@Serializable
data class PermissionResponseMsg(
    val type: String = "permission_response",
    val id: String,
    val approved: Boolean
)

@Serializable
data class ReplayLastMsg(val type: String = "replay_last")

@Serializable
data class CloseWorkspaceMsg(
    val type: String = "close_workspace",
    val name: String
)

@Serializable
data class DeactivateMsg(val type: String = "deactivate")

// --- Incoming (server → phone) ---

// The event log: transcript content arrives as append-only events, each with a
// monotonic `seq`. `event` appends one; `event_delta` grows an assistant_text
// event's text; `sync` replays the tail from a cursor (switch/reconnect/gap).

@Serializable
data class EventFrameMsg(
    val type: String,
    val workspace: String = "",
    val event: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class EventDeltaMsg(
    val type: String,
    val workspace: String = "",
    val seq: Int,
    val text: String
)

@Serializable
data class SyncMsg(
    val type: String,
    val workspace: String = "",
    val events: List<JsonObject> = emptyList(),
    val reset: Boolean = false,
    @SerialName("head_seq") val headSeq: Int = -1
)

@Serializable
data class TtsStartMsg(
    val type: String,
    val format: String = "mp3",
    // The assistant_text event seq being read (-1 = last assistant message).
    @SerialName("target_seq") val targetSeq: Int = -1,
    val workspace: String = ""
)

@Serializable
data class TtsEndMsg(val type: String, val workspace: String = "")

@Serializable
data class ErrorMsg(
    val type: String,
    val message: String,
    val code: String = "unknown",
    val workspace: String = ""
)

@Serializable
data class PongMsg(val type: String)

@Serializable
data class WorkspaceInfo(
    val name: String,
    val path: String
)

@Serializable
data class WorkspaceListMsg(
    val type: String,
    val workspaces: List<WorkspaceInfo>
)

@Serializable
data class WorkspaceSelectedMsg(
    val type: String,
    val name: String,
    val path: String
)

@Serializable
data class SessionStatusInfoMsg(
    val name: String,
    val responding: Boolean = false,
    val blocked: Boolean = false,
)

@Serializable
data class SessionsStatusMsg(
    val type: String,
    val sessions: List<SessionStatusInfoMsg> = emptyList(),
    val total: Int = 0,
    @SerialName("needs_you") val needsYou: Int = 0,
)

/**
 * Represents any incoming server message, parsed by type field.
 */
sealed class ServerMessage {
    data class Event(val msg: EventFrameMsg) : ServerMessage()
    data class EventDelta(val msg: EventDeltaMsg) : ServerMessage()
    data class Sync(val msg: SyncMsg) : ServerMessage()
    data class TtsStart(val msg: TtsStartMsg) : ServerMessage()
    data class TtsEnd(val msg: TtsEndMsg) : ServerMessage()
    data class Error(val msg: ErrorMsg) : ServerMessage()
    data object Pong : ServerMessage()
    data class WorkspaceList(val msg: WorkspaceListMsg) : ServerMessage()
    data class WorkspaceSelected(val msg: WorkspaceSelectedMsg) : ServerMessage()
    data class SessionsStatus(val msg: SessionsStatusMsg) : ServerMessage()
    data class Unknown(val type: String) : ServerMessage()
}

fun parseServerMessage(json: String): ServerMessage {
    val obj = WsJson.decodeFromString<JsonObject>(json)
    val type = obj["type"]?.toString()?.trim('"') ?: return ServerMessage.Unknown("null")

    return when (type) {
        "event" -> ServerMessage.Event(WsJson.decodeFromString(json))
        "event_delta" -> ServerMessage.EventDelta(WsJson.decodeFromString(json))
        "sync" -> ServerMessage.Sync(WsJson.decodeFromString(json))
        "tts_start" -> ServerMessage.TtsStart(WsJson.decodeFromString(json))
        "tts_end" -> ServerMessage.TtsEnd(WsJson.decodeFromString(json))
        "error" -> ServerMessage.Error(WsJson.decodeFromString(json))
        "pong" -> ServerMessage.Pong
        "workspace_list" -> ServerMessage.WorkspaceList(WsJson.decodeFromString(json))
        "workspace_selected" -> ServerMessage.WorkspaceSelected(WsJson.decodeFromString(json))
        "sessions_status" -> ServerMessage.SessionsStatus(WsJson.decodeFromString(json))
        else -> ServerMessage.Unknown(type)
    }
}
