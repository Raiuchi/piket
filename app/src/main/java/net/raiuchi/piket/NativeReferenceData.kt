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
        timingStations = parseStations(JSONObject(asset("timing.json")).getJSONObject("stations"))
        speedRoutes = parseSpeedRoutes(JSONObject(asset("speed-reference.json")).getJSONArray("routes"))
    }

    fun stations(route: String): List<TimingStation> = timingStations[route].orEmpty()

    fun stationMeters(route: String, station: String): Double? {
        val wanted = normalizeStation(station)
        val candidates = stations(route)
        return candidates.firstOrNull { normalizeStation(it.name) == wanted }?.meters
            ?: candidates.firstOrNull {
                val candidate = normalizeStation(it.name)
                candidate.contains(wanted) || wanted.contains(candidate)
            }?.meters
    }

    companion object {
        fun normalizeStation(value: String): String = value
            .uppercase(Locale.ROOT)
            .replace('Ё', 'Е')
            .replace(Regex("[^А-ЯA-Z0-9]"), "")
            .replace("САНКТПЕТЕРБУРГ", "СПБ")
            .replace("МОСКОВСКОЕ", "МОСК")
            .replace("ПАССАЖИРСКИЙ", "ПАСС")
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

