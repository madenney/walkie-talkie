package com.walkietalkie.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

private const val TAG = "AudioPlayer"

/**
 * Streams MP3 audio chunks from the server using ExoPlayer.
 *
 * Audio chunks are accumulated into a buffer. When TTS ends,
 * the complete audio is played back via ExoPlayer.
 */
class AudioPlayer(private val context: Context) {

    private var player: ExoPlayer? = null
    private val audioBuffer = ByteArrayOutputStream()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    fun initialize() {
        if (player != null) return
        player = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _isPlaying.value = state == Player.STATE_READY && isPlaying
                    if (state == Player.STATE_ENDED) {
                        _isPlaying.value = false
                    }
                }

                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    _isPlaying.value = isPlayingNow
                }
            })
        }
    }

    fun onTtsStart() {
        audioBuffer.reset()
    }

    fun onTtsChunk(data: ByteArray) {
        audioBuffer.write(data)
    }

    fun onTtsEnd() {
        val audioData = audioBuffer.toByteArray()
        audioBuffer.reset()

        if (audioData.isEmpty()) return

        val p = player ?: return

        try {
            val dataSource = ByteArrayDataSource(audioData)
            val factory = DataSource.Factory { dataSource }
            val mediaSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(Uri.EMPTY))

            p.stop()
            p.setMediaSource(mediaSource)
            p.prepare()
            p.play()
            _isPlaying.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play TTS audio", e)
        }
    }

    fun stop() {
        player?.stop()
        audioBuffer.reset()
        _isPlaying.value = false
    }

    fun release() {
        player?.release()
        player = null
        audioBuffer.reset()
        _isPlaying.value = false
    }
}
