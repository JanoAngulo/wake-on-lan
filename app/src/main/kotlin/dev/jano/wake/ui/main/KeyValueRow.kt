package dev.jano.wake.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeShapes
import dev.jano.wake.ui.theme.WakeType

/** Rounded card holding key-value rows separated by hairlines. */
@Composable
fun KeyValueCard(modifier: Modifier = Modifier, inset: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .padding(horizontal = inset)
            .fillMaxWidth()
            .background(WakeColors.Surface, WakeShapes.Card)
            .border(1.dp, WakeColors.Outline, WakeShapes.Card),
        content = content,
    )
}

@Composable
fun RowDivider() = HorizontalDivider(color = WakeColors.Outline, thickness = 1.dp)

/**
 * Label in a fixed 128dp column, value beside it. When either won't fit (large system text),
 * the whole value moves under the label instead of breaking words.
 */
@Composable
fun KeyValueRow(label: String, icon: ImageVector? = null, value: @Composable () -> Unit) {
    val labelContent = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = WakeColors.OnSurfaceMuted, modifier = Modifier.size(20.dp))
            Text(label, style = WakeType.RowLabel, color = WakeColors.OnSurfaceMuted, maxLines = 1, softWrap = false)
        }
    }
    Layout(
        contents = listOf(labelContent, value),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) { (labelMeasurables, valueMeasurables), constraints ->
        val labelColumn = 128.dp.roundToPx()
        val gap = 12.dp.roundToPx()
        val stackGap = 4.dp.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val labelPlaceable = labelMeasurables.first().measure(loose)
        val valueMeasurable = valueMeasurables.first()
        val valueSpace = constraints.maxWidth - labelColumn - gap
        val sideBySide = labelPlaceable.width <= labelColumn &&
            valueMeasurable.maxIntrinsicWidth(Constraints.Infinity) <= valueSpace

        if (sideBySide) {
            val valuePlaceable = valueMeasurable.measure(Constraints(maxWidth = valueSpace))
            val height = maxOf(labelPlaceable.height, valuePlaceable.height, constraints.minHeight)
            layout(constraints.maxWidth, height) {
                labelPlaceable.place(0, (height - labelPlaceable.height) / 2)
                valuePlaceable.place(labelColumn + gap, (height - valuePlaceable.height) / 2)
            }
        } else {
            val valuePlaceable = valueMeasurable.measure(loose)
            val height = maxOf(labelPlaceable.height + stackGap + valuePlaceable.height, constraints.minHeight)
            layout(constraints.maxWidth, height) {
                labelPlaceable.place(0, 0)
                valuePlaceable.place(0, labelPlaceable.height + stackGap)
            }
        }
    }
}

/** Plain value text, with optional muted secondary text beside it. */
@Composable
fun ValueText(text: String, secondary: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = WakeType.RowValue, color = WakeColors.OnSurface, maxLines = 1, softWrap = false)
        if (secondary != null) Text(secondary, style = WakeType.RowSecondary, color = WakeColors.OnSurfaceMuted, maxLines = 1, softWrap = false)
    }
}
