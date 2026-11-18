package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class ModeGuidePulseTest {
    @Test fun holdsForTwoHundredMillisecondsThenMovesOutAndFadesAway() {
        val pulse=ModeGuidePulse();assertNull(pulse.frame(0));pulse.show(100)
        for(time in listOf(100L,299L,300L)) {
            assertEquals(0f,pulse.frame(time)!!.travel,0f);assertEquals(1f,pulse.frame(time)!!.opacity,0f)
        }
        val moving=pulse.frame(301)!!
        assertTrue(moving.travel>0);assertTrue(moving.travel<1);assertTrue(moving.opacity>0);assertTrue(moving.opacity<1)
        assertNotNull(pulse.frame(699))
        assertNull(pulse.frame(700));assertNull(pulse.frame(2000))
    }
    @Test fun aNewTapRestartsAndACancelClearsTheHint() {
        val pulse=ModeGuidePulse();pulse.show(0);pulse.show(250)
        assertEquals(1f,pulse.frame(350)!!.opacity,0f)
        pulse.clear();assertNull(pulse.frame(350))
    }
}
