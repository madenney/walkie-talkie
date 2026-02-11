"""OpenAI TTS streaming implementation with sentence-level chunking."""

from __future__ import annotations

import logging
import re
from typing import AsyncIterator

import openai

from .base import TTSEngine

log = logging.getLogger(__name__)

# Split on sentence boundaries for low-latency TTS
SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+")


class OpenAITTS(TTSEngine):
    """Text-to-speech using OpenAI's streaming TTS API."""

    def __init__(
        self,
        api_key: str,
        model: str = "gpt-4o-mini-tts",
        voice: str = "nova",
        speed: float = 1.0,
        instructions: str = "",
    ) -> None:
        self.client = openai.AsyncOpenAI(api_key=api_key)
        self.model = model
        self.voice = voice
        self.speed = speed
        self.instructions = instructions

    async def synthesize(self, text: str) -> AsyncIterator[bytes]:
        """Stream TTS audio for the given text.

        Splits text into sentences and streams each one for low latency.
        Yields MP3 audio chunks.
        """
        # Split into sentences for faster first-byte
        sentences = SENTENCE_SPLIT.split(text.strip())
        sentences = [s.strip() for s in sentences if s.strip()]

        if not sentences:
            return

        for sentence in sentences:
            async for chunk in self._synthesize_chunk(sentence):
                yield chunk

    async def _synthesize_chunk(self, text: str) -> AsyncIterator[bytes]:
        """Stream TTS for a single text chunk."""
        try:
            kwargs = dict(
                model=self.model,
                voice=self.voice,
                input=text,
                response_format="mp3",
                speed=self.speed,
            )
            if self.instructions:
                kwargs["instructions"] = self.instructions
            async with self.client.audio.speech.with_streaming_response.create(
                **kwargs,
            ) as response:
                async for chunk in response.iter_bytes(chunk_size=4096):
                    yield chunk
        except Exception:
            log.exception("TTS synthesis failed for chunk")
