package net.raiuchi.piket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSpeedCeilingsTest {
    @Test fun regionalRoutesUseTheirPhysicalMaximums() {
        assertEquals(120f, RouteSpeedCeilings.maxKmh("Д. Долг - Павлово", null))
        assertEquals(120f, RouteSpeedCeilings.maxKmh("Горы - Петрозаводск", null))
        assertEquals(120f, RouteSpeedCeilings.maxKmh("Чудово - Новгород", null))
        assertEquals(120f, RouteSpeedCeilings.maxKmh("Волховстрой - Чудово", null))
        assertEquals(140f, RouteSpeedCeilings.maxKmh("Броневая - Луга", null))
        assertEquals(160f, RouteSpeedCeilings.maxKmh("СПбФин - Выборг", null))
        assertEquals(160f, RouteSpeedCeilings.maxKmh("Выборг - Каменногорск", null))
    }

    @Test fun twoFiftyIsEnabledOnlyForSapsanNumbers() {
        assertTrue(RouteSpeedCeilings.isSapsan("751"))
        assertTrue(RouteSpeedCeilings.isSapsan("786"))
        assertFalse(RouteSpeedCeilings.isSapsan("723"))
        assertFalse(RouteSpeedCeilings.isSapsan(null))
        assertEquals(250f, RouteSpeedCeilings.maxKmh("СпбГл - Москва", "751"))
        assertEquals(160f, RouteSpeedCeilings.maxKmh("СпбГл - Москва", "723"))
        assertEquals(250f, RouteSpeedCeilings.maxKmh("СпбГл - Москва", null))
        assertEquals(160f, RouteSpeedCeilings.trustedKmh("СпбГл - Москва", null))
        assertEquals(160f, RouteSpeedCeilings.maxKmh("СпбГл - Москва", "801"))
        assertEquals(160f, RouteSpeedCeilings.maxKmh("СпбГл - Москва", "802"))
        assertEquals(160f, RouteSpeedCeilings.maxKmh("СпбГл - Москва", "841"))
        assertEquals(160f, RouteSpeedCeilings.maxKmh("СпбГл - Москва", "842"))
    }
}
