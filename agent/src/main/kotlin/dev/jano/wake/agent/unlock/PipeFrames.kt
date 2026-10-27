package dev.jano.wake.agent.unlock

import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.UnlockResult
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// Frames on \.\pipe\wake-unlock, little-endian; must match credential-provider/src/PipeProtocol.h.
private const val PROTOCOL_VERSION = 1
private const val STATUS_LOGON_FAILURE = 0xC000006DL
private const val STATUS_WRONG_PASSWORD = 0xC000006AL

/** u32 version, u32 length, UTF-16LE password. Built without a String; the decoded chars are zeroed. */
fun encodeRequest(passwordUtf8: ByteArray): ByteArray {
    val chars = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(passwordUtf8))
    try {
        val length = chars.remaining() * 2
        val frame = ByteArray(8 + length)
        putU32(frame, 0, PROTOCOL_VERSION.toLong())
        putU32(frame, 4, length.toLong())
        for (i in 0 until chars.remaining()) {
            val c = chars.get(chars.position() + i).code
            frame[8 + 2 * i] = c.toByte()
            frame[9 + 2 * i] = (c shr 8).toByte()
        }
        return frame
    } finally {
        chars.array().fill('\u0000')
    }
}

/** u32 status, u32 substatus: the NTSTATUS pair LogonUI reported. */
data class PipeReply(val status: Long, val substatus: Long)

fun decodeReply(bytes: ByteArray): PipeReply? =
    if (bytes.size != 8) null else PipeReply(getU32(bytes, 0), getU32(bytes, 4))

fun toResponse(reply: PipeReply): UnlockResponse = when {
    reply.status == 0L -> UnlockResponse(UnlockResult.UNLOCKED)
    reply.status == STATUS_LOGON_FAILURE || reply.substatus == STATUS_WRONG_PASSWORD ->
        UnlockResponse(UnlockResult.WRONG_PASSWORD, reply.status)
    else -> UnlockResponse(UnlockResult.FAILED, reply.status)
}

private fun putU32(out: ByteArray, at: Int, value: Long) {
    for (i in 0 until 4) out[at + i] = (value shr (8 * i)).toByte()
}

private fun getU32(bytes: ByteArray, at: Int): Long =
    (0 until 4).fold(0L) { acc, i -> acc or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }
