package dev.jano.wake.main

import dev.jano.wake.data.Device
import dev.jano.wake.data.DeviceRepository
import dev.jano.wake.data.FakeUnlockPasswords
import dev.jano.wake.data.InMemoryPreferences
import dev.jano.wake.data.Secrets
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.net.ProbeResult
import dev.jano.wake.protocol.Action
import dev.jano.wake.protocol.SignInStatus
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
import dev.jano.wake.data.Authenticator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private var probedPorts: List<Int> = emptyList()
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

    // The pre-login sign-in program: closed (unreachable) unless signInUp.
    private var signInUp = false
    private var signInScreen = true
    private var signInMac = "0A:1B:2C:3D:4E:5F"
    private var signInStatusCalls = 0
    private var signInUnlockCalls = 0
    private var signInUnlockResult = UnlockResult.UNLOCKED
    private var signInUnlockError: Exception? = null
    private val signInApi = object : SignInApi {
        override suspend fun status(): SignInStatus {
            signInStatusCalls++
            if (!signInUp) throw java.io.IOException("closed")
            return SignInStatus(signInMac, 0, signInScreen)
        }
        override suspend fun unlock(password: ByteArray): UnlockResponse {
            signInUnlockCalls++
            sentPasswords += password.copyOf()
            signInUnlockError?.let { throw it }
            return UnlockResponse(signInUnlockResult)
        }
    }

    private val graph = AppGraph(
        repository = repo,
        secrets = object : Secrets {
            override fun put(deviceId: String, secret: ByteArray) = Unit
            override fun get(deviceId: String) = ByteArray(32)
            override fun remove(deviceId: String) = Unit
        },
        prober = HostProber { _, ports -> polls++; probedPorts = ports; if (hostUp) ProbeResult.Up(2) else ProbeResult.Down },
        agents = AgentApiFactory { _, _ -> agentApi },
        signIns = SignInApiFactory { _, _ -> signInApi },
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

    private var authCalls = 0
    private val approve = Authenticator { cipher -> authCalls++; cipher }
    private val cancel = Authenticator { authCalls++; null }

    private fun savePassword(text: String = "pässwörd") {
        val cipher = passwords.sealCipher("d1")
        passwords.save("d1", cipher, text.toByteArray())
    }

    /** Selected PC on, agent answering, lock screen up. */
    private suspend fun TestScope.lockedPc() {
        lockScreen = true
        seed()
        vm.pollOnce()
        runCurrent()
    }

    /** After a cold boot: the PC answers, the agent doesn't, the sign-in program says the sign-in screen is up. */
    private suspend fun TestScope.atSignIn() {
        agentUp = false
        signInUp = true
        seed()
        vm.pollOnce()
        runCurrent()
    }

    @Test
    fun signInScreenShownWhenAgentSilentAndServiceSaysSo() = scope.runTest {
        atSignIn()
        assertTrue(vm.ui.value.signInScreen)
        assertFalse(vm.ui.value.lockScreen)
    }

    @Test
    fun probeIncludesSignInPort() = scope.runTest {
        // At the sign-in screen the agent isn't running and Windows may block 135; the sign-in port is the one that answers.
        seed()
        vm.pollOnce()
        assertTrue(47615 in probedPorts, "probed $probedPorts")
    }

    @Test
    fun signInReplyFromOtherMacIgnored() = scope.runTest {
        signInMac = "0A:1B:2C:3D:4E:60"
        atSignIn()
        assertFalse(vm.ui.value.signInScreen)
    }

    @Test
    fun signInNotAskedWhenAgentAnswers() = scope.runTest {
        signInUp = true
        lockedPc()
        assertEquals(0, signInStatusCalls)
        assertFalse(vm.ui.value.signInScreen)
    }

    @Test
    fun unlockWithoutAgentGoesToSignInService() = scope.runTest {
        savePassword()
        atSignIn()
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(1, signInUnlockCalls)
        assertEquals(0, unlockCalls)
        assertContentEquals("pässwörd".toByteArray(), sentPasswords.single())
        assertEquals(UiMessage.Info("MY-PC signed in"), vm.ui.value.message)
        assertFalse(vm.ui.value.signInScreen)
    }

    @Test
    fun unlockPrefersAgentWhenAgentAnswers() = scope.runTest {
        savePassword()
        signInUp = true
        lockedPc()
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(1, unlockCalls)
        assertEquals(0, signInUnlockCalls)
    }

    @Test
    fun wrongPasswordViaSignInDeletesSavedPassword() = scope.runTest {
        savePassword()
        atSignIn()
        signInUnlockResult = UnlockResult.WRONG_PASSWORD
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(false, passwords.saved("d1"))
        assertEquals(
            UiMessage.OpenUnlockSetup("d1", "MY-PC didn't accept the saved password. Enter it again."),
            vm.ui.value.message,
        )
    }

    @Test
    fun signInUnreachableShowsMessageAndDoesNotRetry() = scope.runTest {
        savePassword()
        atSignIn()
        signInUnlockError = java.io.IOException("down")
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(1, signInUnlockCalls)
        assertEquals(UiMessage.Info("Couldn't reach the agent on the PC."), vm.ui.value.message)
    }

    @Test
    fun unlockSendsSavedPassword() = scope.runTest {
        savePassword()
        lockedPc()
        vm.unlock(approve)
        advanceUntilIdle()
        assertContentEquals("pässwörd".toByteArray(), sentPasswords.single())
        assertEquals(UiMessage.Info("MY-PC unlocked"), vm.ui.value.message)
        assertEquals(false, vm.ui.value.lockScreen)
    }

    @Test
    fun cancelledFingerprintSendsNothing() = scope.runTest {
        savePassword()
        lockedPc()
        vm.unlock(cancel)
        advanceUntilIdle()
        assertEquals(1, authCalls)
        assertEquals(0, unlockCalls)
        assertEquals(null, vm.ui.value.message)
    }

    @Test
    fun unlockWithNothingSavedOpensSetup() = scope.runTest {
        lockedPc()
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(UiMessage.OpenUnlockSetup("d1"), vm.ui.value.message)
        assertEquals(0, authCalls)
        assertEquals(0, unlockCalls)
    }

    @Test
    fun wrongPasswordClearsSavedPasswordAndOpensSetup() = scope.runTest {
        savePassword()
        lockedPc()
        unlockResult = UnlockResult.WRONG_PASSWORD
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(false, passwords.saved("d1"))
        assertEquals(
            UiMessage.OpenUnlockSetup("d1", "MY-PC didn't accept the saved password. Enter it again."),
            vm.ui.value.message,
        )
        assertEquals(1, unlockCalls)
    }

    @Test
    fun invalidatedKeyAsksForPasswordAgain() = scope.runTest {
        savePassword()
        passwords.invalidated += "d1"
        lockedPc()
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(
            UiMessage.OpenUnlockSetup("d1", "Fingerprints on this phone changed. Enter the PC password again."),
            vm.ui.value.message,
        )
        assertEquals(0, authCalls)
        assertEquals(0, unlockCalls)
    }

    @Test
    fun unlockWhenAgentUnreachableShowsMessageAndDoesNotRetry() = scope.runTest {
        savePassword()
        lockedPc()
        unlockError = java.io.IOException("down")
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(1, unlockCalls)
        assertEquals(UiMessage.Info("Couldn't reach the agent on the PC."), vm.ui.value.message)
    }

    @Test
    fun notLockedResultRefreshesQuietly() = scope.runTest {
        savePassword()
        lockedPc()
        unlockResult = UnlockResult.NOT_LOCKED
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(null, vm.ui.value.message)
        assertEquals(false, vm.ui.value.lockScreen)
    }

    @Test
    fun failureResultsShowMessages() = scope.runTest {
        savePassword()
        lockedPc()
        val expected = mapOf(
            UnlockResult.NO_MODULE to "Fingerprint unlock isn't installed on MY-PC. Run Install Wake Agent again on the PC.",
            UnlockResult.TIMEOUT to "MY-PC didn't answer in time.",
            UnlockResult.FAILED to "Couldn't unlock MY-PC.",
        )
        for ((result, text) in expected) {
            unlockResult = result
            vm.unlock(approve)
            advanceUntilIdle()
            assertEquals(UiMessage.Info(text), vm.ui.value.message)
            assertEquals(true, passwords.saved("d1"))
        }
    }

    @Test
    fun secondTapWhileUnlockingIsIgnored() = scope.runTest {
        savePassword()
        lockedPc()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        unlockGate = gate
        vm.unlock(approve)
        runCurrent()
        assertTrue(vm.ui.value.unlocking)
        vm.unlock(approve)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, authCalls)
        assertEquals(1, unlockCalls)
        assertEquals(false, vm.ui.value.unlocking)
    }

    @Test
    fun keystoreErrorOnOpenShowsMessageAndSendsNothing() = scope.runTest {
        savePassword()
        passwords.keystoreBroken = true
        lockedPc()
        vm.unlock(approve)
        advanceUntilIdle()
        assertEquals(UiMessage.Info("Couldn't unlock MY-PC."), vm.ui.value.message)
        assertEquals(0, unlockCalls)
        assertEquals(false, vm.ui.value.unlocking)
    }

    @Test
    fun passwordZeroedAfterSend() = scope.runTest {
        savePassword()
        lockedPc()
        vm.unlock(approve)
        advanceUntilIdle()
        assertTrue(lastSentPassword!!.all { it == 0.toByte() })
    }

    @Test
    fun lockScreenOnlyWhenAgentSaysTrue() = scope.runTest {
        seed()
        for ((reported, shown) in listOf(null to false, false to false, true to true)) {
            lockScreen = reported
            vm.pollOnce()
            runCurrent()
            assertEquals(shown, vm.ui.value.lockScreen, "agent said $reported")
        }
        agentUp = false
        hostUp = false
        repeat(3) { vm.pollOnce() }
        runCurrent()
        assertEquals(false, vm.ui.value.lockScreen)
    }
}
