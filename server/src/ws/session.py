"""Per-connection session state."""

from __future__ import annotations

import asyncio
import time
import uuid
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from ..config import Settings

if TYPE_CHECKING:
    from ..claude.tool_executor import ToolExecutor


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
