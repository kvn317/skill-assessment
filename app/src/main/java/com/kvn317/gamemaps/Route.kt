package com.kvn317.gamemaps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round

private const val USER_AGENT = "IrlGameMaps/1.1 (github.com/kvn317/skill-assessment)"
private val io = Executors.newFixedThreadPool(2)
private val main = Handler(Looper.getMainLooper())

/** GETs [url] off the main thread; [cb] gets the body, or null on any failure, on the main thread. */
fun http(url: String, cb: (String?) -> Unit) = io.execute {
    val body = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            inputStream.bufferedReader().use { it.readText() }
        }
    } catch (e: Exception) {
        null
    }
    main.post { cb(body) }
}

class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

object Places {
    /** Photon (OSM) search, biased toward the current location. */
    fun search(q: String, cb: (List<Place>) -> Unit) {
        var url = "https://photon.komoot.io/api/?limit=5&q=" + URLEncoder.encode(q, "UTF-8")
        Gps.last?.let { url += "&lat=${it.latitude}&lon=${it.longitude}" }
        http(url) { body -> cb(body?.let { runCatching { parse(it) }.getOrNull() } ?: emptyList()) }
    }

    private fun parse(body: String): List<Place> {
        val features = JSONObject(body).getJSONArray("features")
        return (0 until features.length()).map { i ->
            val f = features.getJSONObject(i)
            val pt = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val street = listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ")
            val name = p.optString("name").ifBlank { street }.ifBlank { "Unnamed place" }
            val detail = listOf(if (name == street) "" else street, p.optString("city"), p.optString("state"))
                .filter { it.isNotBlank() }.joinToString(", ")
            Place(name, detail, pt.getDouble(1), pt.getDouble(0))
        }
    }
}

/** One maneuver. [start] is how far along the route (meters) it happens; [angle] is null at the destination. */
class Step(val instruction: String, val road: String, val angle: Int?, val carType: Int, val start: Double)

class Route(
    private val lats: DoubleArray,
    private val lons: DoubleArray,
    private val cum: DoubleArray,
    val steps: List<Step>,
    val duration: Double,
) {
    val total = cum.last()
    val mx = DoubleArray(lons.size) { TileMap.mercX(lons[it]) }
    val my = DoubleArray(lats.size) { TileMap.mercY(lats[it]) }

    /** Snaps a position to the route: (meters along it, meters off it). */
    // ponytail: scans the whole line each fix and can snap to a parallel stretch of the same route; window it if that bites
    fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val kx = M_PER_DEG * cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        var along = 0.0
        for (i in 0 until lats.size - 1) {
            val ax = (lons[i] - lon) * kx
            val ay = (lats[i] - lat) * M_PER_DEG
            val dx = (lons[i + 1] - lons[i]) * kx
            val dy = (lats[i + 1] - lats[i]) * M_PER_DEG
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best) {
                best = d
                along = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        return along to best
    }

    companion object {
        /** Driving route from the public OSRM demo server (fair use only, no uptime promise). */
        fun fetch(fromLat: Double, fromLon: Double, to: Place, cb: (Route?) -> Unit) = http(
            "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;${to.lon},${to.lat}" +
                "?overview=false&steps=true&geometries=geojson"
        ) { body -> cb(body?.let { runCatching { parse(it) }.getOrNull() }) }

        private fun parse(body: String): Route? {
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") return null
            val route = root.getJSONArray("routes").getJSONObject(0)
            val js = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            val lats = ArrayList<Double>()
            val lons = ArrayList<Double>()
            val cum = ArrayList<Double>()
            val steps = ArrayList<Step>()
            for (i in 0 until js.length()) {
                val s = js.getJSONObject(i)
                val m = s.getJSONObject("maneuver")
                val type = m.optString("type")
                val mod = m.optString("modifier")
                val road = s.optString("name").ifBlank { s.optString("ref") }
                val angle = if (type == "arrive") null else ANGLES[mod] ?: 0
                val carType = when (type) {
                    "arrive" -> Maneuver.TYPE_DESTINATION
                    "depart" -> Maneuver.TYPE_DEPART
                    else -> CAR_TYPES[angle] ?: Maneuver.TYPE_UNKNOWN
                }
                steps += Step(instruction(type, mod, road, m.optInt("exit")), road, angle, carType, cum.lastOrNull() ?: 0.0)
                val coords = s.getJSONObject("geometry").getJSONArray("coordinates")
                for (j in 0 until coords.length()) {
                    val lon = coords.getJSONArray(j).getDouble(0)
                    val lat = coords.getJSONArray(j).getDouble(1)
                    if (lats.isEmpty()) cum += 0.0
                    else if (lat == lats.last() && lon == lons.last()) continue
                    else cum += cum.last() + meters(lats.last(), lons.last(), lat, lon)
                    lats += lat
                    lons += lon
                }
            }
            if (steps.isEmpty() || lats.isEmpty()) return null
            return Route(lats.toDoubleArray(), lons.toDoubleArray(), cum.toDoubleArray(), steps, route.getDouble("duration"))
        }

        private fun instruction(type: String, mod: String, road: String, exit: Int): String {
            val onto = if (road.isBlank()) "" else " onto $road"
            return when (type) {
                "depart" -> if (road.isBlank()) "Head out" else "Head out on $road"
                "arrive" -> "Arrive at your destination"
                "roundabout", "rotary" -> if (exit > 0) "At the roundabout, take exit $exit$onto" else "Enter the roundabout"
                "fork" -> "Keep ${if ("left" in mod) "left" else "right"}$onto"
                "merge" -> "Merge$onto"
                "on ramp" -> "Take the ramp$onto"
                "off ramp" -> "Take the exit$onto"
                else -> when (mod) {
                    "", "straight" -> "Continue$onto"
                    "uturn" -> "Make a U-turn$onto"
                    else -> "Turn $mod$onto"
                }
            }
        }

        private const val M_PER_DEG = 111_320.0
        private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
            hypot((lat2 - lat1) * M_PER_DEG, (lon2 - lon1) * M_PER_DEG * cos(Math.toRadians((lat1 + lat2) / 2)))

        private val ANGLES = mapOf(
            "uturn" to 180, "sharp right" to 135, "right" to 90, "slight right" to 45,
            "straight" to 0, "slight left" to -45, "left" to -90, "sharp left" to -135,
        )
        private val CAR_TYPES = mapOf(
            0 to Maneuver.TYPE_STRAIGHT, 180 to Maneuver.TYPE_U_TURN_LEFT,
            45 to Maneuver.TYPE_TURN_SLIGHT_RIGHT, 90 to Maneuver.TYPE_TURN_NORMAL_RIGHT, 135 to Maneuver.TYPE_TURN_SHARP_RIGHT,
            -45 to Maneuver.TYPE_TURN_SLIGHT_LEFT, -90 to Maneuver.TYPE_TURN_NORMAL_LEFT, -135 to Maneuver.TYPE_TURN_SHARP_LEFT,
        )
    }
}

private val imperial = Locale.getDefault().country in setOf("US", "LR", "MM")

/** Rounded display distance as (value, androidx.car.app.model.Distance unit). */
fun roundDistance(m: Double): Pair<Double, Int> = when {
    imperial && m < 300 -> maxOf(50.0, round(m * 3.28084 / 50) * 50) to Distance.UNIT_FEET
    imperial -> round(m / 1609.344 * 10) / 10 to Distance.UNIT_MILES
    m < 1000 -> maxOf(10.0, round(m / 10) * 10) to Distance.UNIT_METERS
    else -> round(m / 100) / 10 to Distance.UNIT_KILOMETERS
}

fun distText(m: Double, spoken: Boolean = false): String {
    val (v, unit) = roundDistance(m)
    val num = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    val name = when (unit) {
        Distance.UNIT_FEET -> if (spoken) "feet" else "ft"
        Distance.UNIT_MILES -> if (spoken) (if (v == 1.0) "mile" else "miles") else "mi"
        Distance.UNIT_METERS -> if (spoken) "meters" else "m"
        else -> if (spoken) "kilometers" else "km"
    }
    return "$num $name"
}

private val arrows = HashMap<Int?, Bitmap>()

/** White maneuver arrow rotated [angle] degrees from straight ahead; a dot for the destination. */
fun arrowBitmap(angle: Int?): Bitmap = arrows.getOrPut(angle) {
    val s = 128f
    Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (angle == null) {
            c.drawCircle(s / 2, s / 2, s / 4, p)
        } else {
            c.rotate(angle.toFloat(), s / 2, s / 2)
            c.drawRect(s * 0.42f, s * 0.45f, s * 0.58f, s * 0.92f, p)
            c.drawPath(Path().apply { moveTo(s / 2, s * 0.08f); lineTo(s * 0.82f, s * 0.5f); lineTo(s * 0.18f, s * 0.5f); close() }, p)
        }
    }
}
