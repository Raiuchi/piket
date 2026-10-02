package net.raiuchi.piket

/** Pure timing rules for the short-lived Android GPS recovery reserve. */
object NativeGpsRecoveryPolicy {
    const val DIRECT_START_AFTER_UNUSABLE_MS = 5_000L
    const val NETWORK_START_AFTER_UNUSABLE_MS = 10_000L
    const val FUSED_RESTART_AFTER_SILENCE_MS = 10_000L
    const val FUSED_RESTART_AFTER_UNUSABLE_MS = 15_000L
    const val DIRECT_RESTART_COOLDOWN_MS = 30_000L
    const val FUSED_RESTART_COOLDOWN_MS = 120_000L
    const val DIRECT_MIN_ACTIVE_MS = 30_000L
    const val INTERFERENCE_PREWARM_MIN_MS = 45_000L
    const val RECOVERY_WAKE_LOCK_TIMEOUT_MS = 180_000L
    const val MAX_TRUSTED_ROUTE_DISTANCE_M = 120.0

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
        sinceDirectStopMs >= DIRECT_RESTART_COOLDOWN_MS &&
        (unusableForMs >= DIRECT_START_AFTER_UNUSABLE_MS || interferencePrewarmPending)

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
}
