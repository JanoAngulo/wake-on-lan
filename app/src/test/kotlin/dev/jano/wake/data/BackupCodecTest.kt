package dev.jano.wake.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackupCodecTest {
    private val pc = Device(id = "d1", name = "MY-PC", mac = "0A:1B:2C:3D:4E:5F", lastIp = "192.168.1.14",
        subnet = "192.168.1.0/24", folderId = "f1", paired = true)
    private val store = Store(devices = listOf(pc), folders = listOf(Folder("f1", "Desk")))

    @Test
    fun exportNeverMarksPaired() {
        val json = BackupCodec.export(store)
        assertFalse(json.contains("\"paired\":true"))
        val (devices, _) = BackupCodec.import(json).getOrThrow()
        assertFalse(devices.single().paired)
    }

    @Test
    fun exportImportRoundTrip() {
        val (devices, folders) = BackupCodec.import(BackupCodec.export(store)).getOrThrow()
        assertEquals(listOf(pc.copy(paired = false)), devices)
        assertEquals(store.folders, folders)
    }

    @Test
    fun importRejectsBadMac() {
        val json = """{"version":1,"devices":[{"id":"x","name":"Laptop","mac":"zz:zz"}],"folders":[]}"""
        val error = BackupCodec.import(json).exceptionOrNull()
        assertTrue(error?.message?.contains("Laptop") == true, error?.message)
    }

    @Test
    fun importNormalizesMac() {
        val json = """{"version":1,"devices":[{"id":"x","name":"PC","mac":"0a-1b-2c-3d-4e-5f"}],"folders":[]}"""
        assertEquals("0A:1B:2C:3D:4E:5F", BackupCodec.import(json).getOrThrow().first.single().mac)
    }

    @Test
    fun mergeAddsNewDevicesAndSkipsKnownMacs() {
        val incoming = listOf(
            pc.copy(id = "other-id", name = "Renamed", paired = false),
            Device(id = "d9", name = "Laptop", mac = "00:00:00:00:00:02"),
        )
        val merged = BackupCodec.merge(store, incoming, listOf(Folder("f1", "Desk"), Folder("f2", "Servers")))
        assertEquals(listOf("MY-PC", "Laptop"), merged.devices.map { it.name })
        assertTrue(merged.devices.first().paired, "existing pairing must survive an import")
        assertEquals(listOf("f1", "f2"), merged.folders.map { it.id })
    }

    @Test
    fun importRegeneratesCollidingIds() {
        val json = """{"version":1,"devices":[{"id":"x","name":"A","mac":"00:00:00:00:00:01"},""" +
            """{"id":"x","name":"B","mac":"00:00:00:00:00:02"}],"folders":[]}"""
        val devices = BackupCodec.import(json).getOrThrow().first
        assertNotEquals(devices[0].id, devices[1].id)
    }
}
