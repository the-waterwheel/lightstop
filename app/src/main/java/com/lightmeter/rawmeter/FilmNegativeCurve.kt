package com.lightmeter.rawmeter

/** Fixed input anchors and ordered outputs keep manual RGB curves monotone and black fixed. */
data class FilmNegativeCurve(val shadows: Float = .25f, val midtones: Float = .5f, val highlights: Float = .75f) {
    fun points(): FloatArray {
        val a = shadows.takeIf { it.isFinite() }?.coerceIn(.01f, .97f) ?: .25f
        val b = midtones.takeIf { it.isFinite() }?.coerceIn(a + .01f, .98f) ?: maxOf(.5f, a + .01f)
        val c = highlights.takeIf { it.isFinite() }?.coerceIn(b + .01f, .99f) ?: maxOf(.75f, b + .01f)
        return floatArrayOf(0f, a, b, c, 1f)
    }

    fun move(point: Int, output: Float): FilmNegativeCurve {
        require(point in 1..3)
        val p = points()
        p[point] = (output.takeIf { it.isFinite() } ?: p[point]).coerceIn(p[point - 1] + .01f, p[point + 1] - .01f)
        return FilmNegativeCurve(p[1], p[2], p[3])
    }

    fun evaluate(input: Double): Double {
        val x = input.coerceAtLeast(0.0)
        val p = points()
        val segment = (x * 4).toInt().coerceIn(0, 3)
        // Extrapolate the final segment: curve edits retain highlight headroom above white.
        return p[segment] + (p[segment + 1] - p[segment]) * (x * 4 - segment)
    }
}
