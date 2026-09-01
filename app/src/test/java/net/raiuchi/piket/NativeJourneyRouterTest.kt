package net.raiuchi.piket

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class NativeJourneyRouterTest {
    private lateinit var router: NativeJourneyRouter

    @Before fun setup() {
        val file = listOf(File("app/src/main/assets/data/timing.json"), File("src/main/assets/data/timing.json")).first { it.exists() }
        val journeys = listOf(File("app/src/main/assets/data/journeys.json"), File("src/main/assets/data/journeys.json")).first { it.exists() }
        router = NativeJourneyRouter.fromTimingJson(file.readText(), journeys.readText())
    }

    @Test fun followsDachaAndVyborgChainsInBothDirections() {
        assertEquals("Павлово - Горы II путь", router.nextRoute("Д. Долг - Павлово", "tuda"))
        assertEquals("Горы - Павлово I путь", router.nextRoute("Горы - Петрозаводск", "obratno"))
        assertEquals("Выборг - Каменногорск", router.nextRoute("СПбФин - Выборг", "tuda"))
        assertEquals("СПбФин - Выборг", router.nextRoute("Выборг - Каменногорск", "obratno"))
    }

    @Test fun requiresTwoReliableBoundaryFixes() {
        assertNull(router.consider(null, "СПбФин - Выборг", "tuda", 128_700.0, 128_900.0, 130.0, 15.0, 128_700.0))
        assertEquals("Выборг - Каменногорск", router.consider(null, "СПбФин - Выборг", "tuda", 128_750.0, 128_900.0, 120.0, 12.0, 128_750.0)?.route)
    }

    @Test fun refusesEarlyOrAmbiguousSwitch() {
        assertNull(router.consider(null, "СПбФин - Выборг", "tuda", 100_000.0, 128_900.0, 150.0, 10.0, 100_000.0))
        assertNull(router.consider(null, "СПбФин - Выборг", "tuda", 128_800.0, 128_900.0, 20.0, 18.0, 128_800.0))
    }

    @Test fun follows819And820TechnicalDirections() {
        assertEquals(NativeJourneyRouter.Transition("Горы - Петрозаводск", "tuda"),
            router.nextLeg("819", "Волховстрой - Чудово", "obratno"))
        assertEquals(NativeJourneyRouter.Transition("Волховстрой - Чудово", "tuda"),
            router.nextLeg("820", "Горы - Петрозаводск", "obratno"))
        assertEquals(NativeJourneyRouter.Transition("Чудово - Новгород", "obratno"),
            router.nextLeg("820", "Чудово - Новгород", "tuda"))
    }

    @Test fun confirmsNovgorodCabChangeFromActualReverseMovement() {
        assertNull(router.consider("820", "Чудово - Новгород", "tuda", 67_590.0, 67_600.0, 10.0, 10.0, 67_590.0))
        assertNull(router.consider("820", "Чудово - Новгород", "tuda", 67_585.0, 67_600.0, 10.0, 10.0, 67_580.0))
        val switched = router.consider("820", "Чудово - Новгород", "tuda", 67_580.0, 67_600.0, 10.0, 10.0, 67_570.0)
        assertEquals("Чудово - Новгород", switched?.route)
        assertEquals("obratno", switched?.direction)
    }
}
