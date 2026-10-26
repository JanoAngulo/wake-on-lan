package dev.jano.wake.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.jano.wake.R

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val ManropeFamily = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700))

private fun style(size: Int, weight: Int) = TextStyle(
    fontFamily = ManropeFamily,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    fontFeatureSettings = "tnum",
)

/** Type roles from the design spec §7. */
object WakeType {
    val DeviceName = style(22, 700).copy(letterSpacing = (-0.2).sp)
    val HeroCaption = style(16, 500)
    val Button = style(16, 600)
    val ButtonPrimary = style(17, 600)
    val RowValue = style(16, 600)
    val RowLabel = style(14, 500)
    val RowSecondary = style(14, 500)
    val Section = style(15, 700)
    val Pill = style(14, 700)
    val Note = style(13, 500)
    val Body = style(15, 500)
    val Title = style(20, 700)
}
