package com.bitstreamer.client.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.bitstreamer.client.R
import com.bitstreamer.client.discovery.DiscoveryClient

/**
 * Launcher screen: finds BitStreamer servers on the LAN via UDP broadcast and
 * lists them; also offers manual IP entry for networks that filter broadcast.
 */
class DiscoveryActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = DiscoveryClient()
    private val servers = mutableListOf<DiscoveryClient.Server>()

    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discovery)

        statusView = findViewById(R.id.status)
        listView = findViewById(R.id.server_list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            servers.getOrNull(position)?.let { play(it) }
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
            try {
                lock.acquire()
                found = client.discover()
                    .map { s -> client.fetchInfo(s.host, s.httpPort) ?: s }
            } finally {
                if (lock.isHeld) lock.release()
            }

            mainHandler.post {
                scanning = false
                servers.clear()
                servers.addAll(found)
                adapter.clear()
                adapter.addAll(found.map { s ->
                    if (s.file.isNotEmpty()) "${s.name} — ${s.file}" else "${s.name} (${s.host})"
                })
                statusView.text = if (found.isEmpty()) {
                    getString(R.string.status_none_found)
                } else {
                    getString(R.string.status_found, found.size)
                }
                if (found.isNotEmpty()) listView.requestFocus()
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
}
