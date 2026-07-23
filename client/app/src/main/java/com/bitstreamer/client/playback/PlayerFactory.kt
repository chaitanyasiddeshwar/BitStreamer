package com.bitstreamer.client.playback

import android.content.Context
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

        val cleanData = ByteBuffer.allocate(data.remaining())
        cleanData.order(data.order())

        var index = originalPosition
        var modified = false

        while (index < limit) {
            val startCodeOffset = findStartCode(data, index, limit)
            if (startCodeOffset == -1) {
                val dup = data.duplicate()
                dup.position(index)
                dup.limit(limit)
                cleanData.put(dup)
                break
            }

            if (startCodeOffset > index) {
                val dup = data.duplicate()
                dup.position(index)
                dup.limit(startCodeOffset)
                cleanData.put(dup)
            }

            val startCodeLength = if (startCodeOffset + 3 < limit &&
                data.get(startCodeOffset) == 0.toByte() &&
                data.get(startCodeOffset + 1) == 0.toByte() &&
                data.get(startCodeOffset + 2) == 0.toByte() &&
                data.get(startCodeOffset + 3) == 1.toByte()) 4 else 3

            val nalStart = startCodeOffset + startCodeLength
            if (nalStart >= limit) {
                val dup = data.duplicate()
                dup.position(startCodeOffset)
                dup.limit(limit)
                cleanData.put(dup)
                break
            }

            val nextStartCodeOffset = findStartCode(data, nalStart, limit)
            val nalEnd = if (nextStartCodeOffset != -1) nextStartCodeOffset else limit

            val nalHeader0 = data.get(nalStart)
            val nalType = (nalHeader0.toInt() and 0x7E) ushr 1

            if (nalType == 62 || nalType == 63) {
                modified = true
            } else {
                if (startCodeLength == 4) {
                    cleanData.put(0.toByte())
                }
                cleanData.put(0.toByte())
                cleanData.put(0.toByte())
                cleanData.put(1.toByte())

                val dup = data.duplicate()
                dup.position(nalStart)
                dup.limit(nalEnd)
                cleanData.put(dup)
            }

            index = nalEnd
        }

        if (modified) {
            cleanData.flip()
            data.clear()
            data.put(cleanData)
            data.flip()
        } else {
            data.position(originalPosition)
        }
    }

    private fun findStartCode(data: ByteBuffer, start: Int, limit: Int): Int {
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
