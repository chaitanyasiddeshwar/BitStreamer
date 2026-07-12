package com.bitstreamer.client.discovery

import android.os.SystemClock
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Finds BitStreamer servers by broadcasting the discovery probe over UDP and
 * collecting JSON replies. Blocking — call from a background thread.
 */
class DiscoveryClient {

    data class Server(
        val host: String,
        val name: String,
        val httpPort: Int,
        val file: String = "",
    ) {
        val streamUrl: String get() = "http://$host:$httpPort/stream"
    }

    /** Broadcasts up to [rounds] probes, returning as soon as a round finds servers. */
    fun discover(rounds: Int = 3, roundTimeoutMs: Long = 1000): List<Server> {
        val found = LinkedHashMap<String, Server>()
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = 250
            val payload = PROBE.toByteArray(Charsets.US_ASCII)
            val targets = broadcastAddresses()

            repeat(rounds) {
                for (target in targets) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, target, PORT))
                    } catch (_: IOException) {
                        // Interface may not allow broadcast; others might.
                    }
                }
                val deadline = SystemClock.elapsedRealtime() + roundTimeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val buf = ByteArray(512)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: IOException) {
                        break
                    }
                    parseReply(packet)?.let { found[it.host] = it }
                }
                if (found.isNotEmpty()) return found.values.toList()
            }
        } finally {
            socket.close()
        }
        return found.values.toList()
    }

    /** Fetches /info from a server to learn what file it is serving. */
    fun fetchInfo(host: String, httpPort: Int = DEFAULT_HTTP_PORT): Server? {
        return try {
            val conn = URL("http://$host:$httpPort/info").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(body)
            Server(
                host = host,
                name = json.optString("name", host),
                httpPort = httpPort,
                file = json.optString("file", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseReply(packet: DatagramPacket): Server? {
        return try {
            val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
            if (json.optString("app") != "bitstreamer") return null
            val host = packet.address?.hostAddress ?: return null
            Server(
                host = host,
                name = json.optString("name", host),
                httpPort = json.optInt("httpPort", DEFAULT_HTTP_PORT),
            )
        } catch (_: JSONException) {
            null
        }
    }

    /** 255.255.255.255 plus each interface's subnet-directed broadcast address. */
    private fun broadcastAddresses(): List<InetAddress> {
        val addrs = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return addrs
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    ia.broadcast?.let { addrs.add(it) }
                }
            }
        } catch (_: Exception) {
            // Fall back to the limited broadcast address alone.
        }
        return addrs.distinct()
    }

    companion object {
        const val PROBE = "BITSTREAMER_DISCOVER_V1"
        const val PORT = 46899
        const val DEFAULT_HTTP_PORT = 46898
    }
}
