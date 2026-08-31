package net.raiuchi.piket

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

class NativeRouteEngineTest {
    companion object {
        private lateinit var engine: NativeRouteEngine

        @JvmStatic @BeforeClass fun loadRealRoutes() {
            val coreFile = listOf(File("app/src/main/assets/assets/piket-core.js"),
                File("src/main/assets/assets/piket-core.js")).first { it.exists() }
            val core = coreFile.readText()
            engine = NativeRouteEngine.fromCoreJs(core)
        }
    }

    @Test fun parsesEveryProductionRouteAndPoint() {
        assertEquals(10, engine.routeCount)
        assertEquals(1558, engine.pointCount)
    }

    @Test fun everyControlPointKeepsExactOfficialKilometer() {
        for (label in engine.labels()) {
            val route = engine.route(label)!!
            route.points.indices.forEach { index ->
                assertEquals("$label point $index", route.chainageM[index],
                    engine.officialMeters(label, route.points[index].physicalM)!!, 0.001)
            }
        }
    }

    @Test fun discontinuitiesSwitchAtControlPointInsteadOfStretching() {
        var transitions = 0
        for (label in engine.labels()) {
            val route = engine.route(label)!!
            for (i in 0 until route.points.lastIndex) {
                val physicalDelta = route.points[i + 1].physicalM - route.points[i].physicalM
                val officialDelta = route.chainageM[i + 1] - route.chainageM[i]
                if (officialDelta <= 0 || abs(officialDelta - physicalDelta) > 3_000) {
                    transitions++
                    val before = route.points[i + 1].physicalM - 2.0
                    assertEquals(route.chainageM[i] + before - route.points[i].physicalM,
                        engine.officialMeters(label, before)!!, 0.001)
                    assertEquals(route.chainageM[i + 1],
                        engine.officialMeters(label, route.points[i + 1].physicalM)!!, 0.001)
                }
            }
        }
        assertTrue("production data must exercise kilometer-axis changes", transitions >= 5)
    }

    @Test fun snappingEveryControlPointReturnsItsPhysicalPosition() {
        for (label in engine.labels()) {
            val route = engine.route(label)!!
            route.points.forEachIndexed { index, point ->
                val snap = engine.snap(label, point.latitude, point.longitude)!!
                assertTrue("$label point $index is too far from route", snap.distanceM < 2.0)
                assertTrue("$label point $index snapped to wrong location",
                    abs(snap.physicalM - point.physicalM) < 5.0)
            }
        }
    }
}
