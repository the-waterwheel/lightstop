package com.lightmeter.rawmeter

import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class YuvRegionAnalyzerTest {
    @Test fun fullResolutionRegionsMatchThePreviousConversionAndSortBitForBit() {
        val curve = ProcessedToneCurve(doubleArrayOf(0.0, 0.0, 0.25, 0.5, 1.0, 1.0))
        val transfers = listOf(ProcessedTransfer.LINEAR, ProcessedTransfer.SRGB,
            ProcessedTransfer.REC709, ProcessedTransfer.GAMMA(2.2),
            ProcessedTransfer.CURVES(curve, curve, curve))
        for (range in YuvCodeRange.entries) {
            for ((kr, kb) in listOf(0.299 to 0.114, 0.2126 to 0.0722, 0.2627 to 0.0593)) {
                for (transfer in transfers) {
                    val f = Fixture(61, 39, YuvColorEncoding(range, kr, kb), transfer)
                    val analyzer = f.analyzer()
                    for (fraction in listOf(0.01f, 0.12f, 0.65f, 1f, 0.12f)) {
                        assertSameStat(f.legacy(fraction), analyzer.analyze(fraction))
                    }
                }
            }
        }
    }

    @Test fun interleavedChromaOffsetsAndReadOnlyDirectBuffersKeepTheirPositions() {
        val width = 20
        val height = 14
        val rowStride = width + 4
        val storage = ByteBuffer.allocateDirect(7 + rowStride * (height / 2))
        // Expose overlapping U/V views with different starts, as in semiplanar camera buffers.
        val bytes = ByteArray(storage.capacity()).also { Random(11).nextBytes(it) }
        storage.put(bytes).clear()
        val f = Fixture(width, height)
        f.u = Plane(storage.asReadOnlyBuffer().apply { position(7) }, rowStride, 2)
        f.v = Plane(storage.asReadOnlyBuffer().apply { position(8) }, rowStride, 2)
        val positions = listOf(f.y, f.u, f.v).map { it.buffer.position() to it.buffer.limit() }
        assertSameStat(f.legacy(0.65f), f.analyzer().analyze(0.65f))
        assertEquals(positions, listOf(f.y, f.u, f.v).map { it.buffer.position() to it.buffer.limit() })
    }

    @Test fun truncatedVendorPlanesSkipInvalidPixelsExactlyAsBefore() {
        val f = Fixture(37, 25)
        f.y.buffer.limit(f.y.buffer.limit() - 29)
        f.u.buffer.limit(f.u.buffer.limit() / 2)
        f.v.buffer.limit(f.v.buffer.limit() - 9)
        val analyzer = f.analyzer()
        for (fraction in listOf(0.12f, 0.65f, 1f)) {
            assertSameStat(f.legacy(fraction), analyzer.analyze(fraction))
        }
    }

    @Test fun emptyOrInvalidPlanesReturnNoReading() {
        val f = Fixture(8, 8)
        f.u.buffer.limit(f.u.buffer.position())
        assertNull(f.analyzer().analyze(1f))
        f.u = plane(4, 4, 2)
        f.v = f.v.copy(pixelStride = 0)
        assertNull(f.analyzer().analyze(1f))
    }

    @Test fun scratchReuseDoesNotMixSpotAndWideSamplesOrChangeTheFrameBytes() {
        val f = Fixture(63, 41)
        val input = listOf(f.y, f.u, f.v).map { snapshot(it.buffer) }
        val analyzer = f.analyzer()
        for (fraction in listOf(0.12f, 0.65f, 0.12f, 1f, 0.01f)) {
            assertSameStat(f.legacy(fraction), analyzer.analyze(fraction))
        }
        listOf(f.y, f.u, f.v).forEachIndexed { index, plane ->
            assertArrayEquals(input[index], snapshot(plane.buffer))
        }
    }

    @Test fun clippingStillUsesReconstructedRgbInsteadOfOnlyTheYChannel() {
        val f = Fixture(16, 16)
        f.y = constantPlane(16, 16, 76)
        f.u = constantPlane(8, 8, 85)
        f.v = constantPlane(8, 8, 255)
        val stat = f.analyzer().analyze(1f)!!
        assertEquals(1.0, stat.clipped, 0.0)
        assertSameStat(f.legacy(1f), stat)
    }

    @Test fun invalidColorMatrixKeepsThePreviousGrayFallback() {
        val f = Fixture(14, 18, YuvColorEncoding(redLumaCoefficient = 0.9, blueLumaCoefficient = 0.8))
        assertSameStat(f.legacy(1f), f.analyzer().analyze(1f))
    }

    @Test fun planeOffsetsUseWideArithmeticAndRespectPositionLimitAndStride() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 10, 20, 88, 30, 40, 77)).apply {
            position(1)
            limit(6)
        }
        val reader = YuvPlaneReader(buffer, 3, 1)
        assertEquals(10, reader.sample(0, 0))
        assertEquals(40, reader.sample(1, 1))
        assertEquals(-1, reader.sample(2, 1))
        assertEquals(-1, reader.sample(-1, 0))
        assertEquals(-1, reader.sample(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(-1, YuvPlaneReader(buffer, -1, 1).sample(0, 0))
        assertEquals(1, buffer.position())
        assertEquals(6, buffer.limit())
    }

    private class Fixture(
        val width: Int,
        val height: Int,
        val encoding: YuvColorEncoding = YuvColorEncoding(),
        transfer: ProcessedTransfer = ProcessedTransfer.SRGB,
    ) {
        var y = plane(width, height, 1)
        var u = plane((width + 1) / 2, (height + 1) / 2, 2)
        var v = plane((width + 1) / 2, (height + 1) / 2, 3)
        val decoder = ProcessedLumaDecoder(transfer)
        fun analyzer() = YuvRegionAnalyzer(width, height, y.reader(), u.reader(), v.reader(), decoder, encoding, 1f)

        /** Frozen pre-optimization region/conversion implementation, including full sorting. */
        fun legacy(fraction: Float): YuvRegionStat? {
            val roiWidth = (width * fraction).roundToInt().coerceIn(2, width)
            val roiHeight = (height * fraction).roundToInt().coerceIn(2, height)
            val left = (width - roiWidth) / 2
            val top = (height - roiHeight) / 2
            val samples = DoubleArray(roiWidth * roiHeight)
            var count = 0
            var clipped = 0
            for (row in top until top + roiHeight) for (column in left until left + roiWidth) {
                val yc = y.sample(column, row) ?: continue
                val uc = u.sample(column / 2, row / 2) ?: continue
                val vc = v.sample(column / 2, row / 2) ?: continue
                val rgb = legacyRgb(yc, uc, vc, encoding)
                samples[count++] = decoder.linearLuma(rgb.red, rgb.green, rgb.blue)
                if (rgb.clipped) clipped++
            }
            if (count == 0) return null
            samples.sort(0, count)
            val luma = if (count % 2 == 0) (samples[count / 2 - 1] + samples[count / 2]) * 0.5 else samples[count / 2]
            return YuvRegionStat(luma, clipped.toDouble() / count)
        }
    }

    private data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int) {
        fun reader() = YuvPlaneReader(buffer, rowStride, pixelStride)
        fun sample(column: Int, row: Int): Int? {
            if (column < 0 || row < 0 || rowStride <= 0 || pixelStride <= 0) return null
            val offset = buffer.position().toLong() + row.toLong() * rowStride + column.toLong() * pixelStride
            if (offset < buffer.position() || offset >= buffer.limit()) return null
            return buffer.get(offset.toInt()).toInt() and 255
        }
    }

    private companion object {
        fun assertSameStat(expected: YuvRegionStat?, actual: YuvRegionStat?) {
            if (expected == null) { assertNull(actual); return }
            assertNotNull(actual)
            assertEquals(expected.luma.toBits(), actual!!.luma.toBits())
            assertEquals(expected.clipped.toBits(), actual.clipped.toBits())
        }
        fun snapshot(buffer: ByteBuffer) = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        fun plane(width: Int, height: Int, seed: Int): Plane {
            val stride = if (seed == 1) 1 else 2
            val row = width * stride + 5
            val bytes = ByteArray(7 + row * height).also { Random(seed.toLong()).nextBytes(it) }
            return Plane(ByteBuffer.wrap(bytes).apply { position(7) }, row, stride)
        }
        fun constantPlane(width: Int, height: Int, value: Int) =
            Plane(ByteBuffer.wrap(ByteArray(width * height) { value.toByte() }), width, 1)
        fun legacyRgb(yCode: Int, uCode: Int, vCode: Int, encoding: YuvColorEncoding): EncodedRgb {
            val y = if (encoding.range == YuvCodeRange.FULL) yCode.coerceIn(0, 255) / 255.0
                else ((yCode.coerceIn(0, 255) - 16.0) / 219.0).coerceIn(0.0, 1.0)
            val scale = if (encoding.range == YuvCodeRange.LIMITED) 224.0 else 255.0
            val cb = (uCode.coerceIn(0, 255) - 128.0) / scale
            val cr = (vCode.coerceIn(0, 255) - 128.0) / scale
            val kr = encoding.redLumaCoefficient
            val kb = encoding.blueLumaCoefficient
            val kg = 1.0 - kr - kb
            if (kr <= 0.0 || kb <= 0.0 || kg <= 0.0) return EncodedRgb(y, y, y)
            val red = y + 2.0 * (1.0 - kr) * cr
            val blue = y + 2.0 * (1.0 - kb) * cb
            val green = (y - kr * red - kb * blue) / kg
            return EncodedRgb(red.coerceIn(0.0, 1.0), green.coerceIn(0.0, 1.0), blue.coerceIn(0.0, 1.0))
        }
    }
}
