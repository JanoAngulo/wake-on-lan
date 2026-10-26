package dev.jano.wake.agent

import dev.jano.wake.protocol.DISCOVERY_PROBE
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.protocol.WakeJson
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** Answers "Find my PC" broadcasts, but only from the home LAN. */
class DiscoveryResponder(
    private val port: Int,
    private val lanPrefix: Pair<String, Int>,
    private val reply: () -> DiscoveryReply,
) {
    @Volatile private var socket: DatagramSocket? = null

    fun handle(payload: ByteArray, from: InetAddress): ByteArray? {
        if (payload.decodeToString().trim() != DISCOVERY_PROBE) return null
        if (from !is Inet4Address) return null
        if (!sameSubnet(from.hostAddress, lanPrefix.first, lanPrefix.second)) return null
        return WakeJson.encodeToString(DiscoveryReply.serializer(), reply()).toByteArray()
    }

    fun start() {
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        socket = s
        Thread({
            val buffer = ByteArray(256)
            while (!s.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    s.receive(packet)
                } catch (e: java.io.IOException) {
                    break
                }
                val answer = handle(packet.data.copyOf(packet.length), packet.address) ?: continue
                s.send(DatagramPacket(answer, answer.size, packet.socketAddress))
            }
        }, "wake-discovery").apply { isDaemon = true }.start()
    }

    fun stop() {
        socket?.close()
    }
}
