package com.lightmeter.rawmeter

/** Product boundary for the calculator: normal results stop at 24 h, exact data may extend it. */
internal object ReciprocityLimitPolicy {
    const val NORMAL_RESULT_LIMIT_SECONDS = 24.0 * 60.0 * 60.0

    fun maximumInputSeconds(method: ReciprocityMethod?, step: ExposureStep): Double {
        if (method == null || method.type in setOf(ReciprocityMethodType.NONE, ReciprocityMethodType.RANGE)) {
            return NORMAL_RESULT_LIMIT_SECONDS
        }
        val exactCeiling = exactInputCeiling(method)
        if (exactCeiling != null && exactResultExceedsNormalLimit(method, exactCeiling)) {
            return exactCeiling
        }
        val ticks = ReciprocityShutterScale.ticks(step, NORMAL_RESULT_LIMIT_SECONDS)
        return ticks.lastOrNull { tick ->
            ReciprocityMath.calculate(method, tick.nominalSeconds).correctedSeconds
                ?.let { it <= NORMAL_RESULT_LIMIT_SECONDS + EPSILON }
                ?: false
        }?.nominalSeconds ?: ticks.first().nominalSeconds
    }

    private fun exactInputCeiling(method: ReciprocityMethod): Double? = when (method.type) {
        ReciprocityMethodType.TABLE -> {
            val lastNode = method.points
                .filter { point ->
                    method.officialMaximumSeconds?.let { point.meteredSeconds <= it + EPSILON } ?: true
                }
                .maxByOrNull(ReciprocityPoint::meteredSeconds)
            lastNode?.meteredSeconds
        }
        ReciprocityMethodType.FIXED_EV,
        ReciprocityMethodType.POWER,
        -> method.officialMaximumSeconds
        ReciprocityMethodType.NONE,
        ReciprocityMethodType.RANGE,
        -> null
    }

    private fun exactResultExceedsNormalLimit(method: ReciprocityMethod, ceiling: Double): Boolean =
        ReciprocityMath.calculate(method, ceiling).correctedSeconds
            ?.let { it > NORMAL_RESULT_LIMIT_SECONDS + EPSILON }
            ?: false

    private const val EPSILON = 1e-9
}
