"""Audio format conversion utilities."""

from __future__ import annotations

import io
import struct
import wave

import numpy as np


def pcm_to_wav(pcm_data: bytes, sample_rate: int = 16000, channels: int = 1) -> bytes:
    """Convert raw PCM s16le bytes to WAV format."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(2)  # 16-bit
        wf.setframerate(sample_rate)
        wf.writeframes(pcm_data)
    return buf.getvalue()


def pcm_to_float32(pcm_data: bytes) -> np.ndarray:
    """Convert PCM s16le bytes to float32 numpy array in [-1, 1]."""
    samples = np.frombuffer(pcm_data, dtype=np.int16)
    return samples.astype(np.float32) / 32768.0


def compute_rms(pcm_data: bytes) -> float:
    """Compute RMS energy of PCM s16le audio."""
    if len(pcm_data) < 2:
        return 0.0
    samples = np.frombuffer(pcm_data, dtype=np.int16)
    return float(np.sqrt(np.mean(samples.astype(np.float64) ** 2)))
