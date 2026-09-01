package net.raiuchi.piket

import kotlin.math.abs

object NativeTimetableCalculator {
    data class Result(
        val distanceM: Double,
        val durationSeconds: Int,
        val averageKmh: Double,
        val plausible: Boolean
    )

    fun calculate(fromM: Double?, toM: Double?, departure: String?, arrival: String?, maxKmh: Double = 250.0): Result? {
        if (fromM == null || toM == null) return null
        val duration = secondsBetween(departure, arrival) ?: return null
        if (duration <= 0) return null
        val distance = abs(toM - fromM)
        val average = distance / duration * 3.6
        return Result(distance, duration, average, average.isFinite() && average <= maxKmh)
    }

    fun secondsBetween(from: String?, to: String?): Int? {
        val start = parseTime(from) ?: return null
        var end = parseTime(to) ?: return null
        if (end < start) end += 24 * 3600
        return end - start
    }

    private fun parseTime(value: String?): Int? {
        if (value == null) return null
        val parts = value.split(':').mapNotNull(String::toIntOrNull)
        if (parts.size !in 2..3 || parts[0] !in 0..23 || parts[1] !in 0..59 || parts.getOrElse(2) { 0 } !in 0..59) return null
        return parts[0] * 3600 + parts[1] * 60 + parts.getOrElse(2) { 0 }
    }
}
