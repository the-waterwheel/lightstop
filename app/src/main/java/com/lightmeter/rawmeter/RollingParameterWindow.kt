package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/** A clipped three-value reel: adjacent values always differ by the configured EV interval. */
internal class RollingParameterWindow {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER;typeface=Typeface.DEFAULT_BOLD }
    private var target=Double.NaN
    private var from=Double.NaN
    private var startedAt=0L
    private var interval=0.0
    private val durationMs=180L

    fun draw(canvas: Canvas,rect: RectF,value: Double?,step: Double,density: Float,color: Int,
        format: (Double)->String,invalidate: ()->Unit) {
        if(value==null || !value.isFinite()) {
            target=Double.NaN
            paint.color=color;paint.textSize=21*density
            canvas.drawText("—",rect.centerX(),rect.centerY()-(paint.ascent()+paint.descent())/2,paint)
            return
        }
        val now=SystemClock.uptimeMillis()
        if(target!=value || interval!=step) {
            from=if(!target.isFinite() || interval!=step || !ValueAnimator.areAnimatorsEnabled()) value else position(now)
            target=value;interval=step;startedAt=now
        }
        val current=position(now)
        val pitch=rect.height()/3f
        val centerIndex=floor((current-target)/step).toInt()
        canvas.save();canvas.clipRect(rect)
        for(index in centerIndex-2..centerIndex+2) {
            val candidate=target+index*step
            val offset=((candidate-current)/step).toFloat()
            if(abs(offset)>1.7f) continue
            val prominence=1f-min(1f,abs(offset))
            val label=format(candidate)
            paint.textSize=(10f+11f*prominence)*density
            val measured=paint.measureText(label)
            if(measured>rect.width()-8*density) paint.textSize*=(rect.width()-8*density).coerceAtLeast(1f)/measured
            paint.color=color;paint.alpha=((.28f+.72f*prominence)*255).toInt()
            val y=rect.centerY()-offset*pitch
            canvas.drawText(label,rect.centerX(),y-(paint.ascent()+paint.descent())/2,paint)
        }
        canvas.restore();paint.alpha=255
        if(now-startedAt<durationMs && from!=target) invalidate()
    }

    private fun position(now: Long): Double {
        val progress=((now-startedAt).toDouble()/durationMs).coerceIn(0.0,1.0)
        val eased=progress*progress*(3-2*progress)
        return from+(target-from)*eased
    }
}
