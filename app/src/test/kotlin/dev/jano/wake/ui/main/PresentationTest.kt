package dev.jano.wake.ui.main

import dev.jano.wake.data.Device
import dev.jano.wake.main.MainUiState
import dev.jano.wake.main.UiMessage
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.status.PcState
import dev.jano.wake.status.StopKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationTest {
    private val device = Device("d1", "MY-PC", "0A:1B:2C:3D:4E:5F", paired = true)
    private val on = PcState.On(uptimeSec = 8_040, latencyMs = 2, steady = true, agent = true)
    private fun state(pc: PcState, message: UiMessage? = null, paired: Boolean = true) =
        MainUiState(device.copy(paired = paired), 1, pc, null, PhoneNet(true, "192.168.1.23", 24), true, true, message)

    @Test
    fun captionsMatchSpec() {
        assertEquals("Tap to wake", Presentation.caption(state(PcState.Off(null)), 0))
        assertEquals("Waking up… 18s", Presentation.caption(state(PcState.Waking(1_000)), 19_000))
        assertEquals("On for 2h 14m", Presentation.caption(state(on), 0))
        assertEquals("On, waiting for agent", Presentation.caption(state(on.copy(agent = false, uptimeSec = null)), 0))
        assertEquals("Restarting…", Presentation.caption(state(PcState.Stopping(StopKind.REBOOT, 0)), 0))
        assertEquals("No answer after 90s", Presentation.caption(state(PcState.DidntWake(0)), 0))
        assertEquals("Away from home", Presentation.caption(state(PcState.Away(null)), 0))
    }

    @Test
    fun uptimeUnderAnHourShowsMinutes() {
        assertEquals("7m", Presentation.uptime(420))
        assertEquals("1h 0m", Presentation.uptime(3_600))
    }

    @Test
    fun shutdownNoteExplainsWhyDisabled() {
        assertEquals("PC is off", Presentation.shutdownNote(state(PcState.Off(null))))
        assertEquals("Not yet", Presentation.shutdownNote(state(PcState.Waking(0))))
        assertEquals("Hold 1s", Presentation.shutdownNote(state(on)))
        assertEquals("Sent", Presentation.shutdownNote(state(on, UiMessage.Undo("c1", StopKind.SHUTDOWN, 0))))
        assertEquals("Needs agent", Presentation.shutdownNote(state(on.copy(agent = false))))
        assertEquals("Pair to use", Presentation.shutdownNote(state(on.copy(agent = false), paired = false)))
        assertEquals("Unavailable", Presentation.shutdownNote(state(PcState.Away(null))))
    }

    @Test
    fun commandsOnlyWhenOnWithAgentAndNoUndoPending() {
        assertTrue(Presentation.commandsEnabled(state(on)))
        assertFalse(Presentation.commandsEnabled(state(on.copy(agent = false))))
        assertFalse(Presentation.commandsEnabled(state(on, UiMessage.Undo("c1", StopKind.SHUTDOWN, 0))))
        assertFalse(Presentation.commandsEnabled(state(PcState.Off(null))))
    }

    @Test
    fun undoCountdownUsesCompactSeconds() {
        assertEquals("Shutting down in 3s", Presentation.undoText(StopKind.SHUTDOWN, 3))
        assertEquals("Restarting in 2s", Presentation.undoText(StopKind.REBOOT, 2))
    }

    @Test
    fun checkingClaimsNothing() {
        val checking = state(PcState.Checking)
        assertEquals("Checking…", Presentation.caption(checking, 0))
        assertEquals("Checking", Presentation.shutdownNote(checking))
        assertEquals("MY-PC status: checking", Presentation.heroDescription(checking))
        assertFalse(Presentation.commandsEnabled(checking))
    }

    @Test
    fun announcementsAreDiscreteStateChanges() {
        assertEquals(null, Presentation.announcement(state(PcState.Checking)))
        assertEquals("MY-PC is off", Presentation.announcement(state(PcState.Off(5))))
        assertEquals("MY-PC is waking up", Presentation.announcement(state(PcState.Waking(1_000))))
        assertEquals("MY-PC is on", Presentation.announcement(state(on)))
        assertEquals("MY-PC is restarting", Presentation.announcement(state(PcState.Stopping(StopKind.REBOOT, 0))))
        assertEquals("MY-PC didn't wake up", Presentation.announcement(state(PcState.DidntWake(0))))
    }

    @Test
    fun announcementIgnoresTimerAndLatencyTicks() {
        assertEquals(
            Presentation.announcement(state(PcState.Waking(1_000))),
            Presentation.announcement(state(PcState.Waking(9_000))),
        )
        assertEquals(
            Presentation.announcement(state(on)),
            Presentation.announcement(state(on.copy(latencyMs = 40, uptimeSec = 9_999))),
        )
    }

    @Test
    fun heroDescriptionNamesTheAction() {
        assertEquals("Wake MY-PC", Presentation.heroDescription(state(PcState.Off(null))))
        assertEquals("MY-PC status: on", Presentation.heroDescription(state(on)))
    }
}
