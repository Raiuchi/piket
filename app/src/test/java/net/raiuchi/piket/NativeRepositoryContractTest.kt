package net.raiuchi.piket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeRepositoryContractTest {
    private fun projectFile(path: String): File = listOf(File(path), File("../$path")).first { it.exists() }
    private fun json(path: String) = JSONObject(projectFile("app/src/main/assets/data/$path").readText())

    @Test fun premiumHtmlIsViewAndCriticalEngineRemainsKotlin() {
        val main = projectFile("app/src/main/java/net/raiuchi/piket/MainActivity.kt").readText()
        val service = projectFile("app/src/main/java/net/raiuchi/piket/TrackingService.kt").readText()
        val html = projectFile("app/src/main/assets/index.html").readText()
        assertTrue(main.contains("WebView") && main.contains("publishSnapshot"))
        assertTrue(main.contains("api.github.com/repos/Raiuchi/piket/releases/latest"))
        assertTrue(main.contains("browser_download_url") && main.contains("showUpdateBanner"))
        assertTrue(service.contains("NativeTripEngine") && service.contains("NativeMotionFilter"))
        assertTrue(html.contains("Kotlin is the source of truth"))
        val sourceFiles = sequenceOf(projectFile("app/src/main"), projectFile("app/src/test"), projectFile("app/src/androidTest"))
            .flatMap { it.walkTopDown().asSequence() }.filter { it.isFile }.toList()
        assertFalse(sourceFiles.any { it.extension == "java" })
    }

    @Test fun premiumWebAssetsCalibrationAndNativeBridgeAreBundled() {
        val html = projectFile("app/src/main/assets/index.html").readText()
        val service = projectFile("app/src/main/java/net/raiuchi/piket/TrackingService.kt").readText()
        assertTrue(projectFile("app/src/main/assets/icons/piket-signal.gif").length() > 100_000)
        assertTrue(projectFile("app/src/main/assets/assets/piket-core.js").length() > 50_000)
        assertTrue(projectFile("app/src/main/assets/assets/piket-schedules.js").length() > 100_000)
        assertTrue(html.contains("VYBORG_THROUGH=\"СПбФин - Каменногорск\""))
        assertTrue(html.contains("DACHA_THROUGH=\"Дача Долгорукова - Петрозаводск\""))
        assertTrue(html.contains("CHUDOVO_DUTY=\"Чудово - Петрозаводск\""))
        assertTrue(html.contains("Вышло обновление") && html.contains("id=\"ubDownload\""))
        assertTrue(html.contains("label!==\"Все участки\"") && html.contains("state.ctx.peregon===\"Все участки\""))
        assertTrue(html.contains("syncNativeRouteContext();") && html.contains("window.Android.startTracking();"))
        assertTrue(html.contains("manualOfficialM:manual") && html.contains("m:+r.m||0") && html.contains("journey:nativeJourney"))
        assertTrue(html.contains("return 128900+rt.posM"))
        assertTrue(html.contains("r.kmE!=null?metersOf(r.kmE,r.pkE):start+100"))
        assertFalse(html.contains("+r.spd>0&&r.kmE!=null"))
        assertTrue(service.contains("optDouble(\"pk\", 1.0) - 1.0"))
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
        val expected = mapOf("751" to 49, "754" to 49, "768" to 49, "804" to 34, "819" to 61, "820" to 61, "841" to 40, "842" to 39)
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

    @Test fun everyTimetableRendersFromItsFirstStopAndProgressesOnlyForward() {
        val trains = json("schedules.json").getJSONArray("trains")
        val train768 = (0 until trains.length()).map(trains::getJSONObject)
            .first { it.getString("number") == "768" }
        val first = train768.getJSONArray("stops").getJSONObject(0)
        assertEquals("МОСКВА ПАС.ОКТ.", first.getString("station"))
        assertEquals("13:30", first.getString("dep"))

        val html = projectFile("app/src/main/assets/index.html").readText()
        assertTrue(html.contains("\"москвапасокт\":\"москвапассажирская\""))
        assertTrue(html.contains("if(!rt.tracking||rt.posM==null)return null"))
        assertTrue(html.contains("if(schedulePos==null){rt.scheduleProgressIndex=0"))
        assertTrue(html.contains("candidate>rt.scheduleProgressIndex"))
        assertFalse(html.contains("if(index<first||index>last)return"))
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
