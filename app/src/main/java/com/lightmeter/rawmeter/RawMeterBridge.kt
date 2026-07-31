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
}
