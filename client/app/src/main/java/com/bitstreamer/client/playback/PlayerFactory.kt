package com.bitstreamer.client.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * The single place ExoPlayer is configured. The choices here exist to keep
 * audio passthrough (bitstreaming) intact — see docs/AUDIO_PASSTHROUGH.md §4
 * before changing anything.
 */
object PlayerFactory {

    @OptIn(UnstableApi::class)
    fun create(context: Context, disableDolbyVision: Boolean = false): ExoPlayer {
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
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(videoCodecSelector(disableDolbyVision))

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

    /**
     * Decoder selector that avoids the Fire TV Dolby Vision decoder when
     * [disableDolbyVision] is set. That decoder black-screens (audio only) on
     * the problematic combinations — DV Profile 7 dual-layer and DV+HDR10+ MKVs
     * (androidx/media #957, #1895) — while ordinary single-layer DV plays fine,
     * so the caller decides per file. Returning no decoder for the DV mime makes
     * Media3 fall back to its own alternative HEVC/AVC decoder, decoding the
     * HDR10-compatible base layer (DV metadata dropped; shows as HDR10).
     */
    @OptIn(UnstableApi::class)
    private fun videoCodecSelector(disableDolbyVision: Boolean): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            if (disableDolbyVision && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                emptyList()
            } else {
                MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
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
