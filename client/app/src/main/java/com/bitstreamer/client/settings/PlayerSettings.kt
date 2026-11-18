package com.bitstreamer.client.settings

import android.content.Context
import com.bitstreamer.client.playback.ExternalPlayer

/**
 * Which player plays a file: BitStreamer's own (ExoPlayer, the default and the
 * only choice on Fire TV) or the device's own player (Zidoo). Chosen on the
 * first screen and kept across app restarts.
 */
object PlayerSettings {

    enum class Mode { INTERNAL, EXTERNAL }

    private const val PREFS = "bitstreamer_settings"
    private const val KEY_MODE = "player_mode"

    /** The saved choice (INTERNAL unless the owner picked EXTERNAL). */
    fun savedMode(context: Context): Mode =
        if (prefs(context).getString(KEY_MODE, "internal") == "external") Mode.EXTERNAL else Mode.INTERNAL

    fun save(context: Context, mode: Mode) {
        prefs(context).edit()
            .putString(KEY_MODE, if (mode == Mode.EXTERNAL) "external" else "internal")
            .apply()
    }

    /** True when this device has a player we can hand off to (never on Fire TV). */
    fun externalAvailable(context: Context): Boolean = ExternalPlayer.looksLikeZidoo(context)

    /** The mode to use now: EXTERNAL only if saved AND still available. */
    fun effectiveMode(context: Context): Mode =
        if (savedMode(context) == Mode.EXTERNAL && externalAvailable(context)) Mode.EXTERNAL else Mode.INTERNAL

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
