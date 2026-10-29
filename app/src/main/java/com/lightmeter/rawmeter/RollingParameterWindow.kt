package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.min

/** Three-value reel. Keep the previous labels until they actually scroll out of the window. */
internal class RollingParameterWindow {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textAlign=Paint.Align.CENTER;typeface=Typeface.DEFAULT_BOLD}
    private var target=Double.NaN
    private var interval=0.0
    private data class Glyph(val label:String,val position:Float,val opacity:Float)
    private var fromGlyphs=emptyList<Glyph>()
    private var toLabels=emptyList<String>()
    private var startedAt=0L
    private var direction=1f
    private val durationMs=220L

    fun draw(canvas:Canvas,rect:RectF,value:Double?,step:Double,density:Float,color:Int,
        format:(Double)->String,invalidate:()->Unit) {
        if(value==null || !value.isFinite()) {
            target=Double.NaN;fromGlyphs=emptyList();toLabels=emptyList()
            paint.color=color;paint.alpha=255;paint.textSize=21*density
            canvas.drawText("—",rect.centerX(),rect.centerY()-(paint.ascent()+paint.descent())/2,paint)
            return
        }
        val now=SystemClock.uptimeMillis()
        if(value!=target || interval!=step) {
            val labels=listOf(format(value-step),format(value),format(value+step))
            if(labels!=toLabels || interval!=step) {
                val initialized=target.isFinite()
                // Capture the currently visible glyph positions, including a partially finished
                // transition. A rapid second detent therefore starts at exactly the last frame.
                fromGlyphs=if(!initialized || interval!=step || !ValueAnimator.areAnimatorsEnabled()) emptyList()
                    else visibleGlyphs(progress(now))
                direction=if(!initialized || value>=target) 1f else -1f
                toLabels=labels;startedAt=now
            }
            target=value;interval=step
        }
        val progress=if(fromGlyphs.isEmpty()) 1f else progress(now)
        val pitch=rect.height()/3f
        canvas.save();canvas.clipRect(rect)
        fun label(value:String,position:Float,opacity:Float) {
            if(opacity<=0f) return
            val prominence=1f-min(1f,abs(position))
            paint.textSize=(10+11*prominence)*density
            val measured=paint.measureText(value)
            if(measured>rect.width()-8*density) paint.textSize*=(rect.width()-8*density).coerceAtLeast(1f)/measured
            paint.color=color;paint.alpha=((.28f+.72f*prominence)*opacity*255).toInt()
            val y=rect.centerY()+position*pitch
            canvas.drawText(value,rect.centerX(),y-(paint.ascent()+paint.descent())/2,paint)
        }
        // Values increase down the reel: swiping up brings the lower neighbour into the centre.
        if(progress>=1f) toLabels.forEachIndexed {index,value->label(value,(index-1).toFloat(),1f)} else {
            fromGlyphs.forEach {label(it.label,it.position-direction*progress,it.opacity*(1-progress))}
            toLabels.forEachIndexed {index,value->label(value,index-1+direction*(1-progress),progress)}
            invalidate()
        }
        canvas.restore();paint.alpha=255
    }
    private fun visibleGlyphs(progress:Float):List<Glyph> =
        if(fromGlyphs.isEmpty() || progress>=1f) toLabels.mapIndexed {index,value->Glyph(value,(index-1).toFloat(),1f)}
        else (fromGlyphs.map {it.copy(position=it.position-direction*progress,opacity=it.opacity*(1-progress))}+
            toLabels.mapIndexed {index,value->Glyph(value,index-1+direction*(1-progress),progress)})
            // Continuous 5 Hz updates can retarget before 220 ms. Drop fully transparent
            // remnants so an always-on meter never accumulates a history of invisible labels.
            .filter {it.opacity>=1f/255}
    private fun progress(now:Long):Float {
        val p=((now-startedAt).toFloat()/durationMs).coerceIn(0f,1f)
        return p*p*(3-2*p)
    }
}
