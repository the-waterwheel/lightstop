package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.RectF

/** Transient presentation only: a shared viewfinder and a moving/fading control layer. */
internal data class ModePanelMotion(
    val preview:RectF,
    val progress:Float,
    val alpha:Float,
    val x:Float,
    val y:Float,
    val density:Float,
) {
    fun begin(canvas:Canvas,width:Int,height:Int):Int {
        val checkpoint=if(alpha>=1f)canvas.save() else canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(255*alpha).toInt().coerceIn(0,255))
        canvas.translate(x,y)
        return checkpoint
    }
}
