package dev.jano.wake.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jano.wake.main.MainUiState
import dev.jano.wake.net.subnetOf
import dev.jano.wake.status.PcState
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeType
import kotlinx.coroutines.delay

data class MainActions(
    val wake: () -> Unit,
    val shutdown: () -> Unit,
    val reboot: () -> Unit,
    val sleep: () -> Unit,
    val lock: () -> Unit,
    val undo: () -> Unit,
    val dismissMessage: () -> Unit,
    val openSettings: () -> Unit,
    val openSetupHelp: () -> Unit,
    val switchDevice: () -> Unit,
    val unlock: () -> Unit,
)

@Composable
fun MainScreen(state: MainUiState, actions: MainActions, nowMs: () -> Long = System::currentTimeMillis) {
    val reducedMotion = rememberReducedMotion()
    val haptics = rememberHaptics(state.haptics)
    val name = state.device?.name ?: "PC"
    val now by produceState(nowMs(), state.pc) {
        while (true) {
            value = nowMs()
            delay(1_000)
        }
    }
    val enabled = Presentation.commandsEnabled(state)

    // TalkBack hears each real state change once ("MY-PC is on"), never the ticking timer.
    val view = LocalView.current
    val announcement = Presentation.announcement(state)
    var lastAnnounced by remember { mutableStateOf(announcement) }
    LaunchedEffect(announcement) {
        if (announcement != null && announcement != lastAnnounced) view.announceForAccessibility(announcement)
        lastAnnounced = announcement
    }

    Box(Modifier.fillMaxSize().background(WakeColors.Background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Header(
                deviceName = name,
                showChevron = state.deviceCount >= 2,
                pc = state.pc,
                caption = Presentation.caption(state, now),
                heroDescription = Presentation.heroDescription(state),
                reducedMotion = reducedMotion,
                haptics = haptics,
                onWake = actions.wake,
                onOpenSettings = actions.openSettings,
                onSwitchDevice = actions.switchDevice,
                locked = state.lockScreen,
            )

            StateNotice(state, name, actions)

            KeyValueCard(Modifier.padding(top = 20.dp)) {
                KeyValueRow("IP address", WakeIcons.Globe) { ValueText(state.device?.lastIp ?: "Unknown") }
                RowDivider()
                KeyValueRow("Response", WakeIcons.Pulse) { ResponseValue(state.pc) }
            }

            // Safe actions first, then the two forced ones grouped together with Shut down last.
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TapButton("Sleep", WakeIcons.Sleep, enabled, reducedMotion, haptics, actions.sleep, Modifier.weight(1f))
                    // While the lock screen is up, Lock has nothing to do and Unlock takes its place (a tap:
                    // the fingerprint is already the deliberate step). After a cold boot the sign-in screen counts too.
                    if (state.lockScreen || state.signInScreen || state.unlocking) {
                        TapButton(
                            if (state.unlocking) "Unlocking…" else "Unlock", WakeIcons.Fingerprint, Presentation.unlockEnabled(state),
                            reducedMotion, haptics,
                            actions.unlock, Modifier.weight(1f), primary = true, busy = state.unlocking,
                        )
                    } else {
                        TapButton("Lock", WakeIcons.Lock, enabled, reducedMotion, haptics, actions.lock, Modifier.weight(1f))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoldButton(
                        label = "Reboot", icon = WakeIcons.Reboot, note = "Hold 1s",
                        tapHint = "Hold to reboot", enabled = enabled, height = 60.dp,
                        reducedMotion = reducedMotion, haptics = haptics,
                        confirmQuestion = "Force reboot $name?", confirmLabel = "Reboot", onConfirm = actions.reboot,
                    )
                    HoldButton(
                        label = "Shut down", icon = WakeIcons.Power, note = Presentation.shutdownNote(state),
                        tapHint = "Hold to shut down", enabled = enabled, height = 68.dp,
                        reducedMotion = reducedMotion, haptics = haptics,
                        confirmQuestion = "Force shut down $name?", confirmLabel = "Shut down", onConfirm = actions.shutdown,
                    )
                }
            }

            NetworkSection(state)

            Spacer(Modifier.height(96.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }

        MessageSnackbar(
            message = state.message,
            deviceName = name,
            reducedMotion = reducedMotion,
            onUndo = actions.undo,
            onDismiss = actions.dismissMessage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun ResponseValue(pc: PcState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (pc) {
            is PcState.On -> {
                Pill("${pc.latencyMs} ms", PillKind.OK)
                Text(if (pc.steady) "steady" else "slow", style = WakeType.RowSecondary, color = WakeColors.OnSurfaceMuted)
            }
            is PcState.Off -> {
                Pill("Off", PillKind.NEUTRAL)
                if (pc.sinceMs != null) Text("since ${Presentation.time(pc.sinceMs)}", style = WakeType.RowSecondary, color = WakeColors.OnSurfaceMuted)
            }
            is PcState.Waking -> Pill("Waiting", PillKind.WARN)
            is PcState.Stopping -> Pill("Still answering", PillKind.WARN)
            is PcState.DidntWake -> {
                Pill("Off", PillKind.NEUTRAL)
                Text("no reply", style = WakeType.RowSecondary, color = WakeColors.OnSurfaceMuted)
            }
            is PcState.Away -> Pill("Unknown", PillKind.NEUTRAL)
            PcState.Checking -> Pill("Checking", PillKind.NEUTRAL)
        }
    }
}

@Composable
private fun StateNotice(state: MainUiState, name: String, actions: MainActions) {
    val pc = state.pc
    val notice: (@Composable () -> Unit)? = when {
        pc is PcState.DidntWake -> {
            {
                Notice(
                    WakeIcons.Alert,
                    "$name didn't wake up. It may be unplugged, or Wake-on-LAN is off in the BIOS.",
                    listOf(NoticeAction("Retry", actions.wake), NoticeAction("Check PC setup", actions.openSetupHelp)),
                )
            }
        }
        pc is PcState.Away -> {
            {
                val text = if (state.phone.onWifi) {
                    "You're on a different Wi-Fi. Join your home network to wake or control $name."
                } else {
                    "You're on mobile data. Join your home Wi-Fi to wake or control $name."
                }
                Notice(WakeIcons.Wifi, text)
            }
        }
        pc is PcState.On && !pc.agent && state.device?.paired == true -> {
            { Notice(WakeIcons.Plug, "$name is on but the agent isn't responding. Log in on the PC to enable commands.") }
        }
        else -> null
    }
    if (notice != null) {
        Spacer(Modifier.height(16.dp))
        notice()
    }
}

@Composable
private fun NetworkSection(state: MainUiState) {
    val device = state.device
    val phone = state.phone
    val phoneSubnet = if (phone.ip != null && phone.prefixLength != null) subnetOf(phone.ip, phone.prefixLength) else null
    val pc = state.pc

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 20.dp, top = 32.dp, bottom = 10.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Network", style = WakeType.Section, color = WakeColors.OnSurfaceMuted, modifier = Modifier.semantics { heading() })
        if (state.home) {
            Pill("Same subnet", PillKind.OK, icon = WakeIcons.Check)
        } else {
            Pill(if (phone.onWifi) "Different subnet" else "Not on Wi-Fi", PillKind.WARN, icon = WakeIcons.Cross)
        }
    }
    KeyValueCard {
        KeyValueRow("Subnet") { ValueText(device?.subnet ?: phoneSubnet ?: "Unknown") }
        RowDivider()
        KeyValueRow("This phone") { ValueText(phone.ip ?: "Not connected") }
        RowDivider()
        KeyValueRow("PC link") { ValueText(state.agentStatus?.link ?: "Unknown") }
        RowDivider()
        KeyValueRow("PC MAC") { ValueText(device?.mac ?: "Unknown") }
        RowDivider()
        KeyValueRow("Wake target") {
            Column {
                ValueText(device?.broadcast ?: "Unknown")
                Text("port ${device?.port ?: 9}", style = WakeType.RowSecondary, color = WakeColors.OnSurfaceMuted)
            }
        }
        RowDivider()
        KeyValueRow("PC agent") {
            when {
                device?.paired != true -> Pill("Not paired", PillKind.NEUTRAL)
                pc is PcState.On && pc.agent -> Pill("Connected", PillKind.OK, dot = true)
                pc is PcState.Away -> Pill("Unreachable", PillKind.NEUTRAL)
                else -> Pill("Offline", PillKind.NEUTRAL)
            }
        }
    }
}
