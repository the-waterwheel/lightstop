package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException

internal interface CameraSessionCoordinatorListener {
    fun onCameraOpened(generation: Int)
    fun onSessionConfigured(
        device: CameraDevice,
        session: CameraCaptureSession,
        previewSurface: Surface?,
        generation: Int,
    )
    fun onSessionConfigurationFailed(generation: Int, error: Exception? = null)
    fun onCameraDisconnected(generation: Int)
    fun onCameraError(error: Int, generation: Int)
}

/**
 * Owns Camera2 session resources and their close order.
 *
 * CameraController decides which lens and stream profile to use; this coordinator materializes
 * that decision as a preview Surface, optional RAW/YUV ImageReaders, CameraDevice and capture
 * session. A generation token rejects callbacks from a session that has already been closed.
 *
 * Configuration, open and close calls are confined to the camera handler. Image callbacks are
 * forwarded on that same handler. Consumers own acquired Images, while this class owns readers.
 */
internal class CameraSessionCoordinator(
    private val cameraManager: CameraManager,
    private val onRawImageAvailable: (ImageReader) -> Unit,
    private val onTrackingImageAvailable: (ImageReader) -> Unit,
    private val sessionParametersProvider: (CameraDevice, Boolean) -> CaptureRequest?,
    private val listener: CameraSessionCoordinatorListener,
) {
    @Volatile
    var device: CameraDevice? = null
        private set

    @Volatile
    var session: CameraCaptureSession? = null
        private set

    @Volatile
    var isOpening: Boolean = false
        private set

    var previewSurface: Surface? = null
        private set
    /**
     * Only readers attached to the current Camera2 session are exposed to consumers. The backing
     * readers may stay allocated while dormant so an isolated Zone RAW round trip does not rebuild
     * both buffer queues. Dormant surfaces are never added to the active session.
     */
    val rawReader: ImageReader?
        get() = cachedRawReader.takeIf { rawOutputActive }
    val trackingReader: ImageReader?
        get() = cachedTrackingReader.takeIf { trackingOutputActive }

    private var cachedRawReader: ImageReader? = null
    private var cachedTrackingReader: ImageReader? = null
    private var cachedRawSize: Size? = null
    private var cachedTrackingSize: Size? = null
    private var rawOutputActive = false
    private var trackingOutputActive = false

    private var activeGeneration: Int? = null
    private var sessionRevision = 0L
    private var configurationTimeoutHandler: Handler? = null
    private var configurationTimeoutRevision: Long? = null
    private var configurationTimeoutTask: Runnable? = null

    fun configureOutputs(
        surfaceTexture: SurfaceTexture,
        previewSize: Size,
        rawSize: Size?,
        trackingSize: Size?,
        handler: Handler,
    ) {
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)

        closeCachedReaders()
        ensureReaders(rawSize, trackingSize)
        setActiveReaders(
            rawActive = rawSize != null,
            trackingActive = trackingSize != null,
            handler = handler,
        )
    }

    @SuppressLint("MissingPermission")
    fun open(
        logicalCameraId: String,
        physicalCameraId: String?,
        generation: Int,
        handler: Handler,
    ) {
        check(!isOpening && device == null) { "Camera session is already opening or open" }
        checkNotNull(previewSurface) { "Preview output is not configured" }
        activeGeneration = generation
        isOpening = true
        try {
            cameraManager.openCamera(
                logicalCameraId,
                createDeviceCallback(physicalCameraId, generation, handler),
                handler,
            )
        } catch (error: Exception) {
            isOpening = false
            activeGeneration = null
            throw error
        }
    }

    /**
     * Replaces only the capture session while retaining the open CameraDevice, TextureView Surface,
     * and compatible dormant ImageReaders. Excluding [previewSurface] keeps its last submitted
     * buffer visible during a short RAW-only Zone measurement. Reader retention does not combine
     * stream profiles: [createSession] adds only the outputs enabled by [profile].
     */
    fun reconfigure(
        profile: CameraSessionProfile,
        rawSize: Size?,
        trackingSize: Size?,
        physicalCameraId: String?,
        generation: Int,
        handler: Handler,
    ) {
        val camera = checkNotNull(device) { "Camera device is not open" }
        check(isActive(generation)) { "Camera generation is no longer active" }
        val preview = previewSurface
        if (profile.usesPreview) checkNotNull(preview) { "Preview output is not configured" }
        if (profile.usesRaw) checkNotNull(rawSize) { "RAW output size is unavailable" }
        if (profile.usesTracking) checkNotNull(trackingSize) { "YUV output size is unavailable" }

        // Stop callbacks before invalidating the old session. A dormant Reader may retain its
        // Surface, but it must not deliver a late frame into the other isolated workflow.
        deactivateReaderCallbacks()
        invalidateCurrentSession()
        ensureReaders(
            rawSize = rawSize.takeIf { profile.usesRaw },
            trackingSize = trackingSize.takeIf { profile.usesTracking },
        )
        setActiveReaders(
            rawActive = profile.usesRaw,
            trackingActive = profile.usesTracking,
            handler = handler,
        )
        createSession(
            camera = camera,
            physicalCameraId = physicalCameraId,
            preview = preview.takeIf { profile.usesPreview },
            generation = generation,
            handler = handler,
        )
    }

    /** Closes producers before readers and releases the TextureView Surface last. */
    fun close() {
        activeGeneration = null
        sessionRevision += 1L
        cancelSessionConfigurationTimeout()
        isOpening = false
        try {
            session?.close()
        } catch (_: Exception) {
            Unit
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
            Unit
        }
        device = null
        closeCachedReaders()
        previewSurface?.release()
        previewSurface = null
    }

    private fun createDeviceCallback(
        physicalCameraId: String?,
        generation: Int,
        handler: Handler,
    ) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!isActive(generation)) {
                camera.close()
                return
            }
            isOpening = false
            device = camera
            listener.onCameraOpened(generation)
            createSession(camera, physicalCameraId, previewSurface, generation, handler)
        }

        override fun onDisconnected(camera: CameraDevice) {
            if (!isActive(generation)) {
                camera.close()
                return
            }
            invalidateCurrentSession()
            camera.close()
            if (device === camera) device = null
            isOpening = false
            listener.onCameraDisconnected(generation)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            if (!isActive(generation)) {
                camera.close()
                return
            }
            invalidateCurrentSession()
            camera.close()
            if (device === camera) device = null
            isOpening = false
            listener.onCameraError(error, generation)
        }
    }

    private fun createSession(
        camera: CameraDevice,
        physicalCameraId: String?,
        preview: Surface?,
        generation: Int,
        handler: Handler,
    ) {
        val revision = ++sessionRevision
        val outputs = buildList {
            preview?.let(::add)
            rawReader?.surface?.let(::add)
            trackingReader?.surface?.let(::add)
        }
        check(outputs.isNotEmpty()) { "Camera session has no outputs" }
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configuredSession: CameraCaptureSession) {
                if (!isSessionActive(generation, revision) || device !== camera) {
                    configuredSession.close()
                    return
                }
                cancelSessionConfigurationTimeout(revision)
                session = configuredSession
                listener.onSessionConfigured(camera, configuredSession, preview, generation)
            }

            override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                failedSession.close()
                reportSessionConfigurationFailure(generation, revision, null)
            }
        }
        try {
            val outputConfigurations = outputs.map { surface ->
                OutputConfiguration(surface).apply {
                    if (physicalCameraId != null) setPhysicalCameraId(physicalCameraId)
                }
            }
            val executor = Executor(handler::post)
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigurations,
                executor,
                stateCallback,
            )
            sessionParametersProvider(camera, preview != null)?.let {
                configuration.setSessionParameters(it)
            }
            val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    camera.isSessionConfigurationSupported(configuration)
                } catch (_: UnsupportedOperationException) {
                    // Some vendor HAL implementations expose the method but cannot answer the
                    // query. Let actual session creation remain the source of truth.
                    true
                } catch (_: CameraAccessException) {
                    // A transient query failure must not be treated as a definitive unsupported
                    // combination. The real create call/callback provides the actionable result.
                    true
                }
            } else {
                // Android 9 has SessionConfiguration but not the preflight method.
                true
            }
            if (!supported) {
                // Some vivo/MediaTek builds return false while replacing an isolated RAW
                // session even though the identical processed session was configured moments
                // earlier. Treat this API as a preflight hint: the asynchronous configure
                // callback remains the only reliable rejection signal across vendor HALs.
                Log.w(
                    TAG,
                    "Session preflight reported unsupported; attempting real configuration",
                )
            }
            camera.createCaptureSession(configuration)
            armSessionConfigurationTimeout(handler, generation, revision)
        } catch (error: Exception) {
            reportSessionConfigurationFailure(generation, revision, error)
        }
    }

    private fun invalidateCurrentSession() {
        cancelSessionConfigurationTimeout()
        sessionRevision += 1L
        val current = session
        session = null
        if (current != null) {
            runCatching { current.stopRepeating() }
            runCatching { current.abortCaptures() }
            runCatching { current.close() }
        }
    }

    private fun armSessionConfigurationTimeout(
        handler: Handler,
        generation: Int,
        revision: Long,
    ) {
        cancelSessionConfigurationTimeout()
        val timeout = Runnable {
            reportSessionConfigurationFailure(
                generation = generation,
                revision = revision,
                error = TimeoutException(
                    "Camera session configuration timed out after " +
                        "$SESSION_CONFIGURATION_TIMEOUT_MS ms",
                ),
            )
        }
        configurationTimeoutHandler = handler
        configurationTimeoutRevision = revision
        configurationTimeoutTask = timeout
        if (!handler.postDelayed(timeout, SESSION_CONFIGURATION_TIMEOUT_MS)) {
            reportSessionConfigurationFailure(
                generation,
                revision,
                IllegalStateException("Camera handler rejected the session timeout task"),
            )
        }
    }

    private fun reportSessionConfigurationFailure(
        generation: Int,
        revision: Long,
        error: Exception?,
    ) {
        if (!isSessionActive(generation, revision)) return
        cancelSessionConfigurationTimeout(revision)
        // Reject every later callback from this configure operation before recovery starts.
        sessionRevision += 1L
        if (error is TimeoutException) {
            Log.e(TAG, "Camera session configure callback timed out generation=$generation revision=$revision")
        }
        listener.onSessionConfigurationFailed(generation, error)
    }

    private fun cancelSessionConfigurationTimeout(revision: Long? = null) {
        if (revision != null && configurationTimeoutRevision != revision) return
        val handler = configurationTimeoutHandler
        val task = configurationTimeoutTask
        if (handler != null && task != null) handler.removeCallbacks(task)
        configurationTimeoutHandler = null
        configurationTimeoutRevision = null
        configurationTimeoutTask = null
    }

    private fun ensureReaders(
        rawSize: Size?,
        trackingSize: Size?,
    ) {
        if (rawSize != null && (cachedRawReader == null || cachedRawSize != rawSize)) {
            runCatching { cachedRawReader?.close() }
            cachedRawReader = ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                RAW_READER_MAX_IMAGES,
            )
            cachedRawSize = rawSize
        }
        if (trackingSize != null &&
            (cachedTrackingReader == null || cachedTrackingSize != trackingSize)
        ) {
            runCatching { cachedTrackingReader?.close() }
            cachedTrackingReader = ImageReader.newInstance(
                trackingSize.width,
                trackingSize.height,
                ImageFormat.YUV_420_888,
                ZoneLumaBufferPool.DEFAULT_CAPACITY,
            )
            cachedTrackingSize = trackingSize
        }
    }

    private fun setActiveReaders(
        rawActive: Boolean,
        trackingActive: Boolean,
        handler: Handler,
    ) {
        check(!rawActive || cachedRawReader != null) { "RAW reader is unavailable" }
        check(!trackingActive || cachedTrackingReader != null) { "YUV reader is unavailable" }
        rawOutputActive = rawActive
        trackingOutputActive = trackingActive
        cachedRawReader?.let { reader ->
            drainReader(reader)
            reader.setOnImageAvailableListener(
                if (rawActive) onRawImageAvailable else null,
                if (rawActive) handler else null,
            )
        }
        cachedTrackingReader?.let { reader ->
            drainReader(reader)
            reader.setOnImageAvailableListener(
                if (trackingActive) onTrackingImageAvailable else null,
                if (trackingActive) handler else null,
            )
        }
        Log.i(
            TAG,
            "Reader cache activeRaw=$rawActive activeTracking=$trackingActive " +
                "cachedRaw=${cachedRawReader != null} cachedTracking=${cachedTrackingReader != null}",
        )
    }

    private fun deactivateReaderCallbacks() {
        cachedRawReader?.setOnImageAvailableListener(null, null)
        cachedTrackingReader?.setOnImageAvailableListener(null, null)
        rawOutputActive = false
        trackingOutputActive = false
    }

    private fun drainReader(reader: ImageReader) {
        while (true) {
            val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
            image.close()
        }
    }

    private fun closeCachedReaders() {
        deactivateReaderCallbacks()
        runCatching { cachedRawReader?.close() }
        runCatching { cachedTrackingReader?.close() }
        cachedRawReader = null
        cachedTrackingReader = null
        cachedRawSize = null
        cachedTrackingSize = null
    }

    private fun isActive(generation: Int): Boolean = activeGeneration == generation

    private fun isSessionActive(generation: Int, revision: Long): Boolean =
        isActive(generation) && sessionRevision == revision

    private companion object {
        private const val TAG = "CameraSession"
        private const val RAW_READER_MAX_IMAGES = 1
        private const val SESSION_CONFIGURATION_TIMEOUT_MS = 8_000L
    }
}
