package dev.jano.wake.net

import dev.jano.wake.protocol.MacAddress
import dev.jano.wake.protocol.Ports
import dev.jano.wake.protocol.magicPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends the magic packet [repeats] times; a dropped datagram is cheap insurance. */
suspend fun sendWake(
    mac: MacAddress,
    broadcast: String,
    port: Int = Ports.WOL,
    repeats: Int = 3,
    gapMs: Long = 100,
) = withContext(Dispatchers.IO) {
    val packet = magicPacket(mac)
    val target = InetAddress.getByName(broadcast)
    DatagramSocket().use { socket ->
        socket.broadcast = true
        repeat(repeats) { i ->
            socket.send(DatagramPacket(packet, packet.size, target, port))
            if (i < repeats - 1) delay(gapMs)
        }
    }
}
