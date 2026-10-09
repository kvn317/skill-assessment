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
import org.maplibre.android.maps.MapLibreMap
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

/**
 * Themed vector map with the route, destination and player blip; shared by the phone screen and the car screen.
 * Main thread only. Callers forward lifecycle via [start]/[stop]/[destroy].
 */
class GameMap(ctx: Context) : FrameLayout(ctx) {
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
        mapView = MapView(ctx)
        mapView.onCreate(null)
        addView(mapView)
        addView(hud)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false // credit is drawn by the HUD so it shows in the car too
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

    fun recenter() {
        follow = true
        onLocation()
    }

    /** New GPS fix: move the blip and, when following, glide the camera there over about one fix interval. */
    fun onLocation() {
        refresh()
        val l = Gps.last ?: return
        if (l.hasBearing() && l.speed > 1.5f) heading = l.bearing.toDouble() // GPS bearing is noise below ~3 mph
        if (!follow) return
        val p = padding
        map?.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(l.latitude, l.longitude))
                    .bearing(if (headingUp) heading else 0.0)
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
            else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(l.longitude, l.latitude)).apply { addNumberProperty("bearing", l.bearing) })
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
