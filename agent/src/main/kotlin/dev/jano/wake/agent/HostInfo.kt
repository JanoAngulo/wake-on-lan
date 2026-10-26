package dev.jano.wake.agent

data class HostInfo(
    val hostname: String,
    val ip: String,
    val mac: String,
    val uptimeSeconds: Long,
    val version: String,
)
