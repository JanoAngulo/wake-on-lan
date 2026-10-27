package dev.jano.wake.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.common.BodyText
import dev.jano.wake.ui.common.FormField
import dev.jano.wake.ui.common.PrimaryButton
import dev.jano.wake.ui.common.ScreenScaffold
import dev.jano.wake.ui.common.SecondaryButton
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.main.KeyValueCard
import dev.jano.wake.ui.main.Notice
import dev.jano.wake.ui.main.Pill
import dev.jano.wake.ui.main.PillKind
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType

/**
 * Settings › PC › Fingerprint unlock. [reason] says why the main screen sent the user here
 * (wrong password, fingerprints changed); [moduleMissing] is true when the agent reports no unlock module.
 */
@Composable
fun UnlockSetupScreen(
    deviceName: String,
    saved: Boolean,
    moduleMissing: Boolean,
    reason: String?,
    info: String?,
    onSave: (CharArray) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    // Compose text fields hold a String; it lives only until save or leaving the screen.
    var password by remember { mutableStateOf("") }

    ScreenScaffold("Fingerprint unlock", onBack) {
        if (reason != null && !saved) Notice(WakeIcons.Alert, reason, inset = 0.dp)
        BodyText(
            "Unlock $deviceName's lock screen with your fingerprint. Wake keeps the Windows password on this phone, " +
                "locked by your fingerprint. It never stays on the PC.",
            Modifier.padding(horizontal = 8.dp),
        )
        if (moduleMissing) {
            Notice(WakeIcons.Plug, "Install the unlock module on the PC first: run Install Wake Agent again and answer y.", inset = 0.dp)
        }

        if (saved) {
            KeyValueCard(inset = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(WakeIcons.Fingerprint, null, tint = WakeColors.OnSurfaceMuted, modifier = Modifier.size(22.dp))
                    Text("Fingerprint unlock", style = WakeType.RowValue, color = WakeColors.OnSurface, modifier = Modifier.weight(1f))
                    Pill("On", PillKind.OK, icon = WakeIcons.Check)
                }
            }
            if (info != null) SavedLine(info)
            SecondaryButton("Remove", onRemove, danger = true)
        } else {
            FormField(
                label = "Windows password",
                value = password,
                onValueChange = { password = it },
                helper = "Your Microsoft account or local account password. A PIN won't work.",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                password = true,
            )
            PrimaryButton(
                "Save with fingerprint",
                onClick = {
                    onSave(password.toCharArray())
                    password = ""
                },
                enabled = password.isNotEmpty(),
                icon = WakeIcons.Fingerprint,
            )
            if (info != null) Notice(WakeIcons.Alert, info, inset = 0.dp)
        }
    }
}

@Composable
private fun SavedLine(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(WakeColors.Success.copy(alpha = 0.08f), WakeShapes.Notice)
            .border(1.dp, WakeColors.Success.copy(alpha = 0.30f), WakeShapes.Notice)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(WakeIcons.Check, null, tint = WakeColors.SuccessText, modifier = Modifier.size(20.dp))
        Text(text, style = WakeType.Body, color = WakeColors.OnSurface)
    }
}
