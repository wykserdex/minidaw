package org.androidaudioplugin.minidahost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class ClipPreviewView(context: Context) : View(context) {

    var clip: Clip? = null
        set(value) {
            field = value
            invalidate()
        }
    var playingStep = -1
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        paint.style = Paint.Style.FILL
        paint.color = Flm.panelAlt
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, 8f, 8f, paint)

        val notes = clip?.notes ?: return
        if (notes.isEmpty()) {
            paint.color = Flm.textDim
            paint.textSize = context.dp(11).toFloat()
            canvas.drawText("Empty clip · tap to edit", context.dp(8).toFloat(), height * 0.6f, paint)
        }
        val colWidth = width.toFloat() / STEPS_PER_CLIP
        val low = notes.minOfOrNull { it.pitch } ?: 48
        val high = notes.maxOfOrNull { it.pitch } ?: 60
        val span = (high - low + 1).coerceAtLeast(12)
        paint.color = Flm.text
        notes.forEach { note ->
            val left = note.step * colWidth + 1f
            val row = (high - note.pitch).toFloat() / span
            val top = height * 0.15f + row * height * 0.7f
            canvas.drawRect(left, top, left + note.lengthSteps * colWidth - 2f,
                top + (height * 0.7f / span).coerceAtLeast(2f), paint)
        }
        if (playingStep in 0 until STEPS_PER_CLIP) {
            paint.color = Flm.accent
            canvas.drawRect(playingStep * colWidth, 0f, playingStep * colWidth + 2f,
                height.toFloat(), paint)
        }
    }
}
