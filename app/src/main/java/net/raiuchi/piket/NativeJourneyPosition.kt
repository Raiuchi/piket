package net.raiuchi.piket

/** Переводит локальную ось активного участка в общую ось составного рейса. */
object NativeJourneyPosition {
    private val dachaRoutes = setOf(
        "Д. Долг - Павлово", "Павлово - Горы II путь",
        "Горы - Павлово I путь", "Горы - Петрозаводск"
    )

    fun unifiedMeters(
        selectedRoute: String,
        actualRoute: String,
        officialM: Double?,
        trainNumber: String? = null,
        physicalM: Double? = null
    ): Double? {
        val value = officialM ?: return null
        val chudovoJourney = trainNumber in setOf("819", "820") ||
            selectedRoute in setOf("Чудово - Новгород", "Волховстрой - Чудово")
        return when {
            selectedRoute in dachaRoutes && actualRoute == "Д. Долг - Павлово" && physicalM != null ->
                physicalM
            selectedRoute in dachaRoutes && actualRoute == "Павлово - Горы II путь" && physicalM != null ->
                interpolate(physicalM, 21_199.0, 33_500.0, 29_200.0, 42_000.0)
            selectedRoute in dachaRoutes && actualRoute == "Горы - Павлово I путь" && physicalM != null ->
                interpolate(physicalM, 28_200.0, 52_000.0, 29_200.0, 42_000.0)
            selectedRoute in setOf("СПбФин - Выборг", "Выборг - Каменногорск") &&
                actualRoute == "Выборг - Каменногорск" -> 128_900.0 + value
            // Новгородская ветвь общей оси: Чудово = 70 км, Новгород = 2,4 км.
            chudovoJourney && actualRoute == "Чудово - Новгород" -> 70_000.0 - value
            // На участке Волховстрой—Чудово локальная ось растёт к Чудово,
            // а сквозная ось 819-го растёт от Чудово к Волховстрою.
            chudovoJourney && actualRoute == "Волховстрой - Чудово" -> 171_000.0 - value
            // Продолжение сквозной оси от Волховстроя до Петрозаводска.
            chudovoJourney && actualRoute == "Горы - Петрозаводск" -> 46_600.0 + value
            else -> value
        }
    }

    private fun interpolate(value: Double, fromA: Double, fromB: Double, toA: Double, toB: Double): Double {
        val fraction = ((value - fromA) / (fromB - fromA)).coerceIn(0.0, 1.0)
        return toA + fraction * (toB - toA)
    }

    fun isCurrentLeg(positionM: Double?, fromM: Double?, toM: Double?): Boolean {
        if (positionM == null || fromM == null || toM == null) return false
        return if (toM >= fromM) positionM >= fromM && positionM < toM
        else positionM <= fromM && positionM > toM
    }
}
