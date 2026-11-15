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
    var accentColor = Flm.accent
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val pad = height * 0.08f
        val top = pad
        val bottom = height - pad
        paint.style = Paint.Style.FILL
        paint.color = Flm.panelAlt
        for (level in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val y = bottom - (bottom - top) * level
            canvas.drawRect(cx - width * 0.40f, y - 1f, cx + width * 0.40f, y + 1f, paint)
        }
        paint.color = Flm.bg
        canvas.drawRoundRect(cx - width * 0.05f, top, cx + width * 0.05f, bottom, 3f, 3f, paint)

        val y = bottom - (bottom - top) * fraction
        paint.color = accentColor
        canvas.drawRect(cx - width * 0.025f, y, cx + width * 0.025f, bottom, paint)
        val halfThumb = context.dp(7).toFloat()
        canvas.drawRoundRect(cx - width * 0.30f, y - halfThumb,
            cx + width * 0.30f, y + halfThumb, 3f, 3f, paint)
        paint.color = Flm.bg
        canvas.drawRect(cx - width * 0.22f, y - 1f, cx + width * 0.22f, y + 1f, paint)
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
