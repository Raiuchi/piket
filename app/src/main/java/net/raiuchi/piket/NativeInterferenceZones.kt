package net.raiuchi.piket

import kotlin.math.floor

/** Pure rules for the quiet, visual-only frequent GPS interference hint. */
object NativeInterferenceZones {
    const val LIVE_INTERFERENCE = "interference-suspected"
    const val LIVE_SPOOFING = "spoofing-suspected"

    fun currentAndAheadBuckets(officialM: Double, directionSign: Int): List<Int> =
        listOf(officialM, officialM + directionSign.coerceIn(-1, 1) * 1_500.0)
            .map { floor(it / 1_000.0).toInt() }
            .distinct()

    fun isFrequent(passes: Int, badPasses: Int): Boolean =
        passes >= 3 && badPasses >= 2 &&
            badPasses.coerceIn(0, passes).toDouble() / passes >= 2.0 / 3.0

    fun shouldShowHint(currentBucketKnown: Boolean, badForMs: Long): Boolean =
        currentBucketKnown && badForMs >= 8_000L

    /**
     * Describes live evidence only. It never changes coordinates or GPS requests.
     * "Interference" deliberately includes shielding/tunnels because Android cannot
     * prove intent from radio symptoms alone. A precise fix far outside the selected
     * railway corridor is a stronger, separate spoofing signal.
     */
    fun liveReason(
        active: Boolean,
        recovering: Boolean,
        directGpsActive: Boolean,
        outageMs: Long,
        satellitesUsed: Int,
        satellitesVisible: Int,
        accuracyM: Float?,
        distanceToRouteM: Double?,
        preciseOffRouteMs: Long
    ): String? {
        if (!active || !recovering) return null
        val accuracy = accuracyM ?: Float.POSITIVE_INFINITY
        if (satellitesUsed >= 4 && accuracy <= 50f &&
            (distanceToRouteM ?: 0.0) > 1_000.0 && preciseOffRouteMs >= 3_000L
        ) return LIVE_SPOOFING
        // A cached/coarse fused coordinate may still advertise a small historical
        // accuracy while the live GNSS receiver sees no satellites at all. The real
        // trip showed this exact state, so zero-satellite recovery is the stronger
        // signal after a sustained outage.
        if (directGpsActive && outageMs >= 15_000L && satellitesVisible == 0)
            return LIVE_INTERFERENCE
        return null
    }
}
