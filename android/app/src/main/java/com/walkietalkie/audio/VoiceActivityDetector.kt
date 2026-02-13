package com.walkietalkie.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

private const val TAG = "VAD"

// Fixed thresholds
private const val SILENCE_RMS_THRESHOLD = 150.0
private const val MIN_SPEECH_RMS = 200.0  // Absolute minimum speech threshold

// Adaptive noise floor
private const val NOISE_EMA_ALPHA = 0.1          // ~1 second to adapt (10 chunks × 100ms)
private const val NOISE_MULTIPLIER = 3.0          // Speech must be 3× above noise floor
private const val INITIAL_NOISE_FLOOR = 80.0      // Conservative initial estimate

// Timing (chunk count assumes ~100ms per chunk at 16kHz)
private const val SPEECH_START_CHUNKS = 2
private const val SILENCE_TIMEOUT_MS = 1000L
private const val MIN_UTTERANCE_MS = 100L
private const val MAX_UTTERANCE_MS = 60000L
private const val PRE_ROLL_CHUNKS = 3

enum class VadState {
    IDLE,
    LISTENING,
    SPEECH,
    COOLDOWN,
}

class VoiceActivityDetector(
    private val audioCapture: AudioCapture,
    private val onSpeechStart: () -> Unit,
    private val onAudioChunk: (ByteArray) -> Unit,
    private val onSpeechEnd: () -> Unit,
) {
    private val _state = MutableStateFlow(VadState.IDLE)
    val state: StateFlow<VadState> = _state

    private var scope: CoroutineScope? = null
    private var silenceJob: Job? = null
    private var maxUtteranceJob: Job? = null

    // Speech start detection: count consecutive above-threshold chunks
    private var speechStartCounter = 0

    // Pre-roll ring buffer: recent chunks sent when speech starts (prevents clipping)
    private val preRollBuffer = ArrayDeque<ByteArray>(PRE_ROLL_CHUNKS + 1)

    // Track utterance start time for MIN_UTTERANCE_MS check
    private var utteranceStartMs = 0L

    // Adaptive noise floor — tracks ambient sound level
    private var noiseFloor = INITIAL_NOISE_FLOOR

    // Suppression: ignore speech detection while TTS is playing
    @Volatile var suppressed = false

    fun startListening(scope: CoroutineScope) {
        if (_state.value != VadState.IDLE) return
        this.scope = scope
        _state.value = VadState.LISTENING
        speechStartCounter = 0
        preRollBuffer.clear()
        noiseFloor = INITIAL_NOISE_FLOOR

        audioCapture.start(scope) { pcmBytes, rms ->
            onChunk(pcmBytes, rms)
        }
        Log.i(TAG, "Listening started")
    }

    fun stopListening() {
        val wasActive = _state.value == VadState.SPEECH || _state.value == VadState.COOLDOWN
        silenceJob?.cancel()
        silenceJob = null
        maxUtteranceJob?.cancel()
        maxUtteranceJob = null
        audioCapture.stop()

        if (wasActive) {
            onSpeechEnd()
        }

        _state.value = VadState.IDLE
        speechStartCounter = 0
        preRollBuffer.clear()
        Log.i(TAG, "Listening stopped")
    }

    private fun onChunk(pcmBytes: ByteArray, rms: Double) {
        when (_state.value) {
            VadState.IDLE -> return

            VadState.LISTENING -> {
                // Maintain pre-roll buffer regardless of suppression
                preRollBuffer.addLast(pcmBytes)
                if (preRollBuffer.size > PRE_ROLL_CHUNKS) {
                    preRollBuffer.removeFirst()
                }

                // While suppressed (TTS playing), don't detect speech or update noise floor
                // (speaker output would inflate the noise estimate)
                if (suppressed) {
                    speechStartCounter = 0
                    return
                }

                // Update adaptive noise floor (exponential moving average)
                noiseFloor = noiseFloor * (1 - NOISE_EMA_ALPHA) + rms * NOISE_EMA_ALPHA
                val threshold = max(noiseFloor * NOISE_MULTIPLIER, MIN_SPEECH_RMS)

                if (rms > threshold) {
                    speechStartCounter++
                    if (speechStartCounter >= SPEECH_START_CHUNKS) {
                        // Speech confirmed — transition to SPEECH
                        _state.value = VadState.SPEECH
                        utteranceStartMs = System.currentTimeMillis()
                        Log.d(TAG, "Speech detected (RMS=${"%.0f".format(rms)}, threshold=${"%.0f".format(threshold)}, noise=${"%.0f".format(noiseFloor)})")
                        onSpeechStart()

                        // Send pre-roll chunks to avoid clipping the start
                        for (chunk in preRollBuffer) {
                            onAudioChunk(chunk)
                        }
                        preRollBuffer.clear()

                        // Start max utterance timer
                        startMaxUtteranceTimer()
                    }
                } else {
                    speechStartCounter = 0
                }
            }

            VadState.SPEECH -> {
                onAudioChunk(pcmBytes)

                if (rms < SILENCE_RMS_THRESHOLD) {
                    // Transition to COOLDOWN — start silence timer
                    _state.value = VadState.COOLDOWN
                    startSilenceTimer()
                }
            }

            VadState.COOLDOWN -> {
                // Keep sending audio during cooldown (it might be a brief pause)
                onAudioChunk(pcmBytes)

                // Use adaptive threshold for speech resumption too
                val threshold = max(noiseFloor * NOISE_MULTIPLIER, MIN_SPEECH_RMS)
                if (rms > threshold) {
                    // Speech resumed — cancel silence timer, go back to SPEECH
                    silenceJob?.cancel()
                    silenceJob = null
                    _state.value = VadState.SPEECH
                }
            }
        }
    }

    private fun startSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = scope?.launch {
            delay(SILENCE_TIMEOUT_MS)
            // Timer expired — end utterance
            val duration = System.currentTimeMillis() - utteranceStartMs
            if (duration < MIN_UTTERANCE_MS) {
                Log.d(TAG, "Discarding short utterance (${duration}ms)")
            } else {
                Log.d(TAG, "Speech ended (${duration}ms)")
                onSpeechEnd()
            }
            maxUtteranceJob?.cancel()
            maxUtteranceJob = null
            speechStartCounter = 0
            _state.value = VadState.LISTENING
        }
    }

    private fun startMaxUtteranceTimer() {
        maxUtteranceJob?.cancel()
        maxUtteranceJob = scope?.launch {
            delay(MAX_UTTERANCE_MS)
            Log.w(TAG, "Max utterance duration reached, forcing end")
            silenceJob?.cancel()
            silenceJob = null
            onSpeechEnd()
            speechStartCounter = 0
            _state.value = VadState.LISTENING
        }
    }
}
