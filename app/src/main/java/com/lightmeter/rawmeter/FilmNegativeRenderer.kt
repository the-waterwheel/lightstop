package com.lightmeter.rawmeter

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Camera2 produces to inputTexture, ES 2.0 renders to the existing TextureView's outputTexture.
 * Input keeps the camera size; output follows the actual EGL window. Display crop/rotation is
 * applied here once, while TextureView uses identity for this processed producer.
 * All EGL/GL access and input-texture destruction are confined to the render thread.
 */
internal class FilmNegativeRenderer(
    val outputTexture: SurfaceTexture,
    initialSettings: FilmNegativeSettings,
    private val onReady: (SurfaceTexture) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val thread = HandlerThread("film-negative-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)
    @Volatile var inputTexture: SurfaceTexture? = null
        private set
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputSurface: Surface? = null
    private var cameraTexture = 0
    private var lookupTexture = 0
    private var sampleTexture = 0
    private var sampleFramebuffer = 0
    private var program = 0
    private var settings = initialSettings
    private var width = 1
    private var height = 1
    @Volatile private var displayDegrees = 0
    @Volatile private var mirrored = false
    @Volatile private var zoom = 1f
    private val orientationSerial = AtomicLong(0)
    val orientationToken: Long get() = orientationSerial.get()
    private val outputDimensions = IntArray(2)
    private var hasFrame = false
    private var firstFrameReported = false
    private var drawQueued = false
    private var failureReported = false
    private val pendingSettings = AtomicReference<FilmNegativeSettings?>(null)
    private val settingsQueued = AtomicBoolean(false)
    private val locations = HashMap<String, Int>()
    private val matcher = FilmNegativeSampleMatcher()
    private var sampleCompletion: ((FilmNegativeBaseSample?) -> Unit)? = null
    private var sampleStartTimestamp = 0L
    private var lastSampleTimestamp = 0L
    private var automaticBase = false
    private var sampleMonochrome = false
    private var sampleOrientation = 0 to false
    @Volatile private var sampling = false
    private val sampleTimeout = Runnable { finishSample(null) }
    private val frameEvidence = LinkedHashMap<Long, FilmNegativeFrameEvidence>()
    private var frameCompletion: ((FilmNegativeFrozenFrame?) -> Unit)? = null
    private var frozenPixels: IntArray? = null
    private var frozenTimestamp = 0L
    private var frozenWidth = 0
    private var frozenHeight = 0
    private var frameStartTimestamp = 0L
    private var frameMaxEdge = 1280
    private var frameOrientation = 0 to false
    private var frameOrientationToken = 0L
    private val smallFrameBytes = ByteBuffer.allocateDirect(FilmNegativeSelectionAnalysis.ANALYSIS_EDGE * FilmNegativeSelectionAnalysis.ANALYSIS_EDGE * 4)
    private val frameTimeout = Runnable { finishFrame(null) }
    private val textureMatrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

    fun start() {
        handler.post {
            if (released.get()) return@post
            guarded {
                initialize()
                if (!released.get()) onReady(checkNotNull(inputTexture))
            }
        }
    }

    fun resize(width: Int, height: Int) {
        handler.post {
            if (released.get()) return@post
            this.width = width
            this.height = height
            guarded {
                inputTexture?.setDefaultBufferSize(width, height)
            }
        }
    }

    fun orient(displayDegrees: Int, mirrored: Boolean, zoom: Float = 1f) {
        if (this.displayDegrees != displayDegrees || this.mirrored != mirrored) orientationSerial.incrementAndGet()
        this.displayDegrees = displayDegrees
        this.mirrored = mirrored
        this.zoom = zoom
    }

    fun update(settings: FilmNegativeSettings) {
        pendingSettings.set(settings)
        queueSettings()
    }

    private fun queueSettings() {
        if (!settingsQueued.compareAndSet(false, true)) return
        handler.post {
            settingsQueued.set(false)
            val next = pendingSettings.getAndSet(null) ?: return@post
            if (released.get() || program == 0) return@post
            guarded {
                val oldCurve = curveSettings(this.settings)
                this.settings = next
                if (oldCurve != curveSettings(next)) uploadLookup()
                if (hasFrame) render()
            }
        }
    }

    private fun curveSettings(value: FilmNegativeSettings) = value.copy(gamma = 1f, exposureEv = 0f,
        saturation = 1f, monochrome = false, warmth = 0f, tint = 0f, contrast = 1f, inverted = false, showGuide = false)

    fun observe(evidence: FilmNegativeFrameEvidence) {
        if (released.get()) return
        handler.post {
            if (released.get()) return@post
            frameEvidence[evidence.timestampNs] = evidence
            while (frameEvidence.size > 12) frameEvidence.remove(frameEvidence.keys.first())
            matchFrozenFrame()
            if (!released.get() && sampleCompletion != null && evidence.timestampNs > sampleStartTimestamp) {
                matcher.evidence(evidence)?.let(::finishSample)
            }
        }
    }

    /** Original camera colors, without inversion or guides; metadata uses the same timestamp. */
    fun captureFrame(maxEdge: Int = 1280, completion: (FilmNegativeFrozenFrame?) -> Unit) {
        handler.post {
            finishFrame(null)
            if (released.get() || !hasFrame || failureReported) {
                completion(null)
                return@post
            }
            frameStartTimestamp = inputTexture?.timestamp ?: 0L
            frameMaxEdge = maxEdge.coerceIn(18, 1280)
            frameOrientation = displayDegrees to mirrored
            frameOrientationToken = orientationToken
            frameCompletion = completion
            handler.postDelayed(frameTimeout, 3000L)
        }
    }

    private fun finishFrame(frame: FilmNegativeFrozenFrame?) {
        val completion = frameCompletion
        if (completion != null) Log.i("FilmFrameDiagnostic", "accepted=${frame != null} pixels=${frozenPixels != null} " +
            "textureTimestamp=$frozenTimestamp metadataUsable=${frameEvidence.values.lastOrNull()?.usable}")
        frameCompletion = null
        frozenPixels = null
        handler.removeCallbacks(frameTimeout)
        completion?.invoke(frame)
    }

    private fun matchFrozenFrame() {
        val pixels = frozenPixels ?: return
        if (frameOrientationToken != orientationToken || frameOrientation != (displayDegrees to mirrored)) { finishFrame(null); return }
        val evidence = frameEvidence[frozenTimestamp] ?: return
        if (evidence.usable) finishFrame(FilmNegativeFrozenFrame(frozenWidth, frozenHeight, pixels, evidence, frameOrientationToken))
        else frozenPixels = null // Wait for a later locked frame, never accept an unlocked image.
    }

    private fun captureFrozenFrame() {
        val timestamp = inputTexture?.timestamp ?: return
        if (frameCompletion == null || frozenPixels != null || timestamp <= frameStartTimestamp) return
        if (frameOrientationToken != orientationToken || frameOrientation != (displayDegrees to mirrored)) { finishFrame(null); return }
        val swapped = kotlin.math.abs(textureMatrix[1]) > kotlin.math.abs(textureMatrix[0])
        val rotated = displayDegrees % 180 != 0
        val sourceWidth = if (swapped xor rotated) height else width
        val sourceHeight = if (swapped xor rotated) width else height
        val scale = minOf(1f, frameMaxEdge.toFloat() / maxOf(sourceWidth, sourceHeight))
        frozenWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        frozenHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val texture = IntArray(1)
        val framebuffer = IntArray(1)
        val small = frameMaxEdge <= FilmNegativeSelectionAnalysis.ANALYSIS_EDGE
        try {
            if (small) GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleFramebuffer)
            else {
                GLES20.glGenTextures(1, texture, 0)
                configureTexture(GLES20.GL_TEXTURE_2D, texture[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frozenWidth, frozenHeight,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glGenFramebuffers(1, framebuffer, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, texture[0], 0)
            }
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            GLES20.glViewport(0, 0, frozenWidth, frozenHeight)
            draw(2, frozenWidth, frozenHeight)
            val bytes = if (small) smallFrameBytes else ByteBuffer.allocateDirect(frozenWidth * frozenHeight * 4)
            bytes.clear()
            GLES20.glReadPixels(0, 0, frozenWidth, frozenHeight, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, bytes)
            checkGl()
            frozenPixels = IntArray(frozenWidth * frozenHeight) { index ->
                val offset = ((frozenHeight - 1 - index / frozenWidth) * frozenWidth + index % frozenWidth) * 4
                val red = bytes.get(offset).toInt() and 255
                val green = bytes.get(offset + 1).toInt() and 255
                val blue = bytes.get(offset + 2).toInt() and 255
                (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
            frozenTimestamp = timestamp
            Log.i("FilmFrameDiagnostic", "captured=${frozenWidth}x$frozenHeight textureTimestamp=$timestamp " +
                "metadataTimestamp=${frameEvidence.keys.lastOrNull()}")
            matchFrozenFrame()
        } catch (error: Exception) {
            Log.w(TAG, "Frozen film frame failed", error)
            finishFrame(null)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (!small) {
                GLES20.glDeleteFramebuffers(1, framebuffer, 0)
                GLES20.glDeleteTextures(1, texture, 0)
            }
        }
    }

    /** Read only new frames after the user request; wait for matching locked CaptureResults. */
    fun sampleBase(automatic: Boolean = false, completion: (FilmNegativeBaseSample?) -> Unit) {
        handler.post {
            finishSample(null)
            if (released.get() || !hasFrame || failureReported) {
                completion(null)
                return@post
            }
            matcher.clear()
            sampleStartTimestamp = inputTexture?.timestamp ?: 0L
            lastSampleTimestamp = sampleStartTimestamp
            automaticBase = automatic
            sampleMonochrome = settings.monochrome
            sampleOrientation = displayDegrees to mirrored
            sampleCompletion = completion
            sampling = true
            handler.postDelayed(sampleTimeout, 3000L)
        }
    }

    private fun finishSample(result: FilmNegativeBaseSample?) {
        val completion = sampleCompletion
        sampling = false
        sampleCompletion = null
        handler.removeCallbacks(sampleTimeout)
        matcher.clear()
        completion?.invoke(result)
    }

    private fun sampleFrame() {
        val timestamp = inputTexture?.timestamp ?: return
        if (sampleCompletion == null || timestamp - lastSampleTimestamp < 80_000_000L) return
        if (automaticBase && sampleOrientation != (displayDegrees to mirrored)) {
            finishSample(null)
            return
        }
        lastSampleTimestamp = timestamp
        try {
            val swapped = kotlin.math.abs(textureMatrix[1]) > kotlin.math.abs(textureMatrix[0])
            val rotated = displayDegrees % 180 != 0
            val sourceWidth = if (swapped xor rotated) height else width
            val sourceHeight = if (swapped xor rotated) width else height
            val scale = minOf(1f, FilmNegativeBaseDetector.ANALYSIS_EDGE.toFloat() / maxOf(sourceWidth, sourceHeight))
            val sampleWidth = if (automaticBase) (sourceWidth * scale).toInt().coerceAtLeast(1) else SAMPLE_EDGE
            val sampleHeight = if (automaticBase) (sourceHeight * scale).toInt().coerceAtLeast(1) else SAMPLE_EDGE
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleFramebuffer)
            GLES20.glViewport(0, 0, sampleWidth, sampleHeight)
            // Mode 2 returns the full original, without inversion, zoom or the guide overlay.
            draw(if (automaticBase) 2 else 1, sampleWidth, sampleHeight)
            val bytes = ByteBuffer.allocateDirect(sampleWidth * sampleHeight * 4)
            GLES20.glReadPixels(0, 0, sampleWidth, sampleHeight, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, bytes)
            val sample = ByteArray(bytes.capacity())
            bytes.position(0)
            bytes.get(sample)
            checkGl()
            val rgb = if (automaticBase) {
                val pixels = IntArray(sampleWidth * sampleHeight) { index ->
                    val offset = index * 4
                    (255 shl 24) or ((sample[offset].toInt() and 255) shl 16) or
                        ((sample[offset + 1].toInt() and 255) shl 8) or (sample[offset + 2].toInt() and 255)
                }
                FilmNegativeBaseDetector.find(sampleWidth, sampleHeight, pixels, sampleMonochrome)?.rgb
            } else FilmNegativeMath.sampleBase(sample)
            matcher.frame(timestamp, rgb)?.let(::finishSample)
        } catch (error: Exception) {
            Log.w(TAG, "Film-base sample failed", error)
            finishSample(null)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    /** The owner must close the Camera2 producer before calling this. Never releases outputTexture. */
    fun release(completion: () -> Unit = {}) {
        if (!released.compareAndSet(false, true)) return
        handler.post {
            finishSample(null)
            finishFrame(null)
            try {
                runCatching {
                    inputTexture?.setOnFrameAvailableListener(null)
                    inputTexture?.release()
                }.onFailure { Log.w(TAG, "Film input texture release failed", it) }
                inputTexture = null
                if (display != EGL14.EGL_NO_DISPLAY) {
                    if (program != 0) GLES20.glDeleteProgram(program)
                    GLES20.glDeleteTextures(3, intArrayOf(cameraTexture, lookupTexture, sampleTexture), 0)
                    GLES20.glDeleteFramebuffers(1, intArrayOf(sampleFramebuffer), 0)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                    EGL14.eglReleaseThread()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Film renderer cleanup failed", error)
            } finally {
                runCatching { outputSurface?.release() }
                    .onFailure { Log.w(TAG, "Film output surface release failed", it) }
                outputSurface = null
                thread.quitSafely()
                completion()
            }
        }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        outputSurface = Surface(outputTexture)
        window = EGL14.eglCreateWindowSurface(display, config, outputSurface,
            intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, window, window, context))
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        val log = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(linked[0] != 0) { log }
        for (name in listOf("uTextureMatrix", "uDisplayMatrix", "uSample", "uGuide", "uInverted", "uSaturation", "uMonochrome", "uCamera", "uLookup", "uGamma", "uExposure", "uColorShift", "uContrast")) {
            locations[name] = GLES20.glGetUniformLocation(program, name)
        }
        locations["aPosition"] = GLES20.glGetAttribLocation(program, "aPosition")
        locations["aUv"] = GLES20.glGetAttribLocation(program, "aUv")
        val textures = IntArray(3)
        GLES20.glGenTextures(3, textures, 0)
        cameraTexture = textures[0]
        lookupTexture = textures[1]
        sampleTexture = textures[2]
        configureTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        configureTexture(GLES20.GL_TEXTURE_2D, lookupTexture)
        uploadLookup()
        configureTexture(GLES20.GL_TEXTURE_2D, sampleTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            FilmNegativeSelectionAnalysis.ANALYSIS_EDGE, FilmNegativeSelectionAnalysis.ANALYSIS_EDGE,
            0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        sampleFramebuffer = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleFramebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, sampleTexture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        checkGl()
        inputTexture = SurfaceTexture(cameraTexture).apply {
            setOnFrameAvailableListener({
                if (!released.get() && !drawQueued) {
                    drawQueued = true
                    handler.post {
                        drawQueued = false
                        if (released.get()) return@post
                        guarded {
                            updateTexImage()
                            getTransformMatrix(textureMatrix)
                            hasFrame = true
                            sampleFrame()
                            captureFrozenFrame()
                            render()
                        }
                    }
                }
            }, handler)
        }
        Log.i(TAG, "Film negative ES 2.0 renderer initialized")
    }

    private fun render() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // EGL can change size after a TextureView traversal or foreground transition.
        // Camera input dimensions cannot be used as this window's viewport dimensions.
        check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, outputDimensions, 0))
        check(EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, outputDimensions, 1))
        GLES20.glViewport(0, 0, outputDimensions[0], outputDimensions[1])
        draw(0)
        checkGl()
        check(EGL14.eglSwapBuffers(display, window)) { "Preview swap failed: ${EGL14.eglGetError()}" }
        if (!firstFrameReported) {
            firstFrameReported = true
            onFirstFrame()
        }
    }

    private fun draw(sample: Int, targetWidth: Int = outputDimensions[0], targetHeight: Int = outputDimensions[1]) {
        GLES20.glUseProgram(program)
        val position = locations.getValue("aPosition")
        val uv = locations.getValue("aUv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniformMatrix4fv(locations.getValue("uTextureMatrix"), 1, false, textureMatrix, 0)
        val swapped = kotlin.math.abs(textureMatrix[1]) > kotlin.math.abs(textureMatrix[0])
        val displayMatrix = FilmNegativeDisplayGeometry.matrix(
            if (swapped) height else width, if (swapped) width else height,
            targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1),
            -displayDegrees, mirrored, if (sample == 2) 1f else zoom)
        GLES20.glUniformMatrix3fv(locations.getValue("uDisplayMatrix"), 1, false, displayMatrix, 0)
        GLES20.glUniform1i(locations.getValue("uSample"), sample)
        GLES20.glUniform1i(locations.getValue("uInverted"), if (settings.inverted) 1 else 0)
        GLES20.glUniform1i(locations.getValue("uGuide"), if (settings.showGuide) 1 else 0)
        GLES20.glUniform1f(locations.getValue("uSaturation"), settings.saturation.coerceIn(0f, 2f))
        GLES20.glUniform1f(locations.getValue("uGamma"), settings.gamma.coerceIn(0.3f, 3f))
        GLES20.glUniform1f(locations.getValue("uExposure"), settings.exposureEv.coerceIn(-3f, 3f))
        GLES20.glUniform1f(locations.getValue("uContrast"), settings.contrast.coerceIn(.5f, 2f))
        GLES20.glUniform3f(locations.getValue("uColorShift"),
            0.5f * settings.warmth + 0.25f * settings.tint, -0.5f * settings.tint,
            -0.5f * settings.warmth + 0.25f * settings.tint)
        GLES20.glUniform1i(locations.getValue("uMonochrome"), if (settings.monochrome) 1 else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(locations.getValue("uCamera"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lookupTexture)
        GLES20.glUniform1i(locations.getValue("uLookup"), 1)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uploadLookup() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lookupTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        val bytes = ByteBuffer.allocateDirect(256 * 3).apply { put(FilmNegativeMath.lookup(settings)); position(0) }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, 256, 1, 0,
            GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, bytes)
    }

    private fun configureTexture(target: Int, texture: Int) {
        GLES20.glBindTexture(target, texture)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error(log)
        }
        return shader
    }

    private fun checkGl() {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error: $error" }
    }

    private inline fun guarded(action: () -> Unit) {
        if (failureReported) return
        try { action() } catch (error: Exception) {
            failureReported = true
            Log.e(TAG, "Film negative preview failed", error)
            onFailure(error)
        }
    }

    companion object {
        private const val TAG = "lightstop"
        private const val SAMPLE_EDGE = 16
        private const val VERTEX_SHADER = """
            precision mediump int;
            attribute vec2 aPosition;
            attribute vec2 aUv;
            uniform mat4 uTextureMatrix;
            uniform mat3 uDisplayMatrix;
            uniform int uSample;
            varying mediump vec2 vUv;
            varying mediump vec2 vGuideUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vec2 uv = uSample == 1 ? vec2(0.485) + aUv * 0.03 : (uDisplayMatrix * vec3(aUv, 1.0)).xy;
                vGuideUv = uv;
                vUv = (uTextureMatrix * vec4(uv, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            precision mediump int;
            uniform samplerExternalOES uCamera;
            uniform sampler2D uLookup;
            uniform int uSample;
            uniform int uMonochrome;
            uniform int uInverted;
            uniform int uGuide;
            uniform float uSaturation;
            uniform float uGamma;
            uniform float uExposure;
            uniform float uContrast;
            uniform vec3 uColorShift;
            varying mediump vec2 vUv;
            varying mediump vec2 vGuideUv;
            vec3 encode(vec3 x) {
                return mix(12.92 * x, 1.055 * pow(max(x, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
                    step(vec3(0.0031308), x));
            }
            void main() {
                vec3 raw = texture2D(uCamera, vUv).rgb;
                if (uSample > 0) { gl_FragColor = vec4(raw, 1.0); return; }
                if (uInverted == 0) {
                    float border = max(abs(vGuideUv.x - 0.5), abs(vGuideUv.y - 0.5));
                    if (uGuide == 1 && border >= 0.015 && border <= 0.017) raw = vec3(1.0);
                    gl_FragColor = vec4(raw, 1.0);
                    return;
                }
                vec3 coord = (raw * 255.0 + 0.5) / 256.0;
                vec3 color = vec3(texture2D(uLookup, vec2(coord.r, 0.5)).r,
                    texture2D(uLookup, vec2(coord.g, 0.5)).g,
                    texture2D(uLookup, vec2(coord.b, 0.5)).b);
                vec3 logLight = color * color * color * 2.83321334;
                // exp(x)-1 loses small values in mediump; use its stable local expansion.
                color = mix(logLight * (1.0 + 0.5 * logLight), exp(logLight) - 1.0,
                    step(vec3(0.01), logLight));
                color = min(pow(max(color, vec3(0.0)), vec3(1.0 / uGamma)), vec3(1024.0)) * exp2(vec3(uExposure) + uColorShift);
                float gray = dot(color, vec3(0.2126, 0.7152, 0.0722));
                color = uMonochrome == 1 ? vec3(gray) : mix(vec3(gray), color, uSaturation);
                if (abs(uContrast - 1.0) > 0.0001) {
                    float level = min(0.18 * pow(max(gray, 0.0) / 0.18, uContrast), 1024.0);
                    color *= level / max(gray, 0.0001);
                    gray = level;
                }
                float mapped = gray <= 0.75 ? gray : 0.75 + 0.25 * (1.0 - exp(-(gray - 0.75) / 0.25));
                color *= mapped / max(gray, 0.0001);
                float hi = max(color.r, max(color.g, color.b));
                float lo = min(color.r, min(color.g, color.b));
                float chroma = 1.0;
                if (hi > 1.0) chroma = min(chroma, (1.0 - mapped) / max(hi - mapped, 0.0001));
                if (lo < 0.0) chroma = min(chroma, mapped / max(mapped - lo, 0.0001));
                color = encode(clamp(vec3(mapped) + (color - mapped) * chroma, 0.0, 1.0));
                // Draw the guide in the very same coordinates used for readback. It follows
                // all crop, rotation and mirror transforms without an independent UI rectangle.
                vec2 edge = abs(vGuideUv - vec2(0.5));
                float border = max(edge.x, edge.y);
                if (uGuide == 1 && border >= 0.015 && border <= 0.017) color = vec3(1.0);
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
