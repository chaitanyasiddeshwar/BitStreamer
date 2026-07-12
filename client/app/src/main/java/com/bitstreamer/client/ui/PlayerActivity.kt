package com.bitstreamer.client.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.ui.PlayerView
import com.bitstreamer.client.R
import com.bitstreamer.client.playback.AudioCaps
import com.bitstreamer.client.playback.PlayerFactory

/**
 * Full-screen playback. The Menu key toggles a debug overlay showing the input
 * audio format vs. the AudioTrack the sink actually opened — the in-app ground
 * truth for whether audio is being bitstreamed (docs/AUDIO_PASSTHROUGH.md §5).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlayView: TextView
    private lateinit var errorView: TextView
    private var audioTrackDescription = "AudioTrack not initialized yet"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        overlayView = findViewById(R.id.debug_overlay)
        errorView = findViewById(R.id.error_view)
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initializePlayer() {
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        errorView.visibility = View.GONE

        val exoPlayer = PlayerFactory.create(this)
        player = exoPlayer
        playerView.player = exoPlayer

        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                val encoding = AudioCaps.encodingName(audioTrackConfig.encoding)
                val passthrough = !encoding.startsWith("PCM")
                audioTrackDescription = buildString {
                    append(encoding)
                    append(" @ ").append(audioTrackConfig.sampleRate).append(" Hz")
                    append(if (passthrough) "  [PASSTHROUGH]" else "  [decoded to PCM]")
                }
                updateOverlay()
            }
        })
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateOverlay()
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
            overlayView.visibility =
                if (overlayView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateOverlay()
            return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun updateOverlay() {
        if (overlayView.visibility != View.VISIBLE) return
        val audioFormat = player?.audioFormat
        val videoFormat = player?.videoFormat
        overlayView.text = buildString {
            append("file audio:  ").append(describeFormat(audioFormat)).append('\n')
            append("AudioTrack:  ").append(audioTrackDescription).append('\n')
            append("sink caps:   ").append(AudioCaps.describe(this@PlayerActivity)).append('\n')
            append("video:       ").append(describeFormat(videoFormat))
        }
    }

    private fun describeFormat(format: Format?): String {
        if (format == null) return "none"
        val channels =
            if (format.channelCount != Format.NO_VALUE) " ${format.channelCount}ch" else ""
        val size = if (format.width != Format.NO_VALUE) " ${format.width}x${format.height}" else ""
        return "${format.sampleMimeType}$channels$size"
    }

    private fun showError(error: PlaybackException) {
        val audioMime = player?.audioFormat?.sampleMimeType
        errorView.text = getString(
            R.string.playback_error,
            error.errorCodeName,
            error.cause?.message ?: error.message ?: "unknown",
            audioMime ?: "unknown",
            AudioCaps.describe(this),
        )
        errorView.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }
}
