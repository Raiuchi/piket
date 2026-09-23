package net.raiuchi.piket.replay

import net.raiuchi.piket.NativeJourneyRouter
import net.raiuchi.piket.NativeMotionFilter
import net.raiuchi.piket.NativeRouteEngine
import net.raiuchi.piket.NativeTripEngine
import net.raiuchi.piket.RouteSpeedCeilings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

private data class TimelineEntry(val elapsedMs: Long, val order: Long, val event: JSONObject? = null, val trace: JSONArray? = null)

data class ReplaySessionReport(
    val sessionId: String,
    val completeTrace: Boolean,
    val gpsRecords: Int,
    val tickRecords: Int,
    val replayTransitions: List<String>,
    val recordedTransitions: List<String>,
    val discrepancies: List<String>
) {
    val clean: Boolean get() = completeTrace && discrepancies.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("session_id", sessionId)
        .put("complete_trace", completeTrace)
        .put("gps_records", gpsRecords)
        .put("tick_records", tickRecords)
        .put("clean", clean)
        .put("replay_transitions", JSONArray(replayTransitions))
        .put("recorded_transitions", JSONArray(recordedTransitions))
        .put("discrepancies", JSONArray(discrepancies))
}

data class ReplayReport(val sessions: List<ReplaySessionReport>) {
    val clean: Boolean get() = sessions.isNotEmpty() && sessions.all { it.clean }
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("engine", "production-kotlin")
        .put("clean", clean)
        .put("sessions", JSONArray(sessions.map { it.toJson() }))
}

object DiagnosticReplay {
    fun run(logText: String): ReplayReport {
        val rows = logText.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) null else runCatching { JSONObject(trimmed) }.getOrNull()
        }.toList()
        val sessions = linkedSetOf<String>()
        rows.forEach { row ->
            row.optNullableString("trip_session_id")?.let(sessions::add)
        }
        if (sessions.isEmpty()) return ReplayReport(emptyList())
        val resources = Resources.load()
        return ReplayReport(sessions.map { replaySession(it, rows, resources) })
    }

    private fun replaySession(id: String, rows: List<JSONObject>, resources: Resources): ReplaySessionReport {
        val timeline = mutableListOf<TimelineEntry>()
        var sequence = 0L
        rows.forEach { row ->
            if (row.optNullableString("trip_session_id") != id) return@forEach
            val event = row.optString("event")
            if (event == "replay_trace_batch") {
                val records = row.optJSONArray("records") ?: return@forEach
                repeat(records.length()) { index ->
                    val record = records.optJSONArray(index) ?: return@repeat
                    timeline += TimelineEntry(record.optLong(1), sequence++ * 2 + 1, trace = record)
                }
            } else {
                timeline += TimelineEntry(row.optLong("elapsed_ms", row.optLong("time", 0L)), sequence++ * 2, event = row)
            }
        }
        timeline.sortWith(compareBy<TimelineEntry> { it.elapsedMs }.thenBy { if (it.event != null) 0 else 1 }.thenBy { it.order })

        val routeEngine = NativeRouteEngine.fromJson(resources.routes)
        val motion = NativeMotionFilter()
        val trip = NativeTripEngine(routeEngine)
        val router = NativeJourneyRouter.fromTimingJson(resources.timing, resources.journeys)
        val issues = mutableListOf<String>()
        val replayTransitions = mutableListOf<String>()
        val recordedTransitions = mutableListOf<String>()
        var route = "Все участки"
        var direction = "tuda"
        var journey: String? = null
        var train: String? = null
        var manualOfficialM = 0.0
        var active = false
        var restrictions = emptyList<NativeTripEngine.Restriction>()
        var gpsRecords = 0
        var tickRecords = 0
        var outageMarkedAt = Long.MIN_VALUE

        fun configure() {
            motion.setSpeedCeilingsKmh(RouteSpeedCeilings.maxKmh(route, train), RouteSpeedCeilings.trustedKmh(route, train))
            trip.configure(route, direction, manualOfficialM, active, restrictions)
        }

        fun issue(elapsed: Long, field: String, expected: Any?, actual: Any?) {
            if (issues.size < 250) issues += "${elapsed} мс: $field — в журнале=${expected ?: "null"}, повтор=${actual ?: "null"}"
        }

        fun compareDouble(elapsed: Long, field: String, expected: Double?, actual: Double?, tolerance: Double) {
            if (expected == null && actual == null) return
            if (expected == null || actual == null || abs(expected - actual) > tolerance) issue(elapsed, field, expected, actual)
        }

        fun compareValue(elapsed: Long, field: String, expected: Any?, actual: Any?) {
            if (expected != actual) issue(elapsed, field, expected, actual)
        }

        timeline.forEach { entry ->
            entry.event?.let { event ->
                when (event.optString("event")) {
                    "trip_session_started" -> {
                        route = event.optString("route", route)
                        direction = event.optString("direction", direction)
                        journey = event.optNullableString("journey")
                        train = event.optNullableString("train")
                        manualOfficialM = event.optDouble("manual_official_m", manualOfficialM)
                        active = true
                        configure()
                    }
                    "trip_configured" -> {
                        route = event.optString("route", route)
                        direction = event.optString("direction", direction)
                        journey = event.optNullableString("journey")
                        train = event.optNullableString("train")
                        manualOfficialM = event.optDouble("manual_official_m", manualOfficialM)
                        active = event.optBoolean("active", active)
                        configure()
                    }
                    "restrictions_configured" -> {
                        val lead = event.optDouble("lead_m", 3_000.0)
                        restrictions = event.optJSONArray("items")?.let { items ->
                            buildList {
                                repeat(items.length()) { index ->
                                    val item = items.optJSONObject(index) ?: return@repeat
                                    add(NativeTripEngine.Restriction(
                                        item.optString("id", index.toString()),
                                        item.optString("route", "Все участки"),
                                        item.optString("direction", "both"),
                                        item.optDouble("start_m"), item.optDouble("end_m"), lead,
                                        item.optNullableDouble("track_start_m"), item.optNullableDouble("track_end_m")
                                    ))
                                }
                            }
                        }.orEmpty()
                        configure()
                    }
                    "gps_outage_started" -> if (outageMarkedAt != entry.elapsedMs) {
                        outageMarkedAt = entry.elapsedMs
                        motion.markSignalUnavailable(); trip.markSignalUnavailable()
                    }
                    "route_transition" -> recordedTransitions += "${event.optString("from")} -> ${event.optString("to")}"
                }
            }
            entry.trace?.let { record ->
                when (record.optString(0)) {
                    "g" -> {
                        gpsRecords++
                        val elapsed = record.optLong(1)
                        val fix = NativeMotionFilter.Fix(
                            record.optDouble(2), record.optDouble(3), elapsed, record.optLong(4),
                            record.optDouble(5, 999.0).toFloat(), record.optNullableDouble(6)?.toFloat(),
                            record.optNullableDouble(7)?.toFloat(), record.optBoolean(8), record.optInt(9),
                            record.optDouble(10).toFloat(), record.optBoolean(11)
                        )
                        val filtered = motion.process(fix)
                        var snap = routeEngine.snap(route, fix.latitude, fix.longitude, direction)
                        if (filtered.accepted && filtered.quality in setOf("good", "stationary")) {
                            val state = trip.save()
                            val next = router.nextLeg(journey, route, state.direction)
                            val points = routeEngine.route(route)?.points
                            val endpoint = points?.takeIf { it.isNotEmpty() }?.let {
                                if (state.direction == "obratno") it.first().physicalM else it.last().physicalM
                            }
                            val boundary = next?.boundaryM ?: endpoint
                            val stopped = state.speedMps <= 1.5f
                            val nearBoundary = boundary != null &&
                                ((state.physicalM?.let { abs(it - boundary) <= 800.0 } == true) ||
                                    (snap?.let { abs(it.physicalM - boundary) <= 80.0 } == true))
                            val nextSnap = if (nearBoundary && (next?.requireStop != true || stopped)) next?.let {
                                routeEngine.snap(it.route, fix.latitude, fix.longitude, it.direction)
                            } else null
                            val transition = router.consider(journey, route, state.direction, state.physicalM,
                                boundary, snap?.distanceM, nextSnap?.distanceM, snap?.physicalM, stopped)
                            if (transition != null && nextSnap != null) {
                                val from = route
                                route = transition.route; direction = transition.direction
                                motion.setSpeedCeilingsKmh(RouteSpeedCeilings.maxKmh(route, train), RouteSpeedCeilings.trustedKmh(route, train))
                                trip.switchRoute(route, direction, nextSnap)
                                snap = nextSnap
                                replayTransitions += "$from -> $route"
                            }
                        }
                        val positionAccepted = filtered.accepted && filtered.quality in setOf("good", "stationary")
                        val provisionalCalibrationFix = trip.save().physicalM == null && !positionAccepted &&
                            fix.ageMs <= 5_000 && !fix.mock && fix.accuracyM in 1f..120f &&
                            snap?.distanceM?.let { it <= 120.0 } == true &&
                            (fix.satellitesUsed > 0 || !fix.hasGnssTelemetry)
                        val output = trip.update(NativeTripEngine.Input(elapsed, filtered.filteredSpeedMps,
                            positionAccepted, snap, filtered.stationary, provisionalCalibrationFix))
                        compareValue(elapsed, "GPS accepted", record.optBoolean(13), filtered.accepted)
                        compareDouble(elapsed, "filtered speed", record.optNullableDouble(14), filtered.filteredSpeedMps?.toDouble(), 0.02)
                        compareValue(elapsed, "stationary", record.optBoolean(15), filtered.stationary)
                        compareValue(elapsed, "quality", record.optString(16), filtered.quality)
                        compareValue(elapsed, "reason", record.optString(17), filtered.reason)
                        compareDouble(elapsed, "snap physical", record.optNullableDouble(18), snap?.physicalM, 1.0)
                        compareDouble(elapsed, "snap distance", record.optNullableDouble(19), snap?.distanceM, 1.0)
                        compareDouble(elapsed, "engine physical", record.optNullableDouble(20), output.physicalM, 1.0)
                        compareDouble(elapsed, "official", record.optNullableDouble(21), output.officialM, 1.0)
                        compareValue(elapsed, "source", record.optNullableString(22), output.source)
                        compareValue(elapsed, "recovering", record.optNullableBoolean(23), output.recovering)
                        compareValue(elapsed, "route", record.optString(24), route)
                        compareValue(elapsed, "direction", record.optNullableString(25), direction)
                    }
                    "t" -> {
                        tickRecords++
                        val elapsed = record.optLong(1)
                        val output = trip.update(NativeTripEngine.Input(elapsed, null, false, null))
                        compareDouble(elapsed, "tick physical", record.optNullableDouble(2), output.physicalM, 1.0)
                        compareDouble(elapsed, "tick official", record.optNullableDouble(3), output.officialM, 1.0)
                        compareDouble(elapsed, "tick speed", record.optDouble(4), output.speedMps.toDouble(), 0.02)
                        compareValue(elapsed, "tick source", record.optString(5), output.source)
                        compareValue(elapsed, "tick recovering", record.optBoolean(6), output.recovering)
                        compareValue(elapsed, "alert id", record.optNullableString(7), output.alertId)
                        compareDouble(elapsed, "alert distance", record.optNullableDouble(8), output.alertDistanceM, 1.0)
                        compareValue(elapsed, "alert zone", record.optBoolean(9), output.alertInZone)
                        compareValue(elapsed, "tick route", record.optString(10), route)
                        compareValue(elapsed, "tick direction", record.optNullableString(11), direction)
                    }
                }
            }
        }
        if (replayTransitions != recordedTransitions) {
            issue(timeline.lastOrNull()?.elapsedMs ?: 0L, "последовательность переходов", recordedTransitions, replayTransitions)
        }
        val hasTrace = gpsRecords + tickRecords > 0
        if (!hasTrace) issues += "В журнале нет replay_trace_batch: он создан старой версией и допускает только обычный анализ."
        return ReplaySessionReport(id, hasTrace, gpsRecords, tickRecords, replayTransitions, recordedTransitions, issues)
    }

    private data class Resources(val routes: String, val timing: String, val journeys: String) {
        companion object {
            fun load(): Resources {
                fun read(path: String): String = DiagnosticReplay::class.java.classLoader
                    .getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Не найден ресурс $path")
                return Resources(read("data/routes.json"), read("data/timing.json"), read("data/journeys.json"))
            }
        }
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeUnless { it.isBlank() || it == "null" }
private fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
private fun JSONArray.optNullableString(index: Int): String? =
    if (index >= length() || isNull(index)) null else optString(index).takeUnless { it == "null" }
private fun JSONArray.optNullableDouble(index: Int): Double? =
    if (index >= length() || isNull(index)) null else optDouble(index).takeIf { it.isFinite() }
private fun JSONArray.optNullableBoolean(index: Int): Boolean? =
    if (index >= length() || isNull(index)) null else optBoolean(index)

fun main(args: Array<String>) {
    val path = args.firstOrNull { !it.startsWith("--") }
        ?: error("Укажите путь к экспортированному piket-diagnostics.txt")
    val report = DiagnosticReplay.run(File(path).readText(Charsets.UTF_8))
    if ("--json" in args) println(report.toJson().toString(2)) else {
        println("ПИКЕТ — полное воспроизведение поездки тем же Kotlin-движком")
        if (report.sessions.isEmpty()) println("Сессии поездки не найдены.")
        report.sessions.forEach { session ->
            println("\nСессия ${session.sessionId}: GPS ${session.gpsRecords}, тики ${session.tickRecords}")
            println(if (session.clean) "Результат: всё совпало." else "Результат: найдены расхождения — ${session.discrepancies.size}.")
            session.discrepancies.take(30).forEach { println("- $it") }
            if (session.discrepancies.size > 30) println("- ...ещё ${session.discrepancies.size - 30}; полный список доступен с --json")
        }
    }
    if ("--expect-clean" in args && !report.clean) exitProcess(1)
}