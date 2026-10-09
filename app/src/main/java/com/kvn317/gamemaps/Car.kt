package com.kvn317.gamemaps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
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
        override fun onCreateScreen(intent: Intent): Screen {
            handle(intent)
            return CarMapScreen(carContext)
        }

        override fun onNewIntent(intent: Intent) {
            if (intent.action == CarContext.ACTION_NAVIGATE) carContext.getCarService(ScreenManager::class.java).popToRoot()
            handle(intent)
        }

        /** "Hey Google, navigate to …" arrives as ACTION_NAVIGATE with a geo: URI. */
        private fun handle(intent: Intent) {
            if (intent.action != CarContext.ACTION_NAVIGATE) return
            val uri = intent.data ?: return
            Places.fromUri(uri) { p ->
                if (p != null) Nav.start(carContext, p)
                else CarToast.makeText(carContext, "Couldn't find that place", CarToast.LENGTH_LONG).show()
            }
        }
    }
}

/** Full-screen themed map on the car display that follows the car and shows turn-by-turn guidance. */
class CarMapScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {
    private var carTheme = Themes.all[0]
    // The map is a normal Android view shown on the car surface through a private virtual display.
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var map: GameMap? = null
    private var surfaceSize = Rect()
    private var area: Rect? = null
    private val navManager = ctx.getCarService(NavigationManager::class.java)
    private var navStarted = false
    private val onFix: () -> Unit = { map?.onLocation() }
    private val onNav: () -> Unit = {
        // Tells Android Auto (and other nav apps) whether we're guiding right now.
        if (Nav.active != navStarted) {
            navStarted = Nav.active
            if (navStarted) navManager.navigationStarted() else navManager.navigationEnded()
        }
        invalidate()
        map?.refresh()
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
            .addAction(action(title = carTheme.name) {
                carTheme = Themes.all[(Themes.all.indexOf(carTheme) + 1) % Themes.all.size]
                map?.theme = carTheme
                invalidate()
            })
            .addAction(
                if (Nav.active) action(icon = R.drawable.ic_close) { Nav.stop() }
                else action(icon = R.drawable.ic_search) { screenManager.push(CarSearchScreen(carContext)) }
            )
            .addAction(action(icon = R.drawable.ic_zoom_in) { map?.zoomBy(1) })
            .addAction(action(icon = R.drawable.ic_zoom_out) { map?.zoomBy(-1) })
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
        val surface = container.surface ?: return
        surfaceSize = Rect(0, 0, container.width, container.height)
        val vd = carContext.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("IrlGameMaps", container.width, container.height, container.dpi, surface, 0)
        display = vd
        presentation = Presentation(carContext, vd.display).apply {
            map = GameMap(context).also {
                it.theme = carTheme
                setContentView(it)
            }
            show()
        }
        map?.start()
        updatePadding()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        area = visibleArea
        updatePadding()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        map?.stop()
        map?.destroy()
        presentation?.dismiss()
        display?.release()
        map = null
        presentation = null
        display = null
    }

    /** Puts the car 70% of the way down the unobstructed area, showing more road ahead than behind. */
    private fun updatePadding() {
        val m = map ?: return
        val a = area ?: surfaceSize
        val h = surfaceSize.height().toDouble()
        val y = a.top + a.height() * 0.7
        val bottom = h - a.bottom
        m.padding = doubleArrayOf(a.left.toDouble(), 2 * y - a.bottom, (surfaceSize.width() - a.right).toDouble(), bottom)
        m.onLocation()
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
