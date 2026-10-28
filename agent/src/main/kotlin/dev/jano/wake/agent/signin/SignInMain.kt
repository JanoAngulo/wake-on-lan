package dev.jano.wake.agent.signin

import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import dev.jano.wake.agent.unlock.WindowsPipeHost
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.system.exitProcess

// Spike (plan Task 1): proves the startup task runs before sign-in, under its own task SID, and sees the pipe.

fun signInLogDir(): Path = Path(System.getenv("ProgramData") ?: "C:\\ProgramData", "Wake", "logs")

private val logFile = File(signInLogDir().toFile(), "signin.log")

private fun log(message: String) {
    logFile.appendText("${LocalDateTime.now()} $message\n")
}

/** "user=<sid> groups=<sid>,<sid>,…" for this process's token. */
private fun tokenSids(): String {
    val token = WinNT.HANDLEByReference()
    if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
        return "token unreadable: ${Kernel32.INSTANCE.GetLastError()}"
    }
    return try {
        val user = Advapi32Util.getTokenAccount(token.value).sidString
        val groups = Advapi32Util.getTokenGroups(token.value).joinToString(",") { it.sidString }
        "user=$user groups=$groups"
    } finally {
        Kernel32.INSTANCE.CloseHandle(token.value)
    }
}

fun main(args: Array<String>) {
    val dir = signInLogDir().toFile()
    if (!dir.isDirectory) exitProcess(1)  // the installer creates it with the right ACL; never create it here
    val lock = RandomAccessFile(File(dir, "signin.lock"), "rw").channel.tryLock() ?: exitProcess(0)

    log("started ${tokenSids()}")
    val host = WindowsPipeHost()
    var last: Boolean? = null
    val timer = Executors.newSingleThreadScheduledExecutor()
    timer.scheduleWithFixedDelay({
        runCatching {
            val now = host.pipeExists()
            if (now != last) log("pipe=$now")
            last = now
        }.onFailure { log("pipe check failed: ${it.message}") }
    }, 0, 1, TimeUnit.SECONDS)
    Runtime.getRuntime().addShutdownHook(Thread { lock.release() })
}
