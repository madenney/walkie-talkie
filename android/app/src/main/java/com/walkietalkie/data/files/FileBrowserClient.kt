package com.walkietalkie.data.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
data class FsRoot(val name: String, val path: String)

@Serializable
private data class RootsResponse(val roots: List<FsRoot> = emptyList())

@Serializable
data class FsEntry(
    val name: String,
    val path: String,
    @SerialName("is_dir") val isDir: Boolean,
    val size: Long = 0,
    val mime: String = "",
    val mtime: Double = 0.0,
)

@Serializable
data class FsListing(val path: String, val entries: List<FsEntry> = emptyList())

/**
 * Talks to the server's read-only file API (the /fs routes) over HTTP — separate from
 * the realtime WebSocket so big reads don't stall audio. [baseUrl] is the http
 * origin (e.g. `http://100.x:8765`), derived from the configured ws URL.
 */
class FileBrowserClient(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun roots(): List<FsRoot> =
        json.decodeFromString<RootsResponse>(getString("$base/fs/roots")).roots

    suspend fun list(path: String): FsListing {
        val url = "$base/fs/list".toHttpUrl().newBuilder()
            .addQueryParameter("path", path).build().toString()
        return json.decodeFromString<FsListing>(getString(url))
    }

    /** The raw GET URL for a file's bytes (used for downloads / streaming). */
    fun readUrl(path: String): String =
        "$base/fs/read".toHttpUrl().newBuilder().addQueryParameter("path", path).build().toString()

    suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(readUrl(path)).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body!!.bytes()
        }
    }

    /** Stream a file to [dest] on disk (for "open with" hand-off). */
    suspend fun download(path: String, dest: File): Unit = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(readUrl(path)).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
    }

    private suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body!!.string()
        }
    }
}
