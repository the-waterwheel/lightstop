package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class ModeGuidePulseTest {
    @Test fun holdsHalfASecondThenMovesOutAndFadesAway() {
        val pulse=ModeGuidePulse();assertNull(pulse.frame(0));pulse.show(100)
        for(time in listOf(100L,599L,600L)) {
            assertEquals(0f,pulse.frame(time)!!.travel,0f);assertEquals(1f,pulse.frame(time)!!.opacity,0f)
        }
        val moving=pulse.frame(800)!!
        assertTrue(moving.travel>0);assertTrue(moving.travel<1);assertTrue(moving.opacity>0);assertTrue(moving.opacity<1)
        assertNull(pulse.frame(1000));assertNull(pulse.frame(2000))
    }
    @Test fun aNewTapRestartsAndACancelClearsTheHint() {
        val pulse=ModeGuidePulse();pulse.show(0);pulse.show(600)
        assertEquals(1f,pulse.frame(700)!!.opacity,0f)
        pulse.clear();assertNull(pulse.frame(700))
    }
}
