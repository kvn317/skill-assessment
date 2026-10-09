package com.kvn317.gamemaps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.FrameLayout
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos

/**
 * Themed vector map with the route, destination and player blip; shared by the phone screen and the car screen.
 * Main thread only. Callers forward lifecycle via [start]/[stop]/[destroy].
 */
/** [texture] renders through a TextureView, needed when shown on Android Auto's virtual display. */
class GameMap(ctx: Context, texture: Boolean = false) : FrameLayout(ctx) {
    var theme = Themes.all[0]
        set(v) {
            field = v
            applyTheme()
        }
    /** Camera tracks the car; turned off when the user drags the map. */
    var follow = true
    /** Rotate so the direction of travel points up; false = north up. */
    var headingUp = true
        set(v) {
            field = v
            onLocation()
        }
    /** Camera padding (left, top, right, bottom px) so the car can sit low in the visible area. */
    var padding = DoubleArray(4)
    var onLongPress: ((LatLng) -> Unit)? = null

    private val mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var shownRoute: Route? = null
    private var heading = 0.0 // last reliable direction of travel
    private val hud = Hud(ctx)

    init {
        MapLibre.getInstance(ctx)
        mapView = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(texture))
        mapView.onCreate(null)
        addView(mapView)
        addView(hud)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false // credit is drawn by the HUD so it shows in the car too
            m.uiSettings.isCompassEnabled = false // its tap-to-north fights heading-up; the Heading/North button replaces it
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) { follow = false }
                override fun onMove(detector: MoveGestureDetector) {}
                override fun onMoveEnd(detector: MoveGestureDetector) {}
            })
            m.addOnMapLongClickListener {
                onLongPress?.invoke(it)
                true
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(29.9511, -90.0715)).zoom(15.0).build() // New Orleans until a fix
            applyTheme()
        }
    }

    fun start() {
        mapView.onStart()
        mapView.onResume()
    }

    fun stop() {
        mapView.onPause()
        mapView.onStop()
    }

    fun destroy() = mapView.onDestroy()

    fun zoomBy(d: Int) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(d.toDouble()))
    }

    /** Zooms out to the whole trip (stops following until recentered), like Google's route overview. */
    fun overview() {
        val r = Nav.route ?: return
        val m = map ?: return
        val pts = r.lats.indices.map { LatLng(r.lats[it], r.lons[it]) } + listOfNotNull(Gps.last?.let { LatLng(it.latitude, it.longitude) })
        if (pts.size < 2) return
        follow = false
        val pad = (48 * resources.displayMetrics.density).toInt()
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(pts).build(), pad), 800)
    }

    fun recenter() {
        follow = true
        onLocation()
    }

    /** New GPS fix: move the blip and, when following, glide the camera there over about one fix interval. */
    fun onLocation() {
        hud.invalidate() // speed readout
        refresh()
        val l = Gps.last ?: return
        if (l.hasBearing() && l.speed > 1.5f) heading = l.bearing.toDouble() // GPS bearing is noise below ~3 mph
        if (!follow) return
        val m = map ?: return
        val cur = m.cameraPosition
        val p = padding
        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(cur) // keep the user's zoom (and tilt when not navigating)
                    .target(LatLng(l.latitude, l.longitude))
                    .bearing(if (headingUp) heading else 0.0)
                    .tilt(navTilt(cur.tilt)) // 3D driving view while guiding
                    .padding(p[0], p[1], p[2], p[3])
                    .build()
            ),
            1000,
            false, // linear, so back-to-back fixes blend into one continuous glide
        )
    }

    /** Redraws route, destination and blip from [Nav] and [Gps]. */
    fun refresh() {
        val s = style ?: return
        val l = Gps.last
        s.getSourceAs<GeoJsonSource>("me")?.let { src ->
            if (l == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(l.longitude, l.latitude)).apply {
                addNumberProperty("bearing", blipBearing(l))
                // Accuracy radius in pixels at zoom 22 (512px tiles); the layer halves it per zoom level.
                addNumberProperty("r22", l.accuracy / (0.018661 * cos(Math.toRadians(l.latitude))))
            })
        }
        val d = Nav.dest
        s.getSourceAs<GeoJsonSource>("dest")?.let { src ->
            if (d == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            else src.setGeoJson(Point.fromLngLat(d.lon, d.lat))
        }
        val r = Nav.route
        if (r !== shownRoute) {
            shownRoute = r
            s.getSourceAs<GeoJsonSource>("route")?.let { src ->
                if (r == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
                else src.setGeoJson(LineString.fromLngLats(r.lats.indices.map { Point.fromLngLat(r.lons[it], r.lats[it]) }))
            }
        }
    }

    private var tiltedForNav = false

    /** 45° while guiding; flattens once when guidance ends; otherwise keeps whatever tilt the user chose. */
    private fun navTilt(current: Double): Double = when {
        Nav.active -> 45.0.also { tiltedForNav = true }
        tiltedForNav -> 0.0.also { tiltedForNav = false }
        else -> current
    }

    /** Driving: GPS direction of travel. Standing or walking slowly: where the phone points, if it has a compass. */
    private fun blipBearing(l: android.location.Location): Float =
        if (l.hasBearing() && l.speed > 1.5f) l.bearing else Compass.azimuth ?: l.bearing

    private fun applyTheme() {
        hud.invalidate()
        val m = map ?: return
        val t = theme
        style = null
        m.setStyle(Style.Builder().fromJson(styleJson(t))) { s ->
            s.addImage("blip", blipBitmap(t.blip))
            s.addSource(GeoJsonSource("route"))
            s.addLayer(
                LineLayer("route", "route").withProperties(
                    PropertyFactory.lineColor(t.route),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineOpacity(0.9f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            s.addSource(GeoJsonSource("dest"))
            s.addLayer(
                CircleLayer("dest", "dest").withProperties(
                    PropertyFactory.circleColor(t.route),
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleStrokeColor("#000000"),
                    PropertyFactory.circleStrokeWidth(2f),
                )
            )
            s.addSource(GeoJsonSource("me"))
            s.addLayer( // GPS accuracy halo, like Google's light-blue circle
                CircleLayer("accuracy", "me").withProperties(
                    PropertyFactory.circleRadius(
                        Expression.interpolate(Expression.exponential(2), Expression.zoom(), Expression.stop(0, 0), Expression.stop(22, Expression.get("r22")))
                    ),
                    PropertyFactory.circleColor(t.blip),
                    PropertyFactory.circleOpacity(0.15f),
                    PropertyFactory.circleStrokeColor(t.blip),
                    PropertyFactory.circleStrokeWidth(1f),
                    PropertyFactory.circleStrokeOpacity(0.4f),
                    PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
                )
            )
            s.addLayer(
                SymbolLayer("me", "me").withProperties(
                    PropertyFactory.iconImage("blip"),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            )
            if (theme === t) {
                style = s
                shownRoute = null
                refresh()
            }
        }
    }

    /** Screen-fixed overlay: the Pip-Boy grid and the map credit. */
    private inner class Hud(ctx: Context) : View(ctx) {
        private val gridPaint = Paint()
        private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22 * resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.RIGHT
            isFakeBoldText = true
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        private val creditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11 * resources.displayMetrics.scaledDensity
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(c: Canvas) {
            val g = theme.grid
            if (g != 0) {
                gridPaint.color = g
                val step = 40 * resources.displayMetrics.density
                var x = 0f
                while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
                var y = 0f
                while (y < height) { c.drawLine(0f, y, width.toFloat(), y, gridPaint); y += step }
            }
            c.drawText("© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors", 12f, height - 12f, creditPaint)
            Gps.last?.takeIf { it.hasSpeed() && it.speed > 0.5f }?.let { // current speed, bottom right
                val dp = resources.displayMetrics.density
                c.drawText(speedText(it.speed), width - 16 * dp, height - 28 * dp, speedPaint)
            }
        }
    }
}

private fun blipBitmap(color: Int): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { b ->
    val c = Canvas(b)
    val arrow = Path().apply { moveTo(32f, 4f); lineTo(56f, 58f); lineTo(32f, 44f); lineTo(8f, 58f); close() }
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    c.drawPath(arrow, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 4f
    p.color = Color.BLACK
    c.drawPath(arrow, p)
}
