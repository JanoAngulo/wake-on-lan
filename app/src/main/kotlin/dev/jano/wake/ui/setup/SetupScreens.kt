package dev.jano.wake.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jano.wake.data.Device
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.ui.common.BodyText
import dev.jano.wake.ui.common.FormField
import dev.jano.wake.ui.common.ListRow
import dev.jano.wake.ui.common.PrimaryButton
import dev.jano.wake.ui.common.ScreenScaffold
import dev.jano.wake.ui.common.SecondaryButton
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.main.KeyValueCard
import dev.jano.wake.ui.main.Notice
import dev.jano.wake.ui.main.RowDivider
import dev.jano.wake.ui.theme.ManropeFamily
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType

@Composable
fun FindPcScreen(
    state: SetupUiState,
    onFind: () -> Unit,
    onPick: (DiscoveryReply) -> Unit,
    onManual: () -> Unit,
    onGuide: () -> Unit,
    onBack: (() -> Unit)?,
) {
    LaunchedEffect(Unit) { if (!state.searched && !state.searching) onFind() }
    ScreenScaffold("Find my PC", onBack) {
        BodyText("Wake looks for the Wake agent on your home network. Make sure it's running on your PC: the green power icon in the system tray.")
        KeyValueCard(inset = 0.dp) { ListRow("New here?", "See how to set up the PC and pair this phone", WakeIcons.Wifi, onGuide) }
        if (state.found.isNotEmpty()) {
            KeyValueCard(inset = 0.dp) {
                state.found.forEachIndexed { i, reply ->
                    if (i > 0) RowDivider()
                    ListRow(reply.hostname, "${reply.ip}\n${reply.mac}", WakeIcons.Power, onClick = { onPick(reply) })
                }
            }
            SecondaryButton(if (state.searching) "Searching…" else "Search again", onFind)
        } else {
            if (state.searched && !state.searching) {
                Notice(
                    WakeIcons.Alert,
                    "No PC answered. Make sure the Wake agent is running on your PC and that Windows Firewall allows it on private networks.",
                    inset = 0.dp,
                )
            }
            PrimaryButton(if (state.searching) "Searching…" else if (state.searched) "Try again" else "Find my PC", onFind, busy = state.searching)
        }
        SecondaryButton("Add manually", onManual)
    }
}

@Composable
fun PairScreen(reply: DiscoveryReply, state: SetupUiState, onPair: (String) -> Unit, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    ScreenScaffold("Pair with ${reply.hostname}", onBack) {
        BodyText("On your PC, right-click the Wake icon in the system tray and choose Pair phone…, then enter the 6-digit code it shows.")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            singleLine = true,
            label = { Text("Pairing code") },
            isError = state.pairError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = TextStyle(fontFamily = ManropeFamily, fontSize = 32.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center, color = WakeColors.OnSurface),
            shape = WakeShapes.Button,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WakeColors.HeroDisc, unfocusedBorderColor = WakeColors.Outline,
                focusedLabelColor = WakeColors.HeroDisc, unfocusedLabelColor = WakeColors.OnSurfaceMuted,
                cursorColor = WakeColors.HeroDisc, errorBorderColor = WakeColors.Danger, errorLabelColor = WakeColors.Danger,
                focusedContainerColor = WakeColors.Surface, unfocusedContainerColor = WakeColors.Surface, errorContainerColor = WakeColors.Surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.pairError != null) Text(state.pairError, style = WakeType.Body, color = WakeColors.Danger)
        PrimaryButton(if (state.pairing) "Pairing…" else "Pair", { onPair(code) }, enabled = code.length == 6, busy = state.pairing)
    }
}

/** Add or edit a device by hand (wake-only until it's paired with an agent). */
@Composable
fun ManualDeviceForm(
    title: String,
    initial: Device?,
    defaultBroadcast: String,
    onSave: (name: String, mac: String, broadcast: String, port: String, ip: String) -> List<FieldError>,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var mac by rememberSaveable { mutableStateOf(initial?.mac ?: "") }
    var broadcast by rememberSaveable { mutableStateOf(initial?.broadcast ?: defaultBroadcast) }
    var port by rememberSaveable { mutableStateOf((initial?.port ?: 9).toString()) }
    var ip by rememberSaveable { mutableStateOf(initial?.lastIp ?: "") }
    var errors by remember { mutableStateOf(emptyMap<Field, String>()) }

    fun validate(field: Field) {
        val message = validateDevice(name, mac, broadcast, port, ip).firstOrNull { it.field == field }?.message
        errors = if (message == null) errors - field else errors + (field to message)
    }

    ScreenScaffold(title, onBack) {
        FormField("Name", name, { name = it }, helper = "What you call this PC.", error = errors[Field.NAME], onBlur = { validate(Field.NAME) })
        FormField(
            "MAC address", mac, { mac = it },
            helper = "Found in the PC's network adapter details, like 0A:1B:2C:3D:4E:5F.",
            error = errors[Field.MAC], onBlur = { validate(Field.MAC) },
        )
        FormField(
            "Broadcast address", broadcast, { broadcast = it },
            helper = "Usually your network address ending in .255.",
            error = errors[Field.BROADCAST], keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal), onBlur = { validate(Field.BROADCAST) },
        )
        FormField(
            "Port", port, { port = it.filter(Char::isDigit).take(5) },
            helper = "9 works for most network cards; 7 or 0 sometimes do.",
            error = errors[Field.PORT], keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), onBlur = { validate(Field.PORT) },
        )
        FormField(
            "IP address (optional)", ip, { ip = it },
            helper = "Lets the app show whether the PC is on.",
            error = errors[Field.IP], keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal), onBlur = { validate(Field.IP) },
        )
        PrimaryButton("Save", {
            val result = onSave(name, mac, broadcast, port, ip)
            errors = result.associate { it.field to it.message }
            if (result.isEmpty()) onSaved()
        })
        extra()
    }
}

@Composable
fun DangerAction(label: String, onClick: () -> Unit) = SecondaryButton(label, onClick, danger = true)
