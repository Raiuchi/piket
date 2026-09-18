package net.raiuchi.piket

/** Route-specific sanity limits used to reject impossible GPS speed spikes. */
object RouteSpeedCeilings {
    fun isSapsan(trainNumber: String?): Boolean {
        val number = trainNumber?.toIntOrNull() ?: return false
        return number in 751..786
    }

    fun maxKmh(route: String?, trainNumber: String?): Float = when (route) {
        "СпбГл - Москва" -> if (trainNumber.isNullOrBlank() || isSapsan(trainNumber)) 250f else 160f
        "Броневая - Луга" -> 140f
        "СПбФин - Выборг", "Выборг - Каменногорск" -> 160f
        "Горы - Петрозаводск", "Д. Долг - Павлово", "Павлово - Горы II путь",
        "Горы - Павлово I путь", "Чудово - Новгород", "Волховстрой - Чудово" -> 120f
        else -> 160f
    }
    fun trustedKmh(route: String?, trainNumber: String?): Float =
        if (route == "СпбГл - Москва" && trainNumber.isNullOrBlank()) 160f else maxKmh(route, trainNumber)
}
