"""Per-connection session state."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from ..config import Settings

if TYPE_CHECKING:
    from ..claude.tool_executor import ToolExecutor

log = logging.getLogger(__name__)


@dataclass
class Session:
    """Holds all state for a single WebSocket connection."""

    session_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    settings: Settings = field(default_factory=Settings)

    # Active workspace
    workspace_name: str | None = None
    tool_executor: ToolExecutor | None = field(default=None, repr=False)

    # Claude conversation history: list of {"role": ..., "content": ...}
    conversation: list[dict] = field(default_factory=list)

    # Audio buffering
    audio_buffer: bytearray = field(default_factory=bytearray)
    is_recording: bool = False

    # TTS/response state
    is_responding: bool = False
    interrupted: bool = False

    # Cancellation for the current Claude response task
    response_task: asyncio.Task | None = field(default=None, repr=False)

    # Timing
    created_at: float = field(default_factory=time.time)
    last_activity: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.last_activity = time.time()

    def add_user_message(self, content: list[dict] | str) -> None:
        """Append a user message to conversation history."""
        if isinstance(content, str):
            content = [{"type": "text", "text": content}]
        self.conversation.append({"role": "user", "content": content})
        self._trim_history()

    def add_assistant_message(self, content: list[dict]) -> None:
        """Append an assistant message to conversation history."""
        self.conversation.append({"role": "assistant", "content": content})
        self._trim_history()

    def _trim_history(self) -> None:
        """Keep conversation within configured turn limit."""
        max_turns = self.settings.claude.max_conversation_turns
        # Each turn is 2 messages (user + assistant), keep some extra
        max_messages = max_turns * 2
        if len(self.conversation) > max_messages:
            # Remove oldest pairs, keeping at least the last max_messages
            excess = len(self.conversation) - max_messages
            self.conversation = self.conversation[excess:]

    def clear_audio_buffer(self) -> None:
        self.audio_buffer.clear()

    def cancel_response(self) -> None:
        """Cancel any in-flight Claude response."""
        self.interrupted = True
        if self.response_task and not self.response_task.done():
            self.response_task.cancel()

    def estimate_tokens(self) -> int:
        """Rough token estimate for conversation history (~4 chars per token)."""
        total_chars = 0
        for msg in self.conversation:
            content = msg.get("content", "")
            if isinstance(content, str):
                total_chars += len(content)
            elif isinstance(content, list):
                for block in content:
                    if isinstance(block, dict):
                        total_chars += len(block.get("text", ""))
                        total_chars += len(block.get("content", ""))
        return total_chars // 4

    def _trim_history(self) -> None:
        """Keep conversation within configured turn limit and token budget."""
        max_turns = self.settings.claude.max_conversation_turns
        max_messages = max_turns * 2

        # Trim by turn count
        if len(self.conversation) > max_messages:
            excess = len(self.conversation) - max_messages
            self.conversation = self.conversation[excess:]

        # Trim by estimated tokens (keep under ~100k tokens)
        max_tokens = 100_000
        while len(self.conversation) > 2 and self.estimate_tokens() > max_tokens:
            # Drop oldest pair (user + assistant)
            self.conversation = self.conversation[2:]


class SessionRegistry:
    """Tracks active sessions for monitoring and cleanup."""

    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def add(self, session: Session) -> None:
        self._sessions[session.session_id] = session

    def remove(self, session_id: str) -> None:
        session = self._sessions.pop(session_id, None)
        if session:
            session.cancel_response()
            session.conversation.clear()
            session.clear_audio_buffer()

    def get(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def clear(self) -> None:
        for sid in list(self._sessions):
            self.remove(sid)

    def __len__(self) -> int:
        return len(self._sessions)

    def start_cleanup(self, interval: int = 300, max_idle: int = 1800) -> asyncio.Task:
        """Periodically remove sessions idle for longer than max_idle seconds."""
        async def _cleanup_loop():
            while True:
                await asyncio.sleep(interval)
                now = time.time()
                stale = [
                    sid for sid, s in self._sessions.items()
                    if now - s.last_activity > max_idle
                ]
                for sid in stale:
                    log.info("Reaping stale session %s", sid)
                    self.remove(sid)
        return asyncio.create_task(_cleanup_loop())
