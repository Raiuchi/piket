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

    private data class AxisPoint(val physicalM: Double, val officialM: Double)

    /**
     * Точные смены километровой оси из карты Петрозаводск. Координатная линия остаётся
     * непрерывной, а официальный километр меняется на отмеченной в карте границе.
     * Отдельный профиль нужен для I/II пути у Заневского поста.
     */
    private fun exactProfile(label: String, direction: String?): List<AxisPoint>? {
        if (direction == null) return null
        return when (label) {
        "Д. Долг - Павлово" -> if (direction == "obratno") listOf(
            AxisPoint(5_001.0, 5_000.0), AxisPoint(6_073.0, 6_400.0),
            AxisPoint(6_074.0, 2_300.0), AxisPoint(7_078.0, 3_000.0)
        ) else listOf(
            AxisPoint(5_001.0, 5_000.0), AxisPoint(6_073.0, 7_400.0),
            AxisPoint(6_074.0, 2_300.0),
            AxisPoint(7_078.0, 3_000.0)
        )
        "Павлово - Горы II путь" -> listOf(
            AxisPoint(32_488.0, 32_000.0), AxisPoint(33_499.0, 33_500.0),
            AxisPoint(33_500.0, 42_800.0)
        )
        "Горы - Павлово I путь" -> listOf(
            AxisPoint(32_404.0, 32_000.0), AxisPoint(33_499.0, 33_500.0),
            AxisPoint(33_500.0, 42_800.0), AxisPoint(35_354.0, 43_000.0)
        )
        "Горы - Петрозаводск" -> listOf(
            AxisPoint(42_000.0, 42_800.0), AxisPoint(43_000.0, 43_000.0)
        )
            else -> null
        }
    }

    private fun profileOfficial(points: List<AxisPoint>, physicalM: Double): Double? {
        if (physicalM < points.first().physicalM || physicalM > points.last().physicalM) return null
        for (i in 0 until points.lastIndex) {
            if (points[i].physicalM == points[i + 1].physicalM && physicalM == points[i].physicalM)
                return points[i + 1].officialM
        }
        for (i in 0 until points.lastIndex) {
            val a = points[i]; val b = points[i + 1]
            if (a.physicalM == b.physicalM) {
                if (physicalM == b.physicalM) return b.officialM
                continue
            }
            if (physicalM < b.physicalM || i == points.lastIndex - 1) {
                val fraction = (physicalM - a.physicalM) / (b.physicalM - a.physicalM)
                return a.officialM + fraction * (b.officialM - a.officialM)
            }
        }
        return points.last().officialM
    }

    fun officialMeters(label: String, physicalM: Double, direction: String? = null): Double? {
        val route = route(label) ?: return null
        val points = route.points
        val axis = route.chainageM
        if (points.isEmpty() || points.size != axis.size || !physicalM.isFinite()) return null
        exactProfile(label, direction)?.let { profileOfficial(it, physicalM)?.let { value -> return value } }
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

    fun physicalMeters(label: String, officialM: Double, nearPhysicalM: Double? = null,
                       direction: String? = null): Double? {
        val route = route(label) ?: return null
        if (!officialM.isFinite()) return null
        data class Candidate(val physical: Double, val error: Double)
        val candidates = mutableListOf<Candidate>()
        val profile = exactProfile(label, direction)
        profile?.let { knots ->
            for (i in 0 until knots.lastIndex) {
                val a = knots[i]; val b = knots[i + 1]
                if (a.physicalM == b.physicalM || a.officialM == b.officialM) continue
                if (officialM in minOf(a.officialM, b.officialM)..maxOf(a.officialM, b.officialM)) {
                    candidates += Candidate(a.physicalM +
                        (officialM - a.officialM) / (b.officialM - a.officialM) *
                        (b.physicalM - a.physicalM), 0.0)
                }
            }
        }
        val profileStart = profile?.first()?.physicalM
        val profileEnd = profile?.last()?.physicalM
        val first = route.points.firstOrNull()
        if (first != null && first.physicalM in 0.1..5_000.0 &&
            abs(route.chainageM.first() - first.physicalM) <= 2_500.0) {
            val prefix = first.physicalM + officialM - route.chainageM.first()
            if (prefix in 0.0..first.physicalM) candidates += Candidate(prefix, 0.0)
        }
        route.points.indices.forEach { i ->
            if (profileStart != null && route.points[i].physicalM in profileStart..profileEnd!!) return@forEach
            candidates += Candidate(route.points[i].physicalM, abs(route.chainageM[i] - officialM))
        }
        for (i in 0 until route.points.lastIndex) {
            val a = route.points[i].physicalM
            val b = route.points[i + 1].physicalM
            val physical = b - a
            val oa = route.chainageM[i]
            val ob = route.chainageM[i + 1]
            val official = ob - oa
            if (profileStart != null && maxOf(a, profileStart) < minOf(b, profileEnd!!)) continue
            if (official <= 0.0 || abs(official - physical) > 3_000.0) {
                // Before the reset, officialMeters advances along the old axis.
                if (officialM >= oa && officialM < oa + physical - 1.0) {
                    candidates += Candidate(a + officialM - oa, 0.0)
                }
                continue
            }
            if (officialM in minOf(oa, ob)..maxOf(oa, ob)) {
                candidates += Candidate(a + (officialM - oa) / official * physical, 0.0)
            }
        }
        // Never clamp a remote restriction to the closest endpoint of this route.
        return candidates.filter { it.error <= 0.01 }.minWithOrNull(compareBy<Candidate> { it.error }.thenBy {
            if (nearPhysicalM == null) it.physical else abs(it.physical - nearPhysicalM)
        })?.physical
    }

    fun snap(label: String, latitude: Double, longitude: Double, direction: String? = null): Snap? {
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
            val official = officialMeters(label, physical, direction) ?: continue
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
