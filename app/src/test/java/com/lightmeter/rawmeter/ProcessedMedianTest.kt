package com.lightmeter.rawmeter

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class ProcessedMedianTest {
    @Test fun selectionMatchesSortedMedianAcrossOddEvenAndDuplicateSamples() {
        val random = Random(20261014L)
        for (size in (1..130).toList() + listOf(255, 256, 999, 1000, 4096, 65536)) {
            repeat(6) { kind ->
                val values = DoubleArray(size) { index ->
                    when (kind) {
                        0 -> random.nextDouble()
                        1 -> random.nextInt(5).toDouble()
                        2 -> 0.12
                        3 -> index.toDouble()
                        4 -> (size - index).toDouble()
                        else -> minOf(index, size - index).toDouble()
                    }
                }
                assertSameMedian(values)
            }
        }
    }

    @Test fun onlyTheValidPrefixParticipatesAndTheUnusedTailIsUntouched() {
        val random = Random(42)
        val values = DoubleArray(1024) { random.nextDouble() }
        for (size in listOf(1, 2, 24, 25, 127, 128, 511, 512)) {
            val copy = values.copyOf()
            val tail = copy.copyOfRange(size, copy.size)
            val expected = sortedMedian(copy.copyOf(size))
            assertEquals(expected, ProcessedLumaMath.median(copy, size)!!, 0.0)
            assertArrayEquals(tail, copy.copyOfRange(size, copy.size), 0.0)
        }
    }

    @Test fun signedZeroNonFiniteAndSubnormalValuesKeepSortSemantics() {
        val edgeValues = doubleArrayOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, 1.0, -1.0,
        )
        val random = Random(7)
        for (size in 1..300) {
            assertSameMedian(DoubleArray(size) { edgeValues[random.nextInt(edgeValues.size)] })
        }
        assertSameMedian(DoubleArray(100) { -0.0 })
    }

    @Test fun invalidPrefixDoesNotChangeTheInput() {
        val values = doubleArrayOf(3.0, 1.0, 2.0)
        for (size in listOf(-1, 0, 4)) assertNull(ProcessedLumaMath.median(values, size))
        assertArrayEquals(doubleArrayOf(3.0, 1.0, 2.0), values, 0.0)
        assertNull(ProcessedLumaMath.median(doubleArrayOf()))
    }

    private fun assertSameMedian(values: DoubleArray) {
        val expected = sortedMedian(values)
        val actual = ProcessedLumaMath.median(values.copyOf())!!
        assertEquals("median bits for size=${values.size}", expected.toBits(), actual.toBits())
    }

    private fun sortedMedian(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5 else sorted[middle]
    }
}
