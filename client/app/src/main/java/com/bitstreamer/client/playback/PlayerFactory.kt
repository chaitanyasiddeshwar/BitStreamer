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
import java.util.ArrayList

/**
 * The single place ExoPlayer is configured. The choices here exist to keep
 * audio passthrough (bitstreaming) intact — see docs/AUDIO_PASSTHROUGH.md §4
 * before changing anything.
 */
object PlayerFactory {

    @OptIn(UnstableApi::class)
    fun create(context: Context, fallbackToHdr10: Boolean = false): ExoPlayer {
        if (fallbackToHdr10) {
            Log.i("PlayerFactory", "Dolby Vision Profile 7 (FEL or unknown) detected: forcing fallback to standard HEVC (HDR10)")
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

        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
    }

    // Custom renderer that intercepts Dolby Vision Profile 7 tracks and reports them as
    // standard HEVC (H.265) to both the codec selector and the MediaCodec configuration.
    // This forces fallback to standard HEVC decoding (HDR10 base layer) instead of using the
    // hardware Dolby Vision decoder which fails/black-screens on dual-layer Profile 7 FEL files.
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
            if (fallbackToHdr10 && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                return format.buildUpon()
                    .setSampleMimeType(MimeTypes.VIDEO_H265)
                    .build()
            }
            return format
        }

        override fun getDecoderInfos(
            mediaCodecSelector: MediaCodecSelector,
            format: Format,
            requiresSecureDecoder: Boolean
        ): List<MediaCodecInfo> {
            return super.getDecoderInfos(mediaCodecSelector, mapFormat(format), requiresSecureDecoder)
        }

        override fun getMediaCodecConfiguration(
            codecInfo: MediaCodecInfo,
            format: Format,
            crypto: MediaCrypto?,
            codecOperatingRate: Float
        ): MediaCodecAdapter.Configuration {
            return super.getMediaCodecConfiguration(codecInfo, mapFormat(format), crypto, codecOperatingRate)
        }
    }

    // Note: forcing the HEVC base layer for Dolby Vision (to work around the Fire TV
    // DV black screen) was tried and removed — it black-screens/timeouts on Profile 7
    // FEL and breaks Profile 8 DV that otherwise plays fine. Profile 7 FEL simply
    // isn't playable on Fire TV without transcoding. Native DV decoder is used for all.

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
