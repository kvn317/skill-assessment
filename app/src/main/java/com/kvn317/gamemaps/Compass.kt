package com.kvn317.gamemaps

import android.app.Activity
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

/** Which way the phone's top edge points, in degrees from true north. Main thread only. */
object Compass : SensorEventListener {
    var azimuth: Float? = null
        private set
    private val listeners = LinkedHashSet<() -> Unit>()
    private var sensors: SensorManager? = null
    private var window: WindowManager? = null
    private var lastNotified = 0f
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    fun add(activity: Activity, l: () -> Unit) {
        listeners += l
        if (sensors != null) return
        val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return // no compass: GPS bearing only
        sensors = sm
        window = activity.windowManager
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun remove(l: () -> Unit) {
        listeners -= l
        if (listeners.isEmpty()) {
            sensors?.unregisterListener(this)
            sensors = null
            window = null
            azimuth = null
        }
    }

    @Suppress("DEPRECATION") // defaultDisplay: Activity.display needs API 30
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Measure along the screen's "up", whichever way the phone is rotated.
        val (x, y) = when (window?.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, x, y, remapped)
        SensorManager.getOrientation(remapped, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        deg += declination() // magnetic -> true north
        // Low-pass along the short way round so the arrow doesn't jitter.
        val prev = azimuth ?: deg
        val delta = ((deg - prev) % 360 + 540) % 360 - 180
        val next = ((prev + delta * 0.25f) % 360 + 360) % 360
        azimuth = next
        val moved = ((next - lastNotified) % 360 + 540) % 360 - 180
        if (abs(moved) >= 2f) { // ~2° steps keep redraws cheap
            lastNotified = next
            listeners.toList().forEach { it() }
        }
    }

    private var declinationAt: android.location.Location? = null
    private var declinationDeg = 0f

    /** Recomputed only when the fix changes; the sensor fires ~15x a second. */
    private fun declination(): Float {
        val l = Gps.last ?: return 0f
        if (l !== declinationAt) {
            declinationAt = l
            declinationDeg = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), l.time).declination
        }
        return declinationDeg
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
