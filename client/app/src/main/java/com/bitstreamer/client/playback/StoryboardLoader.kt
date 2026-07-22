package com.bitstreamer.client.playback

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.bitstreamer.client.discovery.ServerApi
import com.bitstreamer.client.logging.RemoteLog
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Provides scrubbing-preview tiles: maps a playback time to a tile in the
 * server's storyboard sprite sheets. Caches each sheet's *encoded JPEG bytes*
 * (not the decoded bitmap) and decodes only the requested tile region with a
 * BitmapRegionDecoder — so memory stays a few MB regardless of how large/
 * high-resolution the sheets are. See docs/THUMBNAILS.md. Best-effort — a
 * failure yields null, never an error.
 */
class StoryboardLoader(
    private val api: ServerApi,
    private val sb: ServerApi.Storyboard,
    private val path: String? = null,
    private val rootIndex: Int? = null,
) {
    // Cache encoded sheet bytes, not decoded bitmaps: ~1-2 MB per sheet vs. tens
    // of MB decoded, so higher-resolution sheets don't blow up the heap.
    private val sheetBytes = LruCache<Int, ByteArray>(CACHED_SHEETS)
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var released = false

    /** Returns the preview tile for [timeMs] via [onTile] on the main thread. */
    fun tileForTime(timeMs: Long, onTile: (Bitmap?) -> Unit) {
        val tileIndex = (timeMs / sb.intervalMs).toInt().coerceIn(0, (sb.tileCount - 1).coerceAtLeast(0))
        val sheet = tileIndex / sb.tilesPerSheet
        val within = tileIndex % sb.tilesPerSheet
        val row = within / sb.cols
        val col = within % sb.cols

        sheetBytes.get(sheet)?.let {
            onTile(cropTile(it, row, col))
            return
        }
        executor.execute {
            if (released) return@execute
            val bytes = fetchSheetBytes(sheet)
            if (bytes != null) sheetBytes.put(sheet, bytes)
            val tile = bytes?.let { cropTile(it, row, col) }
            mainHandler.post { if (!released) onTile(tile) }
        }
    }

    /** Decodes just one tile's region from the sheet's encoded JPEG bytes. */
    private fun cropTile(bytes: ByteArray, row: Int, col: Int): Bitmap? {
        val x = col * sb.tileWidth
        val y = row * sb.tileHeight
        return try {
            @Suppress("DEPRECATION") // newInstance(byte[],...) is fine on minSdk 25
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
            try {
                if (x + sb.tileWidth > decoder.width || y + sb.tileHeight > decoder.height) {
                    return null // last sheet may be partially filled
                }
                decoder.decodeRegion(Rect(x, y, x + sb.tileWidth, y + sb.tileHeight), null)
            } finally {
                decoder.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchSheetBytes(sheet: Int): ByteArray? {
        return try {
            val url = api.storyboardSheetUrl(sheet, path, rootIndex)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 8000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { input ->
                val buf = ByteArrayOutputStream()
                input.copyTo(buf)
                buf.toByteArray()
            }
        } catch (e: Exception) {
            RemoteLog.d(TAG, "storyboard sheet $sheet fetch failed: ${e.message}")
            null
        }
    }

    fun release() {
        released = true
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "Storyboard"
        private const val CACHED_SHEETS = 4
    }
}
