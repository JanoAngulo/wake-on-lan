package dev.jano.wake.agent

import dev.jano.wake.protocol.Action
import dev.jano.wake.protocol.CommandResponse
import dev.jano.wake.protocol.PendingCommand
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Shutdown and reboot wait [delayMs] so the phone can undo them; sleep and lock run at once.
 * Only one delayed command exists at a time; a new one replaces it.
 */
class CommandScheduler(
    private val actions: PowerActions,
    private val clock: () -> Long,
    private val timer: ScheduledExecutorService,
    private val delayMs: Long = 3_000,
) {
    private var pending: PendingCommand? = null
    private var future: ScheduledFuture<*>? = null

    @Synchronized
    fun submit(action: Action): CommandResponse = when (action) {
        Action.SLEEP -> runNow(actions::sleep)
        Action.LOCK -> runNow(actions::lock)
        Action.SHUTDOWN, Action.REBOOT -> schedule(action)
    }

    @Synchronized
    fun cancel(id: String): Boolean {
        if (pending?.id != id) return false
        future?.cancel(false)
        pending = null
        future = null
        return true
    }

    @Synchronized
    fun pending(): PendingCommand? = pending

    private fun runNow(action: () -> Unit): CommandResponse {
        timer.execute(action)
        return CommandResponse()
    }

    private fun schedule(action: Action): CommandResponse {
        future?.cancel(false)
        val command = PendingCommand(UUID.randomUUID().toString(), action, clock() + delayMs)
        pending = command
        future = timer.schedule({ fire(command) }, delayMs, TimeUnit.MILLISECONDS)
        return CommandResponse(command.id, command.firesAtEpochMs)
    }

    private fun fire(command: PendingCommand) {
        synchronized(this) {
            if (pending?.id != command.id) return
            pending = null
            future = null
        }
        when (command.action) {
            Action.SHUTDOWN -> actions.shutdown()
            Action.REBOOT -> actions.reboot()
            else -> Unit
        }
    }
}
