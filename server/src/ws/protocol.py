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


# NOTE: every message the phone routes to a specific workspace's page carries a
# `workspace` field, so the phone can ignore anything not meant for the page it's
# on (during rapid workspace switches, repaints for the old workspace can still be
# in flight). It's stamped centrally when sent — see WorkspacePool.send.

class ResponseDelta(BaseModel):
    type: Literal["response_delta"] = "response_delta"
    text: str
    workspace: str = ""


class ResponseEnd(BaseModel):
    type: Literal["response_end"] = "response_end"
    workspace: str = ""


class ToolUse(BaseModel):
    type: Literal["tool_use"] = "tool_use"
    tool_name: str
    tool_id: str
    input: dict[str, Any] = {}
    workspace: str = ""


class ToolResult(BaseModel):
    type: Literal["tool_result"] = "tool_result"
    tool_id: str
    tool_name: str
    success: bool
    output: str = ""
    workspace: str = ""


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
    workspace: str = ""


class PermissionResolved(BaseModel):
    """Tells the phone an approval is settled (by voice/tap/timeout) so it can
    dismiss the prompt — needed because voice answers don't originate on the app."""
    type: Literal["permission_resolved"] = "permission_resolved"
    id: str
    approved: bool
    workspace: str = ""


class WorkspaceList(BaseModel):
    type: Literal["workspace_list"] = "workspace_list"
    workspaces: list[dict[str, str]]  # [{name, path}]


class SessionStatusInfo(BaseModel):
    """Live status of one workspace agent for the phone's dashboard."""
    name: str
    responding: bool = False  # a turn is actively running
    blocked: bool = False     # waiting on a tool approval (needs the user)


class SessionsStatus(BaseModel):
    """Pushed whenever any workspace's status changes, so the phone can show a
    live Shelf-style view of every running Claude session (independent of which
    workspace is on screen)."""
    type: Literal["sessions_status"] = "sessions_status"
    sessions: list[SessionStatusInfo] = []
    total: int = 0       # all live sessions (the notification badge number)
    needs_you: int = 0   # how many are blocked on an approval


class WorkspaceSelected(BaseModel):
    type: Literal["workspace_selected"] = "workspace_selected"
    name: str
    path: str
    responding: bool = False  # a turn for this workspace is still running


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
    workspace: str = ""


# Union types for parsing
IncomingMessage = (
    AudioStart | AudioEnd | TextMessage | ImageMessage | Interrupt | Ping
    | SelectWorkspace | PermissionResponse
)

OutgoingMessage = (
    Transcription | ResponseDelta | ResponseEnd | ToolUse | ToolResult
    | TTSStart | TTSEnd | Error | Pong | WorkspaceList | WorkspaceSelected
    | PermissionRequest | PermissionResolved | ConversationHistory
    | SessionsStatus
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
