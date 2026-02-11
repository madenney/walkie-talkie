"""Silero VAD (Voice Activity Detection)."""

from __future__ import annotations

import asyncio
import logging

import numpy as np

log = logging.getLogger(__name__)


class SileroVAD:
    """Voice activity detection using Silero VAD."""

    def __init__(self, threshold: float = 0.5, sample_rate: int = 16000) -> None:
        self.threshold = threshold
        self.sample_rate = sample_rate
        self._model = None

    def _get_model(self):
        if self._model is None:
            import torch
            model, utils = torch.hub.load(
                repo_or_dir="snakers4/silero-vad",
                model="silero_vad",
                trust_repo=True,
            )
            self._model = model
            log.info("Silero VAD model loaded")
        return self._model

    def detect_speech(self, audio_chunk: np.ndarray) -> float:
        """Return speech probability for an audio chunk.

        Args:
            audio_chunk: float32 numpy array, values in [-1, 1]

        Returns:
            Speech probability between 0 and 1.
        """
        import torch

        model = self._get_model()
        tensor = torch.from_numpy(audio_chunk)
        prob = model(tensor, self.sample_rate).item()
        return float(prob)

    async def detect_speech_async(self, audio_chunk: np.ndarray) -> float:
        """Async wrapper for detect_speech."""
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self.detect_speech, audio_chunk)

    def reset(self) -> None:
        """Reset VAD state between utterances."""
        if self._model is not None:
            self._model.reset_states()
