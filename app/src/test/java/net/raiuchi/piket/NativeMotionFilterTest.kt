package net.raiuchi.piket

import org.junit.Assert.*
import org.junit.Test

class NativeMotionFilterTest {
    private fun fix(t: Long, lat: Double = 59.9, lon: Double = 30.3, speed: Float? = 0f,
                    speedAccuracy: Float? = 0.5f, mock: Boolean = false, age: Long = 0L) =
        NativeMotionFilter.Fix(lat, lon, t, age, 5f, speed, speedAccuracy, mock, 12, 32f, true)

    @Test fun rejectsMockAndStaleFixes() {
        val filter = NativeMotionFilter()
        assertFalse(filter.process(fix(0, mock = true)).accepted)
        assertFalse(filter.process(fix(0, age = 8_000)).accepted)
    }

    @Test fun suppressesImpossibleAcceleration() {
        val filter = NativeMotionFilter()
        assertNull(filter.process(fix(0)).filteredSpeedMps)
        assertEquals(0f, filter.process(fix(1_000)).filteredSpeedMps)
        assertNull(filter.process(fix(2_000, speed = 27.78f)).filteredSpeedMps)
    }

    @Test fun rejectsPoorAccuracyThatCouldShiftPositionAlongTrack() {
        val filter = NativeMotionFilter()
        val poor = fix(0).copy(accuracyM = 120f)
        assertFalse(filter.process(poor).accepted)
        assertEquals("accuracy", filter.process(poor).reason)
    }

    @Test fun coldStartRequiresTwoConsistentSpeedSamples() {
        val filter = NativeMotionFilter()
        assertNull(filter.process(fix(0, speed = 2.8f)).filteredSpeedMps)
        assertEquals(0f, filter.process(fix(1_000, speed = 0f)).filteredSpeedMps)
    }

    @Test fun confirmsSpeedTwiceAfterSignalLoss() {
        val filter = NativeMotionFilter()
        filter.process(fix(0, speed = 20f))
        filter.markSignalUnavailable()
        assertNull(filter.process(fix(2_000, lat = 59.9003, speed = 20f)).filteredSpeedMps)
        assertNotNull(filter.process(fix(4_000, lat = 59.9006, speed = 20.4f)).filteredSpeedMps)
    }

    @Test fun forcesZeroOnStableTenSecondStop() {
        val filter = NativeMotionFilter()
        filter.process(fix(0, speed = 0f))
        filter.process(fix(5_000, lat = 59.90001, speed = 15f))
        val result = filter.process(fix(11_000, lat = 59.90001, speed = 15f))
        assertTrue(result.stationary)
        assertEquals(0f, result.filteredSpeedMps)
    }

    @Test fun detectsAStopAfterEarlierMovement() {
        val filter = NativeMotionFilter()
        filter.process(fix(0, speed = 20f))
        filter.process(fix(2_000, lat = 59.901, speed = 20f))
        filter.process(fix(5_000, lat = 59.901, speed = 0f))
        val result = filter.process(fix(13_000, lat = 59.901, speed = 12f))
        assertTrue(result.stationary)
        assertEquals(0f, result.filteredSpeedMps)
    }

    @Test fun replacesStuckZeroWithConfirmedCoordinateSpeed() {
        val filter = NativeMotionFilter()
        filter.process(fix(0, speed = 0f))
        filter.process(fix(1_000, speed = 0f))
        assertNull(filter.process(fix(2_000, lat = 59.90020, speed = 0f)).filteredSpeedMps)
        val corrected = filter.process(fix(3_000, lat = 59.90040, speed = 0f))
        assertNotNull(corrected.filteredSpeedMps)
        assertTrue(corrected.filteredSpeedMps!! > 15f)
    }
}
