package com.walkietalkie.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AudioPlayer"

/**
 * Streams MP3 audio from the server via ExoPlayer.
 *
 * Uses [StreamingDataSource] as a pipe: chunks fed from the WebSocket
 * are read by ExoPlayer's loader thread. Playback begins as soon as
 * ExoPlayer has enough data to decode the first MP3 frame.
 */
class AudioPlayer(private val context: Context) {

    private var player: ExoPlayer? = null
    private var streamingSource: StreamingDataSource? = null

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
        val p = player ?: return

        // Tear down any previous stream
        p.stop()
        streamingSource?.close()

        // Set up a fresh streaming pipe and start playback immediately.
        // ExoPlayer will block on read() until the first chunks arrive,
        // then begin decoding and playing as soon as it can.
        val source = StreamingDataSource()
        streamingSource = source

        val mediaSource = ProgressiveMediaSource
            .Factory(StreamingDataSource.Factory(source))
            .createMediaSource(MediaItem.fromUri(Uri.parse("streaming://tts")))

        p.setMediaSource(mediaSource)
        p.prepare()
        p.play()
        Log.d(TAG, "Streaming playback started")
    }

    fun onTtsChunk(data: ByteArray) {
        streamingSource?.feed(data)
    }

    fun onTtsEnd() {
        streamingSource?.finish()
        Log.d(TAG, "TTS stream ended")
    }

    fun stop() {
        player?.stop()
        streamingSource?.close()
        streamingSource = null
        _isPlaying.value = false
    }

    fun release() {
        player?.release()
        player = null
        streamingSource?.close()
        streamingSource = null
        _isPlaying.value = false
    }
}
