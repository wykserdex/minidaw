package org.androidaudioplugin.minidahost

import android.content.Context
import android.graphics.Color

// Dark host chrome with a single neon accent, in the spirit of a mobile DAW.
object Flm {
    val bg = Color.rgb(0x14, 0x15, 0x19)
    val panel = Color.rgb(0x1E, 0x20, 0x27)
    val panelAlt = Color.rgb(0x2A, 0x2D, 0x36)
    val accent = Color.rgb(0x62, 0xE3, 0x6B)
    val text = Color.rgb(0xD8, 0xDB, 0xE0)
    val textDim = Color.rgb(0x8B, 0x90, 0x99)
    val laneColors = intArrayOf(
        Color.rgb(0xE2, 0x55, 0x5B), Color.rgb(0xE2, 0x9C, 0x4B), Color.rgb(0xDA, 0xD4, 0x4C),
        Color.rgb(0x62, 0xD3, 0x6D), Color.rgb(0x56, 0xC9, 0xE2), Color.rgb(0xCA, 0x6C, 0xE2))

    fun laneColor(index: Int) = laneColors[index % laneColors.size]
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
