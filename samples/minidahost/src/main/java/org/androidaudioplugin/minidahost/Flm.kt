package org.androidaudioplugin.minidahost

import android.content.Context
import android.graphics.Color

object Flm {
    data class Theme(
        val name: String,
        val bg: Int, val panel: Int, val panelAlt: Int,
        val accent: Int, val text: Int, val textDim: Int,
        val laneSaturation: Float, val laneValue: Float,
        val danger: Int, val success: Int, val warning: Int, val info: Int
    )

    private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

    val themes = listOf(
        Theme("Charcoal", rgb(0x16, 0x16, 0x19), rgb(0x20, 0x20, 0x24), rgb(0x2B, 0x2B, 0x31),
            rgb(0xB2, 0xAA, 0xCF), rgb(0xDE, 0xDD, 0xE4), rgb(0x91, 0x90, 0x9B),
            0.28f, 0.74f, rgb(0xE6, 0x78, 0x78), rgb(0x84, 0xAE, 0xA1), rgb(0xC0, 0x9D, 0x85), rgb(0x88, 0xA5, 0xBE)),
        Theme("Deep Purple", rgb(0x0C, 0x0C, 0x16), rgb(0x17, 0x18, 0x2D), rgb(0x1D, 0x20, 0x33),
            rgb(0xFF, 0x76, 0x29), rgb(0xFF, 0xFF, 0xFF), rgb(0x9A, 0x9F, 0xB8),
            0.80f, 0.62f, rgb(0xE6, 0x4A, 0x4A), rgb(0x3D, 0xDC, 0x84), rgb(0xFF, 0xB0, 0x2E), rgb(0x3D, 0x9B, 0xE6)),
        Theme("Mint", rgb(0x10, 0x14, 0x13), rgb(0x1A, 0x21, 0x1F), rgb(0x1D, 0x27, 0x27),
            rgb(0xAB, 0xDD, 0xD2), rgb(0xD5, 0xE8, 0xDF), rgb(0x82, 0x99, 0x98),
            0.70f, 0.70f, rgb(0xE0, 0x6C, 0x6C), rgb(0x63, 0xC9, 0x9A), rgb(0xD9, 0xB0, 0x5B), rgb(0x6F, 0xA8, 0xC9)),
        Theme("Paper", rgb(0xF5, 0xF5, 0xF4), rgb(0xEC, 0xEB, 0xE9), rgb(0xD4, 0xD4, 0xD4),
            rgb(0x30, 0x7C, 0x58), rgb(0x58, 0x4C, 0x46), rgb(0x7A, 0x6F, 0x68),
            0.72f, 0.42f, rgb(0xB0, 0x3A, 0x3A), rgb(0x2E, 0x7D, 0x5B), rgb(0xA8, 0x76, 0x2A), rgb(0x33, 0x6B, 0x9E))
    )

    var themeIndex = 0
        private set
    var bg = themes[0].bg
        private set
    var panel = themes[0].panel
        private set
    var panelAlt = themes[0].panelAlt
        private set
    var accent = themes[0].accent
        private set
    var text = themes[0].text
        private set
    var textDim = themes[0].textDim
        private set
    var danger = themes[0].danger
        private set
    var success = themes[0].success
        private set
    var warning = themes[0].warning
        private set
    var info = themes[0].info
        private set
    private var laneSaturation = themes[0].laneSaturation
    private var laneValue = themes[0].laneValue

    fun applyTheme(index: Int) {
        val theme = themes[index.coerceIn(themes.indices)]
        themeIndex = themes.indexOf(theme)
        bg = theme.bg; panel = theme.panel; panelAlt = theme.panelAlt
        accent = theme.accent; text = theme.text; textDim = theme.textDim
        danger = theme.danger; success = theme.success; warning = theme.warning; info = theme.info
        laneSaturation = theme.laneSaturation; laneValue = theme.laneValue
    }

    // Evenly-spaced hues (golden angle) keep neighbouring tracks separable under any theme.
    fun laneColor(index: Int): Int {
        val hue = (index * 137.508f) % 360f
        return Color.HSVToColor(floatArrayOf(hue, laneSaturation, laneValue))
    }

    fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
