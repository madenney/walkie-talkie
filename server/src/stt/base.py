"""Abstract STT interface."""

from __future__ import annotations

from abc import ABC, abstractmethod


class STTEngine(ABC):
    """Base class for speech-to-text engines."""

    @abstractmethod
    async def transcribe(self, audio_data: bytes, sample_rate: int = 16000) -> str:
        """Transcribe audio bytes (PCM s16le) to text."""
        ...
