package com.bitstreamer.client.discovery

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small client for the server's non-streaming endpoints. All methods are
 * blocking and swallow errors — call from a background thread; a dead server
 * must never disturb playback.
 */
class ServerApi(private val baseUrl: String) {

    /** Last stored resume position for this client, or 0 if none. */
    fun getResumePositionMs(): Long {
        return try {
            val conn = URL("$baseUrl/position").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(body).optLong("positionMs", 0)
        } catch (_: Exception) {
            0
        }
    }

    /** Stores the playback position; 0 clears the stored position. */
    fun postPosition(positionMs: Long) {
        try {
            val conn = URL("$baseUrl/position?ms=$positionMs").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}
