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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
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
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.bitstreamer.client.R
import com.bitstreamer.client.discovery.ServerApi
import com.bitstreamer.client.logging.RemoteLog
import com.bitstreamer.client.playback.AudioCaps
import com.bitstreamer.client.playback.ChapterThumbnailLoader
import com.bitstreamer.client.playback.PlayerFactory
import com.bitstreamer.client.playback.StoryboardLoader
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
    private var chaptersDialog: AlertDialog? = null
    private var chapters: List<ServerApi.Chapter> = emptyList()
    private var thumbnailLoader: ChapterThumbnailLoader? = null
    private var hasThumbnails = false
    private var baseUrl: String = ""

    private var storyboard: ServerApi.Storyboard? = null
    private var storyboardLoader: StoryboardLoader? = null
    private var storyboardEnabled = false
    private lateinit var playerRoot: View
    private lateinit var scrubPreview: LinearLayout
    private lateinit var scrubPreviewImage: ImageView
    private lateinit var scrubPreviewTime: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scrubListener = object : TimeBar.OnScrubListener {
        override fun onScrubStart(timeBar: TimeBar, position: Long) = showScrubPreview(position)
        override fun onScrubMove(timeBar: TimeBar, position: Long) = showScrubPreview(position)
        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) = hideScrubPreview()
    }

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
        playerRoot = findViewById(R.id.player_root)
        scrubPreview = findViewById(R.id.scrub_preview)
        scrubPreviewImage = findViewById(R.id.scrub_preview_image)
        scrubPreviewTime = findViewById(R.id.scrub_preview_time)
    }

    override fun onStart() {
        super.onStart()
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        val uri = Uri.parse(url)
        baseUrl = "http://${uri.host}:${uri.port}"
        api = ServerApi(baseUrl)
        RemoteLog.init(baseUrl)
        // Ask the server for the resume position and chapter/thumbnail info
        // before starting playback (cheap /position + /info reads).
        Thread {
            val a = api
            val resumeMs = a?.getResumePositionMs() ?: 0
            val info = a?.getInfo()
            // Storyboard may still be generating; fetch it if the server reports
            // it enabled, and poll later (setupControls) if not ready yet.
            val sb = if (info?.storyboardAvailable == true) a?.getStoryboard() else null
            mainHandler.post {
                if (!isFinishing) {
                    chapters = info?.chapters ?: emptyList()
                    hasThumbnails = info?.thumbnailsAvailable ?: false
                    storyboardEnabled = info?.storyboardAvailable ?: false
                    storyboard = sb
                    initializePlayer(url, resumeMs)
                }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(reportPosition)
        resumeDialog?.dismiss()
        resumeDialog = null
        chaptersDialog?.dismiss()
        chaptersDialog = null
        thumbnailLoader?.release()
        thumbnailLoader = null
        storyboardLoader?.release()
        storyboardLoader = null
        hideScrubPreview()
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
        playerView.player = PlayerFactory.withoutSpeedControls(exoPlayer)
        setupControls()

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

    /**
     * Wires the custom controller: makes the time bar the default focus (so
     * D-pad left/right scrubs), and connects the Audio/Subtitles/Chapters
     * option row. See docs/MEDIA3.md for the D-pad layer design.
     */
    private fun setupControls() {
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.requestFocus()
                }
            }
        )

        val btnAudio = playerView.findViewById<ImageButton>(R.id.btn_audio)
        val btnSubs = playerView.findViewById<ImageButton>(R.id.btn_subtitles)
        val btnChapters = playerView.findViewById<ImageButton>(R.id.btn_chapters)

        btnAudio?.setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_AUDIO, getString(R.string.dialog_audio_title), allowOff = false)
        }
        btnSubs?.setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_TEXT, getString(R.string.dialog_subtitles_title), allowOff = true)
        }
        btnChapters?.setOnClickListener { showChaptersDialog() }

        // Seek-bar D-pad step = storyboard interval (default 30s), so each
        // left/right press lands on the next preview frame — and no more
        // "jumps several minutes". Applied whether or not previews exist.
        val timeBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        timeBar?.setKeyTimeIncrement(storyboard?.intervalMs ?: 30_000L)
        timeBar?.removeListener(scrubListener)
        timeBar?.addListener(scrubListener) // no-op preview until a loader exists
        when {
            storyboard != null -> enableStoryboard(storyboard!!)
            storyboardEnabled -> pollStoryboard() // still generating on the server
        }

        if (chapters.isNotEmpty()) {
            btnChapters?.visibility = View.VISIBLE
            // From the option row, D-pad DOWN opens the chapter markers.
            val openChapters = View.OnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    showChaptersDialog()
                    true
                } else {
                    false
                }
            }
            btnAudio?.setOnKeyListener(openChapters)
            btnSubs?.setOnKeyListener(openChapters)
            btnChapters?.setOnKeyListener(openChapters)

            val times = chapters.map { it.startMs }.toLongArray()
            playerView.setExtraAdGroupMarkers(times, BooleanArray(times.size))
            thumbnailLoader?.release()
            thumbnailLoader = if (hasThumbnails) ChapterThumbnailLoader(baseUrl) else null
            RemoteLog.d(TAG, "chapters: ${chapters.size}, thumbnails=$hasThumbnails")
        } else {
            btnChapters?.visibility = View.GONE
        }
    }

    private fun showTrackDialog(trackType: Int, title: String, allowOff: Boolean) {
        val p = player ?: return
        TrackSelectionDialogBuilder(this, title, p, trackType)
            .setAllowAdaptiveSelections(false)
            .setShowDisableOption(allowOff)
            .build()
            .show()
    }

    private fun showChaptersDialog() {
        val p = player ?: return
        if (chapters.isEmpty()) return
        val listView = ListView(this)
        listView.adapter = ChapterListAdapter(this, chapters, thumbnailLoader)
        listView.setOnItemClickListener { _, _, position, _ ->
            p.seekTo(chapters[position].startMs)
            p.playWhenReady = true
            chaptersDialog?.dismiss()
        }
        chaptersDialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_chapters_title)
            .setView(listView)
            .create()
        chaptersDialog?.show()
    }

    private fun enableStoryboard(sb: ServerApi.Storyboard) {
        storyboard = sb
        storyboardLoader?.release()
        storyboardLoader = api?.let { StoryboardLoader(it, sb) }
        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            ?.setKeyTimeIncrement(sb.intervalMs)
        RemoteLog.d(TAG, "scrub previews enabled: ${sb.tileCount} tiles @${sb.intervalMs}ms")
    }

    /** Polls for the storyboard manifest while the server is still generating it. */
    private fun pollStoryboard() {
        val a = api ?: return
        Thread {
            repeat(STORYBOARD_POLL_ATTEMPTS) {
                if (isFinishing || storyboardLoader != null) return@Thread
                val sb = a.getStoryboard()
                if (sb != null) {
                    mainHandler.post { if (!isFinishing) enableStoryboard(sb) }
                    return@Thread
                }
                try {
                    Thread.sleep(STORYBOARD_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.start()
    }

    private fun showScrubPreview(position: Long) {
        val loader = storyboardLoader ?: return
        scrubPreview.visibility = View.VISIBLE
        scrubPreviewTime.text = formatTime(position)
        loader.tileForTime(position) { bmp ->
            if (scrubPreview.visibility == View.VISIBLE && bmp != null) {
                scrubPreviewImage.setImageBitmap(bmp)
            }
        }
        positionScrubPreview(position)
    }

    private fun hideScrubPreview() {
        if (this::scrubPreview.isInitialized) scrubPreview.visibility = View.GONE
    }

    /** Positions the preview horizontally under the scrub thumb, above the seek bar. */
    private fun positionScrubPreview(position: Long) {
        val duration = player?.duration ?: return
        if (duration <= 0) return
        val timeBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress) ?: return

        val tbLoc = IntArray(2)
        timeBar.getLocationOnScreen(tbLoc)
        val rootLoc = IntArray(2)
        playerRoot.getLocationOnScreen(rootLoc)

        val frac = (position.toFloat() / duration).coerceIn(0f, 1f)
        val scrubX = (tbLoc[0] - rootLoc[0]) + frac * timeBar.width
        val topInRoot = tbLoc[1] - rootLoc[1]
        val gap = 12 * resources.displayMetrics.density
        scrubPreview.post {
            val w = scrubPreview.width
            val maxX = (playerRoot.width - w).toFloat().coerceAtLeast(0f)
            scrubPreview.translationX = (scrubX - w / 2f).coerceIn(0f, maxX)
            scrubPreview.translationY = (topInRoot - scrubPreview.height - gap).coerceAtLeast(0f)
        }
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
        private const val STORYBOARD_POLL_ATTEMPTS = 12
        private const val STORYBOARD_POLL_INTERVAL_MS = 5_000L
    }
}
