package dev.jano.wake.agent.unlock

import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.UnlockResult

interface UnlockRelay {
    /** True while the lock screen is up, false when unlocked, null when the module isn't installed. */
    fun lockScreen(): Boolean?

    /** Hands the password to the credential provider. Zeroes [passwordUtf8] whatever happens. */
    fun relay(passwordUtf8: ByteArray): UnlockResponse
}

interface PipeChannel : AutoCloseable {
    /** String SID of the pipe's owner, or null if it couldn't be read. */
    val ownerSid: String?
    fun write(bytes: ByteArray)

    /** Null on timeout. */
    fun read(timeoutMs: Long): ByteArray?
}

interface PipeHost {
    fun installed(): Boolean
    fun pipeExists(): Boolean
    fun connect(): PipeChannel?
    fun wakeDisplay()
}

// SYSTEM, and Administrators (the default owner of objects SYSTEM creates). A normal-user program
// can't create a pipe owned by either, so a squatter on the pipe name never sees the password.
private val TRUSTED_OWNERS = setOf("S-1-5-18", "S-1-5-32-544")

// The provider's "closed without a result" reply; must match kStatusProviderClosed in PipeProtocol.h.
private const val STATUS_PROVIDER_CLOSED = 0xFFFFFFFFL

class PipeUnlockRelay(
    private val host: PipeHost,
    private val log: (String) -> Unit,
    private val replyTimeoutMs: Long = 15_000,
    private val settleMs: Long = 1_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : UnlockRelay {
    override fun lockScreen(): Boolean? = if (!host.installed()) null else host.pipeExists()

    override fun relay(passwordUtf8: ByteArray): UnlockResponse {
        try {
            if (!host.installed()) return UnlockResponse(UnlockResult.NO_MODULE)
            // No connection while the pipe exists means its one instance is busy with another request.
            val channel = host.connect()
                ?: return UnlockResponse(if (host.pipeExists()) UnlockResult.FAILED else UnlockResult.NOT_LOCKED)
            channel.use { return send(it, passwordUtf8) }
        } catch (e: Exception) {
            log("unlock: relay failed: ${e.message}")
            return UnlockResponse(UnlockResult.FAILED)
        } finally {
            passwordUtf8.fill(0)
        }
    }

    private fun send(channel: PipeChannel, passwordUtf8: ByteArray): UnlockResponse {
        val owner = channel.ownerSid
        if (owner !in TRUSTED_OWNERS) {
            log("unlock: refused pipe owned by $owner")
            return UnlockResponse(UnlockResult.FAILED)
        }
        host.wakeDisplay()
        val frame = encodeRequest(passwordUtf8)
        try {
            channel.write(frame)
        } finally {
            frame.fill(0)
        }
        val bytes = channel.read(replyTimeoutMs) ?: return UnlockResponse(UnlockResult.TIMEOUT)
        val reply = decodeReply(bytes)
        // LogonUI can tear the provider down without reporting a result, e.g. when the unlock
        // succeeds or someone unlocks by hand. If the lock screen is gone, the PC is unlocked.
        if (bytes.isEmpty() || reply?.status == STATUS_PROVIDER_CLOSED) {
            sleep(settleMs)
            return UnlockResponse(if (host.pipeExists()) UnlockResult.FAILED else UnlockResult.UNLOCKED)
        }
        return reply?.let(::toResponse) ?: UnlockResponse(UnlockResult.FAILED)
    }
}
