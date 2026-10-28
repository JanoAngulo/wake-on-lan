package dev.jano.wake.agent.signin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jano.wake.agent.SignedExchange
import dev.jano.wake.agent.readCapped
import dev.jano.wake.agent.unlock.UnlockRelay
import dev.jano.wake.protocol.H_NONCE
import dev.jano.wake.protocol.PATH_SIGNIN_STATUS
import dev.jano.wake.protocol.PATH_SIGNIN_UNLOCK
import dev.jano.wake.protocol.SignInStatus
import dev.jano.wake.protocol.UnlockRequest
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.openPassword
import kotlinx.serialization.SerializationException
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pre-login listener: two routes, signed with the sign-in key, nothing else. It never holds the
 * pairing secret, so it can't take commands or pair.
 */
class SignInServer(
    private val bind: InetSocketAddress,
    private val mac: () -> String,
    private val keys: () -> ByteArray?,
    private val clock: () -> Long,
    private val unlock: UnlockRelay,
) {
    private val exchanges = SignedExchange(keys, clock)
    private val active = AtomicInteger()
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService

    val port: Int get() = server.address.port

    fun start() {
        executor = Executors.newFixedThreadPool(2)
        server = HttpServer.create(bind, 0).apply {
            createContext("/") { exchange ->
                active.incrementAndGet()
                try {
                    exchange.use { route(it) }
                } finally {
                    active.decrementAndGet()
                }
            }
            setExecutor(this@SignInServer.executor)
            start()
        }
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    /** True while a request is being handled, so the gate never closes the port under one. */
    fun busy(): Boolean = active.get() > 0

    private fun route(exchange: HttpExchange) {
        val body = readCapped(exchange) ?: return exchanges.error(exchange, 413, "too_large")
        when (exchange.requestMethod.uppercase() to exchange.requestURI.path) {
            "GET" to PATH_SIGNIN_STATUS -> exchanges.handle(exchange, body) {
                SignInStatus(mac(), clock(), unlock.lockScreen() == true) to SignInStatus.serializer()
            }
            "POST" to PATH_SIGNIN_UNLOCK -> exchanges.handle(exchange, body) {
                val request = WakeJson.decodeFromString(UnlockRequest.serializer(), body.decodeToString())
                // Signature already verified, so the nonce header is present and fresh.
                val nonce = exchange.requestHeaders.getFirst(H_NONCE)!!
                val password = openPassword(keys()!!, nonce, request)
                    ?: throw SerializationException("unlock password didn't open")
                unlock.relay(password) to UnlockResponse.serializer()
            }
            else -> exchanges.error(exchange, 404, "not_found")
        }
    }
}
