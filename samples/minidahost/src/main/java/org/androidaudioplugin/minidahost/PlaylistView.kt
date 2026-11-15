package org.androidaudioplugin.minidahost

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

class PlaylistView(context: Context) : View(context) {
    var project: DawProject? = null
        set(value) { field = value; invalidate() }
    var selectedClip: String? = null
        set(value) { field = value; invalidate() }
    var playhead = -1f
        set(value) { field = value; invalidate() }
    var audioSteps: Map<String, Float> = emptyMap()
    var canEdit: () -> Boolean = { true }
    var onInsert: ((Int, Int) -> Unit)? = null
    var onSelect: ((ArrangementClip, Boolean) -> Unit)? = null
    var onMove: ((String, Int, Int) -> Unit)? = null
    var onMenu: ((ArrangementClip) -> Unit)? = null
    var onTrackMenu: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val labelWidth = context.dp(80).toFloat()
    private val rulerHeight = context.dp(24).toFloat()
    private val stepWidth = context.dp(12).toFloat()
    private val laneHeight = context.dp(44).toFloat()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var offsetX = 0f
    private var offsetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var mode = 0
    private var held: ArrangementClip? = null
    private var preview: ArrangementClip? = null
    private var lastTapId: String? = null
    private var lastTapTime = 0L
    private var glow = 0f
    private var animator: ValueAnimator? = null
    private val longPress = Runnable {
        if (mode == 0 && held != null) {
            mode = 3
            feedback(false)
            held?.let { onMenu?.invoke(it) }
        }
    }
    private var trackLane = -1
    private val trackLongPress = Runnable {
        if (mode == 0 && trackLane >= 0) onTrackMenu?.invoke(trackLane)
    }

    private fun feedback(active: Boolean) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(glow, if (active) 1f else 0f).apply {
            duration = 120
            addUpdateListener { glow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun length(item: ArrangementClip): Float =
        project?.tracks?.firstOrNull { it.id == item.trackId }?.let {
            if (it.isInstrument) STEPS_PER_CLIP.toFloat() else audioSteps[it.id] ?: 16f
        } ?: 16f

    private fun bounds(item: ArrangementClip) {
        rect.set(labelWidth + item.startStep * stepWidth - offsetX,
            rulerHeight + item.lane * laneHeight - offsetY + context.dp(3),
            labelWidth + (item.startStep + length(item)) * stepWidth - offsetX,
            rulerHeight + (item.lane + 1) * laneHeight - offsetY - context.dp(3))
    }

    override fun onDraw(canvas: Canvas) {
        val current = project ?: return
        canvas.drawColor(Flm.bg)
        val firstLane = (offsetY / laneHeight).toInt()
        val lastLane = minOf(PLAYLIST_LANES - 1, ((offsetY + height - rulerHeight) / laneHeight).toInt())
        val firstStep = (offsetX / stepWidth).toInt()
        val lastStep = minOf(PLAYLIST_STEPS, ((offsetX + width - labelWidth) / stepWidth).toInt() + 1)
        canvas.save()
        canvas.clipRect(labelWidth, rulerHeight, width.toFloat(), height.toFloat())
        for (lane in firstLane..lastLane) {
            val y = rulerHeight + lane * laneHeight - offsetY
            paint.color = if (lane % 2 == 0) Flm.panel else Flm.bg
            canvas.drawRect(labelWidth, y, width.toFloat(), y + laneHeight, paint)
            paint.color = Flm.panelAlt
            canvas.drawLine(labelWidth, y + laneHeight, width.toFloat(), y + laneHeight, paint)
        }
        for (step in firstStep..lastStep) {
            paint.color = Flm.withAlpha(Flm.text, if (step % 16 == 0) 90 else if (step % 4 == 0) 38 else 14)
            paint.strokeWidth = if (step % 16 == 0) context.dp(1).toFloat() else 1f
            val x = labelWidth + step * stepWidth - offsetX
            canvas.drawLine(x, rulerHeight, x, height.toFloat(), paint)
        }
        current.arrangement.forEach { item ->
            if (item.id != held?.id || preview == null) drawClip(canvas, item, false)
        }
        preview?.let { drawClip(canvas, it, true) }
        if (playhead >= 0) {
            val x = labelWidth + playhead * stepWidth - offsetX
            paint.color = Flm.text
            paint.strokeWidth = context.dp(1).toFloat()
            canvas.drawLine(x, rulerHeight, x, height.toFloat(), paint)
        }
        canvas.restore()
        paint.color = Flm.panelAlt
        canvas.drawRect(0f, 0f, labelWidth, height.toFloat(), paint)
        canvas.drawRect(labelWidth, 0f, width.toFloat(), rulerHeight, paint)
        paint.textSize = context.dp(10).toFloat()
        for (lane in firstLane..lastLane) {
            val y = rulerHeight + lane * laneHeight - offsetY
            if (y + laneHeight <= rulerHeight) continue
            canvas.save()
            canvas.clipRect(0f, rulerHeight, labelWidth, height.toFloat())
            paint.color = Flm.textDim
            canvas.drawText("Track ${lane + 1}", context.dp(8).toFloat(), y + context.dp(24), paint)
            paint.color = Flm.bg
            canvas.drawLine(0f, y + laneHeight, labelWidth, y + laneHeight, paint)
            canvas.restore()
        }
        paint.color = Flm.textDim
        canvas.drawText("PLAYLIST", context.dp(8).toFloat(), context.dp(16).toFloat(), paint)
        canvas.save()
        canvas.clipRect(labelWidth, 0f, width.toFloat(), rulerHeight)
        for (step in firstStep..lastStep) if (step % 16 == 0) {
            canvas.drawText("${step / 16 + 1}", labelWidth + step * stepWidth - offsetX + context.dp(4),
                context.dp(16).toFloat(), paint)
        }
        canvas.restore()
    }

    private fun drawClip(canvas: Canvas, item: ArrangementClip, grabbed: Boolean) {
        val current = project ?: return
        val index = current.tracks.indexOfFirst { it.id == item.trackId }
        if (index < 0) return
        bounds(item)
        if (rect.right < labelWidth || rect.left > width || rect.bottom < rulerHeight || rect.top > height) return
        val track = current.tracks[index]
        val color = Flm.laneColor(index)
        paint.style = Paint.Style.FILL
        paint.color = Flm.withAlpha(color, if (track.muted) 55 else 150)
        canvas.drawRoundRect(rect, context.dp(3).toFloat(), context.dp(3).toFloat(), paint)
        paint.color = Flm.withAlpha(color, 240)
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + context.dp(14), paint)
        canvas.save()
        canvas.clipRect(rect)
        paint.color = Flm.text
        paint.textSize = context.dp(10).toFloat()
        canvas.drawText(if (track.isInstrument) track.name else track.audioName ?: track.name,
            rect.left + context.dp(4), rect.top + context.dp(11), paint)
        if (track.isInstrument) {
            val notes = track.clip?.notes.orEmpty()
            val low = notes.minOfOrNull { it.pitch } ?: 60
            val high = maxOf(low + 12, notes.maxOfOrNull { it.pitch } ?: 72)
            val noteArea = rect.height() - context.dp(18)
            val thickness = context.dp(3).toFloat()
            notes.forEach { note ->
                val y = rect.bottom - context.dp(4) - (note.pitch - low).toFloat() / (high - low) * noteArea
                val left = rect.left + note.step * stepWidth + 1f
                val right = maxOf(left + context.dp(4), rect.left + (note.step + note.lengthSteps) * stepWidth - 1f)
                canvas.drawRoundRect(left, y - thickness, right, y + thickness, thickness, thickness, paint)
            }
        } else {
            paint.color = Flm.withAlpha(Flm.text, 160)
            canvas.drawText("AUDIO", rect.left + context.dp(4), rect.bottom - context.dp(6), paint)
        }
        canvas.restore()
        if (grabbed || item.id == selectedClip) {
            paint.style = Paint.Style.STROKE
            paint.color = Flm.withAlpha(Flm.text, if (grabbed) (160 + 95 * glow).toInt() else 150)
            paint.strokeWidth = context.dp(1) + context.dp(2) * if (grabbed) glow else 0f
            canvas.drawRoundRect(rect, context.dp(3).toFloat(), context.dp(3).toFloat(), paint)
            paint.style = Paint.Style.FILL
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = project ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y; startX = offsetX; startY = offsetY
                mode = 0
                held = if (event.x >= labelWidth && event.y >= rulerHeight) current.arrangement.lastOrNull {
                    bounds(it); rect.contains(event.x, event.y)
                } else null
                preview = held
                if (held != null) {
                    feedback(true)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                } else if (event.x < labelWidth && event.y >= rulerHeight) {
                    trackLane = ((event.y - rulerHeight + offsetY) / laneHeight).toInt().coerceIn(0, PLAYLIST_LANES - 1)
                    postDelayed(trackLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress); removeCallbacks(trackLongPress); trackLane = -1
                mode = 3
                preview = null
                feedback(false)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (mode == 0 && maxOf(abs(dx), abs(dy)) > slop) {
                    removeCallbacks(longPress); removeCallbacks(trackLongPress); trackLane = -1
                    mode = if (held != null) { if (canEdit()) 1 else 3 } else 2
                }
                if (mode == 1) {
                    held?.let {
                        preview = it.copy(lane = (it.lane + (dy / laneHeight).roundToInt()).coerceIn(0, PLAYLIST_LANES - 1),
                            startStep = (it.startStep + (dx / stepWidth).roundToInt()).coerceIn(0, PLAYLIST_STEPS - 1))
                    }
                    invalidate()
                } else if (mode == 2) {
                    offsetX = (startX - dx).coerceIn(0f, maxOf(0f, PLAYLIST_STEPS * stepWidth - width + labelWidth))
                    offsetY = (startY - dy).coerceIn(0f, maxOf(0f, PLAYLIST_LANES * laneHeight - height + rulerHeight))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress); removeCallbacks(trackLongPress); trackLane = -1
                val item = held
                if (mode == 1 && item != null) {
                    val destination = item.copy(
                        lane = (item.lane + ((event.y - downY) / laneHeight).roundToInt()).coerceIn(0, PLAYLIST_LANES - 1),
                        startStep = (item.startStep + ((event.x - downX) / stepWidth).roundToInt()).coerceIn(0, PLAYLIST_STEPS - 1))
                    if (destination != item) onMove?.invoke(destination.id, destination.lane, destination.startStep)
                } else if (mode == 0 && maxOf(abs(event.x - downX), abs(event.y - downY)) <= slop) {
                    if (item != null) {
                        val doubleTap = lastTapId == item.id && event.eventTime - lastTapTime < 350
                        lastTapId = item.id; lastTapTime = event.eventTime
                        selectedClip = item.id
                        onSelect?.invoke(item, doubleTap)
                    } else if (event.x >= labelWidth && event.y >= rulerHeight && canEdit()) {
                        onInsert?.invoke(((event.y - rulerHeight + offsetY) / laneHeight).toInt().coerceIn(0, PLAYLIST_LANES - 1),
                            ((event.x - labelWidth + offsetX) / stepWidth).toInt().coerceIn(0, PLAYLIST_STEPS - 1))
                    }
                }
                preview = null; held = null; mode = 3
                feedback(false)
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress); removeCallbacks(trackLongPress); trackLane = -1
                preview = null; held = null; mode = 3
                feedback(false)
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress); removeCallbacks(trackLongPress)
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
