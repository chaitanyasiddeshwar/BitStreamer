package com.bitstreamer.client.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.bitstreamer.client.discovery.ServerApi
import com.bitstreamer.client.logging.RemoteLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Provides scrubbing-preview tiles: maps a playback time to a tile in the
 * server's storyboard sprite sheets, fetching and caching whole sheets and
 * cropping the requested tile. See docs/THUMBNAILS.md. Best-effort — a failure
 * yields null, never an error.
 */
class StoryboardLoader(
    private val api: ServerApi,
    private val sb: ServerApi.Storyboard,
) {
    private val sheetCache = LruCache<Int, Bitmap>(4)
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

        sheetCache.get(sheet)?.let {
            onTile(crop(it, row, col))
            return
        }
        executor.execute {
            if (released) return@execute
            val sheetBmp = fetchSheet(sheet)
            if (sheetBmp != null) sheetCache.put(sheet, sheetBmp)
            val tile = sheetBmp?.let { crop(it, row, col) }
            mainHandler.post { if (!released) onTile(tile) }
        }
    }

    private fun crop(sheet: Bitmap, row: Int, col: Int): Bitmap? {
        val x = col * sb.tileWidth
        val y = row * sb.tileHeight
        if (x < 0 || y < 0 || x + sb.tileWidth > sheet.width || y + sb.tileHeight > sheet.height) {
            return null // last sheet may be partially filled
        }
        return try {
            Bitmap.createBitmap(sheet, x, y, sb.tileWidth, sb.tileHeight)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchSheet(sheet: Int): Bitmap? {
        return try {
            val conn = URL(api.storyboardSheetUrl(sheet)).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 8000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
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
    }
}
