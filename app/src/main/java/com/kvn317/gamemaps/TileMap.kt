package com.kvn317.gamemaps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

private const val TILE = 512 // @2x tiles

/** Slippy map drawn onto any Canvas; shared by the phone view and the Android Auto surface. Main thread only. */
class TileMap(private val onUpdate: () -> Unit) {
    var theme = Themes.all[0]
        set(v) {
            field = v
            paint.colorFilter = v.filter
            onUpdate()
        }
    var zoom = 16
        private set

    // Normalized Web Mercator center. Starts in New Orleans (Saint Denis IRL) until the first fix.
    private var x = mercX(-90.0715)
    private var y = mercY(29.9511)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = theme.filter }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
    private val scanPaint = Paint().apply { color = 0x50000000 }
    private val dst = RectF()
    private val arrow = Path().apply {
        val r = TILE / 24f
        moveTo(0f, -r); lineTo(r * 0.75f, r); lineTo(0f, r * 0.45f); lineTo(-r * 0.75f, r); close()
    }

    fun center(l: Location) {
        x = mercX(l.longitude)
        y = mercY(l.latitude)
        onUpdate()
    }

    fun zoomBy(d: Int) {
        zoom = (zoom + d).coerceIn(3, 19)
        onUpdate()
    }

    fun pan(dx: Float, dy: Float) {
        val world = TILE.toDouble() * (1 shl zoom)
        x = ((x + dx / world) % 1 + 1) % 1
        y = (y + dy / world).coerceIn(0.0, 1.0)
        onUpdate()
    }

    /** Draws a [w]x[h] map with the center at ([cx], [cy]). */
    fun draw(c: Canvas, w: Int, h: Int, cx: Float = w / 2f, cy: Float = h / 2f) {
        val n = 1 shl zoom
        val world = TILE.toDouble() * n
        val left = x * world - cx
        val top = y * world - cy
        c.drawColor(theme.background)
        for (ty in floor(top / TILE).toInt()..floor((top + h) / TILE).toInt()) {
            if (ty !in 0 until n) continue
            for (tx in floor(left / TILE).toInt()..floor((left + w) / TILE).toInt()) {
                val bmp = tile(zoom, Math.floorMod(tx, n), ty) ?: continue
                val l = (tx * TILE.toDouble() - left).toFloat()
                val t = (ty * TILE.toDouble() - top).toFloat()
                dst.set(l, t, l + TILE, t + TILE)
                c.drawBitmap(bmp, null, dst, paint)
            }
        }
        if (theme.scanlines) for (sy in 0 until h step 4) c.drawLine(0f, sy.toFloat(), w.toFloat(), sy.toFloat(), scanPaint)
        Gps.last?.let {
            c.save()
            c.translate((mercX(it.longitude) * world - left).toFloat(), (mercY(it.latitude) * world - top).toFloat())
            c.rotate(it.bearing)
            blipPaint.style = Paint.Style.FILL
            blipPaint.color = theme.blip
            c.drawPath(arrow, blipPaint)
            blipPaint.style = Paint.Style.STROKE
            blipPaint.color = 0xFF000000.toInt()
            c.drawPath(arrow, blipPaint)
            c.restore()
        }
    }

    private fun tile(z: Int, tx: Int, ty: Int): Bitmap? {
        val url = theme.url.replace("{z}", "$z").replace("{x}", "$tx").replace("{y}", "$ty")
        cache.get(url)?.let { return it }
        if (!pending.add(url)) return null
        pool.execute {
            val bmp = try {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "IrlGameMaps/1.0 (Android)")
                    inputStream.use { BitmapFactory.decodeStream(it, null, RGB_565) }
                }
            } catch (e: Exception) {
                null // retried on a later draw
            }
            main.post {
                pending.remove(url)
                if (bmp != null) {
                    cache.put(url, bmp)
                    onUpdate()
                }
            }
        }
        return null
    }

    companion object {
        // ponytail: memory-only cache (~24MB of RGB_565 tiles) shared by phone and car; add a disk cache if data use matters
        private val cache = LruCache<String, Bitmap>(48)
        private val pending = HashSet<String>()
        private val pool = Executors.newFixedThreadPool(4)
        private val main = Handler(Looper.getMainLooper())
        private val RGB_565 = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

        fun mercX(lon: Double) = (lon + 180) / 360
        fun mercY(lat: Double): Double {
            val r = Math.toRadians(lat)
            return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
        }
    }
}
