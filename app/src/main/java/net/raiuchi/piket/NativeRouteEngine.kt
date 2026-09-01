package net.raiuchi.piket

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Нативная геометрия маршрута и официальная километровая ось.
 * Единственный источник расчёта позиции в Android-приложении.
 */
class NativeRouteEngine private constructor(private val routes: List<Route>) {
    data class Point(val latitude: Double, val longitude: Double, val physicalM: Double)
    data class Route(val label: String, val points: List<Point>, val chainageM: List<Double>)
    data class Snap(
        val routeLabel: String,
        val physicalM: Double,
        val officialM: Double,
        val distanceM: Double,
        val segmentIndex: Int
    )

    val routeCount: Int get() = routes.size
    val pointCount: Int get() = routes.sumOf { it.points.size }
    fun labels(): List<String> = routes.map { it.label }
    fun route(label: String): Route? = routes.firstOrNull { it.label == label }

    fun officialMeters(label: String, physicalM: Double): Double? {
        val route = route(label) ?: return null
        val points = route.points
        val axis = route.chainageM
        if (points.isEmpty() || points.size != axis.size || !physicalM.isFinite()) return null
        if (physicalM <= points.first().physicalM) return axis.first() + physicalM - points.first().physicalM
        for (i in 0 until points.lastIndex) {
            val a = points[i].physicalM
            val b = points[i + 1].physicalM
            if (physicalM > b) continue
            val physical = b - a
            val official = axis[i + 1] - axis[i]
            val discontinuity = official <= 0.0 || abs(official - physical) > 3_000.0
            if (discontinuity) {
                return if (physicalM >= b - 1.0) axis[i + 1] else axis[i] + physicalM - a
            }
            val fraction = if (physical > 0.0) (physicalM - a) / physical else 0.0
            return axis[i] + fraction * official
        }
        return axis.last() + physicalM - points.last().physicalM
    }

    fun physicalMeters(label: String, officialM: Double, nearPhysicalM: Double? = null): Double? {
        val route = route(label) ?: return null
        if (!officialM.isFinite()) return null
        data class Candidate(val physical: Double, val error: Double)
        val candidates = mutableListOf<Candidate>()
        route.points.indices.forEach { i ->
            candidates += Candidate(route.points[i].physicalM, abs(route.chainageM[i] - officialM))
        }
        for (i in 0 until route.points.lastIndex) {
            val a = route.points[i].physicalM
            val b = route.points[i + 1].physicalM
            val physical = b - a
            val oa = route.chainageM[i]
            val ob = route.chainageM[i + 1]
            val official = ob - oa
            if (official <= 0.0 || abs(official - physical) > 3_000.0) continue
            if (officialM in minOf(oa, ob)..maxOf(oa, ob)) {
                candidates += Candidate(a + (officialM - oa) / official * physical, 0.0)
            }
        }
        return candidates.minWithOrNull(compareBy<Candidate> { it.error }.thenBy {
            if (nearPhysicalM == null) it.physical else abs(it.physical - nearPhysicalM)
        })?.physical
    }

    fun snap(label: String, latitude: Double, longitude: Double): Snap? {
        val route = route(label) ?: return null
        if (route.points.size < 2 || !latitude.isFinite() || !longitude.isFinite()) return null
        var best: Snap? = null
        for (i in 0 until route.points.lastIndex) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val physicalSpan = b.physicalM - a.physicalM
            // Several source maps begin at the first kilometre although the terminal
            // station lies immediately before that point. Extrapolate only that short
            // missing prefix; never bridge long technical axis gaps this way.
            val canReachOrigin = i == 0 && physicalSpan > 0.0 &&
                a.physicalM in 0.1..5_000.0 &&
                abs(route.chainageM.first() - a.physicalM) <= 2_500.0
            val minFraction = if (canReachOrigin) -a.physicalM / physicalSpan else 0.0
            val projection = project(latitude, longitude, a, b, minFraction, 1.0)
            val physical = a.physicalM + projection.fraction * (b.physicalM - a.physicalM)
            val official = officialMeters(label, physical) ?: continue
            val candidate = Snap(label, physical, official, projection.distanceM, i)
            if (best == null || candidate.distanceM < best.distanceM) best = candidate
        }
        return best
    }

    private data class Projection(val fraction: Double, val distanceM: Double)

    private fun project(
        lat: Double,
        lon: Double,
        a: Point,
        b: Point,
        minFraction: Double,
        maxFraction: Double
    ): Projection {
        val metersPerDegree = 111_320.0
        val lonScale = cos(Math.toRadians((lat + a.latitude + b.latitude) / 3.0)) * metersPerDegree
        val ax = (a.longitude - lon) * lonScale
        val ay = (a.latitude - lat) * metersPerDegree
        val bx = (b.longitude - lon) * lonScale
        val by = (b.latitude - lat) * metersPerDegree
        val dx = bx - ax
        val dy = by - ay
        val denominator = dx * dx + dy * dy
        val fraction = if (denominator > 0.0) {
            (-(ax * dx + ay * dy) / denominator).coerceIn(minFraction, maxFraction)
        } else 0.0
        val px = ax + fraction * dx
        val py = ay + fraction * dy
        return Projection(fraction, sqrt(px * px + py * py))
    }

    companion object {
        fun fromJson(source: String): NativeRouteEngine {
            val root = JSONObject(source)
            return fromArrays(root.getJSONObject("tracks"), root.getJSONArray("chainage"))
        }

        private fun fromArrays(track: JSONObject, chainage: JSONArray): NativeRouteEngine {
            val labels = track.getJSONArray("labels")
            val segments = track.getJSONArray("segs")
            require(labels.length() == segments.length() && labels.length() == chainage.length())
            val routes = (0 until labels.length()).map { routeIndex ->
                val rawPoints = segments.getJSONArray(routeIndex)
                val rawAxis = chainage.getJSONArray(routeIndex)
                require(rawPoints.length() == rawAxis.length())
                Route(
                    labels.getString(routeIndex),
                    (0 until rawPoints.length()).map { pointIndex ->
                        val point = rawPoints.getJSONArray(pointIndex)
                        Point(point.getDouble(0), point.getDouble(1), point.getDouble(2))
                    },
                    (0 until rawAxis.length()).map(rawAxis::getDouble)
                )
            }
            return NativeRouteEngine(routes)
        }
    }
}
