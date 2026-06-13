"""WebSocket message type definitions.

Text frames carry JSON with a "type" field. Binary frames carry audio
with a 1-byte prefix: 0x01 = mic audio (phone→server), 0x02 = TTS audio
(server→phone).
"""

from __future__ import annotations

from enum import IntEnum
from typing import Any, Literal

from pydantic import BaseModel


# Binary frame prefixes
class AudioPrefix(IntEnum):
    MIC = 0x01
    TTS = 0x02


# --- Phone → Server messages ---

class SelectWorkspace(BaseModel):
    type: Literal["select_workspace"] = "select_workspace"
    name: str


class AudioStart(BaseModel):
    type: Literal["audio_start"] = "audio_start"
    sample_rate: int = 16000
    channels: int = 1
    encoding: str = "pcm_s16le"


class AudioEnd(BaseModel):
    type: Literal["audio_end"] = "audio_end"


class TextMessage(BaseModel):
    type: Literal["text_message"] = "text_message"
    text: str


class ImageMessage(BaseModel):
    type: Literal["image_message"] = "image_message"
    data: str  # base64-encoded JPEG
    media_type: str = "image/jpeg"
    text: str | None = None  # optional accompanying text


class Interrupt(BaseModel):
    type: Literal["interrupt"] = "interrupt"


class Ping(BaseModel):
    type: Literal["ping"] = "ping"


class PermissionResponse(BaseModel):
    type: Literal["permission_response"] = "permission_response"
    id: str
    approved: bool


# --- Server → Phone messages ---

class Transcription(BaseModel):
    type: Literal["transcription"] = "transcription"
    text: str
    is_final: bool = True


class ResponseDelta(BaseModel):
    type: Literal["response_delta"] = "response_delta"
    text: str


class ResponseEnd(BaseModel):
    type: Literal["response_end"] = "response_end"


class ToolUse(BaseModel):
    type: Literal["tool_use"] = "tool_use"
    tool_name: str
    tool_id: str
    input: dict[str, Any] = {}


class ToolResult(BaseModel):
    type: Literal["tool_result"] = "tool_result"
    tool_id: str
    tool_name: str
    success: bool
    output: str = ""


class TTSStart(BaseModel):
    type: Literal["tts_start"] = "tts_start"
    format: str = "mp3"


class TTSEnd(BaseModel):
    type: Literal["tts_end"] = "tts_end"


class Error(BaseModel):
    type: Literal["error"] = "error"
    message: str
    code: str = "unknown"


class Pong(BaseModel):
    type: Literal["pong"] = "pong"


class PermissionRequest(BaseModel):
    type: Literal["permission_request"] = "permission_request"
    id: str
    tool_name: str
    summary: str            # short spoken/displayed line, e.g. "run a shell command"
    detail: str = ""        # the actual command/path for the on-screen card


class PermissionResolved(BaseModel):
    """Tells the phone an approval is settled (by voice/tap/timeout) so it can
    dismiss the prompt — needed because voice answers don't originate on the app."""
    type: Literal["permission_resolved"] = "permission_resolved"
    id: str
    approved: bool


class WorkspaceList(BaseModel):
    type: Literal["workspace_list"] = "workspace_list"
    workspaces: list[dict[str, str]]  # [{name, path}]


class WorkspaceSelected(BaseModel):
    type: Literal["workspace_selected"] = "workspace_selected"
    name: str
    path: str


class HistoryMessage(BaseModel):
    role: str               # "user" | "assistant" | "tool"
    text: str
    tool_name: str | None = None
    tool_output: str | None = None
    success: bool | None = None


class ConversationHistory(BaseModel):
    """Replayed scrollback for a resumed session, sent right after selection."""
    type: Literal["conversation_history"] = "conversation_history"
    messages: list[HistoryMessage] = []


# Union types for parsing
IncomingMessage = (
    AudioStart | AudioEnd | TextMessage | ImageMessage | Interrupt | Ping
    | SelectWorkspace | PermissionResponse
)

OutgoingMessage = (
    Transcription | ResponseDelta | ResponseEnd | ToolUse | ToolResult
    | TTSStart | TTSEnd | Error | Pong | WorkspaceList | WorkspaceSelected
    | PermissionRequest | PermissionResolved | ConversationHistory
)

INCOMING_TYPES: dict[str, type[BaseModel]] = {
    "audio_start": AudioStart,
    "audio_end": AudioEnd,
    "text_message": TextMessage,
    "image_message": ImageMessage,
    "interrupt": Interrupt,
    "ping": Ping,
    "select_workspace": SelectWorkspace,
    "permission_response": PermissionResponse,
}


def parse_incoming(data: dict[str, Any]) -> IncomingMessage:
    msg_type = data.get("type")
    cls = INCOMING_TYPES.get(msg_type)  # type: ignore[arg-type]
    if cls is None:
        raise ValueError(f"Unknown message type: {msg_type}")
    return cls(**data)
