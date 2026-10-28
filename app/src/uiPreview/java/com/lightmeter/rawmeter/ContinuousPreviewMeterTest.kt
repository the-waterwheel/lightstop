package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCaptureResult
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPreviewMeterTest {
    @Test fun repeatedGlobalSamplesUseTheirOwnExposureWithoutRestoringNeutralAe() {
        val f=Fixture();f.meter.setEnabled(true);drain()
        assertEquals(1,f.readings.size)
        assertNotNull(f.readings.single().evaluative)
        val initial=f.readings.single().sceneEv100
        // The simulated camera exposure changes by one stop and the displayed linear signal
        // halves. Matching metadata must cancel that change in the estimated scene brightness.
        f.time=5_000_000L;f.gray=92;f.timestamp+=33_333_333L
        advance(350)
        assertEquals(2,f.readings.size)
        assertEquals(initial,f.readings.last().sceneEv100,.04)
        assertEquals(MeteringSource.ISP_PREVIEW,f.readings.last().source)
        assertTrue(f.bitmaps.all { it.isRecycled })
        f.meter.setEnabled(false);advance(3_000)
        assertEquals(2,f.readings.size)
    }
    @Test fun cameraBusyGateAndPauseDiscardPendingWorkAndResumeOnlyWhenEnabled() {
        val f=Fixture();f.ready=false
        f.meter.setEnabled(true);advance(1_000)
        assertTrue(f.readings.isEmpty());assertTrue(f.bitmaps.isEmpty())
        f.ready=true;advance(350);assertEquals(1,f.readings.size)
        f.meter.suspend();advance(2_000);assertEquals(1,f.readings.size)
        f.meter.resume();advance(350);assertEquals(2,f.readings.size)
        f.meter.setEnabled(false);f.meter.resume();advance(2_000)
        assertEquals(2,f.readings.size)
    }
    @Test fun frameSamplingDoesNotIncludeTheBrightAreaOutsideTheSelectedFrame() {
        val f=Fixture();f.gray=128
        f.meter.setEnabled(true);drain()
        val expected=ExposurePreviewMath.cameraEv100(2.0,10_000_000L,100)!!+
            ExposureMath.log2(ProcessedLumaDecoder(ProcessedTransfer.SRGB).linearLuma(128/255.0,128/255.0,128/255.0)/.18)
        assertEquals(expected,f.readings.single().sceneEv100,.001)
        f.meter.setEnabled(false)
    }
    private class Fixture {
        val handler=Handler(Looper.getMainLooper())
        var ready=true;var time=10_000_000L;var gray=128;var timestamp=1_000_000_000L
        val bitmaps=mutableListOf<Bitmap>();val readings=mutableListOf<MeterReading>()
        val view: TextureView=object:TextureView(RuntimeEnvironment.getApplication()) {
            private val texture: SurfaceTexture=object: SurfaceTexture(0){ override fun getTimestamp(): Long=this@Fixture.timestamp }
            override fun isAvailable()=true
            override fun getSurfaceTexture()=texture
            override fun getBitmap(w: Int,h: Int): Bitmap {
                val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                val pixels=IntArray(w*h){index ->
                    val x=index%w;val y=index/w
                    if(x in w/4 until w*3/4 && y in h/4 until h*3/4) Color.rgb(gray,gray,gray) else Color.WHITE
                }
                b.setPixels(pixels,0,w,0,0,w,h);bitmaps+=b;return b
            }
        }.apply { layout(0,0,320,320) }
        private val chars=ShadowCameraCharacteristics.newCameraCharacteristics()
        val meter=ContinuousPreviewMeter(handler,{handler},context={
            CompatibleMeteringContext(view,handler,false,{ready},{ts -> result(ts)},{chars},{"auto-test"},
                evaluativeViewport=EvaluativeMeteringViewport(1f,1f,NormalizedSensorViewport(.25f,.25f,.75f,.75f)))
        },canSample={ready},calibrationStore=CameraCalibrationStore(RuntimeEnvironment.getApplication()),
            localized={_,en->en},onReading={readings+=it},onError={throw AssertionError(it)})
        private fun result(ts: Long): CaptureResult=ShadowCaptureResult.newCaptureResult().also {
            shadowOf(it).set(CaptureResult.SENSOR_TIMESTAMP,ts)
            shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME,time)
            shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY,100)
            shadowOf(it).set(CaptureResult.LENS_APERTURE,2f)
            shadowOf(it).set(CaptureResult.CONTROL_AE_MODE,CaptureResult.CONTROL_AE_MODE_OFF)
        }
    }
    private companion object {
        fun drain()=shadowOf(Looper.getMainLooper()).idle()
        fun advance(ms: Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
}
