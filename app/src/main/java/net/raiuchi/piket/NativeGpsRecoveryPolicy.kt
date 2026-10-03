package net.raiuchi.piket

/** Pure timing rules for the Android GPS recovery reserve. */
object NativeGpsRecoveryPolicy {
    const val DIRECT_START_AFTER_UNUSABLE_MS = 5_000L
    const val NETWORK_START_AFTER_UNUSABLE_MS = 10_000L
    const val FUSED_RESTART_AFTER_SILENCE_MS = 10_000L
    const val FUSED_RESTART_AFTER_UNUSABLE_MS = 15_000L
    const val DIRECT_RESTART_COOLDOWN_MS = 10_000L
    const val FUSED_RESTART_COOLDOWN_MS = 120_000L
    const val DIRECT_MIN_ACTIVE_MS = 90_000L
    const val INTERFERENCE_PREWARM_MIN_MS = 120_000L
    const val PRIMARY_STABLE_BEFORE_DIRECT_STOP_MS = 30_000L
    const val PRIMARY_STABLE_FIX_MAX_GAP_MS = 5_000L
    const val DIRECT_REQUEST_RESTART_AFTER_MS = 25_000L
    const val DIRECT_REQUEST_RESTART_COOLDOWN_MS = 20_000L
    const val CURRENT_LOCATION_PROBE_AFTER_MS = 12_000L
    const val CURRENT_LOCATION_PROBE_COOLDOWN_MS = 20_000L
    const val RECOVERY_WAKE_LOCK_TIMEOUT_MS = 180_000L
    // The hand-traced railway axis can be 128-177 m away from the actual main
    // track on the Gory-Petrozavodsk corridor. Motion/accuracy filtering happens
    // before this check, so 250 m accepts a precise railway fix without accepting
    // the 300-550 m coarse fused fixes seen in real diagnostics.
    const val MAX_TRUSTED_ROUTE_DISTANCE_M = 250.0

    fun isRoutePositionPlausible(routeLabel: String, distanceToRouteM: Double?): Boolean =
        routeLabel == "Все участки" ||
            (distanceToRouteM != null && distanceToRouteM.isFinite() &&
                distanceToRouteM <= MAX_TRUSTED_ROUTE_DISTANCE_M)

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

    fun shouldProbeCurrentLocation(
        directActive: Boolean,
        probeInFlight: Boolean,
        sinceLastUsableFixMs: Long,
        sinceLastProbeMs: Long
    ): Boolean = directActive && !probeInFlight &&
        sinceLastUsableFixMs >= CURRENT_LOCATION_PROBE_AFTER_MS &&
        sinceLastProbeMs >= CURRENT_LOCATION_PROBE_COOLDOWN_MS

    fun shouldStopDirect(
        directActive: Boolean,
        nowMs: Long,
        minimumStopAtMs: Long,
        primaryStableSinceMs: Long
    ): Boolean = directActive && nowMs >= minimumStopAtMs && primaryStableSinceMs > 0L &&
        nowMs - primaryStableSinceMs >= PRIMARY_STABLE_BEFORE_DIRECT_STOP_MS
}
