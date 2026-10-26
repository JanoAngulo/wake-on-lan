package dev.jano.wake.agent

interface PowerActions {
    fun shutdown()
    fun reboot()
    fun sleep()
    fun lock()
}
