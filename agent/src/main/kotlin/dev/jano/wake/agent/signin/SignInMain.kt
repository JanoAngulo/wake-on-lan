package dev.jano.wake.agent.signin

import dev.jano.wake.agent.AGENT_VERSION
import dev.jano.wake.agent.NicInfo
import dev.jano.wake.agent.SignedExchange
import dev.jano.wake.agent.limitHttpServerTimes
import dev.jano.wake.agent.WindowsSystem
import dev.jano.wake.agent.pickLanInterface
import dev.jano.wake.agent.unlock.PipeUnlockRelay
import dev.jano.wake.agent.unlock.UnlockRelay
import dev.jano.wake.agent.unlock.WindowsPipeHost
import dev.jano.wake.protocol.Ports
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.system.exitProcess

// The pre-login sign-in program. A startup task runs it as LOCAL SERVICE with its own task SID, before
// anyone signs in. It listens on Ports.SIGNIN only while the lock or sign-in screen is up.

fun signInLogDir(): Path = Path(System.getenv("ProgramData") ?: "C:\\ProgramData", "Wake", "logs")

private val logFile = File(signInLogDir().toFile(), "signin.log")

private fun log(message: String) {
    logFile.appendText("${LocalDateTime.now()} $message\n")
}

fun main(args: Array<String>) {
    limitHttpServerTimes()
    val dir = signInLogDir().toFile()
    if (!dir.isDirectory) exitProcess(1)  // the installer creates it with the right ACL; never create it here
    val lock = RandomAccessFile(File(dir, "signin.lock"), "rw").channel.tryLock() ?: exitProcess(0)

    log("sign-in $AGENT_VERSION starting")
    val keys = SignInKeyStore(signInDataDir(), DpapiMachineProtector)
    val host = WindowsPipeHost()
    val pipeRelay = PipeUnlockRelay(host, ::log)
    // Results only: the password never reaches the log.
    val relay = object : UnlockRelay by pipeRelay {
        override fun relay(passwordUtf8: ByteArray) = pipeRelay.relay(passwordUtf8).also { log("unlock: ${it.result}") }
    }

    // Everything below runs on this one thread, so the gate and the adapter refresh never race.
    val timer = Executors.newSingleThreadScheduledExecutor()
    var nic: NicInfo? = null
    var server: SignInServer? = null
    var lastPipe: Boolean? = null
    // One for every server this program opens, so a nonce seen before a close is still refused after a reopen.
    val exchanges = SignedExchange(keys::load, System::currentTimeMillis)
    lateinit var gate: SignInGate

    fun refreshAdapter() {
        val next = pickLanInterface(WindowsSystem.nics(), WindowsSystem.defaultGateway())
        if (next?.ipv4 == nic?.ipv4) return
        log(if (next == null) "LAN adapter gone" else "LAN adapter ${next.ipv4}")
        nic = next
        gate.reset()  // reopens on the new address at the next tick, if the screen is still up
    }

    fun openServer() {
        // At boot this program usually starts before the network. Look again now rather than waiting for the
        // 30 s refresh, so the port opens within a second of the network coming up.
        if (nic == null) refreshAdapter()
        val lan = nic ?: error("no LAN adapter")
        val next = SignInServer(InetSocketAddress(lan.ipv4, Ports.SIGNIN), { lan.mac!! }, keys::load, System::currentTimeMillis, relay, exchanges)
        next.start()
        server = next
        log("open on ${lan.ipv4}:${Ports.SIGNIN}")
    }

    fun closeServer() {
        server?.stop()
        server = null
        log("closed")
    }

    gate = SignInGate(
        pipeExists = {
            host.pipeExists().also {
                if (it != lastPipe) log("pipe=$it")
                lastPipe = it
            }
        },
        inFlight = { server?.busy() == true },
        clock = System::currentTimeMillis,
        open = ::openServer,
        close = ::closeServer,
        log = ::log,
    )

    timer.scheduleWithFixedDelay({ runCatching { refreshAdapter() }.onFailure { log("refresh failed: ${it.message}") } }, 0, 30, TimeUnit.SECONDS)
    timer.scheduleWithFixedDelay({ runCatching { gate.tick() }.onFailure { log("tick failed: ${it.message}") } }, 0, 1, TimeUnit.SECONDS)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { server?.stop() }
        lock.release()
    })
}
