package dev.jano.wake.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.theme.Motion
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DangerFill = WakeColors.Danger.copy(alpha = 0.24f)
private val TrackHeight = 60.dp
private val ThumbInset = 4.dp
private val ThumbSize = TrackHeight - ThumbInset * 2
private val ThumbShape = RoundedCornerShape(16.dp)
private val Peek = 24.dp

/**
 * Forced actions need a slide to the end. The drag only starts once the finger moves sideways past touch slop,
 * so a scroll that begins on the bar leaves it alone and stays silent. A plain tap makes the thumb peek right.
 * Accessibility services (TalkBack, switch access, keyboard) get a confirm dialog instead.
 */
@Composable
fun SlideButton(
    label: String,
    prompt: String,
    icon: ImageVector,
    note: String,
    enabled: Boolean,
    reducedMotion: Boolean,
    haptics: Haptics,
    confirmQuestion: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracker = remember { SlideTracker() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val confirm by rememberUpdatedState(onConfirm)
    var offset by remember { mutableFloatStateOf(0f) }
    var travel by remember { mutableFloatStateOf(0f) }
    var ticks by remember { mutableIntStateOf(0) }
    var sent by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf<Job?>(null) }
    var dialog by remember { mutableStateOf(false) }
    // Stay red while the "sent" thumb sits at the end, even though the undo window has already disabled commands.
    val live = enabled || sent
    val content = if (live) WakeColors.Danger else WakeColors.OnSurfaceMuted

    suspend fun glide(to: Float, ms: Int = Motion.RETRACT_MS) =
        animate(offset, to, animationSpec = tween(ms, easing = Motion.Standard)) { value, _ -> offset = value }

    fun settle(block: suspend () -> Unit) {
        settling?.cancel()
        settling = scope.launch { block() }
    }

    fun complete() {
        sent = true
        haptics.confirm()
        confirm()
        settle {
            glide(travel)
            delay(Motion.SENT_MS.toLong())
            glide(0f)
            sent = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TrackHeight)
            .onSizeChanged { travel = it.width - with(density) { (ThumbSize + ThumbInset * 2).toPx() } }
            .clip(WakeShapes.Button)
            .background(if (live) WakeColors.DangerContainer else WakeColors.Surface)
            .border(1.dp, if (live) WakeColors.DangerOutline else WakeColors.Outline, WakeShapes.Button)
            .drawBehind {
                if (live) drawRoundRect(
                    DangerFill,
                    size = Size(ThumbSize.toPx() + ThumbInset.toPx() * 2 + offset, size.height),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                stateDescription = note
                if (enabled) onClick(label = label) { dialog = true; true } else disabled()
            }
            .pointerInput(enabled, reducedMotion) {
                if (!enabled || reducedMotion) return@pointerInput
                detectTapGestures(onTap = {
                    if (!sent) settle {
                        glide(Peek.toPx(), Motion.PEEK_MS)
                        glide(0f)
                    }
                })
            }
            .draggable(
                state = rememberDraggableState { delta ->
                    if (sent) return@rememberDraggableState
                    offset = (offset + delta).coerceIn(0f, travel)
                    val progress = tracker.progress(offset, travel)
                    val quarter = tracker.quarterTicks(progress)
                    if (quarter in (ticks + 1)..3) { ticks = quarter; haptics.tick() }
                    if (progress >= 1f) complete()
                },
                orientation = Orientation.Horizontal,
                enabled = enabled && !sent,
                onDragStarted = {
                    settling?.cancel()
                    ticks = tracker.quarterTicks(tracker.progress(offset, travel))
                    haptics.press()
                },
                onDragStopped = {
                    if (!sent) {
                        if (tracker.release(tracker.progress(offset, travel)) == SlideOutcome.COMPLETED) complete()
                        else settle { glide(0f) }
                    }
                },
            ),
    ) {
        when {
            sent -> Text(
                "$label sent", style = WakeType.Button, color = content,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
            )
            enabled -> Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = ThumbSize + ThumbInset + 16.dp, end = 16.dp)
                    .graphicsLayer { alpha = (1f - tracker.progress(offset, travel) * 3f).coerceAtLeast(0f) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                Text(prompt, style = WakeType.Button, color = content, maxLines = 1)
                Chevrons(content, animated = !reducedMotion)
            }
            else -> Row(
                Modifier.fillMaxSize().padding(start = ThumbSize + ThumbInset + 16.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = WakeType.Button, color = content, modifier = Modifier.weight(1f))
                Text(note, style = WakeType.Note, color = content.copy(alpha = 0.9f), maxLines = 1)
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .padding(ThumbInset)
                .size(ThumbSize)
                .clip(ThumbShape)
                .background(if (live) WakeColors.Danger else WakeColors.Outline),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (sent) WakeIcons.Check else icon, contentDescription = null,
                tint = if (live) WakeColors.DangerContainer else WakeColors.OnSurfaceMuted,
                modifier = Modifier.size(24.dp),
            )
        }
    }

    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            containerColor = WakeColors.Surface,
            title = { Text(confirmQuestion, style = WakeType.Title, color = WakeColors.OnSurface) },
            text = { Text("Open apps will close.", style = WakeType.Body, color = WakeColors.OnSurfaceMuted) },
            confirmButton = {
                TextButton(onClick = { dialog = false; confirm() }) { Text(confirmLabel, style = WakeType.Button, color = WakeColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = false }) { Text("Cancel", style = WakeType.Button, color = WakeColors.OnSurface) }
            },
        )
    }
}

/** Three chevrons pointing the way, brightening left to right. Static when animations are off. */
@Composable
private fun Chevrons(color: Color, animated: Boolean) {
    val pulse = if (animated) {
        rememberInfiniteTransition(label = "chevrons")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.CHEVRON_MS, easing = LinearEasing)), label = "pulse")
    } else {
        null
    }
    Row {
        repeat(3) { i ->
            Icon(
                WakeIcons.ChevronRight, contentDescription = null, tint = color,
                modifier = Modifier
                    .offset(x = (-5 * i).dp)
                    .size(14.dp)
                    .graphicsLayer { alpha = pulse?.let { chevronAlpha(it.value, i) } ?: 0.7f },
            )
        }
    }
}

/** Each chevron flashes once per loop, 150 ms after the one before it. */
private fun chevronAlpha(pulse: Float, index: Int): Float {
    val t = (pulse - index * 150f / Motion.CHEVRON_MS + 1f) % 1f
    return when {
        t < 0.25f -> 0.25f + 0.75f * (t / 0.25f)
        t < 0.6f -> 1f - 0.75f * ((t - 0.25f) / 0.35f)
        else -> 0.25f
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
