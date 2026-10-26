package dev.jano.wake.ui.main

import dev.jano.wake.main.MainUiState
import dev.jano.wake.main.UiMessage
import dev.jano.wake.status.PcState
import dev.jano.wake.status.StopKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Words shown for each state (spec §6). Kept out of composables so the copy lives in one place. */
object Presentation {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun time(ms: Long): String = clock.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun uptime(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    fun caption(state: MainUiState, nowMs: Long): String {
        val paired = state.device?.paired == true
        return when (val pc = state.pc) {
            is PcState.Off -> "Tap to wake"
            is PcState.Waking -> "Waking up… ${((nowMs - pc.startedMs) / 1000).coerceAtLeast(0)} s"
            is PcState.On -> when {
                pc.agent && pc.uptimeSec != null -> "On for ${uptime(pc.uptimeSec)}"
                paired -> "On, waiting for agent"
                else -> "On"
            }
            is PcState.Stopping -> when (pc.kind) {
                StopKind.SHUTDOWN -> "Shutting down…"
                StopKind.REBOOT -> "Restarting…"
                StopKind.SLEEP -> "Going to sleep…"
            }
            is PcState.DidntWake -> "No answer after 90 s"
            is PcState.Away -> "Away from home"
        }
    }

    fun heroDescription(state: MainUiState): String {
        val name = state.device?.name ?: "PC"
        return when (state.pc) {
            is PcState.Off, is PcState.DidntWake -> "Wake $name"
            is PcState.Waking -> "$name status: waking up"
            is PcState.On -> "$name status: on"
            is PcState.Stopping -> "$name status: shutting down"
            is PcState.Away -> "$name status: unknown, away from home"
        }
    }

    fun commandsEnabled(state: MainUiState): Boolean =
        (state.pc as? PcState.On)?.agent == true && state.message !is UiMessage.Undo

    fun shutdownNote(state: MainUiState): String = when (val pc = state.pc) {
        is PcState.Off, is PcState.DidntWake -> "PC is off"
        is PcState.Waking -> "Not yet"
        is PcState.Stopping -> "Sent"
        is PcState.Away -> "Unavailable"
        is PcState.On -> when {
            state.message is UiMessage.Undo -> "Sent"
            state.device?.paired != true -> "Pair to use"
            !pc.agent -> "Needs agent"
            else -> "Hold 1 s"
        }
    }

    fun undoText(kind: StopKind, secondsLeft: Long): String = when (kind) {
        StopKind.SHUTDOWN -> "Shutting down in $secondsLeft s"
        StopKind.REBOOT -> "Restarting in $secondsLeft s"
        StopKind.SLEEP -> "Going to sleep"
    }
}
