package org.androidaudioplugin.minidahost

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Two-octave piano strip. Keys are laid out geometrically and hit-tested black-first, so a
 * finger between two white keys selects the black one that is drawn on top.
 */
class KeyboardView(context: Context) : View(context) {

    var firstNote = 48
    var noteCount = 25
        set(value) {
            field = value
            layoutKeys()
            invalidate()
        }

    var onNote: ((note: Int, down: Boolean) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyRects = mutableListOf<Triple<Int, RectF, Boolean>>()
    private var pressedNote = -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun isBlack(note: Int) =
        note % 12 in setOf(1, 3, 6, 8, 10)

    private fun layoutKeys() {
        keyRects.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val whiteCount = (0 until noteCount).count { !isBlack(firstNote + it) }
        val whiteWidth = w / whiteCount
        var whiteIndex = 0
        for (offset in 0 until noteCount) {
            val note = firstNote + offset
            if (isBlack(note)) {
                val center = whiteIndex * whiteWidth
                val half = whiteWidth * 0.31f
                keyRects += Triple(note, RectF(center - half, 0f, center + half, h * 0.62f), true)
            } else {
                keyRects += Triple(note,
                    RectF(whiteIndex * whiteWidth, 0f, (whiteIndex + 1) * whiteWidth, h), false)
                whiteIndex++
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Flm.bg)
        // Black keys are drawn last because they overlap the white ones.
        for (blackPass in listOf(false, true)) {
            for ((note, rect, black) in keyRects) {
                if (black != blackPass) continue
                paint.color = when {
                    note == pressedNote -> Flm.accent
                    black -> Color.rgb(0x1B, 0x1D, 0x23)
                    else -> Color.rgb(0xE8, 0xEA, 0xEE)
                }
                canvas.drawRect(rect, paint)
                paint.color = Color.rgb(0x35, 0x38, 0x42)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawRect(rect, paint)
                paint.style = Paint.Style.FILL
            }
        }
        paint.color = Flm.textDim
        paint.textSize = height * 0.13f
        for ((note, rect, black) in keyRects)
            if (!black && note % 12 == 0)
                canvas.drawText("C${note / 12 - 1}", rect.left + 8f, rect.bottom - 10f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val note = keyAt(event.x, event.y)
                if (note != pressedNote) {
                    if (pressedNote >= 0) onNote?.invoke(pressedNote, false)
                    pressedNote = note
                    if (note >= 0) onNote?.invoke(note, true)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedNote >= 0) onNote?.invoke(pressedNote, false)
                pressedNote = -1
                invalidate()
            }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): Int {
        // Black keys sit on top of the white ones, so they win the hit test.
        keyRects.lastOrNull { (it.third) && it.second.contains(x, y) }?.let { return it.first }
        return keyRects.firstOrNull { !it.third && it.second.contains(x, y) }?.first ?: -1
    }
}
