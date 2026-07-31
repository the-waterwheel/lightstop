package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.view.ViewGroup

class MeterLayout @JvmOverloads constructor(
    context: Context,
    val state: MeterState,
    attributeSet: AttributeSet? = null,
) : ViewGroup(context, attributeSet) {

    interface Listener {
        fun onMeasureRequested()
        fun onOrientationToggle()
        fun onPreviewGeometryChanged(width: Int, height: Int)
        fun onControlsChanged(frameChanged: Boolean)
    }

    val textureView = TextureView(context).apply {
        isOpaque = true
    }
    val instrumentView = InstrumentView(context, state)
    var listener: Listener? = null
        set(value) {
            field = value
            instrumentView.listener = object : InstrumentView.Listener {
                override fun onMeasureRequested() {
                    value?.onMeasureRequested()
                }

                override fun onOrientationToggle() {
                    value?.onOrientationToggle()
                }

                override fun onControlsChanged(frameChanged: Boolean) {
                    if (frameChanged) requestLayout()
                    value?.onControlsChanged(frameChanged)
                }
            }
        }

    init {
        setBackgroundColor(Color.WHITE)
        addView(textureView)
        addView(instrumentView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        instrumentView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        val geometry = LayoutGeometry.calculate(
            width,
            height,
            resources.displayMetrics.density,
            state.frameFormat,
            state.frameLandscape,
        )
        textureView.measure(
            MeasureSpec.makeMeasureSpec(geometry.cameraFrame.width().toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(geometry.cameraFrame.height().toInt(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val geometry = LayoutGeometry.calculate(
            width,
            height,
            resources.displayMetrics.density,
            state.frameFormat,
            state.frameLandscape,
        )
        textureView.layout(
            geometry.cameraFrame.left.toInt(),
            geometry.cameraFrame.top.toInt(),
            geometry.cameraFrame.right.toInt(),
            geometry.cameraFrame.bottom.toInt(),
        )
        instrumentView.layout(0, 0, width, height)
        // A format change can resize this child while the ViewGroup's own bounds stay the
        // same, so `changed` is not a reliable signal. Publish geometry after every layout.
        post {
            listener?.onPreviewGeometryChanged(textureView.width, textureView.height)
        }
    }

    fun setSurfaceTextureListener(listener: TextureView.SurfaceTextureListener) {
        textureView.surfaceTextureListener = listener
    }

    fun currentSurfaceTexture(): SurfaceTexture? = textureView.surfaceTexture

    fun refresh(frameChanged: Boolean = false) {
        if (frameChanged) requestLayout()
        instrumentView.invalidate()
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        LayoutParams(context, attrs)

    override fun checkLayoutParams(params: LayoutParams?): Boolean = params != null
}
