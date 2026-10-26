package dev.jano.wake.agent

data class HostInfo(
    val hostname: String,
    val ip: String,
    val mac: String,
    val uptimeSeconds: Long,
    val version: String,
    val link: String = "Ethernet",
)

private val WIRELESS = Regex("wi-?fi|wireless|wlan|802\\.11", RegexOption.IGNORE_CASE)

fun linkKind(adapterName: String): String = if (WIRELESS.containsMatchIn(adapterName)) "Wi-Fi" else "Ethernet"
