package com.lightmeter.rawmeter

/** The same EV grids used by Settings and Normal, expressed on three vertical wheels. */
internal object AutoMeterControls {
    fun coordinates(state: MeterState, session: AutoMeterSession): List<Double?> {
        val evAtIso = session.reading?.let {
            it.sceneEv100 + (it.evaluative?.exposureReductionEv ?: 0.0) - state.exposureCompEv +
                ExposureMath.log2(state.iso / 100.0)
        }
        val aperture = if(state.exposureLockMode==ExposureLockMode.APERTURE) state.lockedApertureStop
            else evAtIso?.plus(state.lockedShutterLogSeconds)
        val shutter = if(state.exposureLockMode==ExposureLockMode.SHUTTER) state.lockedShutterLogSeconds
            else evAtIso?.let { state.lockedApertureStop-it }
        // Larger shutter-wheel coordinates mean faster speeds, matching an upward swipe.
        return listOf(state.exposureCompEv,aperture,shutter?.unaryMinus())
    }

    fun interval(state: MeterState,column: Int): Double = when(column) {
        0 -> state.exposureCompensationStep.sixthStops / 6.0
        1 -> 1.0/state.apertureStep.denominator
        else -> 1.0/state.shutterStep.denominator
    }

    fun adjust(state: MeterState,session: AutoMeterSession,column: Int,detents: Int) {
        if(session.rawPending || detents==0) return
        if(column==0) {
            state.exposureCompSteps=(state.exposureCompSteps+detents*state.exposureCompensationStep.sixthStops).coerceIn(-30,30)
            return
        }
        val values=coordinates(state,session)
        val aperture=values[1] ?: state.lockedApertureStop
        val shutter=values[2]?.unaryMinus() ?: state.lockedShutterLogSeconds
        state.setExposureLockModeFromCoordinates(if(column==1) ExposureLockMode.APERTURE else ExposureLockMode.SHUTTER,aperture,shutter)
        val base=if(column==1) state.lockedApertureStop else -state.lockedShutterLogSeconds
        val next=base+detents*interval(state,column)
        state.setLockedExposureCoordinate(if(column==1) next else -next)
        state.snapLockedExposure()
    }
}
