package dev.jano.wake.agent

import com.sun.net.httpserver.HttpExchange
import dev.jano.wake.protocol.ErrorBody
import dev.jano.wake.protocol.H_NONCE
import dev.jano.wake.protocol.H_SIGNATURE
import dev.jano.wake.protocol.H_TIMESTAMP
import dev.jano.wake.protocol.RequestVerifier
import dev.jano.wake.protocol.SignedHeaders
import dev.jano.wake.protocol.VerifyResult
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.staleProof
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * The JDK HttpServer waits forever for a slow request by default. Cap it: 5 s to receive a request, 30 s to
 * answer (an unlock takes up to ~16 s). Must run before the first HttpServer is created, since the JDK reads
 * these once.
 */
fun limitHttpServerTimes() {
    System.setProperty("sun.net.httpserver.maxReqTime", "5")
    System.setProperty("sun.net.httpserver.maxRspTime", "30")
}

/** Every request body the phone sends fits easily; anything bigger is refused unread. */
const val MAX_BODY_BYTES = 4096

/** The request body, or null when it's over [max] bytes. Never reads more than max + 1. */
fun readCapped(exchange: HttpExchange, max: Int = MAX_BODY_BYTES): ByteArray? {
    val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
    if (declared != null && declared > max) return null
    val bytes = exchange.requestBody.readNBytes(max + 1)
    return if (bytes.size > max) null else bytes
}

/** Signature, freshness and replay checks, and the JSON replies, for requests signed with [key]. */
class SignedExchange(private val key: () -> ByteArray?, private val clock: () -> Long) {
    private val verifier = RequestVerifier(secret = key, clock = clock)

    fun <T> handle(exchange: HttpExchange, body: ByteArray, handler: () -> Pair<T, KSerializer<T>>) {
        val h = exchange.requestHeaders
        val timestamp = h.getFirst(H_TIMESTAMP)?.toLongOrNull()
        val nonce = h.getFirst(H_NONCE)
        val signature = h.getFirst(H_SIGNATURE)
        val result = if (timestamp == null || nonce == null || signature == null) {
            if (key() == null) VerifyResult.NOT_PAIRED else VerifyResult.BAD_SIGNATURE
        } else {
            verifier.verify(exchange.requestMethod, exchange.requestURI.path, body, SignedHeaders(timestamp, nonce, signature))
        }
        if (result == VerifyResult.STALE) {
            // Signature already checked, so this request really came from the paired phone.
            val now = clock()
            val stale = ErrorBody("stale", now, staleProof(key()!!, nonce!!, now))
            return json(exchange, 401, WakeJson.encodeToString(ErrorBody.serializer(), stale))
        }
        if (result != VerifyResult.OK) return error(exchange, 401, result.name.lowercase())

        val (value, serializer) = try {
            handler()
        } catch (e: SerializationException) {
            return error(exchange, 400, "bad_request")
        }
        json(exchange, 200, WakeJson.encodeToString(serializer, value))
    }

    fun error(exchange: HttpExchange, status: Int, code: String) =
        json(exchange, status, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody(code, clock())))

    fun json(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
