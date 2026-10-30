package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Small native drawing primitives shared by Clear and the record workspace. */
internal class PanelChrome {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun card(canvas: Canvas, rect: RectF, d: Float, dark: Boolean, selected: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = if (selected) InstrumentStyle.accentSurface(dark) else InstrumentStyle.control(dark)
        canvas.drawRoundRect(rect, 14*d, 14*d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .7f*d
        paint.color = if (selected) InstrumentStyle.accent(dark) else InstrumentStyle.hairline(dark)
        canvas.drawRoundRect(rect, 14*d, 14*d, paint)
    }
    fun toggle(canvas: Canvas, x: Float, y: Float, d: Float, dark: Boolean, checked: Boolean, enabled: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (checked) InstrumentStyle.accent(dark) else InstrumentStyle.border(dark)
        paint.alpha = if (enabled) 255 else 90
        canvas.drawRoundRect(x-17*d,y-10*d,x+17*d,y+10*d,10*d,10*d,paint)
        paint.color = if (dark && checked) InstrumentStyle.ink else Color.WHITE
        paint.alpha = if (enabled) 255 else 120
        canvas.drawCircle(x+(if(checked)7 else -7)*d,y,7*d,paint)
        paint.alpha = 255
    }
    fun chevron(canvas: Canvas, x: Float, y: Float, d: Float, color: Int, expanded: Boolean) {
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*d;paint.strokeCap=Paint.Cap.ROUND;paint.color=color
        val sign=if(expanded)-1 else 1
        canvas.drawLine(x-3*d,y-sign*1.5f*d,x,y+sign*1.5f*d,paint)
        canvas.drawLine(x,y+sign*1.5f*d,x+3*d,y-sign*1.5f*d,paint)
    }
    fun adjustments(canvas: Canvas, x: Float, y: Float, d: Float, color: Int) {
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f*d;paint.strokeCap=Paint.Cap.ROUND;paint.color=color
        for(i in -1..1) {
            val yy=y+i*5*d;val xx=x+(if(i==0)3 else -3)*d
            canvas.drawLine(x-7*d,yy,xx-2*d,yy,paint)
            canvas.drawLine(xx+2*d,yy,x+7*d,yy,paint)
            canvas.drawCircle(xx,yy,2*d,paint)
        }
    }
}
