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
the pool (``pool.emit`` / ``pool.send_text_delta`` / ``pool.send_audio``) rather
than captured against the connection that started the turn. A turn begun on a
now-dead connection therefore keeps appending to its event log and streaming to
whichever connection is currently attached, with no stale-handler references.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from ..config import Settings
from .protocol import (
    EventDelta,
    EventFrame,
    SessionsStatus,
    SessionStatusInfo,
    Sync,
)

if TYPE_CHECKING:
    from ..claude.agent_engine import AgentSession

log = logging.getLogger(__name__)

# Cap each workspace's in-memory event log so a long-lived session doesn't grow
# unbounded. Older events live on in the SDK transcript; a phone asking for a
# cursor below the retained window gets a `reset` sync rebuilt from there.
LOG_CAP = 600
# How many display messages to seed from a resumed transcript (the scrollback
# the phone shows on reopen). Older than this isn't replayed.
SEED_CAP = 300


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

    # Append-only event log + the next seq to assign. This is the authoritative
    # transcript stream the phone renders from; it replaces the old repaint
    # bookkeeping (display_accum/sent_len). Each event: {"seq", "kind", ...}.
    event_log: list[dict] = field(default_factory=list, repr=False)
    next_seq: int = 0
    # The spoken (<speak>) text of the most recent completed turn, so the phone
    # can replay it on demand — e.g. to hear a reply that finished while you were
    # looking at another project.
    last_spoken: str = ""

    # The current turn's TTS queue (so permission prompts can be spoken).
    tts_queue: "asyncio.Queue[str | None] | None" = field(default=None, repr=False)

    # Outstanding tool-permission requests for THIS workspace, id -> Future[bool].
    pending_approvals: dict[str, asyncio.Future] = field(default_factory=dict, repr=False)
    # Per-request auto-deny timer tasks, so they can be cancelled when an
    # approval is answered early (rather than leaving an orphan sleeping task).
    approval_timers: dict[str, asyncio.Task] = field(default_factory=dict, repr=False)

    # Last time this workspace did anything (turn started/ended). Drives idle
    # reaping so abandoned agents don't pile up subprocesses forever.
    last_activity: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.last_activity = time.time()

    def append_event(self, kind: str, **data) -> dict:
        """Append one event to the log (assigning the next seq) and return it.
        Pure state mutation — sending it to the phone is the pool's job."""
        ev = {"seq": self.next_seq, "kind": kind, **data}
        self.next_seq += 1
        self.event_log.append(ev)
        if len(self.event_log) > LOG_CAP:
            # Drop the oldest; the phone re-syncs (reset) if it asks below the
            # retained window.
            del self.event_log[: len(self.event_log) - LOG_CAP]
        return ev

    def seed_events(self, history: list[dict]) -> None:
        """Seed the log from a resumed SDK transcript so reopening shows
        scrollback. Each history item is {role, text, tool_name, tool_output,
        success}; map it to the matching event kind. Only called once, before
        any live turn, so seqs start at 0."""
        for h in history[-SEED_CAP:]:
            role = h.get("role")
            if role == "user":
                self.append_event("user_msg", text=h.get("text", ""))
            elif role == "assistant":
                self.append_event("assistant_text", text=h.get("text", ""))
            elif role == "tool":
                if h.get("tool_output") is not None or h.get("success") is not None:
                    self.append_event(
                        "tool_result",
                        tool_id="",
                        tool_name=h.get("tool_name") or "",
                        success=bool(h.get("success")),
                        output=h.get("tool_output") or "",
                    )
                else:
                    self.append_event(
                        "tool_use",
                        tool_id="",
                        tool_name=h.get("tool_name") or "",
                        input={},
                    )

    def deny_pending_approvals(self) -> None:
        # Only resolve the futures — each blocked _request_permission has its own
        # finally that pops the tracking dict, cancels the timer, and appends a
        # permission_res event so the phone dismisses the overlay.
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
        """A connection has come up. It becomes the live handler; the phone
        re-selects its on-screen workspace and re-subscribes from its cursor."""
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

    # ----------------------------------------------------------------- events
    async def emit(self, rt: WorkspaceRuntime, kind: str, **data) -> dict:
        """Append an event to ``rt``'s log and stream it to the phone.

        Unlike the old gated `send`, this streams for EVERY workspace (cheap
        JSON), not just the on-screen one — so a background turn stays live on
        the phone and switching to it needs no repaint. Frame delivery is
        idempotent by seq on the phone, so concurrent turns / overlapping syncs
        can't corrupt anything. (TTS *audio*, which is heavy, still goes only to
        the active workspace — see send_audio.)"""
        ev = rt.append_event(kind, **data)
        h = self.handler
        if h is not None and h.connected:
            await h.send_json(EventFrame(workspace=rt.name, event=ev))
        return ev

    async def send_text_delta(self, rt: WorkspaceRuntime, seq: int, text: str) -> None:
        """Stream more text into an already-emitted assistant_text event. The
        caller has already appended ``text`` to the stored event's payload."""
        h = self.handler
        if h is not None and h.connected:
            await h.send_json(EventDelta(workspace=rt.name, seq=seq, text=text))

    def build_sync(self, rt: WorkspaceRuntime, since: int) -> Sync:
        """Build the catch-up frame for a phone at cursor ``since``.

        Contiguous (``since`` within the retained window) → the tail after it,
        appended. Otherwise (fresh cursor or a gap below the window) → the whole
        retained log with ``reset`` so the phone replaces its view."""
        log_ = rt.event_log
        head = rt.next_seq - 1
        if not log_:
            # No events yet. A phone claiming a cursor (since >= 0) is stale —
            # e.g. the server restarted with a fresh log — so tell it to reset.
            return Sync(workspace=rt.name, events=[], reset=(since != head), head_seq=head)
        earliest = log_[0]["seq"]
        # Reset on a gap below the retained window OR a cursor ahead of our head
        # (server restarted; the phone's old, higher cursor is stale).
        if since < 0 or since < earliest - 1 or since > head:
            return Sync(workspace=rt.name, events=list(log_), reset=True, head_seq=head)
        return Sync(
            workspace=rt.name,
            events=[e for e in log_ if e["seq"] > since],
            reset=False,
            head_seq=head,
        )

    async def send_sync(self, rt: WorkspaceRuntime, since: int) -> None:
        h = self.handler
        if h is not None and h.connected:
            await h.send_json(self.build_sync(rt, since))

    async def send_audio(self, rt: WorkspaceRuntime, data: bytes) -> None:
        h = self.handler
        if h is not None and h.connected and self.active_name == rt.name:
            await h.send_audio(data)

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
