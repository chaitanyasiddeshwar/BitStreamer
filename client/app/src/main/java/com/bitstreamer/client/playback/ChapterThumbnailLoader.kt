package com.bitstreamer.client.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.LruCache
import com.bitstreamer.client.logging.RemoteLog

/**
 * Extracts chapter thumbnails on the device (Option A — see docs/MEDIA3.md):
 * the Fire TV already hardware-decodes this exact stream, so we grab a frame
 * per chapter with MediaMetadataRetriever over the same HTTP URL. All work runs
 * on one background thread reusing a single retriever; results are cached.
 * Purely best-effort: any failure yields a null bitmap, never an error.
 */
class ChapterThumbnailLoader(private val url: String) {

    private val thread = HandlerThread("chapter-thumbs").apply { start() }
    private val bgHandler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<Long, Bitmap>(64)
    private var retriever: MediaMetadataRetriever? = null
    private var released = false

    /**
     * Requests the thumbnail for [timeMs]; [onLoaded] is called on the main
     * thread with the bitmap (or null). Cached results return immediately.
     */
    fun request(timeMs: Long, onLoaded: (Bitmap?) -> Unit) {
        cache.get(timeMs)?.let {
            onLoaded(it)
            return
        }
        bgHandler.post {
            if (released) return@post
            val bmp = extract(timeMs)
            if (bmp != null) cache.put(timeMs, bmp)
            mainHandler.post { onLoaded(bmp) }
        }
    }

    private fun extract(timeMs: Long): Bitmap? {
        return try {
            val r = retriever ?: MediaMetadataRetriever().also {
                it.setDataSource(url, HashMap<String, String>())
                retriever = it
            }
            // OPTION_CLOSEST_SYNC lands on the nearest keyframe (fast, no decode
            // of a GOP). Scale down to keep memory small.
            val frame = r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            scale(frame)
        } catch (e: Exception) {
            RemoteLog.d(TAG, "thumbnail extract failed at ${timeMs}ms: ${e.message}")
            null
        }
    }

    private fun scale(src: Bitmap): Bitmap {
        val targetW = 320
        if (src.width <= targetW) return src
        val targetH = (src.height.toFloat() / src.width * targetW).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    fun release() {
        released = true
        bgHandler.post {
            try {
                retriever?.release()
            } catch (_: Exception) {
            }
            retriever = null
        }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "ChapterThumbs"
    }
}
