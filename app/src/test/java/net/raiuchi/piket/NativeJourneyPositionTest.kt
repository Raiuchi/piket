package net.raiuchi.piket

import org.junit.Assert.*
import org.junit.Test

class NativeJourneyPositionTest {
    @Test fun vyborgAxisContinuesFromPassengerStationToTammisuo() {
        val atVyborg = NativeJourneyPosition.unifiedMeters("СПбФин - Выборг", "Выборг - Каменногорск", 0.0)
        assertEquals(128_900.0, atVyborg!!, 0.01)
        assertTrue(NativeJourneyPosition.isCurrentLeg(atVyborg, 128_900.0, 134_500.0))
        assertFalse(NativeJourneyPosition.isCurrentLeg(atVyborg, 0.0, 5_100.0))
    }

    @Test fun throughAxisDoesNotHighlightPreviousLegAtSharedBoundary() {
        assertFalse(NativeJourneyPosition.isCurrentLeg(128_900.0, 124_200.0, 128_900.0))
        assertTrue(NativeJourneyPosition.isCurrentLeg(128_900.0, 128_900.0, 134_500.0))
    }
}
