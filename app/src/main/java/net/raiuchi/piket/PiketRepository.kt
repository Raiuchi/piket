package net.raiuchi.piket

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PiketRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("piket_compose", Context.MODE_PRIVATE)

    fun loadRestrictions(): List<RestrictionRecord> = runCatching {
        val array = JSONArray(prefs.getString("restrictions", "[]"))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            RestrictionRecord(
                item.optString("id", UUID.randomUUID().toString()),
                item.optString("route", "Все участки"),
                item.optString("direction", "both"),
                item.optInt("km"), item.optInt("pk"), item.optInt("meter"),
                item.optInt("speed", 60), item.optString("reason", "Ограничение скорости"),
                item.optInt("leadM", 2000)
            )
        }
    }.getOrDefault(emptyList())

    fun saveRestrictions(items: List<RestrictionRecord>): Boolean = runCatching {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("route", item.route); put("direction", item.direction)
                put("km", item.km); put("pk", item.pk); put("meter", item.meter)
                put("speed", item.speed); put("reason", item.reason); put("leadM", item.leadM)
            })
        }
        prefs.edit().putString("restrictions", array.toString()).commit()
    }.getOrDefault(false)

    fun loadSettings() = PiketSettings(
        sound = prefs.getBoolean("sound", true),
        vibration = prefs.getBoolean("vibration", true),
        keepScreenOn = prefs.getBoolean("keepScreen", true),
        demoMode = prefs.getBoolean("demo", false),
        leadM = prefs.getInt("leadM", 2000)
    )

    fun saveSettings(value: PiketSettings) = prefs.edit()
        .putBoolean("sound", value.sound).putBoolean("vibration", value.vibration)
        .putBoolean("keepScreen", value.keepScreenOn).putBoolean("demo", value.demoMode)
        .putInt("leadM", value.leadM).commit()

    fun loadSnapshot(): TripSnapshot = runCatching {
        val raw = context.getSharedPreferences("piket_native", Context.MODE_PRIVATE)
            .getString("snapshot", null) ?: return TripSnapshot()
        val json = JSONObject(raw)
        TripSnapshot(
            active = json.optBoolean("active"), route = json.optString("route", "СпбГл - Москва"),
            direction = json.optString("direction", "tuda"),
            officialM = json.optNullableDouble("officialM"), physicalM = json.optNullableDouble("physicalM"),
            speedKmh = json.optDouble("speedKmh", 0.0).toFloat(), recovering = json.optBoolean("recovering"),
            source = json.optString("source", "inactive"), satellites = json.optInt("satellites"),
            averageCn0 = json.optDouble("averageCn0", 0.0).toFloat(),
            accuracyM = json.optNullableDouble("accuracyM")?.toFloat(),
            alertId = json.optString("alertId").takeIf { it.isNotBlank() },
            alertDistanceM = json.optNullableDouble("alertDistanceM"),
            alertInZone = json.optBoolean("alertInZone")
        )
    }.getOrDefault(TripSnapshot())

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null
}
