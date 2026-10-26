package dev.jano.wake.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceRepositoryTest {
    private val repo = DeviceRepository(InMemoryPreferences())
    private val pc = Device(id = "d1", name = "MY-PC", mac = "0A:1B:2C:3D:4E:5F", folderId = "f1")
    private val laptop = Device(id = "d2", name = "Laptop", mac = "00:00:00:00:00:02", lastIp = "192.168.1.30")

    @Test
    fun deleteFolderUnassignsDevices(): Unit = runBlocking {
        repo.upsertFolder(Folder("f1", "Desk"))
        repo.upsertDevice(pc)
        repo.deleteFolder("f1")
        val store = repo.store.first()
        assertEquals(emptyList(), store.folders)
        assertNull(store.devices.single().folderId)
    }

    @Test
    fun updateIpChangesOnlyThatDevice(): Unit = runBlocking {
        repo.upsertDevice(pc)
        repo.upsertDevice(laptop)
        repo.updateIp("d1", "192.168.1.20")
        val devices = repo.store.first().devices.associateBy { it.id }
        assertEquals("192.168.1.20", devices.getValue("d1").lastIp)
        assertEquals("192.168.1.30", devices.getValue("d2").lastIp)
    }

    @Test
    fun deleteDeviceClearsSelection(): Unit = runBlocking {
        repo.upsertDevice(pc)
        repo.select("d1")
        repo.deleteDevice("d1")
        val store = repo.store.first()
        assertEquals(emptyList(), store.devices)
        assertNull(store.selectedId)
    }

    @Test
    fun firstDeviceBecomesSelected(): Unit = runBlocking {
        repo.upsertDevice(pc)
        repo.upsertDevice(laptop)
        assertEquals("d1", repo.store.first().selectedId)
    }
}
