package dev.jano.wake.agent

data class NicInfo(
    val name: String,
    val displayName: String,
    val ipv4: String,
    val prefixLength: Int,
    val mac: String?,
    val up: Boolean,
)

private val VIRTUAL_MARKERS = listOf(
    "radmin", "vpn", "virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "tap-", "wsl", "loopback", "bluetooth",
)

fun isVirtual(nic: NicInfo): Boolean {
    val name = nic.displayName.lowercase()
    return VIRTUAL_MARKERS.any { it in name }
}

internal fun ipv4ToInt(ip: String): Int =
    ip.split('.').fold(0) { acc, part -> (acc shl 8) or part.toInt() }

internal fun sameSubnet(a: String, b: String, prefixLength: Int): Boolean {
    if (prefixLength == 0) return true
    val mask = -1 shl (32 - prefixLength)
    return ipv4ToInt(a) and mask == ipv4ToInt(b) and mask
}

private fun isPrivate(ip: String): Boolean =
    sameSubnet(ip, "10.0.0.0", 8) ||
        sameSubnet(ip, "172.16.0.0", 12) ||
        sameSubnet(ip, "192.168.0.0", 16)

/** The physical adapter the home LAN runs on; never a VPN or virtual switch. */
fun pickLanInterface(nics: List<NicInfo>, gatewayIp: String?): NicInfo? {
    val candidates = nics.filter { it.up && it.mac != null && !isVirtual(it) }
    if (gatewayIp != null) {
        candidates.firstOrNull { sameSubnet(it.ipv4, gatewayIp, it.prefixLength) }?.let { return it }
    }
    return candidates.firstOrNull { isPrivate(it.ipv4) }
}
