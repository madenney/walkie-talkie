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
    ConversationHistory,
    Error,
    HistoryMessage,
    ImageMessage,
    Interrupt,
    PermissionRequest,
    PermissionResolved,
    PermissionResponse,
    Ping,
    Pong,
    ResponseDelta,
    ResponseEnd,
    SelectWorkspace,
    TextMessage,
    ToolResult,
    ToolUse,
    Transcription,
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
            await self.ws.send_text(msg.model_dump_json())
        except (WebSocketDisconnect, RuntimeError):
            self._mark_disconnected()

    async def send_audio(self, data: bytes) -> None:
        if not self.connected:
            return
        try:
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
        # its on-screen workspace and we repaint it (see _activate_and_repaint).
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
            case SelectWorkspace(name=name):
                await self._handle_select_workspace(name)
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

    async def _handle_binary(self, data: bytes) -> None:
        if len(data) < 2:
            return
        if data[0] == AudioPrefix.MIC and self.session.is_recording:
            self.session.audio_buffer.extend(data[1:])

    # ---------------------------------------------------------------- turns

    async def _handle_user_input(self, text: str, image_note: str | None = None) -> None:
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

        prompt = text if not image_note else f"{text}\n\n{image_note}"
        rt.interrupted = False
        rt.is_responding = True
        rt.touch()
        rt.response_task = asyncio.create_task(self._run_turn(rt, prompt))
        await self.pool.broadcast_status()

    async def _run_turn(self, rt: WorkspaceRuntime, prompt: str) -> None:
        """Run one Claude turn for ``rt``: stream text → TTS, surface tool calls.

        Phone-bound output (deltas, tool cards, TTS) is emitted only while ``rt``
        is the active workspace; otherwise the turn runs silently and persists to
        the SDK transcript for replay on switch-back.
        """
        # Reset the repaint cursor under the lock so a concurrent activate/repaint
        # can't read a half-reset state.
        async with self.pool.lock:
            rt.display_accum = ""
            rt.sent_len = 0
        tts_buffer = ""
        in_speak = False
        speak_accum = ""
        first_chunk_sent = False
        tts_queue: asyncio.Queue[str | None] = asyncio.Queue()
        tts_task: asyncio.Task | None = None
        if self.tts:
            tts_task = asyncio.create_task(self._tts_consumer(rt, tts_queue))
            rt.tts_queue = tts_queue

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
                    rt.disp_buf += delta
                    disp_buf = rt.disp_buf.replace("<speak>", "").replace("</speak>", "")
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
                    rt.disp_buf = disp_buf
                    if display:
                        rt.display_accum += display
                        await self.pool.flush_delta(rt)

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
                                    speak_accum = speak_accum[m.end():]
                            break

                elif etype == "tool_use":
                    await self.pool.send(rt, ToolUse(
                        tool_name=event["tool_name"],
                        tool_id=event["tool_id"],
                        input=event.get("input", {}) or {},
                    ))

                elif etype == "tool_result":
                    await self.pool.send(rt, ToolResult(
                        tool_id=event["tool_id"],
                        tool_name=event.get("tool_name", ""),
                        success=event["success"],
                        output=(event.get("output") or "")[:2000],
                    ))

                elif etype == "response_complete":
                    self._persist_session_id(rt)

        except asyncio.CancelledError:
            log.info("Turn cancelled for workspace %s", rt.name)
        except Exception as e:
            log.exception("Turn error for workspace %s", rt.name)
            await self.pool.send(rt, Error(message=str(e), code="agent_error"))
        finally:
            await self.pool.send(rt, ResponseEnd())
            if tts_task:
                await tts_queue.put(None)
                try:
                    await tts_task
                except Exception:
                    pass
            rt.tts_queue = None
            rt.is_responding = False
            rt.disp_buf = ""
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
                    await self.pool.send(rt, TTSStart())
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
                await self.pool.send(rt, TTSEnd())
        except (WebSocketDisconnect, RuntimeError):
            log.debug("TTS consumer: client disconnected")

    async def _interrupt_active(self) -> None:
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

        If the workspace is backgrounded, the request waits silently and is
        surfaced to the phone when the user switches to it.
        """
        rt = self.pool.runtimes.get(name)
        if rt is None:
            return False
        pid = uuid.uuid4().hex[:12]
        summary, detail, spoken = _describe_tool(tool_name, tool_input)
        loop = asyncio.get_event_loop()
        fut: asyncio.Future = loop.create_future()
        rt.pending_approvals[pid] = fut
        rt.approval_meta[pid] = {
            "tool_name": tool_name, "summary": summary, "detail": detail, "spoken": spoken,
        }
        # This workspace is now blocked on the user — reflect it on the dashboard
        # even if it's a background session the phone isn't watching.
        await self.pool.broadcast_status()

        if self.pool.is_active(rt):
            await self._surface_approval(rt, pid)

        approved = False
        try:
            approved = await fut
            return approved
        finally:
            rt.pending_approvals.pop(pid, None)
            rt.approval_meta.pop(pid, None)
            timer = rt.approval_timers.pop(pid, None)
            if timer is not None:
                timer.cancel()
            was_surfaced = pid in rt.surfaced
            rt.surfaced.discard(pid)
            # Tell the phone it's settled, however it was answered (only if it
            # was ever shown there).
            if was_surfaced:
                await self.pool.send(rt, PermissionResolved(id=pid, approved=approved))
            # No longer blocked — refresh the dashboard.
            await self.pool.broadcast_status()

    async def _surface_approval(self, rt: WorkspaceRuntime, pid: str) -> None:
        """Show a pending approval on the phone and start its auto-deny timer.
        Only meaningful while ``rt`` is the on-screen workspace."""
        if pid in rt.surfaced or not self.pool.is_active(rt):
            return
        meta = rt.approval_meta.get(pid)
        if not meta:
            return
        rt.surfaced.add(pid)
        await self.pool.send(rt, PermissionRequest(
            id=pid, tool_name=meta["tool_name"],
            summary=meta["summary"], detail=meta["detail"],
        ))
        self._speak(rt, meta["spoken"])
        rt.approval_timers[pid] = asyncio.create_task(self._approval_timeout(rt, pid))

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

    async def _handle_select_workspace(self, name: str) -> None:
        ws_config = self.workspaces.get(name)
        if ws_config is None:
            await self.send_json(Error(
                message=f"Unknown workspace: {name}", code="invalid_workspace"
            ))
            return

        # Already live (including surviving a previous connection)? Just bring it
        # to the foreground and repaint — never interrupt it.
        existing = self.pool.runtimes.get(name)
        if existing is not None:
            await self._activate_and_repaint(existing)
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
        self.pool.runtimes[name] = rt
        log.info("Session %s → workspace %s (%s)", self.session.session_id, name, ws_config.path)
        await self._activate_and_repaint(rt)
        # A new live session exists now — update the dashboard.
        await self.pool.broadcast_status()

    async def _activate_and_repaint(self, rt: WorkspaceRuntime) -> None:
        """Bring ``rt`` on-screen: mark it active and replay scrollback + any
        in-flight progress, atomically so a concurrent live turn can't interleave.

        The whole thing runs under the pool's send lock: any in-flight turn for
        ``rt`` blocks on the lock before it can send, so its first live delta
        lands strictly after this repaint (and only the not-yet-sent suffix, via
        the shared ``sent_len`` cursor). This is also the reconnect path — the
        runtime may have been running the whole time the app was closed."""
        async with self.pool.lock:
            self.pool.active_name = rt.name
            await self.send_json(WorkspaceSelected(
                name=rt.name, path=rt.path, responding=rt.is_responding,
            ))

            # Persisted prior turns from the SDK transcript.
            if rt.agent.session_id:
                try:
                    history = load_history(rt.agent.session_id)
                except Exception:
                    log.exception("Failed to load history for %s", rt.agent.session_id)
                    history = []
                if history:
                    await self.send_json(ConversationHistory(
                        messages=[HistoryMessage(**m) for m in history]
                    ))

            # The phone cleared this page before switching, so resend the current
            # turn's progress-so-far from the top and reset the cursor.
            rt.sent_len = 0
            if rt.is_responding and rt.display_accum:
                await self.send_json(ResponseDelta(text=rt.display_accum))
                rt.sent_len = len(rt.display_accum)

        # Surface any approval that's been waiting in the background (its own
        # sends don't need the stream lock).
        for pid in list(rt.pending_approvals.keys()):
            await self._surface_approval(rt, pid)

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
        await self.send_json(Transcription(text=text))
        await self._handle_user_input(text)

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
