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

// Standard OpenStreetMap tiles: no key needed, but the tile policy asks for light use and visible attribution.
// (Carto's free basemaps started demanding an API key.)
private const val OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

object Themes {
    // ponytail: palettes tuned on paper against OSM's standard colors; tweak here after seeing them on a real screen
    val all = listOf(
        MapTheme("Saint Denis II", OSM, duotone(0xFF2B1A0C, 0xFFE6D3A3, lo = 0.5f, hi = 0.98f), 0xFFE6D3A3, 0xFF8B1A1A, 0xDDB22222),
        MapTheme("Pip-Boy", OSM, duotone(0xFF41FF8A, 0xFF04140A, lo = 0.5f, hi = 0.98f), 0xFF04140A, 0xFF41FF8A, 0xDDC8FFDC, scanlines = true),
        MapTheme("San Andreas", OSM, sanAndreas(), 0xFF6D8064, 0xFFFFFFFF, 0xDDFFD400),
    )
}

/**
 * Maps tile brightness onto a two-color ramp: luminance [lo] or darker -> [from], [hi] or brighter -> [to].
 * Narrowing lo..hi stretches OSM's mostly-pale palette so land, water and road outlines separate.
 */
private fun duotone(from: Long, to: Long, lo: Float, hi: Float): ColorMatrix {
    val m = FloatArray(20)
    for (c in 0..2) {
        val shift = 16 - 8 * c
        val f = ((from shr shift) and 0xFFL).toFloat()
        val span = ((to shr shift) and 0xFFL) - f
        val k = span / (255f * (hi - lo))
        m[c * 5] = 0.299f * k
        m[c * 5 + 1] = 0.587f * k
        m[c * 5 + 2] = 0.114f * k
        m[c * 5 + 4] = f - lo / (hi - lo) * span
    }
    m[18] = 1f
    return ColorMatrix(m)
}

/** Olive land, dark-outlined roads, teal water: roughly the GTA SA radar. Contrast stretch around OSM's pale land color. */
private fun sanAndreas() = ColorMatrix().apply {
    setSaturation(1.2f)
    val tint = floatArrayOf(0.7f, 0.85f, 0.72f)
    val m = FloatArray(20)
    for (c in 0..2) {
        m[c * 6] = 1.6f * tint[c] // (v - 225) * 1.6 + 128, then tinted
        m[c * 5 + 4] = -232f * tint[c]
    }
    m[18] = 1f
    postConcat(ColorMatrix(m))
}
