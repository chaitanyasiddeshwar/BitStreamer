package com.bitstreamer.client.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.bitstreamer.client.R
import com.bitstreamer.client.discovery.DiscoveryClient
import com.bitstreamer.client.discovery.ServerApi
import java.util.Locale

/**
 * Launcher screen: finds BitStreamer servers on the LAN via UDP broadcast and
 * lists them; also offers manual IP entry. Focusing a server shows its full
 * /info metadata as a table before you play it.
 */
class DiscoveryActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = DiscoveryClient()
    private val servers = mutableListOf<DiscoveryClient.Server>()
    private val infoByHost = HashMap<String, ServerApi.Info>()

    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private lateinit var detailsView: LinearLayout
    private lateinit var adapter: ArrayAdapter<String>
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discovery)

        statusView = findViewById(R.id.status)
        listView = findViewById(R.id.server_list)
        detailsView = findViewById(R.id.server_details)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            servers.getOrNull(position)?.let { play(it) }
        }
        listView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                renderDetails(servers.getOrNull(position)?.host)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = renderDetails(null)
        }

        findViewById<Button>(R.id.retry_button).setOnClickListener { scan() }
        findViewById<Button>(R.id.connect_button).setOnClickListener { connectManually() }
    }

    override fun onResume() {
        super.onResume()
        scan()
    }

    private fun scan() {
        if (scanning) return
        scanning = true
        statusView.text = getString(R.string.status_scanning)

        Thread {
            // Broadcast reception can be power-filtered without a MulticastLock.
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("bitstreamer-discovery")
            val found: List<DiscoveryClient.Server>
            val infos = HashMap<String, ServerApi.Info>()
            try {
                lock.acquire()
                found = client.discover().map { s -> client.fetchInfo(s.host, s.httpPort) ?: s }
                for (s in found) {
                    ServerApi("http://${s.host}:${s.httpPort}").getInfo()?.let { infos[s.host] = it }
                }
            } finally {
                if (lock.isHeld) lock.release()
            }

            mainHandler.post {
                scanning = false
                servers.clear()
                servers.addAll(found)
                infoByHost.clear()
                infoByHost.putAll(infos)
                adapter.clear()
                adapter.addAll(found.map { s ->
                    if (s.file.isNotEmpty()) "${s.name} — ${s.file}" else "${s.name} (${s.host})"
                })
                statusView.text = if (found.isEmpty()) {
                    getString(R.string.status_none_found)
                } else {
                    getString(R.string.status_found, found.size)
                }
                if (found.isNotEmpty()) {
                    listView.requestFocus()
                    listView.setSelection(0)
                    renderDetails(found[0].host)
                } else {
                    renderDetails(null)
                }
            }
        }.start()
    }

    private fun connectManually() {
        val host = findViewById<EditText>(R.id.ip_entry).text.toString().trim()
        if (host.isEmpty()) return
        statusView.text = getString(R.string.status_connecting, host)
        Thread {
            val server = client.fetchInfo(host)
            mainHandler.post {
                if (server != null) {
                    play(server)
                } else {
                    statusView.text = getString(R.string.status_connect_failed, host)
                }
            }
        }.start()
    }

    private fun play(server: DiscoveryClient.Server) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, server.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, server.file.ifEmpty { server.name })
        })
    }

    // ---- details table ----

    private fun renderDetails(host: String?) {
        detailsView.removeAllViews()
        val info = host?.let { infoByHost[it] } ?: return
        addHeader(getString(R.string.details_header))
        addRow("File", info.file)
        addRow("Size", humanSize(info.sizeBytes))
        addRow("Container", friendlyMime(info.mime))
        addRow("Video", colourSummary(info))
        addRow("Chapters", if (info.chapters.isEmpty()) "None" else info.chapters.size.toString())
        addRow("Thumbnails", if (info.thumbnailsAvailable) "Yes" else "No")
        addRow("Scrub previews", if (info.storyboardAvailable) "Yes" else "No")
    }

    private fun addHeader(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(10))
        }
        detailsView.addView(tv)
    }

    private fun addRow(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val labelView = TextView(this).apply {
            this.text = label
            textSize = 16f
            setTextColor(0xFF9AA4B2.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val valueView = TextView(this).apply {
            this.text = value
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(labelView)
        row.addView(valueView)
        detailsView.addView(row)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "unknown"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var s = bytes.toDouble()
        var i = 0
        while (s >= 1024 && i < units.size - 1) { s /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", s, units[i])
    }

    private fun friendlyMime(mime: String): String = when (mime) {
        "video/x-matroska" -> "Matroska (MKV)"
        "video/mp4", "video/m4v" -> "MP4"
        "video/quicktime" -> "QuickTime (MOV)"
        "video/webm" -> "WebM"
        "" -> "unknown"
        else -> mime
    }

    private fun colourSummary(info: ServerApi.Info): String {
        val parts = mutableListOf<String>()
        parts.add(
            when {
                info.videoTransfer == "smpte2084" -> "HDR10"
                info.videoTransfer == "arib-std-b67" -> "HLG"
                info.videoHdr -> "HDR"
                else -> "SDR"
            }
        )
        if (info.videoHdr10Plus) parts.add("HDR10+")
        if (info.dvProfile >= 0) parts.add("Dolby Vision (Profile ${info.dvProfile})")
        val space = when (info.videoColorSpace) {
            "bt2020nc", "bt2020c" -> "BT.2020"
            "bt709" -> "BT.709"
            else -> ""
        }
        val s = parts.joinToString(" + ")
        return if (space.isEmpty()) s else "$s · $space"
    }
}
