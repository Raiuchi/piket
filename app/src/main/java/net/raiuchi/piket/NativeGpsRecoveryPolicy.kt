package net.raiuchi.piket

/** Pure timing rules for the Android GPS recovery reserve. */
object NativeGpsRecoveryPolicy {
    const val DIRECT_START_AFTER_UNUSABLE_MS = 3_000L
    const val NETWORK_START_AFTER_UNUSABLE_MS = 3_000L
    const val FUSED_RESTART_AFTER_SILENCE_MS = 8_000L
    const val FUSED_RESTART_AFTER_UNUSABLE_MS = 8_000L
    const val DIRECT_RESTART_COOLDOWN_MS = 10_000L
    const val FUSED_RESTART_COOLDOWN_MS = 30_000L
    const val DIRECT_MIN_ACTIVE_MS = 90_000L
    const val INTERFERENCE_PREWARM_MIN_MS = 120_000L
    const val PRIMARY_STABLE_BEFORE_DIRECT_STOP_MS = 30_000L
    const val PRIMARY_STABLE_FIX_MAX_GAP_MS = 5_000L
    // Re-registering GPS every 20 seconds repeatedly reset Samsung's weak-signal
    // acquisition. Leave the continuous request alive long enough for a cold fix.
    // If Android silently stalls GPS_PROVIDER with no satellites in use, repeat
    // the internal navigator kick automatically. A normal cold start is left alone,
    // but a receiver that still cannot use four satellites after 30 seconds is stale.
    const val DIRECT_REQUEST_RESTART_AFTER_MS = 30_000L
    const val DIRECT_REQUEST_RESTART_COOLDOWN_MS = 30_000L
    // Field logs from the Finland corridor showed a Samsung receiver reporting
    // 2-5 visible satellites but using fewer than four of them for more than an hour.
    // Merely seeing satellites is not proof that acquisition is progressing. Refresh the
    // whole recovery stack after 30 seconds without a minimally stable fix; this is the
    // automatic equivalent of the foreground resume that recovered fixes in 5-6 s.
    const val STALLED_ACQUISITION_REFRESH_AFTER_MS = 30_000L
    const val CURRENT_LOCATION_PROBE_AFTER_MS = 6_000L
    const val CURRENT_LOCATION_PROBE_COOLDOWN_MS = 12_000L
    const val NATIVE_LOCATION_PROBE_AFTER_MS = 12_000L
    const val NATIVE_LOCATION_PROBE_COOLDOWN_MS = 20_000L
    const val RECOVERY_WAKE_LOCK_TIMEOUT_MS = 180_000L
    // The hand-traced railway axis can be 128-177 m away from the actual main
    // track on the Gory-Petrozavodsk corridor. Motion/accuracy filtering happens
    // before this check, so 250 m accepts a precise railway fix without accepting
    // the 300-550 m coarse fused fixes seen in real diagnostics.
    const val MAX_TRUSTED_ROUTE_DISTANCE_M = 250.0
    // Speed may remain useful while the position accuracy is temporarily too poor
    // for an exact snap. It must still belong to the selected railway corridor.
    // Real diagnostics contained fresh 130 km/h fixes almost 60 km away from the
    // Finland-station route; feeding that Doppler value into dead reckoning moved a
    // freshly calibrated train while it was actually standing at the platform.
    const val MAX_SPEED_ROUTE_DISTANCE_M = 1_000.0

    fun isRoutePositionPlausible(routeLabel: String, distanceToRouteM: Double?): Boolean =
        routeLabel == "Все участки" ||
            (distanceToRouteM != null && distanceToRouteM.isFinite() &&
                distanceToRouteM <= MAX_TRUSTED_ROUTE_DISTANCE_M)

    fun isRouteSpeedPlausible(routeLabel: String, distanceToRouteM: Double?): Boolean =
        routeLabel == "Все участки" ||
            (distanceToRouteM != null && distanceToRouteM.isFinite() &&
                distanceToRouteM <= MAX_SPEED_ROUTE_DISTANCE_M)

    fun shouldUsePosition(filterAccepted: Boolean, routeLabel: String,
                          distanceToRouteM: Double?): Boolean =
        filterAccepted && isRoutePositionPlausible(routeLabel, distanceToRouteM)

    fun shouldStartDirect(
        tripActive: Boolean,
        directActive: Boolean,
        unusableForMs: Long,
        sinceDirectStopMs: Long,
        interferencePrewarmPending: Boolean
    ): Boolean = tripActive && !directActive &&
        ((unusableForMs >= DIRECT_START_AFTER_UNUSABLE_MS &&
            sinceDirectStopMs >= DIRECT_RESTART_COOLDOWN_MS) ||
            (interferencePrewarmPending &&
                sinceDirectStopMs > DIRECT_RESTART_COOLDOWN_MS))

    fun shouldStartNetwork(tripActive: Boolean, silenceMs: Long, unusableForMs: Long): Boolean =
        tripActive && (silenceMs >= NETWORK_START_AFTER_UNUSABLE_MS ||
            unusableForMs >= NETWORK_START_AFTER_UNUSABLE_MS)

    fun shouldRestartFused(
        tripActive: Boolean,
        silenceMs: Long,
        unusableForMs: Long,
        sinceLastRestartMs: Long
    ): Boolean = tripActive && sinceLastRestartMs >= FUSED_RESTART_COOLDOWN_MS &&
        (silenceMs >= FUSED_RESTART_AFTER_SILENCE_MS ||
            unusableForMs >= FUSED_RESTART_AFTER_UNUSABLE_MS)

    fun shouldRestartDirectRequest(
        directActive: Boolean,
        sinceLastDirectCallbackMs: Long,
        sinceLastDirectRequestMs: Long
    ): Boolean = directActive &&
        sinceLastDirectCallbackMs >= DIRECT_REQUEST_RESTART_AFTER_MS &&
        sinceLastDirectRequestMs >= DIRECT_REQUEST_RESTART_COOLDOWN_MS

    fun shouldRefreshStalledAcquisition(
        directActive: Boolean,
        sinceLastUsableFixMs: Long,
        satellitesUsed: Int,
        sinceLastDirectRequestMs: Long
    ): Boolean = directActive && satellitesUsed < 4 &&
        sinceLastUsableFixMs >= STALLED_ACQUISITION_REFRESH_AFTER_MS &&
        sinceLastDirectRequestMs >= STALLED_ACQUISITION_REFRESH_AFTER_MS

    fun isGnssActivelyAcquiring(
        telemetrySeen: Boolean,
        satellitesVisible: Int,
        sinceLastGnssStatusMs: Long
    ): Boolean = telemetrySeen && satellitesVisible > 0 && sinceLastGnssStatusMs in 0L..15_000L

    fun shouldProbeCurrentLocation(
        directActive: Boolean,
        probeInFlight: Boolean,
        sinceLastUsableFixMs: Long,
        sinceLastProbeMs: Long
    ): Boolean = directActive && !probeInFlight &&
        sinceLastUsableFixMs >= CURRENT_LOCATION_PROBE_AFTER_MS &&
        sinceLastProbeMs >= CURRENT_LOCATION_PROBE_COOLDOWN_MS

    fun shouldProbeNativeLocation(
        directActive: Boolean,
        probeInFlight: Boolean,
        sinceLastUsableFixMs: Long,
        sinceLastProbeMs: Long
    ): Boolean = directActive && !probeInFlight &&
        sinceLastUsableFixMs >= NATIVE_LOCATION_PROBE_AFTER_MS &&
        sinceLastProbeMs >= NATIVE_LOCATION_PROBE_COOLDOWN_MS

    fun shouldStopDirect(
        directActive: Boolean,
        nowMs: Long,
        minimumStopAtMs: Long,
        primaryStableSinceMs: Long
    ): Boolean = directActive && nowMs >= minimumStopAtMs && primaryStableSinceMs > 0L &&
        nowMs - primaryStableSinceMs >= PRIMARY_STABLE_BEFORE_DIRECT_STOP_MS
}
