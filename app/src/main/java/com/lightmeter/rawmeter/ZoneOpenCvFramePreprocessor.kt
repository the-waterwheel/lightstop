package com.lightmeter.rawmeter

import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Converts pooled camera Y bytes into the orientation and working resolution used by tracking.
 * Keeping this boundary separate prevents buffer ownership and camera stride concerns from leaking
 * into the marker-motion algorithm.
 */
internal class ZoneOpenCvFramePreprocessor(
    private val trackingLongEdge: Int,
) {
    fun targetSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
        if (sourceWidth >= sourceHeight) {
            trackingLongEdge to
                (trackingLongEdge * sourceHeight.toFloat() / sourceWidth)
                    .roundToInt()
                    .coerceAtLeast(1)
        } else {
            (trackingLongEdge * sourceWidth.toFloat() / sourceHeight)
                .roundToInt()
                .coerceAtLeast(1) to trackingLongEdge
        }

    fun copyToGray(frame: ZoneTrackingFrame, destination: Mat) {
        val source = Mat(frame.height, frame.width, CvType.CV_8UC1)
        val oriented = Mat()
        try {
            source.put(0, 0, frame.luma)
            when (frame.clockwiseRotationDegrees) {
                90 -> Core.rotate(source, oriented, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(source, oriented, Core.ROTATE_180)
                270 -> Core.rotate(source, oriented, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> source.copyTo(oriented)
            }
            val (targetWidth, targetHeight) = targetSize(oriented.cols(), oriented.rows())
            Imgproc.resize(oriented, destination, Size(targetWidth.toDouble(), targetHeight.toDouble()))
        } finally {
            source.release()
            oriented.release()
        }
    }
}
