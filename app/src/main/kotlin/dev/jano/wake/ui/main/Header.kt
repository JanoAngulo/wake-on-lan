package dev.jano.wake.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jano.wake.status.PcState
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.theme.Motion
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeType

/** Bottom edge curves like the CSS `border-radius: 0 0 50% 50% / 0 0 40px 40px` in the prototype. */
private object CurvedBottom : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { 40.dp.toPx() }
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - r)
            arcTo(Rect(0f, size.height - 2 * r, size.width, size.height), 0f, 180f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

enum class HeroStyle { BUTTON, ERROR, INDICATOR, DIM }

fun heroStyleOf(pc: PcState): HeroStyle = when (pc) {
    is PcState.Off, is PcState.Waking -> HeroStyle.BUTTON
    is PcState.DidntWake -> HeroStyle.ERROR
    is PcState.On -> HeroStyle.INDICATOR
    is PcState.Stopping, is PcState.Away -> HeroStyle.DIM
}

/** 0 = off header, 1 = fully lit; in-between states sit part way. */
private fun onnessOf(pc: PcState): Float = when (pc) {
    is PcState.On -> 1f
    is PcState.Waking, is PcState.Stopping -> 0.45f
    else -> 0f
}

@Composable
fun Header(
    deviceName: String,
    showChevron: Boolean,
    pc: PcState,
    caption: String,
    heroDescription: String,
    reducedMotion: Boolean,
    haptics: Haptics,
    onWake: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchDevice: () -> Unit,
) {
    val onness = remember { Animatable(onnessOf(pc)) }
    val target = onnessOf(pc)
    LaunchedEffect(target) {
        val rising = target > onness.value
        val spec = when {
            reducedMotion -> tween<Float>(Motion.REDUCED_MS)
            rising -> tween(Motion.FOCAL_IN_MS, easing = Motion.EmphasizedDecelerate)
            else -> tween(Motion.FOCAL_OUT_MS, easing = Motion.EmphasizedAccelerate)
        }
        onness.animateTo(target, spec)
    }
    val glow by animateFloatAsState(
        if (pc is PcState.On) 1f else 0f,
        if (reducedMotion) tween(Motion.REDUCED_MS) else tween(Motion.FOCAL_IN_MS, easing = Motion.EmphasizedDecelerate),
        label = "glow",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CurvedBottom)
            .drawBehind {
                val start = Offset(size.width * 0.35f, 0f)
                val end = Offset(size.width * 0.65f, size.height)
                drawRect(Brush.linearGradient(listOf(WakeColors.HeaderOffStart, WakeColors.HeaderOffEnd), start, end))
                drawRect(Brush.linearGradient(listOf(WakeColors.HeaderOnStart, WakeColors.HeaderOnEnd), start, end), alpha = onness.value)
                if (glow > 0f) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(WakeColors.Glow, Color.Transparent),
                            center = Offset(size.width / 2, size.height * 0.56f),
                            radius = size.width * 0.6f,
                        ),
                        alpha = glow,
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(if (showChevron) Modifier.clip(CircleShape).clickable(onClickLabel = "Switch device", onClick = onSwitchDevice) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(deviceName, style = WakeType.DeviceName, color = Color.White, maxLines = 1)
                if (showChevron) Icon(WakeIcons.ChevronDown, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Open settings", role = Role.Button, onClick = onOpenSettings)
                    .semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(WakeIcons.Gear, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Hero(pc, heroDescription, reducedMotion, haptics, onWake)
        Spacer(Modifier.height(22.dp))
        AnimatedContent(
            targetState = caption,
            transitionSpec = { fadeIn(tween(Motion.CROSSFADE_MS)) togetherWith fadeOut(tween(Motion.CROSSFADE_MS)) },
            label = "caption",
        ) { text ->
            Text(
                text,
                style = WakeType.HeroCaption,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(52.dp))
    }
}

@Composable
private fun Hero(pc: PcState, description: String, reducedMotion: Boolean, haptics: Haptics, onWake: () -> Unit) {
    val style = heroStyleOf(pc)
    val clickable = pc is PcState.Off || pc is PcState.DidntWake
    val lighting = style == HeroStyle.INDICATOR
    val spec = when {
        reducedMotion -> tween<Color>(Motion.REDUCED_MS)
        lighting -> tween(Motion.FOCAL_IN_MS, easing = Motion.EmphasizedDecelerate)
        else -> tween(Motion.FOCAL_OUT_MS, easing = Motion.EmphasizedAccelerate)
    }
    val disc by animateColorAsState(
        when (style) {
            HeroStyle.BUTTON, HeroStyle.ERROR -> WakeColors.HeroDisc
            HeroStyle.INDICATOR -> WakeColors.Success.copy(alpha = 0.10f)
            HeroStyle.DIM -> Color.Transparent
        }, spec, label = "disc",
    )
    val border by animateColorAsState(
        when (style) {
            HeroStyle.INDICATOR -> WakeColors.Success
            HeroStyle.DIM -> WakeColors.Neutral.copy(alpha = 0.45f)
            else -> Color.Transparent
        }, spec, label = "border",
    )
    val ring by animateColorAsState(
        when (style) {
            HeroStyle.BUTTON -> Color.White.copy(alpha = 0.12f)
            HeroStyle.ERROR -> WakeColors.Warning.copy(alpha = 0.30f)
            else -> Color.Transparent
        }, spec, label = "ring",
    )
    val iconTint by animateColorAsState(
        when (style) {
            HeroStyle.BUTTON, HeroStyle.ERROR -> WakeColors.HeroIcon
            HeroStyle.INDICATOR -> WakeColors.SuccessText
            HeroStyle.DIM -> WakeColors.Neutral
        }, spec, label = "icon",
    )
    val glow by animateFloatAsState(if (lighting) 1f else 0f, tween(Motion.FOCAL_IN_MS), label = "heroGlow")

    val spinning = !reducedMotion && (pc is PcState.Waking || pc is PcState.Stopping)
    val spin = if (spinning) {
        rememberInfiniteTransition(label = "spin").animateFloat(
            0f, 360f, infiniteRepeatable(tween(Motion.SPIN_MS, easing = LinearEasing), RepeatMode.Restart), label = "angle",
        ).value
    } else null

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed && clickable && !reducedMotion) 0.96f else 1f, tween(Motion.PRESS_MS), label = "press")

    Box(
        modifier = Modifier
            .size(148.dp)
            .drawBehind {
                val radius = size.minDimension / 2
                if (glow > 0f) {
                    drawCircle(
                        Brush.radialGradient(listOf(WakeColors.Success.copy(alpha = 0.30f), Color.Transparent), radius = radius * 1.5f),
                        radius = radius * 1.5f,
                        alpha = glow,
                    )
                }
                drawCircle(ring, radius = radius + 5.dp.toPx(), style = Stroke(10.dp.toPx()))
                if (spin != null) {
                    val arcRadius = radius + 11.dp.toPx()
                    val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Color.White.copy(alpha = 0.16f), radius = arcRadius, style = Stroke(4.dp.toPx()))
                    drawArc(
                        Color.White, spin, 94f, false,
                        topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                        size = Size(2 * arcRadius, 2 * arcRadius),
                        style = stroke,
                    )
                }
            }
            .scale(pressScale)
            .clip(CircleShape)
            .background(disc)
            .border(3.dp, border, CircleShape)
            .then(
                if (clickable) {
                    Modifier.clickable(interaction, ripple(), role = Role.Button, onClickLabel = "Wake") {
                        haptics.press()
                        onWake()
                    }
                } else Modifier,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(WakeIcons.Power, contentDescription = null, tint = iconTint, modifier = Modifier.size(56.dp))
    }
}
