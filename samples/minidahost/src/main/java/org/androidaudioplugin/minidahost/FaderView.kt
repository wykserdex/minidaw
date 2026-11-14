package org.androidaudioplugin.minidahost

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

class FaderView(context: Context) : View(context) {

    var fraction = 0.8f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var onFraction: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val pad = height * 0.08f
        val top = pad
        val bottom = height - pad
        paint.style = Paint.Style.FILL
        paint.color = Flm.panelAlt
        canvas.drawRect(cx - width * 0.06f, top, cx + width * 0.06f, bottom, paint)

        val y = bottom - (bottom - top) * fraction
        paint.color = Flm.accent
        canvas.drawRect(cx - width * 0.30f, y - height * 0.05f,
            cx + width * 0.30f, y + height * 0.05f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val pad = height * 0.08f
                val next = ((height - pad - event.y) / (height - 2 * pad)).coerceIn(0f, 1f)
                if (next != fraction) {
                    fraction = next
                    onFraction?.invoke(next)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
