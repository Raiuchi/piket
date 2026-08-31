package net.raiuchi.piket

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Нативный первый слой GPS-движка. Он не знает о WebView и Android Location, поэтому
 * одинаково проверяется обычными unit-тестами и может позже стать основой полного
 * маршрутного движка. Все скорости внутри класса — м/с.
 */
class NativeMotionFilter {
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val elapsedMs: Long,
        val ageMs: Long,
        val accuracyM: Float,
        val speedMps: Float?,
        val speedAccuracyMps: Float?,
        val mock: Boolean,
        val satellitesUsed: Int,
        val averageCn0: Float,
        val hasGnssTelemetry: Boolean
    )

    data class Result(
        val accepted: Boolean,
        val filteredSpeedMps: Float?,
        val stationary: Boolean,
        val quality: String,
        val reason: String
    )

    private var previous: Fix? = null
    private var lastSpeedMps = 0f
    private var stationaryAnchor: Fix? = null
    private var stationarySinceMs = 0L
    private var recovering = false
    private var recoveryCandidate: Float? = null
    private var recoveryCount = 0

    fun reset() {
        previous = null
        lastSpeedMps = 0f
        stationaryAnchor = null
        stationarySinceMs = 0L
        recovering = false
        recoveryCandidate = null
        recoveryCount = 0
    }

    fun markSignalUnavailable() {
        recovering = true
        recoveryCandidate = null
        recoveryCount = 0
    }

    fun process(fix: Fix): Result {
        if (fix.mock) return rejected("mock")
        if (fix.ageMs > 5_000L) return rejected("stale")
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) {
            return rejected("coordinates")
        }

        val prior = previous
        val dt = prior?.let { (fix.elapsedMs - it.elapsedMs).coerceAtLeast(0L) / 1000.0 } ?: 0.0
        val distance = prior?.let { distanceMeters(it.latitude, it.longitude, fix.latitude, fix.longitude) } ?: 0.0
        val weakSatellites = fix.hasGnssTelemetry && (fix.satellitesUsed < 4 || fix.averageCn0 < 12f)
        val poorDoppler = (fix.speedAccuracyMps ?: -1f) > 8f

        var speed = if (!poorDoppler) fix.speedMps?.takeIf { it >= 0f } else null
        if (speed == null && dt >= 0.5 && !weakSatellites) speed = (distance / dt).toFloat()

        updateStationary(fix)
        val stationary = stationaryAnchor != null &&
            fix.elapsedMs - stationarySinceMs >= 10_000L &&
            distanceMeters(stationaryAnchor!!.latitude, stationaryAnchor!!.longitude, fix.latitude, fix.longitude) <= 25.0
        if (stationary) speed = 0f

        if (speed != null) {
            if (speed > 83.34f) speed = null // 300 км/ч — выше рабочего диапазона составов
            if (speed != null && !stationary && prior != null && dt > 0.0) {
                val maxChangeKmh = min(12.0 * maxOf(dt, 0.5) + 5.0, 45.0)
                if (abs(speed * 3.6f - lastSpeedMps * 3.6f) > maxChangeKmh) speed = null
            }
        }

        if (recovering && speed != null) {
            val candidate = recoveryCandidate
            if (candidate != null && abs(candidate - speed) <= 4.2f) recoveryCount++
            else {
                recoveryCandidate = speed
                recoveryCount = 1
            }
            if (recoveryCount < 2) speed = null else recovering = false
        }

        previous = fix
        if (speed != null) lastSpeedMps = speed
        val quality = when {
            stationary -> "stationary"
            weakSatellites -> "weak"
            recovering -> "recovering"
            else -> "good"
        }
        return Result(true, speed, stationary, quality, if (speed == null) "speed-filtered" else "ok")
    }

    private fun updateStationary(fix: Fix) {
        val anchor = stationaryAnchor
        if (anchor == null || distanceMeters(anchor.latitude, anchor.longitude, fix.latitude, fix.longitude) > 25.0) {
            stationaryAnchor = fix
            stationarySinceMs = fix.elapsedMs
        }
    }

    private fun rejected(reason: String) = Result(false, null, false, "rejected", reason)

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
