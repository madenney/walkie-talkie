"""WebSocket message type definitions.

Text frames carry JSON with a "type" field. Binary frames carry audio
with a 1-byte prefix: 0x01 = mic audio (phone→server), 0x02 = TTS audio
(server→phone).

Transcript content (user/assistant/tool messages, turn state, approvals) is
delivered as an append-only, per-workspace **event log**: each event has a
monotonic ``seq`` and the phone keeps a cursor. Live events stream as ``event``
/ ``event_delta`` frames; a ``sync`` frame replays the tail from a cursor
(switch, reconnect, cold open, or gap recovery). This makes a dropped/reordered
frame *recoverable* (the phone re-subscribes from its cursor) rather than
silently corrupting the transcript, and makes switching workspaces a pure
client-side render change instead of a server-side repaint.

Ephemeral, audio-coupled signals (TTS start/end), the dashboard snapshot, and
connection-level acks stay as their own control frames — they don't need replay.
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
    # The phone's current cursor for this workspace's event log (-1 = "I have
    # nothing, send me recent history"). The server answers with a `sync` frame.
    since: int = -1


class Subscribe(BaseModel):
    """Catch up a workspace's event log from a cursor — used for gap recovery
    when the phone sees a non-contiguous seq mid-stream."""
    type: Literal["subscribe"] = "subscribe"
    workspace: str
    since: int = -1


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


class ReplayLast(BaseModel):
    """Phone asks the server to speak the active workspace's last response again
    (e.g. to hear a reply that arrived while you were on another project)."""
    type: Literal["replay_last"] = "replay_last"


class CloseWorkspace(BaseModel):
    """Stop a live session: close its agent and drop it from the pool. The
    conversation transcript is kept, so reopening the project resumes it."""
    type: Literal["close_workspace"] = "close_workspace"
    name: str


class Deactivate(BaseModel):
    """Phone moved to the home/sessions list — no workspace is on screen. Every
    workspace keeps running in the background and its events still stream (cheap
    JSON), but no TTS audio is sent until a workspace is selected again."""
    type: Literal["deactivate"] = "deactivate"


# --- Server → Phone: the event log ---
#
# An event is a plain dict: {"seq": int, "kind": str, ...payload}. Kinds:
#   user_msg        {text}
#   assistant_text  {text}                 # grows via event_delta
#   tool_use        {tool_id, tool_name, input}
#   tool_result     {tool_id, tool_name, success, output}
#   turn_state      {responding: bool}
#   permission_req  {id, tool_name, summary, detail}
#   permission_res  {id, approved}

class EventFrame(BaseModel):
    """One newly-appended event for a workspace's log."""
    type: Literal["event"] = "event"
    workspace: str
    event: dict[str, Any]


class EventDelta(BaseModel):
    """Append streamed text to an already-sent assistant_text event (by seq)."""
    type: Literal["event_delta"] = "event_delta"
    workspace: str
    seq: int
    text: str


class Sync(BaseModel):
    """Replay a workspace's event-log tail from a cursor.

    ``reset`` means the phone had a gap (or no data) and should REPLACE its view
    for this workspace with ``events``; otherwise it appends them. ``head_seq``
    is the highest seq the server holds, so the phone can validate its cursor.
    """
    type: Literal["sync"] = "sync"
    workspace: str
    events: list[dict[str, Any]] = []
    reset: bool = False
    head_seq: int = -1


# --- Server → Phone: ephemeral control frames ---

class TTSStart(BaseModel):
    type: Literal["tts_start"] = "tts_start"
    format: str = "mp3"
    # The assistant_text event seq being read aloud, so the phone highlights the
    # right bubble (-1 = fall back to the last assistant message).
    target_seq: int = -1
    workspace: str = ""


class TTSEnd(BaseModel):
    type: Literal["tts_end"] = "tts_end"
    workspace: str = ""


class Error(BaseModel):
    type: Literal["error"] = "error"
    message: str
    code: str = "unknown"
    # Stamped when tied to a specific workspace's turn; blank for connection-level
    # errors (parse errors, no workspace selected), which always show.
    workspace: str = ""


class Pong(BaseModel):
    type: Literal["pong"] = "pong"


class WorkspaceList(BaseModel):
    type: Literal["workspace_list"] = "workspace_list"
    workspaces: list[dict[str, str]]  # [{name, path}]


class WorkspaceSelected(BaseModel):
    """Ack that a workspace is now the on-screen one (drives audio routing). The
    scrollback + responding state arrive via the event log (`sync`)."""
    type: Literal["workspace_selected"] = "workspace_selected"
    name: str
    path: str


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


# Union types for parsing
IncomingMessage = (
    AudioStart | AudioEnd | TextMessage | ImageMessage | Interrupt | Ping
    | SelectWorkspace | Subscribe | PermissionResponse | ReplayLast
    | CloseWorkspace | Deactivate
)

OutgoingMessage = (
    EventFrame | EventDelta | Sync | TTSStart | TTSEnd | Error | Pong
    | WorkspaceList | WorkspaceSelected | SessionsStatus
)

INCOMING_TYPES: dict[str, type[BaseModel]] = {
    "audio_start": AudioStart,
    "audio_end": AudioEnd,
    "text_message": TextMessage,
    "image_message": ImageMessage,
    "interrupt": Interrupt,
    "ping": Ping,
    "select_workspace": SelectWorkspace,
    "subscribe": Subscribe,
    "permission_response": PermissionResponse,
    "replay_last": ReplayLast,
    "close_workspace": CloseWorkspace,
    "deactivate": Deactivate,
}


def parse_incoming(data: dict[str, Any]) -> IncomingMessage:
    msg_type = data.get("type")
    cls = INCOMING_TYPES.get(msg_type)  # type: ignore[arg-type]
    if cls is None:
        raise ValueError(f"Unknown message type: {msg_type}")
    return cls(**data)
