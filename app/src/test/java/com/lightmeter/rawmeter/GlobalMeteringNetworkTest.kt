package com.lightmeter.rawmeter

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GlobalMeteringNetworkTest {
    private fun bytes() = File("src/main/assets/global_metering_epoch219.bin").readBytes()

    @Test fun actualEpoch219NetworkMatchesPytorchIncludingNormalizationAndOutputTransforms() {
        val model = GlobalMeteringNetwork.decode(bytes())
        val rows = javaClass.classLoader!!.getResourceAsStream("global-metering-epoch219.tsv")!!
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") || it.isBlank() }
        var maximum = 0.0
        for (row in rows) {
            val parts = row.split('\t')
            val result = model.predict(parts.drop(3).map(String::toFloat).toFloatArray())!!
            maximum = maxOf(maximum, kotlin.math.abs(parts[1].toDouble() - result.compensationEv))
            assertEquals(parts[0], parts[1].toDouble(), result.compensationEv, .001)
            assertEquals(parts[0], parts[2].toDouble(), result.errorScaleEv, .001)
        }
        val input = rows.first { it.startsWith("gradient\t") }.split('\t').drop(3).map(String::toFloat).toFloatArray()
        repeat(200) { model.predict(input) }
        val began = System.nanoTime()
        repeat(2000) { model.predict(input) }
        val result = File(System.getProperty("uiPreview.output", "build/ui-preview"), "global-network-epoch219.json")
        result.parentFile?.mkdirs()
        result.writeText("""{"epoch":219,"cases":${rows.size},"max_compensation_difference_ev":$maximum,"jvm_inference_mean_ms":${(System.nanoTime()-began)/2e9},"device_benchmark":false}""")
    }

    @Test fun malformedWeightsAndInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { GlobalMeteringNetwork.decode(ByteArray(8)) }
        val corrupt = bytes().also { it[12] = 1 }
        assertThrows(IllegalArgumentException::class.java) { GlobalMeteringNetwork.decode(corrupt) }
        val model = GlobalMeteringNetwork.decode(bytes())
        assertNull(model.predict(FloatArray(775)))
        assertNull(model.predict(FloatArray(776) { Float.NaN }))
    }

    private fun stat(cameraEv: Double, compensation: Double) = MeteringFrameStat(12.0, .18, 0.0, 100,
        10_000_000L, 2f, evaluative = EvaluativeFrameStats(12.0,16.0,true,true),
        neuralExposure = GlobalMeteringExposure.from(cameraEv, GlobalMeteringPrediction(compensation,.3)))

    @Test fun positiveCompensationIncreasesExposure() {
        val reading = MeteringFusion.fuse(listOf(stat(12.0,1.0)),MeteringSource.RAW)!!
        assertEquals(11.0,reading.evaluative!!.recommendedEv100,1e-9)
        assertEquals(-1.0,reading.evaluative.exposureReductionEv,1e-9)
        assertEquals(219,reading.evaluative.modelEpoch)
        assertNull(reading.calibrationSample)
    }

    @Test fun burstFusesTargetsUsingEachFramesOwnExposureAndDoesNotStackHighlightPolicy() {
        val reading = MeteringFusion.fuse(listOf(stat(12.0,-1.0),stat(14.0,1.0)),MeteringSource.RAW)!!
        assertEquals(13.0,reading.evaluative!!.recommendedEv100,1e-9)
        assertEquals(1.0,reading.evaluative.exposureReductionEv,1e-9)
        val fallback = MeteringFusion.fuse(listOf(stat(12.0,-1.0).copy(neuralExposure=null)),MeteringSource.RAW)!!
        assertNull(fallback.evaluative!!.modelEpoch)
        assertEquals(12.5,fallback.evaluative.recommendedEv100,1e-9)
    }
}
