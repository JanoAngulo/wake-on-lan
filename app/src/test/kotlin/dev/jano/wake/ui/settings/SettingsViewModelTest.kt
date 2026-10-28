package dev.jano.wake.ui.settings

import dev.jano.wake.data.Authenticator
import dev.jano.wake.data.Device
import dev.jano.wake.data.DeviceRepository
import dev.jano.wake.data.FakeUnlockPasswords
import dev.jano.wake.data.InMemoryPreferences
import dev.jano.wake.data.OpenResult
import dev.jano.wake.data.Secrets
import dev.jano.wake.main.AgentApiFactory
import dev.jano.wake.main.SignInApiFactory
import dev.jano.wake.main.AppGraph
import dev.jano.wake.main.Discoverer
import dev.jano.wake.main.HostProber
import dev.jano.wake.main.Pairer
import dev.jano.wake.main.PhoneNetReader
import dev.jano.wake.main.Waker
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.net.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = DeviceRepository(InMemoryPreferences())
    private val passwords = FakeUnlockPasswords()
    private val pc = Device(id = "d1", name = "MY-PC", mac = "0A:1B:2C:3D:4E:5F", lastIp = "192.168.1.14", paired = true)

    private val graph = AppGraph(
        repository = repo,
        secrets = object : Secrets {
            override fun put(deviceId: String, secret: ByteArray) = Unit
            override fun get(deviceId: String) = ByteArray(32)
            override fun remove(deviceId: String) = Unit
        },
        prober = HostProber { _, _ -> ProbeResult.Down },
        agents = AgentApiFactory { _, _ -> error("unused") },
        signIns = SignInApiFactory { _, _ -> error("unused") },
        discoverer = Discoverer { emptyList() },
        phoneNet = PhoneNetReader { PhoneNet(true, "192.168.1.23", 24) },
        waker = Waker { _, _, _ -> },
        pairer = Pairer { _, _ -> ByteArray(32) },
        unlockPasswords = passwords,
    )
    private lateinit var vm: SettingsViewModel

    private val approve = Authenticator { it }
    private val cancel = Authenticator { null }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = SettingsViewModel(graph)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun savedPassword(): ByteArray? {
        val opened = passwords.openCipher("d1") as? OpenResult.Ready ?: return null
        return passwords.open(opened.cipher, "d1")
    }

    @Test
    fun savePasswordStoresAfterFingerprint() = runTest(dispatcher) {
        repo.upsertDevice(pc)
        val typed = "pässwörd".toCharArray()
        vm.saveUnlockPassword("d1", typed, approve)
        advanceUntilIdle()
        assertContentEquals("pässwörd".toByteArray(), savedPassword())
        assertTrue(typed.all { it == '\u0000' }, "typed password zeroed")
        assertEquals(setOf("d1"), vm.unlockSaved.value)
        assertEquals("Saved. Lock your PC and tap Unlock to try it.", vm.unlockInfo.value)
    }

    @Test
    fun cancelledFingerprintSavesNothing() = runTest(dispatcher) {
        repo.upsertDevice(pc)
        val typed = "pässwörd".toCharArray()
        vm.saveUnlockPassword("d1", typed, cancel)
        advanceUntilIdle()
        assertEquals(false, passwords.saved("d1"))
        assertTrue(typed.all { it == '\u0000' })
        assertEquals(emptySet(), vm.unlockSaved.value)
    }

    @Test
    fun removeDeletesSavedPassword() = runTest(dispatcher) {
        repo.upsertDevice(pc)
        vm.saveUnlockPassword("d1", "x".toCharArray(), approve)
        advanceUntilIdle()
        vm.removeUnlockPassword("d1")
        advanceUntilIdle()
        assertEquals(false, passwords.saved("d1"))
        assertEquals(emptySet(), vm.unlockSaved.value)
    }

    @Test
    fun unlockSavedRefreshesAfterRemovalElsewhere() = runTest(dispatcher) {
        repo.upsertDevice(pc)
        vm.saveUnlockPassword("d1", "x".toCharArray(), approve)
        advanceUntilIdle()
        passwords.remove("d1") // what MainViewModel does after wrong_password
        vm.refreshUnlockSaved()
        advanceUntilIdle()
        assertEquals(emptySet(), vm.unlockSaved.value)
    }

    @Test
    fun keystoreFailureShowsMessageInsteadOfCrashing() = runTest(dispatcher) {
        repo.upsertDevice(pc)
        passwords.keystoreBroken = true
        val typed = "x".toCharArray()
        vm.saveUnlockPassword("d1", typed, approve)
        advanceUntilIdle()
        assertEquals("Couldn't save. Make sure this phone has a screen lock and a fingerprint set up.", vm.unlockInfo.value)
        assertTrue(typed.all { it == '\u0000' })
    }

    @Test
    fun unpairAlsoRemovesUnlockPassword() = runTest(dispatcher) {
        repo.upsertDevice(pc)
        vm.saveUnlockPassword("d1", "x".toCharArray(), approve)
        advanceUntilIdle()
        vm.unpair("d1")
        advanceUntilIdle()
        assertEquals(false, passwords.saved("d1"))
    }

    @Test
    fun deleteDeviceAlsoRemovesUnlockPassword() = runTest(dispatcher) {
        repo.upsertDevice(pc)
        vm.saveUnlockPassword("d1", "x".toCharArray(), approve)
        advanceUntilIdle()
        vm.deleteDevice("d1")
        advanceUntilIdle()
        assertEquals(false, passwords.saved("d1"))
    }
}
