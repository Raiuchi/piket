package net.raiuchi.piket

import kotlin.math.floor

/** One formatter for native UI surfaces, including the foreground notification. */
object NativePositionLabel {
    fun kmPk(officialM: Double): String {
        val whole = floor(officialM.coerceAtLeast(0.0)).toInt()
        return "${whole / 1_000} км ${((whole % 1_000) / 100) + 1} пк"
    }
}
