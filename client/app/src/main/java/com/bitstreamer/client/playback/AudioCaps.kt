package com.bitstreamer.client.playback

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.provider.Settings
import org.json.JSONObject

/**
 * Reports what encodings the HDMI audio path advertises, per Amazon's Fire TV
 * Dolby integration guidelines (see docs/AUDIO_PASSTHROUGH.md §3). Used for
 * the debug overlay and error messages — Media3 does its own capability
 * detection for the actual passthrough decision.
 */
object AudioCaps {

    /** Union of encodings reported by the sticky HDMI intent and the HDMI output devices. */
    fun hdmiEncodings(context: Context): List<Int> {
        val encodings = sortedSetOf<Int>()

        // 1. Sticky ACTION_HDMI_AUDIO_PLUG broadcast (Amazon's recommended source).
        val sticky: Intent? =
            context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
        if (sticky != null && sticky.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1) {
            sticky.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)?.forEach { encodings.add(it) }
        }

        // 2. Cross-check with the HDMI output device's own report.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC
            }
            .forEach { device -> device.encodings.forEach { encodings.add(it) } }

        return encodings.toList()
    }

    /**
     * Fire OS 6 (API 25) has no ENCODING_E_AC3_JOC constant; Amazon exposes Atmos
     * support via the audio_platform_capabilities global setting instead.
     */
    fun fireOs6AtmosEnabled(context: Context): Boolean {
        return try {
            val caps = Settings.Global.getString(
                context.contentResolver, "audio_platform_capabilities"
            ) ?: return false
            JSONObject(caps)
                .optJSONObject("audiocaps")
                ?.optJSONObject("atmos")
                ?.optBoolean("enabled", false)
                ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Human-readable capability summary for the debug overlay. */
    fun describe(context: Context): String {
        val encodings = hdmiEncodings(context)
        if (encodings.isEmpty()) {
            return "HDMI sink reports no encodings (check Fire TV Surround Sound setting)"
        }
        val names = encodings.map { encodingName(it) }.toMutableList()
        if (fireOs6AtmosEnabled(context) && encodings.none { it == AudioFormat.ENCODING_E_AC3_JOC }) {
            names.add("Dolby Atmos (Fire OS 6 flag)")
        }
        return names.joinToString(", ")
    }

    fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        AudioFormat.ENCODING_AC3 -> "Dolby Digital (AC3)"
        AudioFormat.ENCODING_E_AC3 -> "Dolby Digital Plus (E-AC3)"
        AudioFormat.ENCODING_E_AC3_JOC -> "Dolby Atmos (E-AC3-JOC)"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        AudioFormat.ENCODING_DOLBY_MAT -> "Dolby MAT"
        else -> "encoding #$encoding"
    }
}
