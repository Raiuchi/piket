package net.raiuchi.piket

import org.json.JSONObject

/** Безопасно переключает геометрию только в известной последовательности участков. */
class NativeJourneyRouter private constructor(
    private val chains: List<Chain>,
    private val journeys: Map<String, List<Leg>>
) {
    data class Chain(val direction: String, val routes: List<String>)
    data class Leg(val route: String, val direction: String)
    data class Transition(val route: String, val direction: String)

    private var candidate: String? = null
    private var confirmations = 0
    private var lastObservedPhysicalM: Double? = null

    fun nextRoute(current: String, direction: String): String? {
        val routes = chains.firstOrNull { it.direction == direction && current in it.routes }?.routes ?: return null
        val index = routes.indexOf(current)
        return routes.getOrNull(index + 1)
    }

    fun nextLeg(journey: String?, current: String, direction: String): Transition? {
        if (journey != null) {
            val legs = journeys[journey].orEmpty()
            val index = legs.indexOfFirst { it.route == current && it.direction == direction }
            return legs.getOrNull(index + 1)?.let { Transition(it.route, it.direction) }
        }
        return nextRoute(current, direction)?.let { Transition(it, direction) }
    }

    fun consider(
        journey: String?,
        current: String,
        direction: String,
        currentPhysicalM: Double?,
        currentEndM: Double?,
        currentDistanceM: Double?,
        nextDistanceM: Double?,
        observedPhysicalM: Double?
    ): Transition? {
        val next = nextLeg(journey, current, direction) ?: return reset()
        if (currentPhysicalM == null || currentEndM == null || nextDistanceM == null) return reset()
        val nearBoundary = kotlin.math.abs(currentPhysicalM - currentEndM) <= 800.0
        val neighborReliable = nextDistanceM <= 80.0
        val sameGeometryTurn = next.route == current && next.direction != direction
        val previousObserved = lastObservedPhysicalM
        lastObservedPhysicalM = observedPhysicalM
        val movedInNextDirection = sameGeometryTurn && observedPhysicalM != null && previousObserved != null &&
            if (next.direction == "tuda") observedPhysicalM > previousObserved + 4.0
            else observedPhysicalM < previousObserved - 4.0
        val neighborClearlyBetter = currentDistanceM != null && nextDistanceM + 25.0 < currentDistanceM
        if (!nearBoundary || !neighborReliable || !(neighborClearlyBetter || movedInNextDirection)) return reset(false)
        val key = "${next.route}|${next.direction}"
        if (candidate == key) confirmations += 1 else { candidate = key; confirmations = 1 }
        if (confirmations < 2) return null
        reset()
        return next
    }

    private fun reset(clearObservation: Boolean = true): Transition? {
        candidate = null; confirmations = 0
        if (clearObservation) lastObservedPhysicalM = null
        return null
    }

    companion object {
        fun fromTimingJson(source: String, journeySource: String? = null): NativeJourneyRouter {
            val root = JSONObject(source)
            val rows = root.getJSONArray("railChains")
            val chains = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val routes = row.getJSONArray("chain")
                Chain(row.getString("towards"), (0 until routes.length()).map(routes::getString))
            }
            val journeyRoot = journeySource?.let(::JSONObject) ?: root
            val journeys = journeyRoot.optJSONObject("journeys")?.let { journeyRows ->
                journeyRows.keys().asSequence().associateWith { id ->
                    val legs = journeyRows.getJSONArray(id)
                    (0 until legs.length()).map { legIndex ->
                        val leg = legs.getJSONObject(legIndex)
                        Leg(leg.getString("route"), leg.getString("direction"))
                    }
                }
            }.orEmpty()
            return NativeJourneyRouter(chains, journeys)
        }
    }
}
