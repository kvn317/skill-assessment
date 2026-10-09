package com.kvn317.gamemaps

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

private const val PANEL = 0xCC000000.toInt()

class MainActivity : Activity() {
    private lateinit var map: GameMap
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private lateinit var searchBox: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var arrow: ImageView
    private lateinit var cue: TextView
    private lateinit var eta: TextView
    private val onFix: () -> Unit = { map.onLocation() }
    private val onNav: () -> Unit = { showNav() }
    private val onTurn: () -> Unit = { map.refresh() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        map = GameMap(this)
        map.onLongPress = { p ->
            map.follow = true
            Nav.start(this, Place("Dropped pin", "", p.latitude, p.longitude))
        }

        query = EditText(this).apply {
            hint = "Where to? (or long-press the map)"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> find(); true }
        }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                addView(query, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(button("Go") { find() })
            })
            addView(results)
        }

        arrow = ImageView(this)
        cue = TextView(this).apply { textSize = 20f }
        eta = TextView(this)
        banner = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(arrow, LinearLayout.LayoutParams(144, 144))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 0, 24, 0)
                addView(cue)
                addView(eta)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("End") { Nav.stop() })
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(16, 16, 16, 16)
            addView(searchBox)
            addView(banner)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            addView(row(Themes.all.map { t -> t.name to { map.theme = t } }))
            addView(row(listOf(
                "−" to { map.zoomBy(-1) },
                "◎" to { map.recenter() },
                "+" to { map.zoomBy(1) },
            )).apply {
                addView(button("⬆ Heading") {}.apply {
                    setOnClickListener {
                        map.headingUp = !map.headingUp
                        text = if (map.headingUp) "⬆ Heading" else "N North"
                    }
                }, 2)
            })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(top)
            addView(map, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bottom)
        })
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 0)
        }
        if (state == null) onNewIntent(intent) // not again after rotation
    }

    /** geo: / google.navigation: links from the Assistant, Maps shares, contacts, etc. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data ?: return
        Places.fromUri(uri) { p ->
            if (p == null) {
                Toast.makeText(this, "Couldn't find that place", Toast.LENGTH_LONG).show()
                return@fromUri
            }
            map.follow = true
            Nav.start(this, p)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        Gps.add(this, onFix) // starts the feed now that it's allowed
    }

    override fun onStart() {
        super.onStart()
        map.start()
        Gps.add(this, onFix)
        Compass.add(this, onTurn)
        Nav.add(onNav)
        showNav()
    }

    override fun onStop() {
        map.stop()
        Gps.remove(onFix)
        Compass.remove(onTurn)
        Nav.remove(onNav)
        super.onStop()
    }

    override fun onDestroy() {
        map.destroy()
        super.onDestroy()
    }

    private fun find() {
        val q = query.text.toString().trim()
        if (q.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        results.removeAllViews()
        results.addView(TextView(this).apply { text = "Searching…" })
        Places.search(q) { places ->
            results.removeAllViews()
            if (places.isEmpty()) results.addView(TextView(this).apply { text = "No results" })
            for (p in places) {
                val label = if (p.detail.isBlank()) p.name else "${p.name}\n${p.detail}"
                results.addView(button(label) {
                    results.removeAllViews()
                    query.setText("")
                    map.follow = true
                    Nav.start(this, p)
                }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
            }
        }
    }

    private fun showNav() {
        searchBox.visibility = if (Nav.active) View.GONE else View.VISIBLE
        banner.visibility = if (Nav.active) View.VISIBLE else View.GONE
        map.refresh()
        val s = Nav.route?.steps?.getOrNull(Nav.next)
        if (s == null) {
            arrow.setImageDrawable(null)
            cue.text = Nav.status
            eta.text = ""
            return
        }
        arrow.setImageBitmap(arrowBitmap(s.angle))
        cue.text = "${distText(Nav.toNext)} · ${s.instruction}"
        eta.text = "${(Nav.remainingSec / 60).roundToInt()} min · ${distText(Nav.remaining)} left"
    }

    private fun button(label: String, f: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { f() }
    }

    private fun row(items: List<Pair<String, () -> Unit>>) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        for ((label, f) in items) addView(button(label, f))
    }
}
