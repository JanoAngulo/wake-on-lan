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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun retriesOnceWithAgentClockOffset(): Unit = runBlocking {
        val agentTime = now + 120_000
        server.enqueue(json(401, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody("stale", agentTime))))
        server.enqueue(json(200, WakeJson.encodeToString(StatusResponse.serializer(), status)))

        assertEquals(status, client.status())
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val retried = headersOf(server.takeRequest())
        assertTrue(abs(retried.timestampMs - agentTime) < 1_000, "retry used ${retried.timestampMs}")
    }

    @Test
    fun badSignatureSurfacesRejected() {
        server.enqueue(json(401, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody("bad_signature", now))))
        val error = assertFailsWith<AgentRejected> { runBlocking { client.status() } }
        assertEquals("bad_signature", error.error)
    }
}
