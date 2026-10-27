package dev.jano.wake.net

import dev.jano.wake.protocol.ErrorBody
import dev.jano.wake.protocol.H_NONCE
import dev.jano.wake.protocol.H_SIGNATURE
import dev.jano.wake.protocol.H_TIMESTAMP
import dev.jano.wake.protocol.RequestVerifier
import dev.jano.wake.protocol.SignedHeaders
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.protocol.VerifyResult
import dev.jano.wake.protocol.WakeJson
import kotlinx.coroutines.runBlocking
import dev.jano.wake.protocol.staleProof
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import dev.jano.wake.protocol.UnlockRequest
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.UnlockResult
import dev.jano.wake.protocol.openPassword
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentClientTest {
    private val secret = ByteArray(32) { (it + 3).toByte() }
    private val now = 1_793_000_000_000L
    private val server = MockWebServer().apply { start() }
    private val client = AgentClient(OkHttpClient(), server.url("/").toString().trimEnd('/'), secret) { now }

    private val status = StatusResponse("MY-PC", "192.168.1.14", "0A:1B:2C:3D:4E:5F", 60, "1.0", now)

    @AfterTest
    fun tearDown() = server.close()

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun headersOf(request: RecordedRequest) = SignedHeaders(
        request.headers[H_TIMESTAMP]!!.toLong(),
        request.headers[H_NONCE]!!,
        request.headers[H_SIGNATURE]!!,
    )

    @Test
    fun signsRequests(): Unit = runBlocking {
        server.enqueue(json(200, WakeJson.encodeToString(StatusResponse.serializer(), status)))
        assertEquals(status, client.status())

        val request = server.takeRequest()
        val verifier = RequestVerifier(secret = { secret }, clock = { now })
        assertEquals(VerifyResult.OK, verifier.verify("GET", request.url.encodedPath, ByteArray(0), headersOf(request)))
    }

    /** First reply: "stale" with the agent's clock, proven (or not) for the request's nonce. */
    private fun staleThenOk(agentTime: Long, proven: Boolean) {
        server.dispatcher = object : Dispatcher() {
            var calls = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                if (calls > 1) return json(200, WakeJson.encodeToString(StatusResponse.serializer(), status))
                val proof = if (proven) staleProof(secret, request.headers[H_NONCE]!!, agentTime) else null
                return json(401, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody("stale", agentTime, proof)))
            }
        }
    }

    @Test
    fun retriesOnceWithAgentClockOffset(): Unit = runBlocking {
        val agentTime = now + 120_000
        staleThenOk(agentTime, proven = true)

        assertEquals(status, client.status())
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val retried = headersOf(server.takeRequest())
        assertTrue(abs(retried.timestampMs - agentTime) < 1_000, "retry used ${retried.timestampMs}")
    }

    @Test
    fun ignoresUnprovenStaleTime() {
        staleThenOk(now + 36_000_000, proven = false)
        val error = assertFailsWith<AgentRejected> { runBlocking { client.status() } }
        assertEquals("stale", error.error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun badSignatureSurfacesRejected() {
        server.enqueue(json(401, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody("bad_signature", now))))
        val error = assertFailsWith<AgentRejected> { runBlocking { client.status() } }
        assertEquals("bad_signature", error.error)
    }

    private fun unlockReply(result: UnlockResult = UnlockResult.UNLOCKED) =
        json(200, WakeJson.encodeToString(UnlockResponse.serializer(), UnlockResponse(result)))

    private fun openedPassword(request: RecordedRequest): ByteArray? {
        val body = WakeJson.decodeFromString(UnlockRequest.serializer(), request.body!!.utf8())
        return openPassword(secret, request.headers[H_NONCE]!!, body)
    }

    @Test
    fun unlockSendsCiphertextBoundToNonce(): Unit = runBlocking {
        server.enqueue(unlockReply())
        client.unlock("pässwörd".toByteArray())

        val request = server.takeRequest()
        assertEquals("/unlock", request.url.encodedPath)
        assertContentEquals("pässwörd".toByteArray(), openedPassword(request))
        val verifier = RequestVerifier(secret = { secret }, clock = { now })
        assertEquals(VerifyResult.OK, verifier.verify("POST", "/unlock", request.body!!.toByteArray(), headersOf(request)))
    }

    @Test
    fun unlockRetryAfterStaleUsesFreshNonceAndCiphertext(): Unit = runBlocking {
        val agentTime = now + 120_000
        server.dispatcher = object : Dispatcher() {
            var calls = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                if (calls > 1) return unlockReply()
                val proof = staleProof(secret, request.headers[H_NONCE]!!, agentTime)
                return json(401, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody("stale", agentTime, proof)))
            }
        }
        assertEquals(UnlockResult.UNLOCKED, client.unlock("pässwörd".toByteArray()).result)

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertNotEquals(first.headers[H_NONCE], second.headers[H_NONCE])
        val iv = { r: RecordedRequest -> WakeJson.decodeFromString(UnlockRequest.serializer(), r.body!!.utf8()).iv }
        assertNotEquals(iv(first), iv(second))
        assertContentEquals("pässwörd".toByteArray(), openedPassword(second))
    }

    @Test
    fun unlockParsesResult(): Unit = runBlocking {
        server.enqueue(unlockReply(UnlockResult.WRONG_PASSWORD))
        assertEquals(UnlockResult.WRONG_PASSWORD, client.unlock("x".toByteArray()).result)
    }
}
