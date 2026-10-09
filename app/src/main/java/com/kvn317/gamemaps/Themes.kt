package com.kvn317.gamemaps

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

class MapTheme(
    val name: String,
    val url: String,
    matrix: ColorMatrix,
    background: Long,
    blip: Long,
    route: Long,
    val scanlines: Boolean = false,
) {
    val filter = ColorMatrixColorFilter(matrix)
    val background = background.toInt()
    val blip = blip.toInt()
    val route = route.toInt()
}

// Carto basemaps (OSM data), free for non-commercial use.
private const val DARK = "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png"
private const val VOYAGER = "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png"

object Themes {
    // ponytail: colors and gains are eyeballed; tune them here against real tiles
    val all = listOf(
        MapTheme("Saint Denis II", DARK, duotone(0xFFE6D3A3, 0xFF2B1A0C, gain = 3f), 0xFFE6D3A3, 0xFF8B1A1A, 0xDDB22222),
        MapTheme("Pip-Boy", DARK, duotone(0xFF04140A, 0xFF41FF8A, gain = 3f), 0xFF04140A, 0xFF41FF8A, 0xDDC8FFDC, scanlines = true),
        MapTheme("San Andreas", VOYAGER, sanAndreas(), 0xFF5E6B55, 0xFFFFFFFF, 0xDDFFD400),
    )
}

/** Maps tile brightness onto a two-color ramp: black -> [from], bright -> [to]. [gain] stretches dim tiles. */
private fun duotone(from: Long, to: Long, gain: Float): ColorMatrix {
    val m = FloatArray(20)
    for (c in 0..2) {
        val shift = 16 - 8 * c
        val f = ((from shr shift) and 0xFFL).toFloat()
        val k = gain * (((to shr shift) and 0xFFL) - f) / 255f
        m[c * 5] = 0.299f * k
        m[c * 5 + 1] = 0.587f * k
        m[c * 5 + 2] = 0.114f * k
        m[c * 5 + 4] = f
    }
    m[18] = 1f
    return ColorMatrix(m)
}

/** Darker, punchier, olive-tinted street map, roughly the GTA SA radar look. */
private fun sanAndreas() = ColorMatrix().apply {
    setSaturation(2f)
    postConcat(ColorMatrix().apply { setScale(0.62f, 0.72f, 0.66f, 1f) })
}
