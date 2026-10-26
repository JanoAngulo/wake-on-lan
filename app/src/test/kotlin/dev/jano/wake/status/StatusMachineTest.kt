package dev.jano.wake.status

import dev.jano.wake.net.ProbeResult
import dev.jano.wake.protocol.StatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StatusMachineTest {
    private var now = 0L
    private val machine = StatusMachine(clock = { now })
    private val agent = StatusResponse("MY-PC", "192.168.1.14", "0A:1B:2C:3D:4E:5F", 8040, "1.0", 0)

    private fun up(rtt: Long = 2, withAgent: Boolean = true) = machine.onProbe(ProbeResult.Up(rtt), if (withAgent) agent else null)
    private fun down(times: Int = 1) = repeat(times) { machine.onProbe(ProbeResult.Down, null) }
    private val state get() = machine.state.value

    private fun turnOn() {
        up()
        assertIs<PcState.On>(state)
    }

    @Test
    fun checkingUpGoesOn() {
        up()
        assertIs<PcState.On>(state)
    }

    @Test
    fun checkingTwoDownsGoOff() {
        down(1)
        assertEquals(PcState.Checking, state)
        down(1)
        assertEquals(PcState.Off(null), state)
    }

    @Test
    fun restartingPollingRechecksOldState() {
        turnOn()
        machine.onPollingStarted()
        assertEquals(PcState.Checking, state)
    }

    @Test
    fun restartingPollingKeepsWakingTimer() {
        machine.onWakeSent()
        machine.onPollingStarted()
        assertEquals(PcState.Waking(0), state)
    }

    @Test
    fun startsChecking() {
        assertEquals(PcState.Checking, state)
    }

    @Test
    fun wakeThenUpGoesOn() {
        machine.onWakeSent()
        assertEquals(PcState.Waking(0), state)
        now = 20_000
        up()
        assertEquals(PcState.On(uptimeSec = 8040, latencyMs = 2, steady = true, agent = true), state)
    }

    @Test
    fun wakeTimesOutAt90s() {
        machine.onWakeSent()
        now = 89_999
        machine.tick()
        assertIs<PcState.Waking>(state)
        now = 90_000
        machine.tick()
        assertEquals(PcState.DidntWake(90_000), state)
    }

    @Test
    fun secondWakeTapKeepsTimer() {
        machine.onWakeSent()
        now = 10_000
        machine.onWakeSent()
        assertEquals(PcState.Waking(0), state)
    }

    @Test
    fun didntWakeCanRetry() {
        machine.onWakeSent()
        now = 90_000
        machine.tick()
        now = 95_000
        machine.onWakeSent()
        assertEquals(PcState.Waking(95_000), state)
    }

    @Test
    fun oneMissDoesNotTurnOff() {
        turnOn()
        down(2)
        assertIs<PcState.On>(state)
    }

    @Test
    fun threeMissesTurnOff() {
        turnOn()
        now = 6_000
        down(3)
        assertEquals(PcState.Off(6_000), state)
    }

    @Test
    fun upResetsMissCount() {
        turnOn()
        down(2)
        up()
        down(2)
        assertIs<PcState.On>(state)
    }

    @Test
    fun shutdownEndsOff() {
        turnOn()
        machine.onStopSent(StopKind.SHUTDOWN)
        assertEquals(PcState.Stopping(StopKind.SHUTDOWN, 0), state)
        up()
        assertIs<PcState.Stopping>(state)
        now = 8_000
        down(3)
        assertEquals(PcState.Off(8_000), state)
    }

    @Test
    fun rebootGoesOffThenWakingThenOn() {
        turnOn()
        machine.onStopSent(StopKind.REBOOT)
        now = 5_000
        down(3)
        assertEquals(PcState.Waking(5_000), state)
        now = 40_000
        up(withAgent = false)
        assertEquals(PcState.On(uptimeSec = null, latencyMs = 2, steady = true, agent = false), state)
    }

    @Test
    fun stoppingThatNeverStopsReturnsToOn() {
        turnOn()
        machine.onStopSent(StopKind.SHUTDOWN)
        now = 59_999
        up()
        machine.tick()
        assertIs<PcState.Stopping>(state)
        now = 60_000
        machine.tick()
        assertIs<PcState.On>(state)
    }

    @Test
    fun awayDuringWakingResumesTimer() {
        machine.onWakeSent()
        now = 30_000
        machine.onNetwork(home = false)
        assertEquals(PcState.Away(PcState.Waking(0)), state)
        now = 50_000
        machine.tick()
        machine.onNetwork(home = true)
        assertEquals(PcState.Waking(0), state)
        now = 89_000
        machine.tick()
        assertIs<PcState.Waking>(state)
        now = 90_000
        machine.tick()
        assertIs<PcState.DidntWake>(state)
    }

    @Test
    fun probesIgnoredWhileAway() {
        turnOn()
        machine.onNetwork(home = false)
        down(5)
        assertIs<PcState.Away>(state)
    }

    @Test
    fun upWhileOffGoesOn() {
        up()
        assertIs<PcState.On>(state)
    }

    @Test
    fun onWithoutAgentFlagsAgentFalse() {
        up(withAgent = false)
        assertEquals(false, (state as PcState.On).agent)
    }

    @Test
    fun steadyFlagUsesMedian() {
        listOf(2L, 3, 80, 4, 5).forEach { up(it) }
        assertTrue((state as PcState.On).steady)
        listOf(30L, 40, 25, 2, 60).forEach { up(it) }
        assertFalse((state as PcState.On).steady)
    }

    @Test
    fun cancelReturnsToOn() {
        turnOn()
        machine.onStopSent(StopKind.SHUTDOWN)
        machine.onStopCancelled()
        assertIs<PcState.On>(state)
    }
}
