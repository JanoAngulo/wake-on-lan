package dev.jano.wake.agent.unlock

import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.UnlockResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PipeFramesTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun encodesAsciiFrame() {
        assertContentEquals(hex("01000000 06000000 610062006300"), encodeRequest("abc".toByteArray()))
    }

    @Test
    fun encodesNonAsciiPasswordAsUtf16le() {
        val frame = encodeRequest("ñé😀".toByteArray(Charsets.UTF_8))
        assertContentEquals(hex("01000000 08000000 F100E9003DD800DE"), frame)
    }

    @Test
    fun decodesReply() {
        assertEquals(PipeReply(0xC000006DL, 0xC000006AL), decodeReply(hex("6D0000C0 6A0000C0")))
    }

    @Test
    fun rejectsShortReply() {
        assertNull(decodeReply(hex("6D0000C0 6A00")))
        assertNull(decodeReply(ByteArray(9)))
    }

    @Test
    fun mapsSuccess() {
        assertEquals(UnlockResponse(UnlockResult.UNLOCKED), toResponse(PipeReply(0, 0)))
    }

    @Test
    fun mapsLogonFailureToWrongPassword() {
        assertEquals(
            UnlockResponse(UnlockResult.WRONG_PASSWORD, 0xC000006DL),
            toResponse(PipeReply(0xC000006DL, 0xC000006AL)),
        )
        assertEquals(
            UnlockResponse(UnlockResult.WRONG_PASSWORD, 0xC0000001L),
            toResponse(PipeReply(0xC0000001L, 0xC000006AL)),
        )
    }

    @Test
    fun mapsOtherStatusToFailed() {
        assertEquals(UnlockResponse(UnlockResult.FAILED, 0xC0000234L), toResponse(PipeReply(0xC0000234L, 0)))
    }
}
