package com.bitstreamer.client.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import com.bitstreamer.client.R
import com.bitstreamer.client.discovery.ServerApi
import com.bitstreamer.client.logging.RemoteLog
import com.bitstreamer.client.playback.AudioCaps
import com.bitstreamer.client.playback.ExternalPlayer
import com.bitstreamer.client.playback.PlayerFactory
import java.util.Locale

/**
 * Hands one file to the device's own player (Zidoo) and keeps BitStreamer's
 * server-side resume point in sync. Launched by [start] only when the first-
 * screen setting is "Player: Zidoo"; PlayerActivity is untouched.
 *
 * Flow: ask resume/start-over -> launch the Zidoo player on the /stream URL
 * (activity route, falling back to the 9529 control API) -> poll position every
 * 10 s and post it -> on return, post the final position (0 if >= 90 % played)
 * -> finish back to the browser.
 */
class ExternalPlaybackActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var api: ServerApi

    private var url = ""
    private var title = ""
    private var infoPath: String? = null
    private var rootIndex: Int? = null

    private var route: ExternalPlayer.Route? = null
    private var launched = false
    private var finished = false
    @Volatile private var lastPosMs = 0L
    @Volatile private var lastDurMs = 0L
    @Volatile private var seenPlaying = false
    @Volatile private var lastPostedMs = -1L
    private var poller: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#101318"))
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 20f
            gravity = Gravity.CENTER
            text = "Preparing Zidoo player…"
        }
        setContentView(statusView)

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        infoPath = intent.getStringExtra(EXTRA_INFO_PATH)?.ifEmpty { null }
        rootIndex = if (intent.hasExtra(EXTRA_ROOT_INDEX)) intent.getIntExtra(EXTRA_ROOT_INDEX, 0) else null
        val uri = Uri.parse(url)
        val base = "http://${uri.host}:${uri.port}"
        api = ServerApi(base)
        RemoteLog.init(base, this)

        if (savedInstanceState != null) {
            // Recreated while the Zidoo player was on top: the result will still arrive.
            launched = savedInstanceState.getBoolean("launched", false)
            lastPosMs = savedInstanceState.getLong("lastPosMs", 0)
            lastDurMs = savedInstanceState.getLong("lastDurMs", 0)
            route = savedInstanceState.getString("route")?.let {
                if (it == "api") ExternalPlayer.Route.ControlApi
                else ExternalPlayer.findComponent(this)?.let { c -> ExternalPlayer.Route.Activity(c) }
            }
            if (launched) { startPoller(); return }
        }

        Thread {
            val r = ExternalPlayer.detect(this)
            val resumeMs = api.getResumePositionMs(infoPath, rootIndex)
            logDeviceProbe(r)
            main.post {
                if (isFinishing) return@post
                route = r
                if (r == null) {
                    statusView.text = "No Zidoo player found on this device."
                    RemoteLog.d(TAG, "no external player route; nothing launched")
                    main.postDelayed({ finish() }, 2500)
                } else if (resumeMs >= MIN_RESUME_MS) {
                    askResume(resumeMs)
                } else {
                    launch(0)
                }
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("launched", launched)
        outState.putLong("lastPosMs", lastPosMs)
        outState.putLong("lastDurMs", lastDurMs)
        outState.putString("route", if (route is ExternalPlayer.Route.ControlApi) "api" else "activity")
    }

    private fun askResume(resumeMs: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.resume_title)
            .setPositiveButton(getString(R.string.resume_from, formatTime(resumeMs))) { _, _ -> launch(resumeMs) }
            .setNegativeButton(R.string.start_over) { _, _ -> launch(0) }
            .setNeutralButton(R.string.mark_as_done) { _, _ ->
                Thread { api.postPosition(0, infoPath, rootIndex) }.start()
                launch(0)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun launch(startMs: Long) {
        statusView.text = "Playing on Zidoo player…\n$title"
        val r = route
        if (r is ExternalPlayer.Route.Activity) {
            try {
                RemoteLog.d(TAG, "hand-off via activity ${r.component.flattenToShortString()} start=${startMs}ms url=$url")
                @Suppress("DEPRECATION")
                startActivityForResult(ExternalPlayer.buildIntent(r.component, url, title, startMs), REQ_PLAY)
                launched = true
                startPoller()
                return
            } catch (e: ActivityNotFoundException) {
                RemoteLog.d(TAG, "activity launch failed (${e.message}); falling back to control API")
            } catch (e: SecurityException) {
                RemoteLog.d(TAG, "activity launch refused (${e.message}); falling back to control API")
            }
            route = ExternalPlayer.Route.ControlApi
        }
        launchViaApi(startMs)
    }

    private fun launchViaApi(startMs: Long) {
        Thread {
            RemoteLog.d(TAG, "hand-off via 9529 control API start=${startMs}ms url=$url")
            val ok = ExternalPlayer.playViaApi(url)
            if (ok && startMs > 0) {
                // The API has no start-position parameter: wait for playback, then seek.
                for (i in 0 until 40) {
                    val s = ExternalPlayer.status()
                    if (s != null && s.playing) {
                        RemoteLog.d(TAG, "seek to resume point: ${ExternalPlayer.seekViaApi(startMs)}")
                        break
                    }
                    Thread.sleep(500)
                }
            }
            main.post {
                if (!ok) {
                    statusView.text = "The Zidoo player did not start."
                    RemoteLog.d(TAG, "control API openFile failed")
                    main.postDelayed({ finish() }, 2500)
                } else {
                    launched = true
                    startPoller()
                }
            }
        }.start()
    }

    /** Polls the Zidoo player for its position (both routes); ends the API route when the player closes. */
    private fun startPoller() {
        if (poller != null) return
        poller = Thread {
            var misses = 0
            var logged = false
            while (!finished) {
                val s = ExternalPlayer.status()
                if (s != null) {
                    misses = 0
                    if (s.positionMs > 0) lastPosMs = s.positionMs
                    if (s.durationMs > 0) lastDurMs = s.durationMs
                    if (s.playing) seenPlaying = true
                    if (!logged && s.playing) {
                        logged = true
                        RemoteLog.d(TAG, "zidoo playing: audio='${s.audio}' output='${s.output}' path='${s.path}' pos=${s.positionMs} dur=${s.durationMs}")
                    }
                    if (s.playing && kotlin.math.abs(lastPosMs - lastPostedMs) >= POST_STEP_MS) {
                        lastPostedMs = lastPosMs
                        api.postPosition(lastPosMs, infoPath, rootIndex)
                    }
                } else if (seenPlaying) {
                    misses++
                    // API route has no activity result: player gone for ~4 s => done.
                    if (route is ExternalPlayer.Route.ControlApi && misses >= 2) {
                        main.post { complete(lastPosMs, "control API: player closed") }
                        return@Thread
                    }
                }
                try { Thread.sleep(if (seenPlaying) POLL_MS else 2000) } catch (_: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PLAY) return
        val resultPos = data?.let {
            // The player returns an Int; accept a Long too in case firmware changes it.
            if (it.hasExtra("position")) it.getIntExtra("position", it.getLongExtra("position", 0L).toInt()).toLong() else -1L
        } ?: -1L
        val keys = data?.extras?.keySet()?.joinToString(",") ?: "none"
        RemoteLog.d(TAG, "player returned resultCode=$resultCode position=$resultPos extras=[$keys] lastPolled=$lastPosMs dur=$lastDurMs")
        complete(if (resultPos > 0) resultPos else lastPosMs, "activity result")
    }

    override fun onResume() {
        super.onResume()
        // API route: if we come back to the front after the player ran, it has closed.
        if (launched && !finished && route is ExternalPlayer.Route.ControlApi && seenPlaying) {
            Thread {
                Thread.sleep(1500)
                if (ExternalPlayer.status() == null) main.post { complete(lastPosMs, "control API: back in BitStreamer") }
            }.start()
        }
    }

    private fun complete(positionMs: Long, why: String) {
        if (finished) return
        finished = true
        poller?.interrupt()
        val dur = lastDurMs
        val finalMs = if (dur > 0 && positionMs >= dur * 0.9) 0L else positionMs
        RemoteLog.d(TAG, "playback done ($why): position=${positionMs}ms dur=${dur}ms -> stored ${finalMs}ms")
        Thread {
            api.postPosition(finalMs, infoPath, rootIndex)
            RemoteLog.flushNow()
        }.start()
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            finished = true
            poller?.interrupt()
        }
        super.onDestroy()
    }

    /** One log block per hand-off so the Zidoo's capabilities land in the server's client log. */
    private fun logDeviceProbe(r: ExternalPlayer.Route?) {
        try {
            RemoteLog.d(TAG, "device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
            RemoteLog.d(TAG, "external route: ${when (r) {
                is ExternalPlayer.Route.Activity -> "activity ${r.component.flattenToShortString()}"
                is ExternalPlayer.Route.ControlApi -> "9529 control API only"
                null -> "none"
            }}")
            RemoteLog.d(TAG, "HDMI audio caps (third-party apps): ${AudioCaps.describe(this)}")
            val dv = PlayerFactory.probeDvCaps(this)
            RemoteLog.d(TAG, "DV caps: decoder=${dv.hasDvDecoder} name=${dv.dvDecoderName} display=${dv.displaySupportsDv}")
        } catch (t: Throwable) {
            RemoteLog.d(TAG, "device probe failed: ${t.message}")
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    companion object {
        private const val TAG = "ExternalPlayback"
        private const val REQ_PLAY = 4711
        private const val MIN_RESUME_MS = 10_000L
        private const val POLL_MS = 10_000L
        private const val POST_STEP_MS = 5_000L

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_INFO_PATH = "infoPath"
        const val EXTRA_ROOT_INDEX = "rootIndex"

        fun start(context: Context, url: String, title: String, infoPath: String?, rootIndex: Int?) {
            context.startActivity(Intent(context, ExternalPlaybackActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                infoPath?.let { putExtra(EXTRA_INFO_PATH, it) }
                rootIndex?.let { putExtra(EXTRA_ROOT_INDEX, it) }
            })
        }
    }
}
