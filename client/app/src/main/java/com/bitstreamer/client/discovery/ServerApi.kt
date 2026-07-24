package com.bitstreamer.client.discovery

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Small client for the server's non-streaming endpoints. All methods are
 * blocking and swallow errors — call from a background thread; a dead server
 * must never disturb playback.
 */
class ServerApi(private val baseUrl: String) {

    /** A chapter marker parsed by the server from the MKV. */
    data class Chapter(val startMs: Long, val name: String)

    /** One entry in a folder listing (folder mode). */
    data class FolderEntry(val name: String, val isDir: Boolean, val sizeBytes: Long, val mime: String)

    /** A sidecar subtitle file served alongside the movie (e.g. movie1.en.srt). */
    data class SubtitleTrack(val url: String, val label: String, val lang: String, val mime: String)

    /** A browsable root directory (multi-root mode). */
    data class Root(val index: Int, val name: String)

    /** An audio track parsed by the server. */
    data class AudioTrackInfo(
        val index: Int,
        val codecName: String,
        val bitrate: Long,
        val channels: Int,
        val channelLayout: String,
        val language: String,
        val title: String
    )

    /** Server metadata relevant to the player. */
    data class Info(
        val mode: String, // "file", "folder", or "multi"
        val name: String,
        val file: String,
        val sizeBytes: Long,
        val mime: String,
        val chapters: List<Chapter>,
        val thumbnailsAvailable: Boolean,
        val storyboardAvailable: Boolean,
        // Seek-bar step / preview interval (ms) — smaller for short videos. Present
        // even when previews haven't been generated yet; 0 if the server omits it.
        val seekIntervalMs: Long,
        // Authoritative colour info from the server's ffprobe (Media3's client-side
        // colorInfo is unreliable, so we trust the server here).
        val videoHdr: Boolean,
        val videoHdr10Plus: Boolean,
        val videoTransfer: String,
        val videoColorSpace: String,
        val dvProfile: Int,
        val videoBitrate: Long,
        val audioBitrate: Long,
        val videoCodec: String,
        val videoProfile: String,
        val videoLevel: String,
        val videoRFrameRate: String,
        val videoAvgFrameRate: String,
        val videoPixFmt: String,
        val videoBitsPerRawSample: Int,
        val stripDV: Boolean,
        val audioTracks: List<AudioTrackInfo>,
        // On-disk sidecar subtitle files (movie1.srt etc.) the server offers.
        val subtitles: List<SubtitleTrack>,
        // Multi-root mode: the available roots (empty for file/folder mode).
        val roots: List<Root> = emptyList(),
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

    /**
     * Reads /info. For folder mode, pass the relative [path] of a file to get its
     * metadata; null/"" reads the root marker. For multi-root mode, pass [rootIndex]
     * to select the root. Returns null if unreachable.
     */
    fun getInfo(path: String? = null, rootIndex: Int? = null): Info? {
        return try {
            val params = mutableListOf<String>()
            if (rootIndex != null) params.add("root=$rootIndex")
            if (!path.isNullOrEmpty()) params.add("path=${enc(path)}")
            val url = if (params.isEmpty()) "$baseUrl/info" else "$baseUrl/info?${params.joinToString("&")}"
            val json = getJson(url)

            // Parse roots array for multi mode.
            val rootsArr = json.optJSONArray("roots")
            val roots = if (rootsArr == null) emptyList() else
                (0 until rootsArr.length()).mapNotNull { i ->
                    val o = rootsArr.optJSONObject(i) ?: return@mapNotNull null
                    Root(o.optInt("index", 0), o.optString("name", ""))
                }

            val arr = json.optJSONArray("chapters")
            val chapters = if (arr == null) emptyList() else
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Chapter(o.optLong("startMs", 0), o.optString("name", ""))
                }
            val subsArr = json.optJSONArray("subtitles")
            val subtitles = if (subsArr == null) emptyList() else
                (0 until subsArr.length()).mapNotNull { i ->
                    val o = subsArr.optJSONObject(i) ?: return@mapNotNull null
                    val u = o.optString("url", "")
                    if (u.isEmpty()) null else SubtitleTrack(
                        url = u,
                        label = o.optString("label", "Subtitle"),
                        lang = o.optString("lang", ""),
                        mime = o.optString("mime", ""),
                    )
                }
            val audioArr = json.optJSONArray("audioTracks")
            val audioTracks = if (audioArr == null) emptyList() else
                (0 until audioArr.length()).mapNotNull { i ->
                    val o = audioArr.optJSONObject(i) ?: return@mapNotNull null
                    AudioTrackInfo(
                        index = o.optInt("index", 0),
                        codecName = o.optString("codec_name", ""),
                        bitrate = o.optLong("bitrate", 0L),
                        channels = o.optInt("channels", 0),
                        channelLayout = o.optString("channel_layout", ""),
                        language = o.optString("language", ""),
                        title = o.optString("title", "")
                    )
                }
            val video = json.optJSONObject("video")
            Info(
                mode = json.optString("mode", "file"),
                name = json.optString("name", ""),
                file = json.optString("file", ""),
                sizeBytes = json.optLong("size", 0),
                mime = json.optString("mime", ""),
                chapters = chapters,
                thumbnailsAvailable = json.optBoolean("thumbnails", false),
                storyboardAvailable = json.optBoolean("storyboard", false),
                seekIntervalMs = json.optLong("seekIntervalMs", 0L),
                videoHdr = video?.optBoolean("hdr", false) ?: false,
                videoHdr10Plus = video?.optBoolean("hdr10plus", false) ?: false,
                videoTransfer = video?.optString("transfer", "") ?: "",
                videoColorSpace = video?.optString("colorSpace", "") ?: "",
                dvProfile = video?.optInt("dvProfile", -1) ?: -1,
                videoBitrate = video?.optLong("videoBitrate", 0L) ?: 0L,
                audioBitrate = video?.optLong("audioBitrate", 0L) ?: 0L,
                videoCodec = video?.optString("codec", "") ?: "",
                videoProfile = video?.optString("profile", "") ?: "",
                videoLevel = video?.optString("level", "") ?: "",
                videoRFrameRate = video?.optString("rFrameRate", "") ?: "",
                videoAvgFrameRate = video?.optString("avgFrameRate", "") ?: "",
                videoPixFmt = video?.optString("pixFmt", "") ?: "",
                videoBitsPerRawSample = video?.optInt("bitsPerRawSample", 0) ?: 0,
                stripDV = video?.optBoolean("stripDV", false) ?: false,
                audioTracks = audioTracks,
                subtitles = subtitles,
                roots = roots,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Fetches the storyboard manifest, or null if not ready / unavailable. */
    fun getStoryboard(path: String? = null, rootIndex: Int? = null): Storyboard? {
        return try {
            val params = mutableListOf<String>()
            if (rootIndex != null) params.add("root=$rootIndex")
            if (!path.isNullOrEmpty()) params.add("path=${enc(path)}")
            val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
            val j = getJson("$baseUrl/storyboard.json$query")
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
    fun storyboardSheetUrl(sheet: Int, path: String? = null, rootIndex: Int? = null): String {
        val params = mutableListOf("sheet=$sheet")
        if (rootIndex != null) params.add("root=$rootIndex")
        if (!path.isNullOrEmpty()) params.add("path=${enc(path)}")
        return "$baseUrl/storyboard?${params.joinToString("&")}"
    }

    /** URL of the server-generated thumbnail for chapter [index]. */
    fun chapterThumbUrl(index: Int, path: String? = null, rootIndex: Int? = null): String {
        val params = mutableListOf("index=$index")
        if (rootIndex != null) params.add("root=$rootIndex")
        if (!path.isNullOrEmpty()) params.add("path=${enc(path)}")
        return "$baseUrl/chapter-thumb?${params.joinToString("&")}"
    }

    /** Lists a directory (folder mode). Pass [rootIndex] in multi-root mode. Empty on error. */
    fun list(path: String, rootIndex: Int? = null): List<FolderEntry> {
        return try {
            val params = mutableListOf("path=${enc(path)}")
            if (rootIndex != null) params.add(0, "root=$rootIndex")
            val json = getJson("$baseUrl/list?${params.joinToString("&")}")
            val arr = json.optJSONArray("entries") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                FolderEntry(o.optString("name", ""), o.optBoolean("dir", false),
                    o.optLong("size", 0), o.optString("mime", ""))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Stream URL for the single-file server. */
    fun streamUrl(): String = "$baseUrl/stream"

    /** Stream URL for a file at [path] within the served folder. Pass [rootIndex] in multi-root mode. */
    fun streamUrlForPath(path: String, rootIndex: Int? = null): String {
        val params = mutableListOf("path=${enc(path)}")
        if (rootIndex != null) params.add(0, "root=$rootIndex")
        return "$baseUrl/stream?${params.joinToString("&")}"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(body)
    }

    /** Last stored resume position for this client, or 0 if none. */
    fun getResumePositionMs(path: String? = null, rootIndex: Int? = null): Long {
        return try {
            val params = mutableListOf<String>()
            if (rootIndex != null) params.add("root=$rootIndex")
            if (!path.isNullOrEmpty()) params.add("path=${enc(path)}")
            val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
            val conn = URL("$baseUrl/position$query").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(body).optLong("positionMs", 0)
        } catch (_: Exception) {
            0
        }
    }

    /** Stores the playback position; 0 clears the stored position. */
    fun postPosition(positionMs: Long, path: String? = null, rootIndex: Int? = null) {
        try {
            val params = mutableListOf("ms=$positionMs")
            if (rootIndex != null) params.add("root=$rootIndex")
            if (!path.isNullOrEmpty()) params.add("path=${enc(path)}")
            val query = "?${params.joinToString("&")}"
            val conn = URL("$baseUrl/position$query").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    data class PreviewStatus(
        val status: String,
        val percent: Int,
        val done: Int,
        val total: Int
    )

    fun startPreviewGeneration(path: String? = null, rootIndex: Int? = null): PreviewStatus? {
        return try {
            val params = mutableListOf<String>()
            if (rootIndex != null) params.add("root=$rootIndex")
            if (!path.isNullOrEmpty()) params.add("path=${enc(path)}")
            val url = if (params.isEmpty()) "$baseUrl/generate-previews" else "$baseUrl/generate-previews?${params.joinToString("&")}"
            val json = postJson(url)
            PreviewStatus(
                status = json.optString("status", "idle"),
                percent = json.optInt("percent", 0),
                done = json.optInt("done", 0),
                total = json.optInt("total", 0)
            )
        } catch (e: Exception) {
            com.bitstreamer.client.logging.RemoteLog.d("ServerApi", "startPreviewGeneration error for $path: ${e.message}")
            null
        }
    }

    fun getPreviewStatus(path: String? = null, rootIndex: Int? = null): PreviewStatus? {
        return try {
            val params = mutableListOf<String>()
            if (rootIndex != null) params.add("root=$rootIndex")
            if (!path.isNullOrEmpty()) params.add("path=${enc(path)}")
            val url = if (params.isEmpty()) "$baseUrl/preview-status" else "$baseUrl/preview-status?${params.joinToString("&")}"
            val json = getJson(url)
            PreviewStatus(
                status = json.optString("status", "idle"),
                percent = json.optInt("percent", 0),
                done = json.optInt("done", 0),
                total = json.optInt("total", 0)
            )
        } catch (e: Exception) {
            com.bitstreamer.client.logging.RemoteLog.d("ServerApi", "getPreviewStatus error for $path: ${e.message}")
            null
        }
    }

    private fun postJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(body)
    }
}
