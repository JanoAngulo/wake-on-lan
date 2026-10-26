package dev.jano.wake.data

import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.parseMac
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
private data class Backup(val version: Int = 1, val devices: List<Device> = emptyList(), val folders: List<Folder> = emptyList())

/** Device list as a JSON file. Pairing secrets never leave the phone, so exports are always unpaired. */
object BackupCodec {
    fun export(store: Store): String = WakeJson.encodeToString(
        Backup.serializer(),
        Backup(devices = store.devices.map { it.copy(paired = false) }, folders = store.folders),
    )

    fun import(json: String): Result<Pair<List<Device>, List<Folder>>> = runCatching {
        val backup = WakeJson.decodeFromString(Backup.serializer(), json)
        val seen = mutableSetOf<String>()
        val devices = backup.devices.map { device ->
            val mac = parseMac(device.mac)
                ?: throw IllegalArgumentException("\"${device.name}\" has an invalid MAC address: ${device.mac}")
            val id = if (seen.add(device.id)) device.id else UUID.randomUUID().toString().also(seen::add)
            device.copy(id = id, mac = mac.value, paired = false)
        }
        devices to backup.folders
    }
}
