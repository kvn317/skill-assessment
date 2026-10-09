package com.kvn317.gamemaps

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Active trip: routing, progress along the route, rerouting and voice prompts. Main thread only. */
object Nav {
    var dest: Place? = null
        private set
    var route: Route? = null
        private set
    var status = ""
        private set
    /** Index into route.steps of the upcoming maneuver. */
    var next = 0
        private set
    var toNext = 0.0
        private set
    var remaining = 0.0
        private set
    var remainingSec = 0.0
        private set
    val active get() = dest != null

    private val listeners = LinkedHashSet<() -> Unit>()
    private val fix: () -> Unit = { onFix() }
    private var loading = false
    private var lastRequest = 0L
    private var offCount = 0
    private var spokenFar = -1
    private var spokenNear = -1

    fun add(l: () -> Unit) { listeners += l }
    fun remove(l: () -> Unit) { listeners -= l }

    fun start(ctx: Context, p: Place) {
        Speech.init(ctx)
        dest = p
        route = null
        lastRequest = 0
        status = "Waiting for GPS…"
        Gps.add(ctx, fix)
        onFix()
    }

    fun stop() {
        Gps.remove(fix)
        dest = null
        route = null
        changed()
    }

    private fun onFix() {
        val l = Gps.last
        val r = route
        if (l == null || r == null) {
            if (l != null) request()
            changed()
            return
        }
        val (along, off) = r.project(l.latitude, l.longitude)
        offCount = if (off > 50) offCount + 1 else 0
        if (offCount >= 3) request()
        remaining = r.total - along
        if (remaining < 30) {
            Speech.say("You have arrived")
            stop()
            return
        }
        next = r.steps.indexOfFirst { it.start > along + 5 }.let { if (it < 0) r.steps.lastIndex else it }
        toNext = r.steps[next].start - along
        remainingSec = r.duration * remaining / r.total
        speak(r.steps[next], l.speed)
        changed()
    }

    private fun speak(s: Step, speed: Float) {
        if (next != spokenFar) {
            spokenFar = next
            if (toNext > 250) {
                Speech.say("In ${distText(toNext, spoken = true)}, ${s.instruction}")
                return
            }
        }
        if (next != spokenNear && toNext < maxOf(40.0, speed * 6.0)) {
            spokenNear = next
            Speech.say(s.instruction)
        }
    }

    /** Fetches a route from the current fix; throttled so a dead network or bad fix can't spam the server. */
    private fun request() {
        val from = Gps.last ?: return
        val to = dest ?: return
        val now = SystemClock.elapsedRealtime()
        if (loading || now - lastRequest < 10_000) return
        loading = true
        lastRequest = now
        if (route == null) status = "Finding route…"
        Route.fetch(from.latitude, from.longitude, to) { r ->
            loading = false
            if (dest !== to) return@fetch // trip ended or changed meanwhile
            if (r == null) {
                if (route == null) status = "No route found, retrying…"
                changed()
                return@fetch
            }
            route = r
            offCount = 0
            spokenFar = -1
            spokenNear = -1
            onFix()
        }
    }

    private fun changed() = listeners.toList().forEach { it() }
}

/** Spoken prompts that duck other audio, as Android Auto expects of navigation apps. */
object Speech : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var audio: AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    fun init(ctx: Context) {
        if (tts != null) return
        audio = ctx.applicationContext.getSystemService(AudioManager::class.java)
        tts = TextToSpeech(ctx.applicationContext, this).apply {
            setAudioAttributes(attrs)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { audio.abandonAudioFocusRequest(focus) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { audio.abandonAudioFocusRequest(focus) }
            })
        }
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
    }

    fun say(text: String) {
        val t = tts ?: return
        if (!ready) return
        audio.requestAudioFocus(focus)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }
}
