package dev.jano.wake.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals

class SlideTrackerTest {
    private val tracker = SlideTracker(commitAt = 0.9f)

    @Test
    fun progressFollowsTheThumbAndStaysInTheTrack() {
        assertEquals(0f, tracker.progress(offsetPx = 0f, travelPx = 300f))
        assertEquals(0.5f, tracker.progress(offsetPx = 150f, travelPx = 300f))
        assertEquals(1f, tracker.progress(offsetPx = 300f, travelPx = 300f))
        assertEquals(1f, tracker.progress(offsetPx = 420f, travelPx = 300f))
        assertEquals(0f, tracker.progress(offsetPx = -20f, travelPx = 300f))
    }

    @Test
    fun noTrackMeansNoProgress() {
        assertEquals(0f, tracker.progress(offsetPx = 40f, travelPx = 0f))
    }

    @Test
    fun quarterTicksCountUp() {
        assertEquals(listOf(0, 0, 1, 2, 3, 4), listOf(0f, 0.24f, 0.25f, 0.5f, 0.75f, 1f).map(tracker::quarterTicks))
    }

    @Test
    fun lettingGoShortOfTheEndCancels() {
        assertEquals(SlideOutcome.CANCELLED, tracker.release(0f))
        assertEquals(SlideOutcome.CANCELLED, tracker.release(0.89f))
    }

    @Test
    fun lettingGoNearTheEndCompletes() {
        assertEquals(SlideOutcome.COMPLETED, tracker.release(0.9f))
        assertEquals(SlideOutcome.COMPLETED, tracker.release(1f))
    }
}
