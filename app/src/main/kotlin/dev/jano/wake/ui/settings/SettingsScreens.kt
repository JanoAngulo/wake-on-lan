package dev.jano.wake.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jano.wake.data.Device
import dev.jano.wake.data.Folder
import dev.jano.wake.data.Store
import dev.jano.wake.ui.common.BodyText
import dev.jano.wake.ui.common.ListRow
import dev.jano.wake.ui.common.PrimaryButton
import dev.jano.wake.ui.common.ScreenScaffold
import dev.jano.wake.ui.common.SecondaryButton
import dev.jano.wake.ui.common.SectionLabel
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.main.KeyValueCard
import dev.jano.wake.ui.main.Pill
import dev.jano.wake.ui.main.PillKind
import dev.jano.wake.ui.main.RowDivider
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeType

@Composable
fun SettingsScreen(
    message: String?,
    onDevices: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onAdvanced: () -> Unit,
    onHelp: () -> Unit,
    onGuide: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold("Settings", onBack) {
        KeyValueCard(inset = 0.dp) {
            ListRow("How to connect", "Set up the PC and pair this phone", WakeIcons.Wifi, onGuide)
            RowDivider()
            ListRow("Devices", "Add, edit, pair and group your PCs", WakeIcons.Power, onDevices)
            RowDivider()
            ListRow("Import devices", "From a JSON file", WakeIcons.Plus, onImport)
            RowDivider()
            ListRow("Export devices", "Pairing stays on this phone", WakeIcons.Folder, onExport)
            RowDivider()
            ListRow("Advanced", "Status checks and haptics", WakeIcons.Gear, onAdvanced)
            RowDivider()
            ListRow("PC setup help", "If waking stops working", WakeIcons.Alert, onHelp)
        }
        if (message != null) Text(message, style = WakeType.Body, color = WakeColors.SuccessText, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
fun DevicesScreen(
    store: Store,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onSaveFolder: (id: String?, name: String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<Folder?>(null) }
    var creating by remember { mutableStateOf(false) }
    ScreenScaffold("Devices", onBack) {
        KeyValueCard(inset = 0.dp) {
            store.devices.forEachIndexed { i, device ->
                if (i > 0) RowDivider()
                ListRow(
                    device.name,
                    listOfNotNull(device.lastIp, store.folders.firstOrNull { it.id == device.folderId }?.name).joinToString(", ").ifEmpty { device.mac },
                    WakeIcons.Power,
                    onClick = { onEdit(device.id) },
                    trailing = { Pill(if (device.paired) "Paired" else "Wake only", if (device.paired) PillKind.OK else PillKind.NEUTRAL) },
                )
            }
        }
        PrimaryButton("Add a PC", onAdd)

        SectionLabel("Folders")
        if (store.folders.isEmpty()) BodyText("Folders group PCs so you can wake them together.", Modifier.padding(horizontal = 8.dp))
        if (store.folders.isNotEmpty()) {
            KeyValueCard(inset = 0.dp) {
                store.folders.forEachIndexed { i, folder ->
                    if (i > 0) RowDivider()
                    val count = store.devices.count { it.folderId == folder.id }
                    ListRow(folder.name, if (count == 1) "1 device" else "$count devices", WakeIcons.Folder, onClick = { editing = folder })
                }
            }
        }
        SecondaryButton("New folder", { creating = true })
    }

    if (creating || editing != null) {
        FolderDialog(
            initial = editing,
            onSave = { name -> onSaveFolder(editing?.id, name); creating = false; editing = null },
            onDelete = editing?.let { folder -> { onDeleteFolder(folder.id); editing = null } },
            onDismiss = { creating = false; editing = null },
        )
    }
}

@Composable
private fun FolderDialog(initial: Folder?, onSave: (String) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WakeColors.Surface,
        title = { Text(if (initial == null) "New folder" else "Edit folder", style = WakeType.Title, color = WakeColors.OnSurface) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Folder name") }) },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save", style = WakeType.Button) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", style = WakeType.Button, color = WakeColors.Danger) }
                TextButton(onClick = onDismiss) { Text("Cancel", style = WakeType.Button, color = WakeColors.OnSurface) }
            }
        },
    )
}

@Composable
fun DeviceExtras(
    device: Device,
    folders: List<Folder>,
    onAssignFolder: (String?) -> Unit,
    onRepair: () -> Unit,
    onUnpair: () -> Unit,
    onDelete: () -> Unit,
    unlockSaved: Boolean = false,
    unlockModuleMissing: Boolean = false,
    onUnlockSetup: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (folders.isNotEmpty()) {
        SectionLabel("Folder")
        KeyValueCard(inset = 0.dp) {
            ListRow("No folder", onClick = { onAssignFolder(null) }, trailing = { if (device.folderId == null) Pill("Selected", PillKind.OK) })
            folders.forEach { folder ->
                RowDivider()
                ListRow(folder.name, icon = WakeIcons.Folder, onClick = { onAssignFolder(folder.id) },
                    trailing = { if (device.folderId == folder.id) Pill("Selected", PillKind.OK) })
            }
        }
    }
    SectionLabel("Pairing")
    BodyText(if (device.paired) "Paired with this phone. Shut down, reboot, sleep and lock are available." else "Wake only. Pair with the Wake agent to shut down, reboot, sleep and lock.", Modifier.padding(horizontal = 8.dp))
    SecondaryButton(if (device.paired) "Pair again" else "Pair with agent", onRepair)
    if (device.paired) SecondaryButton("Unpair", onUnpair)
    if (device.paired) {
        SectionLabel("Fingerprint unlock")
        KeyValueCard(inset = 0.dp) {
            ListRow(
                "Fingerprint unlock",
                subtitle = when {
                    unlockSaved -> "On"
                    unlockModuleMissing -> "Install the unlock module on the PC first"
                    else -> "Off"
                },
                icon = WakeIcons.Fingerprint,
                onClick = onUnlockSetup,
            )
        }
    }
    SecondaryButton("Delete device", { confirmDelete = true }, danger = true)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = WakeColors.Surface,
            title = { Text("Delete ${device.name}?", style = WakeType.Title, color = WakeColors.OnSurface) },
            text = { BodyText("You'll need to add and pair it again to use it.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", style = WakeType.Button, color = WakeColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", style = WakeType.Button, color = WakeColors.OnSurface) } },
        )
    }
}

@Composable
fun AdvancedScreen(store: Store, onPollMs: (Long) -> Unit, onHaptics: (Boolean) -> Unit, onBack: () -> Unit) {
    ScreenScaffold("Advanced", onBack) {
        SectionLabel("Check status every")
        KeyValueCard(inset = 0.dp) {
            listOf(1_000L to "1 second", 2_000L to "2 seconds", 5_000L to "5 seconds").forEachIndexed { i, (ms, label) ->
                if (i > 0) RowDivider()
                ListRow(label, onClick = { onPollMs(ms) }, trailing = { if (store.settings.pollMs == ms) Pill("Selected", PillKind.OK) })
            }
        }
        BodyText("Only while the app is open. Broadcast address and port are set per device.", Modifier.padding(horizontal = 8.dp))
        KeyValueCard(inset = 0.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Haptics", style = WakeType.RowValue, color = WakeColors.OnSurface)
                    Text("Buzz on wake, hold and confirm", style = WakeType.RowSecondary, color = WakeColors.OnSurfaceMuted)
                }
                Switch(
                    checked = store.settings.haptics,
                    onCheckedChange = onHaptics,
                    colors = SwitchDefaults.colors(checkedTrackColor = WakeColors.Success, checkedThumbColor = WakeColors.Background),
                )
            }
        }
    }
}

@Composable
fun PcSetupHelpScreen(onBack: () -> Unit) {
    ScreenScaffold("PC setup help", onBack) {
        SectionLabel("If the PC won't wake")
        BodyText("1. Make sure the PC is plugged in and its network cable is connected.")
        BodyText("2. On the PC, open PowerShell in the Wake project folder and run: pc-setup\\setup.ps1 -Check. Every line should say yes.")
        BodyText("3. In the BIOS (press Delete at startup), enable Resume by PCI-E device (LAN) and disable ErP Ready.")
        BodyText("4. Keep Windows Fast Startup off so Shut down is a real shutdown.")
        SectionLabel("If commands don't work")
        BodyText("The Wake agent runs when you log in on the PC. Look for the green power icon in the system tray. If it's missing, log in, or run the setup script again.")
        BodyText("If you reinstalled the app or the agent, pair again from Settings → Devices.")
    }
}
