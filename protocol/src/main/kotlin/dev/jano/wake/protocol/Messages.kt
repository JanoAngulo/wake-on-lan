package dev.jano.wake.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val WakeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

const val DISCOVERY_PROBE = "WAKE-DISCOVER-1"

@Serializable
enum class Action {
    @SerialName("shutdown") SHUTDOWN,
    @SerialName("reboot") REBOOT,
    @SerialName("sleep") SLEEP,
    @SerialName("lock") LOCK,
}

@Serializable
data class PendingCommand(val id: String, val action: Action, val firesAtEpochMs: Long)

@Serializable
data class StatusResponse(
    val hostname: String,
    val ip: String,
    val mac: String,
    val uptimeSeconds: Long,
    val agentVersion: String,
    val serverTimeMs: Long,
    val pending: PendingCommand? = null,
)

@Serializable
data class CommandRequest(val action: Action)

@Serializable
data class CommandResponse(val id: String? = null, val firesAtEpochMs: Long? = null)

@Serializable
data class CancelRequest(val id: String)

@Serializable
data class CancelResponse(val cancelled: Boolean)

@Serializable
data class DiscoveryReply(
    val hostname: String,
    val ip: String,
    val mac: String,
    val httpPort: Int,
    val agentVersion: String,
)

@Serializable
data class PairStart(val phonePublicKey: String)

@Serializable
data class PairChallenge(val agentPublicKey: String, val agentConfirm: String)

@Serializable
data class PairFinish(val phonePublicKey: String, val phoneConfirm: String)

@Serializable
data class ErrorBody(val error: String, val serverTimeMs: Long)
