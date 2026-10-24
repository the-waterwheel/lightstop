package com.lightmeter.rawmeter

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises renderer ownership and stale callbacks without a real EGL worker or camera. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CameraFilmPreviewCoordinatorTest {
    @Test fun newRendererWaitsForOldGlReleaseToFinish() {
        val fixture = Fixture()
        val output = SurfaceTexture(0)
        assertNull(fixture.owner.inputFor(output))
        assertSame(output, fixture.owner.inputFor(output))
        fixture.owner.releaseRenderer()
        assertTrue(fixture.owner.isReleasingRenderer)
        assertNull(fixture.owner.inputFor(output))
        assertEquals(1, fixture.renderers.size)

        fixture.renderers.single().finishRelease()
        drain()
        assertFalse(fixture.owner.isReleasingRenderer)
        assertNull(fixture.owner.inputFor(output))
        assertEquals(2, fixture.renderers.size)
    }

    @Test fun glReleaseAfterStopCanReopenThroughTheNewForegroundHandler() {
        val fixture = Fixture()
        fixture.owner.inputFor(SurfaceTexture(0))
        fixture.owner.releaseRenderer()
        fixture.host.cameraHandler = null
        fixture.renderers.single().finishRelease()
        // Resume before the lifecycle-thread continuation runs. The retired handler is unusable.
        fixture.host.cameraHandler = Handler(Looper.getMainLooper())
        fixture.host.started = true
        drain()
        assertEquals(1, fixture.host.openRequests)
    }

    @Test fun failureFromAnOldRendererCannotCloseItsReplacement() {
        val fixture = Fixture()
        val output = SurfaceTexture(0)
        fixture.owner.inputFor(output)
        val old = fixture.renderers.single()
        fixture.owner.releaseRenderer()
        old.finishRelease()
        drain()
        fixture.owner.inputFor(output)

        old.fail(IllegalStateException("late failure"))
        drain()
        assertEquals(0, fixture.host.closeRequests)
        assertTrue(fixture.owner.hasRenderer)
        assertFalse(fixture.owner.isReleasingRenderer)

        fixture.renderers.last().fail(IllegalStateException("current failure"))
        drain()
        assertEquals(1, fixture.host.closeRequests)
        assertEquals(1, fixture.host.failureRequests)
        assertFalse(fixture.owner.hasRenderer)
    }

    @Test fun settingsForNegativeToolDoNotInvertTheOrdinaryPreview() {
        val fixture = Fixture()
        fixture.owner.updateSettings(FilmNegativeSettings(inverted = true, showGuide = true))
        drain()
        fixture.owner.inputFor(SurfaceTexture(0))
        val renderer = fixture.renderers.single()
        assertFalse(renderer.initialSettings.inverted)
        assertFalse(renderer.initialSettings.showGuide)
        fixture.owner.updateSettings(FilmNegativeSettings(inverted = true))
        drain()
        assertEquals(0, renderer.settingsUpdates)
    }

    @Test fun firstFrameFromAnOldRendererCannotAnnounceItsReplacementAsReady() {
        val fixture = Fixture()
        fixture.owner.setEnabled(true)
        drain()
        val ready = mutableListOf<String>()
        fixture.owner.onReady = ready::add
        val output = SurfaceTexture(0)
        fixture.owner.inputFor(output)
        val old = fixture.renderers.single()
        fixture.owner.releaseRenderer()
        old.finishRelease()
        drain()
        fixture.owner.inputFor(output)

        old.firstFrame()
        drain()
        assertTrue(ready.isEmpty())
        fixture.renderers.last().firstFrame()
        drain()
        assertEquals(listOf("0"), ready)
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private class Fixture {
        val host = FakeHost()
        val renderers = mutableListOf<FakeRenderer>()
        val owner = CameraFilmPreviewCoordinator(
            mainHandler = Handler(Looper.getMainLooper()),
            host = host,
            localized = { _, english -> english },
            rendererFactory = CameraFilmPreviewRendererFactory { output, settings, _, first, failure ->
                FakeRenderer(output, settings, first, failure).also(renderers::add)
            },
        )
        init { host.onClose = owner::onCameraClosed }
    }

    private class FakeHost : CameraFilmPreviewHost {
        override var cameraHandler: Handler? = Handler(Looper.getMainLooper())
        override val cameraGeneration = 1
        override val cameraInfo = CameraUiInfo(cameraId = "0")
        override val requestCharacteristics: CameraCharacteristics? = null
        override val sessionAvailable = true
        override val textureView: TextureView? = null
        override var started = false
        override val stopInProgress = false
        override val displayRotation = 0
        override val displayZoom = 1f
        var onClose: () -> Unit = {}
        var closeRequests = 0
        var openRequests = 0
        var failureRequests = 0
        override fun closeCamera() { closeRequests++; onClose() }
        override fun resetRecoveryState() = Unit
        override fun openCamera(output: SurfaceTexture?) { openRequests++ }
        override fun updatePreviewRepeatingRequest() = Unit
        override fun finishCameraFailure(message: String) { failureRequests++ }
    }

    private class FakeRenderer(
        override val outputTexture: SurfaceTexture,
        val initialSettings: FilmNegativeSettings,
        val firstFrame: () -> Unit,
        val fail: (Exception) -> Unit,
    ) : CameraFilmPreviewRenderer {
        override val inputTexture = outputTexture
        override val orientationToken = 0L
        var settingsUpdates = 0
        private var released: (() -> Unit)? = null
        override fun start() = Unit
        override fun resize(width: Int, height: Int) = Unit
        override fun orient(displayDegrees: Int, mirrored: Boolean, zoom: Float) = Unit
        override fun update(settings: FilmNegativeSettings) { settingsUpdates++ }
        override fun observe(evidence: FilmNegativeFrameEvidence) = Unit
        override fun captureFrame(maxEdge: Int, completion: (FilmNegativeFrozenFrame?) -> Unit) = completion(null)
        override fun sampleBase(automatic: Boolean, completion: (FilmNegativeBaseSample?) -> Unit) = completion(null)
        override fun release(completion: () -> Unit) { released = completion }
        fun finishRelease() { checkNotNull(released).invoke(); released = null }
    }
}
