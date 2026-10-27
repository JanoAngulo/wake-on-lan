package dev.jano.wake.main

import dev.jano.wake.data.Device
import dev.jano.wake.data.DeviceRepository
import dev.jano.wake.data.FakeUnlockPasswords
import dev.jano.wake.data.InMemoryPreferences
import dev.jano.wake.data.Secrets
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.net.ProbeResult
import dev.jano.wake.protocol.Action
import dev.jano.wake.protocol.CancelResponse
import dev.jano.wake.protocol.CommandResponse
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.protocol.MacAddress
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.UnlockResult
import dev.jano.wake.status.PcState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repo = DeviceRepository(InMemoryPreferences())
    private val pc = Device(id = "d1", name = "MY-PC", mac = "0A:1B:2C:3D:4E:5F", lastIp = "192.168.1.14",
        subnet = "192.168.1.0/24", paired = true)

    private var hostUp = true
    private var agentUp = true
    private var cancelResult = true
    private var discoveryIp = "192.168.1.14"
    private val sentWakes = mutableListOf<MacAddress>()
    private val commands = mutableListOf<Action>()
    private var polls = 0
    private var statusCalls = 0
    private var discoveries = 0
    private var discoveryDelayMs = 0L
    private var agentMac = "0A:1B:2C:3D:4E:5F"
    private var lockScreen: Boolean? = null
    private var unlockCalls = 0
    private val sentPasswords = mutableListOf<ByteArray>()
    private var lastSentPassword: ByteArray? = null
    private var unlockResult = UnlockResult.UNLOCKED
    private var unlockError: Exception? = null
    private var unlockGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private val agentApi = object : AgentApi {
        override suspend fun status(): StatusResponse {
            statusCalls++
            if (!agentUp) throw java.io.IOException("down")
            return StatusResponse("MY-PC", "192.168.1.14", agentMac, 60, "1.0", 0, lockScreen = lockScreen)
        }
        override suspend fun command(action: Action): CommandResponse {
            commands += action
            return if (action == Action.SHUTDOWN || action == Action.REBOOT) CommandResponse("c1", 3_000) else CommandResponse()
        }
        override suspend fun cancel(id: String) = CancelResponse(cancelResult)
        override suspend fun unlock(password: ByteArray): UnlockResponse {
            unlockCalls++
            sentPasswords += password.copyOf()
            lastSentPassword = password
            unlockGate?.await()
            unlockError?.let { throw it }
            return UnlockResponse(unlockResult)
        }
    }
    private val passwords = FakeUnlockPasswords()

    private val graph = AppGraph(
        repository = repo,
        secrets = object : Secrets {
            override fun put(deviceId: String, secret: ByteArray) = Unit
            override fun get(deviceId: String) = ByteArray(32)
            override fun remove(deviceId: String) = Unit
        },
        prober = HostProber { _, _ -> polls++; if (hostUp) ProbeResult.Up(2) else ProbeResult.Down },
        agents = AgentApiFactory { _, _ -> agentApi },
        discoverer = Discoverer {
            discoveries++
            kotlinx.coroutines.delay(discoveryDelayMs)
            listOf(DiscoveryReply("MY-PC", discoveryIp, pc.mac, 47613, "1.0"))
        },
        phoneNet = PhoneNetReader { PhoneNet(true, "192.168.1.23", 24) },
        waker = Waker { mac, _, _ -> sentWakes += mac },
        pairer = Pairer { _, _ -> ByteArray(32) },
        unlockPasswords = passwords,
    )

    private lateinit var vm: MainViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = MainViewModel(graph, clock = { scope.testScheduler.currentTime })
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun seed() = repo.upsertDevice(pc)

    @Test
    fun wakeSendsPacketAndEntersWaking() = scope.runTest {
        hostUp = false
        seed()
        vm.wake()
        advanceUntilIdle()
        assertEquals(listOf(MacAddress("0A:1B:2C:3D:4E:5F")), sentWakes)
        assertIs<PcState.Waking>(vm.ui.value.pc)
    }

    @Test
    fun discoveryReplyWithNewIpUpdatesDevice() = scope.runTest {
        seed()
        hostUp = false
        agentUp = false
        discoveryIp = "192.168.1.20"
        repeat(3) { vm.pollOnce() }
        advanceUntilIdle()
        assertEquals("192.168.1.20", repo.store.first().devices.single().lastIp)
    }

    @Test
    fun movedPcRecoversWithoutFlashingOff() = scope.runTest {
        seed()
        vm.pollOnce()
        hostUp = false
        agentUp = false
        discoveryIp = "192.168.1.20"
        repeat(2) { vm.pollOnce() }
        advanceUntilIdle()
        assertIs<PcState.On>(vm.ui.value.pc)
        assertEquals("192.168.1.20", repo.store.first().devices.single().lastIp)

        hostUp = true
        agentUp = true
        vm.pollOnce()
        runCurrent()
        assertEquals(true, (vm.ui.value.pc as PcState.On).agent)
    }

    @Test
    fun slowDiscoveryDoesNotBlockPolling() = scope.runTest {
        seed()
        hostUp = false
        agentUp = false
        discoveryDelayMs = 5_000
        val start = currentTime
        repeat(6) { vm.pollOnce() }
        assertEquals(start, currentTime)
        advanceUntilIdle()
        assertEquals(1, discoveries)
    }

    @Test
    fun statusSkippedWhenHostDown() = scope.runTest {
        seed()
        hostUp = false
        repeat(3) { vm.pollOnce() }
        assertEquals(0, statusCalls)
    }

    @Test
    fun upWithoutAgentTriggersRediscoveryOnce() = scope.runTest {
        seed()
        agentUp = false
        repeat(6) { vm.pollOnce() }
        advanceUntilIdle()
        assertEquals(1, discoveries)
    }

    @Test
    fun statusFromAnotherMacIsIgnored() = scope.runTest {
        seed()
        agentMac = "00:00:00:00:00:99"
        vm.pollOnce()
        runCurrent()
        assertEquals(false, (vm.ui.value.pc as PcState.On).agent)
    }

    @Test
    fun undoAfterFireReportsTooLate() = scope.runTest {
        seed()
        vm.pollOnce()
        runCurrent()
        assertIs<PcState.On>(vm.ui.value.pc)
        vm.shutdown()
        advanceTimeBy(100)
        assertIs<UiMessage.Undo>(vm.ui.value.message)
        cancelResult = false
        vm.undo()
        advanceTimeBy(100)
        assertEquals(UiMessage.TooLateToUndo, vm.ui.value.message)
    }

    @Test
    fun undoInTimeKeepsPcOn() = scope.runTest {
        seed()
        vm.pollOnce()
        runCurrent()
        vm.shutdown()
        advanceTimeBy(100)
        vm.undo()
        advanceUntilIdle()
        assertIs<PcState.On>(vm.ui.value.pc)
        assertEquals(UiMessage.Info("Cancelled"), vm.ui.value.message)
    }

    @Test
    fun undoWindowEndsInStopping() = scope.runTest {
        seed()
        vm.pollOnce()
        runCurrent()
        vm.shutdown()
        advanceTimeBy(3_100)
        assertIs<PcState.Stopping>(vm.ui.value.pc)
    }

    @Test
    fun pollingStopsWhenScopeCancelled() = scope.runTest {
        seed()
        val job = Job()
        launch(job) { vm.runPolling() }
        advanceTimeBy(5_000)
        val seen = polls
        assertTrue(seen >= 2, "polled $seen times")
        job.cancel()
        advanceTimeBy(10_000)
        assertEquals(seen, polls)
    }

    @Test
    fun wakeAllSendsEveryDeviceInFolder() = scope.runTest {
        repo.upsertDevice(pc.copy(folderId = "f1"))
        repo.upsertDevice(Device(id = "d2", name = "NAS", mac = "00:00:00:00:00:02", folderId = "f1"))
        repo.upsertDevice(Device(id = "d3", name = "Laptop", mac = "00:00:00:00:00:03"))
        vm.wakeAll("f1")
        advanceUntilIdle()
        assertEquals(listOf("0A:1B:2C:3D:4E:5F", "00:00:00:00:00:02"), sentWakes.map { it.value })
    }

    @Test
    fun commandsIgnoredUnlessOnWithAgent() = scope.runTest {
        seed()
        vm.shutdown()
        vm.lock()
        advanceUntilIdle()
        assertEquals(emptyList(), commands)

        agentUp = false
        vm.pollOnce()
        runCurrent()
        vm.shutdown()
        advanceUntilIdle()
        assertEquals(emptyList(), commands)
    }
}
