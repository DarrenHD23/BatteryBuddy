package com.example.fiskertemp

// ---------------------------------------------------------------------------
// The media card's own player, for when its source is not the phone or another app:
//   - a local music file (and the other files found beside it, for previous / next)
//   - an internet radio station (the free radio-browser.info directory, which lists Shoutcast and Icecast streams)
// With the default source (external player / Bluetooth) none of this runs.
// ---------------------------------------------------------------------------

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class Station(val name: String, val url: String, val logo: String, val info: String)

object InApp {
    const val EXTERNAL = 0
    const val MP3 = 1
    const val RADIO = 2

    /** Which source the card follows. Always starts as external, so the car never begins with a stream. */
    @Volatile var mode = EXTERNAL
    var title: String? = null
    var artist: String? = null
    var art: Bitmap? = null
    var playing = false
    var hasSession = false
    var onChange: (() -> Unit)? = null
    var log: (String) -> Unit = {}

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val mpx = Executors.newCachedThreadPool()     // MediaPlayer stop / release / setDataSource can wait on a stalled stream: never on the main thread
    private var ctx: Context? = null
    private var mp: MediaPlayer? = null
    private var gen = 0            // each start of the player gets a number; callbacks of older ones are ignored
    private var req = 0            // each request to load a track or station gets a number
    private var tracks: List<String> = emptyList()
    private var trackAt = 0
    private var stations: List<Station> = emptyList()
    private var stationAt = 0
    private var singleUri: Uri? = null

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private fun changed() { ui.post { onChange?.invoke() } }

    fun init(c: Context) { ctx = c.applicationContext }

    private fun release() {
        gen++
        val m = mp
        mp = null
        if (m != null) {
            try { m.setOnPreparedListener(null); m.setOnCompletionListener(null); m.setOnErrorListener(null) } catch (e: Throwable) { }
            mpx.execute { try { m.release() } catch (e: Throwable) { } }
        }
        playing = false
    }

    /** Back to the external player / Bluetooth: everything of ours is stopped and cleared. */
    fun useExternal() {
        req++
        release()
        mode = EXTERNAL
        title = null; artist = null; art = null; hasSession = false
        tracks = emptyList(); stations = emptyList(); singleUri = null
        abandonFocus()
        changed()
    }

    // one listener object for every request: a new one each time made Android tell the older one it had lost focus, which stopped the station just started
    private val focusListener = AudioManager.OnAudioFocusChangeListener { ch -> if (ch == AudioManager.AUDIOFOCUS_LOSS && mode != EXTERNAL) ui.post { pauseNow() } }

    @Suppress("DEPRECATION")
    private fun takeFocus() {
        val c = ctx ?: return
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val c = ctx ?: return
        try { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocus(focusListener) } catch (e: Exception) { }
    }

    private fun pauseNow() {
        if (mode == RADIO) { release(); changed(); return }
        try { mp?.pause() } catch (e: Exception) { }
        playing = false
        changed()
    }

    private fun launch(prep: (MediaPlayer) -> Unit) {
        release()
        val my = gen
        takeFocus()
        val m = MediaPlayer()
        mp = m
        try {
            m.setAudioAttributes(attrs)
            m.setOnPreparedListener { if (gen == my) { try { it.start() } catch (e: Exception) { }; playing = true; changed() } }
            m.setOnCompletionListener { if (gen == my && mode == MP3) ui.post { next() } }
            m.setOnErrorListener { _, what, extra ->
                if (gen == my) {
                    log("player error $what/$extra")
                    playing = false
                    if (mode == RADIO) artist = "This station could not be played"
                    changed()
                }
                true
            }
        } catch (e: Exception) {
            log("player: " + e.message)
            playing = false
            artist = "Could not play this"
            changed()
            return
        }
        // opening the stream can wait on the network, so it is not done on the main thread
        mpx.execute {
            try {
                if (gen != my) return@execute
                prep(m)
                if (gen != my) return@execute
                m.prepareAsync()
            } catch (e: Throwable) {
                if (gen == my) ui.post {
                    if (gen != my) return@post
                    log("player: " + e.message)
                    playing = false
                    artist = "Could not play this"
                    changed()
                }
            }
        }
    }

    // ---- local files -------------------------------------------------------------------------------------------

    /** Play [paths][index] and keep the rest of the list for previous / next. */
    fun playFiles(paths: List<String>, index: Int) {
        if (paths.isEmpty()) return
        mode = MP3; stations = emptyList(); singleUri = null
        tracks = paths; trackAt = index.coerceIn(0, paths.size - 1)
        loadTrack()
    }

    /** One file chosen with Android's own picker. */
    fun playUri(uri: Uri, name: String) {
        mode = MP3; tracks = emptyList(); stations = emptyList(); singleUri = uri
        val my = ++req
        release()
        title = name; artist = null; art = null; hasSession = true; changed()
        io.execute {
            val c = ctx
            var t: String? = null; var a: String? = null; var b: Bitmap? = null
            if (c != null) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(c, uri)
                    t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    b = r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } catch (e: Exception) { } finally { try { r.release() } catch (e: Exception) { } }
            }
            ui.post {
                if (req != my) return@post
                if (!t.isNullOrBlank()) title = t
                artist = a?.takeIf { it.isNotBlank() }; art = b
                launch { m -> m.setDataSource(ctx!!, uri) }
                changed()
            }
        }
    }

    private fun loadTrack() {
        val path = tracks.getOrNull(trackAt) ?: return
        val my = ++req
        release()
        val fileName = path.substringAfterLast('/').substringBeforeLast('.')
        title = fileName; artist = path.substringBeforeLast('/').substringAfterLast('/'); art = null; hasSession = true
        changed()
        io.execute {
            val c = ctx ?: return@execute
            // the app often cannot read the music folders itself, so the file is copied where it can (as root)
            var f = File(path)
            if (!f.canRead()) {
                val tmp = File(c.cacheDir, "track${my % 2}.audio")
                val q = "'" + path.replace("'", "'\\''") + "'"
                Root.exec("cp $q ${tmp.path} && chmod 666 ${tmp.path}", 30)
                f = tmp
            }
            var t: String? = null; var a: String? = null; var b: Bitmap? = null
            if (f.exists()) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.path)
                    t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    b = r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } catch (e: Exception) { } finally { try { r.release() } catch (e: Exception) { } }
            }
            ui.post {
                if (req != my) return@post
                if (!f.exists()) { artist = "That file could not be opened"; changed(); return@post }
                if (!t.isNullOrBlank()) title = t
                if (!a.isNullOrBlank()) artist = a
                art = b
                launch { m -> m.setDataSource(f.path) }
                changed()
            }
        }
    }

    // ---- radio -------------------------------------------------------------------------------------------------

    fun playStations(list: List<Station>, index: Int) {
        if (list.isEmpty()) return
        mode = RADIO; tracks = emptyList(); singleUri = null
        stations = list; stationAt = index.coerceIn(0, list.size - 1)
        loadStation()
    }

    private fun loadStation() {
        val s = stations.getOrNull(stationAt) ?: return
        val my = ++req
        release()
        title = s.name; artist = s.info.ifEmpty { "Internet radio" }; art = null; hasSession = true
        changed()
        launch { m -> m.setDataSource(s.url) }
        if (s.logo.isNotEmpty()) Logos.load(s.logo, 400) { b -> if (req == my && b != null) { art = b; changed() } }
    }

    // ---- controls ----------------------------------------------------------------------------------------------

    fun next() {
        when (mode) {
            MP3 -> if (tracks.isNotEmpty()) { trackAt = (trackAt + 1) % tracks.size; loadTrack() }
            RADIO -> if (stations.isNotEmpty()) { stationAt = (stationAt + 1) % stations.size; loadStation() }
        }
    }

    fun prev() {
        when (mode) {
            MP3 -> if (tracks.isNotEmpty()) { trackAt = (trackAt - 1 + tracks.size) % tracks.size; loadTrack() } else mp?.seekTo(0)
            RADIO -> if (stations.isNotEmpty()) { stationAt = (stationAt - 1 + stations.size) % stations.size; loadStation() }
        }
    }

    fun toggle() {
        when {
            playing -> pauseNow()
            mode == RADIO && stations.isNotEmpty() -> loadStation()
            mode == MP3 && mp != null -> { takeFocus(); try { mp?.start(); playing = true } catch (e: Exception) { }; changed() }
            mode == MP3 && tracks.isNotEmpty() -> loadTrack()
            mode == MP3 && singleUri != null -> playUri(singleUri!!, title ?: "")
        }
    }
}

/** The radio-browser.info directory: free, no key. It lists Shoutcast and Icecast streams with their logos. */
object Radio {
    private val hosts = listOf("de1", "nl1", "at1")

    /** [path] is the part after the host, for example "/json/stations/topvote/40". Null if no server answered. */
    fun fetch(path: String): List<Station>? {
        for (h in hosts) {
            try {
                val conn = URL("https://$h.api.radio-browser.info$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000; conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "FiskerBatteryBuddy/1.0")
                val txt = try { String(readCapped(conn.inputStream, 4_000_000), Charsets.UTF_8) } finally { try { conn.disconnect() } catch (e: Throwable) { } }
                return parse(txt)
            } catch (e: Throwable) { }
        }
        return null
    }

    fun top(): List<Station>? = fetch("/json/stations/topvote/60?hidebroken=true")
    fun byTag(tag: String): List<Station>? =
        fetch("/json/stations/search?tag=" + URLEncoder.encode(tag, "UTF-8") + "&limit=60&hidebroken=true&order=votes&reverse=true")
    fun search(q: String): List<Station>? =
        fetch("/json/stations/search?name=" + URLEncoder.encode(q, "UTF-8") + "&limit=60&hidebroken=true&order=votes&reverse=true")

    private fun parse(txt: String): List<Station> {
        val arr = JSONArray(txt)
        val out = ArrayList<Station>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            var url = o.optString("url_resolved").trim()
            if (url.isEmpty()) url = o.optString("url").trim()
            if (name.isEmpty() || url.isEmpty() || !seen.add(name.lowercase())) continue
            val bits = ArrayList<String>()
            val cc = o.optString("countrycode").trim(); if (cc.isNotEmpty()) bits.add(cc)
            val codec = o.optString("codec").trim(); val br = o.optInt("bitrate", 0)
            if (codec.isNotEmpty()) bits.add(if (br > 0) "$codec $br kbps" else codec)
            val tag = o.optString("tags").split(',').firstOrNull()?.trim().orEmpty(); if (tag.isNotEmpty()) bits.add(tag)
            out.add(Station(name, url, o.optString("favicon").trim(), bits.joinToString(" • ")))
        }
        return out
    }
}

/** Reads a stream but gives up past [max] bytes, so a huge or endless answer cannot fill the memory. */
fun readCapped(inp: java.io.InputStream, max: Int): ByteArray {
    inp.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw java.io.IOException("too large")
        }
        return out.toByteArray()
    }
}

/** Small station logos: loaded in the background and kept in memory. */
object Logos {
    private val cache = android.util.LruCache<String, Bitmap>(120)
    private val bad = HashSet<String>()
    private val io = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    fun load(url: String, maxPx: Int, done: (Bitmap?) -> Unit) {
        val hit = cache.get(url)
        if (hit != null) { done(hit); return }
        if (synchronized(bad) { url in bad } || !(url.startsWith("http://") || url.startsWith("https://"))) { done(null); return }
        io.execute {
            var b: Bitmap? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 7000; conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "FiskerBatteryBuddy/1.0")
                val bytes = try { readCapped(conn.inputStream, 1_500_000) } finally { try { conn.disconnect() } catch (e: Throwable) { } }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var ss = 1
                while (bounds.outWidth / (ss * 2) >= maxPx && bounds.outHeight / (ss * 2) >= maxPx) ss *= 2
                b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss })
            } catch (e: Throwable) { }
            if (b != null) cache.put(url, b) else synchronized(bad) { bad.add(url) }
            ui.post { done(b) }
        }
    }
}

/** The user's favourite stations, kept in the app's settings. */
object Favs {
    private const val KEY = "radioFavs"

    @Volatile private var cache: List<Station>? = null

    fun load(c: Context): MutableList<Station> {
        cache?.let { return ArrayList(it) }
        val out = ArrayList<Station>()
        try {
            val arr = JSONArray(c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Station(o.optString("name"), o.optString("url"), o.optString("logo"), o.optString("info")))
            }
        } catch (e: Exception) { }
        cache = ArrayList(out)
        return out
    }

    private fun save(c: Context, list: List<Station>) {
        cache = ArrayList(list)
        val arr = JSONArray()
        for (s in list) arr.put(org.json.JSONObject().put("name", s.name).put("url", s.url).put("logo", s.logo).put("info", s.info))
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun has(c: Context, s: Station): Boolean { val l = cache ?: load(c); return l.any { it.url == s.url } }

    /** Adds the station, or takes it away if it is already a favourite. */
    fun toggle(c: Context, s: Station) {
        val l = load(c)
        if (l.any { it.url == s.url }) l.removeAll { it.url == s.url } else l.add(s)
        save(c, l)
    }
}
