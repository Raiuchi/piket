package net.raiuchi.piket

import kotlin.math.floor

/** Pure rules for the quiet, visual-only frequent GPS interference hint. */
object NativeInterferenceZones {
    fun currentAndAheadBuckets(officialM: Double, directionSign: Int): List<Int> =
        listOf(officialM, officialM + directionSign.coerceIn(-1, 1) * 1_500.0)
            .map { floor(it / 1_000.0).toInt() }
            .distinct()

    fun isFrequent(total: Int, bad: Int): Boolean =
        total >= 3 && bad.coerceIn(0, total).toDouble() / total >= 0.4
}
