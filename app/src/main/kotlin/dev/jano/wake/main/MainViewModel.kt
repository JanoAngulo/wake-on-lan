package dev.jano.wake.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jano.wake.data.Device
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.net.ProbeResult
import dev.jano.wake.net.isHome
import dev.jano.wake.net.subnetOf
import dev.jano.wake.protocol.Action
import dev.jano.wake.protocol.PendingCommand
import dev.jano.wake.protocol.parseMac
import dev.jano.wake.status.PcState
import dev.jano.wake.status.StatusMachine
import dev.jano.wake.status.StopKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val pending = MutableStateFlow<PendingCommand?>(null)
    private val message = MutableStateFlow<UiMessage?>(null)
    private var downs = 0
    private var undoJob: Job? = null

    val ui: StateFlow<MainUiState> = combine(graph.repository.store, machine.state, pending, phone, message) { store, pc, pend, net, msg ->
        val device = store.selected
        MainUiState(
            device = device,
            deviceCount = store.devices.size,
            pc = pc,
            pending = pend,
            phone = net,
            home = isHome(net, device?.subnet),
            haptics = store.settings.haptics,
            message = msg,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        MainUiState(null, 0, machine.state.value, null, phone.value, false, true, null),
    )

    fun startPolling(scope: CoroutineScope): Job = scope.launch { runPolling() }

    suspend fun runPolling() {
        while (true) {
            pollOnce()
            delay(graph.repository.store.first().settings.pollMs)
        }
    }

    suspend fun pollOnce() {
        val device = graph.repository.store.first().selected ?: return
        val net = graph.phoneNet.read()
        phone.value = net
        val home = isHome(net, device.subnet)
        machine.onNetwork(home)
        if (!home) return machine.tick()

        val ip = device.lastIp
        val secret = if (device.paired) graph.secrets.get(device.id) else null
        val (host, status) = coroutineScope {
            val host = async { ip?.let { graph.prober.probe(it, listOf(device.agentPort, WINDOWS_RPC_PORT)) } ?: ProbeResult.Down }
            val status = async {
                if (ip == null || secret == null) null
                else runCatching { graph.agents.create(device, secret).status() }.getOrNull()
            }
            host.await() to status.await()
        }

        if (status != null && status.ip != ip) graph.repository.updateIp(device.id, status.ip)
        machine.onProbe(host, status)
        pending.value = status?.pending

        downs = if (host is ProbeResult.Down) downs + 1 else 0
        if (downs >= 3 || ip == null) rediscover(device)
        machine.tick()
    }

    /** The PC may have a new DHCP address; ask the agent where it is now. */
    private suspend fun rediscover(device: Device) {
        val reply = runCatching { graph.discoverer.discover(device.broadcast) }.getOrNull()
            ?.firstOrNull { parseMac(it.mac) == parseMac(device.mac) } ?: return
        if (reply.ip != device.lastIp) graph.repository.updateIp(device.id, reply.ip)
        downs = 0
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

    fun shutdown() = stop(Action.SHUTDOWN, StopKind.SHUTDOWN)

    fun reboot() = stop(Action.REBOOT, StopKind.REBOOT)

    fun sleep() = command(Action.SLEEP) { device ->
        machine.onStopSent(StopKind.SLEEP)
        "Sleep sent to ${device.name}"
    }

    fun lock() = command(Action.LOCK) { device -> "${device.name} locked" }

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
