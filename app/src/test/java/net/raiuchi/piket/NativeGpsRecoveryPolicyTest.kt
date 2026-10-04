package net.raiuchi.piket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGpsRecoveryPolicyTest {
    @Test fun directReserveStartsEarlyOnlyDuringAnActiveTrip() {
        assertFalse(NativeGpsRecoveryPolicy.shouldStartDirect(false, false, 60_000, 60_000, false))
        assertFalse(NativeGpsRecoveryPolicy.shouldStartDirect(true, true, 60_000, 60_000, false))
        assertFalse(NativeGpsRecoveryPolicy.shouldStartDirect(true, false, 4_999, 60_000, false))
        assertTrue(NativeGpsRecoveryPolicy.shouldStartDirect(true, false, 5_000, 60_000, false))
    }

    @Test fun learnedInterferenceCanPrewarmWithoutWaitingForAnOutage() {
        assertTrue(NativeGpsRecoveryPolicy.shouldStartDirect(true, false, 0, 60_000, true))
        assertFalse(NativeGpsRecoveryPolicy.shouldStartDirect(true, false, 0, 10_000, true))
        assertTrue(NativeGpsRecoveryPolicy.INTERFERENCE_PREWARM_MIN_MS >
            NativeGpsRecoveryPolicy.DIRECT_MIN_ACTIVE_MS)
    }

    @Test fun uselessFreshFixesStillStartBackupsAndRestartFused() {
        assertTrue(NativeGpsRecoveryPolicy.shouldStartNetwork(true, 0, 10_000))
        assertTrue(NativeGpsRecoveryPolicy.shouldRestartFused(true, 0, 15_000, 120_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartFused(true, 0, 14_999, 120_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartFused(true, 20_000, 20_000, 60_000))
    }

    @Test fun accurateButOffRouteCoordinatesAreNotTrusted() {
        assertTrue(NativeGpsRecoveryPolicy.isRoutePositionPlausible("Горы - Петрозаводск", 177.3))
        assertTrue(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", 249.9))
        assertFalse(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", 250.1))
        assertFalse(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", null))
        assertTrue(NativeGpsRecoveryPolicy.isRoutePositionPlausible("Все участки", null))
    }

    @Test fun stalledNativeGpsRequestIsActivelyRestarted() {
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(true, 119_999, 120_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(true, 180_000, 119_999))
        assertTrue(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(true, 120_000, 120_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(false, 180_000, 180_000))
    }

    @Test fun currentLocationProbeOnlyRunsDuringARealRecovery() {
        assertTrue(NativeGpsRecoveryPolicy.shouldProbeCurrentLocation(true, false, 12_000, 20_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeCurrentLocation(true, true, 60_000, 60_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeCurrentLocation(true, false, 11_999, 60_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeCurrentLocation(false, false, 60_000, 60_000))
    }

    @Test fun nativeGpsProbeWaitsLongerAndDoesNotOverlap() {
        assertTrue(NativeGpsRecoveryPolicy.shouldProbeNativeLocation(true, false, 30_000, 45_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeNativeLocation(true, true, 60_000, 60_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeNativeLocation(true, false, 29_999, 60_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeNativeLocation(false, false, 60_000, 60_000))
    }

    @Test fun directReserveStopsOnlyAfterSustainedPrimaryRecovery() {
        val now = 200_000L
        assertFalse(NativeGpsRecoveryPolicy.shouldStopDirect(true, now, 100_000L, 0L))
        assertFalse(NativeGpsRecoveryPolicy.shouldStopDirect(true, now, 100_000L, 170_001L))
        assertTrue(NativeGpsRecoveryPolicy.shouldStopDirect(true, now, 100_000L, 170_000L))
        assertFalse(NativeGpsRecoveryPolicy.shouldStopDirect(true, 99_999L, 100_000L, 1L))
    }
}
