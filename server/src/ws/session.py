"""Per-connection session state.

Conversation history is owned by the Claude Agent SDK client (per workspace),
so the session only tracks transport-level state: audio buffering, the active
agent, and outstanding permission approvals.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from ..config import Settings

if TYPE_CHECKING:
    from ..claude.agent_engine import AgentSession

log = logging.getLogger(__name__)


@dataclass
class Session:
    """Holds all state for a single WebSocket connection."""

    session_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    settings: Settings = field(default_factory=Settings)

    # Active workspace + its Agent SDK client.
    workspace_name: str | None = None
    workspace_path: str | None = None
    agent: "AgentSession | None" = field(default=None, repr=False)

    # Audio buffering
    audio_buffer: bytearray = field(default_factory=bytearray)
    is_recording: bool = False

    # Response state
    is_responding: bool = False
    interrupted: bool = False

    # Outstanding tool-permission requests, keyed by request id -> Future[bool].
    pending_approvals: dict[str, asyncio.Future] = field(default_factory=dict, repr=False)

    # Timing
    created_at: float = field(default_factory=time.time)
    last_activity: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.last_activity = time.time()

    def clear_audio_buffer(self) -> None:
        self.audio_buffer.clear()

    def deny_pending_approvals(self) -> None:
        """Resolve any in-flight approval as denied (used on interrupt/cleanup)."""
        for fut in list(self.pending_approvals.values()):
            if not fut.done():
                fut.set_result(False)
        self.pending_approvals.clear()


class SessionRegistry:
    """Tracks active sessions for monitoring and cleanup."""

    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def add(self, session: Session) -> None:
        self._sessions[session.session_id] = session

    def remove(self, session_id: str) -> None:
        session = self._sessions.pop(session_id, None)
        if session:
            session.interrupted = True
            session.deny_pending_approvals()
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
