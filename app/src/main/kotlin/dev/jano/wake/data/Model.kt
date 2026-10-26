package dev.jano.wake.data

import dev.jano.wake.protocol.Ports
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val name: String,
    val mac: String,
    val broadcast: String = "192.168.1.255",
    val port: Int = Ports.WOL,
    val lastIp: String? = null,
    val subnet: String? = null,
    val folderId: String? = null,
    val agentPort: Int = Ports.HTTP,
    val paired: Boolean = false,
)

@Serializable
data class Folder(val id: String, val name: String)

@Serializable
data class AppSettings(val pollMs: Long = 2_000, val haptics: Boolean = true)

@Serializable
data class Store(
    val devices: List<Device> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val selectedId: String? = null,
) {
    val selected: Device? get() = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()
}
