package net.raiuchi.piket

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class NativeJourneyRouterTest {
    private lateinit var router: NativeJourneyRouter
    private lateinit var timingSource: String
    private lateinit var journeySource: String
    private lateinit var routes: NativeRouteEngine

    @Before fun setup() {
        val file = listOf(File("app/src/main/assets/data/timing.json"), File("src/main/assets/data/timing.json")).first { it.exists() }
        val journeys = listOf(File("app/src/main/assets/data/journeys.json"), File("src/main/assets/data/journeys.json")).first { it.exists() }
        timingSource = file.readText()
        journeySource = journeys.readText()
        router = NativeJourneyRouter.fromTimingJson(timingSource, journeySource)
        val routeFile = listOf(File("app/src/main/assets/data/routes.json"), File("src/main/assets/data/routes.json")).first { it.exists() }
        routes = NativeRouteEngine.fromJson(routeFile.readText())
    }

    @Test fun followsDachaAndVyborgChainsInBothDirections() {
        assertEquals("Павлово - Горы II путь", router.nextRoute("Д. Долг - Павлово", "tuda"))
        assertEquals("Горы - Петрозаводск", router.nextRoute("Павлово - Горы II путь", "tuda"))
        assertEquals("Горы - Павлово I путь", router.nextRoute("Горы - Петрозаводск", "obratno"))
        assertEquals("Д. Долг - Павлово", router.nextRoute("Горы - Павлово I путь", "obratno"))
        assertEquals("Выборг - Каменногорск", router.nextRoute("СПбФин - Выборг", "tuda"))
        assertEquals("СПбФин - Выборг", router.nextRoute("Выборг - Каменногорск", "obratno"))
    }

    @Test fun everyDachaPetrozavodskBoundaryReallySwitchesInBothDirections() {
        val transitions = listOf(
            Triple("Д. Долг - Павлово", "Павлово - Горы II путь", "tuda"),
            Triple("Павлово - Горы II путь", "Горы - Петрозаводск", "tuda"),
            Triple("Горы - Петрозаводск", "Горы - Павлово I путь", "obratno"),
            Triple("Горы - Павлово I путь", "Д. Долг - Павлово", "obratno")
        )
        transitions.forEach { (currentLabel, nextLabel, direction) ->
            val current = routes.route(currentLabel)!!
            val next = routes.route(nextLabel)!!
            val boundaryPoint = if (direction == "tuda") current.points.last() else current.points.first()
            val junction = next.points.indices.minBy { index ->
                val p = next.points[index]
                (p.latitude - boundaryPoint.latitude) * (p.latitude - boundaryPoint.latitude) +
                    (p.longitude - boundaryPoint.longitude) * (p.longitude - boundaryPoint.longitude)
            }
            val probeIndex = (junction + if (direction == "tuda") 1 else -1).coerceIn(0, next.points.lastIndex)
            val probe = next.points[probeIndex]
            val currentSnap = routes.snap(currentLabel, probe.latitude, probe.longitude)!!
            val nextSnap = routes.snap(nextLabel, probe.latitude, probe.longitude)!!
            val boundaryM = boundaryPoint.physicalM
            val freshRouter = NativeJourneyRouter.fromTimingJson(timingSource, journeySource)
            assertNull(freshRouter.consider("null", currentLabel, direction, boundaryM, boundaryM,
                currentSnap.distanceM, nextSnap.distanceM, currentSnap.physicalM))
            val switched = freshRouter.consider("null", currentLabel, direction, boundaryM, boundaryM,
                currentSnap.distanceM, nextSnap.distanceM, currentSnap.physicalM)
            assertEquals("$currentLabel → $nextLabel", nextLabel, switched?.route)
            assertEquals(direction, switched?.direction)
        }
    }

    @Test fun endpointProjectionRecoversMissedTransitionAfterCountingDrift() {
        repeat(2) { index ->
            val result = router.consider("null", "Павлово - Горы II путь", "tuda",
                40_000.0, 33_500.0, 120.0, 10.0, 33_500.0)
            if (index == 0) assertNull(result) else assertEquals("Горы - Петрозаводск", result?.route)
        }
    }

    @Test fun requiresTwoReliableBoundaryFixes() {
        assertNull(router.consider(null, "СПбФин - Выборг", "tuda", 128_700.0, 128_900.0, 130.0, 15.0, 128_700.0))
        assertEquals("Выборг - Каменногорск", router.consider(null, "СПбФин - Выборг", "tuda", 128_750.0, 128_900.0, 120.0, 12.0, 128_750.0)?.route)
    }

    @Test fun refusesEarlyOrAmbiguousSwitch() {
        assertNull(router.consider(null, "СПбФин - Выборг", "tuda", 100_000.0, 128_900.0, 150.0, 10.0, 100_000.0))
        assertNull(router.consider(null, "СПбФин - Выборг", "tuda", 128_800.0, 128_900.0, 20.0, 18.0, 128_800.0))
    }

    @Test fun missedPavlovoTransitionRecoversWellBeyondOldEndpoint() {
        val label = "Горы - Павлово I путь"
        assertNull(router.consider(null, label, "obratno", 22_000.0, 28_200.0, 6_000.0, 15.0, 28_200.0))
        assertEquals("Д. Долг - Павлово", router.consider(null, label, "obratno",
            21_980.0, 28_200.0, 6_020.0, 12.0, 28_200.0)?.route)
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
