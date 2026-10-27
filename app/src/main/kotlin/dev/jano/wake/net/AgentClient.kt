package dev.jano.wake.net

import dev.jano.wake.protocol.Action
import dev.jano.wake.protocol.CancelRequest
import dev.jano.wake.protocol.CancelResponse
import dev.jano.wake.protocol.CommandRequest
import dev.jano.wake.protocol.CommandResponse
import dev.jano.wake.protocol.ErrorBody
import dev.jano.wake.protocol.H_NONCE
import dev.jano.wake.protocol.H_SIGNATURE
import dev.jano.wake.protocol.H_TIMESTAMP
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.protocol.UnlockRequest
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.newNonce
import dev.jano.wake.protocol.sealPassword
import dev.jano.wake.protocol.sign
import dev.jano.wake.protocol.staleProof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AgentUnavailable(cause: Throwable) : IOException(cause)
class AgentRejected(val error: String) : IOException("agent rejected request: $error")

/** The agent proved its clock differs from ours; we adopted it and should retry once. */
private class ClockCorrected : IOException("clock corrected")

private val JSON = "application/json".toMediaType()

/** Signed calls to the PC agent. Corrects for clock drift once if the agent says "stale". */
class AgentClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val secret: ByteArray,
    private val clock: () -> Long,
) {
    @Volatile private var offsetMs = 0L

    // The agent waits up to 15 s for Windows to answer an unlock.
    private val unlockHttp by lazy { http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build() }

    suspend fun status(): StatusResponse = call("GET", "/status", null, StatusResponse.serializer())

    suspend fun command(action: Action): CommandResponse =
        call("POST", "/command", fixed(WakeJson.encodeToString(CommandRequest.serializer(), CommandRequest(action))), CommandResponse.serializer())

    suspend fun cancel(id: String): CancelResponse =
        call("POST", "/command/cancel", fixed(WakeJson.encodeToString(CancelRequest.serializer(), CancelRequest(id))), CancelResponse.serializer())

    /** Sealed per attempt: the ciphertext is bound to that attempt's nonce. */
    suspend fun unlock(password: ByteArray): UnlockResponse =
        call("POST", "/unlock", { nonce ->
            WakeJson.encodeToString(UnlockRequest.serializer(), sealPassword(secret, nonce, password))
        }, UnlockResponse.serializer(), unlockHttp)

    private fun fixed(json: String): (String) -> String = { _ -> json }

    private suspend fun <T> call(
        method: String,
        path: String,
        body: ((nonce: String) -> String)?,
        serializer: KSerializer<T>,
        client: OkHttpClient = http,
    ): T =
        withContext(Dispatchers.IO) {
            try {
                execute(method, path, body, serializer, client)
            } catch (e: ClockCorrected) {
                try {
                    execute(method, path, body, serializer, client)
                } catch (again: ClockCorrected) {
                    throw AgentRejected("stale")
                }
            }
        }

    private fun <T> execute(
        method: String,
        path: String,
        bodyFor: ((nonce: String) -> String)?,
        serializer: KSerializer<T>,
        client: OkHttpClient,
    ): T {
        val nonce = newNonce()
        val body = bodyFor?.invoke(nonce)
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val headers = sign(secret, method, path, bytes, clock() + offsetMs, nonce)
        val request = Request.Builder()
            .url(baseUrl + path)
            .method(method, body?.toRequestBody(JSON))
            .header(H_TIMESTAMP, headers.timestampMs.toString())
            .header(H_NONCE, headers.nonce)
            .header(H_SIGNATURE, headers.signature)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AgentUnavailable(e)
        }
        response.use {
            val text = it.body.string()
            if (it.isSuccessful) return WakeJson.decodeFromString(serializer, text)
            val error = runCatching { WakeJson.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
            if (error?.error == "stale" && error.proof == staleProof(secret, headers.nonce, error.serverTimeMs)) {
                offsetMs = error.serverTimeMs - clock()
                throw ClockCorrected()
            }
            throw AgentRejected(error?.error ?: "http_${it.code}")
        }
    }
}
