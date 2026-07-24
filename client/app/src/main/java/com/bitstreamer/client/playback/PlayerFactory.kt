package com.bitstreamer.client.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

import android.media.MediaCrypto
import android.os.Handler
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.bitstreamer.client.logging.RemoteLog
import androidx.media3.decoder.DecoderInputBuffer
import java.nio.ByteBuffer
import java.util.ArrayList

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

import com.suyashbelekar.exoplayerhdrutils.exoplayer.source.HdrCompatMediaSourceFactory
import com.suyashbelekar.exoplayerhdrutils.video.transformers.DoviStrategy
import com.suyashbelekar.exoplayerhdrutils.video.transformers.Hdr10PlusStrategy
import com.suyashbelekar.exoplayerhdrutils.video.transformers.TransformStrategy

import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * How to handle a Dolby Vision source on this device.
 *  - NATIVE:      hand the stream to the platform as-is (no processing).
 *  - CONVERT_P8:  convert Profile 7 -> Profile 8.1 (drop EL, remap RPU) via
 *                 ExoplayerHdrUtils. Keeps DV dynamic metadata; works for MEL & FEL.
 *  - STRIP_HDR10: strip DV NAL 62/63 and fall back to plain HEVC/HDR10.
 */
enum class DvPlan { NATIVE, CONVERT_P8, STRIP_HDR10 }

/**
 * What this device can do with Dolby Vision, probed from MediaCodecList + Display.
 *  - hasDvDecoder:      a video/dolby-vision decoder exists (Fire TV 4K/Max: true).
 *  - nativeDualLayer:   DIAGNOSTIC ONLY (logged, not used for decisions). The DV
 *                       decoder name contains "mtk"/"realtek". This does NOT reliably
 *                       mean the device decodes Profile 7 dual-layer — Fire TV's
 *                       MediaTek decoder reports this yet is single-layer only. See
 *                       planDv().
 *  - displaySupportsDv: the connected display reports HDR_TYPE_DOLBY_VISION (sanity/log).
 */
data class DvCaps(
    val hasDvDecoder: Boolean,
    val dvDecoderName: String?,
    val nativeDualLayer: Boolean,
    val displaySupportsDv: Boolean,
)

/**
 * The single place ExoPlayer is configured. The choices here exist to keep
 * audio passthrough (bitstreaming) intact — see docs/AUDIO_PASSTHROUGH.md §4
 * before changing anything.
 */
object PlayerFactory {

    private var bandwidthMeter: DefaultBandwidthMeter? = null
    @Volatile
    private var totalBytesTransferred: Long = 0L
    private var compositeTransferListener: TransferListener? = null

    fun getTotalBytesTransferred(): Long = totalBytesTransferred

    @OptIn(UnstableApi::class)
    fun getBandwidthMeter(context: Context): DefaultBandwidthMeter {
        var meter = bandwidthMeter
        if (meter == null) {
            meter = DefaultBandwidthMeter.Builder(context.applicationContext).build()
            bandwidthMeter = meter
        }
        return meter
    }

    @OptIn(UnstableApi::class)
    fun getTransferListener(context: Context): TransferListener {
        var listener = compositeTransferListener
        if (listener == null) {
            val meter = getBandwidthMeter(context)
            listener = object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    meter.onTransferInitializing(source, dataSpec, isNetwork)
                }

                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    meter.onTransferStart(source, dataSpec, isNetwork)
                }

                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    totalBytesTransferred += bytesTransferred
                    meter.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
                }

                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    meter.onTransferEnd(source, dataSpec, isNetwork)
                }
            }
            compositeTransferListener = listener
        }
        return listener
    }

    fun shouldFallbackToHdr10(dvProfile: Int, stripDV: Boolean, forceStripDV: Boolean): Boolean {
        return stripDV || forceStripDV
    }

    /**
     * Probe what this device can do with Dolby Vision. Mirrors the approach ViMu
     * and Kodi use: scan MediaCodecList for a video/dolby-vision decoder, and flag
     * MediaTek/Realtek decoders as native dual-layer capable. Also reads the
     * display's HDR capabilities (informational). Safe to call on any device; all
     * failures degrade to "no DV".
     */
    @OptIn(UnstableApi::class)
    fun probeDvCaps(context: Context): DvCaps {
        var hasDv = false
        var name: String? = null
        var nativeDual = false
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                val supportsDv = info.supportedTypes.any {
                    it.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true)
                }
                if (!supportsDv) continue
                hasDv = true
                if (name == null) name = info.name
                val lower = info.name.lowercase()
                if (lower.contains("mtk") || lower.contains("realtek")) {
                    nativeDual = true
                    name = info.name // prefer the dual-layer-capable decoder's name
                    break
                }
            }
        } catch (t: Throwable) {
            RemoteLog.d("PlayerFactory", "DV capability probe failed: ${t.message}")
        }

        var displayDv = false
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                @Suppress("DEPRECATION")
                val hdr = display?.hdrCapabilities
                @Suppress("DEPRECATION")
                displayDv = hdr?.supportedHdrTypes?.contains(
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
                ) ?: false
            }
        } catch (t: Throwable) {
            RemoteLog.d("PlayerFactory", "DV display probe failed: ${t.message}")
        }

        return DvCaps(hasDv, name, nativeDual, displayDv)
    }

    /**
     * Decide how to play a Dolby Vision source, automatically. **Device-agnostic:**
     * the decision is keyed only on the source DV profile and whether the platform
     * exposes a Dolby Vision decoder ([DvCaps.hasDvDecoder], probed from
     * MediaCodecList). There are deliberately NO Build.MODEL or decoder-name checks.
     *
     * Policy — the superset of what ViMu/Kodi/Emby do, adapted to an ExoPlayer client:
     *  - Profile 5:  NATIVE. Its base layer is proprietary IPTPQc2 (not HDR10), so it
     *                can only be shown by a real DV pipeline and must never be stripped
     *                (stripping gives wrong colors). Nothing safe to do otherwise.
     *  - Profile 7:  hasDvDecoder -> CONVERT_P8 (drop EL, remap RPU to 8.1; keeps DV
     *                dynamic metadata; correct for MEL and FEL alike).
     *                else         -> STRIP_HDR10 (BL is HDR10-compatible).
     *  - Profile 8:  hasDvDecoder -> NATIVE (single-layer DV, decoded directly).
     *                else         -> STRIP_HDR10 (8.1 base is HDR10-compatible).
     *  - Not DV:     NATIVE.
     *
     * Why NATIVE is never correct for Profile 7 on ANY device here: BitStreamer decodes
     * through ExoPlayer/MediaCodec, and media3's extractor drops the Profile 7
     * enhancement layer before it ever reaches the decoder. So no device — not even a
     * true dual-layer player — can present FEL through this pipeline; converting to
     * single-layer P8.1 is the only way to keep Dolby Vision for Profile 7. This is why
     * we do NOT branch on [DvCaps.nativeDualLayer] (a "mtk"/"realtek" decoder name is
     * meaningless here; the Fire TV MediaTek decoder reports it yet is single-layer
     * only). Converting is also safe on genuine dual-layer hardware — you lose only the
     * imperceptible FEL residual, never the DV look.
     *
     * A non-null [userOverride] (from the remote menu) always wins.
     * [hdr10Plus] is accepted for future dual-metadata handling; unused today (the
     * CONVERT_P8 transform already discards co-present HDR10+).
     */
    fun planDv(dvProfile: Int, hdr10Plus: Boolean, caps: DvCaps, userOverride: DvPlan?): DvPlan {
        if (userOverride != null) return userOverride
        return when (dvProfile) {
            5 -> DvPlan.NATIVE
            7 -> if (caps.hasDvDecoder) DvPlan.CONVERT_P8 else DvPlan.STRIP_HDR10
            8 -> if (caps.hasDvDecoder) DvPlan.NATIVE else DvPlan.STRIP_HDR10
            else -> DvPlan.NATIVE
        }
    }

    fun mapFormat(format: Format, fallbackToHdr10: Boolean): Format {
        if (fallbackToHdr10 && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(null) // Clear DV specific codecs string
                .build()
        }
        return format
    }

    fun filterDecoders(infos: List<MediaCodecInfo>, fallbackToHdr10: Boolean): List<MediaCodecInfo> {
        if (fallbackToHdr10) {
            val filtered = infos.filter { info ->
                val nameLower = info.name.lowercase()
                val mimeLower = info.mimeType.lowercase()
                !nameLower.contains("dolby") &&
                !nameLower.contains("dovi") &&
                !mimeLower.contains("dolby")
            }
            if (filtered.isNotEmpty()) {
                return filtered
            }
        }
        return infos
    }

    @OptIn(UnstableApi::class)
    fun create(context: Context, fallbackToHdr10: Boolean = false, convertDv8: Boolean = false): ExoPlayer {
        if (fallbackToHdr10) {
            Log.i("PlayerFactory", "Dolby Vision fallback active: forcing fallback to standard HEVC (HDR10)")
        } else if (convertDv8) {
            Log.i("PlayerFactory", "Dolby Vision Profile 7 to Profile 8.1 conversion active via ExoplayerHdrUtils HdrCompatMediaSourceFactory")
        }

        // EXTENSION_RENDERER_MODE_OFF: no software decoders that could outrank
        // the passthrough path. Decoder fallback stays on so a broken decoder
        // doesn't kill playback outright. The audio sink is wrapped so DTS-HD
        // tracks bitstream their DTS core when the sink lacks ENCODING_DTS_HD.
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                return DtsCoreAudioSink(DefaultAudioSink.Builder(context).build())
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                out.add(
                    DolbyVisionFallbackVideoRenderer(
                        context,
                        mediaCodecSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        50, // MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                        fallbackToHdr10
                    )
                )
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val meter = getBandwidthMeter(context)
        val listener = getTransferListener(context)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)
            .setTransferListener(listener)
        val defaultMediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val mediaSourceFactory = if (convertDv8) {
            val transformStrategy = TransformStrategy(
                doviP7Fel = DoviStrategy.CONVERT_TO_P8,
                doviP7Mel = DoviStrategy.CONVERT_TO_P8,
                doviHdr10Plus = Hdr10PlusStrategy.DISCARD
            )
            HdrCompatMediaSourceFactory(defaultMediaSourceFactory, transformStrategy)
        } else {
            defaultMediaSourceFactory
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(meter)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
    }

    @OptIn(UnstableApi::class)
    private class DolbyVisionFallbackVideoRenderer(
        context: Context,
        mediaCodecSelector: MediaCodecSelector,
        allowedVideoJoiningTimeMs: Long,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        maxDroppedFramesToNotify: Int,
        private val fallbackToHdr10: Boolean
    ) : MediaCodecVideoRenderer(
        context,
        mediaCodecSelector,
        allowedVideoJoiningTimeMs,
        enableDecoderFallback,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify
    ) {

        private fun mapFormat(format: Format): Format {
            return PlayerFactory.mapFormat(format, fallbackToHdr10)
        }

        override fun getDecoderInfos(
            mediaCodecSelector: MediaCodecSelector,
            format: Format,
            requiresSecureDecoder: Boolean
        ): List<MediaCodecInfo> {
            val mappedFormat = mapFormat(format)
            val infos = super.getDecoderInfos(mediaCodecSelector, mappedFormat, requiresSecureDecoder)
            if (fallbackToHdr10) {
                RemoteLog.d("PlayerFactory", "getDecoderInfos: Dolby Vision fallback active, filtering out DV decoders")
                return filterDecoders(infos, fallbackToHdr10)
            }
            return infos
        }

        override fun getMediaCodecConfiguration(
            codecInfo: MediaCodecInfo,
            format: Format,
            crypto: MediaCrypto?,
            codecOperatingRate: Float
        ): MediaCodecAdapter.Configuration {
            return super.getMediaCodecConfiguration(codecInfo, mapFormat(format), crypto, codecOperatingRate)
        }

        override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
            val data = buffer.data
            if (fallbackToHdr10 && data != null) {
                stripDolbyVisionNalUnits(buffer)
            }
            super.onQueueInputBuffer(buffer)
        }

        private fun stripDolbyVisionNalUnits(buffer: DecoderInputBuffer) {
            val data = buffer.data ?: return
            PlayerFactory.stripDolbyVisionNalUnits(data)
        }

        private fun describeFormat(format: Format): String {
            return "mime=${format.sampleMimeType} codecs=${format.codecs} w=${format.width} h=${format.height} color=${format.colorInfo}"
        }
    }

    fun stripDolbyVisionNalUnits(data: ByteBuffer) {
        if (data.remaining() < 5) return

        val originalPosition = data.position()
        val limit = data.limit()

        if (data.hasArray()) {
            val arr = data.array()
            val offset = data.arrayOffset()
            var index = originalPosition
            var writeIndex = originalPosition
            var modified = false

            while (index < limit) {
                val startCodeOffset = findStartCode(arr, offset + index, offset + limit)
                if (startCodeOffset == -1) {
                    val remainingLen = limit - index
                    if (modified && writeIndex < index) {
                        System.arraycopy(arr, offset + index, arr, offset + writeIndex, remainingLen)
                    }
                    writeIndex += remainingLen
                    break
                }

                val relativeStartCode = startCodeOffset - offset

                if (relativeStartCode > index) {
                    val leadingLen = relativeStartCode - index
                    if (modified && writeIndex < index) {
                        System.arraycopy(arr, offset + index, arr, offset + writeIndex, leadingLen)
                    }
                    writeIndex += leadingLen
                    index = relativeStartCode
                }

                val startCodeLength = if (relativeStartCode + 3 < limit &&
                    arr[offset + relativeStartCode] == 0.toByte() &&
                    arr[offset + relativeStartCode + 1] == 0.toByte() &&
                    arr[offset + relativeStartCode + 2] == 0.toByte() &&
                    arr[offset + relativeStartCode + 3] == 1.toByte()
                ) 4 else 3

                val nalStart = relativeStartCode + startCodeLength
                if (nalStart >= limit) {
                    val tailLen = limit - relativeStartCode
                    if (modified && writeIndex < relativeStartCode) {
                        System.arraycopy(arr, offset + relativeStartCode, arr, offset + writeIndex, tailLen)
                    }
                    writeIndex += tailLen
                    break
                }

                val nextStartCodeOffset = findStartCode(arr, offset + nalStart, offset + limit)
                val nalEnd = if (nextStartCodeOffset != -1) nextStartCodeOffset - offset else limit

                val nalHeader0 = arr[offset + nalStart]
                val nalType = (nalHeader0.toInt() and 0x7E) ushr 1

                if (nalType == 62 || nalType == 63) {
                    modified = true
                } else {
                    val nalLen = nalEnd - relativeStartCode
                    if (modified && writeIndex < relativeStartCode) {
                        System.arraycopy(arr, offset + relativeStartCode, arr, offset + writeIndex, nalLen)
                    }
                    writeIndex += nalLen
                }

                index = nalEnd
            }

            if (modified) {
                data.position(originalPosition)
                data.limit(writeIndex)
            } else {
                data.position(originalPosition)
            }
        } else {
            // Direct buffer fallback (non-array backed)
            var index = originalPosition
            var writeIndex = originalPosition
            var modified = false

            while (index < limit) {
                val startCodeOffset = findStartCodeDirect(data, index, limit)
                if (startCodeOffset == -1) {
                    val remainingLen = limit - index
                    if (modified && writeIndex < index) {
                        copyBufferRange(data, index, limit, writeIndex)
                    }
                    writeIndex += remainingLen
                    break
                }

                if (startCodeOffset > index) {
                    val leadingLen = startCodeOffset - index
                    if (modified && writeIndex < index) {
                        copyBufferRange(data, index, startCodeOffset, writeIndex)
                    }
                    writeIndex += leadingLen
                    index = startCodeOffset
                }

                val startCodeLength = if (startCodeOffset + 3 < limit &&
                    data.get(startCodeOffset) == 0.toByte() &&
                    data.get(startCodeOffset + 1) == 0.toByte() &&
                    data.get(startCodeOffset + 2) == 0.toByte() &&
                    data.get(startCodeOffset + 3) == 1.toByte()
                ) 4 else 3

                val nalStart = startCodeOffset + startCodeLength
                if (nalStart >= limit) {
                    val tailLen = limit - startCodeOffset
                    if (modified && writeIndex < startCodeOffset) {
                        copyBufferRange(data, startCodeOffset, limit, writeIndex)
                    }
                    writeIndex += tailLen
                    break
                }

                val nextStartCodeOffset = findStartCodeDirect(data, nalStart, limit)
                val nalEnd = if (nextStartCodeOffset != -1) nextStartCodeOffset else limit

                val nalHeader0 = data.get(nalStart)
                val nalType = (nalHeader0.toInt() and 0x7E) ushr 1

                if (nalType == 62 || nalType == 63) {
                    modified = true
                } else {
                    val nalLen = nalEnd - startCodeOffset
                    if (modified && writeIndex < startCodeOffset) {
                        copyBufferRange(data, startCodeOffset, nalEnd, writeIndex)
                    }
                    writeIndex += nalLen
                }

                index = nalEnd
            }

            if (modified) {
                data.position(originalPosition)
                data.limit(writeIndex)
            } else {
                data.position(originalPosition)
            }
        }
    }

    private fun findStartCode(arr: ByteArray, start: Int, limit: Int): Int {
        var i = start
        val max = limit - 3
        while (i <= max) {
            if (arr[i] == 0.toByte() && arr[i + 1] == 0.toByte()) {
                if (arr[i + 2] == 1.toByte()) {
                    return i
                }
                if (i + 3 < limit && arr[i + 2] == 0.toByte() && arr[i + 3] == 1.toByte()) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun findStartCodeDirect(data: ByteBuffer, start: Int, limit: Int): Int {
        var i = start
        val max = limit - 3
        while (i <= max) {
            if (data.get(i) == 0.toByte() && data.get(i + 1) == 0.toByte()) {
                if (data.get(i + 2) == 1.toByte()) {
                    return i
                }
                if (i + 3 < limit && data.get(i + 2) == 0.toByte() && data.get(i + 3) == 1.toByte()) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun copyBufferRange(data: ByteBuffer, srcStart: Int, srcEnd: Int, dstStart: Int) {
        for (i in 0 until (srcEnd - srcStart)) {
            data.put(dstStart + i, data.get(srcStart + i))
        }
    }

    /**
     * Wrapper for the UI: advertises no speed/pitch command, so PlayerView's
     * settings menu drops the "Playback speed" row (and any future
     * MediaSession/voice integration can't change speed either). Speed != 1.0
     * would force PCM and kill passthrough anyway — see docs/MEDIA3.md.
     */
    fun withoutSpeedControls(player: Player): Player = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            command != Player.COMMAND_SET_SPEED_AND_PITCH && super.isCommandAvailable(command)
    }
}
