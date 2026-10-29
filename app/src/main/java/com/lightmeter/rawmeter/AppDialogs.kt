package com.lightmeter.rawmeter

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import kotlin.math.min
import kotlin.math.roundToInt

/** One visual owner for application dialogs; never installs or replaces business listeners. */
internal object AppDialogs {
    fun themedContext(context:Context,dark:Boolean):Context = ContextThemeWrapper(context,
        if(dark)R.style.Theme_Lightstop_Dialog_Dark else R.style.Theme_Lightstop_Dialog_Light)
    fun builder(context:Context,state:MeterState):AlertDialog.Builder = Builder(context,state.isDarkMode)
    private class Builder(context:Context,private val dark:Boolean):AlertDialog.Builder(themedContext(context,dark)) {
        override fun setView(view:View):AlertDialog.Builder {
            val content=if(view is ScrollView)view else ScrollView(context).apply {
                val d=resources.displayMetrics.density
                val horizontal=if(view is CompoundButton || view.paddingLeft==0&&view.paddingRight==0)(24*d).toInt() else 0
                if(view is CompoundButton)view.setPadding(0,view.paddingTop,0,view.paddingBottom)
                setPadding(horizontal,(8*d).toInt(),horizontal,(12*d).toInt())
                isFillViewport=false;clipToPadding=false;addView(view)
            }
            return super.setView(content)
        }
        override fun create():AlertDialog {
            val dialog=super.create()
            val decor=dialog.window?.decorView?:return dialog
            decor.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view:View) {
                    view.removeOnAttachStateChangeListener(this)
                    decorate(dialog,dark)
                    view.post {if(dialog.isShowing)decorate(dialog,dark)}
                }
                override fun onViewDetachedFromWindow(view:View)=Unit
            })
            decor.addOnLayoutChangeListener(object:View.OnLayoutChangeListener {
                override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int) {
                    if(b<=t)return
                    v.removeOnLayoutChangeListener(this);decorate(dialog,dark)
                }
            })
            return dialog
        }
        override fun show():AlertDialog=super.show().also{decorate(it,dark)}
    }
    private fun accent(dark:Boolean)=if(dark)Color.rgb(221,119,130) else InstrumentStyle.red
    private fun shape(fill:Int,border:Int,d:Float,radius:Float)=GradientDrawable().apply {
        cornerRadius=radius*d;setColor(fill);setStroke(d.roundToInt().coerceAtLeast(1),border)
    }
    @Suppress("DEPRECATION") // Floating platform dialogs retain the IME resize contract back to API 28.
    private fun decorate(dialog:AlertDialog,dark:Boolean) {
        val window=dialog.window?:return;val d=dialog.context.resources.displayMetrics.density
        val foreground=InstrumentStyle.foreground(dark);val secondary=InstrumentStyle.secondary(dark)
        val surface=InstrumentStyle.control(dark);val border=InstrumentStyle.border(dark);val accent=accent(dark)
        window.setBackgroundDrawable(shape(surface,border,d,20f))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes=window.attributes.apply {dimAmount=if(dark).56f else .38f}
        val config=dialog.context.resources.configuration
        val width=min((config.screenWidthDp-40).coerceAtLeast(180),560)*d
        val maxHeight=(config.screenHeightDp-40).coerceAtLeast(180)*d
        val currentHeight=window.attributes.height
        window.setLayout(width.roundToInt(),if(window.decorView.height>maxHeight)maxHeight.roundToInt() else currentHeight)
        val titleId=dialog.context.resources.getIdentifier("alertTitle","id","android")
        var hasEditor=false
        fun style(view:View) {
            if(view is TextView) {
                val old=view.currentTextColor
                val semanticRed=Color.red(old)>100&&Color.red(old)>Color.green(old)*1.4&&Color.red(old)>Color.blue(old)*1.3
                view.setTextColor(if(semanticRed)accent else foreground)
                view.setHintTextColor(secondary);view.setLinkTextColor(if(dark)Color.rgb(124,181,239) else InstrumentStyle.blue)
                when {
                    view.id==titleId->{view.textSize=20f;view.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
                    view is EditText->{
                        hasEditor=true
                        if(view.textSize<TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,16f,view.resources.displayMetrics))view.textSize=16f
                        view.background=shape(InstrumentStyle.panel(dark),border,d,10f)
                        view.backgroundTintList=null;view.setPadding((12*d).toInt(),(10*d).toInt(),(12*d).toInt(),(10*d).toInt())
                        view.minHeight=(48*d).toInt();view.highlightColor=Color.argb(70,Color.red(accent),Color.green(accent),Color.blue(accent))
                        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {it.topMargin=maxOf(it.topMargin,(5*d).toInt());it.bottomMargin=maxOf(it.bottomMargin,(5*d).toInt());view.layoutParams=it}
                        if(Build.VERSION.SDK_INT>=29)view.textCursorDrawable=GradientDrawable().apply{setColor(accent);setSize((2*d).toInt().coerceAtLeast(1),(22*d).toInt())}
                    }
                    view is Button&&view !is CompoundButton->Unit
                    else->{if(view.textSize<TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,15f,view.resources.displayMetrics))view.textSize=15f;view.setLineSpacing(3*d,1f)}
                }
            }
            if(view is CompoundButton)view.buttonTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(accent,secondary))
            if(view is Spinner){view.backgroundTintList=ColorStateList.valueOf(secondary);view.setPopupBackgroundDrawable(shape(surface,border,d,12f))}
            if(view is ViewGroup)for(index in 0 until view.childCount)style(view.getChildAt(index))
        }
        style(window.decorView)
        if(hasEditor)window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.listView?.apply {
            divider=ColorDrawable(border);dividerHeight=(d*.5f).roundToInt().coerceAtLeast(1)
            fun row(child:View) {
                style(child);child.minimumHeight=(52*d).toInt()
                child.setPadding((24*d).toInt(),(10*d).toInt(),(24*d).toInt(),(10*d).toInt())
            }
            for(index in 0 until childCount)row(getChildAt(index))
            setOnHierarchyChangeListener(object:ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent:View?,child:View?) {child?.let(::row)}
                override fun onChildViewRemoved(parent:View?,child:View?)=Unit
            })
        }
        val panel=(dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.parent ?: dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.parent) as? ViewGroup
        panel?.apply {
            layoutParams=layoutParams.apply{height=ViewGroup.LayoutParams.WRAP_CONTENT}
            setPadding(paddingLeft,(8*d).toInt(),paddingRight,(12*d).toInt())
        }
        for(id in listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_POSITIVE)) {
            dialog.getButton(id)?.apply {
                val primary=id==AlertDialog.BUTTON_POSITIVE
                val fill=if(primary)accent else InstrumentStyle.panel(dark)
                background=RippleDrawable(ColorStateList.valueOf(if(primary)0x25ffffff else 0x18a61b24),shape(fill,if(primary)accent else border,d,10f),null)
                setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(secondary,if(primary) {if(dark)InstrumentStyle.ink else Color.WHITE} else foreground)))
                textSize=14f;isAllCaps=false;typeface=InstrumentStyle.labelTypeface
                minHeight=(44*d).toInt();minimumWidth=(64*d).toInt();maxLines=2
                setPadding((14*d).toInt(),(8*d).toInt(),(14*d).toInt(),(8*d).toInt())
                stateListAnimator=null;elevation=0f
                (layoutParams as? ViewGroup.MarginLayoutParams)?.let {it.marginStart=(6*d).toInt();it.marginEnd=(2*d).toInt();it.topMargin=0;it.bottomMargin=0;layoutParams=it}
            }
        }
    }
}
