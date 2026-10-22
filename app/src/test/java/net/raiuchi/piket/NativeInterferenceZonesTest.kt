package net.raiuchi.piket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInterferenceZonesTest {
    @Test fun needsRepeatedEvidenceBeforeShowingHint() {
        assertFalse(NativeInterferenceZones.isFrequent(1, 1))
        assertFalse(NativeInterferenceZones.isFrequent(2, 2))
        assertTrue(NativeInterferenceZones.isFrequent(3, 2))
        assertFalse(NativeInterferenceZones.isFrequent(3, 1))
        assertFalse(NativeInterferenceZones.isFrequent(10, 3))
    }

    @Test fun visibleHintRequiresCurrentSustainedFailureInsideTheZone() {
        assertFalse(NativeInterferenceZones.shouldShowHint(false, 30_000))
        assertFalse(NativeInterferenceZones.shouldShowHint(true, 7_999))
        assertTrue(NativeInterferenceZones.shouldShowHint(true, 8_000))
    }

    @Test fun checksCurrentAndUpcomingZoneInBothDirections() {
        assertTrue(NativeInterferenceZones.currentAndAheadBuckets(10_200.0, 1).contains(11))
        assertTrue(NativeInterferenceZones.currentAndAheadBuckets(10_200.0, -1).contains(8))
    }

    @Test fun liveInterferenceNeedsReserveAndSustainedZeroSatelliteOutage() {
        assertFalse(NativeInterferenceZones.liveReason(true, true, true, 14_999, 0, 0,
            400f, 80.0, 0) != null)
        assertFalse(NativeInterferenceZones.liveReason(true, true, false, 30_000, 0, 0,
            400f, 80.0, 0) != null)
        assertTrue(NativeInterferenceZones.liveReason(true, true, true, 15_000, 0, 0,
            400f, 80.0, 0) == NativeInterferenceZones.LIVE_INTERFERENCE)
        assertTrue(NativeInterferenceZones.liveReason(true, true, true, 15_000, 0, 0,
            20f, 80.0, 0) == NativeInterferenceZones.LIVE_INTERFERENCE)
        assertFalse(NativeInterferenceZones.liveReason(true, true, true, 30_000, 0, 6,
            400f, 80.0, 0) != null)
    }

    @Test fun preciseRepeatedOffRouteFixIsReportedAsPossibleSpoofing() {
        assertFalse(NativeInterferenceZones.liveReason(true, true, true, 30_000, 18, 18,
            12f, 59_000.0, 2_999) != null)
        assertFalse(NativeInterferenceZones.liveReason(true, true, true, 30_000, 2, 18,
            12f, 59_000.0, 5_000) != null)
        assertTrue(NativeInterferenceZones.liveReason(true, true, true, 30_000, 18, 18,
            12f, 59_000.0, 3_000) == NativeInterferenceZones.LIVE_SPOOFING)
    }
}
