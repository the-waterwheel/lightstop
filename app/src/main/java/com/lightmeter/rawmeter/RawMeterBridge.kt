package com.lightmeter.rawmeter

import java.nio.ByteBuffer

object RawMeterBridge {
    init {
        System.loadLibrary("rawmeter")
    }

    /**
     * Returns R, G1, G2, B medians, clipped fraction, and valid sample count.
     * The four black levels are supplied in 2x2 sensor-position order.
     */
    external fun analyzeRaw(
        buffer: ByteBuffer,
        bufferOffset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cfa: Int,
        blackLevels: FloatArray,
        whiteLevel: Int,
        roiLeft: Int,
        roiTop: Int,
        roiWidth: Int,
        roiHeight: Int,
    ): DoubleArray

    /** Clipped/dark area, sample count, then bounded local linear luminances. */
    external fun sampleEvaluativeRaw(
        buffer: ByteBuffer, bufferOffset: Int, bufferLimit: Int,
        width: Int, height: Int, rowStride: Int, pixelStride: Int,
        cfa: Int, blackLevels: FloatArray, whiteLevel: Int,
        left: Int, top: Int, right: Int, bottom: Int,
        lumaCoefficients: DoubleArray,
        shadingColumns: Int, shadingRows: Int, shadingBounds: FloatArray, shadingGains: FloatArray,
    ): DoubleArray
}
