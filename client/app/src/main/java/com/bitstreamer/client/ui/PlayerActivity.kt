package com.bitstreamer.client.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.bitstreamer.client.R
import com.bitstreamer.client.discovery.ServerApi
import com.bitstreamer.client.logging.RemoteLog
import com.bitstreamer.client.playback.AudioCaps
import com.bitstreamer.client.playback.ChapterThumbnailLoader
import com.bitstreamer.client.playback.DvPlan
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
    private var rootIndex: Int? = null

    // Sidecar subtitle files the server found next to the movie (movie1.srt etc.).
    private var externalSubs: List<ServerApi.SubtitleTrack> = emptyList()

    // Authoritative colour info from the server's ffprobe.
    private var srcHdr = false
    private var srcHdr10Plus = false
    private var srcMime = ""
    private var srcTransfer = ""
    private var srcColorSpace = ""
    private var srcDvProfile = -1
    private var srcStripDV = false
    private var forceStripDV = false
    private var convertDv8 = false
    private var playNative = false
    private var lastTotalBytes = 0L
    private var lastTimestampMs = 0L
    private var liveMbps = 0.0
     private var srcVideoBitrate = 0L
    private var srcAudioBitrate = 0L
    private var srcVideoCodec = ""
    private var srcVideoProfile = ""
    private var srcVideoLevel = ""
    private var srcVideoRFrameRate = ""
    private var srcVideoAvgFrameRate = ""
    private var srcVideoPixFmt = ""
    private var srcVideoBitsPerRawSample = 0
    private var srcAudioTracks: List<ServerApi.AudioTrackInfo> = emptyList()
    private lateinit var playerRoot: View
    private lateinit var scrubPreview: LinearLayout
    private lateinit var scrubPreviewImage: ImageView
    private lateinit var scrubPreviewTime: TextView
    private var previewProgressOverlay: View? = null
    private var previewProgressBar: android.widget.ProgressBar? = null
    private var previewProgressText: TextView? = null
    private var generatePreviewsOnLaunch = false

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
            if (p.isPlaying && isVideo(currentTitle, srcMime)) {
                val pos = p.currentPosition
                val dur = p.duration
                val ms = if (dur > 0L && pos >= (dur * 0.9)) 0L else pos
                Thread { api?.postPosition(ms, infoPath.ifEmpty { null }, rootIndex) }.start()
            }
            mainHandler.postDelayed(this, POSITION_REPORT_INTERVAL_MS)
        }
    }

    // The right-hand time label in the controls, showing time *remaining*
    // (e.g. "-1:23:45") rather than the total duration Media3 would put there.
    private var timeRemainingView: TextView? = null
    private val timeFormatBuilder = StringBuilder()
    private val timeFormatter = java.util.Formatter(timeFormatBuilder, java.util.Locale.getDefault())

    // Updates the remaining-time label ~10x/sec while the player exists.
    private val remainingRefresh = object : Runnable {
        override fun run() {
            val p = player
            val tv = timeRemainingView
            if (p != null && tv != null) {
                val dur = p.duration
                if (dur != C.TIME_UNSET) {
                    val remaining = (dur - p.currentPosition).coerceAtLeast(0)
                    tv.text = "-" + Util.getStringForTime(timeFormatBuilder, timeFormatter, remaining)
                }
            }
            mainHandler.postDelayed(this, 100)
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
        previewProgressOverlay = findViewById(R.id.preview_progress_overlay)
        previewProgressBar = findViewById(R.id.preview_progress_bar)
        previewProgressText = findViewById(R.id.preview_progress_text)
        styleSubtitles()
    }

    /**
     * Styles text subtitles (SRT and other text cues). By default Media3 defers
     * to the Fire TV system caption style, which is white text on an opaque
     * black window — the full-width black rectangle. We replace that with a
     * Netflix/Prime-like look: soft-grey text with a black outline (a "shadow"
     * hugging each glyph) and **no** background box or window, at a modest size.
     * Vertical placement is handled separately (see [updateSubtitlePosition]) so
     * subtitles stay inside the picture on letterboxed widescreen movies.
     * Bitmap subtitles (PGS/VOBSUB) are images and can't be restyled; embedded
     * ASS/SSA styling is preserved.
     *
     * To tweak: `edgeType` can be EDGE_TYPE_OUTLINE (border) or _DROP_SHADOW
     * (softer shadow); set a non-transparent `backgroundColor` for a box that
     * hugs the text, or `windowColor` for a full-width rectangle. See docs/MEDIA3.md.
     */
    private fun styleSubtitles() {
        val sv = playerView.subtitleView ?: return
        sv.setApplyEmbeddedStyles(true) // keep ASS/SSA as authored; SRT has none
        sv.setStyle(
            CaptionStyleCompat(
                0xFFC0C0C0.toInt(),                 // text: soft grey, easier on the eyes than white
                Color.TRANSPARENT,                  // no background box behind the text
                Color.TRANSPARENT,                  // no full-width window rectangle
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,                        // black outline -> shadow around each glyph
                null,                               // default typeface
            )
        )
        sv.setFractionalTextSize(SUBTITLE_TEXT_SIZE_FRACTION)
        sv.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
    }

    /**
     * Places subtitles low in the picture regardless of the movie's aspect ratio.
     * Media3's SubtitleView is confined to the *video frame* (not the whole
     * screen), so setBottomPaddingFraction is measured within the picture: a
     * small fraction sits just above the picture's lower edge (above the black
     * bar on a letterboxed movie), a larger one floats higher into the frame.
     * We therefore drop letterboxed (wider-than-screen) movies close to the
     * picture's bottom edge, and keep the roomier placement for 16:9.
     */
    private fun updateSubtitlePosition() {
        val sv = playerView.subtitleView ?: return
        val vs = player?.videoSize ?: return
        val vw = playerView.width
        val vh = playerView.height
        if (vw == 0 || vh == 0 || vs.width == 0 || vs.height == 0) return
        val par = if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f
        val videoAspect = vs.width * par / vs.height
        val viewAspect = vw.toFloat() / vh
        val bottomFraction =
            if (videoAspect > viewAspect) SUBTITLE_LETTERBOX_BOTTOM_FRACTION
            else SUBTITLE_BOTTOM_PADDING_FRACTION
        sv.setBottomPaddingFraction(bottomFraction)
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
        forceStripDV = intent.getBooleanExtra(EXTRA_FORCE_STRIP_DV, false)
        convertDv8 = intent.getBooleanExtra(EXTRA_CONVERT_DV8, false)
        playNative = intent.getBooleanExtra(EXTRA_PLAY_NATIVE, false)
        generatePreviewsOnLaunch = intent.getBooleanExtra(EXTRA_GENERATE_PREVIEWS, false)
        if (intent.hasExtra(EXTRA_ROOT_INDEX)) {
            rootIndex = intent.getIntExtra(EXTRA_ROOT_INDEX, 0)
        }
        val uri = Uri.parse(url)
        baseUrl = "http://${uri.host}:${uri.port}"
        api = ServerApi(baseUrl)
        RemoteLog.init(baseUrl, this)
        // Fetch metadata before playback. Folder mode skips resume/storyboard.
        Thread {
            val a = api
            val info = a?.getInfo(infoPath.ifEmpty { null }, rootIndex)
            val mime = info?.mime ?: ""
            val isVid = isVideo(currentTitle, mime)
            val resumeMs = if (isVid) (a?.getResumePositionMs(infoPath.ifEmpty { null }, rootIndex) ?: 0L) else 0L
            val sb = if (info?.storyboardAvailable == true) a?.getStoryboard(infoPath.ifEmpty { null }, rootIndex) else null
            mainHandler.post {
                if (!isFinishing) {
                    chapters = info?.chapters ?: emptyList()
                    hasThumbnails = info?.thumbnailsAvailable ?: false
                    storyboardEnabled = info?.storyboardAvailable ?: false
                    storyboard = sb
                    srcHdr = info?.videoHdr ?: false
                    srcHdr10Plus = info?.videoHdr10Plus ?: false
                    srcMime = info?.mime ?: ""
                    srcTransfer = info?.videoTransfer ?: ""
                    srcColorSpace = info?.videoColorSpace ?: ""
                    srcDvProfile = info?.dvProfile ?: -1
                    srcStripDV = forceStripDV || (info?.stripDV ?: false)
                     srcVideoBitrate = info?.videoBitrate ?: 0L
                    srcAudioBitrate = info?.audioBitrate ?: 0L
                    srcVideoCodec = info?.videoCodec ?: ""
                    srcVideoProfile = info?.videoProfile ?: ""
                    srcVideoLevel = info?.videoLevel ?: ""
                    srcVideoRFrameRate = info?.videoRFrameRate ?: ""
                    srcVideoAvgFrameRate = info?.videoAvgFrameRate ?: ""
                    srcVideoPixFmt = info?.videoPixFmt ?: ""
                    srcVideoBitsPerRawSample = info?.videoBitsPerRawSample ?: 0
                    srcAudioTracks = info?.audioTracks ?: emptyList()
                    externalSubs = info?.subtitles ?: emptyList()
                    initializePlayer(url, resumeMs)
                    if (generatePreviewsOnLaunch) {
                        startPreviewGenerationProgress(isLaunch = true)
                    }
                }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(reportPosition)
        mainHandler.removeCallbacks(statsRefresh)
        mainHandler.removeCallbacks(remainingRefresh)
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
        val dur = p?.duration ?: -1L
        val pos = p?.currentPosition ?: -1L
        val finalPositionMs = when {
            p == null -> -1L
            p.playbackState == Player.STATE_ENDED -> 0L // finished: clear resume point
            dur > 0L && pos >= (dur * 0.9) -> 0L // reached >= 90% of duration: clear resume point
            else -> pos
        }
        releasePlayer()
        Thread {
            if (isVideo(currentTitle, srcMime) && finalPositionMs >= 0) {
                api?.postPosition(finalPositionMs, infoPath.ifEmpty { null }, rootIndex)
            }
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

        // Manual remote-menu choices win; otherwise the plan is decided automatically
        // from the detected DV profile and this device's capabilities.
        val userOverride: DvPlan? = when {
            srcStripDV -> DvPlan.STRIP_HDR10   // menu "Strip DV" or server -stripdv
            convertDv8 -> DvPlan.CONVERT_P8    // menu "Convert to DV8"
            playNative -> DvPlan.NATIVE        // menu "Play Normally"
            else -> null                       // plain launch -> automatic
        }
        val dvCaps = PlayerFactory.probeDvCaps(this)
        val plan = PlayerFactory.planDv(srcDvProfile, srcHdr10Plus, dvCaps, userOverride)
        RemoteLog.d(
            TAG,
            "video: dvProfile=$srcDvProfile hdr10+=$srcHdr10Plus stripDV=$srcStripDV " +
                "forceStripDV=$forceStripDV convertDv8=$convertDv8 playNative=$playNative " +
                "override=$userOverride caps=$dvCaps -> plan=$plan"
        )
        val exoPlayer = PlayerFactory.create(
            this,
            fallbackToHdr10 = plan == DvPlan.STRIP_HDR10,
            convertDv8 = plan == DvPlan.CONVERT_P8,
        )
        player = exoPlayer
        playerView.player = if (isImage(currentTitle)) {
            exoPlayer
        } else {
            PlayerFactory.withoutSpeedControls(exoPlayer)
        }
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

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Video dimensions known/changed -> re-place subtitles for the
                // movie's aspect ratio (view may not be measured yet, so post).
                playerView.post { updateSubtitlePosition() }
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
                    if (isVideo(currentTitle, srcMime)) {
                        Thread { api?.postPosition(0, infoPath.ifEmpty { null }, rootIndex) }.start() // finished: clear resume point
                    }
                    if (folderMode && isImage(currentTitle)) {
                        val nextIndex = playlistIndex + 1
                        val urls = playlistUrls
                        if (urls != null && nextIndex < urls.size) {
                            playAt(nextIndex)
                            return
                        }
                    }
                    finish()
                }
                updateOverlay()
            }
        })

        // Sidecar subtitle files (movie1.srt etc.) become extra selectable text
        // tracks. ExoPlayer merges them via SingleSampleMediaSource, so they keep
        // their real MIME (SRT/ASS/...) and show up in the subtitle menu.
        val subConfigs = externalSubs.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(baseUrl + s.url))
                .setId(EXT_SUB_ID_PREFIX + i) // marks it external so the menu can list it first
                .setMimeType(s.mime)
                .setLabel(s.label)
                .apply { if (s.lang.isNotEmpty()) setLanguage(s.lang) }
                .build()
        }
        // Images need an explicit duration to render; give a long one so they
        // stay until the user navigates (folder mode).
        val mediaItem = if (isImage(currentTitle)) {
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(if (srcMime.isNotEmpty()) srcMime else "image/jpeg")
                .setImageDurationMs(IMAGE_DURATION_MS)
                .build()
        } else {
            MediaItem.Builder()
                .setUri(url)
                .apply {
                    if (srcMime.isNotEmpty() && srcMime != "application/octet-stream") {
                        setMimeType(srcMime)
                    }
                }
                .setSubtitleConfigurations(subConfigs)
                .build()
        }
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (isVideo(currentTitle, srcMime) && resumeMs >= MIN_RESUME_MS) {
            exoPlayer.playWhenReady = false
            showResumeDialog(resumeMs)
        } else {
            exoPlayer.playWhenReady = true
        }
        if (isVideo(currentTitle, srcMime)) {
            mainHandler.postDelayed(reportPosition, POSITION_REPORT_INTERVAL_MS)
        }
    }

    private fun isVideo(title: String, mime: String): Boolean {
        if (mime.startsWith("video/")) return true
        if (mime.startsWith("audio/") || mime.startsWith("image/")) return false
        val ext = title.substringAfterLast('.', "").lowercase()
        val audioExts = setOf("mp3", "m4a", "aac", "ac3", "eac3", "ac4", "flac", "wav", "ogg", "opus", "amr", "mka")
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif")
        return ext.isNotEmpty() && ext !in audioExts && ext !in imageExts
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
            if (forceStripDV) putExtra(EXTRA_FORCE_STRIP_DV, true)
            if (convertDv8) putExtra(EXTRA_CONVERT_DV8, true)
            if (playNative) putExtra(EXTRA_PLAY_NATIVE, true)
            rootIndex?.let { putExtra(EXTRA_ROOT_INDEX, it) }
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
        // Controls hide quickly (2.5s) back to the clean movie frame, and do NOT
        // auto-appear on pause — pausing (OK on the clean frame) leaves the image
        // undimmed instead of popping the darkened control scrim.
        playerView.controllerShowTimeoutMs = 2_500
        playerView.controllerAutoShow = false
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.requestFocus()
                }
            }
        )

        timeRemainingView = playerView.findViewById(R.id.time_remaining)
        mainHandler.removeCallbacks(remainingRefresh)
        mainHandler.post(remainingRefresh)

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
        val btnPreviews = playerView.findViewById<ImageButton>(R.id.btn_previews)
        if (storyboardEnabled) {
            btnPreviews?.visibility = View.VISIBLE
            btnPreviews?.setOnClickListener {
                showPreviewConfirmationDialog()
            }
        } else {
            btnPreviews?.visibility = View.GONE
        }

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
            thumbnailLoader = ChapterThumbnailLoader(baseUrl, infoPath.ifEmpty { null }, rootIndex)
            RemoteLog.d(TAG, "chapters: ${chapters.size}, thumbnails=$hasThumbnails")
        } else {
            btnChapters?.visibility = View.GONE
        }
    }

    private fun showPreviewConfirmationDialog() {
        playerView.hideController()
        AlertDialog.Builder(this)
            .setTitle("Generate Previews")
            .setMessage("Do you want to generate seekbar previews and thumbnails for this video?")
            .setPositiveButton("Generate") { dialog, _ ->
                dialog.dismiss()
                startPreviewGenerationProgress(isLaunch = false)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun startPreviewGenerationProgress(isLaunch: Boolean) {
        val a = api ?: return
        RemoteLog.d(TAG, "startPreviewGenerationProgress triggered for $infoPath (isLaunch=$isLaunch)")
        mainHandler.post {
            player?.playWhenReady = false
            previewProgressOverlay?.visibility = View.VISIBLE
            previewProgressBar?.progress = 0
            previewProgressText?.text = "0%"
        }
        Thread {
            val statusInit = a.startPreviewGeneration(infoPath.ifEmpty { null }, rootIndex)
            if (statusInit == null) {
                RemoteLog.d(TAG, "startPreviewGeneration returned null for $infoPath — ffmpeg not installed or server error")
                mainHandler.post {
                    previewProgressOverlay?.visibility = View.GONE
                    android.widget.Toast.makeText(this@PlayerActivity, "ffmpeg is not installed on the server", android.widget.Toast.LENGTH_LONG).show()
                    player?.playWhenReady = true
                }
                return@Thread
            }
            if (statusInit.status == "ready" || statusInit.percent >= 100) {
                RemoteLog.d(TAG, "preview generation already ready for $infoPath (cache hit)")
                var sb: ServerApi.Storyboard? = null
                repeat(5) {
                    sb = a.getStoryboard(infoPath.ifEmpty { null }, rootIndex)
                    if (sb != null) return@repeat
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                }
                mainHandler.post {
                    previewProgressBar?.progress = 100
                    previewProgressText?.text = "100%"
                    previewProgressOverlay?.visibility = View.GONE
                    if (sb != null) enableStoryboard(sb)
                    thumbnailLoader?.release()
                    thumbnailLoader = ChapterThumbnailLoader(baseUrl, infoPath.ifEmpty { null }, rootIndex)
                    player?.playWhenReady = false
                }
                return@Thread
            }
            while (!isFinishing) {
                val status = a.getPreviewStatus(infoPath.ifEmpty { null }, rootIndex)
                if (status != null) {
                    mainHandler.post {
                        previewProgressBar?.progress = status.percent
                        previewProgressText?.text = "${status.percent}% (${status.done}/${status.total})"
                    }
                    if (status.status == "ready" || status.percent >= 100) {
                        RemoteLog.d(TAG, "preview generation finished (100%) for $infoPath")
                        var sb: ServerApi.Storyboard? = null
                        repeat(5) {
                            sb = a.getStoryboard(infoPath.ifEmpty { null }, rootIndex)
                            if (sb != null) return@repeat
                            try { Thread.sleep(200) } catch (_: InterruptedException) {}
                        }
                        mainHandler.post {
                            previewProgressBar?.progress = 100
                            previewProgressText?.text = "100%"
                            previewProgressOverlay?.visibility = View.GONE
                            if (sb != null) enableStoryboard(sb)
                            thumbnailLoader?.release()
                            thumbnailLoader = ChapterThumbnailLoader(baseUrl, infoPath.ifEmpty { null }, rootIndex)
                            player?.playWhenReady = false
                        }
                        break
                    }
                } else {
                    RemoteLog.d(TAG, "getPreviewStatus returned null for $infoPath during polling")
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private data class TrackEntry(
        val label: String,
        val group: TrackGroup?, // null = "Off"
        val trackIndex: Int,
        val selected: Boolean,
        val external: Boolean = false, // sidecar subtitle (listed first)
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
                val format = group.getTrackFormat(i)
                val name = nameProvider.getTrackName(format)
                val external = format.id?.startsWith(EXT_SUB_ID_PREFIX) == true
                // For subtitles, tag the format (SRT/ASS/PGS/...) after the name.
                val label = if (trackType == C.TRACK_TYPE_TEXT) {
                    "$name  [${subtitleTypeLabel(effectiveSubtitleMime(format))}]"
                } else {
                    name
                }
                entries.add(TrackEntry(label, group.mediaTrackGroup, i, group.isTrackSelected(i), external))
            }
        }
        if (entries.isEmpty()) return

        // Subtitles: list the on-disk sidecar tracks first (after "Off"), then the
        // embedded ones — otherwise the merged externals always land at the end.
        if (trackType == C.TRACK_TYPE_TEXT) {
            val off = entries.filter { it.group == null }
            val ext = entries.filter { it.group != null && it.external }
            val emb = entries.filter { it.group != null && !it.external }
            entries.clear()
            entries.addAll(off)
            entries.addAll(ext)
            entries.addAll(emb)
        }

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

    /**
     * The real subtitle format MIME. Media3 1.x parses text subtitles during
     * extraction, so the track's sampleMimeType becomes the generic
     * `application/x-media3-cues` wrapper and the original type (SRT/ASS/...) is
     * preserved in `codecs`. Fall back to sampleMimeType for anything not
     * transcoded (e.g. bitmap PGS/VOBSUB).
     */
    private fun effectiveSubtitleMime(format: Format): String? =
        if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES && format.codecs != null) {
            format.codecs
        } else {
            format.sampleMimeType
        }

    /** Short, friendly label for a subtitle track's format, shown in the menu. */
    private fun subtitleTypeLabel(mimeType: String?): String = when (mimeType) {
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VOBSUB"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> "CEA-608"
        MimeTypes.APPLICATION_CEA708 -> "CEA-708"
        null -> "?"
        else -> mimeType.substringAfterLast('/').uppercase()
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

        val currentPos = p.currentPosition
        val activeIndex = chapters.indexOfLast { it.startMs <= currentPos }.coerceAtLeast(0)
        listView.post {
            val listViewHeight = listView.height
            val firstChild = listView.getChildAt(0)
            val rowHeight = firstChild?.height ?: 180
            val offset = (listViewHeight - rowHeight) / 2
            listView.setSelectionFromTop(activeIndex, offset)
            listView.requestFocus()
        }
    }

    private fun seekToNextChapter() {
        val p = player ?: return
        if (chapters.isEmpty()) return
        val currentPos = p.currentPosition
        val nextChapter = chapters.firstOrNull { it.startMs > currentPos + 1000L }
        if (nextChapter != null) {
            p.seekTo(nextChapter.startMs)
            p.playWhenReady = true
        }
    }

    private fun seekToPrevChapter() {
        val p = player ?: return
        if (chapters.isEmpty()) return
        val currentPos = p.currentPosition
        val activeIndex = chapters.indexOfLast { it.startMs <= currentPos }.coerceAtLeast(0)
        val targetIndex = if (currentPos - chapters[activeIndex].startMs < 3000L) {
            (activeIndex - 1).coerceAtLeast(0)
        } else {
            activeIndex
        }
        p.seekTo(chapters[targetIndex].startMs)
        p.playWhenReady = true
    }

    private fun enableStoryboard(sb: ServerApi.Storyboard) {
        storyboard = sb
        storyboardLoader?.release()
        storyboardLoader = api?.let { StoryboardLoader(it, sb, infoPath.ifEmpty { null }, rootIndex) }
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
                val sb = a.getStoryboard(infoPath.ifEmpty { null }, rootIndex)
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
            .setNeutralButton(R.string.mark_as_done) { _, _ ->
                Thread { api?.postPosition(0, infoPath.ifEmpty { null }, rootIndex) }.start()
                player?.seekTo(0)
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
        if (previewProgressOverlay?.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
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
        // Center/OK on the clean frame (controls hidden) toggles play <-> pause.
        // When the controls ARE up we deliberately DON'T intercept it: OK then
        // goes to the focused control — committing an in-progress seek on the
        // time bar (playback keeps its play/pause state, so seeking while playing
        // resumes from the new point and seeking while paused stays paused), or
        // activating an Audio/Subtitles/Chapters/Stats button. Images have no
        // play/pause. Swallow DOWN+UP so no stray click reaches the controller.
        if ((event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) &&
            !playerView.isControllerFullyVisible
        ) {
            val p = player
            if (p != null &&
                p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    p.playWhenReady = !p.playWhenReady
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isImage(currentTitle)) {
                if (folderMode && playlistUrls != null) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { playAt(playlistIndex + 1); return true }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> { playAt(playlistIndex - 1); return true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { playAt(playlistIndex + 1); return true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { playAt(playlistIndex - 1); return true }
                    }
                }
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (chapters.isNotEmpty()) {
                            seekToNextChapter()
                        } else {
                            player?.let { it.seekTo((it.currentPosition + 30_000).coerceAtMost(it.duration)) }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (chapters.isNotEmpty()) {
                            seekToPrevChapter()
                        } else {
                            player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
                        }
                        return true
                    }
                }
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (previewProgressOverlay?.visibility == View.VISIBLE) {
            return
        }
        super.onBackPressed()
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

        val nowMs = SystemClock.elapsedRealtime()
        val totalBytes = PlayerFactory.getTotalBytesTransferred()
        if (lastTimestampMs > 0 && nowMs > lastTimestampMs) {
            val deltaBytes = totalBytes - lastTotalBytes
            val deltaTimeSec = (nowMs - lastTimestampMs) / 1000.0
            if (deltaTimeSec > 0 && deltaBytes >= 0) {
                val currentSpeedMbps = (deltaBytes * 8.0) / (deltaTimeSec * 1_000_000.0)
                liveMbps = if (deltaBytes > 0) {
                    if (liveMbps == 0.0) currentSpeedMbps else (liveMbps * 0.3 + currentSpeedMbps * 0.7)
                } else {
                    0.0
                }
            }
        }
        lastTotalBytes = totalBytes
        lastTimestampMs = nowMs

        row("file", intent.getStringExtra(EXTRA_TITLE) ?: "")
        row("state", playbackStateName(p?.playbackState) + if (p?.isPlaying == true) " (playing)" else " (paused)")
        if (p != null && p.duration > 0) {
            row("position", "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}  ${p.bufferedPercentage}% buf")
            val bwString = if (liveMbps > 0.05) {
                String.format("%.2f Mbps", liveMbps)
            } else if (p.isPlaying && p.bufferedPercentage >= 95) {
                "Buffered"
            } else {
                String.format("%.2f Mbps", liveMbps)
            }
            row("bandwidth", bwString)
        }

        sb.append("— video —\n")
        var videoCodecValue = codecStr(v)
        if (srcVideoProfile.isNotEmpty()) {
            videoCodecValue += " [$srcVideoProfile" + (if (srcVideoLevel.isNotEmpty()) " @ L$srcVideoLevel" else "") + "]"
        }
        row("codec", videoCodecValue)
        if (v != null && v.width != Format.NO_VALUE) {
            var resValue = "${v.width}x${v.height}"
            val fps = if (srcVideoRFrameRate.isNotEmpty()) srcVideoRFrameRate else srcVideoAvgFrameRate
            if (fps.isNotEmpty()) {
                resValue += " @ $fps fps"
            }
            if (srcVideoPixFmt.isNotEmpty()) {
                resValue += " ($srcVideoPixFmt" + (if (srcVideoBitsPerRawSample > 0) ", ${srcVideoBitsPerRawSample}bit" else "") + ")"
            }
            row("resolution", resValue)
        }
        if (v != null && v.frameRate != Format.NO_VALUE.toFloat() && v.frameRate > 0) {
            row("frame rate", String.format("%.3f fps", v.frameRate))
        }
        row("bitRate", bitrateStr((v?.bitrate ?: Format.NO_VALUE).toLong(), mbps = true))
        if (srcVideoBitrate > 0) {
            row("file rate", bitrateStr(srcVideoBitrate, mbps = true))
        }
        row("color", sourceColorStr())
        row("decoder", videoDecoderName)
        row("dropped", droppedFrames.toString())

        sb.append("— audio —\n")
        var activeAudioIndex = -1
        var audioIndexCounter = 0
        p?.currentTracks?.groups?.forEach { group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (j in 0 until group.length) {
                    if (group.isTrackSupported(j)) {
                        if (group.isTrackSelected(j)) {
                            activeAudioIndex = audioIndexCounter
                        }
                        audioIndexCounter++
                    }
                }
            }
        }
        val srcAudio = srcAudioTracks.getOrNull(activeAudioIndex)

        row("codec", codecStr(a))
        if (srcAudio != null && srcAudio.title.isNotEmpty()) {
            row("track title", srcAudio.title)
        }
        var chanVal = if (a != null && a.channelCount != Format.NO_VALUE) "${a.channelCount}" else ""
        if (srcAudio != null && srcAudio.channelLayout.isNotEmpty()) {
            chanVal += " (${srcAudio.channelLayout})"
        }
        if (chanVal.isNotEmpty()) row("channels", chanVal)
        if (a != null && a.sampleRate != Format.NO_VALUE) row("sample rate", "${a.sampleRate} Hz")
        row("audio rate", bitrateStr((a?.bitrate ?: Format.NO_VALUE).toLong(), mbps = false))
        val fileAudioBitrate = srcAudio?.bitrate ?: srcAudioBitrate
        if (fileAudioBitrate > 0) {
            row("bitRate", bitrateStr(fileAudioBitrate, mbps = false))
        }
        var langVal = a?.language ?: ""
        if (srcAudio != null && srcAudio.language.isNotEmpty()) {
            langVal = srcAudio.language
        }
        row("language", langVal)
        row("output", audioTrackDescription)
        //row("HDMI caps", AudioCaps.describe(this))

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

    private fun bitrateStr(bitrate: Long, mbps: Boolean): String {
        if (bitrate == Format.NO_VALUE.toLong() || bitrate <= 0L) return ""
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
        const val EXTRA_FORCE_STRIP_DV = "forceStripDV"
        const val EXTRA_CONVERT_DV8 = "convertDv8"
        const val EXTRA_PLAY_NATIVE = "playNative"
        const val EXTRA_GENERATE_PREVIEWS = "generatePreviews"
        const val EXTRA_PL_URLS = "playlistUrls"
        const val EXTRA_PL_TITLES = "playlistTitles"
        const val EXTRA_PL_INFO_PATHS = "playlistInfoPaths"
        const val EXTRA_PL_INDEX = "playlistIndex"
        const val EXTRA_ROOT_INDEX = "rootIndex"
        private const val IMAGE_DURATION_MS = 5_000L // 5 seconds duration for image files
        private const val MIN_RESUME_MS = 10_000L // don't prompt for the first few seconds
        private const val POSITION_REPORT_INTERVAL_MS = 5_000L
        // Subtitle sizing/placement (fractions of the player-view height). Text
        // size is a touch smaller than Media3's 0.0533 default.
        private const val SUBTITLE_TEXT_SIZE_FRACTION = 0.048f
        // Un-letterboxed (16:9) video: subtitles sit ~10% up from the screen
        // bottom, which reads well and leaves room below.
        private const val SUBTITLE_BOTTOM_PADDING_FRACTION = 0.10f
        // Letterboxed (wider-than-screen) video: the SubtitleView is confined to
        // the picture, so this small fraction sits the subtitles just above the
        // picture's lower edge (i.e. just above the black bar). See
        // updateSubtitlePosition.
        private const val SUBTITLE_LETTERBOX_BOTTOM_FRACTION = 0.03f
        private const val EXT_SUB_ID_PREFIX = "bitstreamer-sub-" // marks sidecar subtitle tracks
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
