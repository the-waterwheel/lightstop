package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.widget.FrameLayout

/** All pages share one unobscured viewport. Camera and hit-test coordinates stay in that viewport. */
internal class SafeAreaHost(context: Context) : FrameLayout(context) {
    private val excluded = Rect()
    private val location = IntArray(2)

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            // The immersive camera canvas extends behind the cutout. Reserving its entire
            // edge leaves a blank status-bar-sized strip even while system bars are hidden.
            val safe = insets.getInsets(WindowInsets.Type.systemBars())
            excluded.set(safe.left, safe.top, safe.right, safe.bottom)
        } else {
            excluded.set(insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                // Legacy systemWindowInsetBottom also includes the keyboard. The window's
                // resize policy handles IME; reserve only the stable navigation-bar portion.
                minOf(insets.systemWindowInsetBottom, insets.stableInsetBottom))
        }
        requestLayout()
        return insets
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets() }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationInWindow(location)
        // Decor may already exclude system bars/cutouts. Subtract that margin exactly once.
        val l = AdaptiveLayout.remainingInset(excluded.left, location[0]).coerceAtMost(width)
        val t = AdaptiveLayout.remainingInset(excluded.top, location[1]).coerceAtMost(height)
        val r = AdaptiveLayout.remainingInset(excluded.right, rootView.width - location[0] - width)
            .coerceAtMost(width - l)
        val b = AdaptiveLayout.remainingInset(excluded.bottom, rootView.height - location[1] - height)
            .coerceAtMost(height - t)
        if (l != paddingLeft || t != paddingTop || r != paddingRight || b != paddingBottom) {
            setPadding(l, t, r, b)
        }
    }
}
