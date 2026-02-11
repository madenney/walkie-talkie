"""WebSocket endpoint and message routing."""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import TYPE_CHECKING

from fastapi import WebSocket, WebSocketDisconnect

from ..config import WorkspaceConfig
from ..claude.tool_executor import ToolExecutor
from ..utils.safety import PathSandbox
from .protocol import (
    AudioEnd,
    AudioPrefix,
    AudioStart,
    Error,
    ImageMessage,
    Interrupt,
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
    from ..claude.client import ClaudeClient
    from ..stt.base import STTEngine
    from ..tts.base import TTSEngine

log = logging.getLogger(__name__)


class ConnectionHandler:
    """Manages a single WebSocket connection."""

    def __init__(
        self,
        ws: WebSocket,
        session: Session,
        claude_client: ClaudeClient,
        stt_engine: STTEngine | None = None,
        tts_engine: TTSEngine | None = None,
        workspaces: list[WorkspaceConfig] | None = None,
        safety_config: dict | None = None,
    ) -> None:
        self.ws = ws
        self.session = session
        self.claude = claude_client
        self.stt = stt_engine
        self.tts = tts_engine
        self.workspaces = {w.name: w for w in (workspaces or [])}
        self.safety_config = safety_config or {}

    async def send_json(self, msg) -> None:
        """Send a Pydantic model as JSON text frame."""
        await self.ws.send_text(msg.model_dump_json())

    async def send_audio(self, data: bytes) -> None:
        """Send TTS audio as binary frame with prefix."""
        await self.ws.send_bytes(bytes([AudioPrefix.TTS]) + data)

    async def handle(self) -> None:
        """Main message loop."""
        sid = self.session.session_id
        log.info("Session %s connected", sid)

        # Send available workspaces on connect
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
            self.session.cancel_response()
            log.info("Session %s cleaned up", sid)

    async def _handle_text(self, text: str) -> None:
        """Route a JSON text frame."""
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
                await self._handle_audio_end()

            case Interrupt():
                await self._handle_interrupt()

    async def _handle_binary(self, data: bytes) -> None:
        """Handle binary audio frame."""
        if len(data) < 2:
            return
        prefix = data[0]
        payload = data[1:]

        if prefix == AudioPrefix.MIC and self.session.is_recording:
            self.session.audio_buffer.extend(payload)

    async def _handle_user_input(
        self, text: str, images: list[dict] | None = None
    ) -> None:
        """Process user text (and optional images) → Claude → stream response."""
        content: list[dict] = []
        if images:
            content.extend(images)
        content.append({"type": "text", "text": text})

        self.session.add_user_message(content)
        self.session.interrupted = False
        self.session.is_responding = True

        # Run Claude response in a task so it can be cancelled
        self.session.response_task = asyncio.create_task(
            self._run_claude_response()
        )
        try:
            await self.session.response_task
        except asyncio.CancelledError:
            log.info("Response cancelled for session %s", self.session.session_id)
        except Exception as e:
            log.exception("Response error for session %s", self.session.session_id)
            await self.send_json(Error(message=str(e), code="claude_error"))
        finally:
            self.session.is_responding = False
            self.session.response_task = None

    async def _run_claude_response(self) -> None:
        """Stream Claude response back to client, handling tool use."""
        # Separate buffer for detecting <speak> tags — consumed as tags are found,
        # so it stays small and doesn't re-scan old text.
        tts_buffer = ""
        tts_queue: asyncio.Queue[str | None] = asyncio.Queue()
        tts_task: asyncio.Task | None = None

        if self.tts:
            tts_task = asyncio.create_task(self._tts_consumer(tts_queue))

        async for event in self.claude.stream_response(
            self.session, executor=self.session.tool_executor
        ):
            if self.session.interrupted:
                break

            if event["type"] == "text_delta":
                delta = event["text"]

                # Strip <speak> tags for display, keep content
                display_text = delta.replace("<speak>", "").replace("</speak>", "")
                if display_text:
                    await self.send_json(ResponseDelta(text=display_text))

                # Accumulate into TTS buffer and extract completed <speak> blocks
                if self.tts:
                    tts_buffer += delta
                    while True:
                        match = re.search(r"<speak>(.*?)</speak>", tts_buffer, re.DOTALL)
                        if not match:
                            break
                        speak_text = match.group(1).strip()
                        if speak_text:
                            await tts_queue.put(speak_text)
                        # Consume everything up to and including the matched tag
                        tts_buffer = tts_buffer[match.end():]

            elif event["type"] == "text_done":
                pass

            elif event["type"] == "tool_use":
                await self.send_json(ToolUse(
                    tool_name=event["tool_name"],
                    tool_id=event["tool_id"],
                    input=event["input"],
                ))

            elif event["type"] == "tool_result":
                await self.send_json(ToolResult(
                    tool_id=event["tool_id"],
                    tool_name=event["tool_name"],
                    success=event["success"],
                    output=event["output"][:2000],
                ))

            elif event["type"] == "response_complete":
                pass

        await self.send_json(ResponseEnd())

        if tts_task:
            await tts_queue.put(None)
            await tts_task

    async def _tts_consumer(self, queue: asyncio.Queue[str | None]) -> None:
        """Consume speak text from the queue and stream TTS audio."""
        started = False
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

        if started:
            await self.send_json(TTSEnd())

    async def _handle_select_workspace(self, name: str) -> None:
        """Switch to a workspace: create sandbox + executor, clear conversation."""
        ws_config = self.workspaces.get(name)
        if ws_config is None:
            await self.send_json(Error(
                message=f"Unknown workspace: {name}", code="invalid_workspace"
            ))
            return

        sandbox = PathSandbox(ws_config.path)
        self.session.tool_executor = ToolExecutor(
            sandbox=sandbox,
            blocked_commands=self.safety_config.get("blocked_commands", []),
            command_timeout=self.safety_config.get("command_timeout", 30),
        )
        self.session.workspace_name = name
        self.session.conversation.clear()
        self.session.cancel_response()

        log.info("Session %s switched to workspace %s (%s)",
                 self.session.session_id, name, ws_config.path)
        await self.send_json(WorkspaceSelected(name=name, path=ws_config.path))

    async def _handle_audio_end(self) -> None:
        """Transcribe buffered audio and send to Claude."""
        audio_data = bytes(self.session.audio_buffer)
        self.session.clear_audio_buffer()

        if not audio_data or not self.stt:
            if not self.stt:
                await self.send_json(
                    Error(message="STT not available", code="stt_unavailable")
                )
            return

        try:
            text = await self.stt.transcribe(
                audio_data,
                sample_rate=self.session.settings.audio.sample_rate,
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
        """Handle image message with optional text."""
        images = [{
            "type": "image",
            "source": {
                "type": "base64",
                "media_type": msg.media_type,
                "data": msg.data,
            },
        }]
        text = msg.text or "What do you see in this image?"
        await self._handle_user_input(text, images=images)

    async def _handle_interrupt(self) -> None:
        """Interrupt current response and TTS playback."""
        self.session.cancel_response()
        log.info("Session %s interrupted", self.session.session_id)
