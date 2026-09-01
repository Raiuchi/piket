package net.raiuchi.piket

/** Переводит локальную ось активного участка в общую ось составного рейса. */
object NativeJourneyPosition {
    fun unifiedMeters(selectedRoute: String, actualRoute: String, officialM: Double?, trainNumber: String? = null): Double? {
        val value = officialM ?: return null
        val chudovoJourney = trainNumber in setOf("819", "820") ||
            selectedRoute in setOf("Чудово - Новгород", "Волховстрой - Чудово")
        return when {
            selectedRoute in setOf("СПбФин - Выборг", "Выборг - Каменногорск") &&
                actualRoute == "Выборг - Каменногорск" -> 128_900.0 + value
            // На участке Волховстрой—Чудово локальная ось растёт к Чудово,
            // а сквозная ось 819-го растёт от Чудово к Волховстрою.
            chudovoJourney && actualRoute == "Волховстрой - Чудово" -> 171_000.0 - value
            // Продолжение сквозной оси от Волховстроя до Петрозаводска.
            chudovoJourney && actualRoute == "Горы - Петрозаводск" -> 46_600.0 + value
            else -> value
        }
    }

    fun isCurrentLeg(positionM: Double?, fromM: Double?, toM: Double?): Boolean {
        if (positionM == null || fromM == null || toM == null) return false
        return if (toM >= fromM) positionM >= fromM && positionM < toM
        else positionM <= fromM && positionM > toM
    }
}
