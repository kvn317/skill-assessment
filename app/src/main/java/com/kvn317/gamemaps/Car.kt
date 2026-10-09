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
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.time.ZonedDateTime

class CarService : CarAppService() {
    // ponytail: accepts any host so a sideloaded build runs in Android Auto; restrict to Google's hosts before publishing
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarMapScreen(carContext)
    }
}

/** Full-screen themed map on the car display that follows the car and shows turn-by-turn guidance. */
class CarMapScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {
    private val map = TileMap(::render)
    private var surface: SurfaceContainer? = null
    private var area: Rect? = null
    private val navManager = ctx.getCarService(NavigationManager::class.java)
    private var navStarted = false
    private val onFix: () -> Unit = { Gps.last?.let(map::center) }
    private val onNav: () -> Unit = {
        // Tells Android Auto (and other nav apps) whether we're guiding right now.
        if (Nav.active != navStarted) {
            navStarted = Nav.active
            if (navStarted) navManager.navigationStarted() else navManager.navigationEnded()
        }
        invalidate()
        render()
    }

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = Nav.stop()
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                Nav.add(onNav)
                onNav()
            }

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

            override fun onDestroy(owner: LifecycleOwner) {
                Nav.remove(onNav)
                if (navStarted) navManager.navigationEnded()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(action(title = map.theme.name) {
                map.theme = Themes.all[(Themes.all.indexOf(map.theme) + 1) % Themes.all.size]
                invalidate()
            })
            .addAction(
                if (Nav.active) action(icon = R.drawable.ic_close) { Nav.stop() }
                else action(icon = R.drawable.ic_search) { screenManager.push(CarSearchScreen(carContext)) }
            )
            .addAction(action(icon = R.drawable.ic_zoom_in) { map.zoomBy(1) })
            .addAction(action(icon = R.drawable.ic_zoom_out) { map.zoomBy(-1) })
            .build()
        val b = NavigationTemplate.Builder().setActionStrip(strip)
        if (Nav.active) {
            val s = Nav.route?.steps?.getOrNull(Nav.next)
            if (s == null) {
                b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            } else {
                val step = Step.Builder(s.instruction)
                    .setManeuver(Maneuver.Builder(s.carType).setIcon(carIcon(IconCompat.createWithBitmap(arrowBitmap(s.angle)))).build())
                if (s.road.isNotBlank()) step.setRoad(s.road)
                b.setNavigationInfo(RoutingInfo.Builder().setCurrentStep(step.build(), distance(Nav.toNext)).build())
                val secs = Nav.remainingSec.toLong()
                b.setDestinationTravelEstimate(
                    TravelEstimate.Builder(distance(Nav.remaining), ZonedDateTime.now().plusSeconds(secs))
                        .setRemainingTimeSeconds(secs)
                        .build()
                )
            }
        }
        return b.build()
    }

    private fun distance(m: Double) = roundDistance(m).let { (v, unit) -> Distance.create(v, unit) }

    private fun carIcon(i: IconCompat) = CarIcon.Builder(i).build()

    // The navigation action strip allows only one text title; the rest must be icons.
    private fun action(title: String? = null, icon: Int = 0, f: () -> Unit) = Action.Builder().apply {
        if (title != null) setTitle(title)
        else setIcon(carIcon(IconCompat.createWithResource(carContext, icon)))
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

/** Destination search; Android Auto disables the keyboard while driving but voice input still works. */
class CarSearchScreen(ctx: CarContext) : Screen(ctx) {
    private var results = emptyList<Place>()
    private var loading = false

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(text: String) {
                if (text.isBlank()) return
                loading = true
                invalidate()
                Places.search(text) {
                    results = it
                    loading = false
                    invalidate()
                }
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Where to?")
            .setShowKeyboardByDefault(true)
        if (loading) return b.setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage("Search for a place")
        for (p in results) {
            val row = Row.Builder().setTitle(p.name).setOnClickListener {
                Nav.start(carContext, p)
                screenManager.pop()
            }
            if (p.detail.isNotBlank()) row.addText(p.detail)
            list.addItem(row.build())
        }
        return b.setItemList(list.build()).build()
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
