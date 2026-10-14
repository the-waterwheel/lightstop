package com.lightmeter.rawmeter

import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal data class YuvRegionStat(val luma: Double, val clipped: Double)

/**
 * One synchronous frame analysis owns its readers and scratch buffer. Spot and wide regions reuse
 * them; no Image/plane buffer is retained after analysis and no scratch state is shared across frames
 * or threads. Keep the full ROI, conversion, clipping threshold and exact median unchanged.
 */
internal class YuvRegionAnalyzer(
    private val width: Int,
    private val height: Int,
    private val yPlane: YuvPlaneReader,
    private val uPlane: YuvPlaneReader,
    private val vPlane: YuvPlaneReader,
    private val lumaDecoder: ProcessedLumaDecoder,
    private val encoding: YuvColorEncoding,
    maxRoiFraction: Float,
) {
    private val luminances = DoubleArray(roiWidth(maxRoiFraction) * roiHeight(maxRoiFraction))

    fun analyze(roiFraction: Float): YuvRegionStat? {
        val roiWidth = roiWidth(roiFraction)
        val roiHeight = roiHeight(roiFraction)
        require(roiWidth * roiHeight <= luminances.size)
        val left = (width - roiWidth) / 2
        val top = (height - roiHeight) / 2
        var count = 0
        var clipped = 0
        for (row in top until top + roiHeight) {
            for (column in left until left + roiWidth) {
                val yCode = yPlane.sample(column, row)
                if (yCode < 0) continue
                val uCode = uPlane.sample(column / 2, row / 2)
                if (uCode < 0) continue
                val vCode = vPlane.sample(column / 2, row / 2)
                if (vCode < 0) continue
                ProcessedLumaMath.withEncodedRgb(yCode, uCode, vCode, encoding) { red, green, blue ->
                    luminances[count] = lumaDecoder.linearLuma(red, green, blue)
                    if (red >= 0.98 || green >= 0.98 || blue >= 0.98) clipped++
                }
                count++
            }
        }
        val luma = ProcessedLumaMath.median(luminances, count) ?: return null
        return YuvRegionStat(luma, clipped.toDouble() / count)
    }

    private fun roiWidth(fraction: Float) = (width * fraction).roundToInt().coerceIn(2, width)
    private fun roiHeight(fraction: Float) = (height * fraction).roundToInt().coerceIn(2, height)
}

/** Absolute reads preserve the vendor plane's offset/limit and padding; -1 avoids boxing Int?. */
internal class YuvPlaneReader(buffer: ByteBuffer, private val rowStride: Int, private val pixelStride: Int) {
    private val buffer = buffer.duplicate()
    private val start = this.buffer.position()

    fun sample(column: Int, row: Int): Int {
        if (column < 0 || row < 0 || rowStride <= 0 || pixelStride <= 0) return -1
        val offset = start.toLong() + row.toLong() * rowStride + column.toLong() * pixelStride
        if (offset < start || offset >= buffer.limit()) return -1
        return buffer.get(offset.toInt()).toInt() and 0xff
    }
}
