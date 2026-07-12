package com.bitstreamer.client.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.DtsUtil
import com.bitstreamer.client.logging.RemoteLog
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Makes DTS-HD tracks playable on devices whose HDMI path only advertises
 * plain DTS (all Fire TVs): every DTS-HD frame starts with a backward-
 * compatible DTS core frame, so we truncate each sample to its core frames
 * and bitstream those as ENCODING_DTS — the same "DTS core" strategy Plex
 * and Kodi use. True DTS-HD passthrough still happens automatically when the
 * platform advertises ENCODING_DTS_HD (the direct path is preferred).
 *
 * Context: Media3's extractors label DTS-HD MA content audio/vnd.dts.hd, and
 * with no ENCODING_DTS_HD sink and no platform decoder the track is simply
 * unplayable without this wrapper. See docs/AUDIO_PASSTHROUGH.md §7.
 */
@OptIn(UnstableApi::class)
class DtsCoreAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    private var stripToCore = false
    private var pendingBuffer: ByteBuffer? = null
    private var warnedNoCore = false
    private var strippedSamples = 0L

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        val direct = super.getFormatSupport(format)
        if (direct != AudioSink.SINK_FORMAT_UNSUPPORTED) return direct
        if (isDtsHd(format) && super.supportsFormat(toCoreFormat(format))) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        pendingBuffer = null
        strippedSamples = 0
        warnedNoCore = false
        if (isDtsHd(inputFormat) && !super.supportsFormat(inputFormat)) {
            val coreFormat = toCoreFormat(inputFormat)
            if (super.supportsFormat(coreFormat)) {
                stripToCore = true
                RemoteLog.d(
                    TAG,
                    "sink lacks DTS-HD; bitstreaming DTS core instead: " +
                        "${inputFormat.sampleMimeType} -> ${coreFormat.sampleMimeType} " +
                        "${coreFormat.channelCount}ch @${coreFormat.sampleRate}Hz"
                )
                super.configure(coreFormat, specifiedBufferSize, outputChannels)
                return
            }
        }
        stripToCore = false
        if (isDts(inputFormat)) {
            RemoteLog.d(TAG, "direct DTS-family passthrough: ${inputFormat.sampleMimeType}")
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        // DefaultAudioSink requires a retried buffer to be the identical
        // instance, so we transform in place and never re-transform: while a
        // buffer is pending (previous call returned false) it is passed through
        // untouched.
        if (stripToCore && buffer !== pendingBuffer) {
            if (!extractCoreInPlace(buffer)) {
                // No core frame in this sample (DTS Express is extension-only,
                // or corrupt data): skip it rather than feed non-core bytes to
                // a DTS AudioTrack.
                if (!warnedNoCore) {
                    warnedNoCore = true
                    RemoteLog.d(TAG, "sample without DTS core frame; skipping (DTS Express-only stream?)")
                }
                return true
            }
            pendingBuffer = buffer
        }
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (handled) pendingBuffer = null
        return handled
    }

    override fun flush() {
        pendingBuffer = null
        super.flush()
    }

    override fun reset() {
        pendingBuffer = null
        super.reset()
    }

    /**
     * Rewrites [buffer] in place (between position and limit) so it contains
     * only DTS core frames, dropping extension substreams. Returns false if no
     * core frame was found.
     */
    private fun extractCoreInPlace(buffer: ByteBuffer): Boolean {
        val start = buffer.position()
        val limit = buffer.limit()
        var read = start
        var write = start
        val header = ByteArray(HEADER_SIZE)
        while (read + HEADER_SIZE <= limit) {
            val frameType = DtsUtil.getFrameType(buffer.getInt(read))
            if (frameType == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM && write > start) {
                // Core copied and the extension substream follows; extensions
                // run to the end of the sample — done. (Also avoids treating
                // random extension payload bytes as a core sync.)
                break
            }
            if (frameType != DtsUtil.FRAME_TYPE_CORE) {
                read++
                continue
            }
            for (i in 0 until HEADER_SIZE) header[i] = buffer.get(read + i)
            val frameSize = min(DtsUtil.getDtsFrameSize(header), limit - read)
            if (frameSize <= 0) {
                read++
                continue
            }
            if (write != read) {
                for (i in 0 until frameSize) buffer.put(write + i, buffer.get(read + i))
            }
            write += frameSize
            read += frameSize
        }
        if (write == start) return false
        strippedSamples++
        if (strippedSamples == 1L || strippedSamples % 2000 == 0L) {
            RemoteLog.d(
                TAG,
                "core extraction active: $strippedSamples samples " +
                    "(last ${limit - start} -> ${write - start} bytes)"
            )
        }
        buffer.limit(write)
        return true
    }

    private fun isDtsHd(format: Format): Boolean =
        MimeTypes.AUDIO_DTS_HD == format.sampleMimeType ||
            MimeTypes.AUDIO_DTS_EXPRESS == format.sampleMimeType

    private fun isDts(format: Format): Boolean =
        MimeTypes.AUDIO_DTS == format.sampleMimeType || isDtsHd(format)

    private fun toCoreFormat(format: Format): Format {
        // DTS core caps: 48 kHz, 5.1 (the 96k/7.1 parts live in the extensions).
        val sampleRate =
            if (format.sampleRate == Format.NO_VALUE) 48000 else min(format.sampleRate, 48000)
        val channels =
            if (format.channelCount == Format.NO_VALUE) 6 else min(format.channelCount, 6)
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_DTS)
            .setSampleRate(sampleRate)
            .setChannelCount(channels)
            .build()
    }

    companion object {
        private const val TAG = "DtsCoreAudioSink"
        private const val HEADER_SIZE = 10 // bytes DtsUtil.getDtsFrameSize reads
    }
}
