package dev.jano.wake.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jano.wake.protocol.CancelRequest
import dev.jano.wake.protocol.CancelResponse
import dev.jano.wake.protocol.CommandRequest
import dev.jano.wake.protocol.CommandResponse
import dev.jano.wake.protocol.ErrorBody
import dev.jano.wake.protocol.H_NONCE
import dev.jano.wake.protocol.H_SIGNATURE
import dev.jano.wake.protocol.H_TIMESTAMP
import dev.jano.wake.protocol.PairChallenge
import dev.jano.wake.protocol.PairFinish
import dev.jano.wake.protocol.PairStart
import dev.jano.wake.protocol.RequestVerifier
import dev.jano.wake.protocol.SignedHeaders
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.protocol.VerifyResult
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.confirmTag
import dev.jano.wake.protocol.decodePublic
import dev.jano.wake.protocol.deriveSecret
import dev.jano.wake.protocol.encodePublic
import dev.jano.wake.protocol.generateX25519
import dev.jano.wake.protocol.transcript
import kotlinx.serialization.KSerializer
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AgentServer(
    private val bind: InetSocketAddress,
    private val hostInfo: () -> HostInfo,
    private val scheduler: CommandScheduler,
    private val store: SecretStore,
    private val pairing: PairingMode,
    private val clock: () -> Long,
    private val onPaired: () -> Unit,
) {
    private class PairSession(val code: String, val phonePub: String, val agentKeys: KeyPair, val transcript: ByteArray)

    private val verifier = RequestVerifier(secret = store::load, clock = clock)
    private var session: PairSession? = null
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService

    val port: Int get() = server.address.port

    fun start() {
        executor = Executors.newFixedThreadPool(4)
        server = HttpServer.create(bind, 0).apply {
            createContext("/") { exchange -> exchange.use { route(it) } }
            setExecutor(this@AgentServer.executor)
            start()
        }
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun route(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes()
        val method = exchange.requestMethod.uppercase()
        when (method to exchange.requestURI.path) {
            "GET" to "/status" -> signed(exchange, body) { status() to StatusResponse.serializer() }
            "POST" to "/command" -> signed(exchange, body) {
                val request = WakeJson.decodeFromString(CommandRequest.serializer(), body.decodeToString())
                scheduler.submit(request.action) to CommandResponse.serializer()
            }
            "POST" to "/command/cancel" -> signed(exchange, body) {
                val request = WakeJson.decodeFromString(CancelRequest.serializer(), body.decodeToString())
                CancelResponse(scheduler.cancel(request.id)) to CancelResponse.serializer()
            }
            "POST" to "/pair/start" -> pairStart(exchange, body)
            "POST" to "/pair/finish" -> pairFinish(exchange, body)
            else -> error(exchange, 404, "not_found")
        }
    }

    private fun status(): StatusResponse {
        val info = hostInfo()
        return StatusResponse(
            hostname = info.hostname,
            ip = info.ip,
            mac = info.mac,
            uptimeSeconds = info.uptimeSeconds,
            agentVersion = info.version,
            serverTimeMs = clock(),
            pending = scheduler.pending(),
        )
    }

    private fun <T> signed(exchange: HttpExchange, body: ByteArray, handler: () -> Pair<T, KSerializer<T>>) {
        val h = exchange.requestHeaders
        val timestamp = h.getFirst(H_TIMESTAMP)?.toLongOrNull()
        val nonce = h.getFirst(H_NONCE)
        val signature = h.getFirst(H_SIGNATURE)
        val result = if (timestamp == null || nonce == null || signature == null) {
            if (store.load() == null) VerifyResult.NOT_PAIRED else VerifyResult.BAD_SIGNATURE
        } else {
            verifier.verify(exchange.requestMethod, exchange.requestURI.path, body, SignedHeaders(timestamp, nonce, signature))
        }
        if (result != VerifyResult.OK) return error(exchange, 401, result.name.lowercase())

        val (value, serializer) = try {
            handler()
        } catch (e: kotlinx.serialization.SerializationException) {
            return error(exchange, 400, "bad_request")
        }
        json(exchange, 200, WakeJson.encodeToString(serializer, value))
    }

    @Synchronized
    private fun pairStart(exchange: HttpExchange, body: ByteArray) {
        val code = pairing.activeCode() ?: return error(exchange, 409, "not_pairing")
        val request = try {
            WakeJson.decodeFromString(PairStart.serializer(), body.decodeToString())
        } catch (e: kotlinx.serialization.SerializationException) {
            return error(exchange, 400, "bad_request")
        }
        val keys = generateX25519()
        val agentPub = encodePublic(keys.public)
        val t = transcript(request.phonePublicKey, agentPub)
        session = PairSession(code, request.phonePublicKey, keys, t)
        val challenge = PairChallenge(agentPub, confirmTag(code, "agent", t))
        json(exchange, 200, WakeJson.encodeToString(PairChallenge.serializer(), challenge))
    }

    @Synchronized
    private fun pairFinish(exchange: HttpExchange, body: ByteArray) {
        val current = session
        if (current == null || pairing.activeCode() != current.code) return error(exchange, 409, "not_pairing")
        val request = try {
            WakeJson.decodeFromString(PairFinish.serializer(), body.decodeToString())
        } catch (e: kotlinx.serialization.SerializationException) {
            return error(exchange, 400, "bad_request")
        }
        val expected = confirmTag(current.code, "phone", current.transcript)
        val codeMatches = MessageDigest.isEqual(expected.toByteArray(), request.phoneConfirm.toByteArray())
        session = null
        pairing.end()
        if (request.phonePublicKey != current.phonePub || !codeMatches) return error(exchange, 403, "bad_code")

        val secret = deriveSecret(current.agentKeys.private, decodePublic(current.phonePub), current.transcript)
        store.save(secret)
        onPaired()
        exchange.sendResponseHeaders(204, -1)
    }

    private fun error(exchange: HttpExchange, status: Int, code: String) =
        json(exchange, status, WakeJson.encodeToString(ErrorBody.serializer(), ErrorBody(code, clock())))

    private fun json(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
