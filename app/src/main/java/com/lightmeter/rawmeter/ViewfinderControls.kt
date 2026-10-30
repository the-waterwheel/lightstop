package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Shared Normal/Clear framing controls; window rotation remains a host callback. */
internal class ViewfinderControls(private val state:MeterState) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val scratch=RectF()
    private fun color(color:Int,alpha:Int) {paint.color=Color.argb(alpha,Color.red(color),Color.green(color),Color.blue(color))}
    private fun text(canvas:Canvas,value:String,rect:RectF,size:Float) {
        paint.style=Paint.Style.FILL;paint.typeface=InstrumentStyle.labelTypeface;paint.textSize=size
        if(paint.measureText(value)>rect.width()-4)paint.textSize*=(rect.width()-4).coerceAtLeast(1f)/paint.measureText(value)
        canvas.drawText(value,rect.centerX()-paint.measureText(value)/2,rect.centerY()-(paint.ascent()+paint.descent())/2,paint)
    }
    fun format(canvas:Canvas,rect:RectF,d:Float,expanded:Boolean,alpha:Int=255,label:String=InstrumentPresentation.formatShortLabel(state.frameFormat,state.menuLanguage)) {
        paint.style=Paint.Style.FILL;color(InstrumentStyle.control(state.isDarkMode),alpha)
        canvas.drawRoundRect(rect,10*d,10*d,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.7f*d
        color(if(expanded)InstrumentStyle.accent(state.isDarkMode) else InstrumentStyle.hairline(state.isDarkMode),alpha)
        canvas.drawRoundRect(rect,10*d,10*d,paint)
        color(if(expanded)InstrumentStyle.accent(state.isDarkMode) else InstrumentStyle.foreground(state.isDarkMode),alpha)
        text(canvas,label,rect,9*d)
    }
    fun orientation(canvas:Canvas,rect:RectF,d:Float,landscape:Boolean,alpha:Int=255) {
        paint.style=Paint.Style.FILL;color(InstrumentStyle.control(state.isDarkMode),alpha)
        canvas.drawRoundRect(rect,10*d,10*d,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.7f*d;color(InstrumentStyle.hairline(state.isDarkMode),alpha)
        canvas.drawRoundRect(rect,10*d,10*d,paint);color(InstrumentStyle.foreground(state.isDarkMode),alpha)
        paint.strokeWidth=1.3f*d
        val w=rect.width()*if(landscape).52f else .36f
        val h=rect.height()*if(landscape).36f else .52f
        scratch.set(rect.centerX()-w/2,rect.centerY()-h/2,rect.centerX()+w/2,rect.centerY()+h/2)
        canvas.drawRoundRect(scratch,2*d,2*d,paint)
        scratch.set(rect.left+5*d,rect.top+5*d,rect.right-5*d,rect.bottom-5*d)
        color(InstrumentStyle.accent(state.isDarkMode),alpha);canvas.drawArc(scratch,205f,86f,false,paint)
    }
    fun label(index:Int):String = if(index==FrameFormat.ALL.size) {
        if(state.menuLanguage==MenuLanguage.ENGLISH) {if(state.frameLandscape)"Portrait" else "Landscape"}
        else if(state.frameLandscape)"竖拍" else "横拍"
    } else InstrumentPresentation.formatShortLabel(FrameFormat.ALL[index],state.menuLanguage)
    fun select(index:Int) {
        if(index==FrameFormat.ALL.size)state.toggleFrameOrientation() else state.selectFrame(index)
    }
    fun options(canvas:Canvas,rects:List<RectF>,d:Float) {
        for((index,rect) in rects.withIndex()) {
            val selected=if(index==FrameFormat.ALL.size)!state.frameLandscape else index==state.frameIndex
            val ink=InstrumentStyle.foreground(state.isDarkMode);val surface=InstrumentStyle.background(state.isDarkMode)
            paint.style=Paint.Style.FILL;paint.color=if(selected)ink else surface
            canvas.drawRoundRect(rect,8*d,8*d,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f*d;paint.color=if(selected)InstrumentStyle.accent(state.isDarkMode) else InstrumentStyle.hairline(state.isDarkMode)
            canvas.drawRoundRect(rect,8*d,8*d,paint)
            paint.color=if(selected)surface else ink;text(canvas,label(index),rect,7.5f*d)
        }
    }
}
