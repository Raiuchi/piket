package net.raiuchi.piket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRouteCatalogTest {
    @Test fun technicalSegmentsAreHiddenInsideUserDirectionsAndThroughJourneys() {
        assertEquals(8, NativeRouteCatalog.choices.size)
        val titles = NativeRouteCatalog.choices.map { it.title }
        assertTrue("Дача Долгорукова — Петрозаводск" in titles)
        assertTrue("Санкт-Петербург-Финляндский — Каменногорск" in titles)
        assertFalse(titles.any { "Павлово - Горы" in it || it == "СПбФин - Выборг" })
    }

    @Test fun compositeDirectionsStartAtCorrectEndInBothDirections() {
        val dacha = NativeRouteCatalog.choices.first { "Дача Долгорукова" in it.title }
        assertEquals("Д. Долг - Павлово", dacha.start("tuda"))
        assertEquals("Горы - Петрозаводск", dacha.start("obratno"))
        val vyborg = NativeRouteCatalog.choices.first { "Каменногорск" in it.title }
        assertEquals("СПбФин - Выборг", vyborg.start("tuda"))
        assertEquals("Выборг - Каменногорск", vyborg.start("obratno"))
    }

    @Test fun everyTechnicalMemberResolvesBackToOneStableUserDirection() {
        NativeRouteCatalog.choices.filter { it.journey == null }.forEach { choice ->
            choice.members.forEach { member -> assertEquals(choice, NativeRouteCatalog.forInternalRoute(member)) }
        }
    }

    @Test fun trains819And820AreSelectedAsThroughJourneysWithoutExtraTechnicalMenu() {
        val train819 = NativeRouteCatalog.choices.first { it.journey == "819" }
        val train820 = NativeRouteCatalog.choices.first { it.journey == "820" }
        assertEquals("Волховстрой - Чудово", train819.start(train819.fixedDirection!!))
        assertEquals("Горы - Петрозаводск", train820.start(train820.fixedDirection!!))
    }
}
