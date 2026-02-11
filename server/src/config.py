from __future__ import annotations

import json
import os
from pathlib import Path

import yaml
from pydantic import BaseModel, field_validator
from pydantic_settings import BaseSettings


class ServerConfig(BaseModel):
    host: str = "0.0.0.0"
    port: int = 8765


class ClaudeConfig(BaseModel):
    model: str = "claude-sonnet-4-5-20250929"
    max_tokens: int = 8192
    max_conversation_turns: int = 50


class STTConfig(BaseModel):
    model_size: str = "base.en"
    language: str = "en"


class TTSConfig(BaseModel):
    model: str = "gpt-4o-mini-tts"
    voice: str = "nova"
    speed: float = 1.0
    instructions: str = ""


class AudioConfig(BaseModel):
    sample_rate: int = 16000
    channels: int = 1
    chunk_duration_ms: int = 100


class VADConfig(BaseModel):
    threshold: float = 0.5
    min_speech_duration_ms: int = 250
    min_silence_duration_ms: int = 800


class SafetyConfig(BaseModel):
    command_timeout: int = 30
    blocked_commands: list[str] = []


class WorkspaceConfig(BaseModel):
    name: str
    path: str

    @field_validator("path")
    @classmethod
    def expand_path(cls, v: str) -> str:
        return str(Path(v).expanduser().resolve())


class Settings(BaseSettings):
    server: ServerConfig = ServerConfig()
    workspace_root: str = "~/workspace"
    claude: ClaudeConfig = ClaudeConfig()
    stt: STTConfig = STTConfig()
    tts: TTSConfig = TTSConfig()
    audio: AudioConfig = AudioConfig()
    vad: VADConfig = VADConfig()
    safety: SafetyConfig = SafetyConfig()
    workspaces: list[WorkspaceConfig] = []
    projws_path: str = ""

    anthropic_api_key: str = ""
    openai_api_key: str = ""

    @field_validator("workspace_root")
    @classmethod
    def expand_workspace(cls, v: str) -> str:
        return str(Path(v).expanduser().resolve())

    model_config = {"env_prefix": "WT_", "env_nested_delimiter": "__"}


def load_settings(config_path: str | None = None) -> Settings:
    """Load settings from config.yaml, overridden by env vars."""
    data: dict = {}
    if config_path is None:
        config_path = os.environ.get(
            "WT_CONFIG", str(Path(__file__).parent.parent / "config.yaml")
        )
    path = Path(config_path)
    if path.exists():
        with open(path) as f:
            data = yaml.safe_load(f) or {}

    # Pull API keys from standard env vars if not in config
    if "anthropic_api_key" not in data:
        data["anthropic_api_key"] = os.environ.get("ANTHROPIC_API_KEY", "")
    if "openai_api_key" not in data:
        data["openai_api_key"] = os.environ.get("OPENAI_API_KEY", "")

    settings = Settings(**data)

    # Load workspaces from projws projects.json if configured
    if settings.projws_path and not settings.workspaces:
        projws_file = Path(settings.projws_path).expanduser().resolve()
        if projws_file.exists():
            with open(projws_file) as f:
                projws = json.load(f)
            for key, proj in projws.get("projects", {}).items():
                cwd = proj.get("cwd")
                if cwd:
                    settings.workspaces.append(WorkspaceConfig(
                        name=proj.get("label", key),
                        path=cwd,
                    ))

    return settings
