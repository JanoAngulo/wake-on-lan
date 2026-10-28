package dev.jano.wake.agent.signin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignInGateTest {
    private var now = 0L
    private var pipe = false
    private var inFlight = false
    private var opens = 0
    private var closes = 0
    private val gate = SignInGate(
        pipeExists = { pipe },
        inFlight = { inFlight },
        clock = { now },
        open = { opens++ },
        close = { closes++ },
    )

    private fun tickAt(ms: Long) {
        now = ms
        gate.tick()
    }

    @Test
    fun opensWhenPipeAppears() {
        pipe = true
        tickAt(0)
        assertEquals(1, opens)
        assertTrue(gate.isOpen)
    }

    @Test
    fun noOpenTwice() {
        pipe = true
        tickAt(0)
        tickAt(1_000)
        assertEquals(1, opens)
    }

    @Test
    fun staysOpenWithinGrace() {
        pipe = true
        tickAt(0)
        pipe = false
        tickAt(1_000)
        tickAt(5_900)
        assertEquals(0, closes)
        assertTrue(gate.isOpen)
    }

    @Test
    fun closesAfterGraceWhenIdle() {
        pipe = true
        tickAt(0)
        pipe = false
        tickAt(1_000)
        tickAt(6_000)
        assertEquals(1, closes)
        assertFalse(gate.isOpen)
    }

    @Test
    fun waitsForInFlightRequest() {
        pipe = true
        tickAt(0)
        pipe = false
        inFlight = true
        tickAt(1_000)
        tickAt(7_000)
        assertEquals(0, closes)
        inFlight = false
        tickAt(8_000)
        assertEquals(1, closes)
    }

    @Test
    fun closesAfterHardLimitEvenWhileBusy() {
        pipe = true
        tickAt(0)
        pipe = false
        inFlight = true
        tickAt(1_000)
        tickAt(25_900)
        assertEquals(0, closes)
        tickAt(26_000)
        assertEquals(1, closes)
        assertFalse(gate.isOpen)
    }

    @Test
    fun pipeReturningDuringGraceCancelsClose() {
        pipe = true
        tickAt(0)
        pipe = false
        tickAt(1_000)
        pipe = true
        tickAt(4_000)
        pipe = false
        tickAt(5_000)
        tickAt(7_000)
        assertEquals(0, closes)
    }

    @Test
    fun reopensWhenPipeReturns() {
        pipe = true
        tickAt(0)
        pipe = false
        tickAt(1_000)
        tickAt(6_000)
        pipe = true
        tickAt(7_000)
        assertEquals(2, opens)
        assertTrue(gate.isOpen)
    }

    @Test
    fun resetClosesAndReopensOnNextTick() {
        pipe = true
        tickAt(0)
        gate.reset()
        assertEquals(1, closes)
        assertFalse(gate.isOpen)
        tickAt(1_000)
        assertEquals(2, opens)
    }

    @Test
    fun resetWhileClosedDoesNothing() {
        gate.reset()
        assertEquals(0, closes)
    }

    @Test
    fun repeatedOpenFailureIsLoggedOnce() {
        val logged = mutableListOf<String>()
        var fail = true
        val flaky = SignInGate({ true }, { false }, { now }, { if (fail) error("no LAN adapter") }, { }, log = { logged += it })
        repeat(5) { flaky.tick() }
        assertEquals(1, logged.size)
        fail = false
        flaky.tick()
        assertTrue(flaky.isOpen)
    }

    @Test
    fun failedOpenStaysClosedAndRetries() {
        var attempts = 0
        val failing = SignInGate({ true }, { false }, { now }, { attempts++; error("port in use") }, { closes++ })
        failing.tick()
        assertFalse(failing.isOpen)
        failing.tick()
        assertEquals(2, attempts)
    }
}
