"""Per-connection session state and the connection-independent workspace pool.

Conversation history is owned by the Claude Agent SDK client (per workspace),
so the per-connection :class:`Session` only tracks transport-level state: audio
buffering and recording.

The live workspace agents live in a :class:`WorkspacePool` that *outlives any
single connection*. A turn started in one project keeps running even if the
phone disconnects (app closed, network blip, server-side only) — closing the app
never interrupts it. When a connection (re)attaches, only the *active* (on-screen)
runtime streams text/audio to the phone; the rest run in the background and
persist to their SDK transcript, which is replayed on switch-back or reconnect.

Because the pool outlives connections, *all* phone-bound output is routed through
the pool (``pool.send`` / ``pool.flush_delta`` / ``pool.send_audio``) rather than
captured against the connection that started the turn. A turn begun on a now-dead
connection therefore keeps streaming to whichever connection is currently
attached, with no stale-handler references.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from ..config import Settings
from .protocol import ResponseDelta, SessionsStatus, SessionStatusInfo

if TYPE_CHECKING:
    from ..claude.agent_engine import AgentSession

log = logging.getLogger(__name__)


@dataclass
class WorkspaceRuntime:
    """One live workspace: its agent plus the state of its (possibly still-
    running) turn. Several of these coexist in the pool, independent of any
    connection."""

    name: str
    path: str
    agent: "AgentSession" = field(repr=False)

    # The in-flight turn for this workspace, if any.
    response_task: asyncio.Task | None = field(default=None, repr=False)
    is_responding: bool = False
    # Set to stop the current turn early (barge-in / new prompt for THIS
    # workspace). Switching to another workspace does NOT set this.
    interrupted: bool = False

    # Full display text accumulated for the current turn, so the phone can be
    # repainted with progress-so-far when the user switches/reconnects to this
    # workspace mid-response.
    display_accum: str = ""
    # How many chars of display_accum have already been delivered to the phone
    # for the current turn. Lets repaint resend cleanly without dup/gap.
    sent_len: int = 0
    # Scratch buffer holding back a partial <speak> tag split across deltas.
    disp_buf: str = ""

    # The current turn's TTS queue (so permission prompts can be spoken).
    tts_queue: "asyncio.Queue[str | None] | None" = field(default=None, repr=False)

    # Outstanding tool-permission requests for THIS workspace, id -> Future[bool].
    pending_approvals: dict[str, asyncio.Future] = field(default_factory=dict, repr=False)
    # Display metadata per request id, kept so a background approval can be
    # surfaced to the phone when the user switches to this workspace.
    approval_meta: dict[str, dict] = field(default_factory=dict, repr=False)
    # Request ids already shown on the phone (timeout started).
    surfaced: set[str] = field(default_factory=set, repr=False)
    # Per-request auto-deny timer tasks, so they can be cancelled when an
    # approval is answered early (rather than leaving an orphan sleeping task).
    approval_timers: dict[str, asyncio.Task] = field(default_factory=dict, repr=False)

    # Last time this workspace did anything (turn started/ended). Drives idle
    # reaping so abandoned agents don't pile up subprocesses forever.
    last_activity: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.last_activity = time.time()

    def deny_pending_approvals(self) -> None:
        # Only resolve the futures — each blocked _request_permission has its own
        # finally that pops the tracking dicts and sends PermissionResolved if it
        # was surfaced. Clearing surfaced/meta here would drop that dismissal, so
        # an interrupt (barge-in) would leave the phone's approval overlay stuck.
        for fut in list(self.pending_approvals.values()):
            if not fut.done():
                fut.set_result(False)


class WorkspacePool:
    """Live workspace runtimes that outlive any single WebSocket connection.

    This is a single-user app: one phone connects at a time. The pool keeps each
    workspace's agent + in-flight turn alive across reconnects, so closing the
    app never interrupts a running turn — reopening just re-attaches and repaints.

    ``handler`` is the currently attached connection (or None when nothing is
    connected); ``active_name`` is the workspace on its screen. All phone-bound
    sends go through here so a turn started on an earlier connection streams to
    whatever connection is attached now.
    """

    def __init__(self) -> None:
        self.runtimes: dict[str, WorkspaceRuntime] = {}
        self.active_name: str | None = None
        # ConnectionHandler | None — duck-typed (needs .connected, .send_json,
        # .send_audio). Avoids importing the handler here.
        self.handler = None
        # Serializes phone-bound multi-message sequences (a turn's deltas vs. a
        # workspace activation repaint) so they can't interleave on the wire.
        self.lock = asyncio.Lock()

    @property
    def active(self) -> "WorkspaceRuntime | None":
        if self.active_name is None:
            return None
        return self.runtimes.get(self.active_name)

    def is_active(self, rt: WorkspaceRuntime) -> bool:
        h = self.handler
        return h is not None and h.connected and self.active_name == rt.name

    # --------------------------------------------------------------- attach
    def attach(self, handler) -> None:
        """A connection has come up. It becomes the live handler; the phone will
        re-select a workspace (triggering a repaint), so nothing is active yet."""
        self.handler = handler
        self.active_name = None

    def detach(self, handler) -> None:
        """A connection has gone away. Leave all runtimes running — only stop
        treating this handler as the live one (so background turns stop trying to
        stream to a dead socket). A newer connection may have already taken over,
        so only clear if this exact handler is still attached."""
        if self.handler is handler:
            self.handler = None
            self.active_name = None

    # ----------------------------------------------------------------- send
    async def send(self, rt: WorkspaceRuntime, msg) -> None:
        """Send one phone-bound message iff ``rt`` is the on-screen workspace.
        Stamps the workspace so the phone can ignore anything not meant for the
        page it's currently on."""
        async with self.lock:
            h = self.handler
            if h is not None and h.connected and self.active_name == rt.name:
                if hasattr(msg, "workspace"):
                    msg.workspace = rt.name
                await h.send_json(msg)

    async def send_audio(self, rt: WorkspaceRuntime, data: bytes) -> None:
        h = self.handler
        if h is not None and h.connected and self.active_name == rt.name:
            await h.send_audio(data)

    async def flush_delta(self, rt: WorkspaceRuntime) -> None:
        """Deliver any not-yet-sent display text for ``rt`` (only while it's the
        active workspace), advancing the sent cursor under the lock so a
        concurrent repaint can't dup or gap the stream."""
        async with self.lock:
            h = self.handler
            if h is None or not h.connected or self.active_name != rt.name:
                return
            new = rt.display_accum[rt.sent_len:]
            if new:
                await h.send_json(ResponseDelta(text=new, workspace=rt.name))
                rt.sent_len = len(rt.display_accum)

    # --------------------------------------------------------------- status
    def status_snapshot(self):
        """Build a live dashboard snapshot of every workspace (independent of
        which one is on screen)."""
        sessions = sorted(
            (
                SessionStatusInfo(
                    name=rt.name,
                    responding=rt.is_responding,
                    blocked=bool(rt.pending_approvals),
                )
                for rt in self.runtimes.values()
            ),
            key=lambda s: s.name.lower(),
        )
        return SessionsStatus(
            sessions=sessions,
            total=len(sessions),
            needs_you=sum(1 for s in sessions if s.blocked),
        )

    async def broadcast_status(self) -> None:
        """Push the dashboard snapshot to the attached connection (if any). Sent
        on every status transition; unconditional (not gated by active workspace)
        since it's connection-level, not per-workspace."""
        h = self.handler
        if h is not None and h.connected:
            await h.send_json(self.status_snapshot())

    # ------------------------------------------------------------- lifecycle
    async def close_all(self) -> None:
        """Close every agent (server shutdown). Cancels in-flight turns."""
        for rt in list(self.runtimes.values()):
            rt.interrupted = True
            rt.deny_pending_approvals()
            if rt.response_task and not rt.response_task.done():
                rt.response_task.cancel()
            try:
                await rt.agent.close()
            except Exception:
                log.exception("Error closing agent for %s", rt.name)
        self.runtimes.clear()
        self.active_name = None

    def start_reaper(self, interval: int = 300, max_idle: int = 3600) -> asyncio.Task:
        """Periodically close idle, *non-responding* workspaces so abandoned
        agents don't keep their subprocess alive forever. A running turn (which
        includes waiting on an approval) is never reaped — its agent resumes from
        the on-disk transcript next time the workspace is selected anyway."""
        async def _loop():
            while True:
                await asyncio.sleep(interval)
                now = time.time()
                stale = [
                    name for name, rt in self.runtimes.items()
                    if not rt.is_responding
                    and not rt.pending_approvals
                    and name != self.active_name
                    and now - rt.last_activity > max_idle
                ]
                for name in stale:
                    rt = self.runtimes.pop(name, None)
                    if rt is None:
                        continue
                    log.info("Reaping idle workspace %s", name)
                    try:
                        await rt.agent.close()
                    except Exception:
                        log.exception("Error reaping agent for %s", name)
                if stale:
                    await self.broadcast_status()
        return asyncio.create_task(_loop())


@dataclass
class Session:
    """Transport state for a single WebSocket connection (audio only — the live
    workspace agents live in the shared :class:`WorkspacePool`)."""

    session_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    settings: Settings = field(default_factory=Settings)

    # Audio buffering
    audio_buffer: bytearray = field(default_factory=bytearray)
    is_recording: bool = False

    # Timing
    created_at: float = field(default_factory=time.time)
    last_activity: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.last_activity = time.time()

    def clear_audio_buffer(self) -> None:
        self.audio_buffer.clear()


class SessionRegistry:
    """Tracks active connections for monitoring and cleanup."""

    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def add(self, session: Session) -> None:
        self._sessions[session.session_id] = session

    def remove(self, session_id: str) -> None:
        session = self._sessions.pop(session_id, None)
        if session:
            session.clear_audio_buffer()

    def get(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def clear(self) -> None:
        for sid in list(self._sessions):
            self.remove(sid)

    def __len__(self) -> int:
        return len(self._sessions)

    def start_cleanup(self, interval: int = 300, max_idle: int = 1800) -> asyncio.Task:
        """Periodically remove connection records idle longer than max_idle."""
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
