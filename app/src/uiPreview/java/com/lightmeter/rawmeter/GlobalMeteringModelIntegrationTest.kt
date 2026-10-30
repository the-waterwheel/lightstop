package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CaptureResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCaptureResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlobalMeteringModelIntegrationTest {
    @Test fun packagedEpoch219IsUsedByGlobalReadingAndUserCompensationIsAppliedOnce() {
        val context = RuntimeEnvironment.getApplication()
        GlobalMeteringModel.initialize(context.assets)
        val result = ShadowCaptureResult.newCaptureResult().also {
            shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME,10_000_000L)
            shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY,100)
            shadowOf(it).set(CaptureResult.LENS_APERTURE,2f)
        }
        val bitmap = Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128,128,128)) }
        val stat = MeteringAnalysis.analyzePreview(bitmap,result,ShadowCameraCharacteristics.newCameraCharacteristics(),
            "0",MeteringMode.AVERAGE,CameraCalibrationStore(context),
            evaluativeViewport=EvaluativeMeteringViewport(1.5f,1f,NormalizedSensorViewport.FULL))!!
        assertNotNull(stat.neuralExposure)
        val prediction = GlobalMeteringModel.predict(stat.globalInput!!)!!
        val cameraEv = kotlin.math.ln(400.0)/kotlin.math.ln(2.0)
        val reading = MeteringFusion.fuse(listOf(stat),MeteringSource.ISP_PREVIEW)!!
        assertEquals(219,reading.evaluative!!.modelEpoch)
        assertEquals(cameraEv-prediction.compensationEv,reading.evaluative.recommendedEv100,1e-6)
        assertNull(reading.calibrationSample)
        val state = MeterState(context)
        state.acceptNormalReading(reading)
        state.exposureCompSteps = 6
        assertEquals(reading.evaluative.recommendedEv100-1.0,state.ambientEffectiveEv100!!,1e-6)
        bitmap.recycle()
    }
}
