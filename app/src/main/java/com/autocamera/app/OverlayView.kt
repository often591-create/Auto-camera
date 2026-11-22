package com.autocamera.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** 画网格线与对焦框。 */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var grid = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255); strokeWidth = 1f * d; style = Paint.Style.STROKE
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 182, 39); strokeWidth = 2f * d; style = Paint.Style.STROKE
    }
    private var fx = 0f
    private var fy = 0f
    private var focusUntil = 0L
    private var focusLocked = false

    fun showFocus(x: Float, y: Float) {
        fx = x; fy = y; focusLocked = false
        focusUntil = System.currentTimeMillis() + 2500
        invalidate()
        postDelayed({ invalidate() }, 2600)
    }

    fun focusResult(ok: Boolean) {
        focusLocked = ok
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (grid) {
            for (k in 1..2) {
                c.drawLine(w * k / 3, 0f, w * k / 3, h, gridPaint)
                c.drawLine(0f, h * k / 3, w, h * k / 3, gridPaint)
            }
        }
        if (System.currentTimeMillis() < focusUntil) {
            focusPaint.color = if (focusLocked) Color.rgb(95, 211, 141) else Color.rgb(255, 182, 39)
            c.drawCircle(fx, fy, 36f * d, focusPaint)
            c.drawCircle(fx, fy, 3f * d, focusPaint)
        }
    }
}
