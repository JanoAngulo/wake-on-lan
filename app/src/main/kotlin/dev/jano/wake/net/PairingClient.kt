package dev.jano.wake.net

import dev.jano.wake.protocol.PairChallenge
import dev.jano.wake.protocol.PairFinish
import dev.jano.wake.protocol.PairStart
import dev.jano.wake.protocol.WakeJson
import dev.jano.wake.protocol.confirmTag
import dev.jano.wake.protocol.decodePublic
import dev.jano.wake.protocol.deriveSecret
import dev.jano.wake.protocol.encodePublic
import dev.jano.wake.protocol.generateX25519
import dev.jano.wake.protocol.transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

enum class PairingError { NOT_PAIRING, BAD_CODE, AGENT_MISMATCH, NETWORK }

class PairingFailed(val reason: PairingError) : Exception(reason.name)

/** Phone side of the X25519 exchange; the 6-digit code authenticates both directions. */
class PairingClient(private val http: OkHttpClient) {
    suspend fun pair(baseUrl: String, code: String): ByteArray = withContext(Dispatchers.IO) {
        val keys = generateX25519()
        val phonePub = encodePublic(keys.public)

        val (startCode, startBody) = post("$baseUrl/pair/start", WakeJson.encodeToString(PairStart.serializer(), PairStart(phonePub)))
        when (startCode) {
            200 -> Unit
            409 -> throw PairingFailed(PairingError.NOT_PAIRING)
            else -> throw PairingFailed(PairingError.NETWORK)
        }
        val challenge = runCatching { WakeJson.decodeFromString(PairChallenge.serializer(), startBody) }
            .getOrElse { throw PairingFailed(PairingError.AGENT_MISMATCH) }
        val t = transcript(phonePub, challenge.agentPublicKey)

        // A typo is far likelier than an impostor, so a mismatched agent tag reads as a wrong code.
        val agentOk = MessageDigest.isEqual(
            confirmTag(code, "agent", t).toByteArray(),
            challenge.agentConfirm.toByteArray(),
        )
        if (!agentOk) throw PairingFailed(PairingError.BAD_CODE)

        val finish = PairFinish(phonePub, confirmTag(code, "phone", t))
        val (finishCode, _) = post("$baseUrl/pair/finish", WakeJson.encodeToString(PairFinish.serializer(), finish))
        when (finishCode) {
            204 -> deriveSecret(keys.private, decodePublic(challenge.agentPublicKey), t)
            403 -> throw PairingFailed(PairingError.BAD_CODE)
            409 -> throw PairingFailed(PairingError.NOT_PAIRING)
            else -> throw PairingFailed(PairingError.NETWORK)
        }
    }

    private fun post(url: String, json: String): Pair<Int, String> = try {
        val request = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { it.code to it.body.string() }
    } catch (e: IOException) {
        throw PairingFailed(PairingError.NETWORK)
    }
}
