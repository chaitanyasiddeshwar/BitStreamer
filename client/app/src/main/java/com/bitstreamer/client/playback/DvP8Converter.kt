package com.bitstreamer.client.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.bitstreamer.client.logging.RemoteLog
import com.suyashbelekar.exoplayerhdrutils.libdovi.LibDovi
import java.nio.ByteBuffer

/*
 * Dolby Vision Profile 7 -> 8.1 conversion (DvPlan.CONVERT_P8).
 *
 * Replaces the ExoplayerHdrUtils glue (HdrCompatMediaSourceFactory & co). We keep
 * only that library's native RPU converter (libdovi_android.so, a thin JNI
 * wrapper over quietvoid's `dolby_vision` crate, mode 2 = "to 8.1" — the same
 * conversion dovi_tool does). Everything around it is ours, because the
 * library's frame handling was the weak part:
 *  - it padded the shrunken RPU with a filler NAL / zero bytes *after* the RPU
 *    (the RPU is meant to be the last NAL of the access unit);
 *  - it left the enhancement layer (NAL 63) in every frame;
 *  - it never relabelled the codecs string, so decoders were configured for
 *    dual-layer Profile 7 while receiving single-layer 8.1;
 *  - an unparsable first RPU threw and killed playback.
 *
 * Here each access unit is rebuilt: NAL 63 dropped, NAL 62 replaced by the
 * converted RPU (no filler), every other NAL copied verbatim. The converter
 * only ever sees one isolated RPU NAL, and its output is cut back to that
 * single NAL. A failed conversion keeps the original RPU (counted and logged).
 */

/** Rebuilds Annex B access units. Pure Kotlin; the RPU converter is injected. */
class DvP8FrameRewriter(
    /** Converted RPU NAL (header + payload, no start code) or null on failure. */
    private val convertRpu: (src: ByteArray, offset: Int, length: Int) -> ByteArray?,
) {
    var output = ByteArray(0)
        private set

    var frames = 0L; private set
    var rpuConverted = 0L; private set
    var rpuFailed = 0L; private set
    var framesWithoutRpu = 0L; private set
    var elDropped = 0L; private set
    var framesWithoutStartCode = 0L; private set

    /** Last frame's details, for first-frame logging. */
    var lastNalTypes = ""; private set
    var lastRpuSizes = ""; private set
    var lastConvertedRpu: ByteArray? = null; private set

    /** Rewrites au[0, size) into [output]; returns the new length. */
    fun rewrite(au: ByteArray, size: Int): Int {
        frames++
        ensureOutput(size + 1024)
        var out = 0
        var sc = findStartCode(au, 0, size)
        if (sc < 0) {
            framesWithoutStartCode++
            System.arraycopy(au, 0, output, 0, size)
            return size
        }
        val types = StringBuilder()
        val rpuSizes = StringBuilder()
        var sawRpu = false
        lastConvertedRpu = null
        // Bytes before the first start code (not expected in Annex B): keep.
        var lead = sc
        while (lead > 0 && au[lead - 1] == 0.toByte()) lead--
        if (lead > 0) {
            System.arraycopy(au, 0, output, 0, lead); out = lead
        }
        while (sc >= 0) {
            val payload = sc + 3
            if (payload >= size) break
            val next = findStartCode(au, payload, size)
            var end = if (next < 0) size else next
            // Trailing zeros belong to the next start code / trailing_zero_8bits;
            // a NAL's last byte is never 0x00.
            while (end > payload && au[end - 1] == 0.toByte()) end--
            val len = end - payload
            if (len > 0) {
                val type = (au[payload].toInt() shr 1) and 0x3F
                if (types.length < 120) types.append(type).append(' ')
                when (type) {
                    63 -> elDropped++
                    62 -> {
                        sawRpu = true
                        val conv = convertRpu(au, payload, len)
                        if (conv != null) {
                            rpuConverted++
                            lastConvertedRpu = conv
                            rpuSizes.append("$len->${conv.size} ")
                            out = writeNal(conv, 0, conv.size, out)
                        } else {
                            rpuFailed++
                            rpuSizes.append("$len->FAILED(kept) ")
                            out = writeNal(au, payload, len, out)
                        }
                    }
                    else -> out = writeNal(au, payload, len, out)
                }
            }
            sc = next
        }
        if (!sawRpu) framesWithoutRpu++
        lastNalTypes = types.toString().trim()
        lastRpuSizes = rpuSizes.toString().trim()
        return out
    }

    private fun writeNal(src: ByteArray, off: Int, len: Int, out: Int): Int {
        ensureOutput(out + 4 + len, keep = out)
        output[out] = 0; output[out + 1] = 0; output[out + 2] = 0; output[out + 3] = 1
        System.arraycopy(src, off, output, out + 4, len)
        return out + 4 + len
    }

    private fun ensureOutput(n: Int, keep: Int = 0) {
        if (output.size >= n) return
        val grown = ByteArray(n + n / 4)
        if (keep > 0) System.arraycopy(output, 0, grown, 0, keep)
        output = grown
    }

    fun stats(): String =
        "frames=$frames rpuConverted=$rpuConverted rpuFailed=$rpuFailed " +
            "noRpu=$framesWithoutRpu elDropped=$elDropped noStartCode=$framesWithoutStartCode"

    companion object {
        /** Index of the next 00 00 01 at or after [from], or -1. */
        fun findStartCode(a: ByteArray, from: Int, end: Int): Int {
            var i = from
            while (i + 2 < end) {
                val b2 = a[i + 2].toInt()
                when {
                    b2 > 1 || b2 < 0 -> i += 3            // can't be inside 00 00 01
                    b2 == 1 && a[i + 1].toInt() == 0 && a[i].toInt() == 0 -> return i
                    else -> i++
                }
            }
            return -1
        }
    }
}

/** One RPU NAL through libdovi's convert_with_mode(2). Not thread-safe (one per stream). */
class NativeRpuConverter {
    private var direct: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024)
    private var tmp = ByteArray(64 * 1024)

    fun convert(src: ByteArray, off: Int, len: Int): ByteArray? {
        // Native may grow the NAL in place (copy_within into spare capacity).
        val need = 4 + len * 2 + 1024
        if (direct.capacity() < need) direct = ByteBuffer.allocateDirect(need)
        if (tmp.size < need) tmp = ByteArray(need)
        direct.clear()
        direct.put(0).put(0).put(0).put(1).put(src, off, len)
        val n = try {
            lib.processHevcFrame(direct, 4 + len, /* convert to P8 */ 1, false)
        } catch (t: Throwable) {
            return null
        }
        if (n <= 4 || n > direct.capacity()) return null
        direct.position(0)
        direct.get(tmp, 0, n)
        // Keep only the RPU NAL: cut at the filler's start code, drop zero padding.
        val next = DvP8FrameRewriter.findStartCode(tmp, 4, n)
        var end = if (next < 0) n else next
        while (end > 4 && tmp[end - 1] == 0.toByte()) end--
        val outLen = end - 4
        if (outLen <= 2) return null
        // Unchanged bytes => libdovi couldn't parse/convert it (it returns the input).
        if (outLen == len) {
            var same = true
            for (k in 0 until len) if (tmp[4 + k] != src[off + k]) { same = false; break }
            if (same) return null
        }
        return tmp.copyOfRange(4, end)
    }

    /** Profile / EL type of a single RPU NAL (only call on converter output: an
     *  unparsable RPU makes libdovi's getRpuFrameInfo loop forever). */
    fun describe(rpu: ByteArray): String {
        val b = ByteBuffer.allocateDirect(rpu.size + 4)
        b.put(0).put(0).put(0).put(1).put(rpu)
        val info = try { lib.getFrameInfo(b, rpu.size + 4) } catch (t: Throwable) { null }
        return info?.let { "profile=${it.doviProfile} el=${it.doviElType}" } ?: "unparsable"
    }

    companion object {
        private val lib: LibDovi by lazy { LibDovi() }
    }
}

@OptIn(UnstableApi::class)
class DvP8SampleStream(
    val delegate: SampleStream,
    private val relabelCodecs: Boolean,
) : SampleStream by delegate {
    private val converter = NativeRpuConverter()
    private val rewriter = DvP8FrameRewriter(converter::convert)
    private var scratch = ByteArray(0)
    private var detailedFramesLogged = 0

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val result = delegate.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_FORMAT_READ && relabelCodecs) {
            val f = formatHolder.format
            if (f != null) {
                val relabelled = PlayerFactory.relabelDvCodecsP7ToP8(f)
                if (relabelled !== f) {
                    RemoteLog.d(TAG, "codecs relabelled ${f.codecs} -> ${relabelled.codecs}")
                    formatHolder.format = relabelled
                }
            }
        }
        if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream &&
            (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0
        ) {
            onBuffer(buffer)
        }
        return result
    }

    private fun onBuffer(buffer: DecoderInputBuffer) {
        val data = buffer.data ?: return
        val size = data.position()
        if (size <= 0) return
        if (scratch.size < size) scratch = ByteArray(size + size / 4)
        data.position(0)
        data.get(scratch, 0, size)
        val outLen = rewriter.rewrite(scratch, size)
        data.clear()
        buffer.ensureSpaceForWrite(outLen)
        buffer.data!!.put(rewriter.output, 0, outLen)
        log(size, outLen)
    }

    private fun log(inLen: Int, outLen: Int) {
        val r = rewriter
        if (detailedFramesLogged < 3) {
            detailedFramesLogged++
            val conv = r.lastConvertedRpu?.let { converter.describe(it) } ?: "none"
            RemoteLog.d(
                TAG, "frame ${r.frames}: $inLen->$outLen bytes, NAL types [${r.lastNalTypes}], " +
                    "RPU ${r.lastRpuSizes.ifEmpty { "absent" }}, converted RPU $conv; ${r.stats()}"
            )
        } else if (r.frames % 2400 == 0L || (r.rpuFailed > 0 && r.rpuFailed <= 5 && r.lastRpuSizes.contains("FAILED"))) {
            RemoteLog.d(TAG, r.stats())
        }
    }

    companion object { private const val TAG = "DvP8" }
}

@OptIn(UnstableApi::class)
class DvP8MediaPeriod(
    val delegate: MediaPeriod,
    private val relabelCodecs: Boolean,
) : MediaPeriod by delegate {

    // Report ourselves (not the delegate) to the player.
    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        delegate.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) = callback.onPrepared(this@DvP8MediaPeriod)
            override fun onContinueLoadingRequested(source: MediaPeriod) =
                callback.onContinueLoadingRequested(this@DvP8MediaPeriod)
        }, positionUs)
    }

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        // The delegate casts retained streams to its own type: hand them back unwrapped.
        val previous = streams.copyOf()
        for (i in streams.indices) {
            (streams[i] as? DvP8SampleStream)?.let { streams[i] = it.delegate }
        }
        val pos = delegate.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)
        for (i in streams.indices) {
            val s = streams[i] ?: continue
            val prev = previous[i]
            if (prev is DvP8SampleStream && prev.delegate === s) {
                streams[i] = prev // retained: keep its converter state
                continue
            }
            val mime = selections[i]?.trackGroup?.getFormat(0)?.sampleMimeType
            if (mime == MimeTypes.VIDEO_DOLBY_VISION) {
                streams[i] = DvP8SampleStream(s, relabelCodecs)
            }
        }
        return pos
    }
}

@OptIn(UnstableApi::class)
class DvP8MediaSource(
    source: MediaSource,
    private val relabelCodecs: Boolean,
) : WrappingMediaSource(source) {
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        DvP8MediaPeriod(super.createPeriod(id, allocator, startPositionUs), relabelCodecs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        super.releasePeriod((mediaPeriod as? DvP8MediaPeriod)?.delegate ?: mediaPeriod)
    }
}

@OptIn(UnstableApi::class)
class DvP8MediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val relabelCodecs: Boolean,
) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        DvP8MediaSource(delegate.createMediaSource(mediaItem), relabelCodecs)

    override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(p); return this
    }

    override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(p); return this
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes
}
