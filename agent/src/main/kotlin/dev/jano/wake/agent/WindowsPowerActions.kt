package dev.jano.wake.agent

import com.sun.jna.Library
import com.sun.jna.Native

private interface PowrProfLib : Library {
    fun SetSuspendState(hibernate: Byte, force: Byte, wakeupEventsDisabled: Byte): Byte
}

private interface User32Lib : Library {
    fun LockWorkStation(): Boolean
}

class WindowsPowerActions(private val log: (String) -> Unit) : PowerActions {
    private val powrprof by lazy { Native.load("powrprof", PowrProfLib::class.java) }
    private val user32 by lazy { Native.load("user32", User32Lib::class.java) }

    override fun shutdown() = run("shutdown", "/s", "/f", "/t", "0")

    override fun reboot() = run("shutdown", "/r", "/f", "/t", "0")

    // hibernate = 0 so this sleeps even when hibernation is enabled.
    override fun sleep() {
        val ok = powrprof.SetSuspendState(0, 0, 0)
        log("sleep requested, result=$ok")
    }

    override fun lock() {
        log("lock requested, result=${user32.LockWorkStation()}")
    }

    private fun run(vararg command: String) {
        log("running ${command.joinToString(" ")}")
        ProcessBuilder(*command).redirectErrorStream(true).start()
    }
}
