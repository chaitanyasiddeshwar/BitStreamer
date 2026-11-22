package com.bitstreamer.client.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Hand-off to a device's own video player (today: Zidoo). Used only when the
 * owner picks "Player: Zidoo" on the first screen; nothing here runs on a
 * Fire TV (no Zidoo player is installed there, so [detect] returns null).
 *
 * The external player reads BitStreamer's untouched HTTP /stream URL itself, so
 * the one rule (never transcode/remux) holds. Verified on a Z9X 8K (fw 1.3.45):
 * TrueHD, TrueHD Atmos, DTS-HD MA and DTS:X all reach the AVR bitstreamed when
 * the Zidoo player plays a /stream URL. See docs/ZIDOO_EXTERNAL_PLAYER.md.
 */
object ExternalPlayer {

    /** Known Zidoo player activities, newest first (from JellyfinToZidoo). */
    private val ZIDOO_COMPONENTS = listOf(
        ComponentName("com.zidoo.player", "com.zidoo.player.activity.PlayerActivity"),
        ComponentName("com.android.gallery3d", "com.android.gallery3d.app.MovieActivity"),
    )

    /** Zidoo's on-device control API (also reachable from the LAN). */
    private const val API = "http://127.0.0.1:9529/"

    /** How playback can be handed to the Zidoo player on this device. */
    sealed class Route {
        /** Launch the player activity directly; it returns a `position` result. */
        data class Activity(val component: ComponentName) : Route()
        /** No launchable component found, but the 9529 control API answers. */
        object ControlApi : Route()
    }

    /**
     * Returns the hand-off route, or null when this is not a Zidoo. Fast and safe:
     * the activity check is local; the API check (network, short timeout) runs
     * only when no component resolves, so call this off the main thread.
     */
    fun detect(context: Context): Route? {
        findComponent(context)?.let { return Route.Activity(it) }
        return if (apiReachable()) Route.ControlApi else null
    }

    /** Local-only check (safe on the main thread): is a Zidoo player activity installed? */
    fun findComponent(context: Context): ComponentName? {
        val pm = context.packageManager
        for (c in ZIDOO_COMPONENTS) {
            try {
                pm.getActivityInfo(c, 0)
                return c
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** True if a Zidoo player activity is installed, or the package is at least visible. */
    fun looksLikeZidoo(context: Context): Boolean {
        if (findComponent(context) != null) return true
        val pm = context.packageManager
        return listOf("com.zidoo.player", "com.zidoo.control.center", "com.zidoo.poster").any {
            try {
                pm.getPackageInfo(it, 0); true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Intent for the Zidoo player (extras as used by JellyfinToZidoo):
     * title, from_start/position (ms), return_result.
     */
    fun buildIntent(component: ComponentName, url: String, title: String, startMs: Long): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(Uri.parse(url), "video/*")
            setClassName(component.packageName, component.className)
            // Same launch flags as JellyfinToZidoo, whose users return to the
            // launching app after closing the Zidoo player.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            putExtra("title", title)
            if (startMs > 0) {
                putExtra("from_start", false)
                putExtra("position", startMs.toInt())
            } else {
                putExtra("from_start", true)
            }
            putExtra("return_result", true)
        }

    /** Starts [url] through the control API (fallback route). True on success. */
    fun playViaApi(url: String): Boolean {
        val upnp = "upnp://zidoo-rc/0.5.0?type=2&res=" + enc(url)
        val json = getJson("ZidooFileControl/v2/openFile?url=" + enc(upnp), 8000) ?: return false
        return json.optInt("code", -1) == 0
    }

    fun seekViaApi(ms: Long): Boolean {
        // Zidoo's API spells the parameter "positon".
        val json = getJson("ZidooVideoPlay/seekTo?positon=$ms", 3000) ?: return false
        return json.optInt("status", -1) == 200
    }

    data class Status(val playing: Boolean, val positionMs: Long, val durationMs: Long,
                      val path: String, val audio: String, val output: String)

    /** Current Zidoo-player state, or null when no video is open (API status 806) or unreachable. */
    fun status(): Status? {
        val json = getJson("ZidooVideoPlay/getPlayStatus", 2000) ?: return null
        if (json.optInt("status", -1) != 200) return null
        val v = json.optJSONObject("video") ?: return null
        return Status(
            playing = v.optInt("status", 0) == 1,
            positionMs = v.optLong("currentPosition", 0),
            durationMs = v.optLong("duration", 0),
            path = v.optString("path", ""),
            audio = v.optString("audioInfo", ""),
            output = v.optString("output", ""),
        )
    }

    private fun apiReachable(): Boolean =
        getJson("ZidooControlCenter/getModel", 1500)?.optInt("status", -1) == 200

    private fun getJson(path: String, timeoutMs: Int): JSONObject? = try {
        val conn = URL(API + path).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        JSONObject(body)
    } catch (_: Exception) {
        null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
