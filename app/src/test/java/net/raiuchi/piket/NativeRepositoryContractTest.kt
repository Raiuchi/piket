package net.raiuchi.piket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeRepositoryContractTest {
    private fun projectFile(path: String): File = listOf(File(path), File("../$path")).first { it.exists() }
    private fun json(path: String) = JSONObject(projectFile("app/src/main/assets/data/$path").readText())

    @Test fun productionAndTestSourcesAreKotlinOnlyCompose() {
        val main = projectFile("app/src/main/java/net/raiuchi/piket/MainActivity.kt").readText()
        val gradle = projectFile("app/build.gradle").readText()
        assertTrue(main.contains("ComponentActivity") && main.contains("setContent"))
        assertFalse(main.contains("WebView"))
        assertTrue(gradle.contains("compose true") && gradle.contains("compose-bom"))
        val sourceFiles = sequenceOf(projectFile("app/src/main"), projectFile("app/src/test"), projectFile("app/src/androidTest"))
            .flatMap { it.walkTopDown().asSequence() }.filter { it.isFile }.toList()
        assertFalse(sourceFiles.any { it.extension in setOf("java", "js", "mjs", "html") })
    }

    @Test fun allRouteGeometryAndOfficialAxesRemainComplete() {
        val root = json("routes.json")
        val tracks = root.getJSONObject("tracks")
        val labels = tracks.getJSONArray("labels")
        val segments = tracks.getJSONArray("segs")
        val axes = root.getJSONArray("chainage")
        assertEquals(10, labels.length()); assertEquals(labels.length(), segments.length()); assertEquals(labels.length(), axes.length())
        var points = 0
        repeat(labels.length()) { routeIndex ->
            val route = segments.getJSONArray(routeIndex); val axis = axes.getJSONArray(routeIndex)
            assertTrue(route.length() >= 2); assertEquals(route.length(), axis.length()); points += route.length()
            repeat(route.length()) { pointIndex ->
                val point = route.getJSONArray(pointIndex); assertEquals(3, point.length())
                repeat(3) { value -> assertTrue(point.getDouble(value).isFinite()) }
            }
        }
        assertEquals(1_558, points)
    }

    @Test fun allSchedulesRemainCompleteUniqueAndMonotonic() {
        val trains = json("schedules.json").getJSONArray("trains")
        assertEquals(66, trains.length())
        val numbers = mutableSetOf<String>(); var passages = 0
        val expected = mapOf("751" to 49, "754" to 49, "804" to 34, "819" to 61, "820" to 61, "841" to 40, "842" to 39)
        repeat(trains.length()) { index ->
            val train = trains.getJSONObject(index); val number = train.getString("number")
            assertTrue("duplicate train $number", numbers.add(number))
            val stops = train.getJSONArray("stops"); passages += stops.length()
            expected[number]?.let { assertEquals("train $number", it, stops.length()) }
            var previous: Int? = null; var wraps = 0
            repeat(stops.length()) { stopIndex ->
                val stop = stops.getJSONObject(stopIndex)
                val raw = listOf("dep", "arr").firstNotNullOfOrNull { key -> stop.optString(key).takeIf(String::isNotBlank) }
                    ?: return@repeat
                val parts = raw.split(':').map(String::toInt); val clock = parts[0] * 3_600 + parts[1] * 60 + parts.getOrElse(2) { 0 }
                if (previous != null && clock < previous!! % 86_400) wraps++
                val current = clock + wraps * 86_400
                assertTrue("$number non-monotonic near ${stop.getString("station")}", previous == null || current >= previous!!)
                previous = current
            }
            assertTrue("$number has multiple midnight transitions", wraps <= 1)
        }
        assertEquals(2_712, passages)
    }

    @Test fun speedTimingAndTechnicalJourneysRemainComplete() {
        val speedRoutes = json("speed-reference.json").getJSONArray("routes")
        assertEquals(15, speedRoutes.length()); var rows = 0
        repeat(speedRoutes.length()) { routeIndex ->
            val groups = speedRoutes.getJSONObject(routeIndex).getJSONArray("groups")
            repeat(groups.length()) { rows += groups.getJSONObject(it).getJSONArray("rows").length() }
        }
        assertEquals(1_306, rows)
        val timing = json("timing.json")
        assertEquals(10, timing.getJSONObject("stations").length()); assertEquals(4, timing.getJSONArray("railChains").length())
        val journeys = json("journeys.json").getJSONObject("journeys")
        assertTrue(journeys.getJSONArray("819").length() > 1); assertTrue(journeys.getJSONArray("820").length() > 1)
    }
}
