package dev.jano.wake.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType

data class NoticeAction(val label: String, val onClick: () -> Unit)

/** Yellow explanation shown when something needs the user's attention (spec §6). */
@Composable
fun Notice(icon: ImageVector, text: String, actions: List<NoticeAction> = emptyList(), modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    Row(
        modifier = modifier
            .padding(horizontal = inset)
            .fillMaxWidth()
            .background(WakeColors.NoticeContainer, WakeShapes.Notice)
            .border(1.dp, WakeColors.NoticeOutline, WakeShapes.Notice)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (actions.isEmpty()) 12.dp else 2.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WakeColors.Warning, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = WakeType.Body, color = WakeColors.Warning)
            if (actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    actions.forEach { action ->
                        TextButton(onClick = action.onClick) {
                            Text(action.label, style = WakeType.Button, color = WakeColors.Warning)
                        }
                    }
                }
            }
        }
    }
}
