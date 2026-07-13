package com.bitstreamer.client.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
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
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
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
    private var audioTrackDescription = "not initialized"
    private var videoDecoderName = "?"
    private var audioDecoderName = "?"
    private var droppedFrames = 0
    private var api: ServerApi? = null
    private var resumeDialog: AlertDialog? = null
    private var chaptersDialog: AlertDialog? = null
    private var trackDialog: AlertDialog? = null
    private var chapters: List<ServerApi.Chapter> = emptyList()
    private var thumbnailLoader: ChapterThumbnailLoader? = null
    private var hasThumbnails = false
    private var baseUrl: String = ""

    private var storyboard: ServerApi.Storyboard? = null
    private var storyboardLoader: StoryboardLoader? = null
    private var storyboardEnabled = false

    // Folder mode: playing a file from a browsed folder. No resume/chapters/
    // storyboard; RW/FF (and D-pad L/R for images) step to the prev/next file.
    private var folderMode = false
    private var infoPath = "" // relative path for /info?path= in folder mode
    private var currentTitle = ""
    private var playlistUrls: ArrayList<String>? = null
    private var playlistTitles: ArrayList<String>? = null
    private var playlistInfoPaths: ArrayList<String>? = null
    private var playlistIndex = 0

    // Authoritative colour info from the server's ffprobe.
    private var srcHdr = false
    private var srcHdr10Plus = false
    private var srcTransfer = ""
    private var srcColorSpace = ""
    private var srcDvProfile = -1
    private lateinit var playerRoot: View
    private lateinit var scrubPreview: LinearLayout
    private lateinit var scrubPreviewImage: ImageView
    private lateinit var scrubPreviewTime: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    // Recovery for transient HDMI audio-device failures. Switching apps (Home ->
    // another app -> back) can leave the passthrough AudioTrack momentarily
    // uncreatable ("Cannot create AudioTrack" / DEAD_OBJECT). The device usually
    // frees up within a few seconds, so we re-prepare with backoff instead of
    // wedging in IDLE. See docs/AUDIO_PASSTHROUGH.md.
    private var audioRetryCount = 0
    private val retryPlayback = Runnable {
        val p = player ?: return@Runnable
        RemoteLog.d(TAG, "re-preparing after audio-device error (attempt $audioRetryCount)")
        p.prepare() // resumes from the retained position; keeps playWhenReady
    }

    private val scrubListener = object : TimeBar.OnScrubListener {
        override fun onScrubStart(timeBar: TimeBar, position: Long) = showScrubPreview(position)
        override fun onScrubMove(timeBar: TimeBar, position: Long) = showScrubPreview(position)
        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
            hideScrubPreview()
            if (!canceled) {
                // Start a bit before the target so the scene shown in the preview
                // actually plays, instead of starting just after it.
                val target = (position - SEEK_LEAD_MS).coerceAtLeast(0)
                mainHandler.post { player?.seekTo(target) } // runs after the controller's own seek
            }
        }
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

    // Refreshes the stats overlay ~1x/sec while it's visible.
    private val statsRefresh = object : Runnable {
        override fun run() {
            updateOverlay()
            if (overlayView.visibility == View.VISIBLE) mainHandler.postDelayed(this, 1000)
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
        styleSubtitles()
    }

    /**
     * Styles text subtitles (SRT and other text cues). By default Media3 defers
     * to the Fire TV system caption style, which is white text on an opaque
     * black window — the full-width black rectangle. We replace that with an
     * Emby-like look: white text on a semi-transparent black background that
     * hugs the text (no window box), plus a thin black outline so it stays
     * legible over bright/HDR scenes. Bitmap subtitles (PGS/VOBSUB) are images
     * and can't be restyled; embedded ASS/SSA styling is preserved.
     *
     * To tweak: `backgroundColor` is the box that hugs the text (set to
     * Color.TRANSPARENT for outline-only, no box); `windowColor` is the
     * full-width rectangle (keep transparent); `edgeType` can be
     * EDGE_TYPE_NONE / _OUTLINE / _DROP_SHADOW. See docs/MEDIA3.md.
     */
    private fun styleSubtitles() {
        val sv = playerView.subtitleView ?: return
        sv.setApplyEmbeddedStyles(true) // keep ASS/SSA as authored; SRT has none
        sv.setStyle(
            CaptionStyleCompat(
                Color.WHITE,                        // text
                0xB3000000.toInt(),                 // background hugging the text (~70% black)
                Color.TRANSPARENT,                  // window: no full-width rectangle
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,                        // outline colour
                null,                               // default typeface
            )
        )
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    }

    override fun onStart() {
        super.onStart()
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        folderMode = intent.getBooleanExtra(EXTRA_FOLDER_MODE, false)
        infoPath = intent.getStringExtra(EXTRA_INFO_PATH) ?: ""
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        playlistUrls = intent.getStringArrayListExtra(EXTRA_PL_URLS)
        playlistTitles = intent.getStringArrayListExtra(EXTRA_PL_TITLES)
        playlistInfoPaths = intent.getStringArrayListExtra(EXTRA_PL_INFO_PATHS)
        playlistIndex = intent.getIntExtra(EXTRA_PL_INDEX, 0)
        val uri = Uri.parse(url)
        baseUrl = "http://${uri.host}:${uri.port}"
        api = ServerApi(baseUrl)
        RemoteLog.init(baseUrl)
        // Fetch metadata before playback. Folder mode skips resume/storyboard.
        Thread {
            val a = api
            val resumeMs = if (folderMode) 0L else (a?.getResumePositionMs() ?: 0L)
            val info = a?.getInfo(infoPath.ifEmpty { null })
            val sb = if (!folderMode && info?.storyboardAvailable == true) a?.getStoryboard() else null
            mainHandler.post {
                if (!isFinishing) {
                    chapters = info?.chapters ?: emptyList()
                    hasThumbnails = info?.thumbnailsAvailable ?: false
                    storyboardEnabled = info?.storyboardAvailable ?: false
                    storyboard = sb
                    srcHdr = info?.videoHdr ?: false
                    srcHdr10Plus = info?.videoHdr10Plus ?: false
                    srcTransfer = info?.videoTransfer ?: ""
                    srcColorSpace = info?.videoColorSpace ?: ""
                    srcDvProfile = info?.dvProfile ?: -1
                    initializePlayer(url, resumeMs)
                }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(reportPosition)
        mainHandler.removeCallbacks(statsRefresh)
        mainHandler.removeCallbacks(retryPlayback)
        resumeDialog?.dismiss()
        resumeDialog = null
        chaptersDialog?.dismiss()
        chaptersDialog = null
        trackDialog?.dismiss()
        trackDialog = null
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
            if (!folderMode && finalPositionMs >= 0) api?.postPosition(finalPositionMs)
            RemoteLog.flushNow()
        }.start()
    }

    private fun initializePlayer(url: String, resumeMs: Long) {
        errorView.visibility = View.GONE
        audioRetryCount = 0
        RemoteLog.d(TAG, "opening $url (stored resume position: ${resumeMs}ms)")
        RemoteLog.d(TAG, "HDMI sink caps: ${AudioCaps.describe(this)}")
        RemoteLog.d(TAG, "raw HDMI encodings: ${AudioCaps.hdmiEncodings(this)}")
        RemoteLog.d(TAG, "FireOS6 atmos flag: ${AudioCaps.fireOs6AtmosEnabled(this)}")

        RemoteLog.d(TAG, "video: dvProfile=$srcDvProfile hdr10+=$srcHdr10Plus (native DV decoder)")
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
                audioDecoderName = decoderName
                RemoteLog.d(TAG, "audio DECODER in use: $decoderName (passthrough NOT active for this track)")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoderName = decoderName
                RemoteLog.d(TAG, "video decoder: $decoderName")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrameCount: Int,
                elapsedMs: Long,
            ) {
                droppedFrames += droppedFrameCount
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
                if (isRecoverableAudioError(error) && audioRetryCount < MAX_AUDIO_RETRIES) {
                    audioRetryCount++
                    val delay = (RETRY_BASE_MS * audioRetryCount).coerceAtMost(RETRY_MAX_MS)
                    RemoteLog.d(TAG, "recoverable audio-device error ${error.errorCodeName} " +
                        "(${error.cause?.message}); retry $audioRetryCount/$MAX_AUDIO_RETRIES in ${delay}ms")
                    mainHandler.removeCallbacks(retryPlayback)
                    mainHandler.postDelayed(retryPlayback, delay)
                    return
                }
                RemoteLog.d(TAG, "player error ${error.errorCodeName}: ${Log.getStackTraceString(error)}")
                showError(error)
            }

            override fun onTracksChanged(tracks: Tracks) {
                logTracks(tracks)
                updateOverlay()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                RemoteLog.d(TAG, "playback state: $playbackState")
                if (playbackState == Player.STATE_READY) {
                    // Recovered (or a clean start): drop the retry budget and any
                    // stale error banner from a prior failed attempt.
                    audioRetryCount = 0
                    errorView.visibility = View.GONE
                }
                if (playbackState == Player.STATE_ENDED) {
                    Thread { api?.postPosition(0) }.start() // finished: clear resume point
                }
                updateOverlay()
            }
        })

        // Images need an explicit duration to render; give a long one so they
        // stay until the user navigates (folder mode).
        val mediaItem = if (isImage(currentTitle)) {
            MediaItem.Builder().setUri(url).setImageDurationMs(IMAGE_DURATION_MS).build()
        } else {
            MediaItem.fromUri(url)
        }
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (!folderMode && resumeMs >= MIN_RESUME_MS) {
            exoPlayer.playWhenReady = false
            showResumeDialog(resumeMs)
        } else {
            exoPlayer.playWhenReady = true
        }
        if (!folderMode) {
            mainHandler.postDelayed(reportPosition, POSITION_REPORT_INTERVAL_MS)
        }
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif")
    }

    /** Relaunches the player on the folder sibling at [index] (manual next/prev). */
    private fun playAt(index: Int) {
        val urls = playlistUrls ?: return
        if (index < 0 || index >= urls.size) return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URL, urls[index])
            putExtra(EXTRA_TITLE, playlistTitles?.getOrNull(index) ?: "")
            putExtra(EXTRA_INFO_PATH, playlistInfoPaths?.getOrNull(index) ?: "")
            putExtra(EXTRA_FOLDER_MODE, true)
            putStringArrayListExtra(EXTRA_PL_URLS, playlistUrls)
            putStringArrayListExtra(EXTRA_PL_TITLES, playlistTitles)
            putStringArrayListExtra(EXTRA_PL_INFO_PATHS, playlistInfoPaths)
            putExtra(EXTRA_PL_INDEX, index)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
        finish()
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
        playerView.findViewById<ImageButton>(R.id.btn_stats)?.setOnClickListener { toggleStats() }

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

    private data class TrackEntry(
        val label: String,
        val group: TrackGroup?, // null = "Off"
        val trackIndex: Int,
        val selected: Boolean,
    )

    /**
     * Custom audio/subtitle picker: selecting a row with D-pad OK applies it
     * immediately and closes (no OK/Cancel; Back cancels). The dialog is widened
     * to fit the longest track name so labels aren't truncated.
     */
    private fun showTrackDialog(trackType: Int, title: String, allowOff: Boolean) {
        val p = player ?: return
        val nameProvider = DefaultTrackNameProvider(resources)
        val entries = mutableListOf<TrackEntry>()

        if (allowOff) {
            val anySelected = p.currentTracks.groups.any { it.type == trackType && it.isSelected }
            entries.add(TrackEntry(getString(R.string.track_off), null, -1, !anySelected))
        }
        for (group in p.currentTracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                entries.add(
                    TrackEntry(
                        nameProvider.getTrackName(group.getTrackFormat(i)),
                        group.mediaTrackGroup, i, group.isTrackSelected(i),
                    )
                )
            }
        }
        if (entries.isEmpty()) return

        val labels = entries.map { (if (it.selected) "●  " else "○  ") + it.label }
        val listView = ListView(this)
        // Custom row wraps long labels (no truncation) with a sensible min width.
        listView.adapter = ArrayAdapter(this, R.layout.track_dialog_row, R.id.track_text, labels)
        listView.setOnItemClickListener { _, _, pos, _ ->
            val e = entries[pos]
            val params = p.trackSelectionParameters.buildUpon()
            if (e.group == null) {
                params.clearOverridesOfType(trackType).setTrackTypeDisabled(trackType, true)
            } else {
                params.setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(e.group, e.trackIndex))
            }
            p.trackSelectionParameters = params.build()
            trackDialog?.dismiss()
        }

        trackDialog = AlertDialog.Builder(this).setTitle(title).setView(listView).create()
        trackDialog?.show()
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
        mainHandler.removeCallbacks(retryPlayback)
        playerView.player = null
        player?.release()
        player = null
    }

    // Audio-device errors that a re-prepare can recover from: on Fire TV the
    // passthrough AudioTrack can briefly fail to (re)create after an app switch.
    private fun isRecoverableAudioError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Menu (remote's ≡ button) toggles the stats-for-nerds overlay.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
            toggleStats()
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
        // Folder mode: RW/FF step to the previous/next file; images also use
        // D-pad left/right (they have no meaningful seek bar).
        if (folderMode && playlistUrls != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { playAt(playlistIndex + 1); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { playAt(playlistIndex - 1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (isImage(currentTitle)) { playAt(playlistIndex + 1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (isImage(currentTitle)) { playAt(playlistIndex - 1); return true }
            }
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

    private fun toggleStats() {
        val show = overlayView.visibility != View.VISIBLE
        overlayView.visibility = if (show) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(statsRefresh)
        if (show) {
            updateOverlay()
            mainHandler.postDelayed(statsRefresh, 1000)
        }
    }

    /** Renders the "stats for nerds" table (Name  Value), refreshed while visible. */
    private fun updateOverlay() {
        if (overlayView.visibility != View.VISIBLE) return
        val p = player
        val v = p?.videoFormat
        val a = p?.audioFormat
        val sb = StringBuilder("── STATS FOR NERDS ──\n")
        fun row(name: String, value: String) {
            if (value.isNotEmpty()) sb.append(name.padEnd(11)).append(value).append('\n')
        }

        row("file", intent.getStringExtra(EXTRA_TITLE) ?: "")
        row("state", playbackStateName(p?.playbackState) + if (p?.isPlaying == true) " (playing)" else " (paused)")
        if (p != null && p.duration > 0) {
            row("position", "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}  ${p.bufferedPercentage}% buf")
        }

        sb.append("— video —\n")
        row("codec", codecStr(v))
        if (v != null && v.width != Format.NO_VALUE) row("resolution", "${v.width}x${v.height}")
        if (v != null && v.frameRate != Format.NO_VALUE.toFloat() && v.frameRate > 0) {
            row("frame rate", String.format("%.3f fps", v.frameRate))
        }
        row("video rate", bitrateStr(v?.bitrate ?: Format.NO_VALUE, mbps = true))
        row("color", sourceColorStr())
        row("decoder", videoDecoderName)
        row("dropped", droppedFrames.toString())

        sb.append("— audio —\n")
        row("codec", codecStr(a))
        if (a != null && a.channelCount != Format.NO_VALUE) row("channels", "${a.channelCount}")
        if (a != null && a.sampleRate != Format.NO_VALUE) row("sample rate", "${a.sampleRate} Hz")
        row("audio rate", bitrateStr(a?.bitrate ?: Format.NO_VALUE, mbps = false))
        row("language", a?.language ?: "")
        row("output", audioTrackDescription)
        row("HDMI caps", AudioCaps.describe(this))

        overlayView.text = sb.toString()
    }

    /** Compact one-line format description for RemoteLog. */
    private fun describeFormat(format: Format?): String {
        if (format == null) return "none"
        val channels =
            if (format.channelCount != Format.NO_VALUE) " ${format.channelCount}ch" else ""
        val rate =
            if (format.sampleRate != Format.NO_VALUE) " ${format.sampleRate}Hz" else ""
        val size = if (format.width != Format.NO_VALUE) " ${format.width}x${format.height}" else ""
        return "${format.sampleMimeType}$channels$rate$size"
    }

    private fun codecStr(f: Format?): String {
        if (f == null) return "none"
        val mime = f.sampleMimeType ?: "?"
        return if (f.codecs != null) "$mime (${f.codecs})" else mime
    }

    private fun bitrateStr(bitrate: Int, mbps: Boolean): String {
        if (bitrate == Format.NO_VALUE || bitrate <= 0) return ""
        return if (mbps) String.format("%.1f Mbps", bitrate / 1_000_000.0)
        else "${bitrate / 1000} kbps"
    }

    /** Colour/HDR from the server's ffprobe (authoritative; Media3's client-side
     *  colorInfo is unreliable and often reports HDR sources as SDR). */
    private fun sourceColorStr(): String {
        val parts = mutableListOf(
            when {
                srcTransfer == "smpte2084" -> "HDR10"
                srcTransfer == "arib-std-b67" -> "HLG"
                srcHdr -> "HDR"
                else -> "SDR"
            }
        )
        if (srcHdr10Plus) parts.add("HDR10+")
        if (srcDvProfile >= 0) parts.add("DV p$srcDvProfile")
        val space = when (srcColorSpace) {
            "bt2020nc", "bt2020c" -> "BT.2020"
            "bt709" -> "BT.709"
            else -> ""
        }
        val s = parts.joinToString(" + ")
        return if (space.isEmpty()) s else "$s · $space"
    }

    private fun playbackStateName(state: Int?): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "?"
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
        const val EXTRA_FOLDER_MODE = "folderMode"
        const val EXTRA_INFO_PATH = "infoPath"
        const val EXTRA_PL_URLS = "playlistUrls"
        const val EXTRA_PL_TITLES = "playlistTitles"
        const val EXTRA_PL_INFO_PATHS = "playlistInfoPaths"
        const val EXTRA_PL_INDEX = "playlistIndex"
        private const val IMAGE_DURATION_MS = 3_600_000L // 1h: image stays until user navigates
        private const val MIN_RESUME_MS = 10_000L // don't prompt for the first few seconds
        private const val POSITION_REPORT_INTERVAL_MS = 5_000L
        // Audio-device error recovery (transient passthrough failures after an
        // app switch). ~1-3s backoff, up to ~40s total before giving up.
        private const val MAX_AUDIO_RETRIES = 15
        private const val RETRY_BASE_MS = 1_000L
        private const val RETRY_MAX_MS = 3_000L
        private const val STORYBOARD_POLL_ATTEMPTS = 12
        private const val STORYBOARD_POLL_INTERVAL_MS = 5_000L
        private const val SEEK_LEAD_MS = 2_000L // start a bit before the scrubbed point
    }
}
