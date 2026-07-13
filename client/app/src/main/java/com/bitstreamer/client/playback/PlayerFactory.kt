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

/**
 * The single place ExoPlayer is configured. The choices here exist to keep
 * audio passthrough (bitstreaming) intact — see docs/AUDIO_PASSTHROUGH.md §4
 * before changing anything.
 */
object PlayerFactory {

    @OptIn(UnstableApi::class)
    fun create(context: Context): ExoPlayer {
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
