package dev.jano.wake.ui.main

enum class SlideOutcome { COMPLETED, CANCELLED }

/** Pure rules for slide-to-confirm, kept apart from gestures so they can be tested. */
class SlideTracker(private val commitAt: Float = 0.9f) {
    fun progress(offsetPx: Float, travelPx: Float): Float =
        if (travelPx <= 0f) 0f else (offsetPx / travelPx).coerceIn(0f, 1f)

    fun quarterTicks(progress: Float): Int = (progress * 4).toInt()

    /** Letting go close enough to the end still counts, so a slightly short swipe doesn't bounce back. */
    fun release(progress: Float): SlideOutcome =
        if (progress >= commitAt) SlideOutcome.COMPLETED else SlideOutcome.CANCELLED
}
