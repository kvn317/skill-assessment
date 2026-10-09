package com.kvn317.gamemaps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

class MainActivity : Activity() {
    private lateinit var mapView: MapView
    private val onFix: () -> Unit = { mapView.onLocation() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mapView = MapView(this)
        val map = mapView.map
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(Themes.all.map { t -> t.name to { map.theme = t } }))
            addView(row(listOf(
                "−" to { map.zoomBy(-1) },
                "◎" to { mapView.follow = true; mapView.onLocation() },
                "+" to { map.zoomBy(1) },
            )))
        }
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(mapView)
            addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 0)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        Gps.add(this, onFix) // starts the feed now that it's allowed
    }

    override fun onStart() {
        super.onStart()
        Gps.add(this, onFix)
    }

    override fun onStop() {
        Gps.remove(onFix)
        super.onStop()
    }

    private fun row(items: List<Pair<String, () -> Unit>>) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        for ((label, f) in items) addView(Button(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener { f() }
        })
    }
}

class MapView(ctx: Context) : View(ctx) {
    val map = TileMap(::invalidate)
    var follow = true

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            follow = false
            map.pan(dx, dy)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            map.zoomBy(1)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent) = gestures.onTouchEvent(e)

    override fun onDraw(c: Canvas) = map.draw(c, width, height)

    fun onLocation() {
        val l = Gps.last
        if (follow && l != null) map.center(l) else invalidate()
    }
}
