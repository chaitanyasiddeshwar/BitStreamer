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
    private var consecutiveSkipped = 0

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
        consecutiveSkipped = 0
        warnedNoCore = false
        if (isDtsHd(inputFormat)) {
            val coreFormat = toCoreFormat(inputFormat)
            val coreOk = super.supportsFormat(coreFormat)
            val directOk = super.supportsFormat(inputFormat)
            if (coreOk && !(directOk && PREFER_DIRECT_DTS_HD)) {
                stripToCore = true
                RemoteLog.d(
                    TAG,
                    "bitstreaming DTS core for ${inputFormat.sampleMimeType} " +
                        "(sink advertises DTS-HD: $directOk; Fire TV's DTS-HD path is " +
                        "unreliable, core extraction forced) -> ${coreFormat.sampleMimeType} " +
                        "${coreFormat.channelCount}ch @${coreFormat.sampleRate}Hz"
                )
                super.configure(coreFormat, specifiedBufferSize, outputChannels)
                return
            }
            if (directOk) {
                RemoteLog.d(TAG, "direct DTS-HD passthrough (PREFER_DIRECT_DTS_HD enabled)")
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
                    RemoteLog.d(
                        TAG,
                        "sample without DTS core frame; skipping. first bytes: ${hexPrefix(buffer)}"
                    )
                }
                consecutiveSkipped++
                if (consecutiveSkipped >= MAX_CONSECUTIVE_SKIPS) {
                    // Every sample coreless: skipping forever would hang the
                    // player at 0 (audio is the master clock). Fail visibly.
                    val message = "DTS track has no extractable core frames " +
                        "(pure XLL/Express stream?); select another audio track"
                    RemoteLog.d(TAG, message)
                    throw IllegalStateException(message)
                }
                return true
            }
            consecutiveSkipped = 0
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
            // Assemble the sync word byte-wise: sample buffers use native byte
            // order (little-endian on ARM), so ByteBuffer.getInt would return
            // a swapped word that matches no DtsUtil sync constant.
            val frameType = DtsUtil.getFrameType(wordAt(buffer, read))
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

    /** Big-endian 32-bit word at [index], independent of the buffer's byte order. */
    private fun wordAt(buffer: ByteBuffer, index: Int): Int =
        ((buffer.get(index).toInt() and 0xFF) shl 24) or
            ((buffer.get(index + 1).toInt() and 0xFF) shl 16) or
            ((buffer.get(index + 2).toInt() and 0xFF) shl 8) or
            (buffer.get(index + 3).toInt() and 0xFF)

    private fun hexPrefix(buffer: ByteBuffer): String {
        val sb = StringBuilder()
        val end = minOf(buffer.position() + 16, buffer.limit())
        for (i in buffer.position() until end) {
            sb.append(String.format("%02X ", buffer.get(i)))
        }
        return sb.toString().trim()
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
        private const val MAX_CONSECUTIVE_SKIPS = 100 // ~1s of coreless samples -> error, not a hang

        // Fire TV advertises ENCODING_DTS_HD in its HDMI caps, but the actual
        // DTS-HD passthrough path does not work for apps: the AudioTrack opens
        // and plays silence (verified on AFTKM / Fire TV Stick 4K Max, and the
        // same is reported for Plex on Fire TV Cube). Only Kodi's IEC-packing
        // hack gets real DTS-HD through. So DTS core extraction is forced even
        // when the sink claims DTS-HD; flip this only if a device with a
        // working direct DTS-HD path ever shows up.
        private const val PREFER_DIRECT_DTS_HD = false
    }
}
