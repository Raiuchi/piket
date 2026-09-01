package net.raiuchi.piket

/** Пользовательские направления поверх внутренних GPS-участков. */
object NativeRouteCatalog {
    data class Choice(
        val title: String,
        val tudaStart: String,
        val obratnoStart: String = tudaStart,
        val members: Set<String> = setOf(tudaStart)
    ) {
        fun start(direction: String) = if (direction == "obratno") obratnoStart else tudaStart
    }

    val choices = listOf(
        Choice("Санкт-Петербург-Главный — Москва", "СпбГл - Москва"),
        Choice(
            "Дача Долгорукова — Петрозаводск",
            "Д. Долг - Павлово",
            "Горы - Петрозаводск",
            setOf("Д. Долг - Павлово", "Павлово - Горы II путь", "Горы - Павлово I путь", "Горы - Петрозаводск")
        ),
        Choice("Броневая — Луга", "Броневая - Луга"),
        Choice("Чудово — Новгород", "Чудово - Новгород"),
        Choice("Волховстрой — Чудово", "Волховстрой - Чудово"),
        Choice(
            "Санкт-Петербург-Финляндский — Каменногорск",
            "СПбФин - Выборг",
            "Выборг - Каменногорск",
            setOf("СПбФин - Выборг", "Выборг - Каменногорск")
        )
    )

    fun forInternalRoute(route: String): Choice = choices.firstOrNull { route in it.members }
        ?: Choice(route, route)
}
