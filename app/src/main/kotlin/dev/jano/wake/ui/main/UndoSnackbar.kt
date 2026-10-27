package dev.jano.wake.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jano.wake.main.UiMessage
import dev.jano.wake.ui.theme.Motion
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType
import kotlinx.coroutines.delay

@Composable
fun MessageSnackbar(
    message: UiMessage?,
    deviceName: String,
    reducedMotion: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slide = !reducedMotion
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = (if (slide) slideInVertically(tween(Motion.SNACK_IN_MS, easing = Motion.EmphasizedDecelerate)) { it } else fadeIn(tween(0))) +
            fadeIn(tween(if (slide) Motion.SNACK_IN_MS else Motion.REDUCED_MS)),
        exit = (if (slide) slideOutVertically(tween(Motion.SNACK_OUT_MS, easing = Motion.EmphasizedAccelerate)) { it } else fadeOut(tween(0))) +
            fadeOut(tween(if (slide) Motion.SNACK_OUT_MS else Motion.REDUCED_MS)),
    ) {
        val current = message ?: return@AnimatedVisibility
        if (current !is UiMessage.Undo) {
            LaunchedEffect(current) {
                delay(3_500)
                onDismiss()
            }
        }
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .shadow(12.dp, WakeShapes.Snackbar)
                .background(WakeColors.Snackbar, WakeShapes.Snackbar)
                .padding(start = 18.dp, end = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = when (current) {
                is UiMessage.Undo -> {
                    val left by produceState(3L, current.id) {
                        while (true) {
                            value = ((current.firesAtMs - System.currentTimeMillis() + 999) / 1000).coerceIn(0, 3)
                            delay(250)
                        }
                    }
                    Presentation.undoText(current.kind, left)
                }
                is UiMessage.Info -> current.text
                UiMessage.TooLateToUndo -> "Too late to undo. $deviceName is already shutting down."
                is UiMessage.OpenUnlockSetup -> current.reason ?: "Set up fingerprint unlock in Settings first."
            }
            Text(text, style = WakeType.Body, color = WakeColors.Background, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
            if (current is UiMessage.Undo) {
                TextButton(onClick = onUndo) { Text("Undo", style = WakeType.Button, color = WakeColors.SnackbarAction) }
            }
        }
    }
}
