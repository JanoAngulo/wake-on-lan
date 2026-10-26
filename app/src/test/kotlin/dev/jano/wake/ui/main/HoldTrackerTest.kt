package dev.jano.wake.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals

class HoldTrackerTest {
    private val tracker = HoldTracker(holdMs = 1_000, tapMaxMs = 250)

    @Test
    fun progressIsLinear() {
        assertEquals(0f, tracker.progress(0))
        assertEquals(0.5f, tracker.progress(500))
        assertEquals(1f, tracker.progress(1_000))
        assertEquals(1f, tracker.progress(1_500))
    }

    @Test
    fun releaseBefore250IsTap() {
        assertEquals(HoldOutcome.TAP, tracker.release(249))
    }

    @Test
    fun releaseBetweenIsCancelled() {
        assertEquals(HoldOutcome.CANCELLED, tracker.release(250))
        assertEquals(HoldOutcome.CANCELLED, tracker.release(999))
    }

    @Test
    fun releaseAt1000IsCompleted() {
        assertEquals(HoldOutcome.COMPLETED, tracker.release(1_000))
    }

    @Test
    fun quarterTicksCountUp() {
        assertEquals(listOf(0, 0, 1, 2, 3, 4), listOf(0L, 249, 250, 500, 750, 1_000).map(tracker::quarterTicks))
    }
}
