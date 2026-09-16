package net.raiuchi.piket

import org.json.JSONObject

/** Безопасно переключает геометрию только в известной последовательности участков. */
class NativeJourneyRouter private constructor(
    private val chains: List<Chain>,
    private val journeys: Map<String, List<Leg>>
) {
    data class Chain(val direction: String, val routes: List<String>)
    data class Leg(val route: String, val direction: String)
    data class Transition(
        val route: String,
        val direction: String,
        /** Physical metre on the current route where this particular junction lives. */
        val boundaryM: Double? = null,
        /** Some documented electronic-map junctions do not share an identical GPS polyline. */
        val maxNextDistanceM: Double = 80.0,
        /** Cab/train changes must never be applied while the train is moving. */
        val requireStop: Boolean = false
    )

    private var candidate: String? = null
    private var confirmations = 0
    private var lastObservedPhysicalM: Double? = null

    fun nextRoute(current: String, direction: String): String? {
        val routes = chains.firstOrNull { it.direction == direction && current in it.routes }?.routes ?: return null
        val index = routes.indexOf(current)
        return routes.getOrNull(index + 1)
    }

    fun nextLeg(journey: String?, current: String, direction: String): Transition? {
        if (!journey.isNullOrBlank() && journey != "null") {
            val legs = journeys[journey].orEmpty()
            val index = legs.indexOfFirst { it.route == current && it.direction == direction }
            val next = legs.getOrNull(index + 1) ?: return null
            return documentedTransition(journey, current, direction, next)
        }
        val next = nextRoute(current, direction) ?: return null
        return documentedTransition(null, current, direction, Leg(next, direction))
    }

    /**
     * Boundaries come from the operational electronic-map memo, not from the first/last
     * coordinate in a polyline. Several routes intentionally overlap around a station and
     * the endpoint heuristic silently used 130.0 km instead of Vyborg's 128.9 km and
     * Gory's 42 km instead of the internal Volkhov 124.4 km junction.
     */
    private fun documentedTransition(journey: String?, current: String, direction: String, next: Leg): Transition {
        return when {
            current == "СПбФин - Выборг" && direction == "tuda" && next.route == "Выборг - Каменногорск" ->
                Transition(next.route, next.direction, boundaryM = 128_900.0,
                    maxNextDistanceM = 150.0, requireStop = true)
            current == "Выборг - Каменногорск" && direction == "obratno" && next.route == "СПбФин - Выборг" ->
                Transition(next.route, next.direction, boundaryM = 1_000.0,
                    maxNextDistanceM = 150.0, requireStop = true)
            journey == "819" && current == "Волховстрой - Чудово" && direction == "obratno" ->
                Transition(next.route, next.direction, boundaryM = 1_000.0, maxNextDistanceM = 150.0)
            journey == "820" && current == "Горы - Петрозаводск" && direction == "obratno" ->
                Transition(next.route, next.direction, boundaryM = 124_400.0, maxNextDistanceM = 150.0)
            journey == "820" && current == "Волховстрой - Чудово" && direction == "tuda" ->
                Transition(next.route, next.direction, boundaryM = 101_000.0,
                    maxNextDistanceM = 5_000.0, requireStop = true)
            journey == "820" && current == "Чудово - Новгород" && direction == "tuda" ->
                Transition(next.route, next.direction, boundaryM = 75_175.0,
                    maxNextDistanceM = 80.0, requireStop = true)
            else -> Transition(next.route, next.direction)
        }
    }

    fun consider(
        journey: String?,
        current: String,
        direction: String,
        currentPhysicalM: Double?,
        currentEndM: Double?,
        currentDistanceM: Double?,
        nextDistanceM: Double?,
        observedPhysicalM: Double?,
        stopped: Boolean = true
    ): Transition? {
        val next = nextLeg(journey, current, direction) ?: return reset()
        if (currentPhysicalM == null || currentEndM == null || nextDistanceM == null) return reset()
        val nearBoundary = kotlin.math.abs(currentPhysicalM - currentEndM) <= 800.0 ||
            (observedPhysicalM != null && kotlin.math.abs(observedPhysicalM - currentEndM) <= 80.0 &&
                currentDistanceM != null)
        val neighborReliable = nextDistanceM <= next.maxNextDistanceM
        if (next.requireStop && !stopped) return reset(false)
        val sameGeometryTurn = next.route == current && next.direction != direction
        val previousObserved = lastObservedPhysicalM
        lastObservedPhysicalM = observedPhysicalM
        val movedInNextDirection = sameGeometryTurn && observedPhysicalM != null && previousObserved != null &&
            if (next.direction == "tuda") observedPhysicalM > previousObserved + 4.0
            else observedPhysicalM < previousObserved - 4.0
        val neighborClearlyBetter = currentDistanceM != null && nextDistanceM + 25.0 < currentDistanceM
        val documentedStoppedJunction = next.requireStop && stopped && neighborReliable
        if (!nearBoundary || !neighborReliable ||
            !(neighborClearlyBetter || movedInNextDirection || documentedStoppedJunction)) return reset(false)
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
