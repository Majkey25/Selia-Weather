package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonPhaseCanvasTest {
    @Test
    fun terminatorMovesFromNewThroughQuarterToFull() {
        assertEquals(40f, moonTerminatorX(0.0, waxing = true, radius = 40f), 0f)
        assertEquals(0f, moonTerminatorX(0.5, waxing = true, radius = 40f), 0f)
        assertEquals(-40f, moonTerminatorX(1.0, waxing = true, radius = 40f), 0f)
    }

    @Test
    fun waningTerminatorMirrorsWaxingAndClampsFraction() {
        assertEquals(40f, moonTerminatorX(1.0, waxing = false, radius = 40f), 0f)
        assertEquals(0f, moonTerminatorX(0.5, waxing = false, radius = 40f), 0f)
        assertEquals(-40f, moonTerminatorX(-1.0, waxing = false, radius = 40f), 0f)
    }
}
