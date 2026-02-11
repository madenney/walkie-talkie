"""faster-whisper STT implementation."""

from __future__ import annotations

import asyncio
import io
import logging
import wave

from .base import STTEngine

log = logging.getLogger(__name__)


class WhisperSTT(STTEngine):
    """Speech-to-text using faster-whisper."""

    def __init__(self, model_size: str = "base.en", language: str = "en") -> None:
        self.model_size = model_size
        self.language = language
        self._model = None

    def _get_model(self):
        if self._model is None:
            from faster_whisper import WhisperModel
            log.info("Loading Whisper model: %s", self.model_size)
            self._model = WhisperModel(
                self.model_size,
                device="auto",
                compute_type="auto",
            )
            log.info("Whisper model loaded")
        return self._model

    async def transcribe(self, audio_data: bytes, sample_rate: int = 16000) -> str:
        """Transcribe PCM s16le audio to text."""
        if not audio_data:
            return ""

        # Convert PCM to WAV in memory (faster-whisper needs a file-like)
        wav_buf = io.BytesIO()
        with wave.open(wav_buf, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(sample_rate)
            wf.writeframes(audio_data)
        wav_buf.seek(0)

        # Run in thread pool since faster-whisper is synchronous
        loop = asyncio.get_event_loop()
        text = await loop.run_in_executor(None, self._transcribe_sync, wav_buf)
        return text

    def _transcribe_sync(self, wav_buf: io.BytesIO) -> str:
        model = self._get_model()
        segments, _info = model.transcribe(
            wav_buf,
            language=self.language if self.language != "auto" else None,
            vad_filter=True,
        )
        text = " ".join(seg.text.strip() for seg in segments)
        return text.strip()
