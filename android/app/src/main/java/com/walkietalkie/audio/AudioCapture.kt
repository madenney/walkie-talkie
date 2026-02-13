package com.walkietalkie.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

private const val TAG = "AudioCapture"
const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

class AudioCapture(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    val bufferSize: Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        .coerceAtLeast(SAMPLE_RATE * 2) // at least 1 second buffer

    /**
     * Start capturing audio. Calls [onAudioChunk] with every PCM chunk and its RMS value.
     * The caller is responsible for deciding what to do with silence vs speech.
     */
    fun start(scope: CoroutineScope, onAudioChunk: (pcmBytes: ByteArray, rms: Double) -> Unit) {
        if (_isRecording.value) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }

        audioRecord = record
        record.startRecording()
        _isRecording.value = true

        // Enable hardware echo cancellation (removes speaker output from mic input)
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                    setEnabled(true)
                    Log.i(TAG, "AcousticEchoCanceler enabled")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AEC unavailable", e)
        }

        // Enable hardware noise suppression (filters ambient noise)
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply {
                    setEnabled(true)
                    Log.i(TAG, "NoiseSuppressor enabled")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor unavailable", e)
        }

        captureJob = scope.launch(Dispatchers.IO) {
            // Read in ~100ms chunks for responsive VAD, regardless of internal buffer size
            val chunkSamples = SAMPLE_RATE / 10  // 1600 samples = 100ms
            val buffer = ShortArray(chunkSamples)
            while (isActive && _isRecording.value) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val rms = computeRms(buffer, read)
                    val bytes = shortsToBytes(buffer, read)
                    onAudioChunk(bytes, rms)
                }
            }
        }

        Log.i(TAG, "Recording started (sample rate: $SAMPLE_RATE, buffer: $bufferSize)")
    }

    fun stop() {
        _isRecording.value = false
        captureJob?.cancel()
        captureJob = null
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Recording stopped")
    }

    companion object {
        fun computeRms(samples: ShortArray, count: Int): Double {
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i].toDouble()
                sum += s * s
            }
            return sqrt(sum / count)
        }
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
