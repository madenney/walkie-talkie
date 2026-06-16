"""FastAPI application entry point."""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket

from pathlib import Path

from .claude.session_store import SessionStore
from .config import load_settings
from .fs_browser import make_fs_router
from .ws.handler import ConnectionHandler
from .ws.session import Session, SessionRegistry, WorkspacePool

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger(__name__)

settings = load_settings()

# Force the Claude Agent SDK to authenticate through the logged-in `claude` CLI
# (subscription), not pay-as-you-go API credits. The SDK spawns the CLI as a
# subprocess inheriting this environment, so the key must be gone before then.
if os.environ.pop("ANTHROPIC_API_KEY", None):
    log.info("ANTHROPIC_API_KEY unset → Agent SDK uses subscription auth")

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

# Live workspace agents, shared across connections so a running turn survives the
# phone disconnecting (app closed / network blip). Reconnecting re-attaches and
# repaints; turns are never interrupted by a connection going away.
pool = WorkspacePool()

# Per-workspace conversation continuity: remembers the last SDK session id for
# each workspace so reopening the app (or restarting the server) resumes it.
session_store = SessionStore(Path(__file__).parent.parent / ".wt_sessions.json")


@asynccontextmanager
async def lifespan(app: FastAPI):
    cleanup_task = sessions.start_cleanup()
    reaper_task = pool.start_reaper()
    yield
    cleanup_task.cancel()
    reaper_task.cancel()
    await pool.close_all()
    sessions.clear()


app = FastAPI(title="Walkie Talkie", version="0.1.0", lifespan=lifespan)

# Read-only file explorer for the phone, scoped to the workspace folders.
app.include_router(make_fs_router(settings))


@app.get("/health")
async def health():
    # Liveness + readiness. Reaching this means the server is up; "degraded"
    # flags that a critical engine (STT/TTS) failed to load, so a half-broken
    # server doesn't report a healthy green. Shape matches the Shelf status
    # check contract: {status, detail}.
    stt_up = stt_engine is not None
    tts_up = tts_engine is not None
    ready = stt_up and tts_up
    return {
        "status": "ok" if ready else "degraded",
        "detail": (
            f"stt={'up' if stt_up else 'down'} "
            f"tts={'up' if tts_up else 'down'} · {len(sessions)} session(s)"
            f" · {len(pool.runtimes)} live workspace(s)"
        ),
        "stt": stt_up,
        "tts": tts_up,
        "active_sessions": len(sessions),
        "live_workspaces": len(pool.runtimes),
    }


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    await ws.accept()
    session = Session(settings=settings)
    sessions.add(session)
    handler = ConnectionHandler(
        ws=ws,
        session=session,
        pool=pool,
        settings=settings,
        stt_engine=stt_engine,
        tts_engine=tts_engine,
        workspaces=settings.workspaces,
        session_store=session_store,
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
