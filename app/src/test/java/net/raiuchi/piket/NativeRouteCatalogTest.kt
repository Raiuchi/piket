package net.raiuchi.piket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRouteCatalogTest {
    @Test fun technicalSegmentsAreHiddenInsideSixUserDirections() {
        assertEquals(6, NativeRouteCatalog.choices.size)
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
        NativeRouteCatalog.choices.forEach { choice ->
            choice.members.forEach { member -> assertEquals(choice, NativeRouteCatalog.forInternalRoute(member)) }
        }
    }
}
