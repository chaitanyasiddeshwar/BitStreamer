package com.bitstreamer.client.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.bitstreamer.client.logging.RemoteLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fetches chapter thumbnails from the server's /chapter-thumb?index=N endpoint
 * (the server generates them with ffmpeg — see docs/THUMBNAILS.md). This
 * replaced on-device MediaMetadataRetriever extraction, which returned black/
 * null frames on Fire TV because the single hardware decoder is busy with
 * playback. Results are cached; failures yield null, never an error.
 */
class ChapterThumbnailLoader(
    private val baseUrl: String,
    private val path: String? = null,
    private val rootIndex: Int? = null
) {

    private val cache = LruCache<Int, Bitmap>(64)
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var released = false

    /**
     * Requests the thumbnail for chapter [index]; [onLoaded] is called on the
     * main thread with the bitmap (or null). Cached results return immediately.
     */
    fun request(index: Int, onLoaded: (Bitmap?) -> Unit) {
        cache.get(index)?.let {
            onLoaded(it)
            return
        }
        executor.execute {
            if (released) return@execute
            val bmp = fetch(index)
            if (bmp != null) cache.put(index, bmp)
            mainHandler.post { if (!released) onLoaded(bmp) }
        }
    }

    private fun fetch(index: Int): Bitmap? {
        return try {
            val params = mutableListOf("index=$index")
            if (rootIndex != null) params.add("root=$rootIndex")
            if (!path.isNullOrEmpty()) params.add("path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            val conn = URL("$baseUrl/chapter-thumb?${params.joinToString("&")}").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 8000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            RemoteLog.d(TAG, "thumbnail fetch for chapter $index failed: ${e.message}")
            null
        }
    }

    fun release() {
        released = true
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "ChapterThumbs"
    }
}
