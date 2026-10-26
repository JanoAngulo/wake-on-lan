package dev.jano.wake.status

import dev.jano.wake.net.ProbeResult
import dev.jano.wake.protocol.StatusResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StatusMachine(
    private val clock: () -> Long,
    private val wakeTimeoutMs: Long = 90_000,
    private val stopTimeoutMs: Long = 60_000,
    private val missesForOff: Int = 3,
    private val steadyMs: Long = 20,
    private val window: Int = 5,
) {
    private val _state = MutableStateFlow<PcState>(PcState.Checking)
    val state: StateFlow<PcState> = _state.asStateFlow()

    private var misses = 0
    private var lastHostUp = false
    private val rtts = ArrayDeque<Long>()
    private var lastOn = PcState.On(uptimeSec = null, latencyMs = 0, steady = true, agent = false)

    @Synchronized
    fun onWakeSent() {
        when (_state.value) {
            is PcState.Off, is PcState.DidntWake, PcState.Checking -> _state.value = PcState.Waking(clock())
            else -> Unit
        }
    }

    /** Polling (re)starts after the app was away: old On/Off may be hours stale, so check again. */
    @Synchronized
    fun onPollingStarted() {
        when (_state.value) {
            is PcState.On, is PcState.Off, is PcState.DidntWake -> {
                misses = 0
                rtts.clear()
                _state.value = PcState.Checking
            }
            else -> Unit
        }
    }

    @Synchronized
    fun onProbe(host: ProbeResult, agent: StatusResponse?) {
        val current = _state.value
        if (current is PcState.Away) return

        when (host) {
            is ProbeResult.Up -> {
                misses = 0
                lastHostUp = true
                rtts.addLast(host.rttMs)
                while (rtts.size > window) rtts.removeFirst()
                lastOn = PcState.On(agent?.uptimeSeconds, host.rttMs, median() <= steadyMs, agent != null)
                if (current !is PcState.Stopping) _state.value = lastOn
            }
            ProbeResult.Down -> {
                misses++
                lastHostUp = false
                // From Checking there's no recent "on" to protect, so two misses are enough.
                if (current == PcState.Checking && misses >= 2) {
                    _state.value = PcState.Off(null)
                    return
                }
                if (misses < missesForOff) return
                _state.value = when (current) {
                    is PcState.On -> PcState.Off(clock())
                    is PcState.Stopping ->
                        if (current.kind == StopKind.REBOOT) PcState.Waking(clock()) else PcState.Off(clock())
                    else -> current
                }
                if (_state.value !is PcState.On) rtts.clear()
            }
        }
    }

    @Synchronized
    fun onStopSent(kind: StopKind) {
        if (_state.value is PcState.On) {
            misses = 0
            _state.value = PcState.Stopping(kind, clock())
        }
    }

    @Synchronized
    fun onStopCancelled() {
        if (_state.value is PcState.Stopping) _state.value = lastOn
    }

    @Synchronized
    fun onNetwork(home: Boolean) {
        val current = _state.value
        if (!home && current !is PcState.Away) {
            _state.value = PcState.Away(current)
        } else if (home && current is PcState.Away) {
            misses = 0
            _state.value = current.resume ?: PcState.Off(null)
        }
    }

    /** Applies time-based transitions; call on every poll. */
    @Synchronized
    fun tick() {
        val now = clock()
        when (val current = _state.value) {
            is PcState.Waking -> if (now - current.startedMs >= wakeTimeoutMs) _state.value = PcState.DidntWake(now)
            is PcState.Stopping -> if (now - current.startedMs >= stopTimeoutMs && lastHostUp) _state.value = lastOn
            else -> Unit
        }
    }

    private fun median(): Long = rtts.sorted()[rtts.size / 2]
}
