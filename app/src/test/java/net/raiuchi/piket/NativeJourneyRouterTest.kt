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

    @Test fun allDocumentedVyborgAndChudovoTransitionsConfirmSafely() {
        val cases = listOf(
            arrayOf<String?>("null", "СПбФин - Выборг", "tuda"),
            arrayOf<String?>("null", "Выборг - Каменногорск", "obratno"),
            arrayOf<String?>("819", "Волховстрой - Чудово", "obratno"),
            arrayOf<String?>("820", "Горы - Петрозаводск", "obratno"),
            arrayOf<String?>("820", "Волховстрой - Чудово", "tuda"),
            arrayOf<String?>("820", "Чудово - Новгород", "tuda")
        )
        cases.forEach { (journey, current, direction) ->
            val fresh = NativeJourneyRouter.fromTimingJson(timingSource, journeySource)
            val currentLabel = current!!
            val travelDirection = direction!!
            val transition = fresh.nextLeg(journey, currentLabel, travelDirection)!!
            val boundary = transition.boundaryM!!
            assertNull(fresh.consider(journey, currentLabel, travelDirection, boundary, boundary,
                120.0, 10.0, boundary, stopped = true))
            val switched = fresh.consider(journey, currentLabel, travelDirection, boundary, boundary,
                120.0, 10.0, boundary, stopped = true)
            assertNotNull("$journey $current $direction", switched)
            assertEquals(transition.route, switched?.route)
            assertEquals(transition.direction, switched?.direction)
        }
    }

    @Test fun bronevayaLugaHasNoAutomaticAxisTransition() {
        assertNull(router.nextRoute("Броневая - Луга", "tuda"))
        assertNull(router.nextRoute("Броневая - Луга", "obratno"))
        assertNull(router.nextLeg(null, "Броневая - Луга", "tuda"))
        assertNull(router.nextLeg(null, "Броневая - Луга", "obratno"))
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
        assertEquals("Горы - Петрозаводск",
            router.nextLeg("819", "Волховстрой - Чудово", "obratno")?.route)
        assertEquals("Волховстрой - Чудово",
            router.nextLeg("820", "Горы - Петрозаводск", "obratno")?.route)
        assertEquals("Чудово - Новгород",
            router.nextLeg("820", "Чудово - Новгород", "tuda")?.route)
    }

    @Test fun usesDocumentedProductionBoundariesInsteadOfPolylineEndpoints() {
        val vyborg = router.nextLeg(null, "СПбФин - Выборг", "tuda")!!
        assertEquals(128_900.0, vyborg.boundaryM!!, 0.01)
        assertTrue(vyborg.requireStop)
        assertEquals(150.0, vyborg.maxNextDistanceM, 0.01)

        val volkhov = router.nextLeg("820", "Горы - Петрозаводск", "obratno")!!
        assertEquals(124_400.0, volkhov.boundaryM!!, 0.01)
        assertFalse(volkhov.requireStop)

        val chudovo = router.nextLeg("820", "Волховстрой - Чудово", "tuda")!!
        assertEquals(101_000.0, chudovo.boundaryM!!, 0.01)
        assertEquals(5_000.0, chudovo.maxNextDistanceM, 0.01)
        assertTrue(chudovo.requireStop)

        val novgorod = router.nextLeg("820", "Чудово - Новгород", "tuda")!!
        assertEquals(75_175.0, novgorod.boundaryM!!, 0.01)
        assertTrue(novgorod.requireStop)
    }

    @Test fun cabChangeWaitsForStopAndTwoReliableFixes() {
        assertNull(router.consider(null, "СПбФин - Выборг", "tuda",
            128_900.0, 128_900.0, 0.0, 48.0, 128_900.0, stopped = false))
        assertNull(router.consider(null, "СПбФин - Выборг", "tuda",
            128_900.0, 128_900.0, 0.0, 48.0, 128_900.0, stopped = true))
        val switched = router.consider(null, "СПбФин - Выборг", "tuda",
            128_900.0, 128_900.0, 0.0, 48.0, 128_900.0, stopped = true)
        assertEquals("Выборг - Каменногорск", switched?.route)
    }

    @Test fun confirmsNovgorodCabChangeAtActualRouteEndpoint() {
        assertNull(router.consider("820", "Чудово - Новгород", "tuda",
            75_175.0, 75_175.0, 0.0, 0.0, 75_175.0, stopped = false))
        assertNull(router.consider("820", "Чудово - Новгород", "tuda",
            75_175.0, 75_175.0, 0.0, 0.0, 75_175.0, stopped = true))
        val switched = router.consider("820", "Чудово - Новгород", "tuda",
            75_175.0, 75_175.0, 0.0, 0.0, 75_175.0, stopped = true)
        assertEquals("Чудово - Новгород", switched?.route)
        assertEquals("obratno", switched?.direction)
    }
}
