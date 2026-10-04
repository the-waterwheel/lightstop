package com.lightmeter.rawmeter

/** Product boundary for the calculator: every displayed corrected result stays below 24 h. */
internal object ReciprocityLimitPolicy {
    const val NORMAL_RESULT_LIMIT_SECONDS = 24.0 * 60.0 * 60.0

    fun maximumInputSeconds(method: ReciprocityMethod?, step: ExposureStep): Double {
        if (method == null || method.type in setOf(ReciprocityMethodType.NONE, ReciprocityMethodType.RANGE)) {
            return NORMAL_RESULT_LIMIT_SECONDS
        }
        if (method.type == ReciprocityMethodType.BOUNDED_UNCHANGED) {
            return method.officialMaximumSeconds
                ?.coerceIn(MIN_INPUT_SECONDS, NORMAL_RESULT_LIMIT_SECONDS - EPSILON)
                ?: NORMAL_RESULT_LIMIT_SECONDS
        }
        val ticks = ReciprocityShutterScale.ticks(step, NORMAL_RESULT_LIMIT_SECONDS)
        return ticks.lastOrNull { tick ->
            ReciprocityMath.calculate(method, tick.nominalSeconds).correctedSeconds
                ?.let { it < NORMAL_RESULT_LIMIT_SECONDS - EPSILON }
                ?: false
        }?.nominalSeconds ?: ticks.first().nominalSeconds
    }

    private const val EPSILON = 1e-9
    private const val MIN_INPUT_SECONDS = 1.0 / 8000.0
}
