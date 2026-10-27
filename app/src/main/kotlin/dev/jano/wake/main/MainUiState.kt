package dev.jano.wake.main

import dev.jano.wake.data.Device
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.protocol.StatusResponse
import dev.jano.wake.status.PcState
import dev.jano.wake.status.StopKind

data class MainUiState(
    val device: Device?,
    val deviceCount: Int,
    val pc: PcState,
    val agentStatus: StatusResponse?,
    val phone: PhoneNet,
    val home: Boolean,
    val haptics: Boolean,
    val message: UiMessage?,
    /** The PC is on and its agent says the lock screen is up, so Unlock replaces Lock. */
    val lockScreen: Boolean = false,
    val unlockSaved: Boolean = false,
    val unlocking: Boolean = false,
)

sealed interface UiMessage {
    data class Undo(val id: String, val kind: StopKind, val firesAtMs: Long) : UiMessage
    data class Info(val text: String) : UiMessage
    data object TooLateToUndo : UiMessage

    /** Go to the device's Fingerprint unlock setup; [reason] says why when it isn't a first-time setup. */
    data class OpenUnlockSetup(val deviceId: String, val reason: String? = null) : UiMessage
}
