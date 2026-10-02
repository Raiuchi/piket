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
        assertTrue(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", 119.9))
        assertFalse(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", 120.1))
        assertFalse(NativeGpsRecoveryPolicy.isRoutePositionPlausible("СПбГл - Москва", null))
        assertTrue(NativeGpsRecoveryPolicy.isRoutePositionPlausible("Все участки", null))
    }
}
