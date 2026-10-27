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

class PipeUnlockRelay(
    private val host: PipeHost,
    private val log: (String) -> Unit,
    private val replyTimeoutMs: Long = 15_000,
) : UnlockRelay {
    override fun lockScreen(): Boolean? = if (!host.installed()) null else host.pipeExists()

    override fun relay(passwordUtf8: ByteArray): UnlockResponse {
        try {
            if (!host.installed()) return UnlockResponse(UnlockResult.NO_MODULE)
            val channel = host.connect() ?: return UnlockResponse(UnlockResult.NOT_LOCKED)
            channel.use {
                val owner = it.ownerSid
                if (owner !in TRUSTED_OWNERS) {
                    log("unlock: refused pipe owned by $owner")
                    return UnlockResponse(UnlockResult.FAILED)
                }
                host.wakeDisplay()
                val frame = encodeRequest(passwordUtf8)
                try {
                    it.write(frame)
                } finally {
                    frame.fill(0)
                }
                val reply = it.read(replyTimeoutMs) ?: return UnlockResponse(UnlockResult.TIMEOUT)
                return decodeReply(reply)?.let(::toResponse) ?: UnlockResponse(UnlockResult.FAILED)
            }
        } finally {
            passwordUtf8.fill(0)
        }
    }
}
