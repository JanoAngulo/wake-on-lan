package dev.jano.wake.agent

import dev.jano.wake.protocol.Action
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingActions : PowerActions {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override fun shutdown() { calls += "shutdown" }
    override fun reboot() { calls += "reboot" }
    override fun sleep() { calls += "sleep" }
    override fun lock() { calls += "lock" }
}

class CommandSchedulerTest {
    private val actions = RecordingActions()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val now = 1_793_000_000_000L
    private val scheduler = CommandScheduler(actions, clock = { now }, timer = timer, delayMs = 150)

    @AfterTest
    fun tearDown() {
        timer.shutdownNow()
    }

    private fun settle(ms: Long = 400) = Thread.sleep(ms)

    @Test
    fun shutdownFiresAfterDelay() {
        scheduler.submit(Action.SHUTDOWN)
        assertTrue(actions.calls.isEmpty())
        settle()
        assertEquals(listOf("shutdown"), actions.calls.toList())
    }

    @Test
    fun cancelBeforeFirePreventsAction() {
        val id = assertNotNull(scheduler.submit(Action.REBOOT).id)
        assertTrue(scheduler.cancel(id))
        settle()
        assertTrue(actions.calls.isEmpty())
        assertNull(scheduler.pending())
    }

    @Test
    fun cancelAfterFireReturnsFalse() {
        val id = assertNotNull(scheduler.submit(Action.SHUTDOWN).id)
        settle()
        assertFalse(scheduler.cancel(id))
    }

    @Test
    fun lockRunsImmediately() {
        val response = scheduler.submit(Action.LOCK)
        assertNull(response.id)
        settle(100)
        assertEquals(listOf("lock"), actions.calls.toList())
    }

    @Test
    fun newShutdownReplacesPending() {
        val first = assertNotNull(scheduler.submit(Action.REBOOT).id)
        val second = assertNotNull(scheduler.submit(Action.SHUTDOWN).id)
        assertFalse(scheduler.cancel(first))
        assertEquals(second, scheduler.pending()?.id)
        settle()
        assertEquals(listOf("shutdown"), actions.calls.toList())
    }

    @Test
    fun pendingReportsFireTime() {
        val response = scheduler.submit(Action.SHUTDOWN)
        assertEquals(now + 150, response.firesAtEpochMs)
        val pending = assertNotNull(scheduler.pending())
        assertEquals(Action.SHUTDOWN, pending.action)
        assertEquals(now + 150, pending.firesAtEpochMs)
    }
}
