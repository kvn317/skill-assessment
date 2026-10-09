package com.kvn317.gamemaps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CarService : CarAppService() {
    // ponytail: accepts any host so a sideloaded build runs in Android Auto; restrict to Google's hosts before publishing
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarMapScreen(carContext)
    }
}

/** Full-screen themed map on the car display, always following the car. */
class CarMapScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {
    private val map = TileMap(::render)
    private var surface: SurfaceContainer? = null
    private var area: Rect? = null
    private val onFix: () -> Unit = { Gps.last?.let(map::center) }

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Keeps GPS flowing while the phone is locked in a pocket.
                if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        ctx.startForegroundService(Intent(ctx, DriveService::class.java))
                    } catch (e: IllegalStateException) {
                        // Android refused a background start; the map still works with an unlocked phone.
                    }
                }
                Gps.add(ctx, onFix)
            }

            override fun onStop(owner: LifecycleOwner) {
                Gps.remove(onFix)
                ctx.stopService(Intent(ctx, DriveService::class.java))
            }
        })
    }

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(action(title = map.theme.name) {
                    map.theme = Themes.all[(Themes.all.indexOf(map.theme) + 1) % Themes.all.size]
                    invalidate()
                })
                .addAction(action(icon = R.drawable.ic_zoom_in) { map.zoomBy(1) })
                .addAction(action(icon = R.drawable.ic_zoom_out) { map.zoomBy(-1) })
                .build()
        )
        .build()

    // The navigation action strip allows only one text title; the rest must be icons.
    private fun action(title: String? = null, icon: Int = 0, f: () -> Unit) = Action.Builder().apply {
        if (title != null) setTitle(title)
        else setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
        setOnClickListener { f() }
    }.build()

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container
        render()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        area = visibleArea
        render()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
    }

    private fun render() {
        val s = surface?.surface ?: return
        if (!s.isValid) return
        val c = s.lockCanvas(null)
        try {
            val a = area ?: Rect(0, 0, c.width, c.height)
            map.draw(c, c.width, c.height, a.exactCenterX(), a.exactCenterY())
        } finally {
            s.unlockCanvasAndPost(c)
        }
    }
}

/** Foreground "driving" service; it exists only so Android keeps delivering location in the background. */
class DriveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("drive", "Driving", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "drive")
            .setContentTitle("IRL Game Maps is on Android Auto")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
        return START_NOT_STICKY
    }
}
