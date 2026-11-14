package org.androidaudioplugin.minidahost

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class PianoRollView(context: Context) : View(context) {
    var clip: Clip? = null
        set(value) { field = value; invalidate() }
    var playingStep = -1
        set(value) { field = value; invalidate() }
    var lowPitch = 60
        set(value) { field = value; invalidate() }
    val highPitch get() = lowPitch + 12
    var onEdited: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteRect = RectF()
    private val blackKeys = setOf(1, 3, 6, 8, 10)
    private val labelWidth get() = context.dp(32).toFloat()
    private val colWidth get() = (width - labelWidth) / STEPS_PER_CLIP
    private val rowHeight get() = height / 12f
    private var touchedNote: ClipNote? = null
    private var existingNote = false
    private var dragged = false
    private var downX = 0f

    override fun onDraw(canvas: Canvas) {
        if (width <= labelWidth || height == 0) return
        paint.style = Paint.Style.FILL
        for (row in 0 until 12) {
            val pitch = highPitch - 1 - row
            paint.color = if (pitch % 12 in blackKeys) Flm.panel else Flm.panelAlt
            canvas.drawRect(0f, row * rowHeight, width.toFloat(), (row + 1) * rowHeight, paint)
            paint.color = Flm.textDim
            paint.textSize = minOf(rowHeight * 0.7f, context.dp(10).toFloat())
            val name = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[pitch % 12]
            canvas.drawText("$name${pitch / 12 - 1}", 3f, (row + 0.75f) * rowHeight, paint)
            paint.color = Flm.bg
            paint.strokeWidth = 1f
            canvas.drawLine(labelWidth, row * rowHeight, width.toFloat(), row * rowHeight, paint)
        }
        for (step in 0..STEPS_PER_CLIP) {
            paint.color = if (step % 4 == 0) Flm.textDim else Flm.bg
            paint.strokeWidth = if (step % 4 == 0) 2f else 1f
            val x = labelWidth + step * colWidth
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }
        paint.color = Flm.accent
        clip?.notes?.forEach { note ->
            val row = highPitch - 1 - note.pitch
            if (row in 0 until 12) {
                noteRect.set(labelWidth + note.step * colWidth + 2f, row * rowHeight + 2f,
                    labelWidth + (note.step + note.lengthSteps) * colWidth - 2f,
                    (row + 1) * rowHeight - 2f)
                canvas.drawRoundRect(noteRect, 3f, 3f, paint)
            }
        }
        if (playingStep in 0 until STEPS_PER_CLIP) {
            paint.color = Flm.text
            val x = labelWidth + playingStep * colWidth
            canvas.drawRect(x, 0f, x + 2f, height.toFloat(), paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = clip ?: return true
        if (width <= labelWidth || height == 0) return true
        val step = ((event.x - labelWidth) / colWidth).toInt().coerceIn(0, STEPS_PER_CLIP - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < labelWidth) return true
                parent.requestDisallowInterceptTouchEvent(true)
                val pitch = highPitch - 1 - (event.y / rowHeight).toInt().coerceIn(0, 11)
                touchedNote = current.noteAt(step, pitch)
                existingNote = touchedNote != null
                dragged = false
                downX = event.x
                if (!existingNote) touchedNote = current.record(step, pitch)
                invalidate()
                onEdited?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val note = touchedNote ?: return true
                if (kotlin.math.abs(event.x - downX) > context.dp(6)) dragged = true
                if (dragged) {
                    touchedNote = current.resize(note, step - note.step + 1)
                    invalidate()
                    onEdited?.invoke()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (existingNote && !dragged) current.notes.remove(touchedNote)
                touchedNote = null
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onEdited?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                touchedNote = null
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
