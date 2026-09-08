package net.raiuchi.piket

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class NativeTripEngineTest {
    private lateinit var routes: NativeRouteEngine
    private lateinit var route: NativeRouteEngine.Route
    private lateinit var engine: NativeTripEngine

    @Before fun setup() {
        val dataFile = listOf(File("app/src/main/assets/data/routes.json"),
            File("src/main/assets/data/routes.json")).first { it.exists() }
        routes = NativeRouteEngine.fromJson(dataFile.readText())
        route = routes.route(routes.labels().first())!!
        engine = NativeTripEngine(routes)
        engine.configure(route.label, "tuda", route.chainageM.first(), true, emptyList())
    }

    private fun snap(index: Int) = NativeRouteEngine.Snap(route.label, route.points[index].physicalM,
        route.chainageM[index], 3.0, index.coerceAtMost(route.points.lastIndex - 1))

    @Test fun initializesFromManualCalibrationAndRouteFix() {
        val output = engine.update(NativeTripEngine.Input(1_000, 0f, true, snap(0)))
        assertEquals(route.points.first().physicalM, output.physicalM!!, 0.01)
        assertEquals(route.chainageM.first(), output.officialM!!, 0.01)
    }

    @Test fun continuesCountingDuringSignalLoss() {
        engine.update(NativeTripEngine.Input(1_000, 20f, true, snap(0)))
        engine.markSignalUnavailable()
        val output = engine.update(NativeTripEngine.Input(6_000, 20f, false, null))
        assertEquals(route.points.first().physicalM + 100.0, output.physicalM!!, 0.1)
        assertTrue(output.recovering)
        assertEquals("native-count", output.source)
    }

    @Test fun requiresTwoConsistentFixesAfterSignalLoss() {
        engine.update(NativeTripEngine.Input(1_000, 20f, true, snap(0)))
        engine.markSignalUnavailable()
        val first = engine.update(NativeTripEngine.Input(3_000, 20f, true, snap(1)))
        assertTrue(first.recovering)
        val second = engine.update(NativeTripEngine.Input(4_000, 20f, true, snap(1)))
        assertFalse(second.recovering)
        assertEquals(route.points[1].physicalM, second.physicalM!!, 0.1)
    }

    @Test fun findsRestrictionByPhysicalDistanceNotBrokenOfficialSubtraction() {
        val restriction = NativeTripEngine.Restriction("r1", route.label, "tuda",
            route.chainageM[1], route.chainageM[1], 1_500.0)
        engine.configure(route.label, "tuda", route.chainageM.first(), true, listOf(restriction))
        val output = engine.update(NativeTripEngine.Input(1_000, 0f, true, snap(0)))
        assertEquals("r1", output.alertId)
        assertTrue(output.alertDistanceM!! in 0.0..1_500.0)
    }

    @Test fun reportsWhenNativePositionIsInsideRestriction() {
        val restriction = NativeTripEngine.Restriction("zone", route.label, "tuda",
            route.chainageM.first(), route.chainageM[1], 1_500.0)
        engine.configure(route.label, "tuda", route.chainageM.first(), true, listOf(restriction))
        val output = engine.update(NativeTripEngine.Input(1_000, 0f, true, snap(0)))
        assertEquals("zone", output.alertId)
        assertTrue(output.alertInZone)
        assertEquals(0.0, output.alertDistanceM!!, 0.01)
    }

    @Test fun restoredStateContinuesWithoutReturningToRouteStart() {
        engine.update(NativeTripEngine.Input(1_000, 20f, true, snap(0)))
        engine.update(NativeTripEngine.Input(6_000, 20f, false, null))
        val saved = engine.save()
        val restored = NativeTripEngine(routes)
        restored.restore(saved)
        val output = restored.update(NativeTripEngine.Input(7_000, null, false, null))
        assertTrue(output.physicalM!! > route.points.first().physicalM + 100.0)
        assertTrue(output.recovering)
    }

    @Test fun tenMinuteOutageRemainsFiniteAndDecaysSpeedGradually() {
        engine.update(NativeTripEngine.Input(1_000, 50f, true, snap(0)))
        engine.markSignalUnavailable()
        var output: NativeTripEngine.Output? = null
        for (second in 1..600) {
            output = engine.update(NativeTripEngine.Input(1_000L + second * 1_000L, null, false, null))
        }
        assertTrue(output!!.physicalM!!.isFinite())
        assertTrue(output.speedMps in 1f..49f)
        assertTrue(output.physicalM!! > route.points.first().physicalM + 5_000.0)
    }

    @Test fun stoppedStateCannotResumeAsActiveAfterProcessRestart() {
        engine.update(NativeTripEngine.Input(1_000, 20f, true, snap(0)))
        engine.stop()
        val restored = NativeTripEngine(routes)
        restored.restore(engine.save())
        assertFalse(restored.update(NativeTripEngine.Input(2_000, null, false, null)).active)
    }

    @Test fun manualOneKilometerOffsetSurvivesTrustedGpsAndRecoveryOnEveryRoute() {
        routes.labels().forEach { label ->
            val testedRoute = routes.route(label)!!
            listOf("tuda", "obratno").forEach { direction ->
                listOf(0, testedRoute.points.lastIndex / 2, testedRoute.points.lastIndex).distinct().forEach { pointIndex ->
                    listOf(-1_000.0, 1_000.0).forEach { offset ->
                        val tested = NativeTripEngine(routes)
                        val routeSnap = NativeRouteEngine.Snap(label, testedRoute.points[pointIndex].physicalM,
                            testedRoute.chainageM[pointIndex], 3.0, pointIndex.coerceAtMost(testedRoute.points.lastIndex - 1))
                        val expected = testedRoute.chainageM[pointIndex] + offset
                        tested.configure(label, direction, expected, true, emptyList())
                        assertEquals("$label $direction point $pointIndex initial", expected,
                            tested.update(NativeTripEngine.Input(1_000, 0f, true, routeSnap)).officialM!!, 0.01)
                        tested.markSignalUnavailable()
                        tested.update(NativeTripEngine.Input(20_000, null, false, null))
                        tested.update(NativeTripEngine.Input(21_000, 0f, true, routeSnap))
                        val recovered = tested.update(NativeTripEngine.Input(22_000, 0f, true, routeSnap))
                        assertEquals("$label $direction point $pointIndex recovery", expected, recovered.officialM!!, 0.01)
                        val restored = NativeTripEngine(routes).also { it.restore(tested.save()) }
                        restored.update(NativeTripEngine.Input(23_000, 0f, true, routeSnap))
                        val afterRestart = restored.update(NativeTripEngine.Input(24_000, 0f, true, routeSnap))
                        assertEquals("$label $direction point $pointIndex restart", expected, afterRestart.officialM!!, 0.01)
                    }
                }
            }
        }
    }
}
