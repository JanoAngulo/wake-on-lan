package dev.jano.wake.agent

import dev.jano.wake.protocol.Action
import dev.jano.wake.protocol.CancelRequest
import dev.jano.wake.protocol.CancelResponse
import dev.jano.wake.protocol.CommandRequest
import dev.jano.wake.protocol.CommandResponse
import dev.jano.wake.protocol.ErrorBody
import dev.jano.wake.protocol.H_NONCE
import dev.jano.wake.protocol.H_SIGNATURE
import dev.jano.wake.protocol.H_TIMESTAMP
import dev.jano.wake.protocol.PairConfirmed
import dev.jano.wake.protocol.PairOffer
import dev.jano.wake.protocol.staleProof
import kotlinx.serialization.json.jsonObject
import dev.jano.wake.protocol.PairFinish
import dev.jano.wake.protocol.PairStart
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.confirmTag
import dev.jano.wake.protocol.decodePublic
import dev.jano.wake.protocol.deriveSecret
import dev.jano.wake.protocol.encodePublic
import dev.jano.wake.protocol.generateX25519
import dev.jano.wake.protocol.sign
import dev.jano.wake.protocol.transcript
import dev.jano.wake.protocol.UnlockRequest
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.UnlockResult
import dev.jano.wake.protocol.sealPassword
import dev.jano.wake.agent.unlock.UnlockRelay
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentServerTest {
    private val secret = ByteArray(32) { (it * 7).toByte() }
    private val now = 1_793_000_000_000L
    private val actions = RecordingActions()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val scheduler = CommandScheduler(actions, { now }, timer, delayMs = 60_000)
    private val store = SecretStore(Files.createTempDirectory("wake-secret"))
    private val pairing = PairingMode({ now })
    private var pairedCalls = 0
    private val relay = FakeRelay()
    private val server = AgentServer(
        bind = InetSocketAddress("127.0.0.1", 0),
        hostInfo = { HostInfo("MY-PC", "192.168.1.14", "0A:1B:2C:3D:4E:5F", 8040, "1.0") },
        scheduler = scheduler,
        store = store,
        pairing = pairing,
        clock = { now },
        onPaired = { pairedCalls++ },
        unlock = relay,
    ).also { it.start() }
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun tearDown() {
        server.stop()
        timer.shutdownNow()
    }

    private fun send(
        method: String,
        path: String,
        body: String = "",
        signWith: ByteArray? = secret,
        at: Long = now,
        nonce: String = dev.jano.wake.protocol.newNonce(),
    ): HttpResponse<String> {
        val bytes = body.toByteArray()
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
            .method(method, if (bytes.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(bytes))
        if (signWith != null) {
            val h = sign(signWith, method, path, bytes, at, nonce)
            builder.header(H_TIMESTAMP, h.timestampMs.toString()).header(H_NONCE, h.nonce).header(H_SIGNATURE, h.signature)
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun paired() = store.save(secret)

    @Test
    fun statusRequiresSignature() {
        paired()
        assertEquals(401, send("GET", "/status", signWith = null).statusCode())
    }

    @Test
    fun signedStatusReturnsHostInfo() {
        paired()
        val response = send("GET", "/status")
        assertEquals(200, response.statusCode())
        val status = WakeJson.decodeFromString(StatusResponse.serializer(), response.body())
        assertEquals("MY-PC", status.hostname)
        assertEquals(8040, status.uptimeSeconds)
        assertEquals(now, status.serverTimeMs)
        assertEquals("Ethernet", status.link)
    }

    @Test
    fun commandSchedulesShutdown() {
        paired()
        val body = WakeJson.encodeToString(CommandRequest.serializer(), CommandRequest(Action.SHUTDOWN))
        val response = send("POST", "/command", body)
        assertEquals(200, response.statusCode())
        val command = WakeJson.decodeFromString(CommandResponse.serializer(), response.body())
        assertNotNull(command.id)
        assertTrue(actions.calls.isEmpty())
        val status = WakeJson.decodeFromString(StatusResponse.serializer(), send("GET", "/status").body())
        assertEquals(command.id, status.pending?.id)
    }

    @Test
    fun cancelWorks() {
        paired()
        val body = WakeJson.encodeToString(CommandRequest.serializer(), CommandRequest(Action.REBOOT))
        val id = WakeJson.decodeFromString(CommandResponse.serializer(), send("POST", "/command", body).body()).id!!
        val cancel = send("POST", "/command/cancel", WakeJson.encodeToString(CancelRequest.serializer(), CancelRequest(id)))
        assertEquals(true, WakeJson.decodeFromString(CancelResponse.serializer(), cancel.body()).cancelled)
    }

    @Test
    fun staleRequestReturnsProvenAgentTime() {
        paired()
        val response = send("GET", "/status", at = now - 60_000, nonce = "n-stale")
        assertEquals(401, response.statusCode())
        val error = WakeJson.decodeFromString(ErrorBody.serializer(), response.body())
        assertEquals("stale", error.error)
        assertEquals(now, error.serverTimeMs)
        assertEquals(staleProof(secret, "n-stale", now), error.proof)
    }

    @Test
    fun forgedStaleRequestLearnsNothing() {
        paired()
        val response = send("GET", "/status", signWith = ByteArray(32) { 1 }, at = now - 60_000)
        assertEquals(401, response.statusCode())
        val error = WakeJson.decodeFromString(ErrorBody.serializer(), response.body())
        assertEquals("bad_signature", error.error)
        assertEquals(null, error.proof)
    }

    @Test
    fun pairStartWithoutPairingModeIs409() {
        val body = WakeJson.encodeToString(PairStart.serializer(), PairStart(encodePublic(generateX25519().public)))
        assertEquals(409, send("POST", "/pair/start", body, signWith = null).statusCode())
    }

    private fun startPairing(phonePub: String): HttpResponse<String> {
        val body = WakeJson.encodeToString(PairStart.serializer(), PairStart(phonePub))
        return send("POST", "/pair/start", body, signWith = null)
    }

    private fun offerOf(response: HttpResponse<String>): PairOffer {
        assertEquals(200, response.statusCode())
        return WakeJson.decodeFromString(PairOffer.serializer(), response.body())
    }

    @Test
    fun pairStartRevealsNoCodeDerivedTag() {
        pairing.begin()
        val response = startPairing(encodePublic(generateX25519().public))
        assertEquals(200, response.statusCode())
        assertEquals(setOf("agentPublicKey"), WakeJson.parseToJsonElement(response.body()).jsonObject.keys)
    }

    @Test
    fun secondStartWhileSessionOpenIsBusy() {
        pairing.begin()
        offerOf(startPairing(encodePublic(generateX25519().public)))
        assertEquals(409, startPairing(encodePublic(generateX25519().public)).statusCode())
    }

    @Test
    fun fullPairingSavesSameSecretAsPhone() {
        val code = pairing.begin()
        val phone = generateX25519()
        val phonePub = encodePublic(phone.public)
        val offer = offerOf(startPairing(phonePub))
        val t = transcript(phonePub, offer.agentPublicKey)

        val finish = PairFinish(phonePub, confirmTag(code, "phone", t))
        val response = send("POST", "/pair/finish", WakeJson.encodeToString(PairFinish.serializer(), finish), signWith = null)
        assertEquals(200, response.statusCode())
        val confirmed = WakeJson.decodeFromString(PairConfirmed.serializer(), response.body())
        assertEquals(confirmTag(code, "agent", t), confirmed.agentConfirm)

        val phoneSecret = deriveSecret(phone.private, decodePublic(offer.agentPublicKey), t)
        assertContentEquals(phoneSecret, store.load())
        assertEquals(1, pairedCalls)
        assertEquals(null, pairing.activeCode())
    }

    @Test
    fun wrongCodeEndsPairing() {
        pairing.begin()
        val phonePub = encodePublic(generateX25519().public)
        val offer = offerOf(startPairing(phonePub))
        val t = transcript(phonePub, offer.agentPublicKey)
        val finish = PairFinish(phonePub, confirmTag("000000", "phone", t))
        val response = send("POST", "/pair/finish", WakeJson.encodeToString(PairFinish.serializer(), finish), signWith = null)
        assertEquals(403, response.statusCode())

        assertEquals(409, startPairing(phonePub).statusCode())
        assertEquals(null, store.load())
    }

    private class FakeRelay : UnlockRelay {
        var lockScreen: Boolean? = null
        val received = mutableListOf<ByteArray>()
        override fun lockScreen() = lockScreen
        override fun relay(passwordUtf8: ByteArray): UnlockResponse {
            received += passwordUtf8.copyOf()
            passwordUtf8.fill(0)
            return UnlockResponse(UnlockResult.UNLOCKED)
        }
    }

    @Test
    fun oversizedBodyIsRefusedByAgent() {
        paired()
        val response = send("POST", "/command", "x".repeat(4097))
        assertEquals(413, response.statusCode())
        assertEquals("too_large", WakeJson.decodeFromString(ErrorBody.serializer(), response.body()).error)
        assertTrue(actions.calls.isEmpty())
    }

    private fun unlockBody(sealFor: String) =
        WakeJson.encodeToString(UnlockRequest.serializer(), sealPassword(secret, sealFor, "pässwörd".toByteArray()))

    @Test
    fun unlockRelaysDecryptedPassword() {
        paired()
        val response = send("POST", "/unlock", unlockBody("n-1"), nonce = "n-1")
        assertEquals(200, response.statusCode())
        assertEquals(UnlockResult.UNLOCKED, WakeJson.decodeFromString(UnlockResponse.serializer(), response.body()).result)
        assertContentEquals("pässwörd".toByteArray(), relay.received.single())
    }

    @Test
    fun unlockRequiresSignature() {
        paired()
        assertEquals(401, send("POST", "/unlock", unlockBody("n-1"), signWith = null, nonce = "n-1").statusCode())
        assertTrue(relay.received.isEmpty())
    }

    @Test
    fun unlockReplayRejected() {
        paired()
        assertEquals(200, send("POST", "/unlock", unlockBody("n-1"), nonce = "n-1").statusCode())
        val replay = send("POST", "/unlock", unlockBody("n-1"), nonce = "n-1")
        assertEquals(401, replay.statusCode())
        assertEquals("replay", WakeJson.decodeFromString(ErrorBody.serializer(), replay.body()).error)
        assertEquals(1, relay.received.size)
    }

    @Test
    fun unlockWithCiphertextForOtherNonceIs400() {
        paired()
        assertEquals(400, send("POST", "/unlock", unlockBody("n-1"), nonce = "n-2").statusCode())
        assertTrue(relay.received.isEmpty())
    }

    @Test
    fun statusReportsLockScreen() {
        paired()
        relay.lockScreen = true
        val response = send("GET", "/status")
        assertTrue(response.body().contains("\"lockScreen\":true"), response.body())
    }
}
