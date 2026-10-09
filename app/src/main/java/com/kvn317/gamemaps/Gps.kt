package com.kvn317.gamemaps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** One location feed shared by the phone screen and the car screen. Main thread only. */
@SuppressLint("MissingPermission")
object Gps : LocationListener {
    var last: Location? = null
        private set
    private val listeners = LinkedHashSet<() -> Unit>()
    private var lm: LocationManager? = null

    fun add(ctx: Context, l: () -> Unit) {
        listeners += l
        if (lm != null || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        lm = ctx.applicationContext.getSystemService(LocationManager::class.java).apply {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (p !in allProviders) continue
                requestLocationUpdates(p, 1000L, 1f, this@Gps, Looper.getMainLooper())
                if (last == null) last = getLastKnownLocation(p)
            }
        }
    }

    fun remove(l: () -> Unit) {
        listeners -= l
        if (listeners.isEmpty()) {
            lm?.removeUpdates(this)
            lm = null
        }
    }

    override fun onLocationChanged(l: Location) {
        val prev = last
        // Don't let a coarse network fix overwrite a fresh GPS one.
        if (prev != null && prev.provider == LocationManager.GPS_PROVIDER && l.provider != LocationManager.GPS_PROVIDER &&
            l.time - prev.time < 10_000
        ) return
        last = l
        listeners.toList().forEach { it() }
    }

    // Android 8-10 call these, and they have no default implementation there.
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
