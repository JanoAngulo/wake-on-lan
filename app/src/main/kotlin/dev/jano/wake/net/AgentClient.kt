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
import dev.jano.wake.protocol.WakeJson
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

    suspend fun status(): StatusResponse = call("GET", "/status", null, StatusResponse.serializer())

    suspend fun command(action: Action): CommandResponse =
        call("POST", "/command", WakeJson.encodeToString(CommandRequest.serializer(), CommandRequest(action)), CommandResponse.serializer())

    suspend fun cancel(id: String): CancelResponse =
        call("POST", "/command/cancel", WakeJson.encodeToString(CancelRequest.serializer(), CancelRequest(id)), CancelResponse.serializer())

    private suspend fun <T> call(method: String, path: String, body: String?, serializer: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            try {
                execute(method, path, body, serializer)
            } catch (e: ClockCorrected) {
                try {
                    execute(method, path, body, serializer)
                } catch (again: ClockCorrected) {
                    throw AgentRejected("stale")
                }
            }
        }

    private fun <T> execute(method: String, path: String, body: String?, serializer: KSerializer<T>): T {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val headers = sign(secret, method, path, bytes, clock() + offsetMs)
        val request = Request.Builder()
            .url(baseUrl + path)
            .method(method, body?.toRequestBody(JSON))
            .header(H_TIMESTAMP, headers.timestampMs.toString())
            .header(H_NONCE, headers.nonce)
            .header(H_SIGNATURE, headers.signature)
            .build()

        val response = try {
            http.newCall(request).execute()
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
