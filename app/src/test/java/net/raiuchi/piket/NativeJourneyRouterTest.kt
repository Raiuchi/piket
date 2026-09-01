package net.raiuchi.piket

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class NativeJourneyRouterTest {
    private lateinit var router: NativeJourneyRouter

    @Before fun setup() {
        val file = listOf(File("app/src/main/assets/data/timing.json"), File("src/main/assets/data/timing.json")).first { it.exists() }
        router = NativeJourneyRouter.fromTimingJson(file.readText())
    }

    @Test fun followsDachaAndVyborgChainsInBothDirections() {
        assertEquals("Павлово - Горы II путь", router.nextRoute("Д. Долг - Павлово", "tuda"))
        assertEquals("Горы - Павлово I путь", router.nextRoute("Горы - Петрозаводск", "obratno"))
        assertEquals("Выборг - Каменногорск", router.nextRoute("СПбФин - Выборг", "tuda"))
        assertEquals("СПбФин - Выборг", router.nextRoute("Выборг - Каменногорск", "obratno"))
    }

    @Test fun requiresTwoReliableBoundaryFixes() {
        assertNull(router.consider("СПбФин - Выборг", "tuda", 128_700.0, 128_900.0, 130.0, 15.0))
        assertEquals("Выборг - Каменногорск", router.consider("СПбФин - Выборг", "tuda", 128_750.0, 128_900.0, 120.0, 12.0))
    }

    @Test fun refusesEarlyOrAmbiguousSwitch() {
        assertNull(router.consider("СПбФин - Выборг", "tuda", 100_000.0, 128_900.0, 150.0, 10.0))
        assertNull(router.consider("СПбФин - Выборг", "tuda", 128_800.0, 128_900.0, 20.0, 18.0))
    }
}
