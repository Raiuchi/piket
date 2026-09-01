package net.raiuchi.piket

import org.json.JSONArray
import org.json.JSONObject

/**
 * Compatibility model kept for the dormant Compose screens.
 * The production activity uses the premium HTML view and sends the same JSON
 * contract to the native tracking service.
 */
data class NativeUiConfig(
    val route: String,
    val direction: String,
    val manualOfficialM: Double,
    val restrictions: List<RestrictionRecord>,
    val leadM: Int,
    val sound: Boolean,
    val vibration: Boolean,
    val active: Boolean = true,
    val journey: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("route", route)
        put("direction", direction)
        put("manualOfficialM", manualOfficialM)
        put("lead", leadM)
        put("active", active)
        put("sound", sound)
        put("vibration", vibration)
        journey?.let { put("journey", it) }
        put("restrictions", JSONArray().apply {
            restrictions.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("peregon", item.route)
                    put("dir", item.direction)
                    put("km", item.km)
                    put("pk", item.pk)
                    put("m", item.meter)
                    put("lead", item.leadM)
                    put("speed", item.speed)
                    put("reason", item.reason)
                })
            }
        })
    }
}
