package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

internal interface CameraSessionCoordinatorListener {
    fun onCameraOpened(generation: Int)
    fun onSessionConfigured(
        device: CameraDevice,
        session: CameraCaptureSession,
        previewSurface: Surface,
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
        val preview = checkNotNull(previewSurface) { "Preview output is not configured" }
        activeGeneration = generation
        isOpening = true
        try {
            cameraManager.openCamera(
                logicalCameraId,
                createDeviceCallback(physicalCameraId, preview, generation, handler),
                handler,
            )
        } catch (error: Exception) {
            isOpening = false
            activeGeneration = null
            throw error
        }
    }

    /** Closes producers before readers and releases the TextureView Surface last. */
    fun close() {
        activeGeneration = null
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
        preview: Surface,
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
            createSession(camera, physicalCameraId, preview, generation, handler)
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
        preview: Surface,
        generation: Int,
        handler: Handler,
    ) {
        val outputs = buildList {
            add(preview)
            rawReader?.surface?.let(::add)
            trackingReader?.surface?.let(::add)
        }
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configuredSession: CameraCaptureSession) {
                if (!isActive(generation) || device !== camera) {
                    configuredSession.close()
                    return
                }
                session = configuredSession
                listener.onSessionConfigured(camera, configuredSession, preview, generation)
            }

            override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                failedSession.close()
                if (isActive(generation)) listener.onSessionConfigurationFailed(generation)
            }
        }
        try {
            val outputConfigurations = outputs.map { surface ->
                OutputConfiguration(surface).apply {
                    if (physicalCameraId != null) setPhysicalCameraId(physicalCameraId)
                }
            }
            val executor = Executor(handler::post)
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigurations,
                    executor,
                    stateCallback,
                ),
            )
        } catch (error: Exception) {
            if (isActive(generation)) {
                listener.onSessionConfigurationFailed(generation, error)
            }
        }
    }

    private fun isActive(generation: Int): Boolean = activeGeneration == generation

    private companion object {
        private const val RAW_READER_MAX_IMAGES = 1
    }
}
