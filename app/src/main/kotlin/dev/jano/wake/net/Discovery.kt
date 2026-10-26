package dev.jano.wake.net

import dev.jano.wake.protocol.DISCOVERY_PROBE
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.protocol.Ports
import dev.jano.wake.protocol.WakeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Broadcasts the discovery probe and collects agent replies until [timeoutMs] passes. */
suspend fun discoverAgents(broadcast: String, timeoutMs: Long = 5_000): List<DiscoveryReply> =
    withContext(Dispatchers.IO) {
        val replies = LinkedHashMap<String, DiscoveryReply>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val probe = DISCOVERY_PROBE.toByteArray()
            socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName(broadcast), Ports.DISCOVERY))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(1024)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val text = String(buffer, 0, packet.length)
                runCatching { WakeJson.decodeFromString(DiscoveryReply.serializer(), text) }
                    .getOrNull()
                    ?.let { replies.putIfAbsent(it.mac, it) }
            }
        }
        replies.values.toList()
    }
