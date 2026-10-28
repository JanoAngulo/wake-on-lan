package dev.jano.wake.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jano.wake.protocol.CancelRequest
import dev.jano.wake.protocol.CancelResponse
import dev.jano.wake.protocol.CommandRequest
import dev.jano.wake.protocol.CommandResponse
import dev.jano.wake.protocol.H_NONCE
import dev.jano.wake.protocol.PairConfirmed
import dev.jano.wake.protocol.PairOffer
import dev.jano.wake.protocol.PairFinish
import dev.jano.wake.protocol.PairStart
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.confirmTag
import dev.jano.wake.protocol.decodePublic
import dev.jano.wake.protocol.deriveSecret
import dev.jano.wake.protocol.encodePublic
import dev.jano.wake.protocol.generateX25519
import dev.jano.wake.protocol.transcript
import dev.jano.wake.protocol.UnlockRequest
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.openPassword
import dev.jano.wake.agent.unlock.UnlockRelay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
    private val unlock: UnlockRelay,
) {
    private class PairSession(val code: String, val phonePub: String, val agentKeys: KeyPair, val transcript: ByteArray)

    private val exchanges = SignedExchange(store::load, clock)
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
        val body = readCapped(exchange) ?: return error(exchange, 413, "too_large")
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
            "POST" to "/unlock" -> signed(exchange, body) {
                val request = WakeJson.decodeFromString(UnlockRequest.serializer(), body.decodeToString())
                // Signature already verified, so the nonce header is present and fresh.
                val nonce = exchange.requestHeaders.getFirst(H_NONCE)!!
                val password = openPassword(store.load()!!, nonce, request)
                    ?: throw SerializationException("unlock password didn't open")
                unlock.relay(password) to UnlockResponse.serializer()
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
            link = info.link,
            lockScreen = unlock.lockScreen(),
        )
    }

    private fun <T> signed(exchange: HttpExchange, body: ByteArray, handler: () -> Pair<T, KSerializer<T>>) =
        exchanges.handle(exchange, body, handler)

    @Synchronized
    private fun pairStart(exchange: HttpExchange, body: ByteArray) {
        val code = pairing.activeCode() ?: return error(exchange, 409, "not_pairing")
        val request = try {
            WakeJson.decodeFromString(PairStart.serializer(), body.decodeToString())
        } catch (e: kotlinx.serialization.SerializationException) {
            return error(exchange, 400, "bad_request")
        }
        // The first phone to start owns this pairing window; nobody else can reset it.
        if (session?.code == code) return error(exchange, 409, "busy")
        val keys = generateX25519()
        val agentPub = encodePublic(keys.public)
        session = PairSession(code, request.phonePublicKey, keys, transcript(request.phonePublicKey, agentPub))
        json(exchange, 200, WakeJson.encodeToString(PairOffer.serializer(), PairOffer(agentPub)))
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
        val confirmed = PairConfirmed(confirmTag(current.code, "agent", current.transcript))
        json(exchange, 200, WakeJson.encodeToString(PairConfirmed.serializer(), confirmed))
    }

    private fun error(exchange: HttpExchange, status: Int, code: String) = exchanges.error(exchange, status, code)

    private fun json(exchange: HttpExchange, status: Int, text: String) = exchanges.json(exchange, status, text)
}
