package dev.jano.wake.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Stroke icons (2dp, round caps) matching docs/design/prototype.html. Tinted by Icon(). */
object WakeIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Power by lazy { icon("power", "M12 3v8", "M6.4 7a8 8 0 1 0 11.2 0") }
    val Reboot by lazy { icon("reboot", "M20 12a8 8 0 1 1-2.4-5.7", "M20 4v4.5h-4.5") }
    val Sleep by lazy { icon("sleep", "M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z") }
    val Lock by lazy {
        icon("lock", "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    }
    val Globe by lazy {
        icon("globe", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18")
    }
    val Pulse by lazy { icon("pulse", "M3 12h4l2-5 4 10 2-5h6") }
    val Check by lazy { icon("check", "M5 12.5l4.5 4.5L19 7.5") }
    val Cross by lazy { icon("cross", "M7 7l10 10M17 7L7 17") }
    val Wifi by lazy {
        icon("wifi", "M2 8.5a15 15 0 0 1 20 0M5 12a10 10 0 0 1 14 0M8.5 15.5a5 5 0 0 1 7 0", "M12 19h.01")
    }
    val Plug by lazy { icon("plug", "M9 7V3M15 7V3M7 7h10v4a5 5 0 0 1-10 0z", "M12 16v5") }
    val Alert by lazy {
        icon("alert", "M12 9v4M12 17h.01", "M10.3 3.9L2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z")
    }
    val ChevronDown by lazy { icon("chevron-down", "M6 9l6 6 6-6") }
    val ChevronRight by lazy { icon("chevron-right", "M9 6l6 6-6 6") }
    val Back by lazy { icon("back", "M15 6l-6 6 6 6") }
    val Plus by lazy { icon("plus", "M12 5v14M5 12h14") }
    val Folder by lazy { icon("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val Gear by lazy {
        icon(
            "gear",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21" +
                "a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1" +
                "a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8" +
                "l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1" +
                "a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9" +
                "a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }
}
