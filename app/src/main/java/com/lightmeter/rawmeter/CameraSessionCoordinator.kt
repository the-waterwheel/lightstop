package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

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
    var rawReader: ImageReader? = null
        private set
    var trackingReader: ImageReader? = null
        private set

    private var activeGeneration: Int? = null
    private var sessionRevision = 0L

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

        rawReader?.close()
        rawReader = rawSize?.let { size ->
            ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.RAW_SENSOR,
                RAW_READER_MAX_IMAGES,
            ).also { reader ->
                reader.setOnImageAvailableListener(onRawImageAvailable, handler)
            }
        }

        trackingReader?.close()
        trackingReader = trackingSize?.let { size ->
            ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                ZoneLumaBufferPool.DEFAULT_CAPACITY,
            ).also { reader ->
                reader.setOnImageAvailableListener(onTrackingImageAvailable, handler)
            }
        }
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
     * Replaces only the capture session and ImageReaders while retaining the open CameraDevice and
     * TextureView Surface. Excluding [previewSurface] keeps its last submitted buffer visible during
     * a short RAW-only Zone measurement.
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

        invalidateCurrentSession()
        replaceReaders(
            rawSize = rawSize.takeIf { profile.usesRaw },
            trackingSize = trackingSize.takeIf { profile.usesTracking },
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
        try {
            rawReader?.close()
        } catch (_: Exception) {
            Unit
        }
        rawReader = null
        try {
            trackingReader?.close()
        } catch (_: Exception) {
            Unit
        }
        trackingReader = null
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
                session = configuredSession
                listener.onSessionConfigured(camera, configuredSession, preview, generation)
            }

            override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                failedSession.close()
                if (isSessionActive(generation, revision)) {
                    listener.onSessionConfigurationFailed(generation)
                }
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
        } catch (error: Exception) {
            if (isSessionActive(generation, revision)) {
                listener.onSessionConfigurationFailed(generation, error)
            }
        }
    }

    private fun invalidateCurrentSession() {
        sessionRevision += 1L
        val current = session
        session = null
        if (current != null) {
            runCatching { current.stopRepeating() }
            runCatching { current.abortCaptures() }
            runCatching { current.close() }
        }
    }

    private fun replaceReaders(
        rawSize: Size?,
        trackingSize: Size?,
        handler: Handler,
    ) {
        runCatching { rawReader?.close() }
        rawReader = rawSize?.let { size ->
            ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.RAW_SENSOR,
                RAW_READER_MAX_IMAGES,
            ).also { reader ->
                reader.setOnImageAvailableListener(onRawImageAvailable, handler)
            }
        }
        runCatching { trackingReader?.close() }
        trackingReader = trackingSize?.let { size ->
            ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                ZoneLumaBufferPool.DEFAULT_CAPACITY,
            ).also { reader ->
                reader.setOnImageAvailableListener(onTrackingImageAvailable, handler)
            }
        }
    }

    private fun isActive(generation: Int): Boolean = activeGeneration == generation

    private fun isSessionActive(generation: Int, revision: Long): Boolean =
        isActive(generation) && sessionRevision == revision

    private companion object {
        private const val TAG = "CameraSession"
        private const val RAW_READER_MAX_IMAGES = 1
    }
}
