package dev.jano.wake.ui.main

import dev.jano.wake.ui.theme.Motion

enum class HoldOutcome { COMPLETED, CANCELLED, TAP }

/** Pure timing rules for hold-to-confirm, kept apart from gestures so they can be tested. */
class HoldTracker(
    private val holdMs: Long = Motion.HOLD_MS.toLong(),
    private val tapMaxMs: Long = Motion.TAP_MAX_MS.toLong(),
) {
    fun progress(elapsedMs: Long): Float = (elapsedMs.toFloat() / holdMs).coerceIn(0f, 1f)

    fun quarterTicks(elapsedMs: Long): Int = (progress(elapsedMs) * 4).toInt()

    fun release(elapsedMs: Long): HoldOutcome = when {
        elapsedMs >= holdMs -> HoldOutcome.COMPLETED
        elapsedMs < tapMaxMs -> HoldOutcome.TAP
        else -> HoldOutcome.CANCELLED
    }
}
