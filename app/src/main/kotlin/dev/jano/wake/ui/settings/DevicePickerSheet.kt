package dev.jano.wake.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jano.wake.data.Store
import dev.jano.wake.ui.common.ListRow
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.main.Pill
import dev.jano.wake.ui.main.PillKind
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeType

/** Switch the selected PC; folders get a Wake all action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(store: Store, onSelect: (String) -> Unit, onWakeAll: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WakeColors.Surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            val groups = listOf(null) + store.folders.map { it.id }
            groups.forEach { folderId ->
                val devices = store.devices.filter { it.folderId == folderId }
                if (devices.isEmpty()) return@forEach
                val folder = store.folders.firstOrNull { it.id == folderId }
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(folder?.name ?: "Devices", style = WakeType.Section, color = WakeColors.OnSurfaceMuted, modifier = Modifier.weight(1f))
                    if (folder != null) TextButton(onClick = { onWakeAll(folder.id); onDismiss() }) { Text("Wake all", style = WakeType.Button, color = WakeColors.HeroDisc) }
                }
                devices.forEach { device ->
                    ListRow(
                        device.name, device.lastIp ?: device.mac, WakeIcons.Power,
                        onClick = { onSelect(device.id); onDismiss() },
                        trailing = { if (device.id == store.selected?.id) Pill("Selected", PillKind.OK) },
                    )
                }
            }
        }
    }
}
