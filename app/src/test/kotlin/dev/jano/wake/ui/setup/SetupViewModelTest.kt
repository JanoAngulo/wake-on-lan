package dev.jano.wake.ui.setup

import dev.jano.wake.data.DeviceRepository
import dev.jano.wake.data.InMemoryPreferences
import dev.jano.wake.data.Secrets
import dev.jano.wake.main.AgentApiFactory
import dev.jano.wake.main.AppGraph
import dev.jano.wake.main.Discoverer
import dev.jano.wake.main.HostProber
import dev.jano.wake.main.Pairer
import dev.jano.wake.main.PhoneNetReader
import dev.jano.wake.main.Waker
import dev.jano.wake.net.PairingError
import dev.jano.wake.net.PairingFailed
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.net.ProbeResult
import dev.jano.wake.protocol.DiscoveryReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = DeviceRepository(InMemoryPreferences())
    private val stored = mutableMapOf<String, ByteArray>()
    private var pairResult: () -> ByteArray = { ByteArray(32) { 9 } }
    private val reply = DiscoveryReply("MY-PC", "192.168.1.14", "0A:1B:2C:3D:4E:5F", 47613, "1.0")

    private val graph = AppGraph(
        repository = repo,
        secrets = object : Secrets {
            override fun put(deviceId: String, secret: ByteArray) { stored[deviceId] = secret }
            override fun get(deviceId: String) = stored[deviceId]
            override fun remove(deviceId: String) { stored.remove(deviceId) }
        },
        prober = HostProber { _, _ -> ProbeResult.Down },
        agents = AgentApiFactory { _, _ -> error("unused") },
        discoverer = Discoverer { listOf(reply) },
        phoneNet = PhoneNetReader { PhoneNet(true, "192.168.1.23", 24) },
        waker = Waker { _, _, _ -> },
        pairer = Pairer { _, _ -> pairResult() },
        unlockPasswords = dev.jano.wake.data.FakeUnlockPasswords(),
    )
    private lateinit var vm: SetupViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = SetupViewModel(graph)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun manualRejectsBadMacWithCopy() {
        val errors = vm.saveManual("PC", "0A:1B:2C", "192.168.1.255", "9", "")
        assertEquals(listOf(FieldError(Field.MAC, "Enter a MAC like 0A:1B:2C:3D:4E:5F")), errors)
    }

    @Test
    fun manualRejectsBadPortAndAddress() {
        val errors = vm.saveManual("PC", "0A:1B:2C:3D:4E:5F", "192.168.100", "70000", "abc")
        assertEquals(
            listOf(
                FieldError(Field.BROADCAST, "Enter an IPv4 address"),
                FieldError(Field.PORT, "Port must be 1–65535"),
                FieldError(Field.IP, "Enter an IPv4 address"),
            ),
            errors,
        )
    }

    @Test
    fun manualNormalizesMacAndSaves() = runTest(dispatcher) {
        assertEquals(emptyList(), vm.saveManual("Desk PC", "0a-1b-2c-3d-4e-5f", "192.168.1.255", "9", ""))
        advanceUntilIdle()
        val device = repo.store.first().devices.single()
        assertEquals("0A:1B:2C:3D:4E:5F", device.mac)
        assertEquals("Desk PC", device.name)
        assertEquals(false, device.paired)
    }

    @Test
    fun findListsAgents() = runTest(dispatcher) {
        vm.find()
        advanceUntilIdle()
        assertEquals(listOf(reply), vm.state.value.found)
        assertTrue(vm.state.value.searched)
    }

    @Test
    fun pairSuccessStoresSecretAndMarksPaired() = runTest(dispatcher) {
        vm.pair(reply, "123456")
        advanceUntilIdle()
        val device = repo.store.first().devices.single()
        assertTrue(device.paired)
        assertEquals("192.168.1.14", device.lastIp)
        assertEquals("192.168.1.0/24", device.subnet)
        assertEquals("192.168.1.255", device.broadcast)
        assertContentEquals(ByteArray(32) { 9 }, stored[device.id])
        assertTrue(vm.state.value.paired)
    }

    @Test
    fun repairingSameMacKeepsOneDevice() = runTest(dispatcher) {
        vm.pair(reply, "123456")
        advanceUntilIdle()
        vm.pair(reply.copy(ip = "192.168.1.20"), "654321")
        advanceUntilIdle()
        val devices = repo.store.first().devices
        assertEquals(1, devices.size)
        assertEquals("192.168.1.20", devices.single().lastIp)
    }

    @Test
    fun pairBadCodeShowsCopy() = runTest(dispatcher) {
        pairResult = { throw PairingFailed(PairingError.BAD_CODE) }
        vm.pair(reply, "000000")
        advanceUntilIdle()
        assertEquals("That code didn't match. Start pairing again on the PC.", vm.state.value.pairError)
        assertEquals(emptyList(), repo.store.first().devices)
    }
}
