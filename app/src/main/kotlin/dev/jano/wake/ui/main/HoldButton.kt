package dev.jano.wake.ui.main

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.theme.Motion
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val DangerFill = WakeColors.Danger.copy(alpha = 0.24f)

/**
 * Forced actions need a 1 s hold. A short tap shakes and explains; letting go early retracts.
 * Accessibility services (TalkBack, switch access, keyboard) get a confirm dialog instead.
 */
@Composable
fun HoldButton(
    label: String,
    icon: ImageVector,
    note: String,
    tapHint: String,
    enabled: Boolean,
    height: Dp,
    reducedMotion: Boolean,
    haptics: Haptics,
    confirmQuestion: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracker = remember { HoldTracker() }
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    var hint by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf(false) }
    val content = if (enabled) WakeColors.Danger else WakeColors.OnSurfaceMuted

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .graphicsLayer { translationX = shake.value * density }
            .clip(WakeShapes.Button)
            .background(if (enabled) WakeColors.DangerContainer else WakeColors.Surface)
            .border(1.dp, if (enabled) WakeColors.DangerOutline else WakeColors.Outline, WakeShapes.Button)
            .drawBehind { drawRect(DangerFill, size = size.copy(width = size.width * progress.value)) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = note
                if (enabled) onClick(label = label) { dialog = true; true } else disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val start = SystemClock.uptimeMillis()
                    haptics.press()
                    var completed = false
                    val holding = scope.launch {
                        var ticks = 0
                        while (isActive) {
                            val elapsed = SystemClock.uptimeMillis() - start
                            progress.snapTo(tracker.progress(elapsed))
                            val quarter = tracker.quarterTicks(elapsed)
                            if (quarter in (ticks + 1)..3) { ticks = quarter; haptics.tick() }
                            if (elapsed >= Motion.HOLD_MS) {
                                completed = true
                                haptics.confirm()
                                onConfirm()
                                progress.animateTo(0f, tween(Motion.RETRACT_MS, delayMillis = 180))
                                break
                            }
                            withFrameMillis { }
                        }
                    }
                    waitForUpOrCancellation()
                    if (!completed) {
                        holding.cancel()
                        val outcome = tracker.release(SystemClock.uptimeMillis() - start)
                        scope.launch { progress.animateTo(0f, tween(Motion.RETRACT_MS, easing = Motion.Standard)) }
                        if (outcome == HoldOutcome.TAP) {
                            hint = tapHint
                            haptics.reject()
                            if (!reducedMotion) scope.launch {
                                shake.animateTo(0f, keyframes {
                                    durationMillis = Motion.SHAKE_MS
                                    -7f at 64; 6f at 144; -3f at 224; 2f at 272
                                })
                            }
                            scope.launch { delay(1_600); hint = null }
                        }
                    }
                }
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Text(label, style = if (height > 64.dp) WakeType.ButtonPrimary else WakeType.Button, color = content, modifier = Modifier.weight(1f))
        Text(hint ?: note, style = WakeType.Note, color = content.copy(alpha = 0.9f), maxLines = 1)
    }

    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            containerColor = WakeColors.Surface,
            title = { Text(confirmQuestion, style = WakeType.Title, color = WakeColors.OnSurface) },
            text = { Text("Open apps will close.", style = WakeType.Body, color = WakeColors.OnSurfaceMuted) },
            confirmButton = {
                TextButton(onClick = { dialog = false; onConfirm() }) { Text(confirmLabel, style = WakeType.Button, color = WakeColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = false }) { Text("Cancel", style = WakeType.Button, color = WakeColors.OnSurface) }
            },
        )
    }
}

/** Neutral tap action (Sleep, Lock). */
@Composable
fun TapButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    reducedMotion: Boolean,
    haptics: Haptics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    busy: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reducedMotion) 0.98f else 1f, tween(Motion.PRESS_MS), label = "press")
    val active = enabled && !busy
    // Primary: the one action that matters right now (Unlock while the lock screen is up).
    val filled = primary && active
    val content = when {
        filled -> WakeColors.Background
        active -> WakeColors.OnSurface
        else -> WakeColors.OnSurfaceMuted
    }
    Row(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 60.dp)
            .clip(WakeShapes.Button)
            .background(if (filled) WakeColors.HeroDisc else WakeColors.Surface)
            .border(1.dp, if (filled) WakeColors.HeroDisc else WakeColors.Outline, WakeShapes.Button)
            .clickable(interaction, ripple(), enabled = active, role = Role.Button) {
                haptics.press()
                onClick()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = WakeColors.OnSurface, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        }
        Text(label, style = WakeType.Button, color = content)
    }
}
