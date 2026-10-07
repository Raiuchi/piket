package net.raiuchi.piket

import kotlin.math.floor

/** Pure rules for the quiet, visual-only frequent GPS interference hint. */
object NativeInterferenceZones {
    fun currentAndAheadBuckets(officialM: Double, directionSign: Int): List<Int> =
        listOf(officialM, officialM + directionSign.coerceIn(-1, 1) * 1_500.0)
            .map { floor(it / 1_000.0).toInt() }
            .distinct()

    fun isFrequent(passes: Int, badPasses: Int): Boolean =
        passes >= 2 && badPasses >= 2 &&
            badPasses.coerceIn(0, passes).toDouble() / passes >= 0.5
}
