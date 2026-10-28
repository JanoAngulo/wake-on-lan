package dev.jano.wake.main

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.jano.wake.data.Device
import dev.jano.wake.data.DeviceRepository
import dev.jano.wake.data.KeystoreUnlockPasswords
import dev.jano.wake.data.SecretVault
import dev.jano.wake.data.Secrets
import dev.jano.wake.data.UnlockPasswords
import dev.jano.wake.net.AgentClient
import dev.jano.wake.net.PairingClient
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.net.ProbeResult
import dev.jano.wake.net.discoverAgents
import dev.jano.wake.net.probeHost
import dev.jano.wake.net.readPhoneNet
import dev.jano.wake.net.sendWake
import dev.jano.wake.protocol.Action
import dev.jano.wake.protocol.CancelResponse
import dev.jano.wake.protocol.CommandResponse
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.protocol.MacAddress
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.protocol.Ports
import dev.jano.wake.protocol.SignInStatus
import dev.jano.wake.protocol.UnlockResponse
import dev.jano.wake.protocol.signInKey
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun interface HostProber { suspend fun probe(ip: String, ports: List<Int>): ProbeResult }

interface AgentApi {
    suspend fun status(): StatusResponse
    suspend fun command(action: Action): CommandResponse
    suspend fun cancel(id: String): CancelResponse
    suspend fun unlock(password: ByteArray): UnlockResponse
}

fun interface AgentApiFactory { fun create(device: Device, secret: ByteArray): AgentApi }

/** The PC's pre-login sign-in program: answers only while the sign-in or lock screen is up. */
interface SignInApi {
    suspend fun status(): SignInStatus
    suspend fun unlock(password: ByteArray): UnlockResponse
}

/** [secret] is the pairing secret; the real factory derives the sign-in key from it. */
fun interface SignInApiFactory { fun create(device: Device, secret: ByteArray): SignInApi }

fun interface Discoverer { suspend fun discover(broadcast: String): List<DiscoveryReply> }

fun interface PhoneNetReader { fun read(): PhoneNet }

fun interface Waker { suspend fun send(mac: MacAddress, broadcast: String, port: Int) }

fun interface Pairer { suspend fun pair(baseUrl: String, code: String): ByteArray }

/** Everything the screens need, with real implementations in [create] and fakes in tests. */
class AppGraph(
    val repository: DeviceRepository,
    val secrets: Secrets,
    val prober: HostProber,
    val agents: AgentApiFactory,
    val signIns: SignInApiFactory,
    val discoverer: Discoverer,
    val phoneNet: PhoneNetReader,
    val waker: Waker,
    val pairer: Pairer,
    val unlockPasswords: UnlockPasswords,
) {
    companion object {
        private val Context.wakeStore by preferencesDataStore("wake")

        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): AppGraph {
            val http = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            return AppGraph(
                repository = DeviceRepository(context.wakeStore),
                secrets = SecretVault(context),
                prober = HostProber { ip, ports -> probeHost(ip, ports) },
                agents = AgentApiFactory { device, secret ->
                    val client = AgentClient(http, "http://${device.lastIp}:${device.agentPort}", secret, System::currentTimeMillis)
                    object : AgentApi {
                        override suspend fun status() = client.status()
                        override suspend fun command(action: Action) = client.command(action)
                        override suspend fun cancel(id: String) = client.cancel(id)
                        override suspend fun unlock(password: ByteArray) = client.unlock(password)
                    }
                },
                signIns = SignInApiFactory { device, secret ->
                    val client = AgentClient(http, "http://${device.lastIp}:${Ports.SIGNIN}", signInKey(secret), System::currentTimeMillis)
                    object : SignInApi {
                        override suspend fun status() = client.signInStatus()
                        override suspend fun unlock(password: ByteArray) = client.signInUnlock(password)
                    }
                },
                discoverer = Discoverer { broadcast -> discoverAgents(broadcast, timeoutMs = 1_500) },
                phoneNet = PhoneNetReader { readPhoneNet(context) },
                waker = Waker { mac, broadcast, port -> sendWake(mac, broadcast, port) },
                pairer = PairingClient(http).let { client -> Pairer { url, code -> client.pair(url, code) } },
                unlockPasswords = KeystoreUnlockPasswords(context),
            )
        }
    }
}
