package org.androidaudioplugin.minidahost

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

class PianoRollView(context: Context) : View(context) {
    var clip: Clip? = null
        set(value) { field = value; invalidate() }
    var playingStep = -1
        set(value) { field = value; invalidate() }
    var lowPitch = 60
        set(value) { field = value; invalidate() }
    val highPitch get() = lowPitch + 12
    var drumMode = false
        set(value) { field = value; invalidate() }
    var showKeyNames = true
        set(value) { field = value; invalidate() }
    var ghostClips: List<Clip> = emptyList()
        set(value) { field = value; invalidate() }
    var accentColor = Flm.accent
        set(value) { field = value; invalidate() }
    var onEdited: (() -> Unit)? = null
    var onEditStart: (() -> Unit)? = null
    var onViewportChanged: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteRect = RectF()
    private val blackKeys = setOf(1, 3, 6, 8, 10)
    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val labelWidth get() = context.dp(if (drumMode) 56 else 32).toFloat()
    private val colWidth get() = (width - labelWidth) / STEPS_PER_CLIP
    private val rowCount get() = if (drumMode) DRUM_PADS.size else 12
    private val rowHeight get() = height / rowCount.toFloat()
    private var touchedNote: ClipNote? = null
    private var gesture = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downStep = 0
    private var downPitch = 0
    private var downLowPitch = 0
    private var originalNote: ClipNote? = null
    private var resizeEdge = false
    private var feedbackNote: ClipNote? = null
    private var grabAmount = 0f
    private var grabAnimator: ValueAnimator? = null

    private fun animateGrab(held: Boolean) {
        grabAnimator?.cancel()
        if (held) {
            feedbackNote = touchedNote
            grabAmount = 0.2f
        }
        grabAnimator = ValueAnimator.ofFloat(grabAmount, if (held) 1f else 0f).apply {
            duration = if (held) 110L else 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                grabAmount = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        grabAnimator?.cancel()
        grabAmount = 0f
        feedbackNote = null
        super.onDetachedFromWindow()
    }

    private fun rowPitch(row: Int) = if (drumMode) DRUM_PITCHES[row] else highPitch - 1 - row
    private fun pitchRow(pitch: Int) = if (drumMode) DRUM_PITCHES.indexOf(pitch) else highPitch - 1 - pitch

    override fun onDraw(canvas: Canvas) {
        if (width <= labelWidth || height == 0) return
        paint.style = Paint.Style.FILL
        for (row in 0 until rowCount) {
            val pitch = rowPitch(row)
            val black = !drumMode && pitch % 12 in blackKeys
            paint.color = if (black) Flm.panel else Flm.panelAlt
            canvas.drawRect(0f, row * rowHeight, width.toFloat(), (row + 1) * rowHeight, paint)
            paint.color = Flm.bg
            paint.strokeWidth = 1f
            canvas.drawLine(labelWidth, row * rowHeight, width.toFloat(), row * rowHeight, paint)
        }
        drawKeyboard(canvas)
        for (step in 0..STEPS_PER_CLIP) {
            paint.color = if (step % 4 == 0) Flm.textDim else Flm.bg
            paint.strokeWidth = if (step % 4 == 0) 2f else 1f
            val x = labelWidth + step * colWidth
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }
        paint.color = Flm.withAlpha(Flm.text, 60)
        ghostClips.forEach { ghost ->
            ghost.notes.forEach { note ->
                val row = pitchRow(note.pitch)
                if (row in 0 until rowCount) fillNote(canvas, note, row)
            }
        }
        paint.color = accentColor
        clip?.notes?.forEach { note ->
            val row = pitchRow(note.pitch)
            if (row in 0 until rowCount) fillNote(canvas, note, row)
        }
        feedbackNote?.let { note ->
            val row = pitchRow(note.pitch)
            if (grabAmount > 0f && row in 0 until rowCount && clip?.notes?.contains(note) == true) {
                canvas.save()
                canvas.clipRect(labelWidth, 0f, width.toFloat(), height.toFloat())
                val expand = context.dp(2) * grabAmount
                noteRect.set(labelWidth + note.step * colWidth + 2f - expand,
                    row * rowHeight + 2f - expand,
                    labelWidth + (note.step + note.lengthSteps) * colWidth - 2f + expand,
                    (row + 1) * rowHeight - 2f + expand)
                paint.color = accentColor
                canvas.drawRoundRect(noteRect, context.dp(3).toFloat(), context.dp(3).toFloat(), paint)
                paint.color = Color.argb((70 * grabAmount).toInt(), 255, 255, 255)
                canvas.drawRoundRect(noteRect, context.dp(3).toFloat(), context.dp(3).toFloat(), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dp(2) * grabAmount
                paint.color = Color.argb((255 * grabAmount).toInt(), 255, 255, 255)
                canvas.drawRoundRect(noteRect, context.dp(3).toFloat(), context.dp(3).toFloat(), paint)
                paint.style = Paint.Style.FILL
                if (resizeEdge) {
                    val handle = context.dp(3).toFloat()
                    canvas.drawRoundRect(noteRect.right - handle * 2, noteRect.top + handle,
                        noteRect.right - handle, noteRect.bottom - handle, handle, handle, paint)
                }
                canvas.restore()
            }
        }
        if (playingStep in 0 until STEPS_PER_CLIP) {
            paint.color = Flm.text
            val x = labelWidth + playingStep * colWidth
            canvas.drawRect(x, 0f, x + 2f, height.toFloat(), paint)
        }
    }

    private fun fillNote(canvas: Canvas, note: ClipNote, row: Int) {
        noteRect.set(labelWidth + note.step * colWidth + 2f, row * rowHeight + 2f,
            labelWidth + (note.step + note.lengthSteps) * colWidth - 2f,
            (row + 1) * rowHeight - 2f)
        canvas.drawRoundRect(noteRect, 3f, 3f, paint)
    }

    private fun drawKeyboard(canvas: Canvas) {
        if (drumMode) {
            paint.color = Flm.panel
            canvas.drawRect(0f, 0f, labelWidth, height.toFloat(), paint)
            paint.textSize = minOf(rowHeight * 0.42f, context.dp(10).toFloat())
            for (row in 0 until rowCount) {
                paint.color = accentColor
                canvas.drawRect(0f, row * rowHeight + 2f, context.dp(3).toFloat(), (row + 1) * rowHeight - 2f, paint)
                paint.color = Flm.text
                canvas.drawText(DRUM_PADS[row], context.dp(6).toFloat(), (row + 0.62f) * rowHeight, paint)
            }
            return
        }
        for (row in 0 until rowCount) {
            val pitch = rowPitch(row)
            val black = pitch % 12 in blackKeys
            val top = row * rowHeight
            paint.color = Color.rgb(0xE8, 0xEA, 0xEE)
            canvas.drawRect(0f, top, labelWidth, top + rowHeight, paint)
            if (black) {
                paint.color = Color.rgb(0x1B, 0x1D, 0x23)
                canvas.drawRect(0f, top, labelWidth * 0.62f, top + rowHeight, paint)
            }
            paint.color = Flm.bg
            paint.strokeWidth = 1f
            canvas.drawLine(0f, top, labelWidth, top, paint)
            if (!black) {
                paint.textSize = minOf(rowHeight * 0.5f, context.dp(9).toFloat())
                if (pitch % 12 == 0) {
                    paint.color = Flm.info
                    canvas.drawText("C${pitch / 12 - 1}", context.dp(3).toFloat(), (row + 0.72f) * rowHeight, paint)
                } else if (showKeyNames) {
                    paint.color = Color.rgb(0x55, 0x5A, 0x66)
                    canvas.drawText(noteNames[pitch % 12], context.dp(3).toFloat(), (row + 0.72f) * rowHeight, paint)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = clip ?: return true
        if (width <= labelWidth || height == 0) return true
        val step = ((event.x - labelWidth) / colWidth).toInt().coerceIn(0, STEPS_PER_CLIP - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                gesture = 0
                downX = event.x
                downY = event.y
                downStep = step
                downPitch = rowPitch((event.y / rowHeight).toInt().coerceIn(0, rowCount - 1))
                downLowPitch = lowPitch
                touchedNote = if (event.x >= labelWidth) current.noteAt(step, downPitch) else null
                originalNote = touchedNote
                resizeEdge = !drumMode && touchedNote?.let {
                    val end = labelWidth + (it.step + it.lengthSteps) * colWidth
                    end - event.x <= minOf(context.dp(12).toFloat(), colWidth * 0.25f)
                } == true
                feedbackNote = null
                if (touchedNote != null) animateGrab(true) else animateGrab(false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesture = 3
                animateGrab(false)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (gesture == 0 && maxOf(abs(dx), abs(dy)) > touchSlop) {
                    gesture = when {
                        originalNote != null -> if (resizeEdge) 2 else 4
                        !drumMode && abs(dy) >= abs(dx) -> 1
                        !drumMode && downX >= labelWidth -> 2
                        else -> 3
                    }
                    if (gesture == 4) onEditStart?.invoke()
                    if (gesture == 2) {
                        onEditStart?.invoke()
                        if (touchedNote == null) {
                            touchedNote = current.record(downStep, downPitch)
                            animateGrab(true)
                        }
                    }
                }
                if (gesture == 1) {
                    val next = (downLowPitch + (dy / rowHeight).roundToInt()).coerceIn(0, 116)
                    if (next != lowPitch) {
                        lowPitch = next
                        onViewportChanged?.invoke()
                    }
                } else if (gesture == 2) {
                    touchedNote?.let { touchedNote = current.resize(it, step - it.step + 1) }
                    feedbackNote = touchedNote
                    invalidate()
                    onEdited?.invoke()
                } else if (gesture == 4) {
                    val original = originalNote ?: return true
                    val nextStep = (original.step + (dx / colWidth).roundToInt())
                        .coerceIn(0, STEPS_PER_CLIP - original.lengthSteps)
                    val nextPitch = if (drumMode) {
                        DRUM_PITCHES[(DRUM_PITCHES.indexOf(original.pitch) + (dy / rowHeight).roundToInt())
                            .coerceIn(0, DRUM_PITCHES.lastIndex)]
                    } else (original.pitch - (dy / rowHeight).roundToInt()).coerceIn(0, 127)
                    val next = original.copy(step = nextStep, pitch = nextPitch)
                    val index = current.notes.indexOf(touchedNote)
                    if (index >= 0 && next != touchedNote) {
                        current.notes[index] = next
                        touchedNote = next
                        feedbackNote = next
                        invalidate()
                        onEdited?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == 0 && downX >= labelWidth &&
                    maxOf(abs(event.x - downX), abs(event.y - downY)) <= touchSlop) {
                    onEditStart?.invoke()
                    val hit = touchedNote
                    if (hit != null) current.notes.remove(hit) else current.record(downStep, downPitch)
                    invalidate()
                    onEdited?.invoke()
                }
                animateGrab(false)
                touchedNote = null
                gesture = 3
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                animateGrab(false)
                touchedNote = null
                gesture = 3
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    companion object {
        val DRUM_PADS = arrayOf("Kick", "Snare", "Clap", "HiHat", "OpenHat", "LowTom", "Crash", "Ride")
        val DRUM_PITCHES = intArrayOf(36, 38, 39, 42, 46, 45, 49, 51)
    }
}
