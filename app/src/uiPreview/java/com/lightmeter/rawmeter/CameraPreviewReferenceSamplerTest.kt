package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Color
import android.view.TextureView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraPreviewReferenceSamplerTest {
    @Test fun batchReferencesComeFromOneRecycledBitmap() {
        val view = SampleTextureView()
        val sampler = CameraPreviewReferenceSampler { view }
        val references = sampler.captureBatch(listOf(
            ZoneMeteringRequest(1, target(0.25f)), ZoneMeteringRequest(2, target(0.75f)),
        ))
        assertEquals(setOf(1, 2), references.keys)
        assertTrue(references.values.all { it != null && it.samples.size == 121 })
        assertEquals(1, view.bitmapReads)
        assertTrue(checkNotNull(view.lastBitmap).isRecycled)
    }

    @Test fun unavailablePreviewDoesNotReadPixels() {
        val view = SampleTextureView().apply { available = false }
        val sampler = CameraPreviewReferenceSampler { view }
        assertNull(sampler.capture(target(0.5f)))
        assertTrue(sampler.captureBatch(listOf(ZoneMeteringRequest(1, target(0.5f)))).isEmpty())
        assertEquals(0, view.bitmapReads)
    }

    @Test fun cameraWorkerCannotReadTextureViewPixels() {
        val view = SampleTextureView()
        val sampler = CameraPreviewReferenceSampler { view }
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertNull(worker.submit<PreviewLumaReference?> { sampler.capture(target(0.5f)) }.get())
            assertEquals(0, view.bitmapReads)
        } finally { worker.shutdownNow() }
    }

    private fun target(position: Float) = ZoneMeteringTarget(position, position, position, position, 1f, 1f)

    private class SampleTextureView : TextureView(RuntimeEnvironment.getApplication()) {
        var available = true
        var bitmapReads = 0
        var lastBitmap: Bitmap? = null
        init { layout(0, 0, 640, 480) }
        override fun isAvailable() = available
        override fun getBitmap(width: Int, height: Int): Bitmap {
            bitmapReads++
            val pixels = IntArray(width * height) { index ->
                val level = if (((index % width) / 7 + (index / width) / 11) % 2 == 0) 30 else 220
                Color.rgb(level, level, level)
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).also { lastBitmap = it }
        }
    }
}
