package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

class ReciprocityMathTest {
    @Test
    fun `table curve fit is smooth and passes manufacturer nodes`() {
        val method = method(
            type = ReciprocityMethodType.TABLE,
            start = 0.1,
            maximum = 10.0,
            points = listOf(
                ReciprocityPoint(1.0, 1.2599, null),
                ReciprocityPoint(10.0, 15.0, "5M"),
            ),
        )

        val exact = ReciprocityMath.calculate(method, 1.0)
        val middle = ReciprocityMath.calculate(method, 3.0)

        assertEquals(1.2599, exact.correctedSeconds!!, 1e-9)
        assertTrue(middle.correctedSeconds!! > exact.correctedSeconds!!)
        assertTrue(middle.correctedSeconds!! < 15.0)
        assertEquals(ReciprocityStatus.CORRECTED, middle.status)

        val delta = 1e-4
        val center = ReciprocityMath.calculate(method, 1.0).correctedSeconds!!
        val left = ReciprocityMath.calculate(method, exp(-delta)).correctedSeconds!!
        val right = ReciprocityMath.calculate(method, exp(delta)).correctedSeconds!!
        val leftSlope = ln(center / left) / delta
        val rightSlope = ln(right / center) / delta
        assertEquals(leftSlope, rightSlope, 0.01)
    }

    @Test
    fun `table starts continuously at no compensation boundary`() {
        val method = method(
            type = ReciprocityMethodType.TABLE,
            start = 0.1,
            maximum = 1.0,
            points = listOf(ReciprocityPoint(1.0, 2.0, null)),
        )

        assertEquals(0.1, ReciprocityMath.calculate(method, 0.1).correctedSeconds!!, 0.0)
        assertTrue(ReciprocityMath.calculate(method, 0.2).correctedSeconds!! > 0.2)
    }

    @Test
    fun `point curve joins the no compensation range smoothly in stop space`() {
        val method = method(
            type = ReciprocityMethodType.TABLE,
            start = 10.0,
            maximum = 1000.0,
            points = listOf(
                ReciprocityPoint(20.0, 26.985657, null),
                ReciprocityPoint(100.0, 158.489319, null),
                ReciprocityPoint(1000.0, 1995.262315, null),
            ),
        )
        val deltaStops = 1e-4
        val boundary = ReciprocityMath.calculate(method, 10.0).correctedSeconds!!
        val justAfter = ReciprocityMath.calculate(method, 10.0 * 2.0.pow(deltaStops)).correctedSeconds!!
        val rightStopSlope = ln(justAfter / boundary) / ln(2.0) / deltaStops

        assertEquals(1.0, rightStopSlope, 0.001)
        assertEquals(10.0, boundary, 0.0)
    }

    @Test
    fun `point curve compensation never dips between increasing stop nodes`() {
        val method = method(
            type = ReciprocityMethodType.TABLE,
            start = 1.0,
            maximum = 1000.0,
            points = listOf(
                ReciprocityPoint(4.0, 5.656854, null),
                ReciprocityPoint(16.0, 32.0, null),
                ReciprocityPoint(128.0, 362.038672, null),
                ReciprocityPoint(1000.0, 4000.0, null),
            ),
        )

        var previousStops = 0.0
        for (index in 0..400) {
            val metered = 2.0.pow(index * 10.0 / 400.0)
            val corrected = ReciprocityMath.calculate(method, metered).correctedSeconds!!
            val stops = ln(corrected / metered) / ln(2.0)
            assertTrue("compensation dipped at $metered seconds", stops + 1e-9 >= previousStops)
            previousStops = stops
        }
    }

    @Test
    fun `fixed EV and power algorithms follow workbook definitions`() {
        val fixed = method(
            type = ReciprocityMethodType.FIXED_EV,
            parameter = 0.5,
            start = 120.0,
            maximum = 1000.0,
        )
        val power = method(
            type = ReciprocityMethodType.POWER,
            parameter = 1.3,
            start = 1.0,
        )

        assertEquals(200.0 * sqrt(2.0), ReciprocityMath.calculate(fixed, 200.0).correctedSeconds!!, 1e-8)
        assertEquals(Math.pow(10.0, 1.3), ReciprocityMath.calculate(power, 10.0).correctedSeconds!!, 1e-8)
    }

    @Test
    fun `curve methods extrapolate beyond exact range but range-only methods do not`() {
        val range = method(
            type = ReciprocityMethodType.RANGE,
            start = 10.0,
            maximum = 10.0,
        )
        val table = method(
            type = ReciprocityMethodType.TABLE,
            start = 1.0,
            maximum = 10.0,
            points = listOf(
                ReciprocityPoint(2.0, 3.0, null),
                ReciprocityPoint(10.0, 20.0, null),
            ),
        )

        assertEquals(ReciprocityStatus.UNCHANGED, ReciprocityMath.calculate(range, 10.0).status)
        assertEquals(ReciprocityStatus.OUT_OF_RANGE, ReciprocityMath.calculate(range, 11.0).status)
        assertNull(ReciprocityMath.calculate(range, 11.0).correctedSeconds)
        val estimate = ReciprocityMath.calculate(table, 20.0)
        assertEquals(ReciprocityStatus.ESTIMATED, estimate.status)
        assertTrue(estimate.estimated)
        assertTrue(estimate.correctedSeconds!! > 20.0)
    }

    @Test
    fun `bounded unchanged methods stop at the published maximum without extrapolation`() {
        val bounded = method(
            type = ReciprocityMethodType.BOUNDED_UNCHANGED,
            start = 120.0,
            maximum = 120.0,
        )

        val lastPublished = ReciprocityMath.calculate(bounded, 120.0)
        assertEquals(ReciprocityStatus.UNCHANGED, lastPublished.status)
        assertEquals(120.0, lastPublished.correctedSeconds!!, 0.0)
        assertEquals(ReciprocityStatus.OUT_OF_RANGE, ReciprocityMath.calculate(bounded, 120.001).status)
        assertNull(ReciprocityMath.calculate(bounded, 120.001).correctedSeconds)
        assertEquals(120.0, ReciprocityLimitPolicy.maximumInputSeconds(bounded, ExposureStep.FULL), 0.0)
    }

    @Test
    fun `calculation itself can evaluate beyond the ui limit`() {
        val power = method(
            type = ReciprocityMethodType.POWER,
            parameter = 1.3,
            start = 1.0,
        )

        assertEquals(
            ReciprocityStatus.CORRECTED,
            ReciprocityMath.calculate(power, ReciprocityShutterScale.MAX_SECONDS + 1.0).status,
        )
    }

    @Test
    fun `reciprocity annotation is only required for a longer result`() {
        val unchangedPower = method(
            type = ReciprocityMethodType.POWER,
            parameter = 1.0,
            start = 1.0,
        )
        val result = ReciprocityMath.calculate(unchangedPower, 10.0)

        assertFalse(result.needsCorrection)
        assertEquals(ReciprocityStatus.UNCHANGED, result.status)
    }

    @Test
    fun `power correction never shortens the metered exposure`() {
        val method = method(
            type = ReciprocityMethodType.POWER,
            parameter = 1.53,
            start = 0.5,
        )

        listOf(0.5001, 0.594604, 0.707107, 0.840896).forEach { meteredSeconds ->
            val result = ReciprocityMath.calculate(method, meteredSeconds)
            assertEquals(meteredSeconds, result.correctedSeconds!!, 0.0)
            assertEquals(ReciprocityStatus.UNCHANGED, result.status)
        }
    }

    @Test
    fun `filter recommendation follows closest manufacturer node`() {
        val method = method(
            type = ReciprocityMethodType.TABLE,
            start = 1.0,
            maximum = 100.0,
            points = listOf(
                ReciprocityPoint(10.0, 20.0, "5M"),
                ReciprocityPoint(100.0, 300.0, "10M"),
            ),
        )

        assertEquals("5M", ReciprocityMath.calculate(method, 15.0).filter)
        assertEquals("10M", ReciprocityMath.calculate(method, 80.0).filter)
    }

    @Test
    fun `no-filter descriptions are not shown as required filters`() {
        val noFilter = method(
            type = ReciprocityMethodType.TABLE,
            start = 1.0,
            points = listOf(
                ReciprocityPoint(10.0, 20.0, "10s无厂家CC滤镜要求，+1/2EV"),
            ),
        )
        val colorCorrection = method(
            type = ReciprocityMethodType.TABLE,
            start = 1.0,
            points = listOf(
                ReciprocityPoint(10.0, 20.0, "10s：CC05R，+1/3EV"),
            ),
        )

        assertNull(ReciprocityMath.calculate(noFilter, 10.0).filter)
        assertEquals("10s：CC05R，+1/3EV", ReciprocityMath.calculate(colorCorrection, 10.0).filter)
    }

    @Test
    fun `final time readout keeps hours separate`() {
        assertEquals("00:05", ReciprocityTimeFormatter.minutesAndSeconds(5.4))
        assertEquals("02:06", ReciprocityTimeFormatter.minutesAndSeconds(125.6))
        assertEquals("120:00", ReciprocityTimeFormatter.minutesAndSeconds(7200.0))
        assertEquals("02:00:00", ReciprocityTimeFormatter.resultReadout(7200.0))
        assertEquals("1/2", ReciprocityTimeFormatter.resultReadout(0.5))
        assertEquals("1/1.3", ReciprocityTimeFormatter.resultReadout(0.8))
    }

    @Test
    fun `time readout provides aligned unit fields`() {
        assertEquals(listOf("min", "s"), ReciprocityTimeReadout.from(61.0).units)
        assertEquals(listOf("h", "min", "s"), ReciprocityTimeReadout.from(3661.0).units)
        assertEquals("01:01", ReciprocityTimeReadout.from(61.0).text)
        assertEquals("01:01:01", ReciprocityTimeReadout.from(3661.0).text)
    }

    @Test
    fun `limit keeps every displayed corrected result below one day`() {
        val estimated = method(
            type = ReciprocityMethodType.POWER,
            parameter = 1.4,
            start = 1.0,
        )
        assertTrue(
            ReciprocityLimitPolicy.maximumInputSeconds(estimated, ExposureStep.FULL) <
                ReciprocityShutterScale.MAX_SECONDS,
        )

        val exactLongTable = method(
            type = ReciprocityMethodType.TABLE,
            start = 1.0,
            maximum = 100_000.0,
            points = listOf(
                ReciprocityPoint(10.0, 20.0, null),
                ReciprocityPoint(100_000.0, 120_000.0, null),
            ),
        )
        val maximumInput = ReciprocityLimitPolicy.maximumInputSeconds(exactLongTable, ExposureStep.FULL)
        assertTrue(maximumInput < ReciprocityShutterScale.MAX_SECONDS)
        assertTrue(
            ReciprocityMath.calculate(exactLongTable, maximumInput).correctedSeconds!! <
                ReciprocityLimitPolicy.NORMAL_RESULT_LIMIT_SECONDS,
        )
    }

    @Test
    fun `only methods with an evaluable long exposure model are selectable`() {
        assertFalse(method(type = ReciprocityMethodType.NONE, start = 1.0).hasCalculationData)
        assertFalse(method(type = ReciprocityMethodType.RANGE, start = 1.0).hasCalculationData)
        assertTrue(
            method(
                type = ReciprocityMethodType.BOUNDED_UNCHANGED,
                start = 120.0,
                maximum = 120.0,
            ).hasCalculationData,
        )
        assertTrue(
            method(type = ReciprocityMethodType.POWER, parameter = 1.3, start = 1.0).hasCalculationData,
        )
        assertTrue(
            method(
                type = ReciprocityMethodType.TABLE,
                start = 1.0,
                points = listOf(ReciprocityPoint(10.0, 20.0, null)),
            ).hasCalculationData,
        )
    }

    private fun method(
        type: ReciprocityMethodType,
        parameter: Double? = null,
        start: Double,
        maximum: Double? = null,
        points: List<ReciprocityPoint> = emptyList(),
    ) = ReciprocityMethod(
        id = "test",
        type = type,
        parameter = parameter,
        noCompensationSeconds = start,
        officialMaximumSeconds = maximum,
        evidence = "A",
        longExposureFilter = "",
        filterRule = "",
        warning = "",
        sourceUrl = "",
        points = points,
    )
}

class ReciprocityShutterScaleTest {
    @Test
    fun `scale has visible tick values beyond thirty seconds`() {
        val ticks = ReciprocityShutterScale.ticks(ExposureStep.THIRD)

        assertTrue(ticks.any { it.nominalSeconds > 30.0 && it.nominalSeconds < 60.0 })
        assertTrue(ticks.any { it.nominalSeconds >= 60.0 })
        assertEquals(ReciprocityShutterScale.MAX_SECONDS, ticks.last().nominalSeconds, 0.0)
        assertTrue(ticks.all { it.nominalSeconds <= ReciprocityShutterScale.MAX_SECONDS })
    }

    @Test
    fun `scale snapping follows selected shutter step`() {
        val full = ReciprocityShutterScale.ticks(ExposureStep.FULL)
        val thirds = ReciprocityShutterScale.ticks(ExposureStep.THIRD)

        assertTrue(thirds.size > full.size)
        assertEquals(
            1.0 / 3.0,
            thirds.zipWithNext().first { it.first.nominalSeconds > 30.0 }
                .let { it.second.coordinate - it.first.coordinate },
            1e-8,
        )
    }
}
