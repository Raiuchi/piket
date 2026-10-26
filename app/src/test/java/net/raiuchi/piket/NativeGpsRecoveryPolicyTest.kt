package net.raiuchi.piket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGpsRecoveryPolicyTest {
    @Test fun directReserveStartsEarlyOnlyDuringAnActiveTrip() {
        assertFalse(NativeGpsRecoveryPolicy.shouldStartDirect(false, false, 60_000, 60_000, false))
        assertFalse(NativeGpsRecoveryPolicy.shouldStartDirect(true, true, 60_000, 60_000, false))
        assertFalse(NativeGpsRecoveryPolicy.shouldStartDirect(true, false, 2_999, 60_000, false))
        assertTrue(NativeGpsRecoveryPolicy.shouldStartDirect(true, false, 3_000, 60_000, false))
    }

    @Test fun learnedInterferenceCanPrewarmWithoutWaitingForAnOutage() {
        assertTrue(NativeGpsRecoveryPolicy.shouldStartDirect(true, false, 0, 60_000, true))
        assertFalse(NativeGpsRecoveryPolicy.shouldStartDirect(true, false, 0, 10_000, true))
        assertTrue(NativeGpsRecoveryPolicy.INTERFERENCE_PREWARM_MIN_MS >
            NativeGpsRecoveryPolicy.DIRECT_MIN_ACTIVE_MS)
    }

    @Test fun uselessFreshFixesStillStartBackupsAndRestartFused() {
        assertTrue(NativeGpsRecoveryPolicy.shouldStartNetwork(true, 0, 3_000))
        assertTrue(NativeGpsRecoveryPolicy.shouldRestartFused(true, 0, 8_000, 30_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartFused(true, 0, 7_999, 30_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartFused(true, 20_000, 20_000, 29_999))
    }

    @Test fun accurateButOffRouteCoordinatesAreNotTrusted() {
        assertTrue(NativeGpsRecoveryPolicy.isRoutePositionPlausible("Горы - Петрозаводск", 177.3))
        assertTrue(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", 249.9))
        assertFalse(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", 250.1))
        assertFalse(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", null))
        assertTrue(NativeGpsRecoveryPolicy.isRoutePositionPlausible("Все участки", null))
        assertTrue(NativeGpsRecoveryPolicy.isRouteSpeedPlausible("СПбФин - Выборг", 999.9))
        assertFalse(NativeGpsRecoveryPolicy.isRouteSpeedPlausible("СПбФин - Выборг", 1_000.1))
        assertTrue(NativeGpsRecoveryPolicy.isRouteSpeedPlausible("Все участки", null))
    }

    @Test fun acceptedCoordinateIsIndependentFromTheSeparateDopplerDecision() {
        assertTrue(NativeGpsRecoveryPolicy.shouldUsePosition(true, "СПбФин - Выборг", 49.0))
        assertFalse(NativeGpsRecoveryPolicy.shouldUsePosition(false, "СПбФин - Выборг", 49.0))
        assertFalse(NativeGpsRecoveryPolicy.shouldUsePosition(true, "СПбФин - Выборг", 251.0))
    }

    @Test fun stalledNativeGpsRequestIsRetriedUntilCallbacksResume() {
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(true, 29_999, 30_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(true, 180_000, 29_999))
        assertTrue(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(true, 30_000, 30_000))
        assertTrue(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(true, 600_000, 30_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldRestartDirectRequest(false, 180_000, 180_000))
    }

    @Test fun visibleSatellitesProtectAColdAcquisitionFromRestart() {
        assertTrue(NativeGpsRecoveryPolicy.isGnssActivelyAcquiring(true, 1, 15_000))
        assertTrue(NativeGpsRecoveryPolicy.isGnssActivelyAcquiring(true, 8, 0))
        assertFalse(NativeGpsRecoveryPolicy.isGnssActivelyAcquiring(true, 0, 1_000))
        assertFalse(NativeGpsRecoveryPolicy.isGnssActivelyAcquiring(true, 8, 15_001))
        assertFalse(NativeGpsRecoveryPolicy.isGnssActivelyAcquiring(false, 8, 1_000))
    }

    @Test fun visibleButNeverUsedSatellitesEventuallyRefreshTheWholeStack() {
        val threshold = NativeGpsRecoveryPolicy.STALLED_ACQUISITION_REFRESH_AFTER_MS
        assertFalse(NativeGpsRecoveryPolicy.shouldRefreshStalledAcquisition(
            true, threshold - 1, 0, threshold))
        assertTrue(NativeGpsRecoveryPolicy.shouldRefreshStalledAcquisition(
            true, threshold, 3, threshold))
        assertFalse(NativeGpsRecoveryPolicy.shouldRefreshStalledAcquisition(
            true, threshold, 4, threshold))
        assertFalse(NativeGpsRecoveryPolicy.shouldRefreshStalledAcquisition(
            true, threshold, 0, threshold - 1))
        assertTrue(NativeGpsRecoveryPolicy.shouldRefreshStalledAcquisition(
            true, threshold, 0, threshold))
        assertFalse(NativeGpsRecoveryPolicy.shouldRefreshStalledAcquisition(
            false, threshold * 2, 0, threshold * 2))
    }

    @Test fun currentLocationProbeOnlyRunsDuringARealRecovery() {
        assertTrue(NativeGpsRecoveryPolicy.shouldProbeCurrentLocation(true, false, 6_000, 12_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeCurrentLocation(true, true, 60_000, 60_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeCurrentLocation(true, false, 5_999, 60_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeCurrentLocation(false, false, 60_000, 60_000))
    }

    @Test fun nativeGpsProbeWaitsLongerAndDoesNotOverlap() {
        assertTrue(NativeGpsRecoveryPolicy.shouldProbeNativeLocation(true, false, 12_000, 20_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeNativeLocation(true, true, 60_000, 60_000))
        assertFalse(NativeGpsRecoveryPolicy.shouldProbeNativeLocation(true, false, 11_999, 60_000))
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
