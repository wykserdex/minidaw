package org.androidaudioplugin.minidahost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        paint.color = Color.rgb(24, 24, 28)
        canvas.drawRect(0f, 0f, width, height, paint)

        val barHeight = height / 5f
        for (channel in 0 until 2) {
            val top = barHeight * (channel * 2 + 0.5f)
            paint.color = Color.rgb(61, 220, 132)
            canvas.drawRect(0f, top, width * levels.getOrElse(channel) { 0f }.coerceIn(0f, 1f),
                top + barHeight, paint)
        }
        paint.color = Color.WHITE
        paint.textSize = height / 5f
        canvas.drawText(label, 12f, height - height / 12f, paint)
    }
}
