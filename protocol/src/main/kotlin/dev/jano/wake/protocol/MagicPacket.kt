package dev.jano.wake.protocol

/** Wake-on-LAN magic packet: 6 x 0xFF followed by the MAC repeated 16 times (102 bytes). */
fun magicPacket(mac: MacAddress): ByteArray {
    val macBytes = mac.bytes()
    val packet = ByteArray(6 + 16 * macBytes.size) { 0xFF.toByte() }
    for (k in 0 until 16) {
        macBytes.copyInto(packet, destinationOffset = 6 + k * macBytes.size)
    }
    return packet
}
