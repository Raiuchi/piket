package net.raiuchi.piket

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePositionLabelTest {
    @Test fun usesSameOneBasedPicketBoundariesAsTripScreen() {
        assertEquals("257 км 7 пк", NativePositionLabel.kmPk(257_699.99))
        assertEquals("257 км 8 пк", NativePositionLabel.kmPk(257_700.0))
        assertEquals("258 км 1 пк", NativePositionLabel.kmPk(258_000.0))
    }
}
