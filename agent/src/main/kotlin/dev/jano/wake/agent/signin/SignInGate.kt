package dev.jano.wake.agent.signin

/**
 * Keeps the sign-in port open only while the lock or sign-in screen is up (its pipe exists), and closes it
 * [graceMs] after the pipe goes away, once no request is in flight. Call [tick] about once a second.
 */
class SignInGate(
    private val pipeExists: () -> Boolean,
    private val inFlight: () -> Boolean,
    private val clock: () -> Long,
    private val open: () -> Unit,
    private val close: () -> Unit,
    private val graceMs: Long = 5_000,
    private val log: (String) -> Unit = {},
) {
    var isOpen = false
        private set
    private var absentSince: Long? = null

    fun tick() {
        val now = clock()
        if (pipeExists()) {
            absentSince = null
            if (!isOpen) {
                // A failed open (port taken, adapter gone) stays closed; the next tick tries again.
                isOpen = runCatching { open() }.onFailure { log("sign-in port didn't open: ${it.message}") }.isSuccess
            }
            return
        }
        if (!isOpen) return
        val since = absentSince ?: now.also { absentSince = it }
        if (now - since >= graceMs && !inFlight()) shut()
    }

    /** Close now; the next tick reopens if the pipe is still there. For an IP address change. */
    fun reset() {
        if (isOpen) shut()
    }

    private fun shut() {
        runCatching { close() }.onFailure { log("sign-in port didn't close cleanly: ${it.message}") }
        isOpen = false
        absentSince = null
    }
}
