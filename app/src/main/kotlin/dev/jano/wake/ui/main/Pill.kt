package dev.jano.wake.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.theme.Motion
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType

enum class PillKind(val color: Color) {
    OK(WakeColors.SuccessText),
    WARN(WakeColors.Warning),
    NEUTRAL(WakeColors.Neutral),
}

/** Status pill: color always paired with a word (and optionally a symbol). */
@Composable
fun Pill(text: String, kind: PillKind, modifier: Modifier = Modifier, icon: ImageVector? = null, dot: Boolean = false) {
    val color by animateColorAsState(kind.color, tween(Motion.CROSSFADE_MS), label = "pill")
    Row(
        modifier = modifier
            .heightIn(min = 30.dp)
            .background(color.copy(alpha = 0.13f), WakeShapes.Pill)
            .border(1.dp, color.copy(alpha = 0.38f), WakeShapes.Pill)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        if (dot) Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = WakeType.Pill, color = color, maxLines = 1)
    }
}
