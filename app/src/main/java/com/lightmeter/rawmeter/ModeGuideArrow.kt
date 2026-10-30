package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Three open chevrons, white with a black outline, shared by normal/zone/clear. */
internal class ModeGuideArrow {
    private val path=Path()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    fun draw(canvas:Canvas,x:Float,y:Float,density:Float,rotation:Float=0f,opacity:Float=1f) {
        canvas.save();canvas.rotate(rotation,x,y)
        path.reset()
        for(index in 0..2) {
            val cy=y+(index-1)*7f*density
            path.moveTo(x-11*density,cy+4*density)
            path.lineTo(x,cy-4*density)
            path.lineTo(x+11*density,cy+4*density)
        }
        paint.color=Color.BLACK;paint.alpha=(255*opacity).toInt().coerceIn(0,255);paint.strokeWidth=4f*density;canvas.drawPath(path,paint)
        paint.color=Color.WHITE;paint.alpha=(255*opacity).toInt().coerceIn(0,255);paint.strokeWidth=2f*density;canvas.drawPath(path,paint)
        canvas.restore()
    }
}
