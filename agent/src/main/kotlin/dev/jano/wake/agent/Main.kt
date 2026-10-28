package dev.jano.wake.agent

import dev.jano.wake.agent.signin.DpapiMachineProtector
import dev.jano.wake.agent.signin.SignInKeyStore
import dev.jano.wake.agent.signin.signInDataDir
import dev.jano.wake.agent.signin.syncSignInKey
import dev.jano.wake.agent.unlock.PipeUnlockRelay
import dev.jano.wake.agent.unlock.WindowsPipeHost
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.protocol.Ports
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val AGENT_VERSION = "1.1"

private val dataDir = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Wake")
private val logFile = File(dataDir, "agent.log")

private fun log(message: String) {
    dataDir.mkdirs()
    logFile.appendText("${LocalDateTime.now()} $message\n")
}

// Shared by every Listeners instance: there's one lock screen and one pipe, whatever the LAN address.
private val unlockRelay = PipeUnlockRelay(WindowsPipeHost(), ::log)

internal fun networkAddress(ip: String, prefixLength: Int): String {
    val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
    val n = ipv4ToInt(ip) and mask
    return listOf(24, 16, 8, 0).joinToString(".") { ((n ushr it) and 0xFF).toString() }
}

/** One running pair of listeners, bound to the LAN adapter as it is right now. */
private class Listeners(nic: NicInfo, scheduler: CommandScheduler, store: SecretStore, pairing: PairingMode, onPaired: () -> Unit) {
    val ip = nic.ipv4
    private val info = {
        HostInfo(WindowsSystem.hostname(), nic.ipv4, nic.mac!!, WindowsSystem.uptimeSeconds(), AGENT_VERSION, linkKind(nic.displayName))
    }
    private val server = AgentServer(
        InetSocketAddress(nic.ipv4, Ports.HTTP), info, scheduler, store, pairing, System::currentTimeMillis, onPaired, unlockRelay,
    )
    private val discovery = DiscoveryResponder(Ports.DISCOVERY, networkAddress(nic.ipv4, nic.prefixLength) to nic.prefixLength) {
        val host = info()
        DiscoveryReply(host.hostname, host.ip, host.mac, Ports.HTTP, AGENT_VERSION)
    }

    fun start() {
        server.start()
        discovery.start()
        log("listening on $ip:${Ports.HTTP}, discovery on ${Ports.DISCOVERY}")
    }

    fun stop() {
        server.stop()
        discovery.stop()
    }
}

fun main(args: Array<String>) {
    dataDir.mkdirs()
    val lock = RandomAccessFile(File(dataDir, "agent.lock"), "rw").channel.tryLock()
    if (lock == null) {
        log("another agent is already running; exiting")
        exitProcess(0)
    }

    val roaming = File(System.getenv("APPDATA") ?: dataDir.path, "Wake").toPath()
    val store = SecretStore(roaming)
    val signInKeys = SignInKeyStore(signInDataDir(), DpapiMachineProtector)
    // Never stops the agent: sign-in after shutdown is optional and may not be installed.
    fun syncSignIn() = runCatching { syncSignInKey(store.load(), signInKeys) }.onFailure { log("sign-in key not saved: ${it.message}") }
    syncSignIn()
    val pairing = PairingMode(System::currentTimeMillis)
    val timer = Executors.newSingleThreadScheduledExecutor()
    val scheduler = CommandScheduler(WindowsPowerActions(::log), System::currentTimeMillis, timer)

    lateinit var tray: Tray
    var listeners: Listeners? = null
    val onPaired = {
        log("paired with phone")
        syncSignIn()
        tray.setPaired(true)
    }

    fun refreshListeners() {
        val nic = pickLanInterface(WindowsSystem.nics(), WindowsSystem.defaultGateway())
        if (nic == null) {
            if (listeners != null) log("LAN adapter gone; stopping listeners")
            listeners?.stop()
            listeners = null
            return
        }
        if (listeners?.ip == nic.ipv4) return
        listeners?.stop()
        val next = Listeners(nic, scheduler, store, pairing, onPaired)
        listeners = try {
            next.also { it.start() }
        } catch (e: Exception) {
            log("failed to start on ${nic.ipv4}: ${e.stackTraceToString()}")
            runCatching { next.stop() }
            null
        }
    }

    tray = Tray(
        onPair = pairing::begin,
        isPairing = { pairing.activeCode() != null },
        onQuit = {
            listeners?.stop()
            tray.remove()
            exitProcess(0)
        },
    )
    tray.show()
    tray.setPaired(store.load() != null)

    log("agent $AGENT_VERSION starting")
    // Headless pairing: `--pair` opens pairing mode at startup and writes the code to the log.
    if ("--pair" in args) log("pairing code: ${pairing.begin()}")
    // Re-check every 30 s so a new DHCP address or a late network doesn't leave the agent deaf.
    timer.scheduleWithFixedDelay({ runCatching { refreshListeners() }.onFailure { log("refresh failed: ${it.message}") } }, 0, 30, TimeUnit.SECONDS)
}
