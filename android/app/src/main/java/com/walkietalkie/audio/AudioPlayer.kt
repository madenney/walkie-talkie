package com.walkietalkie.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AudioPlayer"

/**
 * Lifecycle of one spoken response, distinct from [AudioPlayer.isPlaying]
 * (which flickers on buffer underruns). This stays stable: it only leaves
 * PLAYING when the user pauses, the stream ends, or playback is stopped — so
 * the UI can keep a bubble highlighted across mid-stream buffering gaps.
 */
enum class PlaybackState { IDLE, PLAYING, PAUSED, ENDED }

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

    // Coarse lifecycle of the current utterance, used to drive the "speaking"
    // highlight and the tap-to-pause affordance on the message bubble.
    private val _playback = MutableStateFlow(PlaybackState.IDLE)
    val playback: StateFlow<PlaybackState> = _playback

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var pauseOtherApps = false

    fun initialize() {
        if (player != null) return
        player = ExoPlayer.Builder(context).build().apply {
            // Configure audio attributes for speech/assistant content
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_ASSISTANT)
                .build()
            // handleAudioFocus MUST be false here: ExoPlayer only allows automatic
            // focus handling for USAGE_MEDIA/USAGE_GAME, and our usage is
            // USAGE_ASSISTANT — passing true throws and crashes the app on launch.
            // We manage focus ourselves (requestAudioFocus/abandonAudioFocus) to
            // duck/pause other apps when the "pause other media" setting is on.
            setAudioAttributes(audioAttributes, false)

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _isPlaying.value = state == Player.STATE_READY && isPlaying
                    if (state == Player.STATE_ENDED) {
                        _isPlaying.value = false
                        // Natural end of the utterance — drop the highlight.
                        _playback.value = PlaybackState.ENDED
                        abandonAudioFocus()
                    }
                }

                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    _isPlaying.value = isPlayingNow
                    // Only PROMOTE to PLAYING here; never infer pause/end from a
                    // false (that also fires on buffer underruns mid-stream).
                    // PAUSED/ENDED/IDLE are set explicitly by pause()/stop()/ENDED.
                    if (isPlayingNow) {
                        _playback.value = PlaybackState.PLAYING
                    } else {
                        abandonAudioFocus()
                    }
                }
            })
        }
    }

    fun setPauseOtherApps(pause: Boolean) {
        pauseOtherApps = pause
    }

    private fun requestAudioFocus() {
        if (!pauseOtherApps) return

        val focusGain = if (pauseOtherApps) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }

        val request = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            .setWillPauseWhenDucked(true)
            .build()

        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
        Log.d(TAG, "Audio focus requested")
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
            Log.d(TAG, "Audio focus abandoned")
        }
    }

    fun onTtsStart() {
        val p = player ?: return

        // Request audio focus to pause/duck other apps if enabled
        requestAudioFocus()

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
        // Mark PLAYING up front so the speaking-highlight shows immediately,
        // even before the first frame has buffered enough to actually sound.
        _playback.value = PlaybackState.PLAYING
        Log.d(TAG, "Streaming playback started")
    }

    /** Pause the current utterance, keeping buffered audio so it can resume. */
    fun pause() {
        val p = player ?: return
        if (_playback.value == PlaybackState.PLAYING) {
            p.pause()
            _playback.value = PlaybackState.PAUSED
        }
    }

    /** Resume a paused utterance from where it left off. */
    fun resume() {
        val p = player ?: return
        if (_playback.value == PlaybackState.PAUSED) {
            requestAudioFocus()
            p.play()
            _playback.value = PlaybackState.PLAYING
        }
    }

    /** Toggle pause/resume — what a tap on the speaking bubble does. */
    fun togglePause() {
        when (_playback.value) {
            PlaybackState.PLAYING -> pause()
            PlaybackState.PAUSED -> resume()
            else -> {}
        }
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
        _playback.value = PlaybackState.IDLE
        abandonAudioFocus()
    }

    fun release() {
        abandonAudioFocus()
        player?.release()
        player = null
        streamingSource?.close()
        streamingSource = null
        _isPlaying.value = false
        _playback.value = PlaybackState.IDLE
    }
}
