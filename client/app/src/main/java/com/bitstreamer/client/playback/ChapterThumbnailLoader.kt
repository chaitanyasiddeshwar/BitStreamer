package com.bitstreamer.client.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.LruCache
import com.bitstreamer.client.logging.RemoteLog
import kotlin.math.max

/**
 * Extracts chapter thumbnails on the device (Option A — see docs/MEDIA3.md):
 * the Fire TV already hardware-decodes this exact stream, so we grab a frame
 * per chapter with MediaMetadataRetriever over the same HTTP URL. All work runs
 * on one background thread reusing a single retriever; results are cached.
 * Purely best-effort: any failure yields a null bitmap, never an error.
 *
 * Diagnostics: the first few extractions log source dimensions and whether the
 * frame came back null / black, so client-logs.txt reveals why thumbnails fail
 * on a given device (e.g. 10-bit HDR HEVC that MMR can't decode, or hardware
 * decoder contention with active playback).
 */
class ChapterThumbnailLoader(private val url: String) {

    private val thread = HandlerThread("chapter-thumbs").apply { start() }
    private val bgHandler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<Long, Bitmap>(64)
    private var retriever: MediaMetadataRetriever? = null
    private var released = false
    private var diagnosticsLogged = 0

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
                logSource(it)
            }
            val timeUs = timeMs * 1000
            // getScaledFrameAtTime (API 27+) decodes directly at thumbnail size —
            // lighter on the decoder and the recommended path. Fire TV is API 30+;
            // fall back to full-frame + manual scale on older Fire OS 6 sticks.
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    TARGET_W,
                    targetHeight(r),
                )
            } else {
                r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scale(it) }
            }
            diagnose(timeMs, frame)
            frame
        } catch (e: Exception) {
            RemoteLog.d(TAG, "thumbnail extract failed at ${timeMs}ms: ${e.message}")
            null
        }
    }

    private fun targetHeight(r: MediaMetadataRetriever): Int {
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
        return max(1, TARGET_W * h / max(1, w))
    }

    private fun scale(src: Bitmap): Bitmap {
        if (src.width <= TARGET_W) return src
        val h = max(1, (src.height.toFloat() / src.width * TARGET_W).toInt())
        val scaled = Bitmap.createScaledBitmap(src, TARGET_W, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun logSource(r: MediaMetadataRetriever) {
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        RemoteLog.d(TAG, "thumbnail source: ${w}x$h mime=$mime")
    }

    /** Logs the outcome of the first few extractions to diagnose black frames. */
    private fun diagnose(timeMs: Long, frame: Bitmap?) {
        if (diagnosticsLogged >= 3) return
        diagnosticsLogged++
        if (frame == null) {
            RemoteLog.d(TAG, "frame NULL at ${timeMs}ms (decoder could not produce a frame)")
            return
        }
        val lum = averageLuminance(frame)
        val blackNote = if (lum < 8) " (appears BLACK — likely HDR/decoder issue)" else ""
        RemoteLog.d(TAG, "frame at ${timeMs}ms: ${frame.width}x${frame.height} avgLum=$lum$blackNote")
    }

    private fun averageLuminance(b: Bitmap): Int {
        var sum = 0L
        var n = 0
        val stepX = max(1, b.width / 4)
        val stepY = max(1, b.height / 4)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val bl = c and 0xFF
                sum += (r * 30 + g * 59 + bl * 11) / 100
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0 else (sum / n).toInt()
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
        private const val TARGET_W = 320
    }
}
