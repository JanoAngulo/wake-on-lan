package dev.jano.wake.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HapticsTest {
    @Test
    fun confirmIsARealBuzzLongerThanATap() {
        val buzz = HapticPatterns.confirm
        assertEquals(buzz.timings.size, buzz.amplitudes.size)
        val onMs = buzz.timings.filterIndexed { i, _ -> buzz.amplitudes[i] > 0 }.sum()
        assertTrue(onMs >= 200, "buzz is on for $onMs ms")
        assertEquals(255, buzz.amplitudes.max(), "ends at full strength")
    }

    @Test
    fun confirmStartsWithoutDelay() {
        assertEquals(0L, HapticPatterns.confirm.timings.first())
    }
}
