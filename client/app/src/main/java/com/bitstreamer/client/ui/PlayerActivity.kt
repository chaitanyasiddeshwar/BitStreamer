package com.bitstreamer.client.ui

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.ui.PlayerView
import com.bitstreamer.client.R
import com.bitstreamer.client.discovery.ServerApi
import com.bitstreamer.client.logging.RemoteLog
import com.bitstreamer.client.playback.AudioCaps
import com.bitstreamer.client.playback.PlayerFactory
import java.util.concurrent.TimeUnit

/**
 * Full-screen playback. The Menu key toggles a debug overlay showing the input
 * audio format vs. the AudioTrack the sink actually opened — the in-app ground
 * truth for whether audio is being bitstreamed (docs/AUDIO_PASSTHROUGH.md §5).
 * Everything logged via RemoteLog also lands in client-logs.txt next to
 * bitstreamer.exe on the PC.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlayView: TextView
    private lateinit var errorView: TextView
    private var audioTrackDescription = "AudioTrack not initialized yet"
    private var api: ServerApi? = null
    private var resumeDialog: AlertDialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reportPosition = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                val ms = p.currentPosition
                Thread { api?.postPosition(ms) }.start()
            }
            mainHandler.postDelayed(this, POSITION_REPORT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        overlayView = findViewById(R.id.debug_overlay)
        errorView = findViewById(R.id.error_view)
    }

    override fun onStart() {
        super.onStart()
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        val uri = Uri.parse(url)
        val baseUrl = "http://${uri.host}:${uri.port}"
        api = ServerApi(baseUrl)
        RemoteLog.init(baseUrl)
        // Ask the server for a stored resume position before starting playback.
        Thread {
            val resumeMs = api?.getResumePositionMs() ?: 0
            mainHandler.post {
                if (!isFinishing) initializePlayer(url, resumeMs)
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(reportPosition)
        resumeDialog?.dismiss()
        resumeDialog = null
        val p = player
        val finalPositionMs = when {
            p == null -> -1
            p.playbackState == Player.STATE_ENDED -> 0 // finished: clear resume point
            else -> p.currentPosition
        }
        releasePlayer()
        Thread {
            if (finalPositionMs >= 0) api?.postPosition(finalPositionMs)
            RemoteLog.flushNow()
        }.start()
    }

    private fun initializePlayer(url: String, resumeMs: Long) {
        errorView.visibility = View.GONE
        RemoteLog.d(TAG, "opening $url (stored resume position: ${resumeMs}ms)")
        RemoteLog.d(TAG, "HDMI sink caps: ${AudioCaps.describe(this)}")
        RemoteLog.d(TAG, "raw HDMI encodings: ${AudioCaps.hdmiEncodings(this)}")
        RemoteLog.d(TAG, "FireOS6 atmos flag: ${AudioCaps.fireOs6AtmosEnabled(this)}")

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
                RemoteLog.d(TAG, "AudioTrack initialized: $audioTrackDescription")
                updateOverlay()
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                RemoteLog.d(TAG, "audio input format: ${describeFormat(format)}")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                RemoteLog.d(TAG, "audio DECODER in use: $decoderName (passthrough NOT active for this track)")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                RemoteLog.d(TAG, "video decoder: $decoderName")
            }

            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception,
            ) {
                RemoteLog.d(TAG, "audio sink error: ${Log.getStackTraceString(audioSinkError)}")
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                RemoteLog.d(TAG, "audio underrun: buffer=${bufferSize}B/${bufferSizeMs}ms starved=${elapsedSinceLastFeedMs}ms")
            }
        })
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                RemoteLog.d(TAG, "player error ${error.errorCodeName}: ${Log.getStackTraceString(error)}")
                showError(error)
            }

            override fun onTracksChanged(tracks: Tracks) {
                logTracks(tracks)
                updateOverlay()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                RemoteLog.d(TAG, "playback state: $playbackState")
                if (playbackState == Player.STATE_ENDED) {
                    Thread { api?.postPosition(0) }.start() // finished: clear resume point
                }
                updateOverlay()
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        if (resumeMs >= MIN_RESUME_MS) {
            exoPlayer.playWhenReady = false
            showResumeDialog(resumeMs)
        } else {
            exoPlayer.playWhenReady = true
        }
        mainHandler.postDelayed(reportPosition, POSITION_REPORT_INTERVAL_MS)
    }

    private fun showResumeDialog(resumeMs: Long) {
        resumeDialog = AlertDialog.Builder(this)
            .setTitle(R.string.resume_title)
            .setPositiveButton(getString(R.string.resume_from, formatTime(resumeMs))) { _, _ ->
                player?.seekTo(resumeMs)
                player?.playWhenReady = true
            }
            .setNegativeButton(R.string.start_over) { _, _ ->
                player?.playWhenReady = true
            }
            .setOnCancelListener {
                player?.playWhenReady = true
            }
            .show()
    }

    private fun formatTime(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
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
        // Back with the controller overlay up: dismiss the overlay, don't leave
        // the player. A second Back (on the clean movie frame) exits as usual.
        // Consuming ACTION_DOWN is enough — the framework only triggers back
        // navigation on ACTION_UP if the DOWN was tracked, and ours wasn't.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN &&
            playerView.isControllerFullyVisible
        ) {
            playerView.hideController()
            return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun logTracks(tracks: Tracks) {
        tracks.groups.forEachIndexed { i, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (j in 0 until group.length) {
                val format = group.getTrackFormat(j)
                RemoteLog.d(
                    TAG,
                    "audio track[$i:$j] ${describeFormat(format)} " +
                        "supported=${group.isTrackSupported(j)} selected=${group.isTrackSelected(j)}"
                )
            }
        }
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
        val rate =
            if (format.sampleRate != Format.NO_VALUE) " ${format.sampleRate}Hz" else ""
        val size = if (format.width != Format.NO_VALUE) " ${format.width}x${format.height}" else ""
        return "${format.sampleMimeType}$channels$rate$size"
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
        private const val TAG = "PlayerActivity"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        private const val MIN_RESUME_MS = 10_000L // don't prompt for the first few seconds
        private const val POSITION_REPORT_INTERVAL_MS = 5_000L
    }
}
