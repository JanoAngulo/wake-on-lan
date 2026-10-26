package dev.jano.wake.net

import dev.jano.wake.protocol.ErrorBody
import dev.jano.wake.protocol.PairConfirmed
import dev.jano.wake.protocol.PairFinish
import dev.jano.wake.protocol.PairOffer
import dev.jano.wake.protocol.PairStart
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.confirmTag
import dev.jano.wake.protocol.decodePublic
import dev.jano.wake.protocol.deriveSecret
import dev.jano.wake.protocol.encodePublic
import dev.jano.wake.protocol.generateX25519
import dev.jano.wake.protocol.transcript
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A fake agent that runs the real handshake, with switches to misbehave. */
class PairingClientTest {
    private val code = "123456"
    private val agentKeys = generateX25519()
    private val agentPub = encodePublic(agentKeys.public)
    private var phonePub: String? = null
    private var startError: String? = null
    private var finishStatus = 200
    private var lieAboutTag = false
    private val server = MockWebServer()

    private fun json(status: Int, body: String) = MockResponse.Builder().code(status).body(body).build()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val text = request.body?.utf8() ?: ""
                return when (request.url.encodedPath) {
                    "/pair/start" -> startError?.let { json(409, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody(it, 0))) } ?: run {
                        phonePub = WakeJson.decodeFromString(PairStart.serializer(), text).phonePublicKey
                        json(200, WakeJson.encodeToString(PairOffer.serializer(), PairOffer(agentPub)))
                    }
                    "/pair/finish" -> {
                        if (finishStatus != 200) return json(finishStatus, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody("bad_code", 0)))
                        val finish = WakeJson.decodeFromString(PairFinish.serializer(), text)
                        val t = transcript(finish.phonePublicKey, agentPub)
                        val tag = confirmTag(if (lieAboutTag) "999999" else code, "agent", t)
                        json(200, WakeJson.encodeToString(PairConfirmed.serializer(), PairConfirmed(tag)))
                    }
                    else -> json(404, "")
                }
            }
        }
        server.start()
    }

    private val client = PairingClient(OkHttpClient())
    private val base = server.url("/").toString().trimEnd('/')

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun successDerivesSameSecretAsAgent(): Unit = runBlocking {
        val secret = client.pair(base, code)
        val t = transcript(phonePub!!, agentPub)
        assertContentEquals(deriveSecret(agentKeys.private, decodePublic(phonePub!!), t), secret)
    }

    @Test
    fun wrongCodeIsBadCode() {
        finishStatus = 403
        val error = assertFailsWith<PairingFailed> { runBlocking { client.pair(base, "000000") } }
        assertEquals(PairingError.BAD_CODE, error.reason)
    }

    @Test
    fun agentThatCantProveTheCodeIsRejected() {
        lieAboutTag = true
        val error = assertFailsWith<PairingFailed> { runBlocking { client.pair(base, code) } }
        assertEquals(PairingError.AGENT_MISMATCH, error.reason)
    }

    @Test
    fun anotherPhonePairingIsBusy() {
        startError = "busy"
        val error = assertFailsWith<PairingFailed> { runBlocking { client.pair(base, code) } }
        assertEquals(PairingError.BUSY, error.reason)
    }

    @Test
    fun notPairingIsNotPairing() {
        startError = "not_pairing"
        val error = assertFailsWith<PairingFailed> { runBlocking { client.pair(base, code) } }
        assertEquals(PairingError.NOT_PAIRING, error.reason)
    }
}
