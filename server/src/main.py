"""FastAPI application entry point."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .config import load_settings
from .claude.client import ClaudeClient
from .claude.tool_executor import ToolExecutor
from .utils.safety import PathSandbox
from .ws.handler import ConnectionHandler
from .ws.session import Session, SessionRegistry

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger(__name__)

settings = load_settings()

# Sandbox and tool executor
sandbox = PathSandbox(settings.workspace_root)
tool_executor = ToolExecutor(
    sandbox=sandbox,
    blocked_commands=settings.safety.blocked_commands,
    command_timeout=settings.safety.command_timeout,
)

# Claude client
claude_client = ClaudeClient(
    api_key=settings.anthropic_api_key,
    model=settings.claude.model,
    max_tokens=settings.claude.max_tokens,
    tool_executor=tool_executor,
)

# STT engine (lazy-loaded)
stt_engine = None
try:
    from .stt.whisper_stt import WhisperSTT
    stt_engine = WhisperSTT(
        model_size=settings.stt.model_size,
        language=settings.stt.language,
    )
    log.info("STT engine configured: Whisper %s", settings.stt.model_size)
except ImportError:
    log.warning("faster-whisper not installed — STT disabled")

# TTS engine (lazy-loaded)
tts_engine = None
if settings.openai_api_key:
    from .tts.openai_tts import OpenAITTS
    tts_engine = OpenAITTS(
        api_key=settings.openai_api_key,
        model=settings.tts.model,
        voice=settings.tts.voice,
        speed=settings.tts.speed,
        instructions=settings.tts.instructions,
    )
    log.info("TTS engine configured: OpenAI %s", settings.tts.model)
else:
    log.warning("No OpenAI API key — TTS disabled")


sessions = SessionRegistry()


@asynccontextmanager
async def lifespan(app: FastAPI):
    cleanup_task = sessions.start_cleanup()
    yield
    cleanup_task.cancel()
    sessions.clear()


app = FastAPI(title="Walkie Talkie", version="0.1.0", lifespan=lifespan)


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "stt": stt_engine is not None,
        "tts": tts_engine is not None,
        "active_sessions": len(sessions),
    }


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    await ws.accept()
    session = Session(settings=settings)
    sessions.add(session)
    handler = ConnectionHandler(
        ws=ws,
        session=session,
        claude_client=claude_client,
        stt_engine=stt_engine,
        tts_engine=tts_engine,
        workspaces=settings.workspaces,
        safety_config={
            "blocked_commands": settings.safety.blocked_commands,
            "command_timeout": settings.safety.command_timeout,
        },
    )
    log.info("New connection: session %s", session.session_id)
    try:
        await handler.handle()
    finally:
        sessions.remove(session.session_id)
        log.info("Session %s removed from registry", session.session_id)


def main():
    """Run the server with uvicorn."""
    import uvicorn
    log.info("Starting server on %s:%d", settings.server.host, settings.server.port)
    log.info("Workspace root: %s", settings.workspace_root)
    if settings.workspaces:
        log.info("Workspaces: %s", [w.name for w in settings.workspaces])
    uvicorn.run(
        "src.main:app",
        host=settings.server.host,
        port=settings.server.port,
        log_level="info",
    )


if __name__ == "__main__":
    main()
