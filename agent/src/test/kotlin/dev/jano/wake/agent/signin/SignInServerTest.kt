package dev.jano.wake.agent.signin

import dev.jano.wake.agent.unlock.UnlockRelay
import dev.jano.wake.protocol.ErrorBody
import dev.jano.wake.protocol.H_NONCE
import dev.jano.wake.protocol.H_SIGNATURE
import dev.jano.wake.protocol.H_TIMESTAMP
import dev.jano.wake.protocol.PATH_SIGNIN_STATUS
import dev.jano.wake.protocol.PATH_SIGNIN_UNLOCK
import dev.jano.wake.protocol.SignInStatus
import dev.jano.wake.protocol.UnlockRequest
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.UnlockResult
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.newNonce
import dev.jano.wake.protocol.sealPassword
import dev.jano.wake.protocol.sign
import dev.jano.wake.protocol.signInKey
import dev.jano.wake.protocol.staleProof
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignInServerTest {
    private val secret = ByteArray(32) { (it * 7).toByte() }
    private val key = signInKey(secret)
    private val now = 1_793_000_000_000L
    private val relay = FakeRelay()
    private var keys: () -> ByteArray? = { key }
    private val server = SignInServer(
        bind = InetSocketAddress("127.0.0.1", 0),
        mac = { "0A:1B:2C:3D:4E:5F" },
        keys = { keys() },
        clock = { now },
        unlock = relay,
    ).also { it.start() }
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun tearDown() {
        relay.gate.countDown()
        server.stop()
    }

    private fun send(
        method: String,
        path: String,
        body: String = "",
        signWith: ByteArray = key,
        at: Long = now,
        nonce: String = newNonce(),
    ): HttpResponse<String> {
        val bytes = body.toByteArray()
        val h = sign(signWith, method, path, bytes, at, nonce)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
            .method(method, if (bytes.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(bytes))
            .header(H_TIMESTAMP, h.timestampMs.toString()).header(H_NONCE, h.nonce).header(H_SIGNATURE, h.signature)
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun errorOf(response: HttpResponse<String>) = WakeJson.decodeFromString(ErrorBody.serializer(), response.body())

    private fun unlockBody(sealFor: String, password: String = "correct horse") =
        WakeJson.encodeToString(UnlockRequest.serializer(), sealPassword(key, sealFor, password.toByteArray()))

    @Test
    fun statusSignedWithSignInKeyAnswers() {
        relay.lockScreen = true
        val response = send("GET", PATH_SIGNIN_STATUS)
        assertEquals(200, response.statusCode())
        assertEquals(SignInStatus("0A:1B:2C:3D:4E:5F", now, signInScreen = true), WakeJson.decodeFromString(SignInStatus.serializer(), response.body()))
        relay.lockScreen = false
        assertFalse(WakeJson.decodeFromString(SignInStatus.serializer(), send("GET", PATH_SIGNIN_STATUS).body()).signInScreen)
    }

    @Test
    fun requestSignedWithPairingSecretIsRejected() {
        val response = send("GET", PATH_SIGNIN_STATUS, signWith = secret)
        assertEquals(401, response.statusCode())
        assertEquals("bad_signature", errorOf(response).error)
    }

    @Test
    fun agentPathsAreNotServed() {
        for ((method, path) in listOf("GET" to "/status", "POST" to "/command", "POST" to "/unlock", "POST" to "/pair/start")) {
            assertEquals(404, send(method, path, "{}").statusCode(), path)
        }
    }

    @Test
    fun unlockOpensPasswordAndRelays() {
        val response = send("POST", PATH_SIGNIN_UNLOCK, unlockBody("n-1"), nonce = "n-1")
        assertEquals(200, response.statusCode())
        assertEquals(UnlockResult.UNLOCKED, WakeJson.decodeFromString(UnlockResponse.serializer(), response.body()).result)
        assertContentEquals("correct horse".toByteArray(), relay.received.single())
    }

    @Test
    fun unlockSealedForAnotherNonceIsBadRequest() {
        val response = send("POST", PATH_SIGNIN_UNLOCK, unlockBody("n-1"), nonce = "n-2")
        assertEquals(400, response.statusCode())
        assertEquals("bad_request", errorOf(response).error)
        assertTrue(relay.received.isEmpty())
    }

    @Test
    fun oversizedBodyIsRefused() {
        val response = send("POST", PATH_SIGNIN_UNLOCK, "x".repeat(4097))
        assertEquals(413, response.statusCode())
        assertEquals("too_large", errorOf(response).error)
        assertTrue(relay.received.isEmpty())
    }

    @Test
    fun notPairedWhenNoKey() {
        keys = { null }
        val response = send("GET", PATH_SIGNIN_STATUS)
        assertEquals(401, response.statusCode())
        assertEquals("not_paired", errorOf(response).error)
    }

    @Test
    fun staleProofUsesSignInKey() {
        val response = send("GET", PATH_SIGNIN_STATUS, at = now - 60_000, nonce = "n-stale")
        assertEquals(401, response.statusCode())
        val error = errorOf(response)
        assertEquals("stale", error.error)
        assertEquals(staleProof(key, "n-stale", error.serverTimeMs), error.proof)
    }

    @Test
    fun busyWhileUnlockInFlight() {
        relay.block = true
        assertFalse(server.busy())
        val request = thread { send("POST", PATH_SIGNIN_UNLOCK, unlockBody("n-busy"), nonce = "n-busy") }
        assertTrue(relay.entered.await(5, TimeUnit.SECONDS))
        assertTrue(server.busy())
        relay.gate.countDown()
        request.join(5_000)
        // The reply reaches the client a moment before the handler returns.
        val deadline = System.nanoTime() + 1_000_000_000
        while (server.busy() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(server.busy())
    }

    private class FakeRelay : UnlockRelay {
        @Volatile var lockScreen: Boolean? = null
        @Volatile var block = false
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val received = mutableListOf<ByteArray>()
        override fun lockScreen() = lockScreen
        override fun relay(passwordUtf8: ByteArray): UnlockResponse {
            received += passwordUtf8.copyOf()
            passwordUtf8.fill(0)
            if (block) {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
            return UnlockResponse(UnlockResult.UNLOCKED)
        }
    }
}
