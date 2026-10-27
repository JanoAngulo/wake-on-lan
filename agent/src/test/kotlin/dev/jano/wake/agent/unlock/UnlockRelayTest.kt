package dev.jano.wake.agent.unlock

import dev.jano.wake.protocol.UnlockResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnlockRelayTest {
    private class FakeChannel(
        override val ownerSid: String?,
        private val reply: ByteArray?,
        private val failWrite: Boolean = false,
    ) : PipeChannel {
        val writes = mutableListOf<ByteArray>()
        var closed = false
        var readTimeout = -1L
        override fun write(bytes: ByteArray) {
            if (failWrite) error("pipe write failed: 232")
            writes += bytes.copyOf()
        }
        override fun read(timeoutMs: Long): ByteArray? { readTimeout = timeoutMs; return reply }
        override fun close() { closed = true }
    }

    private class FakeHost(
        val installed: Boolean = true,
        var pipe: Boolean = true,
        val channel: FakeChannel? = FakeChannel("S-1-5-18", ByteArray(8)),
        /** What pipeExists() says once the request is over (the lock screen may have gone). */
        val pipeAfter: Boolean? = null,
    ) : PipeHost {
        var displayWoken = false
        override fun installed() = installed
        override fun pipeExists() = if (channel?.readTimeout != -1L && pipeAfter != null) pipeAfter else pipe
        override fun connect(): PipeChannel? = channel
        override fun wakeDisplay() { displayWoken = true }
    }

    private val logs = mutableListOf<String>()
    private val sleeps = mutableListOf<Long>()
    private fun relay(host: PipeHost) = PipeUnlockRelay(host, { logs += it }, replyTimeoutMs = 1234, sleep = { sleeps += it })
    private fun pw() = "secret".toByteArray()
    private val wrongPasswordReply = byteArrayOf(0x6D, 0, 0, 0xC0.toByte(), 0x6A, 0, 0, 0xC0.toByte())

    @Test
    fun notInstalledIsNoModule() {
        assertEquals(UnlockResult.NO_MODULE, relay(FakeHost(installed = false)).relay(pw()).result)
    }

    @Test
    fun noPipeIsNotLocked() {
        assertEquals(UnlockResult.NOT_LOCKED, relay(FakeHost(pipe = false, channel = null)).relay(pw()).result)
    }

    @Test
    fun busyPipeIsFailedNotNotLocked() {
        // Pipe exists but its one instance is taken (an earlier request is still in flight).
        assertEquals(UnlockResult.FAILED, relay(FakeHost(pipe = true, channel = null)).relay(pw()).result)
    }

    @Test
    fun closedWithoutReplyAndLockScreenGoneIsUnlocked() {
        val host = FakeHost(channel = FakeChannel("S-1-5-18", ByteArray(0)), pipeAfter = false)
        assertEquals(UnlockResult.UNLOCKED, relay(host).relay(pw()).result)
        assertTrue(sleeps.isNotEmpty(), "waited for LogonUI to settle")
    }

    @Test
    fun closedWithoutReplyWhileStillLockedIsFailed() {
        val host = FakeHost(channel = FakeChannel("S-1-5-18", ByteArray(0)), pipeAfter = true)
        assertEquals(UnlockResult.FAILED, relay(host).relay(pw()).result)
    }

    @Test
    fun providerClosedReplyTreatedLikeClosedPipe() {
        val closed = byteArrayOf(-1, -1, -1, -1, 0, 0, 0, 0)
        val gone = FakeHost(channel = FakeChannel("S-1-5-18", closed), pipeAfter = false)
        assertEquals(UnlockResult.UNLOCKED, relay(gone).relay(pw()).result)
        val still = FakeHost(channel = FakeChannel("S-1-5-18", closed), pipeAfter = true)
        assertEquals(UnlockResult.FAILED, relay(still).relay(pw()).result)
    }

    @Test
    fun writeFailureIsFailedNotAnException() {
        val channel = FakeChannel("S-1-5-18", ByteArray(8), failWrite = true)
        val password = pw()
        assertEquals(UnlockResult.FAILED, relay(FakeHost(channel = channel)).relay(password).result)
        assertTrue(channel.closed)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test
    fun rejectsPipeOwnedByUser() {
        val channel = FakeChannel("S-1-5-21-1-2-3-1001", ByteArray(8))
        val result = relay(FakeHost(channel = channel)).relay(pw())
        assertEquals(UnlockResult.FAILED, result.result)
        assertTrue(channel.writes.isEmpty())
        assertTrue(logs.any { "S-1-5-21-1-2-3-1001" in it })
    }

    @Test
    fun rejectsPipeWithUnknownOwner() {
        val channel = FakeChannel(null, ByteArray(8))
        assertEquals(UnlockResult.FAILED, relay(FakeHost(channel = channel)).relay(pw()).result)
        assertTrue(channel.writes.isEmpty())
    }

    @Test
    fun acceptsSystemOwner() {
        val host = FakeHost(channel = FakeChannel("S-1-5-18", ByteArray(8)))
        assertEquals(UnlockResult.UNLOCKED, relay(host).relay(pw()).result)
        assertTrue(host.displayWoken)
        assertEquals(1, host.channel!!.writes.size)
        assertEquals(1234L, host.channel.readTimeout)
    }

    @Test
    fun rejectsAdministratorsOwner() {
        // LogonUI's pipe is owned by SYSTEM (seen in the VM spike); an elevated user program's would not be.
        val channel = FakeChannel("S-1-5-32-544", ByteArray(8))
        assertEquals(UnlockResult.FAILED, relay(FakeHost(channel = channel)).relay(pw()).result)
        assertTrue(channel.writes.isEmpty())
    }

    @Test
    fun timeoutWhenNoReply() {
        val host = FakeHost(channel = FakeChannel("S-1-5-18", null))
        assertEquals(UnlockResult.TIMEOUT, relay(host).relay(pw()).result)
    }

    @Test
    fun malformedReplyIsFailed() {
        val host = FakeHost(channel = FakeChannel("S-1-5-18", ByteArray(3)))
        assertEquals(UnlockResult.FAILED, relay(host).relay(pw()).result)
    }

    @Test
    fun wrongPasswordPassesThrough() {
        val host = FakeHost(channel = FakeChannel("S-1-5-18", wrongPasswordReply))
        val response = relay(host).relay(pw())
        assertEquals(UnlockResult.WRONG_PASSWORD, response.result)
        assertEquals(0xC000006DL, response.ntstatus)
    }

    @Test
    fun zeroesPasswordAfterRelay() {
        val password = pw()
        relay(FakeHost()).relay(password)
        assertTrue(password.all { it == 0.toByte() })
        val rejected = pw()
        relay(FakeHost(installed = false)).relay(rejected)
        assertTrue(rejected.all { it == 0.toByte() })
    }

    @Test
    fun closesChannelOnEveryPath() {
        for (channel in listOf(
            FakeChannel("S-1-5-18", ByteArray(8)),
            FakeChannel("S-1-5-18", null),
            FakeChannel("S-1-5-18", ByteArray(3)),
            FakeChannel("S-1-5-21-1-2-3-1001", ByteArray(8)),
        )) {
            relay(FakeHost(channel = channel)).relay(pw())
            assertTrue(channel.closed)
        }
    }

    @Test
    fun lockScreenNullWhenNotInstalled() {
        assertNull(relay(FakeHost(installed = false)).lockScreen())
    }

    @Test
    fun lockScreenTracksPipe() {
        assertEquals(true, relay(FakeHost(pipe = true)).lockScreen())
        assertEquals(false, relay(FakeHost(pipe = false)).lockScreen())
    }
}
