package net.raiuchi.piket

import org.junit.Assert.*
import org.junit.Test

class NativeTimetableCalculatorTest {
    @Test fun calculatesDepartureToNextArrival() {
        val result = NativeTimetableCalculator.calculate(0.0, 20_000.0, "10:00", "10:15")!!
        assertEquals(20_000.0, result.distanceM, 0.01)
        assertEquals(900, result.durationSeconds)
        assertEquals(80.0, result.averageKmh, 0.01)
        assertTrue(result.plausible)
    }

    @Test fun supportsMidnightTransitionAndHalfMinutes() {
        val result = NativeTimetableCalculator.calculate(0.0, 10_000.0, "23:55:30", "00:05:30")!!
        assertEquals(600, result.durationSeconds)
        assertEquals(60.0, result.averageKmh, 0.01)
    }

    @Test fun marksImpossibleAverageInsteadOfAdvisingIt() {
        val result = NativeTimetableCalculator.calculate(0.0, 100_000.0, "10:00", "10:10")!!
        assertFalse(result.plausible)
        assertEquals(600.0, result.averageKmh, 0.01)
    }

    @Test fun rejectsMissingKilometerOrBrokenTime() {
        assertNull(NativeTimetableCalculator.calculate(null, 1_000.0, "10:00", "10:01"))
        assertNull(NativeTimetableCalculator.calculate(0.0, 1_000.0, "bad", "10:01"))
    }
}
