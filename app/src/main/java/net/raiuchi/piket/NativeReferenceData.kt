package net.raiuchi.piket

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TimetableStop(val station: String, val arrival: String?, val departure: String?)
data class TimetableTrain(
    val number: String,
    val title: String,
    val route: String,
    val direction: String,
    val stops: List<TimetableStop>
)
data class TimingStation(val name: String, val meters: Double)
data class SpeedRow(val name: String, val mainSpeed: Int?, val sideSpeed: Int?)
data class SpeedGroup(val title: String, val rows: List<SpeedRow>)
data class SpeedRoute(val id: String, val train: String, val route: String, val note: String, val groups: List<SpeedGroup>)

class NativeReferenceData(context: Context) {
    val trains: List<TimetableTrain>
    val timingStations: Map<String, List<TimingStation>>
    val speedRoutes: List<SpeedRoute>

    init {
        fun asset(name: String) = context.assets.open("data/$name").bufferedReader().use { it.readText() }
        trains = parseTrains(JSONObject(asset("schedules.json")).getJSONArray("trains"))
        timingStations = augmentStations(parseStations(JSONObject(asset("timing.json")).getJSONObject("stations")))
        speedRoutes = parseSpeedRoutes(JSONObject(asset("speed-reference.json")).getJSONArray("routes"))
    }

    private val chudovoLegs = setOf("Чудово - Новгород", "Волховстрой - Чудово", "Горы - Петрозаводск")
    private val dachaLegs = setOf("Д. Долг - Павлово", "Павлово - Горы II путь", "Горы - Павлово I путь", "Горы - Петрозаводск")
    private val vyborgLegs = setOf("СПбФин - Выборг", "Выборг - Каменногорск")

    fun trainsFor(route: String, direction: String): List<TimetableTrain> {
        val sourceRoutes = when {
            route == "Горы - Петрозаводск" -> setOf("Горы - Петрозаводск", "__819_820__")
            route in setOf("Чудово - Новгород", "Волховстрой - Чудово") -> setOf("__819_820__")
            route in dachaLegs -> "Горы - Петрозаводск"
            route in vyborgLegs -> "СПбФин - Выборг"
            else -> route
        }
        return trains.filter { train ->
            train.direction == direction && when (sourceRoutes) {
                is Set<*> -> train.route in sourceRoutes || ("__819_820__" in sourceRoutes && train.number in setOf("819", "820"))
                else -> train.route == sourceRoutes
            }
        }
    }

    fun stations(route: String, trainNumber: String? = null): List<TimingStation> = when {
        trainNumber in setOf("819", "820") -> chudovoThroughStations()
        route in setOf("Чудово - Новгород", "Волховстрой - Чудово") -> chudovoThroughStations()
        route in dachaLegs -> dachaThroughStations()
        route in vyborgLegs -> vyborgThroughStations()
        else -> timingStations[route].orEmpty()
    }

    fun stationMeters(route: String, station: String, trainNumber: String? = null): Double? {
        val raw = normalizeStation(station)
        val wanted = stationAliases[raw] ?: raw
        val candidates = stations(route, trainNumber)
        return candidates.firstOrNull { normalizeStation(it.name) == wanted }?.meters
            ?: candidates.firstOrNull {
                val candidate = normalizeStation(it.name)
                candidate.contains(wanted) || wanted.contains(candidate)
            }?.meters
    }

    companion object {
        private val stationAliases = mapOf(
            "СПЕТЕРБУРГГЛ" to "СПБГЛАВНЫЙ",
            "СПЕТЕРБУРГТМ" to "СПБТОВАРНЫЙМОСКОВСКИЙ",
            "СПСМПОБУХОВО" to "ОБУХОВО",
            "ЧУДОВОМОСК" to "ЧУДОВОМОСК",
            "БОЛОГОЕМОСК" to "БОЛОГОЕМОСК",
            "МУРМАНСКВОРОТА" to "МУРМАНСКИЕВОРОТА",
            "ОЯТЬВОЛХОВСТР" to "ОЯТЬ",
            "БП284КМ" to "ПОСТ284КМ",
            "ВЫБОРГПАСС" to "ВЫБОРГПАСС",
            "СППОЛИСТЬ" to "СПАССКАЯПОЛИСТЬ",
            "ПРЕДУЗПАВЛОВСК" to "ПРЕДУЗЛОВАЯПАВЛОВСКАЯ",
            "НОВГОРОДПОСТ" to "НОВГОРОДТРАНСПОРТНЫЙПОСТ",
            "НОВООКТЯБРЬСКИЙ" to "ВОЛХОВСТРОЙ1",
            "БПОСТ42КМ" to "БЛОКПОСТ42КМ",
            "БПОСТ60КМ" to "БЛОКПОСТ60КМ"
        )

        fun normalizeStation(value: String): String = value
            .uppercase(Locale.ROOT)
            .replace('Ё', 'Е')
            .replace(Regex("[^А-ЯA-Z0-9]"), "")
            .replace("САНКТПЕТЕРБУРГ", "СПБ")
            .replace("МОСКОВСКОЕ", "МОСК")
            .replace("ПАССАЖИРСКИЙ", "ПАСС")
    }

    private fun chudovoThroughStations(): List<TimingStation> {
        val novgorod = timingStations["Чудово - Новгород"].orEmpty().asReversed()
            .map { TimingStation(it.name, 70_000.0 - it.meters) }
        val volkhov = timingStations["Волховстрой - Чудово"].orEmpty().asReversed()
            .map { TimingStation(it.name, 70_000.0 + (101_000.0 - it.meters)) }
        val north = timingStations["Горы - Петрозаводск"].orEmpty()
        val northStart = north.indexOfFirst { normalizeStation(it.name).contains("ВОЛХОВСТРОЙ2") }.coerceAtLeast(0)
        return novgorod + volkhov + north.drop(northStart).map { TimingStation(it.name, 171_000.0 + it.meters - 124_400.0) }
    }

    /** Points that existed as runtime additions in the legacy UI.  They are data,
     * not presentation details, so keep them in the native reference layer. */
    private fun augmentStations(source: Map<String, List<TimingStation>>): Map<String, List<TimingStation>> {
        val additions = mapOf(
            "СпбГл - Москва" to listOf(
                TimingStation("Вагонное депо", 4_300.0),
                TimingStation("Санкт-Петербург-Товарный-Московский", 5_000.0),
                TimingStation("Лихославль, парк Шлюз", 436_600.0)
            ),
            "Горы - Петрозаводск" to listOf(TimingStation("Блок-пост 116 км", 115_700.0)),
            "Волховстрой - Чудово" to listOf(TimingStation("Блок-пост 60 км", 60_000.0)),
            "Чудово - Новгород" to listOf(TimingStation("Великий Новгород", 70_000.0))
        )
        return source.mapValues { (route, rows) ->
            (rows + additions[route].orEmpty())
                .distinctBy { normalizeStation(it.name) }
                .sortedBy { it.meters }
        }
    }

    private fun dachaThroughStations(): List<TimingStation> {
        val dacha = timingStations["Д. Долг - Павлово"].orEmpty()
        val north = timingStations["Горы - Петрозаводск"].orEmpty()
        return dacha + listOf(TimingStation("Горы", 42_000.0)) + north.drop(1)
    }

    private fun vyborgThroughStations(): List<TimingStation> {
        val first = timingStations["СПбФин - Выборг"].orEmpty()
        val second = timingStations["Выборг - Каменногорск"].orEmpty().drop(1)
            .map { TimingStation(it.name, 128_900.0 + it.meters) }
        return first + second
    }

    private fun parseTrains(array: JSONArray) = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        TimetableTrain(
            number = item.getString("number"),
            title = item.optString("title"),
            route = item.optString("route"),
            direction = item.optString("direction"),
            stops = item.getJSONArray("stops").objects().map { stop ->
                TimetableStop(stop.getString("station"), stop.nullableString("arr"), stop.nullableString("dep"))
            }
        )
    }

    private fun parseStations(root: JSONObject): Map<String, List<TimingStation>> = buildMap {
        root.keys().forEach { route ->
            put(route, root.getJSONArray(route).arrays().map { row -> TimingStation(row.getString(0), row.getDouble(1)) })
        }
    }

    private fun parseSpeedRoutes(array: JSONArray) = array.objects().map { item ->
        SpeedRoute(
            item.getString("id"), item.optString("train"), item.optString("route"), item.optString("note"),
            item.getJSONArray("groups").objects().map { group ->
                SpeedGroup(group.optString("title"), group.getJSONArray("rows").objects().map { row ->
                    SpeedRow(row.optString("name"), row.nullableInt("glp"), row.nullableInt("bokp"))
                })
            }
        )
    }
}

private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
private fun JSONArray.arrays() = (0 until length()).map(::getJSONArray)
private fun JSONObject.nullableString(key: String) = if (has(key) && !isNull(key)) getString(key) else null
private fun JSONObject.nullableInt(key: String) = if (has(key) && !isNull(key)) getInt(key) else null
