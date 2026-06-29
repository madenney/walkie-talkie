"""WebSocket endpoint and message routing.

Each Claude turn runs as a background task so the receive loop stays live during
a response — that's what lets a tool-permission request round-trip to the phone
(and what makes barge-in interrupts responsive).

A single connection keeps several workspaces alive at once: one
``WorkspaceRuntime`` per workspace, each able to run its own turn concurrently.
Only the *active* (on-screen) runtime streams text/audio to the phone; the others
keep working in the background and persist to their SDK transcript, which is
replayed when the user switches back. Switching workspaces never interrupts a
running turn — only a new prompt or barge-in for that same workspace does.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
import uuid
from pathlib import Path
from typing import TYPE_CHECKING, Any

from fastapi import WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState

from ..config import Settings, WorkspaceConfig
from ..claude.agent_engine import AgentSession
from ..claude.session_store import SessionStore
from ..claude.transcript import load_history
from .protocol import (
    AudioEnd,
    AudioPrefix,
    AudioStart,
    CloseWorkspace,
    Deactivate,
    Error,
    ImageMessage,
    Interrupt,
    PermissionResponse,
    Ping,
    Pong,
    ReplayLast,
    SelectWorkspace,
    Subscribe,
    TextMessage,
    TTSEnd,
    TTSStart,
    WorkspaceList,
    WorkspaceSelected,
    parse_incoming,
)
from .session import Session, WorkspacePool, WorkspaceRuntime

if TYPE_CHECKING:
    from ..stt.base import STTEngine
    from ..tts.base import TTSEngine

log = logging.getLogger(__name__)

# Extension guess for attached images saved to disk for the Read tool.
_IMG_EXT = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}


class ConnectionHandler:
    """Manages a single WebSocket connection."""

    def __init__(
        self,
        ws: WebSocket,
        session: Session,
        pool: WorkspacePool,
        settings: Settings,
        stt_engine: STTEngine | None = None,
        tts_engine: TTSEngine | None = None,
        workspaces: list[WorkspaceConfig] | None = None,
        session_store: "SessionStore | None" = None,
    ) -> None:
        self.ws = ws
        self.session = session
        # Shared, connection-independent pool of live workspace agents. Turns
        # survive this connection going away; all phone-bound sends route through
        # the pool so they reach whichever connection is attached now.
        self.pool = pool
        self.settings = settings
        self.stt = stt_engine
        self.tts = tts_engine
        self.workspaces = {w.name: w for w in (workspaces or [])}
        self.session_store = session_store

        self.gated_tools = settings.safety.require_approval
        self.approval_timeout = settings.safety.approval_timeout
        self.model = settings.claude.model or None

        self._disconnected = False
        # In-flight on-demand replay of a workspace's last spoken response.
        self._replay_task: asyncio.Task | None = None
        # Serializes raw socket writes. Multiple workspace turns stream events
        # concurrently through one socket; each frame is atomic, but Starlette's
        # send isn't safe under truly concurrent coroutines, so guard the write.
        self._send_lock = asyncio.Lock()

    # ------------------------------------------------------------------ sends

    @property
    def connected(self) -> bool:
        return (
            not self._disconnected
            and self.ws.application_state == WebSocketState.CONNECTED
        )

    async def send_json(self, msg) -> None:
        if not self.connected:
            return
        try:
            async with self._send_lock:
                await self.ws.send_text(msg.model_dump_json())
        except (WebSocketDisconnect, RuntimeError):
            self._mark_disconnected()

    async def send_audio(self, data: bytes) -> None:
        if not self.connected:
            return
        try:
            async with self._send_lock:
                await self.ws.send_bytes(bytes([AudioPrefix.TTS]) + data)
        except (WebSocketDisconnect, RuntimeError):
            self._mark_disconnected()

    def _mark_disconnected(self) -> None:
        if not self._disconnected:
            self._disconnected = True
            log.info("Session %s client disconnected mid-send", self.session.session_id)
        # Detach from the pool so background turns stop streaming to a dead
        # socket — but DON'T interrupt them. They keep running; reconnecting
        # re-attaches and repaints. This is what makes "closing the app doesn't
        # interrupt the turns" hold.
        self.pool.detach(self)

    # ------------------------------------------------------------------ loop

    async def handle(self) -> None:
        sid = self.session.session_id
        log.info("Session %s connected", sid)

        # Become the live connection for the shared pool. Any workspace already
        # running from a previous connection stays alive; the phone re-selects
        # its on-screen workspace and re-subscribes from its cursor (see
        # _activate / build_sync).
        self.pool.attach(self)

        if self.workspaces:
            await self.send_json(WorkspaceList(
                workspaces=[{"name": w.name, "path": w.path} for w in self.workspaces.values()]
            ))

        # Initial dashboard snapshot — on a reconnect this shows the sessions that
        # kept running while the app was closed.
        await self.pool.broadcast_status()

        try:
            while True:
                raw = await self.ws.receive()
                self.session.touch()

                if raw["type"] == "websocket.receive":
                    if "text" in raw and raw["text"]:
                        await self._handle_text(raw["text"])
                    elif "bytes" in raw and raw["bytes"]:
                        await self._handle_binary(raw["bytes"])
                elif raw["type"] == "websocket.disconnect":
                    break
        except WebSocketDisconnect:
            log.info("Session %s disconnected", sid)
        except Exception:
            log.exception("Session %s error", sid)
        finally:
            # Just detach — the workspace runtimes (and any in-flight turns)
            # live on in the pool so they survive the app closing. The pool's
            # idle reaper (and server shutdown) is what eventually closes agents.
            self._cancel_replay()
            self.pool.detach(self)
            log.info("Session %s detached", sid)

    async def _handle_text(self, text: str) -> None:
        try:
            data = json.loads(text)
            msg = parse_incoming(data)
        except (json.JSONDecodeError, ValueError) as e:
            log.warning("Parse error: %s (raw: %s)", e, text[:200])
            await self.send_json(Error(message=str(e), code="parse_error"))
            return

        log.info("Session %s received: %s", self.session.session_id, type(msg).__name__)

        match msg:
            case Ping():
                await self.send_json(Pong())
            case SelectWorkspace(name=name, since=since):
                await self._handle_select_workspace(name, since)
            case Subscribe(workspace=ws, since=since):
                rt = self.pool.runtimes.get(ws)
                if rt is not None:
                    await self.pool.send_sync(rt, since)
            case Deactivate():
                # Phone went to the home list — keep every workspace running but
                # stop streaming to a screen that isn't showing one.
                self.pool.active_name = None
            case TextMessage(text=user_text):
                await self._handle_user_input(user_text)
            case ImageMessage():
                await self._handle_image(msg)
            case AudioStart():
                self.session.is_recording = True
                self.session.clear_audio_buffer()
            case AudioEnd():
                self.session.is_recording = False
                # Transcribe off the receive loop so a slow STT can't freeze
                # message handling.
                asyncio.create_task(self._handle_audio_end())
            case Interrupt():
                await self._interrupt_active()
            case PermissionResponse(id=pid, approved=approved):
                self._resolve_approval(pid, approved)
            case ReplayLast():
                self._handle_replay_last()
            case CloseWorkspace(name=name):
                # Off the receive loop: interrupt + agent teardown can take a few
                # seconds and shouldn't freeze message handling.
                asyncio.create_task(self._handle_close_workspace(name))

    async def _handle_binary(self, data: bytes) -> None:
        if len(data) < 2:
            return
        if data[0] == AudioPrefix.MIC and self.session.is_recording:
            self.session.audio_buffer.extend(data[1:])

    # ---------------------------------------------------------------- turns

    async def _handle_user_input(self, text: str, image_note: str | None = None) -> None:
        # New input — stop any replay so it can't talk over the turn's TTS.
        self._cancel_replay()
        rt = self.pool.active
        if rt is None:
            await self.send_json(Error(
                message="No workspace selected yet.", code="no_workspace"
            ))
            return

        # If a tool is waiting on approval in THIS workspace, treat this input as
        # the yes/no answer (spoken or typed) rather than a new prompt.
        if rt.pending_approvals:
            decision = _classify_yes_no(text)
            if decision is True:
                self._resolve_all_approvals(rt, True)
            elif decision is False:
                self._resolve_all_approvals(rt, False)
            else:
                self._speak(rt, "Sorry, I didn't catch that. Say yes or no.")
            return

        # One turn at a time *per workspace*: interrupt only this workspace's
        # in-flight response (other workspaces keep running).
        await self._interrupt_runtime(rt)

        # Log the user's message as an event (the phone renders it from here
        # rather than echoing optimistically, so there's one source of truth).
        # The image note is for the agent only — not shown in the bubble.
        await self.pool.emit(rt, "user_msg", text=text)

        prompt = text if not image_note else f"{text}\n\n{image_note}"
        rt.interrupted = False
        rt.is_responding = True
        rt.touch()
        rt.response_task = asyncio.create_task(self._run_turn(rt, prompt))
        await self.pool.broadcast_status()

    async def _run_turn(self, rt: WorkspaceRuntime, prompt: str) -> None:
        """Run one Claude turn for ``rt``: stream text → TTS, surface tool calls.

        All transcript output is appended to ``rt``'s event log and streamed to
        the phone (for every workspace, not just the on-screen one — switching is
        a client render change). Only TTS *audio* is gated to the active
        workspace; a backgrounded turn still streams its text/tool events.
        """
        # Display-text "hold" buffer: keeps back a partial <speak> tag split
        # across deltas until the rest arrives. ``cur_text_ev`` is the
        # assistant_text event currently being streamed (None between text
        # blocks / before any); we mutate its "text" in place as deltas arrive.
        disp_hold = ""
        cur_text_ev: dict | None = None
        tts_buffer = ""
        in_speak = False
        speak_accum = ""
        first_chunk_sent = False
        # The spoken phrases of this turn, kept so the phone can replay the
        # response on demand (set into rt.last_spoken when the turn finishes).
        turn_spoken: list[str] = []
        tts_queue: asyncio.Queue[str | None] = asyncio.Queue()
        tts_task: asyncio.Task | None = None
        if self.tts:
            tts_task = asyncio.create_task(self._tts_consumer(rt, tts_queue))
            rt.tts_queue = tts_queue

        await self.pool.emit(rt, "turn_state", responding=True)
        try:
            await rt.agent.query(prompt)

            async for event in rt.agent.stream():
                if rt.interrupted:
                    break

                etype = event["type"]
                if etype == "text_delta":
                    delta = event["text"]
                    # Strip <speak> tags for display — robust to a tag split across
                    # deltas: remove complete tags, then hold back any trailing
                    # partial tag until the rest of it arrives.
                    disp_hold += delta
                    disp_buf = disp_hold.replace("<speak>", "").replace("</speak>", "")
                    hold = 0
                    for tag in ("<speak>", "</speak>"):
                        for k in range(len(tag) - 1, 0, -1):
                            if disp_buf.endswith(tag[:k]):
                                hold = max(hold, k)
                                break
                    if hold:
                        display, disp_buf = disp_buf[:-hold], disp_buf[-hold:]
                    else:
                        display, disp_buf = disp_buf, ""
                    disp_hold = disp_buf
                    if display:
                        if cur_text_ev is None:
                            cur_text_ev = await self.pool.emit(
                                rt, "assistant_text", text=display
                            )
                        else:
                            cur_text_ev["text"] += display
                            await self.pool.send_text_delta(
                                rt, cur_text_ev["seq"], display
                            )

                    if self.tts:
                        tts_buffer += delta
                        while True:
                            if not in_speak:
                                idx = tts_buffer.find("<speak>")
                                if idx == -1:
                                    lt = tts_buffer.rfind("<")
                                    tts_buffer = tts_buffer[lt:] if (lt != -1 and lt >= len(tts_buffer) - 6) else ""
                                    break
                                in_speak = True
                                first_chunk_sent = False
                                speak_accum = ""
                                tts_buffer = tts_buffer[idx + 7:]

                            close_idx = tts_buffer.find("</speak>")
                            if close_idx != -1:
                                speak_accum += tts_buffer[:close_idx]
                                remaining = speak_accum.strip()
                                if remaining:
                                    await tts_queue.put(remaining)
                                    turn_spoken.append(remaining)
                                in_speak = False
                                speak_accum = ""
                                tts_buffer = tts_buffer[close_idx + 8:]
                                continue

                            lt = tts_buffer.rfind("<")
                            if lt != -1 and lt >= len(tts_buffer) - 7:
                                speak_accum += tts_buffer[:lt]
                                tts_buffer = tts_buffer[lt:]
                            else:
                                speak_accum += tts_buffer
                                tts_buffer = ""

                            if not first_chunk_sent:
                                # First chunk = first natural phrase, for low-latency TTS.
                                m = re.search(r"[,;:—.!?]\s", speak_accum)
                                if m:
                                    phrase = speak_accum[: m.end()].strip()
                                    if phrase:
                                        await tts_queue.put(phrase)
                                        turn_spoken.append(phrase)
                                    speak_accum = speak_accum[m.end():]
                                    first_chunk_sent = True
                            else:
                                while True:
                                    m = re.search(r"[.!?]\s", speak_accum)
                                    if not m:
                                        break
                                    sentence = speak_accum[: m.end()].strip()
                                    if sentence:
                                        await tts_queue.put(sentence)
                                        turn_spoken.append(sentence)
                                    speak_accum = speak_accum[m.end():]
                            break

                elif etype == "tool_use":
                    # A tool call ends the current text block; the next text
                    # opens a fresh bubble.
                    cur_text_ev = None
                    await self.pool.emit(
                        rt, "tool_use",
                        tool_id=event["tool_id"],
                        tool_name=event["tool_name"],
                        input=event.get("input", {}) or {},
                    )

                elif etype == "tool_result":
                    await self.pool.emit(
                        rt, "tool_result",
                        tool_id=event["tool_id"],
                        tool_name=event.get("tool_name", ""),
                        success=event["success"],
                        output=(event.get("output") or "")[:2000],
                    )

                elif etype == "response_complete":
                    self._persist_session_id(rt)

        except asyncio.CancelledError:
            log.info("Turn cancelled for workspace %s", rt.name)
        except Exception as e:
            log.exception("Turn error for workspace %s", rt.name)
            await self.send_json(Error(message=str(e), code="agent_error", workspace=rt.name))
        finally:
            if tts_task:
                await tts_queue.put(None)
                try:
                    await tts_task
                except Exception:
                    pass
            rt.tts_queue = None
            rt.is_responding = False
            # Closing turn_state — appended last so the phone derives "not
            # responding" (and stops the streaming cursor) from the log tail.
            await self.pool.emit(rt, "turn_state", responding=False)
            # Remember this turn's spoken text for on-demand replay (keep the prior
            # one if this turn produced no speech, e.g. a tool-only turn).
            joined = " ".join(turn_spoken).strip()
            if joined:
                rt.last_spoken = joined
            rt.touch()
            self._persist_session_id(rt)
            await self.pool.broadcast_status()

    def _persist_session_id(self, rt: WorkspaceRuntime) -> None:
        """Remember this workspace's SDK session id so it can be resumed later."""
        if not self.session_store:
            return
        if rt.agent.session_id and rt.path:
            self.session_store.set(rt.path, rt.agent.session_id)

    async def _tts_consumer(self, rt: WorkspaceRuntime, queue: asyncio.Queue[str | None]) -> None:
        started = False
        try:
            while True:
                text = await queue.get()
                if text is None:
                    break
                # Only speak while this workspace is the one on screen.
                if rt.interrupted or not self.pool.is_active(rt):
                    continue
                if not started:
                    # Highlight the assistant bubble currently being read.
                    await self.send_json(TTSStart(
                        target_seq=self._last_assistant_seq(rt), workspace=rt.name,
                    ))
                    started = True
                try:
                    async for chunk in self.tts.synthesize(text):
                        if rt.interrupted or not self.pool.is_active(rt):
                            break
                        await self.pool.send_audio(rt, chunk)
                except Exception:
                    log.exception("TTS error")
                    break
            if started and self.pool.is_active(rt):
                await self.send_json(TTSEnd(workspace=rt.name))
        except (WebSocketDisconnect, RuntimeError):
            log.debug("TTS consumer: client disconnected")

    @staticmethod
    def _last_assistant_seq(rt: WorkspaceRuntime) -> int:
        """seq of the most recent assistant_text event (the bubble being read),
        or -1 if none — so the phone can highlight the right message."""
        for ev in reversed(rt.event_log):
            if ev.get("kind") == "assistant_text":
                return ev["seq"]
        return -1

    async def _interrupt_active(self) -> None:
        self._cancel_replay()
        rt = self.pool.active
        if rt is not None:
            await self._interrupt_runtime(rt)

    async def _interrupt_runtime(self, rt: WorkspaceRuntime) -> None:
        """Stop ``rt``'s in-flight turn (and unblock a hook waiting on approval)."""
        task = rt.response_task
        rt.interrupted = True
        rt.deny_pending_approvals()
        try:
            await rt.agent.interrupt()
        except Exception:
            log.exception("interrupt failed for %s", rt.name)
        if task is not None and not task.done():
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=5)
            except (asyncio.TimeoutError, asyncio.CancelledError, Exception):
                task.cancel()
        rt.response_task = None
        log.info("Workspace %s interrupted", rt.name)

    # ----------------------------------------------------------- permissions

    def _make_permission_cb(self, name: str):
        """Build the per-workspace permission callback bound to its name."""
        async def _cb(tool_name: str, tool_input: dict) -> bool:
            return await self._request_permission(name, tool_name, tool_input)
        return _cb

    async def _request_permission(self, name: str, tool_name: str, tool_input: dict) -> bool:
        """Hook callback: ask the phone for yes/no on a gated tool.

        The request is appended to the workspace's event log (a permission_req
        event) so it surfaces on that project's page wherever the phone is —
        switching to it replays the still-open request from the log. The matching
        permission_res event (on approve/deny/timeout) dismisses it.
        """
        rt = self.pool.runtimes.get(name)
        if rt is None:
            return False
        pid = uuid.uuid4().hex[:12]
        summary, detail, _spoken = _describe_tool(tool_name, tool_input)
        loop = asyncio.get_event_loop()
        fut: asyncio.Future = loop.create_future()
        rt.pending_approvals[pid] = fut
        # Log the request — streams to the phone (which buzzes; no spoken prompt).
        await self.pool.emit(
            rt, "permission_req",
            id=pid, tool_name=tool_name, summary=summary, detail=detail,
        )
        rt.approval_timers[pid] = asyncio.create_task(self._approval_timeout(rt, pid))
        # This workspace is now blocked on the user — reflect it on the dashboard.
        await self.pool.broadcast_status()

        approved = False
        try:
            approved = await fut
            return approved
        finally:
            rt.pending_approvals.pop(pid, None)
            timer = rt.approval_timers.pop(pid, None)
            if timer is not None:
                timer.cancel()
            # Settle it in the log, however it was answered — dismisses the phone.
            await self.pool.emit(rt, "permission_res", id=pid, approved=approved)
            await self.pool.broadcast_status()

    async def _approval_timeout(self, rt: WorkspaceRuntime, pid: str) -> None:
        await asyncio.sleep(self.approval_timeout)
        fut = rt.pending_approvals.get(pid)
        if fut is not None and not fut.done():
            log.info("Approval %s timed out → deny", pid)
            fut.set_result(False)

    def _resolve_approval(self, pid: str, approved: bool) -> None:
        for rt in self.pool.runtimes.values():
            fut = rt.pending_approvals.get(pid)
            if fut is not None and not fut.done():
                fut.set_result(approved)
                return

    def _resolve_all_approvals(self, rt: WorkspaceRuntime, approved: bool) -> None:
        """Answer every outstanding approval in ``rt`` (voice yes/no isn't tied
        to an id)."""
        for fut in list(rt.pending_approvals.values()):
            if not fut.done():
                fut.set_result(approved)
        log.info("Voice/text resolved approvals → %s", "approve" if approved else "deny")

    def _speak(self, rt: WorkspaceRuntime, text: str) -> None:
        q = rt.tts_queue
        if q is not None and text and self.pool.is_active(rt):
            try:
                q.put_nowait(text)
            except Exception:
                pass

    # ----------------------------------------------------------- workspaces

    async def _handle_select_workspace(self, name: str, since: int = -1) -> None:
        ws_config = self.workspaces.get(name)
        if ws_config is None:
            await self.send_json(Error(
                message=f"Unknown workspace: {name}", code="invalid_workspace"
            ))
            return

        # Already live (including surviving a previous connection)? Just bring it
        # to the foreground and catch the phone up from its cursor — never
        # interrupt it.
        existing = self.pool.runtimes.get(name)
        if existing is not None:
            await self._activate(existing, since)
            return

        # First time on this connection: spin up a fresh agent for it.
        resume_id = self.session_store.get(ws_config.path) if self.session_store else None

        async def _make_agent(resume: str | None) -> AgentSession:
            a = AgentSession(
                cwd=ws_config.path,
                gated_tools=self.gated_tools,
                on_permission=self._make_permission_cb(name),
                model=self.model,
                resume=resume,
            )
            await a.start()
            return a

        try:
            agent = await _make_agent(resume_id)
        except Exception as e:
            # A stale/missing transcript makes resume fail — fall back to a fresh
            # session rather than leaving the workspace unusable.
            if resume_id:
                log.warning("Resume %s failed (%s); starting fresh session", resume_id, e)
                if self.session_store:
                    self.session_store.clear(ws_config.path)
                try:
                    agent = await _make_agent(None)
                except Exception as e2:
                    log.exception("Failed to start agent for workspace %s", name)
                    await self.send_json(Error(message=f"Failed to start agent: {e2}", code="agent_start"))
                    return
            else:
                log.exception("Failed to start agent for workspace %s", name)
                await self.send_json(Error(message=f"Failed to start agent: {e}", code="agent_start"))
                return

        rt = WorkspaceRuntime(name=name, path=ws_config.path, agent=agent)
        # Seed the event log from the resumed transcript (off the event loop —
        # parsing a long JSONL shouldn't stall other workspaces' streams) so the
        # phone gets scrollback on open.
        if rt.agent.session_id:
            try:
                history = await asyncio.to_thread(load_history, rt.agent.session_id)
                rt.seed_events(history)
            except Exception:
                log.exception("Failed to seed history for %s", rt.agent.session_id)
        self.pool.runtimes[name] = rt
        log.info("Session %s → workspace %s (%s)", self.session.session_id, name, ws_config.path)
        await self._activate(rt, since)
        # A new live session exists now — update the dashboard.
        await self.pool.broadcast_status()

    async def _handle_close_workspace(self, name: str) -> None:
        """Stop a live session: interrupt any turn, close the agent, drop it from
        the pool. The session id is kept in the store, so reopening resumes it."""
        rt = self.pool.runtimes.get(name)
        if rt is None:
            return
        await self._interrupt_runtime(rt)
        try:
            await rt.agent.close()
        except Exception:
            log.exception("Error closing agent for %s", name)
        self.pool.runtimes.pop(name, None)
        if self.pool.active_name == name:
            self.pool.active_name = None
        log.info("Closed workspace %s (transcript kept for resume)", name)
        await self.pool.broadcast_status()

    async def _activate(self, rt: WorkspaceRuntime, since: int) -> None:
        """Bring ``rt`` on-screen (it now routes TTS audio) and catch the phone's
        event-log cursor up from ``since``.

        No lock, no disk I/O, no repaint bookkeeping: scrollback, in-flight turn
        progress, and any open approval all live in the event log, so a single
        `sync` from the phone's cursor delivers exactly what it's missing. This
        is also the reconnect path — the runtime may have been running the whole
        time the app was closed; its events are all in the log."""
        self.pool.active_name = rt.name
        await self.send_json(WorkspaceSelected(name=rt.name, path=rt.path))
        await self.pool.send_sync(rt, since)

    # ---------------------------------------------------------------- audio

    async def _handle_audio_end(self) -> None:
        audio_data = bytes(self.session.audio_buffer)
        self.session.clear_audio_buffer()
        if not audio_data or not self.stt:
            if not self.stt:
                await self.send_json(Error(message="STT not available", code="stt_unavailable"))
            return
        try:
            text = await self.stt.transcribe(
                audio_data, sample_rate=self.session.settings.audio.sample_rate,
            )
        except Exception:
            log.exception("STT error")
            await self.send_json(Error(message="Transcription failed", code="stt_error"))
            return
        if not text or not text.strip():
            return
        # The recognized text becomes a user_msg event inside _handle_user_input.
        await self._handle_user_input(text)

    def _cancel_replay(self) -> None:
        """Stop an in-flight replay so its audio can't overlap a new turn's TTS."""
        if self._replay_task and not self._replay_task.done():
            self._replay_task.cancel()
        self._replay_task = None

    def _handle_replay_last(self) -> None:
        """Speak the active workspace's last response again, on demand — lets the
        user hear a reply that finished while they were on another project."""
        rt = self.pool.active
        if rt is None or not self.tts or not rt.last_spoken.strip():
            return
        self._cancel_replay()
        # Off the receive loop so synthesis can't block message handling.
        self._replay_task = asyncio.create_task(self._stream_replay(rt, rt.last_spoken.strip()))

    async def _stream_replay(self, rt: WorkspaceRuntime, text: str) -> None:
        try:
            if self.pool.is_active(rt):
                await self.send_json(TTSStart(
                    target_seq=self._last_assistant_seq(rt), workspace=rt.name,
                ))
            async for chunk in self.tts.synthesize(text):
                if not self.pool.is_active(rt):  # user swiped away — stop
                    break
                await self.pool.send_audio(rt, chunk)
            if self.pool.is_active(rt):
                await self.send_json(TTSEnd(workspace=rt.name))
        except asyncio.CancelledError:
            pass
        except Exception:
            log.exception("Replay error for %s", rt.name)

    async def _handle_image(self, msg: ImageMessage) -> None:
        """The SDK is text-only, so save the image into the workspace and tell the
        agent to Read it."""
        rt = self.pool.active
        if rt is None:
            await self.send_json(Error(message="No workspace selected yet.", code="no_workspace"))
            return
        import base64
        ext = _IMG_EXT.get(msg.media_type, "jpg")
        img_dir = Path(rt.path) / ".walkie_images"
        img_dir.mkdir(parents=True, exist_ok=True)
        img_path = img_dir / f"{int(time.time())}_{uuid.uuid4().hex[:6]}.{ext}"
        try:
            img_path.write_bytes(base64.b64decode(msg.data))
        except Exception:
            log.exception("Failed to save attached image")
            await self.send_json(Error(message="Could not save image", code="image_error"))
            return
        text = msg.text or "What do you see in this image?"
        note = f"[The user attached an image saved at {img_path}. Use the Read tool to view it.]"
        await self._handle_user_input(text, image_note=note)


_YES_PHRASES = (
    "go ahead", "go for it", "do it", "run it", "send it", "sounds good",
    "please do", "yes please", "that's fine", "thats fine",
)
_NO_PHRASES = (
    "do not", "don't", "dont", "no way", "hold off", "not now",
)
_YES_WORDS = {
    "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "approve", "approved",
    "confirm", "confirmed", "allow", "affirmative", "yea", "yes!", "proceed",
}
_NO_WORDS = {
    "no", "nope", "nah", "deny", "denied", "stop", "cancel", "negative",
    "reject", "rejected", "block", "abort",
}


def _classify_yes_no(text: str) -> bool | None:
    """Interpret a short reply as approval (True), denial (False), or unclear (None)."""
    t = (text or "").strip().lower()
    if not t:
        return None
    has_no = any(p in t for p in _NO_PHRASES)
    has_yes = any(p in t for p in _YES_PHRASES)
    words = {w.strip(".,!?;:'\"") for w in t.split()}
    has_yes = has_yes or bool(words & _YES_WORDS)
    has_no = has_no or bool(words & _NO_WORDS)
    if has_yes and not has_no:
        return True
    if has_no and not has_yes:
        return False
    return None


def _describe_tool(tool_name: str, tool_input: dict[str, Any]) -> tuple[str, str, str]:
    """Return (summary, detail, spoken) for a permission prompt.

    The spoken line is deliberately terse and never reads the command aloud —
    on a hands-free interface that's noise. The command still shows on screen.
    """
    if tool_name == "Bash":
        cmd = str(tool_input.get("command", "")).strip()
        return ("Run a shell command", cmd, "Claude requires approval.")
    if tool_name in ("Write", "Edit", "MultiEdit"):
        path = tool_input.get("file_path") or tool_input.get("path") or "a file"
        return (f"{tool_name} a file", str(path), "Claude requires approval.")
    return (f"Use {tool_name}", json.dumps(tool_input)[:200], "Claude requires approval.")
