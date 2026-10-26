package dev.jano.wake.status

enum class StopKind { SHUTDOWN, REBOOT, SLEEP }

/** What the app believes about the PC. Every transition is driven by evidence (probes, agent replies). */
sealed interface PcState {
    data class Off(val sinceMs: Long?) : PcState
    data class Waking(val startedMs: Long) : PcState
    data class On(val uptimeSec: Long?, val latencyMs: Long, val steady: Boolean, val agent: Boolean) : PcState
    data class Stopping(val kind: StopKind, val startedMs: Long) : PcState
    data class DidntWake(val sinceMs: Long) : PcState

    /** Phone is off the home network; [resume] is restored when it returns. */
    data class Away(val resume: PcState?) : PcState
}
