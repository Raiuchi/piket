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

    @Test fun chudovoAndVolhovLegsMeetOnOneContinuousAxis() {
        val atChudovoFromNovgorod = NativeJourneyPosition.unifiedMeters(
            "Чудово - Новгород", "Чудово - Новгород", 0.0, "820"
        )
        val atChudovoFromVolhov = NativeJourneyPosition.unifiedMeters(
            "Чудово - Новгород", "Волховстрой - Чудово", 101_000.0, "820"
        )
        assertEquals(70_000.0, atChudovoFromNovgorod!!, 0.01)
        assertEquals(atChudovoFromNovgorod, atChudovoFromVolhov)
    }

    @Test fun volhovAndPetrozavodskLegsMeetOnOneContinuousAxis() {
        val atVolhovFromChudovo = NativeJourneyPosition.unifiedMeters(
            "Волховстрой - Чудово", "Волховстрой - Чудово", 0.0, "819"
        )
        val atVolhovFromPetrozavodsk = NativeJourneyPosition.unifiedMeters(
            "Волховстрой - Чудово", "Горы - Петрозаводск", 124_400.0, "819"
        )
        assertEquals(171_000.0, atVolhovFromChudovo!!, 0.01)
        assertEquals(atVolhovFromChudovo, atVolhovFromPetrozavodsk)
    }

    @Test fun everySharedBoundarySelectsOnlyTheFollowingCard() {
        val boundaries = listOf(29_200.0, 42_000.0, 70_000.0, 128_900.0, 171_000.0)
        boundaries.forEach { boundary ->
            assertFalse(NativeJourneyPosition.isCurrentLeg(boundary, boundary - 10_000.0, boundary))
            assertTrue(NativeJourneyPosition.isCurrentLeg(boundary, boundary, boundary + 10_000.0))
        }
    }
}
