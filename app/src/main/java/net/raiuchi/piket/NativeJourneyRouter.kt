package net.raiuchi.piket

import org.json.JSONObject

/** Безопасно переключает геометрию только в известной последовательности участков. */
class NativeJourneyRouter private constructor(private val chains: List<Chain>) {
    data class Chain(val direction: String, val routes: List<String>)

    private var candidate: String? = null
    private var confirmations = 0

    fun nextRoute(current: String, direction: String): String? {
        val routes = chains.firstOrNull { it.direction == direction && current in it.routes }?.routes ?: return null
        val index = routes.indexOf(current)
        return routes.getOrNull(index + 1)
    }

    fun consider(
        current: String,
        direction: String,
        currentPhysicalM: Double?,
        currentEndM: Double?,
        currentDistanceM: Double?,
        nextDistanceM: Double?
    ): String? {
        val next = nextRoute(current, direction) ?: return reset()
        if (currentPhysicalM == null || currentEndM == null || nextDistanceM == null) return reset()
        val nearBoundary = kotlin.math.abs(currentPhysicalM - currentEndM) <= 800.0
        val neighborReliable = nextDistanceM <= 80.0
        val neighborClearlyBetter = currentDistanceM != null && nextDistanceM + 25.0 < currentDistanceM
        if (!nearBoundary || !neighborReliable || !neighborClearlyBetter) return reset()
        if (candidate == next) confirmations += 1 else { candidate = next; confirmations = 1 }
        if (confirmations < 2) return null
        reset()
        return next
    }

    private fun reset(): String? { candidate = null; confirmations = 0; return null }

    companion object {
        fun fromTimingJson(source: String): NativeJourneyRouter {
            val rows = JSONObject(source).getJSONArray("railChains")
            val chains = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val routes = row.getJSONArray("chain")
                Chain(row.getString("towards"), (0 until routes.length()).map(routes::getString))
            }
            return NativeJourneyRouter(chains)
        }
    }
}
