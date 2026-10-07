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
}
