"""Abstract TTS interface."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import AsyncIterator


class TTSEngine(ABC):
    """Base class for text-to-speech engines."""

    @abstractmethod
    async def synthesize(self, text: str) -> AsyncIterator[bytes]:
        """Convert text to audio, yielding chunks of audio bytes (MP3)."""
        ...
        yield b""  # make this a generator
