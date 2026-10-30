package com.lightmeter.rawmeter

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class GlobalMeteringInputMappingTest {
    private val mode = MeteringMode.AVERAGE
    private val n = GlobalMeteringInputMapping.SAMPLES
    private val identity = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)

    @Test fun onlyGlobalCanCreateMappedModelInputs() {
        for (local in listOf(MeteringMode.SPOT,MeteringMode.CENTER_WEIGHTED,MeteringMode.ANGLE)) {
            assertNull(GlobalMeteringInputMapping.fromLinear(local,FloatArray(3*n),MeteringSource.RAW))
            assertNull(GlobalMeteringInputMapping.fromRaw(local,FloatArray(4*n),DoubleArray(4){1.0},identity,0.0))
            assertNull(GlobalMeteringInputMapping.fromProcessedLinear(local,FloatArray(3*n),MeteringSource.YUV_PREVIEW) {
                fail("Local metering must not invoke the global calibration adapter"); 0.0
            })
        }
    }

    @Test fun existingRawCalibrationIsAppliedOnceAndGlobalBrightnessIsRetained() {
        val raw=FloatArray(4*n){.09f}
        val a=GlobalMeteringInputMapping.fromRaw(mode,raw,DoubleArray(4){1.0},identity,0.0)!!
        val b=GlobalMeteringInputMapping.fromRaw(mode,raw,DoubleArray(4){1.0},identity,1.0)!!
        assertEquals(1f,b.features[768]-a.features[768],1e-6f)
        assertEquals(-1f,a.features[768],1e-6f)
        assertEquals(0f,b.features[768],1e-6f)
        assertEquals(776,b.features.size)
        assertEquals(6144,b.sampleCount)
        assertEquals("scene-linear-sparse-pairs-v2",b.domainVersion)
    }

    @Test fun missingRawColorMetadataDoesNotFabricateModelCoordinates() {
        assertNull(GlobalMeteringInputMapping.fromRaw(mode,FloatArray(4*n),null,identity,0.0))
        assertNull(GlobalMeteringInputMapping.fromRaw(mode,FloatArray(4*n),DoubleArray(4){1.0},null,0.0))
        assertNull(GlobalMeteringInputMapping.fromRaw(mode,FloatArray(4*n),DoubleArray(4){1.0},identity,Double.NaN))
    }

    @Test fun invalidOrIncompleteSamplesAreNotSilentlyDropped() {
        assertNull(GlobalMeteringInputMapping.fromLinear(mode,FloatArray(3*n-1),MeteringSource.RAW))
        val values=FloatArray(3*n){.18f}.also { it[33]=Float.NaN }
        assertNull(GlobalMeteringInputMapping.fromLinear(mode,values,MeteringSource.RAW))
    }

    @Test fun gridIsDisplayOrientedInsideTheSelectedCropForAllRotationsAndMirrors() {
        fun first(rotation: Int,mirror: Boolean=false)=GlobalMeteringInputMapping.withSamplePosition(
            0,0,12,24,108,88,rotation,mirror) { x,y -> x to y }
        assertEquals(12 to 24,first(0))
        assertEquals(12 to 87,first(90))
        assertEquals(107 to 87,first(180))
        assertEquals(107 to 24,first(270))
        assertEquals(107 to 24,first(0,true))
        for (rotation in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
            for (row in 0 until 64) for (column in 0 until 96) {
                GlobalMeteringInputMapping.withSamplePosition(column,row,10,20,13,22,rotation,mirror) { x,y ->
                    assertTrue(x in 10..12 && y in 20..21)
                }
            }
        }
    }

    @Test fun productionMappingMatchesPythonTrainingFeaturesAcrossAll776Dimensions() {
        val fixtures=javaClass.getResourceAsStream("/global-metering-mapping-v1.tsv")!!.bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") }.map { it.split('\t') }.toList()
        }.groupBy { it[0] }
        assertEquals(6,fixtures.size)
        val actualCases=fixtures.keys.associateWith { case(it).features }
        val output=java.io.File(System.getProperty("uiPreview.output","build/ui-preview"),"global-mapping-features.tsv")
        output.parentFile?.mkdirs()
        output.printWriter().use { writer ->
            actualCases.forEach { (name,values) -> values.forEachIndexed { index,value -> writer.println("$name\t$index\t$value") } }
        }
        for ((name,rows) in fixtures) {
            val actual=actualCases.getValue(name)
            assertEquals(776,rows.size)
            for (row in rows) {
                val index=row[1].toInt()
                // FP32 Torch reduction and Double fractional integration differ by ~6e-5 at black.
                assertEquals("$name feature $index",row[2].toFloat(),actual[index],1e-4f)
            }
        }
    }

    private fun case(name: String): GlobalMeteringInput = when(name) {
        "raw" -> {
            val multipliers=intArrayOf(11,17,23,37);val offsets=intArrayOf(31,53,79,97)
            val channels=FloatArray(4*n) { at -> val c=at/n; ((at%n*multipliers[c]+offsets[c])%3072)/4095f }
            GlobalMeteringInputMapping.fromRaw(mode,channels,doubleArrayOf(1.4,1.1,.9,1.7),
                doubleArrayOf(1.05,-.02,.01,.03,.98,-.01,-.02,.04,1.03),.25)!!
        }
        "processed" -> {
            fun curve(gamma: Double)=ProcessedToneCurve(DoubleArray(34) { i ->
                val x=(i/2)/16.0; if (i%2==0) x else x.pow(1/gamma)
            })
            val decoder=ProcessedLumaDecoder(ProcessedTransfer.CURVES(curve(2.2),curve(1.8),curve(2.0)))
            val multipliers=intArrayOf(19,31,41);val offsets=intArrayOf(43,61,83)
            val linear=FloatArray(3*n) { at ->
                val c=at/n;val encoded=(((at%n*multipliers[c]+offsets[c])%254)+1)/255f
                when(c) { 0 -> decoder.linearRed(encoded.toDouble()); 1 -> decoder.linearGreen(encoded.toDouble());
                    else -> decoder.linearBlue(encoded.toDouble()) }.toFloat()
            }
            val response=ProcessedResponseCalibration(listOf(ProcessedResponseAnchor(.05,-.2),
                ProcessedResponseAnchor(.4,.15),ProcessedResponseAnchor(.9,.4)))
            GlobalMeteringInputMapping.fromProcessedLinear(mode,linear,MeteringSource.YUV_PREVIEW) {
                response.correctionEv(it,0.0)
            }!!
        }
        "gray_corrected" -> GlobalMeteringInputMapping.fromProcessedLinear(mode,FloatArray(3*n){.18f},
            MeteringSource.ISP_PREVIEW) { -.5 }!!
        else -> {
            val mul=intArrayOf(13,29,47);val add=intArrayOf(37,71,103)
            val rgb=FloatArray(3*n) { at -> when(name) {
                "black" -> 0f; "white" -> 1f
                else -> { val c=at/n; (((at%n*mul[c]+add[c])%997)+1)/998f }
            } }
            GlobalMeteringInputMapping.fromLinear(mode,rgb,MeteringSource.RAW)!!
        }
    }
}
