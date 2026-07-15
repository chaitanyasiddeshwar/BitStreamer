package com.bitstreamer.client.logging

import android.content.Context
import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Tees log lines to logcat and to the BitStreamer server's POST /log endpoint,
 * which appends them to a file next to bitstreamer.exe — so Fire TV playback
 * problems can be diagnosed from the PC without adb.
 */
object RemoteLog {

    private val queue = LinkedBlockingQueue<String>(4000)

    @Volatile
    private var endpoint: URL? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var started = false

    /** [serverBaseUrl] e.g. "http://192.168.1.20:46898". Safe to call repeatedly. */
    @Synchronized
    fun init(serverBaseUrl: String, context: Context) {
        endpoint = URL("$serverBaseUrl/log")
        if (!started) {
            started = true
            Thread(::loop, "RemoteLog").apply { isDaemon = true }.start()
            installCrashHandler()
        }
        val clientVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        d("RemoteLog", "---- session start ----")
        d(
            "RemoteLog",
            "client version: $clientVersion, " +
                "device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), " +
                "Android API ${Build.VERSION.SDK_INT}, build ${Build.DISPLAY}"
        )
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        queue.offer("$ts [$tag] $message") // offer: drop when full rather than block playback
    }

    /** Drains and posts synchronously. Call from a background thread only. */
    fun flushNow() {
        send(drain())
    }

    private fun loop() {
        while (true) {
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                return
            }
            send(drain())
        }
    }

    private fun drain(): List<String> {
        val lines = ArrayList<String>(256)
        queue.drainTo(lines, 500)
        return lines
    }

    private fun send(lines: List<String>) {
        val url = endpoint ?: return
        if (lines.isEmpty()) return
        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use {
                it.write((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            }
            conn.responseCode // force the request; 204 expected
            conn.disconnect()
        } catch (_: Exception) {
            // Server unreachable: drop the batch, never disturb playback.
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            d("CRASH", "uncaught on ${thread.name}: ${Log.getStackTraceString(throwable)}")
            val flusher = Thread { flushNow() }
            flusher.start()
            try {
                flusher.join(2000)
            } catch (_: InterruptedException) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
