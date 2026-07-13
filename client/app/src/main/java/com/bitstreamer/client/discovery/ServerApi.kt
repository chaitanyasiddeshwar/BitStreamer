package com.bitstreamer.client.discovery

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small client for the server's non-streaming endpoints. All methods are
 * blocking and swallow errors — call from a background thread; a dead server
 * must never disturb playback.
 */
class ServerApi(private val baseUrl: String) {

    /** A chapter marker parsed by the server from the MKV. */
    data class Chapter(val startMs: Long, val name: String)

    /** Server metadata relevant to the player. */
    data class Info(
        val name: String,
        val file: String,
        val sizeBytes: Long,
        val mime: String,
        val chapters: List<Chapter>,
        val thumbnailsAvailable: Boolean,
        val storyboardAvailable: Boolean,
        // Authoritative colour info from the server's ffprobe (Media3's client-side
        // colorInfo is unreliable, so we trust the server here).
        val videoHdr: Boolean,
        val videoHdr10Plus: Boolean,
        val videoTransfer: String,
        val videoColorSpace: String,
        val dvProfile: Int,
    )

    /** Scrubbing-preview (storyboard) layout — see docs/THUMBNAILS.md. */
    data class Storyboard(
        val intervalMs: Long,
        val durationMs: Long,
        val tileWidth: Int,
        val tileHeight: Int,
        val cols: Int,
        val rows: Int,
        val tilesPerSheet: Int,
        val tileCount: Int,
        val sheetCount: Int,
    )

    /** Reads /info (full media metadata). Returns null if the server is unreachable. */
    fun getInfo(): Info? {
        return try {
            val json = getJson("$baseUrl/info")
            val arr = json.optJSONArray("chapters")
            val chapters = if (arr == null) emptyList() else
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Chapter(o.optLong("startMs", 0), o.optString("name", ""))
                }
            val video = json.optJSONObject("video")
            Info(
                name = json.optString("name", ""),
                file = json.optString("file", ""),
                sizeBytes = json.optLong("size", 0),
                mime = json.optString("mime", ""),
                chapters = chapters,
                thumbnailsAvailable = json.optBoolean("thumbnails", false),
                storyboardAvailable = json.optBoolean("storyboard", false),
                videoHdr = video?.optBoolean("hdr", false) ?: false,
                videoHdr10Plus = video?.optBoolean("hdr10plus", false) ?: false,
                videoTransfer = video?.optString("transfer", "") ?: "",
                videoColorSpace = video?.optString("colorSpace", "") ?: "",
                dvProfile = video?.optInt("dvProfile", -1) ?: -1,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Fetches the storyboard manifest, or null if not ready / unavailable. */
    fun getStoryboard(): Storyboard? {
        return try {
            val j = getJson("$baseUrl/storyboard.json")
            Storyboard(
                intervalMs = j.optLong("intervalMs", 0),
                durationMs = j.optLong("durationMs", 0),
                tileWidth = j.optInt("tileWidth", 0),
                tileHeight = j.optInt("tileHeight", 0),
                cols = j.optInt("cols", 0),
                rows = j.optInt("rows", 0),
                tilesPerSheet = j.optInt("tilesPerSheet", 0),
                tileCount = j.optInt("tileCount", 0),
                sheetCount = j.optInt("sheetCount", 0),
            ).takeIf { it.intervalMs > 0 && it.tileWidth > 0 && it.cols > 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** URL of storyboard sprite sheet [sheet]. */
    fun storyboardSheetUrl(sheet: Int): String = "$baseUrl/storyboard?sheet=$sheet"

    /** URL of the server-generated thumbnail for chapter [index]. */
    fun chapterThumbUrl(index: Int): String = "$baseUrl/chapter-thumb?index=$index"

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(body)
    }

    /** Last stored resume position for this client, or 0 if none. */
    fun getResumePositionMs(): Long {
        return try {
            val conn = URL("$baseUrl/position").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(body).optLong("positionMs", 0)
        } catch (_: Exception) {
            0
        }
    }

    /** Stores the playback position; 0 clears the stored position. */
    fun postPosition(positionMs: Long) {
        try {
            val conn = URL("$baseUrl/position?ms=$positionMs").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}
