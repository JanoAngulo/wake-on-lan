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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

enum class PairingError { NOT_PAIRING, BUSY, BAD_CODE, AGENT_MISMATCH, NETWORK }

class PairingFailed(val reason: PairingError) : Exception(reason.name)

/**
 * Phone side of the X25519 exchange. The phone proves the code first; the agent's proof comes
 * back only afterwards, so nothing on the wire lets a bystander test codes offline.
 */
class PairingClient(private val http: OkHttpClient) {
    suspend fun pair(baseUrl: String, code: String): ByteArray = withContext(Dispatchers.IO) {
        val keys = generateX25519()
        val phonePub = encodePublic(keys.public)

        val (startCode, startBody) = post("$baseUrl/pair/start", WakeJson.encodeToString(PairStart.serializer(), PairStart(phonePub)))
        if (startCode == 409) throw PairingFailed(if (errorOf(startBody) == "busy") PairingError.BUSY else PairingError.NOT_PAIRING)
        if (startCode != 200) throw PairingFailed(PairingError.NETWORK)
        val offer = runCatching { WakeJson.decodeFromString(PairOffer.serializer(), startBody) }
            .getOrElse { throw PairingFailed(PairingError.AGENT_MISMATCH) }
        val t = transcript(phonePub, offer.agentPublicKey)

        val finish = PairFinish(phonePub, confirmTag(code, "phone", t))
        val (finishCode, finishBody) = post("$baseUrl/pair/finish", WakeJson.encodeToString(PairFinish.serializer(), finish))
        when (finishCode) {
            200 -> Unit
            403 -> throw PairingFailed(PairingError.BAD_CODE)
            409 -> throw PairingFailed(PairingError.NOT_PAIRING)
            else -> throw PairingFailed(PairingError.NETWORK)
        }
        val confirmed = runCatching { WakeJson.decodeFromString(PairConfirmed.serializer(), finishBody) }
            .getOrElse { throw PairingFailed(PairingError.AGENT_MISMATCH) }
        val agentKnowsCode = MessageDigest.isEqual(
            confirmTag(code, "agent", t).toByteArray(),
            confirmed.agentConfirm.toByteArray(),
        )
        // The code was right but the other side can't prove it: not the real agent.
        if (!agentKnowsCode) throw PairingFailed(PairingError.AGENT_MISMATCH)
        deriveSecret(keys.private, decodePublic(offer.agentPublicKey), t)
    }

    private fun errorOf(body: String): String? =
        runCatching { WakeJson.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()

    private fun post(url: String, json: String): Pair<Int, String> = try {
        val request = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { it.code to it.body.string() }
    } catch (e: IOException) {
        throw PairingFailed(PairingError.NETWORK)
    }
}
