package dev.jano.wake.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jano.wake.data.Authenticator
import dev.jano.wake.data.Device
import dev.jano.wake.data.OpenResult
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.net.ProbeResult
import dev.jano.wake.net.isHome
import dev.jano.wake.net.subnetOf
import dev.jano.wake.protocol.Action
import dev.jano.wake.protocol.Ports
import dev.jano.wake.protocol.SignInStatus
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.UnlockResult
import dev.jano.wake.protocol.parseMac
import dev.jano.wake.status.PcState
import dev.jano.wake.status.StatusMachine
import dev.jano.wake.status.StopKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val UNDO_MS = 3_000L
private const val WINDOWS_RPC_PORT = 135

class MainViewModel(
    private val graph: AppGraph,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val machine = StatusMachine(clock)
    private val phone = MutableStateFlow(PhoneNet(false, null, null))
    private val agentStatus = MutableStateFlow<StatusResponse?>(null)
    private val signInStatus = MutableStateFlow<SignInStatus?>(null)
    private val message = MutableStateFlow<UiMessage?>(null)
    private val unlocking = MutableStateFlow(false)
    private var downs = 0
    private var agentMisses = 0
    private var pollCount = 0
    private var discovering = false
    private var undoJob: Job? = null

    val ui: StateFlow<MainUiState> = combine(
        combine(graph.repository.store, machine.state, agentStatus, phone, message) { store, pc, status, net, msg ->
            val device = store.selected
            MainUiState(
                device = device,
                deviceCount = store.devices.size,
                pc = pc,
                agentStatus = status,
                phone = net,
                home = isHome(net, device?.subnet),
                haptics = store.settings.haptics,
                message = msg,
                lockScreen = pc is PcState.On && status?.lockScreen == true,
                unlockSaved = device != null && graph.unlockPasswords.saved(device.id),
            )
        },
        unlocking,
        signInStatus,
    ) { state, busy, signIn ->
        val noAgent = (state.pc as? PcState.On)?.agent == false
        state.copy(unlocking = busy, signInScreen = noAgent && signIn?.signInScreen == true)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        MainUiState(null, 0, machine.state.value, null, phone.value, false, true, null),
    )

    fun startPolling(scope: CoroutineScope): Job = scope.launch { runPolling() }

    suspend fun runPolling() {
        machine.onPollingStarted()
        while (true) {
            pollOnce()
            delay(graph.repository.store.first().settings.pollMs)
        }
    }

    suspend fun pollOnce() {
        pollCount++
        val device = graph.repository.store.first().selected ?: return
        val net = graph.phoneNet.read()
        phone.value = net
        val home = isHome(net, device.subnet)
        machine.onNetwork(home)
        if (!home) return machine.tick()

        val ip = device.lastIp
        val secret = if (device.paired) graph.secrets.get(device.id) else null
        val host = ip?.let { graph.prober.probe(it, listOf(device.agentPort, WINDOWS_RPC_PORT, Ports.SIGNIN)) } ?: ProbeResult.Down
        // Ask the agent only when the PC answers at all, and only trust a reply from this PC's MAC.
        val status = if (host is ProbeResult.Up && ip != null && secret != null) {
            runCatching { graph.agents.create(device, secret).status() }.getOrNull()
                ?.takeIf { parseMac(it.mac) == parseMac(device.mac) }
        } else null

        // No agent before anyone signs in after a cold boot: ask the sign-in program instead, same MAC rule.
        signInStatus.value = if (host is ProbeResult.Up && ip != null && secret != null && status == null) {
            runCatching { graph.signIns.create(device, secret).status() }.getOrNull()
                ?.takeIf { parseMac(it.mac) == parseMac(device.mac) }
        } else null

        if (status != null && status.ip != ip) graph.repository.updateIp(device.id, status.ip)
        machine.onProbe(host, status)
        agentStatus.value = status

        downs = if (host is ProbeResult.Down) downs + 1 else 0
        agentMisses = if (host is ProbeResult.Up && device.paired && status == null) agentMisses + 1 else 0
        // Once per streak: before the 3rd miss would say Off, or when something else answers at that IP.
        if (downs == 2 || agentMisses == 3 || (ip == null && pollCount % 5 == 1)) rediscover(device)
        machine.tick()
    }

    /** The PC may have a new DHCP address; ask the agent where it is now, without holding up polling. */
    private fun rediscover(device: Device) {
        if (discovering) return
        discovering = true
        viewModelScope.launch {
            try {
                val reply = runCatching { graph.discoverer.discover(device.broadcast) }.getOrNull()
                    ?.firstOrNull { parseMac(it.mac) == parseMac(device.mac) }
                if (reply != null && reply.ip != device.lastIp) graph.repository.updateIp(device.id, reply.ip)
            } finally {
                discovering = false
            }
        }
    }

    fun wake() {
        viewModelScope.launch {
            val device = graph.repository.store.first().selected ?: return@launch
            val mac = parseMac(device.mac) ?: return@launch
            runCatching { graph.waker.send(mac, device.broadcast, device.port) }
                .onSuccess { machine.onWakeSent() }
                .onFailure { message.value = UiMessage.Info("Couldn't send the wake signal. Check you're on home Wi-Fi.") }
        }
    }

    /** Wake every device in a folder (Wake all). Status tracking stays on the selected device. */
    fun wakeAll(folderId: String) {
        viewModelScope.launch {
            val store = graph.repository.store.first()
            store.devices.filter { it.folderId == folderId }.forEach { device ->
                val mac = parseMac(device.mac) ?: return@forEach
                runCatching { graph.waker.send(mac, device.broadcast, device.port) }
                if (device.id == store.selected?.id) machine.onWakeSent()
            }
            message.value = UiMessage.Info("Wake signal sent to ${store.folders.firstOrNull { it.id == folderId }?.name ?: "folder"}")
        }
    }

    fun select(deviceId: String) {
        viewModelScope.launch { graph.repository.select(deviceId) }
    }

    fun shutdown() = stop(Action.SHUTDOWN, StopKind.SHUTDOWN)

    fun reboot() = stop(Action.REBOOT, StopKind.REBOOT)

    fun sleep() = command(Action.SLEEP) { device ->
        machine.onStopSent(StopKind.SLEEP)
        "Sleep sent to ${device.name}"
    }

    fun lock() = command(Action.LOCK) { device -> "${device.name} locked" }

    /** Fingerprint, then one /unlock. Never retried: a wrong password must not count twice toward lockout. */
    fun unlock(auth: Authenticator) {
        if (!(agentReady() || signInReady()) || unlocking.value) return
        unlocking.value = true
        viewModelScope.launch {
            try {
                runUnlock(auth)
            } finally {
                unlocking.value = false
            }
        }
    }

    private suspend fun runUnlock(auth: Authenticator) {
        val device = graph.repository.store.first().selected ?: return
        // The agent when it's there (lock screen); otherwise the sign-in program (sign-in screen after a cold boot).
        val viaAgent = agentReady()
        val send: suspend (ByteArray) -> UnlockResponse =
            if (viaAgent) (api() ?: return)::unlock else (signInApi() ?: return)::unlock
        val passwords = graph.unlockPasswords
        val opened = runCatching { passwords.openCipher(device.id) }
            .getOrElse { return run { message.value = UiMessage.Info("Couldn't unlock ${device.name}.") } }
        val cipher = when (opened) {
            OpenResult.NotSaved -> return run { message.value = UiMessage.OpenUnlockSetup(device.id) }
            OpenResult.Invalidated -> return run {
                message.value = UiMessage.OpenUnlockSetup(device.id, "Fingerprints on this phone changed. Enter the PC password again.")
            }
            is OpenResult.Ready -> auth.authenticate(opened.cipher) ?: return
        }
        val password = runCatching { passwords.open(cipher, device.id) }
            .getOrElse { return run { message.value = UiMessage.Info("Couldn't unlock ${device.name}.") } }
        val response = try {
            send(password)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return unreachable()
        } finally {
            password.fill(0)
        }
        message.value = when (response.result) {
            UnlockResult.UNLOCKED -> UiMessage.Info(if (viaAgent) "${device.name} unlocked" else "${device.name} signed in")
            UnlockResult.WRONG_PASSWORD -> {
                passwords.remove(device.id)
                UiMessage.OpenUnlockSetup(device.id, "${device.name} didn't accept the saved password. Enter it again.")
            }
            UnlockResult.NOT_LOCKED -> null
            UnlockResult.NO_MODULE ->
                UiMessage.Info("Fingerprint unlock isn't installed on ${device.name}. Run Install Wake Agent again on the PC.")
            UnlockResult.TIMEOUT -> UiMessage.Info("${device.name} didn't answer in time.")
            UnlockResult.FAILED -> UiMessage.Info("Couldn't unlock ${device.name}.")
        }
        // Windows' own answer says the lock screen is gone; don't wait for the next poll to show it.
        if (response.result == UnlockResult.UNLOCKED || response.result == UnlockResult.NOT_LOCKED) {
            if (viaAgent) agentStatus.value = agentStatus.value?.copy(lockScreen = false) else signInStatus.value = null
        }
    }

    fun undo() {
        val current = message.value as? UiMessage.Undo ?: return
        undoJob?.cancel()
        viewModelScope.launch {
            val api = api() ?: return@launch
            val cancelled = runCatching { api.cancel(current.id).cancelled }.getOrDefault(false)
            if (cancelled) {
                message.value = UiMessage.Info("Cancelled")
            } else {
                message.value = UiMessage.TooLateToUndo
                machine.onStopSent(current.kind)
            }
        }
    }

    fun clearMessage() {
        if (message.value !is UiMessage.Undo) message.value = null
    }

    private fun stop(action: Action, kind: StopKind) {
        if (!agentReady()) return
        viewModelScope.launch {
            val api = api() ?: return@launch
            val response = runCatching { api.command(action) }.getOrElse { return@launch unreachable() }
            val id = response.id ?: return@launch
            message.value = UiMessage.Undo(id, kind, clock() + UNDO_MS)
            undoJob = launch {
                delay(UNDO_MS)
                if ((message.value as? UiMessage.Undo)?.id == id) message.value = null
                machine.onStopSent(kind)
            }
        }
    }

    private fun command(action: Action, after: (Device) -> String) {
        if (!agentReady()) return
        viewModelScope.launch {
            val device = graph.repository.store.first().selected ?: return@launch
            val api = api() ?: return@launch
            runCatching { api.command(action) }
                .onSuccess { message.value = UiMessage.Info(after(device)) }
                .onFailure { unreachable() }
        }
    }

    private fun agentReady(): Boolean = (machine.state.value as? PcState.On)?.agent == true

    private fun signInReady(): Boolean =
        (machine.state.value as? PcState.On)?.agent == false && signInStatus.value?.signInScreen == true

    private suspend fun signInApi(): SignInApi? {
        val device = graph.repository.store.first().selected ?: return null
        val secret = graph.secrets.get(device.id) ?: return null
        return graph.signIns.create(device, secret)
    }

    private suspend fun api(): AgentApi? {
        val device = graph.repository.store.first().selected ?: return null
        val secret = graph.secrets.get(device.id) ?: return null
        return graph.agents.create(device, secret)
    }

    private fun unreachable() {
        message.value = UiMessage.Info("Couldn't reach the agent on the PC.")
    }

    /** Remembers the home subnet the first time the device is used on Wi-Fi. */
    suspend fun rememberSubnetIfMissing() {
        val device = graph.repository.store.first().selected ?: return
        val net = graph.phoneNet.read()
        if (device.subnet == null && net.onWifi && net.ip != null && net.prefixLength != null) {
            graph.repository.upsertDevice(device.copy(subnet = subnetOf(net.ip, net.prefixLength)))
        }
    }
}
