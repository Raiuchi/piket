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

    @Test fun zanevskyAxisResetKeepsDachaTimetableMovingForward() {
        val beforeReset = NativeJourneyPosition.unifiedMeters(
            "Д. Долг - Павлово", "Д. Долг - Павлово", 4_900.0, "804", 4_900.0
        )
        val afterReset = NativeJourneyPosition.unifiedMeters(
            "Д. Долг - Павлово", "Д. Долг - Павлово", 2_000.0, "804", 6_074.0
        )
        assertEquals(4_900.0, beforeReset!!, 0.01)
        assertEquals(6_074.0, afterReset!!, 0.01)
        assertTrue("the 5 km → 2 km official reset must not rewind the timetable", afterReset > beforeReset)
        assertTrue(NativeJourneyPosition.isCurrentLeg(afterReset, 4_800.0, 10_900.0))
    }

    @Test fun dachaTechnicalLegsMeetTimetableAtPavlovoAndGory() {
        assertEquals(29_200.0, NativeJourneyPosition.unifiedMeters(
            "Д. Долг - Павлово", "Павлово - Горы II путь", 19_000.0, "804", 21_199.0
        )!!, 0.01)
        assertEquals(42_000.0, NativeJourneyPosition.unifiedMeters(
            "Д. Долг - Павлово", "Павлово - Горы II путь", 42_000.0, "804", 33_500.0
        )!!, 0.01)
        assertEquals(42_000.0, NativeJourneyPosition.unifiedMeters(
            "Горы - Петрозаводск", "Горы - Павлово I путь", 52_000.0, "803", 52_000.0
        )!!, 0.01)
        assertEquals(29_200.0, NativeJourneyPosition.unifiedMeters(
            "Горы - Петрозаводск", "Горы - Павлово I путь", 29_000.0, "803", 28_200.0
        )!!, 0.01)
    }
}
