package dev.jano.wake.ui.main

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** A one-shot waveform: alternating off/on segments, as VibrationEffect.createWaveform takes them. */
class Waveform(val timings: LongArray, val amplitudes: IntArray)

object HapticPatterns {
    /** "Done": a short nudge, then a full-strength buzz, so finishing a hold feels unlike a tap. */
    val confirm = Waveform(longArrayOf(0, 40, 60, 220), intArrayOf(0, 120, 0, 255))
}

/**
 * The few haptics the spec allows (§9), all switchable from settings. Played on the vibrator directly:
 * the HapticFeedbackConstants for tick, confirm and reject all feel like the same short tap on some phones.
 * Usage TOUCH keeps them under the phone's own touch-vibration setting.
 */
class Haptics(private val vibrator: Vibrator?, private val enabled: Boolean) {
    fun press() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    fun tick() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    fun confirm() = play(HapticPatterns.confirm.let { VibrationEffect.createWaveform(it.timings, it.amplitudes, -1) })
    fun reject() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))

    private fun play(effect: VibrationEffect) {
        if (enabled) vibrator?.vibrate(effect, touch)
    }

    private companion object {
        val touch: VibrationAttributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    }
}

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val context = LocalContext.current
    return remember(context, enabled) {
        val vibrator = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        Haptics(vibrator?.defaultVibrator?.takeIf { it.hasVibrator() }, enabled)
    }
}

/** True when the system's "Remove animations" setting is on. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
