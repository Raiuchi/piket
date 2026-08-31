package net.raiuchi.piket

data class RestrictionRecord(
    val id: String,
    val route: String,
    val direction: String,
    val km: Int,
    val pk: Int,
    val meter: Int,
    val speed: Int,
    val reason: String,
    val leadM: Int = 2000
)

data class TripSnapshot(
    val active: Boolean = false,
    val route: String = "СпбГл - Москва",
    val direction: String = "tuda",
    val officialM: Double? = null,
    val physicalM: Double? = null,
    val speedKmh: Float = 0f,
    val recovering: Boolean = false,
    val source: String = "inactive",
    val satellites: Int = 0,
    val averageCn0: Float = 0f,
    val accuracyM: Float? = null,
    val alertId: String? = null,
    val alertDistanceM: Double? = null,
    val alertInZone: Boolean = false
)

data class PiketSettings(
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val keepScreenOn: Boolean = true,
    val demoMode: Boolean = false,
    val leadM: Int = 2000
)

