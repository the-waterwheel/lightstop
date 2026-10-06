package com.lightmeter.rawmeter

import android.graphics.Matrix
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns TextureView transport geometry and recovery independently of Camera2 session selection.
 *
 * Keeping this state outside [CameraController] makes an important boundary explicit: changing or
 * recommitting a TextureView matrix must never rebuild a session, merge RAW/YUV outputs or alter a
 * vendor-HAL fallback decision.
 */
internal class PreviewSurfaceCoordinator(
    private val mainHandler: Handler,
    private val textureView: () -> TextureView?,
    private val previewSize: () -> Size?,
    private val sensorOrientationDegrees: () -> Int,
    private val lensFacing: () -> Int,
    private val processedPreview: () -> Boolean = { false },
) {
    @Volatile
    var viewWidth: Int = 0
        private set
    @Volatile
    var viewHeight: Int = 0
        private set
    @Volatile
    var displayRotation: Int = Surface.ROTATION_0
        private set
    @Volatile
    var displayZoom: Float = 1f
        private set

    private val revision = AtomicInteger(0)
    private val syncToken = Any()
    @Volatile
    private var confirmationFramesRemaining = 0
    @Volatile
    private var forceRecommitOnNextFrame = false
    private var lastWatchdogAtMs = 0L

    fun update(
        viewWidth: Int,
        viewHeight: Int,
        displayRotation: Int,
        displayZoom: Float,
    ) {
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        this.displayRotation = displayRotation
        this.displayZoom = displayZoom
        val expectedRevision = revision.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(syncToken)
        val now = SystemClock.uptimeMillis()
        // Some vendor window managers publish the final TextureView bounds and rotation well after
        // the first frame. Re-read live state over a bounded UI-only convergence window.
        CONVERGENCE_DELAYS_MS.forEach { delayMs ->
            mainHandler.postAtTime(
                { apply(expectedRevision) },
                syncToken,
                now + delayMs,
            )
        }
    }

    fun prepareForForeground(
        viewWidth: Int,
        viewHeight: Int,
        displayRotation: Int,
        displayZoom: Float,
    ) {
        forceRecommitOnNextFrame = true
        confirmationFramesRemaining = CONFIRMATION_FRAMES
        update(viewWidth, viewHeight, displayRotation, displayZoom)
        // Only CameraSessionCoordinator owns producer buffer dimensions. TextureView may
        // resize its consumer during this traversal; writing dimensions here races that work.
    }

    /** Arms a fresh native-layer transaction after an actual stopped-to-started transition. */
    fun armForFreshStart() {
        forceRecommitOnNextFrame = true
    }

    fun onPreviewStarted() {
        forceRecommitOnNextFrame = true
        confirmationFramesRemaining = CONFIRMATION_FRAMES
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        confirmationFramesRemaining = CONFIRMATION_FRAMES
        val rotation = textureView()?.display?.rotation ?: displayRotation
        update(width, height, rotation, displayZoom)
    }

    /**
     * Called for each presented TextureView frame. Matrix recovery stays entirely on the UI side;
     * this method deliberately has no callback into Camera2 session configuration.
     */
    fun onFrameAvailable() {
        val forceRecommitScheduled = forceRecommitOnNextFrame
        if (forceRecommitScheduled) {
            forceRecommitOnNextFrame = false
            // Never publish an intentionally stretched identity frame. Apply the absolute
            // transform to the current bounds in one transaction, including on resume.
            lastWatchdogAtMs = SystemClock.uptimeMillis()
            apply(revision.get())
        }
        if (!forceRecommitScheduled && confirmationFramesRemaining > 0) {
            confirmationFramesRemaining -= 1
            val texture = textureView()
            update(
                texture?.width ?: viewWidth,
                texture?.height ?: viewHeight,
                texture?.display?.rotation ?: displayRotation,
                displayZoom,
            )
        }
        val now = SystemClock.uptimeMillis()
        if (!forceRecommitScheduled && now - lastWatchdogAtMs >= WATCHDOG_INTERVAL_MS) {
            lastWatchdogAtMs = now
            apply(revision.get())
        }
    }

    /** Invalidates a queued matrix before a different camera route changes the buffer dimensions. */
    fun invalidateForStreamChange() {
        revision.incrementAndGet()
    }

    fun reset() {
        revision.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(syncToken)
        confirmationFramesRemaining = 0
        forceRecommitOnNextFrame = false
        lastWatchdogAtMs = 0L
    }

    private fun apply(expectedRevision: Int) {
        if (expectedRevision != revision.get()) return
        val texture = textureView() ?: return
        val size = previewSize() ?: return
        val actualWidth = texture.width.takeIf { it > 0 } ?: viewWidth
        val actualHeight = texture.height.takeIf { it > 0 } ?: viewHeight
        if (actualWidth <= 0 || actualHeight <= 0) return
        val actualRotation = texture.display?.rotation ?: displayRotation
        viewWidth = actualWidth
        viewHeight = actualHeight
        displayRotation = actualRotation
        if (processedPreview()) {
            texture.setTransform(Matrix())
            return
        }
        texture.setTransform(
            CameraPreviewTransform.create(
                viewWidth = actualWidth,
                viewHeight = actualHeight,
                displayRotation = actualRotation,
                displayZoom = displayZoom,
                bufferSize = size,
                sensorOrientationDegrees = sensorOrientationDegrees(),
                lensFacing = lensFacing(),
            ),
        )
    }

    private companion object {
        private const val CONFIRMATION_FRAMES = 4
        private const val WATCHDOG_INTERVAL_MS = 500L
        private val CONVERGENCE_DELAYS_MS = longArrayOf(
            0L,
            16L,
            50L,
            120L,
            250L,
            500L,
            1_000L,
            2_000L,
        )
    }
}
