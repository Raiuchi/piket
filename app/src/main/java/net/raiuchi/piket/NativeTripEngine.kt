package net.raiuchi.piket

import kotlin.math.abs
import kotlin.math.pow

/** Нативное счисление позиции. Не содержит Android API и проверяется unit-тестами. */
class NativeTripEngine(private val routes: NativeRouteEngine) {
    data class Restriction(val id: String, val route: String, val direction: String,
                           val startOfficialM: Double, val endOfficialM: Double, val leadM: Double,
                           val startTrackHintM: Double? = null, val endTrackHintM: Double? = null)
    data class Input(val elapsedMs: Long, val speedMps: Float?, val acceptedFix: Boolean,
                     val snap: NativeRouteEngine.Snap?, val stationary: Boolean = false,
                     val provisionalCalibrationFix: Boolean = false)
    data class Output(val active: Boolean, val physicalM: Double?, val officialM: Double?,
                      val speedMps: Float, val recovering: Boolean, val source: String,
                      val alertId: String?, val alertDistanceM: Double?, val alertInZone: Boolean)
    data class SavedState(val active: Boolean, val route: String, val direction: String,
                          val manualOfficialM: Double, val physicalM: Double?, val offsetM: Double,
                          val speedMps: Float, val lastElapsedMs: Long,
                          val calibrationWaitStartedElapsedMs: Long = 0L)

    private var active = false
    private var route = "Все участки"
    private var direction = "tuda"
    private var manualOfficialM = 0.0
    private var physicalM: Double? = null
    private var officialOffsetM = 0.0
    private var speedMps = 0f
    private var lastElapsedMs = 0L
    private var calibrationWaitStartedElapsedMs = 0L
    private var recovering = false
    private var recoveryCandidateM: Double? = null
    private var recoveryConfirmations = 0
    private var calibrationHold = false
    private var calibrationAnchorM: Double? = null
    private var calibrationMovementM: Double? = null
    private var calibrationMovementConfirmations = 0
    private var restrictions = emptyList<Restriction>()
    private var previousAlertPhysicalM: Double? = null

    fun configure(route: String, direction: String, manualOfficialM: Double,
                  active: Boolean, restrictions: List<Restriction>, forceCalibration: Boolean = false) {
        val routeChanged = this.route != route || this.direction != direction
        val calibrationChanged = abs(this.manualOfficialM - manualOfficialM) > 0.5
        this.route = route
        this.direction = direction
        this.manualOfficialM = manualOfficialM
        this.active = active
        this.restrictions = restrictions
        if (forceCalibration && !routeChanged && physicalM != null) {
            // Pressing "Set" is an action, even when the entered number equals the
            // previous calibration. Re-anchor the displayed axis at the current place
            // instead of silently ignoring that action.
            val base = routes.officialMeters(route, physicalM!!, direction) ?: manualOfficialM
            officialOffsetM = (manualOfficialM - base).coerceIn(-1_500.0, 1_500.0)
            speedMps = 0f
            lastElapsedMs = 0L
            recovering = false
            recoveryCandidateM = null
            recoveryConfirmations = 0
            calibrationWaitStartedElapsedMs = 0L
            beginCalibrationHold(physicalM)
        } else if (routeChanged || calibrationChanged || forceCalibration) {
            previousAlertPhysicalM = null
            physicalM = null
            officialOffsetM = 0.0
            recoveryCandidateM = null
            recoveryConfirmations = 0
            calibrationWaitStartedElapsedMs = 0L
            if (forceCalibration) beginCalibrationHold(null) else {
                calibrationHold = false
                calibrationAnchorM = null
                calibrationMovementM = null
                calibrationMovementConfirmations = 0
            }
        }
    }

    private fun beginCalibrationHold(anchor: Double?) {
        calibrationHold = true
        calibrationAnchorM = anchor
        calibrationMovementM = null
        calibrationMovementConfirmations = 0
    }

    fun markSignalUnavailable() {
        recovering = true
        recoveryCandidateM = null
        recoveryConfirmations = 0
    }

    fun update(input: Input): Output {
        if (!active) return output("inactive")
        if (physicalM == null && calibrationWaitStartedElapsedMs == 0L)
            calibrationWaitStartedElapsedMs = input.elapsedMs
        val dt = if (lastElapsedMs > 0L) ((input.elapsedMs - lastElapsedMs).coerceIn(0L, 5_000L) / 1000.0) else 0.0
        lastElapsedMs = input.elapsedMs
        input.speedMps?.let { speedMps = it.coerceIn(0f, 83.34f) }
        physicalM?.let { current ->
            val counted = current + directionSign() * speedMps * dt
            val points = routes.route(route)?.points.orEmpty()
            physicalM = if (points.isEmpty()) counted else counted.coerceIn(
                points.minOf { it.physicalM }, points.maxOf { it.physicalM })
        }
        if (recovering && input.speedMps == null && dt > 0.0) {
            speedMps = (speedMps * 0.997.pow(dt)).toFloat()
        }

        val calibrationSnap = input.snap?.takeIf {
            (input.acceptedFix || input.provisionalCalibrationFix) &&
                it.routeLabel == route && it.distanceM <= 120.0
        }
        val snap = calibrationSnap?.takeIf { input.acceptedFix }
        if (physicalM == null && calibrationSnap != null) {
            physicalM = calibrationSnap.physicalM
            val base = routes.officialMeters(route, calibrationSnap.physicalM, direction) ?: manualOfficialM
            // If departure had no usable on-route anchor, a precise fix arriving much
            // later is the train's current place, not the old manually entered place.
            val delayedFirstFix = input.acceptedFix && !input.provisionalCalibrationFix &&
                input.elapsedMs - calibrationWaitStartedElapsedMs >= 30_000L &&
                abs(manualOfficialM - base) > 250.0
            officialOffsetM = if (delayedFirstFix) 0.0
                else (manualOfficialM - base).coerceIn(-1_500.0, 1_500.0)
            calibrationWaitStartedElapsedMs = 0L
            recovering = !input.acceptedFix
            calibrationAnchorM = calibrationSnap.physicalM
            if (calibrationHold) speedMps = 0f
            return output(if (input.acceptedFix) "native-gps" else "native-count")
        }
        if (snap != null && physicalM != null) {
            if (calibrationHold) {
                val anchor = calibrationAnchorM ?: physicalM!!.also { calibrationAnchorM = it }
                val progress = directionSign() * (snap.physicalM - anchor)
                val previous = calibrationMovementM
                val monotonic = previous == null || directionSign() * (snap.physicalM - previous) >= -3.0
                val moving = !input.stationary && (input.speedMps ?: 0f) >= 1.5f && progress >= 25.0
                if (moving && monotonic) calibrationMovementConfirmations++
                else if (progress < 12.0 || input.stationary) calibrationMovementConfirmations = 0
                calibrationMovementM = snap.physicalM
                if (calibrationMovementConfirmations < 2) {
                    physicalM = anchor
                    speedMps = 0f
                    return output("native-gps")
                }
                calibrationHold = false
                physicalM = snap.physicalM
                speedMps = input.speedMps ?: speedMps
            }
            // Do not let harmless GPS drift move the kilometre while the train is
            // stopped. A recovery after a real outage is still confirmed below.
            if (!recovering && (input.stationary || (input.speedMps != null && input.speedMps <= 0.8f))) {
                speedMps = 0f
                return output("native-gps")
            }
            val difference = abs(snap.physicalM - physicalM!!)
            if (difference <= 50.0 && !recovering) {
                physicalM = physicalM!! * 0.35 + snap.physicalM * 0.65
                recoveryCandidateM = null
                recoveryConfirmations = 0
                // GPS уточняет физическое место на пути, но не имеет права стирать
                // поправку, заданную по реальному километровому столбу. Именно её
                // обнуление после хорошего сигнала давало повторный сдвиг примерно на 1 км.
            } else {
                val candidate = recoveryCandidateM
                if (candidate != null && abs(candidate - snap.physicalM) <= 150.0) recoveryConfirmations++
                else { recoveryCandidateM = snap.physicalM; recoveryConfirmations = 1 }
                if (recoveryConfirmations >= 2) {
                    physicalM = snap.physicalM
                    // Сохраняем ручную поправку официальной оси и после РЭБ/потери GPS.
                    recovering = false
                    recoveryCandidateM = null
                    recoveryConfirmations = 0
                } else recovering = true
            }
        }
        return output(if (snap != null && !recovering) "native-gps" else "native-count")
    }

    fun save(): SavedState = SavedState(active, route, direction, manualOfficialM, physicalM,
        officialOffsetM, speedMps, lastElapsedMs, calibrationWaitStartedElapsedMs)

    fun restore(saved: SavedState) {
        active = saved.active; route = saved.route; direction = saved.direction
        manualOfficialM = saved.manualOfficialM; physicalM = saved.physicalM
        officialOffsetM = saved.offsetM; speedMps = saved.speedMps; lastElapsedMs = saved.lastElapsedMs
        calibrationWaitStartedElapsedMs = saved.calibrationWaitStartedElapsedMs
        recovering = true
        calibrationHold = false
        calibrationAnchorM = null
        calibrationMovementM = null
        calibrationMovementConfirmations = 0
        previousAlertPhysicalM = physicalM
    }

    fun stop() { active = false; speedMps = 0f }

    fun switchRoute(nextRoute: String, nextDirection: String, snap: NativeRouteEngine.Snap) {
        route = nextRoute
        direction = nextDirection
        physicalM = snap.physicalM
        officialOffsetM = 0.0
        recoveryCandidateM = null
        recoveryConfirmations = 0
        recovering = false
        calibrationHold = false
        calibrationAnchorM = null
        calibrationMovementM = null
        calibrationMovementConfirmations = 0
        previousAlertPhysicalM = snap.physicalM
    }

    private fun output(source: String): Output {
        val physical = physicalM
        val official = physical?.let { routes.officialMeters(route, it, direction)?.plus(officialOffsetM) }
        val alert = if (active && physical != null) nextRestriction(physical, previousAlertPhysicalM) else null
        previousAlertPhysicalM = physical
        return Output(active, physical, official, speedMps, recovering, source,
            alert?.restriction?.id, alert?.distanceM, alert?.inZone == true)
    }

    private data class AlertCandidate(val restriction: Restriction, val distanceM: Double,
                                      val inZone: Boolean, val priority: Int, val rankM: Double)
    private fun nextRestriction(nowPhysicalM: Double, previousPhysicalM: Double?): AlertCandidate? {
        return restrictions.asSequence()
            .filter { (it.route == "Все участки" || it.route == route) && (it.direction == "both" || it.direction == direction) }
            .mapNotNull { restriction ->
                // A kilometre mark may occur more than once after a chainage reset. The
                // saved physical hint selects the intended occurrence permanently; the
                // official offset still shifts the exact point after manual calibration.
                val start = routes.physicalMeters(route, restriction.startOfficialM - officialOffsetM,
                    restriction.startTrackHintM ?: nowPhysicalM, direction) ?: return@mapNotNull null
                val end = routes.physicalMeters(route, restriction.endOfficialM - officialOffsetM,
                    restriction.endTrackHintM ?: start, direction) ?: start
                val low = minOf(start, end) - 5.0
                val high = maxOf(start, end) + 5.0
                val inZone = nowPhysicalM in low..high
                val entry = if (directionSign() > 0) minOf(start, end) else maxOf(start, end)
                val ahead = directionSign() * (entry - nowPhysicalM)
                val crossed = previousPhysicalM != null &&
                    directionSign() * (entry - previousPhysicalM) > 0.0 &&
                    directionSign() * (entry - nowPhysicalM) <= 0.0
                when {
                    inZone -> AlertCandidate(restriction, 0.0, true, 0, 0.0)
                    // A confirmed GPS reconciliation can jump over a short restriction.
                    // Emit one red entry alert instead of silently losing it; on the next
                    // sample previousPhysicalM catches up and this candidate disappears.
                    crossed -> AlertCandidate(restriction, 0.0, true, 1, abs(nowPhysicalM - entry))
                    ahead in 0.0..restriction.leadM -> AlertCandidate(restriction, ahead, false, 2, ahead)
                    else -> null
                }
            }.minWithOrNull(compareBy<AlertCandidate> { it.priority }.thenBy { it.rankM })
    }

    private fun directionSign() = if (direction == "obratno") -1.0 else 1.0
}
