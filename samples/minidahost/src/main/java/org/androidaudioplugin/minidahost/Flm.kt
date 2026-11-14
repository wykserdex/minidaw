package org.androidaudioplugin.minidahost

import android.content.Context
import android.graphics.Color

object Flm {
    val bg = Color.rgb(0x16, 0x16, 0x19)
    val panel = Color.rgb(0x20, 0x20, 0x24)
    val panelAlt = Color.rgb(0x2B, 0x2B, 0x31)
    val accent = Color.rgb(0xB2, 0xAA, 0xCF)
    val text = Color.rgb(0xDE, 0xDD, 0xE4)
    val textDim = Color.rgb(0x91, 0x90, 0x9B)
    val laneColors = intArrayOf(
        Color.rgb(0xAD, 0xA3, 0xCA), Color.rgb(0x84, 0xAE, 0xA1),
        Color.rgb(0xC0, 0x9D, 0x85), Color.rgb(0x88, 0xA5, 0xBE))

    fun laneColor(index: Int) = laneColors[index % laneColors.size]
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
