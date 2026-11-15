package org.androidaudioplugin.minidahost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class PeakMeterView(context: Context) : View(context) {

    var levels = floatArrayOf(0f, 0f)
        set(value) {
            field = value
            invalidate()
        }
    var label = ""
        set(value) {
            field = value
            invalidate()
        }

    var vertical = false
    var accentColor = Flm.success
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        paint.color = Flm.bg
        canvas.drawRect(0f, 0f, width, height, paint)
        if (vertical) {
            val gap = width * 0.16f
            val barWidth = (width - gap) / 2f
            for (channel in 0 until 2) {
                val level = levels.getOrElse(channel) { 0f }.coerceIn(0f, 1f)
                val left = channel * (barWidth + gap)
                val top = height * (1f - level)
                paint.color = accentColor
                canvas.drawRect(left, top, left + barWidth, height, paint)
                if (level > 0.9f) {
                    paint.color = Flm.danger
                    canvas.drawRect(left, top, left + barWidth, height * 0.1f, paint)
                }
            }
            return
        }

        val barHeight = height / 5f
        for (channel in 0 until 2) {
            val top = barHeight * (channel * 2 + 0.5f)
            paint.color = accentColor
            canvas.drawRect(0f, top, width * levels.getOrElse(channel) { 0f }.coerceIn(0f, 1f),
                top + barHeight, paint)
        }
        paint.color = Flm.text
        paint.textSize = height / 5f
        canvas.drawText(label, 12f, height - height / 12f, paint)
    }
}
