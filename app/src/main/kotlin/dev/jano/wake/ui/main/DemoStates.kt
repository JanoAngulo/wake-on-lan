package dev.jano.wake.ui.main

import dev.jano.wake.data.Device
import dev.jano.wake.main.MainUiState
import dev.jano.wake.main.UiMessage
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.status.PcState
import dev.jano.wake.status.StopKind

/** Fixed states for on-device screenshots (debug builds only, via `--es demoState <name>`). */
object DemoStates {
    fun of(name: String, now: Long): MainUiState? {
        val device = Device("demo", "MY-PC", "0A:1B:2C:3D:4E:5F", lastIp = "192.168.1.14", subnet = "192.168.1.0/24", paired = true)
        val home = PhoneNet(true, "192.168.1.23", 24)
        val status = StatusResponse("MY-PC", "192.168.1.14", device.mac, 8_040, "1.0", now, link = "Ethernet")
        val on = PcState.On(uptimeSec = 8_040, latencyMs = 2, steady = true, agent = true)
        fun state(pc: PcState, phone: PhoneNet = home, agent: StatusResponse? = status, message: UiMessage? = null) =
            MainUiState(device, 1, pc, agent, phone, phone == home, haptics = true, message = message)

        return when (name) {
            "off" -> state(PcState.Off(now - 39 * 60_000), agent = null)
            "waking" -> state(PcState.Waking(now - 18_000), agent = null)
            "on" -> state(on)
            "undo" -> state(on, message = UiMessage.Undo("c1", StopKind.SHUTDOWN, now + 3_000))
            "stopping" -> state(PcState.Stopping(StopKind.SHUTDOWN, now))
            "didntwake" -> state(PcState.DidntWake(now), agent = null)
            "away" -> state(PcState.Away(PcState.Off(null)), phone = PhoneNet(false, "10.12.0.4", 16), agent = null)
            "noagent" -> state(on.copy(latencyMs = 3, uptimeSec = null, agent = false), agent = null)
            else -> null
        }
    }
}
