package org.androidaudioplugin.minidahost

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class Sheet(context: Context, title: String, subtitle: String? = null) : Dialog(context) {

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val rows = LinearLayout(context)
    private val customSlot = LinearLayout(context)
    private var dismissing = false

    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setCancelable(true)
        setCanceledOnTouchOutside(false)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setDimAmount(0f)
            setWindowAnimations(0)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val root = FrameLayout(context)
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0))
        scrim.alpha = 0f
        scrim.setOnClickListener { dismissAnimated() }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val radius = context.dp(20).toFloat()
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(Flm.panel)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        panel.elevation = context.dp(24).toFloat()
        panel.setPadding(0, 0, 0, context.dp(10))
        panel.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(Flm.withAlpha(Flm.text, 70))
                cornerRadius = context.dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(context.dp(36), context.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = context.dp(8)
            bottomMargin = context.dp(12)
        })
        panel.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(Flm.text)
            letterSpacing = 0.02f
            setPadding(context.dp(20), 0, context.dp(20), if (subtitle == null) context.dp(8) else context.dp(2))
        })
        if (subtitle != null) panel.addView(TextView(context).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Flm.textDim)
            setPadding(context.dp(20), 0, context.dp(20), context.dp(8))
        })
        customSlot.orientation = LinearLayout.VERTICAL
        customSlot.visibility = View.GONE
        panel.addView(customSlot)
        rows.orientation = LinearLayout.VERTICAL
        panel.addView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit
                    else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply { addView(rows) })
        val panelWidth = minOf(context.dp(320), context.resources.displayMetrics.widthPixels)
        root.addView(panel, FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
    }

    fun row(title: String, glyph: String? = null, color: Int = Flm.accent, subtitle: String? = null,
            trailing: View? = null, onClick: () -> Unit): Sheet {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(52)
            setPadding(context.dp(16), context.dp(6), context.dp(16), context.dp(6))
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = context.dp(10).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(Flm.withAlpha(Flm.text, 30)), null, mask)
            setOnClickListener { dismissAnimated(); onClick() }
        }
        if (glyph != null) row.addView(TextView(context).apply {
            text = glyph
            textSize = 15f
            setTextColor(color)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Flm.withAlpha(color, 42))
                cornerRadius = context.dp(9).toFloat()
            }
        }, LinearLayout.LayoutParams(context.dp(34), context.dp(34)).apply { marginEnd = context.dp(14) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(Flm.text)
        })
        if (subtitle != null) texts.addView(TextView(context).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Flm.textDim)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { row.addView(it) }
        row.alpha = 0f
        row.translationY = context.dp(14).toFloat()
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return this
    }

    fun chips(vararg colors: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        colors.forEach { color ->
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = context.dp(4).toFloat()
                    setStroke(context.dp(1), Flm.withAlpha(Flm.text, 40))
                }
            }, LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply { marginStart = context.dp(6) })
        }
    }

    fun custom(view: View): Sheet {
        customSlot.visibility = View.VISIBLE
        customSlot.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(20), context.dp(4), context.dp(20), context.dp(4))
        })
        return this
    }

    fun primary(text: String, onClick: () -> Boolean): Sheet {
        val dark = Flm.themes[Flm.themeIndex].name == "Paper"
        val button = Button(context).apply {
            this.text = text
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(if (dark) Color.WHITE else Flm.bg)
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            background = GradientDrawable().apply {
                setColor(Flm.accent)
                cornerRadius = context.dp(10).toFloat()
            }
            setOnClickListener { if (onClick()) dismissAnimated() }
        }
        button.alpha = 0f
        panel.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)).apply {
            setMargins(context.dp(20), context.dp(10), context.dp(20), context.dp(4))
        })
        return this
    }

    fun present() {
        show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(280).setInterpolator(OvershootInterpolator(0.9f)).start()
            scrim.animate().alpha(1f).setDuration(200).start()
            for (index in 0 until rows.childCount) {
                rows.getChildAt(index).animate().alpha(1f).translationY(0f)
                    .setStartDelay(70L + minOf(index, 5) * 35L).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            }
            for (index in 0 until customSlot.childCount)
                customSlot.getChildAt(index).animate().alpha(1f).setStartDelay(90).setDuration(200).start()
            for (index in 0 until panel.childCount) {
                val child = panel.getChildAt(index)
                if (child is Button) child.animate().alpha(1f).setStartDelay(120).setDuration(200).start()
            }
        }
    }

    fun dismissAnimated() {
        if (dismissing) return
        dismissing = true
        scrim.animate().alpha(0f).setDuration(160).start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(180).setInterpolator(AccelerateInterpolator())
            .withEndAction { dismiss() }.start()
    }

    override fun cancel() {
        dismissAnimated()
    }
}
