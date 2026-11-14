package org.androidaudioplugin.minidahost

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * One AAP parameter: an arc that shows its current value and a vertical drag that writes it back.
 * The value is normalized, so the plugin keeps owning min/max.
 */
class KnobView(context: Context) : View(context) {

    var label = ""
        set(value) {
            field = value
            invalidate()
        }
    var fraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var onFraction: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var dragStartY = 0f
    private var dragStartFraction = 0f

    override fun onDraw(canvas: Canvas) {
        val size = Math.min(width, height).toFloat()
        val cx = width / 2f
        val cy = height * 0.42f
        val radius = size * 0.28f
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.color = Flm.panelAlt
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius * 0.72f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.055f
        paint.color = Color.rgb(0x3C, 0x40, 0x4A)
        canvas.drawArc(arc, 135f, 270f, false, paint)
        paint.color = Flm.accent
        canvas.drawArc(arc, 135f, 270f * fraction, false, paint)

        // The arc leaves the paint in STROKE mode; text drawn with a fat stroke is illegible.
        paint.style = Paint.Style.FILL
        paint.color = Flm.text
        paint.textSize = size * 0.15f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("%.2f".format(fraction), cx, cy + size * 0.05f, paint)
        paint.color = Flm.textDim
        paint.textSize = size * 0.12f
        canvas.drawText(label, cx, height - size * 0.04f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.y
                dragStartFraction = fraction
            }
            MotionEvent.ACTION_MOVE -> {
                // A full drag over the view height covers the whole range, twice.
                val delta = (dragStartY - event.y) / height / 2f
                val next = ((dragStartFraction + delta) * 1000).roundToInt() / 1000f
                if (next != fraction) {
                    fraction = next
                    onFraction?.invoke(fraction)
                }
            }
        }
        return true
    }
}
