package com.walkietalkie.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A DataSource that acts as a pipe for streaming audio to ExoPlayer.
 *
 * Chunks are fed in via [feed] (from the WebSocket thread) and read out
 * by ExoPlayer's loader thread via [read]. The read blocks until data
 * is available, so ExoPlayer starts playback as soon as it has enough
 * MP3 data to decode the first frame.
 */
class StreamingDataSource : BaseDataSource(/* isNetwork= */ false) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var currentChunk: ByteArray? = null
    private var currentOffset = 0
    @Volatile private var closed = false

    /** Feed a chunk of audio data. Called from the WebSocket thread. */
    fun feed(data: ByteArray) {
        if (!closed && data.isNotEmpty()) {
            queue.put(data)
        }
    }

    /** Signal that no more data will arrive. Unblocks the reader. */
    fun finish() {
        if (!closed) {
            queue.put(SENTINEL)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (currentChunk == null || currentOffset >= currentChunk!!.size) {
            if (closed) return C.RESULT_END_OF_INPUT

            val next = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return C.RESULT_END_OF_INPUT
            }

            if (next == null) continue // poll timed out, loop and check closed flag
            if (next.isEmpty()) return C.RESULT_END_OF_INPUT // sentinel

            currentChunk = next
            currentOffset = 0
        }

        val chunk = currentChunk!!
        val available = chunk.size - currentOffset
        val toRead = minOf(available, length)
        System.arraycopy(chunk, currentOffset, buffer, offset, toRead)
        currentOffset += toRead
        return toRead
    }

    override fun getUri(): Uri = STREAMING_URI

    override fun close() {
        closed = true
        queue.clear()
        queue.put(SENTINEL) // unblock any waiting reader
        currentChunk = null
        currentOffset = 0
    }

    class Factory(private val source: StreamingDataSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = source
    }

    companion object {
        private val SENTINEL = ByteArray(0)
        private val STREAMING_URI = Uri.parse("streaming://tts")
    }
}
