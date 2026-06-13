"""WebSocket endpoint and message routing.

The Claude turn runs as a background task so the receive loop stays live during
a response — that's what lets a tool-permission request round-trip to the phone
(and what makes barge-in interrupts responsive).
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
from .session import Session

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
        settings: Settings,
        stt_engine: STTEngine | None = None,
        tts_engine: TTSEngine | None = None,
        workspaces: list[WorkspaceConfig] | None = None,
        session_store: "SessionStore | None" = None,
    ) -> None:
        self.ws = ws
        self.session = session
        self.settings = settings
        self.stt = stt_engine
        self.tts = tts_engine
        self.workspaces = {w.name: w for w in (workspaces or [])}
        self.session_store = session_store

        self.gated_tools = settings.safety.require_approval
        self.approval_timeout = settings.safety.approval_timeout
        self.model = settings.claude.model or None

        self._disconnected = False
        self._response_task: asyncio.Task | None = None
        # The current turn's TTS queue, so permission prompts can be spoken.
        self._active_tts_queue: asyncio.Queue[str | None] | None = None

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
        self.session.interrupted = True
        self.session.deny_pending_approvals()

    # ------------------------------------------------------------------ loop

    async def handle(self) -> None:
        sid = self.session.session_id
        log.info("Session %s connected", sid)

        if self.workspaces:
            await self.send_json(WorkspaceList(
                workspaces=[{"name": w.name, "path": w.path} for w in self.workspaces.values()]
            ))

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
            await self._teardown()
            log.info("Session %s cleaned up", sid)

    async def _teardown(self) -> None:
        self.session.interrupted = True
        self.session.deny_pending_approvals()
        if self._response_task and not self._response_task.done():
            self._response_task.cancel()
        if self.session.agent is not None:
            await self.session.agent.close()
            self.session.agent = None

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
                await self._interrupt_current()
            case PermissionResponse(id=pid, approved=approved):
                self._resolve_approval(pid, approved)

    async def _handle_binary(self, data: bytes) -> None:
        if len(data) < 2:
            return
        if data[0] == AudioPrefix.MIC and self.session.is_recording:
            self.session.audio_buffer.extend(data[1:])

    # ---------------------------------------------------------------- turns

    async def _handle_user_input(self, text: str, image_note: str | None = None) -> None:
        if self.session.agent is None:
            await self.send_json(Error(
                message="No workspace selected yet.", code="no_workspace"
            ))
            return

        # If a tool is waiting on approval, treat this input as the yes/no answer
        # (spoken or typed) rather than a new prompt — that's voice approval.
        if self.session.pending_approvals:
            decision = _classify_yes_no(text)
            if decision is True:
                self._resolve_all_approvals(True)
            elif decision is False:
                self._resolve_all_approvals(False)
            else:
                self._speak("Sorry, I didn't catch that. Say yes or no.")
            return

        # One turn at a time: interrupt any in-flight response first.
        await self._interrupt_current()

        prompt = text if not image_note else f"{text}\n\n{image_note}"
        self.session.interrupted = False
        self.session.is_responding = True
        self._response_task = asyncio.create_task(self._run_turn(prompt))

    async def _run_turn(self, prompt: str) -> None:
        """Run one Claude turn: stream text → TTS, surface tool calls."""
        tts_buffer = ""
        disp_buf = ""  # display-side buffer, holds back partial <speak> tags
        in_speak = False
        speak_accum = ""
        first_chunk_sent = False
        tts_queue: asyncio.Queue[str | None] = asyncio.Queue()
        tts_task: asyncio.Task | None = None
        if self.tts:
            tts_task = asyncio.create_task(self._tts_consumer(tts_queue))
            self._active_tts_queue = tts_queue

        try:
            await self.session.agent.query(prompt)

            async for event in self.session.agent.stream():
                if self.session.interrupted:
                    break

                etype = event["type"]
                if etype == "text_delta":
                    delta = event["text"]
                    # Strip <speak> tags for display — robust to a tag split across
                    # deltas: remove complete tags, then hold back any trailing
                    # partial tag until the rest of it arrives.
                    disp_buf += delta
                    disp_buf = disp_buf.replace("<speak>", "").replace("</speak>", "")
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
                    if display:
                        await self.send_json(ResponseDelta(text=display))

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
                    await self.send_json(ToolUse(
                        tool_name=event["tool_name"],
                        tool_id=event["tool_id"],
                        input=event.get("input", {}) or {},
                    ))

                elif etype == "tool_result":
                    await self.send_json(ToolResult(
                        tool_id=event["tool_id"],
                        tool_name=event.get("tool_name", ""),
                        success=event["success"],
                        output=(event.get("output") or "")[:2000],
                    ))

                elif etype == "response_complete":
                    self._persist_session_id()

        except asyncio.CancelledError:
            log.info("Turn cancelled for session %s", self.session.session_id)
        except Exception as e:
            log.exception("Turn error for session %s", self.session.session_id)
            await self.send_json(Error(message=str(e), code="agent_error"))
        finally:
            await self.send_json(ResponseEnd())
            if tts_task:
                await tts_queue.put(None)
                try:
                    await tts_task
                except Exception:
                    pass
            self._active_tts_queue = None
            self.session.is_responding = False
            self._persist_session_id()

    def _persist_session_id(self) -> None:
        """Remember this workspace's SDK session id so it can be resumed later."""
        if not self.session_store:
            return
        agent = self.session.agent
        path = self.session.workspace_path
        if agent and agent.session_id and path:
            self.session_store.set(path, agent.session_id)

    async def _tts_consumer(self, queue: asyncio.Queue[str | None]) -> None:
        started = False
        try:
            while True:
                text = await queue.get()
                if text is None:
                    break
                if self.session.interrupted:
                    continue
                if not started:
                    await self.send_json(TTSStart())
                    started = True
                try:
                    async for chunk in self.tts.synthesize(text):
                        if self.session.interrupted:
                            break
                        await self.send_audio(chunk)
                except Exception:
                    log.exception("TTS error")
                    break
            if started:
                await self.send_json(TTSEnd())
        except (WebSocketDisconnect, RuntimeError):
            log.debug("TTS consumer: client disconnected")

    async def _interrupt_current(self) -> None:
        """Stop any in-flight turn (and unblock a hook waiting on approval)."""
        task = self._response_task
        self.session.interrupted = True
        self.session.deny_pending_approvals()
        if self.session.agent is not None:
            await self.session.agent.interrupt()
        if task is not None and not task.done():
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=5)
            except (asyncio.TimeoutError, asyncio.CancelledError, Exception):
                task.cancel()
        self._response_task = None
        log.info("Session %s interrupted", self.session.session_id)

    # ----------------------------------------------------------- permissions

    async def _request_permission(self, tool_name: str, tool_input: dict) -> bool:
        """Hook callback: ask the phone for yes/no on a gated tool."""
        pid = uuid.uuid4().hex[:12]
        summary, detail, spoken = _describe_tool(tool_name, tool_input)
        loop = asyncio.get_event_loop()
        fut: asyncio.Future = loop.create_future()
        self.session.pending_approvals[pid] = fut

        await self.send_json(PermissionRequest(
            id=pid, tool_name=tool_name, summary=summary, detail=detail,
        ))
        self._speak(spoken)

        approved = False
        try:
            approved = await asyncio.wait_for(fut, timeout=self.approval_timeout)
            return approved
        except asyncio.TimeoutError:
            log.info("Approval %s timed out → deny", pid)
            approved = False
            return False
        finally:
            self.session.pending_approvals.pop(pid, None)
            # Tell the phone it's settled, however it was answered (voice/tap/timeout).
            await self.send_json(PermissionResolved(id=pid, approved=approved))

    def _resolve_approval(self, pid: str, approved: bool) -> None:
        fut = self.session.pending_approvals.get(pid)
        if fut is not None and not fut.done():
            fut.set_result(approved)

    def _resolve_all_approvals(self, approved: bool) -> None:
        """Answer every outstanding approval (voice yes/no isn't tied to an id)."""
        for fut in list(self.session.pending_approvals.values()):
            if not fut.done():
                fut.set_result(approved)
        log.info("Voice/text resolved approvals → %s", "approve" if approved else "deny")

    def _speak(self, text: str) -> None:
        q = self._active_tts_queue
        if q is not None and text:
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

        # Tear down any in-flight turn and the previous agent.
        await self._interrupt_current()
        if self.session.agent is not None:
            await self.session.agent.close()
            self.session.agent = None

        # Resume this workspace's prior conversation if we've seen one before.
        resume_id = self.session_store.get(ws_config.path) if self.session_store else None

        async def _make_agent(resume: str | None) -> AgentSession:
            a = AgentSession(
                cwd=ws_config.path,
                gated_tools=self.gated_tools,
                on_permission=self._request_permission,
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

        self.session.agent = agent
        self.session.workspace_name = name
        self.session.workspace_path = ws_config.path
        log.info("Session %s → workspace %s (%s)", self.session.session_id, name, ws_config.path)
        await self.send_json(WorkspaceSelected(name=name, path=ws_config.path))

        # Replay prior scrollback so a resumed conversation isn't visually blank.
        if agent.session_id:
            try:
                history = load_history(agent.session_id)
            except Exception:
                log.exception("Failed to load history for %s", agent.session_id)
                history = []
            if history:
                await self.send_json(ConversationHistory(
                    messages=[HistoryMessage(**m) for m in history]
                ))

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
        if not self.session.workspace_path:
            await self.send_json(Error(message="No workspace selected yet.", code="no_workspace"))
            return
        import base64
        ext = _IMG_EXT.get(msg.media_type, "jpg")
        img_dir = Path(self.session.workspace_path) / ".walkie_images"
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
    """Return (summary, detail, spoken) for a permission prompt."""
    if tool_name == "Bash":
        cmd = str(tool_input.get("command", "")).strip()
        short = cmd if len(cmd) <= 200 else cmd[:200] + "…"
        return ("Run a shell command", cmd, f"Claude wants to run a command: {short}. Approve?")
    if tool_name in ("Write", "Edit", "MultiEdit"):
        path = tool_input.get("file_path") or tool_input.get("path") or "a file"
        return (f"{tool_name} a file", str(path), f"Claude wants to edit {path}. Approve?")
    return (f"Use {tool_name}", json.dumps(tool_input)[:200], f"Claude wants to use {tool_name}. Approve?")
