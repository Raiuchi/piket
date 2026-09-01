package net.raiuchi.piket

import kotlin.math.abs
import kotlin.math.pow

/** Нативное счисление позиции. Не содержит Android API и проверяется unit-тестами. */
class NativeTripEngine(private val routes: NativeRouteEngine) {
    data class Restriction(val id: String, val route: String, val direction: String,
                           val startOfficialM: Double, val endOfficialM: Double, val leadM: Double)
    data class Input(val elapsedMs: Long, val speedMps: Float?, val acceptedFix: Boolean,
                     val snap: NativeRouteEngine.Snap?)
    data class Output(val active: Boolean, val physicalM: Double?, val officialM: Double?,
                      val speedMps: Float, val recovering: Boolean, val source: String,
                      val alertId: String?, val alertDistanceM: Double?, val alertInZone: Boolean)
    data class SavedState(val active: Boolean, val route: String, val direction: String,
                          val manualOfficialM: Double, val physicalM: Double?, val offsetM: Double,
                          val speedMps: Float, val lastElapsedMs: Long)

    private var active = false
    private var route = "Все участки"
    private var direction = "tuda"
    private var manualOfficialM = 0.0
    private var physicalM: Double? = null
    private var officialOffsetM = 0.0
    private var speedMps = 0f
    private var lastElapsedMs = 0L
    private var recovering = false
    private var recoveryCandidateM: Double? = null
    private var recoveryConfirmations = 0
    private var trustedCalibrationFixes = 0
    private var restrictions = emptyList<Restriction>()

    fun configure(route: String, direction: String, manualOfficialM: Double,
                  active: Boolean, restrictions: List<Restriction>) {
        val routeChanged = this.route != route || this.direction != direction
        val calibrationChanged = abs(this.manualOfficialM - manualOfficialM) > 0.5
        this.route = route
        this.direction = direction
        this.manualOfficialM = manualOfficialM
        this.active = active
        this.restrictions = restrictions
        if (routeChanged || calibrationChanged) {
            physicalM = null
            officialOffsetM = 0.0
            recoveryCandidateM = null
            recoveryConfirmations = 0
            trustedCalibrationFixes = 0
        }
    }

    fun markSignalUnavailable() {
        recovering = true
        recoveryCandidateM = null
        recoveryConfirmations = 0
    }

    fun update(input: Input): Output {
        if (!active) return output("inactive")
        val dt = if (lastElapsedMs > 0L) ((input.elapsedMs - lastElapsedMs).coerceIn(0L, 5_000L) / 1000.0) else 0.0
        lastElapsedMs = input.elapsedMs
        input.speedMps?.let { speedMps = it.coerceIn(0f, 83.34f) }
        physicalM?.let { physicalM = it + directionSign() * speedMps * dt }
        if (recovering && input.speedMps == null && dt > 0.0) {
            speedMps = (speedMps * 0.997.pow(dt)).toFloat()
        }

        val snap = input.snap?.takeIf { input.acceptedFix && it.routeLabel == route && it.distanceM <= 120.0 }
        if (physicalM == null && snap != null) {
            physicalM = snap.physicalM
            val base = routes.officialMeters(route, snap.physicalM) ?: manualOfficialM
            officialOffsetM = (manualOfficialM - base).coerceIn(-1_500.0, 1_500.0)
            trustedCalibrationFixes = 0
            recovering = false
            return output("native-gps")
        }
        if (snap != null && physicalM != null) {
            val difference = abs(snap.physicalM - physicalM!!)
            if (difference <= 50.0 && !recovering) {
                physicalM = physicalM!! * 0.35 + snap.physicalM * 0.65
                recoveryCandidateM = null
                recoveryConfirmations = 0
                trustedCalibrationFixes++
                // Ручной столб нужен только для надёжного старта. Раньше эта поправка
                // (часто ровно ±1 км) сохранялась навсегда и переживала автокоррекцию.
                // После трёх согласованных точных GPS-фиксов принимаем маршрутную ось.
                if (trustedCalibrationFixes >= 3) officialOffsetM = 0.0
            } else {
                val candidate = recoveryCandidateM
                if (candidate != null && abs(candidate - snap.physicalM) <= 150.0) recoveryConfirmations++
                else { recoveryCandidateM = snap.physicalM; recoveryConfirmations = 1 }
                if (recoveryConfirmations >= 2) {
                    physicalM = snap.physicalM
                    officialOffsetM = 0.0
                    trustedCalibrationFixes = 3
                    recovering = false
                    recoveryCandidateM = null
                    recoveryConfirmations = 0
                } else recovering = true
            }
        }
        return output(if (snap != null && !recovering) "native-gps" else "native-count")
    }

    fun save(): SavedState = SavedState(active, route, direction, manualOfficialM, physicalM,
        officialOffsetM, speedMps, lastElapsedMs)

    fun restore(saved: SavedState) {
        active = saved.active; route = saved.route; direction = saved.direction
        manualOfficialM = saved.manualOfficialM; physicalM = saved.physicalM
        officialOffsetM = saved.offsetM; speedMps = saved.speedMps; lastElapsedMs = saved.lastElapsedMs
        trustedCalibrationFixes = 0
        recovering = true
    }

    fun stop() { active = false; speedMps = 0f }

    fun switchRoute(nextRoute: String, nextDirection: String, snap: NativeRouteEngine.Snap) {
        route = nextRoute
        direction = nextDirection
        physicalM = snap.physicalM
        officialOffsetM = 0.0
        recoveryCandidateM = null
        recoveryConfirmations = 0
        trustedCalibrationFixes = 3
        recovering = false
    }

    private fun output(source: String): Output {
        val physical = physicalM
        val official = physical?.let { routes.officialMeters(route, it)?.plus(officialOffsetM) }
        val alert = if (physical != null) nextRestriction(physical) else null
        return Output(active, physical, official, speedMps, recovering, source,
            alert?.restriction?.id, alert?.distanceM, alert?.inZone == true)
    }

    private data class AlertCandidate(val restriction: Restriction, val distanceM: Double, val inZone: Boolean)
    private fun nextRestriction(nowPhysicalM: Double): AlertCandidate? {
        return restrictions.asSequence()
            .filter { (it.route == "Все участки" || it.route == route) && (it.direction == "both" || it.direction == direction) }
            .mapNotNull { restriction ->
                val start = routes.physicalMeters(route, restriction.startOfficialM, nowPhysicalM) ?: return@mapNotNull null
                val end = routes.physicalMeters(route, restriction.endOfficialM, start) ?: start
                val low = minOf(start, end) - 5.0
                val high = maxOf(start, end) + 5.0
                val inZone = nowPhysicalM in low..high
                val ahead = directionSign() * (start - nowPhysicalM)
                if (inZone) AlertCandidate(restriction, 0.0, true)
                else if (ahead in 0.0..restriction.leadM) AlertCandidate(restriction, ahead, false) else null
            }.minByOrNull { it.distanceM }
    }

    private fun directionSign() = if (direction == "obratno") -1.0 else 1.0
}
