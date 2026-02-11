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
    val name: String
)

@Serializable
data class PingMsg(val type: String = "ping")

// --- Incoming (server → phone) ---

@Serializable
data class TranscriptionMsg(
    val type: String,
    val text: String,
    @SerialName("is_final") val isFinal: Boolean = true
)

@Serializable
data class ResponseDeltaMsg(
    val type: String,
    val text: String
)

@Serializable
data class ResponseEndMsg(val type: String)

@Serializable
data class ToolUseMsg(
    val type: String,
    @SerialName("tool_name") val toolName: String,
    @SerialName("tool_id") val toolId: String,
    val input: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ToolResultMsg(
    val type: String,
    @SerialName("tool_id") val toolId: String,
    @SerialName("tool_name") val toolName: String,
    val success: Boolean,
    val output: String = ""
)

@Serializable
data class TtsStartMsg(
    val type: String,
    val format: String = "mp3"
)

@Serializable
data class TtsEndMsg(val type: String)

@Serializable
data class ErrorMsg(
    val type: String,
    val message: String,
    val code: String = "unknown"
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

/**
 * Represents any incoming server message, parsed by type field.
 */
sealed class ServerMessage {
    data class Transcription(val msg: TranscriptionMsg) : ServerMessage()
    data class ResponseDelta(val msg: ResponseDeltaMsg) : ServerMessage()
    data object ResponseEnd : ServerMessage()
    data class ToolUse(val msg: ToolUseMsg) : ServerMessage()
    data class ToolResult(val msg: ToolResultMsg) : ServerMessage()
    data class TtsStart(val msg: TtsStartMsg) : ServerMessage()
    data object TtsEnd : ServerMessage()
    data class Error(val msg: ErrorMsg) : ServerMessage()
    data object Pong : ServerMessage()
    data class WorkspaceList(val msg: WorkspaceListMsg) : ServerMessage()
    data class WorkspaceSelected(val msg: WorkspaceSelectedMsg) : ServerMessage()
    data class Unknown(val type: String) : ServerMessage()
}

fun parseServerMessage(json: String): ServerMessage {
    val obj = WsJson.decodeFromString<JsonObject>(json)
    val type = obj["type"]?.toString()?.trim('"') ?: return ServerMessage.Unknown("null")

    return when (type) {
        "transcription" -> ServerMessage.Transcription(WsJson.decodeFromString(json))
        "response_delta" -> ServerMessage.ResponseDelta(WsJson.decodeFromString(json))
        "response_end" -> ServerMessage.ResponseEnd
        "tool_use" -> ServerMessage.ToolUse(WsJson.decodeFromString(json))
        "tool_result" -> ServerMessage.ToolResult(WsJson.decodeFromString(json))
        "tts_start" -> ServerMessage.TtsStart(WsJson.decodeFromString(json))
        "tts_end" -> ServerMessage.TtsEnd
        "error" -> ServerMessage.Error(WsJson.decodeFromString(json))
        "pong" -> ServerMessage.Pong
        "workspace_list" -> ServerMessage.WorkspaceList(WsJson.decodeFromString(json))
        "workspace_selected" -> ServerMessage.WorkspaceSelected(WsJson.decodeFromString(json))
        else -> ServerMessage.Unknown(type)
    }
}
