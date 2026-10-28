package dev.jano.wake.agent

data class NicInfo(
    val name: String,
    val displayName: String,
    val ipv4: String,
    val prefixLength: Int,
    val mac: String?,
    val up: Boolean,
)

// Never the LAN: tunnels and local-only links.
private val EXCLUDED_MARKERS = listOf("radmin", "vpn", "vmware", "virtualbox", "tap-", "wsl", "loopback", "bluetooth")

// Hyper-V adapters carry the LAN in two cases: the host's vEthernet on an External switch, and the network
// card inside a Hyper-V VM. Both hold the default gateway's subnet; Default Switch and WSL never do.
private val HYPERV_MARKERS = listOf("hyper-v", "vethernet", "virtual")

private fun matches(nic: NicInfo, markers: List<String>): Boolean {
    val name = nic.displayName.lowercase()
    return markers.any { it in name }
}

fun isVirtual(nic: NicInfo): Boolean = matches(nic, EXCLUDED_MARKERS) || matches(nic, HYPERV_MARKERS)

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

/** The adapter the home LAN runs on; never a VPN, and a Hyper-V adapter only when it holds the gateway. */
fun pickLanInterface(nics: List<NicInfo>, gatewayIp: String?): NicInfo? {
    val candidates = nics.filter { it.up && it.mac != null && !matches(it, EXCLUDED_MARKERS) }
    if (gatewayIp != null) {
        val onGateway = candidates.filter { sameSubnet(it.ipv4, gatewayIp, it.prefixLength) }
        (onGateway.firstOrNull { !isVirtual(it) } ?: onGateway.firstOrNull())?.let { return it }
    }
    return candidates.firstOrNull { !isVirtual(it) && isPrivate(it.ipv4) }
}
