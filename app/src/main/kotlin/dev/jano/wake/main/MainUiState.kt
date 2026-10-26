package dev.jano.wake.main

import dev.jano.wake.data.Device
import dev.jano.wake.net.PhoneNet
import dev.jano.wake.protocol.PendingCommand
import dev.jano.wake.status.PcState
import dev.jano.wake.status.StopKind

data class MainUiState(
    val device: Device?,
    val deviceCount: Int,
    val pc: PcState,
    val pending: PendingCommand?,
    val phone: PhoneNet,
    val home: Boolean,
    val haptics: Boolean,
    val message: UiMessage?,
)

sealed interface UiMessage {
    data class Undo(val id: String, val kind: StopKind, val firesAtMs: Long) : UiMessage
    data class Info(val text: String) : UiMessage
    data object TooLateToUndo : UiMessage
}
