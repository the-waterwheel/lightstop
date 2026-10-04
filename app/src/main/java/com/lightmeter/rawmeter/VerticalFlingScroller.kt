package com.lightmeter.rawmeter

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller

/** Small reusable inertial-scrolling helper for canvas-based vertical lists and documents. */
internal class VerticalFlingScroller(context: Context) {
    private val scroller = OverScroller(context)
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null
    private var lastY = 0f

    fun begin(event: MotionEvent) {
        scroller.forceFinished(true)
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        lastY = event.y
    }

    fun drag(event: MotionEvent, currentOffset: Float, maxOffset: Float): Float {
        velocityTracker?.addMovement(event)
        val next = (currentOffset - (event.y - lastY)).coerceIn(0f, maxOffset)
        lastY = event.y
        return next
    }

    fun finish(event: MotionEvent, currentOffset: Float, maxOffset: Float): Boolean {
        val tracker = velocityTracker ?: return false
        tracker.addMovement(event)
        tracker.computeCurrentVelocity(1000, maximumVelocity.toFloat())
        val velocity = -tracker.yVelocity.toInt()
        tracker.recycle()
        velocityTracker = null
        if (kotlin.math.abs(velocity) < minimumVelocity || maxOffset <= 0f) return false
        scroller.fling(
            0,
            currentOffset.toInt(),
            0,
            velocity,
            0,
            0,
            0,
            maxOffset.toInt(),
        )
        return true
    }

    fun cancel() {
        velocityTracker?.recycle()
        velocityTracker = null
        scroller.forceFinished(true)
    }

    fun compute(maxOffset: Float): Float? {
        if (!scroller.computeScrollOffset()) return null
        return scroller.currY.toFloat().coerceIn(0f, maxOffset)
    }
}
