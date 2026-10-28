package dev.jano.wake.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object WakeShapes {
    val Card = RoundedCornerShape(24.dp)
    val Button = RoundedCornerShape(20.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Notice = RoundedCornerShape(14.dp)
    val Snackbar = RoundedCornerShape(16.dp)
}

/** Motion tokens from the design spec §10. */
object Motion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val FOCAL_IN_MS = 600
    const val FOCAL_OUT_MS = 400
    const val PRESS_MS = 100
    const val CROSSFADE_MS = 200
    const val REDUCED_MS = 150
    const val SNACK_IN_MS = 300
    const val SNACK_OUT_MS = 200
    const val SPIN_MS = 1300
    const val SENT_MS = 900
    const val PEEK_MS = 160
    const val RETRACT_MS = 200
    const val CHEVRON_MS = 1600
}
