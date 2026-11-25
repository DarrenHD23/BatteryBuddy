package com.example.fiskertemp

// ---------------------------------------------------------------------------
// Android app: read battery values from the Fisker Ocean BMS through a
// vLinker FD+ (ELM327/STN-compatible, dual-mode Bluetooth 3.0 + BLE 4.0).
//
// The FD+ advertises two ways:
//   "vLinker FD-Android"  -> Bluetooth Classic (SPP)   <- tried first
//   "vLinker FD-iOS"      -> Bluetooth LE (GATT)       <- used as fallback / when selected
// Pair "vLinker FD-Android" in Android's Bluetooth settings (PIN 1234 or 0000 if asked).
// If it doesn't show up, use "Scan BLE" in the app to find the BLE one.
//
// AndroidManifest.xml needs:
//   <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
//                    android:usesPermissionFlags="neverForLocation"/>
//   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30"/>
//
// The BMS address (ATSH7E1 / ATCRA7E9) and the DIDs in PRESETS come from the
// Fisker-Ocean-Apps community repo and have not been verified by me against a
// car. Check each reading against the car's own display before trusting it.
// This app only sends read requests (service 01 / 22).
//
// NOTE: I could not test this with a physical FD+, so the transport code
// (especially the BLE fallback) is untested on real hardware.
// ---------------------------------------------------------------------------

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.content.IntentFilter
import java.io.File
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.util.TypedValue
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** A read-only service-22 request to the BMS (values supplied by the user from the Fisker-Ocean-Apps repo). */
data class Preset(
    val name: String, val request: String, val byteIndex: Int, val byteLen: Int,
    val scale: Double, val offset: Double, val signed: Boolean, val unit: String
)

val PRESETS = listOf(
    Preset("Battery temp",     "222038", 0, 2, 0.5,   -50.0, false, "°C"),  // (A*256+B)/2-50
    Preset("Pack voltage",     "222003", 0, 2, 0.1,   0.0,   false, "V"),   // (A*256+B)/10
    // "signed word at bytes 3 and 4": assumed 1-based data bytes -> 0-based index 2. Verify against a known reading.
    Preset("Pack current",     "222016", 2, 2, 0.1,   0.0,   true,  "A"),
    Preset("State of charge",  "222050", 0, 2, 0.1,   0.0,   false, "%"),
    Preset("Cell V max",       "222137", 0, 2, 1.0,   0.0,   false, "mV"),
    Preset("Cell V min",       "222138", 0, 2, 1.0,   0.0,   false, "mV"),
    Preset("12V battery (unconfirmed)", "222130", 0, 2, 0.001, 0.0, false, "V"),
)

/** Gradient background themes selectable in Settings. */
data class AppTheme(val name: String, val top: Int, val bottom: Int)

val THEMES = listOf(
    AppTheme("Ocean Blue", Color.parseColor("#1E3C72"), Color.parseColor("#2A98D8")),
    AppTheme("Royal Purple", Color.parseColor("#41295A"), Color.parseColor("#9D50BB")),
    AppTheme("Rose Pink", Color.parseColor("#C2185B"), Color.parseColor("#FF80AB")),
    AppTheme("Slate Grey", Color.parseColor("#232526"), Color.parseColor("#6E7B8B")),
)

/**
 * Circular battery-temperature gauge. The ring fills clockwise from the top and its
 * colour runs blue -> green -> yellow -> orange -> red along the ring, so the end of the
 * filled arc is the colour for the current temperature. Scale is always in Celsius;
 * only the number in the middle switches to Fahrenheit.
 */
class TempGaugeView(ctx: Context) : View(ctx) {
    // temperature (C) -> how much of the ring is filled
    private val fillPts = listOf(-10.0 to 0.02, 0.0 to 0.05, 15.0 to 0.30, 22.5 to 0.50,
        30.0 to 0.80, 40.0 to 0.95, 45.0 to 1.0)
    // temperature (C) -> colour
    private val colorPts = listOf(0.0 to Color.parseColor("#2979FF"), 7.5 to Color.parseColor("#00BFA5"),
        15.0 to Color.parseColor("#FFD600"), 22.5 to Color.parseColor("#FF9100"),
        30.0 to Color.parseColor("#FF5722"), 40.0 to Color.parseColor("#E53935"))

    private var celsius: Double? = null
    private var fahrenheit = false
    private var shown = 0f
    private var anim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(55, 255, 255, 255) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255); textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun fractionFor(c: Double): Float {
        if (c <= fillPts.first().first) return fillPts.first().second.toFloat()
        if (c >= fillPts.last().first) return 1f
        for (i in 0 until fillPts.size - 1) {
            val (t0, f0) = fillPts[i]; val (t1, f1) = fillPts[i + 1]
            if (c <= t1) return (f0 + (f1 - f0) * (c - t0) / (t1 - t0)).toFloat()
        }
        return 1f
    }

    private fun tempFor(f: Double): Double {
        if (f <= fillPts.first().second) return fillPts.first().first
        for (i in 0 until fillPts.size - 1) {
            val (t0, f0) = fillPts[i]; val (t1, f1) = fillPts[i + 1]
            if (f <= f1) return t0 + (t1 - t0) * (f - f0) / (f1 - f0)
        }
        return fillPts.last().first
    }

    private fun colorAt(c: Double): Int {
        if (c <= colorPts.first().first) return colorPts.first().second
        if (c >= colorPts.last().first) return colorPts.last().second
        for (i in 0 until colorPts.size - 1) {
            val (t0, c0) = colorPts[i]; val (t1, c1) = colorPts[i + 1]
            if (c <= t1) {
                val k = ((c - t0) / (t1 - t0)).toFloat()
                return Color.rgb(
                    (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * k).toInt(),
                    (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * k).toInt(),
                    (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * k).toInt())
            }
        }
        return colorPts.last().second
    }

    /** Pass null for "no reading". */
    fun setReading(c: Double?, useF: Boolean) {
        celsius = c; fahrenheit = useF
        val target = if (c == null) 0f else fractionFor(c)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 500
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val n = 72
        val colors = IntArray(n) { colorAt(tempFor(it / (n - 1).toDouble())) }
        val pos = FloatArray(n) { it / (n - 1).toFloat() }
        arc.shader = SweepGradient(w / 2f, h / 2f, colors, pos)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val stroke = w * 0.075f
        val r = minOf(w, h) / 2f - stroke
        val cx = w / 2f; val cy = h / 2f
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawCircle(cx, cy, r, track)
        if (shown > 0.001f) {
            canvas.save()
            canvas.rotate(-90f, cx, cy)                 // start the fill at 12 o'clock
            canvas.drawArc(rect, 0f, 360f * shown, false, arc)
            canvas.restore()
        }
        val c = celsius
        val txt = if (c == null) "--" else if (fahrenheit) String.format("%.1f\u00B0F", c * 9.0 / 5.0 + 32.0)
                  else String.format("%.1f\u00B0C", c)
        big.textSize = r * 0.5f
        val tw = big.measureText(txt)
        if (tw > r * 1.6f) big.textSize = big.textSize * r * 1.6f / tw
        val fm = big.fontMetrics
        canvas.drawText(txt, cx, cy - (fm.ascent + fm.descent) / 2f - r * 0.08f, big)
        small.textSize = r * 0.16f
        canvas.drawText("Battery temp", cx, cy + r * 0.42f, small)
    }
}

/**
 * Estimated range impact bar. The scale is in PERCENT, not temperature: -20% on the left
 * (red, biggest loss) to 0% on the right (green, no loss). The marker sits at the estimated
 * range impact for the current battery temperature. The impact numbers are rough estimates:
 * given points are 0 C -20%, 10 C -10%, 20-25 C 0%, 30 C -2%, 40 C -12%; everything in
 * between is a straight line and values outside 0-40 C are extended at about 1% per degree.
 */
class RangeBarView(ctx: Context) : View(ctx) {
    private val impactPts = listOf(-20.0 to -40.0, -10.0 to -30.0, 0.0 to -20.0, 10.0 to -10.0,
        20.0 to 0.0, 25.0 to 0.0, 30.0 to -2.0, 40.0 to -12.0, 50.0 to -22.0)
    private val minPct = -20.0
    private val maxPct = 0.0
    private val barStops = listOf(0f to Color.parseColor("#E53935"), 0.33f to Color.parseColor("#FF9100"),
        0.66f to Color.parseColor("#FFD600"), 1f to Color.parseColor("#43A047"))

    private var impact: Double? = null
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private var shader: LinearGradient? = null

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobInner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private val rect = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun impactFor(c: Double): Double {
        if (c <= impactPts.first().first) return impactPts.first().second
        if (c >= impactPts.last().first) return impactPts.last().second
        for (i in 0 until impactPts.size - 1) {
            val (t0, v0) = impactPts[i]; val (t1, v1) = impactPts[i + 1]
            if (c <= t1) return v0 + (v1 - v0) * (c - t0) / (t1 - t0)
        }
        return impactPts.last().second
    }

    private fun barColorAt(f: Float): Int {
        for (i in 0 until barStops.size - 1) {
            val (f0, c0) = barStops[i]; val (f1, c1) = barStops[i + 1]
            if (f <= f1) {
                val k = ((f - f0) / (f1 - f0)).coerceIn(0f, 1f)
                return Color.rgb(
                    (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * k).toInt(),
                    (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * k).toInt(),
                    (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * k).toInt())
            }
        }
        return barStops.last().second
    }

    /** Takes the battery temperature in Celsius (null = no reading). The unit setting does not affect this bar. */
    fun setReading(c: Double?, @Suppress("UNUSED_PARAMETER") useF: Boolean) {
        impact = c?.let { impactFor(it) }
        val target = impact?.let { ((it - minPct) / (maxPct - minPct)).coerceIn(0.0, 1.0).toFloat() } ?: 0f
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 500
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val colors = IntArray(barStops.size) { barStops[it].second }
        val p = FloatArray(barStops.size) { barStops[it].first }
        val pad = dp(18f)
        shader = LinearGradient(pad, 0f, w - pad, 0f, colors, p, android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val barL = dp(18f); val barR = w - dp(18f)
        val barH = dp(10f); val barCy = dp(52f)
        val v = impact

        val impactTxt = if (v == null) "--" else {
            val r = Math.round(v).toInt()
            if (r == 0) "0%" else if (r > 0) "+$r%" else "-${-r}%"
        }
        title.textSize = sp(14f)
        canvas.drawText("Estimated range impact: $impactTxt", w / 2f, dp(22f), title)

        bar.shader = shader
        rect.set(barL, barCy - barH / 2f, barR, barCy + barH / 2f)
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, bar)

        if (v != null) {
            val x = barL + (barR - barL) * pos
            knobInner.color = barColorAt(pos)
            canvas.drawCircle(x, barCy, dp(11f), knobOuter)
            canvas.drawCircle(x, barCy, dp(7f), knobInner)
        }

        tick.textSize = sp(11f)
        val y = barCy + dp(30f)
        tick.textAlign = Paint.Align.LEFT;   canvas.drawText("-20%", barL, y, tick)
        tick.textAlign = Paint.Align.CENTER; canvas.drawText("-10%", w / 2f, y, tick)
        tick.textAlign = Paint.Align.RIGHT;  canvas.drawText("0%", barR, y, tick)
    }
}

/** Byte queue fed by BLE notifications; only read when available() > 0. */
private class QueueInputStream : InputStream() {
    private val q = ConcurrentLinkedQueue<Int>()
    fun push(b: ByteArray) { for (x in b) q.add(x.toInt() and 0xFF) }
    override fun available() = q.size
    override fun read(): Int = q.poll() ?: -1
}

@Suppress("DEPRECATION")
/** True once this run of the app has done its start-up split screen, so a rebuilt screen never repeats it. */
private var startSplitDone = false
/** The caution notice has been agreed to in this driving session (lives as long as the app process). */
private var cautionAgreedThisRun = false

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val cccUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var gatt: BluetoothGatt? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var polling = false

    private lateinit var deviceSpinner: Spinner
    private lateinit var presetSpinner: Spinner
    private lateinit var headerField: EditText
    private lateinit var filterField: EditText
    private lateinit var requestField: EditText
    private lateinit var byteIndexField: EditText
    private lateinit var byteLenField: EditText
    private lateinit var scaleField: EditText
    private lateinit var offsetField: EditText
    private lateinit var signedBox: CheckBox
    private lateinit var resultView: TextView
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var dotView: View
    private lateinit var canDot: View
    private lateinit var canStatusView: TextView
    private lateinit var canInfoView: TextView
    private lateinit var dongleCard: LinearLayout
    private lateinit var canCard: LinearLayout
    private lateinit var advCard: LinearLayout
    private lateinit var readAllBtn: Button
    private lateinit var pollBtn: Button
    private var useCan = false
    private var pulse: PulseCan? = null
    @Volatile private var canSnap = CanSnapshot()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tempGauge: TempGaugeView
    private lateinit var rangeBar: RangeBarView
    private var lastTempC: Double? = null
    @Volatile private var connected = false
    private lateinit var protocolField: EditText
    private val devices = mutableListOf<BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        EventLog.add("app start (" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }) + ")")
        // a preset ticked "Load on startup" is put in place before the screen is built, once per start of the app (not again when the screen is only rebuilt)
        if (savedInstanceState == null && !startupPresetDone) {
            startupPresetDone = true
            try {
                val sn = prefs().getString("startupPreset", null)
                val hit = if (sn == null) null else readPresets().firstOrNull { it.first.equals(sn, true) }
                if (hit != null) { writePresetToPrefs(hit.second); prefs().edit().putString("currentPreset", hit.first).apply(); EventLog.add("startup preset loaded: ${hit.first}") }
            } catch (e: Exception) { EventLog.add("startup preset failed: ${e.message}") }
        }
        InApp.init(this); InApp.log = { m -> EventLog.add(m) }; InApp.onChange = { refreshMediaCards() }
        logLastExits()
        logBootInfo()
        // a crash writes its stack trace to the event log before the app goes down
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try { EventLog.add("CRASH in thread '${th.name}':\n" + ex.stackTraceToString().lines().take(16).joinToString("\n")) } catch (_: Throwable) {}
            prevHandler?.uncaughtException(th, ex)
        }
        startHeartbeat()
        title = "Fisker Battery Buddy"
        useFahrenheit = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("fahrenheit", false)
        buildUi()
        val saved = getSharedPreferences("prefs", MODE_PRIVATE).getString("source", null)
        applySource(when (saved) { "pulse" -> true; "dongle" -> false; else -> PulseCan.available() })
        val reopenDash = prefs().getBoolean("reopenDash", false)      // a preset was just loaded: back to the dashboard
        if (reopenDash) prefs().edit().remove("reopenDash").apply()
        if (reopenDash || getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dashDefault", false)) showDashboard(true)
        ui.post { showDisclaimerIfNeeded() }
        // optionally go into split screen right after a fresh start (not when the screen is only being rebuilt)
        val startSplit = prefs().getString("startSplit", "")
        if (savedInstanceState == null && !splitOnStartDone && (startSplit == "spotify" || startSplit == "maps" || (startSplit == "custom" && !prefs().getString("startSplitPkg", "").isNullOrEmpty()))) {
            splitOnStartDone = true
            ui.postDelayed({
                if (!isInMultiWindowMode && !isFinishing) {
                    EventLog.add("start option: split screen with $startSplit at the bottom")
                    if (startSplit == "custom") { pendingPkg = prefs().getString("startSplitPkg", "") ?: ""; openLower(-1) }
                    else openLower(if (startSplit == "spotify") 1 else 0)
                }
            }, 2500)
        }
    }

    private fun disclaimerParts(): Pair<String, String>? {
        val raw = try { assets.open("disclaimer.txt").bufferedReader().readText() } catch (e: Exception) { return null }
        val lines = raw.trim().lines()
        return Pair(lines.firstOrNull()?.trim().orEmpty(), lines.drop(1).filter { it.isNotBlank() }.joinToString("\n\n"))
    }

    /** Settings, About: who made the app and who helped, with a button to read the disclaimer again. */
    private fun showAbout() {
        val tv = TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#E6EBF2")); setPadding(dp(22), dp(10), dp(22), dp(10)); setLineSpacing(0f, 1.12f)
            text = "Fisker BatteryBuddy (+Dashboard)\n\nDesigned by DarrenHD23\n\n" +
                "Thank you to Majd (otherwise none of this would be possible), Ben Kay, Moistpope, and sunnypilot for writing Oceanscape for which this gave me inspiration, JoseDB for suggesting the 3D model and Layout 2 graphics and bug reports, Gabriello Gambale for technical advice and help with the main BatteryBuddy app, and several other early adopters and testers for their suggestions and bug reports.\n\n" +
                "Please remember to KEEP your EYES on the ROAD and enjoy driving your Fisker Ocean!  Safety First!  Read the Disclaimer!  It is for your safety!"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv) }
        fun btn(label: String, onTap: () -> Unit) = col.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onTap() } }, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(20); rightMargin = dp(20); topMargin = dp(6) })
        btn("Read the Disclaimer") { showDisclaimerReadOnly() }
        btn("Read the License") { showAssetText("License", "LICENSE.txt") }
        btn("Read the README") { showAssetText("README", "README.txt") }
        btn("Read the Third-party notice") { showAssetText("Third-party notice", "THIRD_PARTY_NOTICE.txt") }
        darkDialog().setTitle("About").setView(ScrollView(this).apply { addView(col) }).setPositiveButton("Close", null).showDark()
    }

    /** Shows one of the project's text files (bundled in the app) in a scrolling window. */
    private fun showAssetText(title: String, asset: String) {
        val txt = try { assets.open(asset).bufferedReader().readText().trim() } catch (e: Exception) { toast("$title is missing from this build."); return }
        val tv = TextView(this).apply { text = txt; textSize = 14f; setTextColor(Color.parseColor("#E6EBF2")); setPadding(dp(20), dp(8), dp(20), dp(8)); setLineSpacing(0f, 1.1f); setTextIsSelectable(true) }
        darkDialog().setTitle(title).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Close", null).showDark()
    }

    private fun showDisclaimerReadOnly() {
        val (title, body) = disclaimerParts() ?: run { toast("The disclaimer text is missing."); return }
        val tv = TextView(this).apply { text = body; textSize = 14f; setTextColor(Color.parseColor("#E6EBF2")); setPadding(dp(20), dp(8), dp(20), dp(8)); setLineSpacing(0f, 1.1f) }
        val at = prefs().getString("disclaimerAt", null)
        val note = if (at != null) "\n\nAccepted on $at." else ""
        tv.text = body + note
        darkDialog().setTitle(title).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Close", null).showDark()
    }

    /** The legal disclaimer, shown on the very first start: it must be scrolled to the end and accepted, or the app exits. */
    private fun showDisclaimerIfNeeded() {
        if (prefs().getBoolean("disclaimerOk", false) || isFinishing) return
        val raw = try { assets.open("disclaimer.txt").bufferedReader().readText() } catch (e: Exception) { EventLog.add("disclaimer text missing: ${e.message}"); return }
        val lines = raw.trim().lines()
        val title = lines.firstOrNull()?.trim().orEmpty()
        val body = lines.drop(1).filter { it.isNotBlank() }.joinToString("\n\n")
        val tv = TextView(this).apply { text = body; textSize = 14f; setTextColor(Color.parseColor("#E6EBF2")); setPadding(dp(20), dp(8), dp(20), dp(8)); setLineSpacing(0f, 1.1f) }
        val sv = ScrollView(this).apply { addView(tv) }
        val cb = CheckBox(this).apply { text = "I understand and accept."; isEnabled = false; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val hint = TextView(this).apply { text = "Scroll to the end to continue."; textSize = 12f; setTextColor(Color.parseColor("#9FB3C4")); setPadding(dp(24), dp(4), dp(20), 0) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (resources.displayMetrics.heightPixels * 0.68f).toInt()      // tall, like the About screen's copy
            addView(sv, LinearLayout.LayoutParams(-1, 0, 1f)); addView(hint); addView(cb, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(16) })
        }
        val d = darkDialog().setTitle(title).setView(col).setCancelable(false)
            .setPositiveButton("Continue", null).setNegativeButton("Do Not Accept", null).showDark()
        d.setCanceledOnTouchOutside(false)
        val ok = d.getButton(AlertDialog.BUTTON_POSITIVE); ok.isEnabled = false
        d.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.86f).toInt())
        fun atEnd() { if (!sv.canScrollVertically(1)) { cb.isEnabled = true; hint.visibility = View.GONE } }
        sv.setOnScrollChangeListener { _, _, _, _, _ -> atEnd() }
        sv.viewTreeObserver.addOnGlobalLayoutListener { atEnd() }      // short text that fits without scrolling
        cb.setOnCheckedChangeListener { _, on -> ok.isEnabled = on }
        ok.setOnClickListener {
            val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())
            prefs().edit().putBoolean("disclaimerOk", true).putString("disclaimerAt", at).putInt("disclaimerVer", 1).commit()
            try { java.io.File(filesDir, "disclaimer_accepted.txt").writeText("Legal disclaimer (version 1) accepted on $at\n") } catch (_: Exception) {}
            EventLog.add("legal disclaimer accepted by the user ($at)")
            d.dismiss()
        }
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            EventLog.add("legal disclaimer declined by the user: app exits")
            d.dismiss(); finishAffinity()
            ui.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
        }
    }

    private var splitOnStartDone: Boolean
        get() = startSplitDone
        set(v) { startSplitDone = v }

    // ------------------------------------------------------------------ UI
    private var themeIndex = 0
    private lateinit var rootScroll: ScrollView
    private lateinit var advancedBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ caution notice
    private lateinit var cautionView: FrameLayout
    private lateinit var climateView: ClimateScreenView
    private lateinit var mediaCard: MediaCardView
    private var mediaWatcher: MediaWatcher? = null
    private var climateTopDp = 0                         // full screen: where the Climate panel starts (dp from the top), 0 = not in the full-screen order
    private var tempPending: Double? = null
    private var tempPendingAt = 0L
    private var wasFullOrder = false                    // the last layout was the full-screen order (to open Climate when entering it)
    private var fullOrderNow = false                     // the window is in the full-screen order (media card + Climate panel)
    private fun relayoutDash() { layoutKey = ""; if (::dashRoot.isInitialized && dashRoot.width > 0) { val dn = resources.displayMetrics.density; adaptDashLayout((dashRoot.width / dn).toInt(), (dashRoot.height / dn).toInt()) } }
    private var climateHDp = 0                           // ... and how tall it is
    private lateinit var climateBtn: TextView
    private lateinit var mediaBtn: TextView
    private lateinit var mediaPopup: MediaCardView
    private var lastReadySeen: Boolean? = null

    /** A full-window notice over everything, with a caution symbol and an Agree button. */
    private fun buildCaution(): FrameLayout {
        val amber = Color.parseColor("#FFB300")
        val f = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(215, 0, 0, 0)); isClickable = true; isFocusable = true
            visibility = View.GONE; alpha = 0f
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(24), dp(26), dp(26))
            background = GradientDrawable().apply { setColor(Color.parseColor("#1B2030")); cornerRadius = dp(20).toFloat(); setStroke(dp(2), amber) }
            addView(CautionSymbolView(context), LinearLayout.LayoutParams(dp(84), dp(84)))
            addView(TextView(context).apply { text = "Caution!"; textSize = 28f; setTextColor(amber); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            addView(TextView(context).apply {
                text = "This is beta software, use at your own risk.\n\nKeep your eyes on the road at all times!"
                textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
            addView(Button(context).apply {
                text = "Agree"; isAllCaps = false; textSize = 18f; setTextColor(Color.parseColor("#1B2030")); typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { setColor(amber); cornerRadius = dp(26).toFloat() }
                setOnClickListener {
                    EventLog.add("Caution notice: user pressed Agree")
                    cautionAgreedThisRun = true
                    isEnabled = false
                    f.animate().alpha(0f).setDuration(450).withEndAction { f.visibility = View.GONE; isEnabled = true }.start()
                }
            }, LinearLayout.LayoutParams(dp(200), dp(52)).apply { topMargin = dp(22) })
            addView(Button(context).apply {
                text = "Don't Agree"; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(26).toFloat(); setStroke(dp(1), Color.argb(160, 255, 255, 255)) }
                setOnClickListener {
                    EventLog.add("Caution notice: user pressed Don't Agree, closing the app")
                    isEnabled = false
                    pulse?.stop()
                    finishAndRemoveTask()
                }
            }, LinearLayout.LayoutParams(dp(200), dp(46)).apply { topMargin = dp(10) })
        }
        val holder = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(16)); addView(card, LinearLayout.LayoutParams(dp(420), -2)) }
        f.addView(ScrollView(this).apply { isFillViewport = true; addView(holder, ViewGroup.LayoutParams(-1, -2)) }, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private fun showCaution() {
        if (!::cautionView.isInitialized) return
        EventLog.add("Caution notice shown")
        cautionView.bringToFront()
        cautionView.animate().cancel()
        cautionView.visibility = View.VISIBLE
        cautionView.alpha = 0f
        cautionView.animate().alpha(1f).setDuration(600).start()
    }

    /** The settings options (radio buttons, check boxes) are just white text: no grey background or ripple behind them. */
    private fun clearOptionBackgrounds(v: View) {
        if (v is android.widget.CompoundButton) { v.background = null; v.setTextColor(Color.WHITE) }
        if (v is ViewGroup) for (i in 0 until v.childCount) clearOptionBackgrounds(v.getChildAt(i))
    }

    private fun roundedBg(alpha: Int, radiusDp: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(alpha, 255, 255, 255)); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun LinearLayout.add(v: View, topDp: Int = 8) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(topDp) })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.argb(50, 255, 255, 255)) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(14) }
    }

    private fun label(t: String, size: Float = 13f, bold: Boolean = false): TextView = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor("#E6FFFFFF"))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(t: String, onClick: () -> Unit): Button = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE)
        background = roundedBg(70, 12)
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, default: String = ""): EditText = EditText(this).apply {
        this.hint = hint; setText(default); setSingleLine(); textSize = 14f
        setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#99FFFFFF"))
        background = roundedBg(30, 10); setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun whiteAdapter(items: List<String>): ArrayAdapter<String> {
        val a = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getView(pos, cv, parent) as TextView).apply { setTextColor(Color.WHITE) }
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getDropDownView(pos, cv, parent) as TextView).apply {
                    setTextColor(Color.BLACK); setPadding(dp(16), dp(12), dp(16), dp(12))
                }
        }
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return a
    }

    private fun buildUi() {
        deviceSpinner = Spinner(this).apply { background = roundedBg(30, 10); setPadding(dp(8), dp(10), dp(8), dp(10)) }
        presetSpinner = Spinner(this).apply {
            adapter = whiteAdapter(PRESETS.map { it.name })
            background = roundedBg(30, 10); setPadding(dp(8), dp(10), dp(8), dp(10))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyPreset(PRESETS[pos])
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        protocolField = field("ELM protocol number", "6")
        headerField = field("ECU request ID (hex)", "7E1")
        filterField = field("Response ID filter (optional hex)", "7E9")
        requestField = field("Request (hex), e.g. 22xxxx or 01xx")
        byteIndexField = field("First data byte index", "0")
        byteLenField = field("Number of bytes (1 or 2)", "1")
        scaleField = field("Scale (multiply)", "1")
        offsetField = field("Offset (added after scaling)", "0")
        signedBox = CheckBox(this).apply {
            text = "Signed value"; setTextColor(Color.WHITE); buttonTintList = ColorStateList.valueOf(Color.WHITE)
        }
        resultView = TextView(this).apply { textSize = 16f; text = ""; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        logView = TextView(this).apply {
            textSize = 12f; setTextIsSelectable(true); text = "Log:\n"; setTextColor(Color.parseColor("#E6FFFFFF"))
        }
        statusView = TextView(this).apply {
            textSize = 14f; text = "Status: not connected"; setTextColor(Color.WHITE)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(88))
        }

        val conn = card()
        dongleCard = conn
        dotView = View(this)
        paintDot(false)
        val arrow = TextView(this).apply { text = "\u25B4"; textSize = 18f; setTextColor(Color.WHITE) }
        val connBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(dotView, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
            addView(label("vLinker Connection", 18f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow)
            setOnClickListener {
                val open = connBody.visibility == View.GONE
                connBody.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "\u25B4" else "\u25BE"
            }
        }
        conn.add(header, 0)
        connBody.add(deviceSpinner, 10)
        connBody.add(button("Scan BLE for vLinker") { scanBle() })
        connBody.add(button("Connect") { connect() })
        connBody.add(statusView, 10)
        conn.add(connBody, 0)
        root.addView(conn)

        val can = card()
        canCard = can
        canDot = View(this)
        canDot.background = dotDrawable(false)
        val canHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(canDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
            addView(label("Pulse CAN bus", 18f, true))
        }
        can.add(canHeader, 0)
        canInfoView = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE); text = "Waiting for the car..." }
        canStatusView = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#E6FFFFFF")); text = "" }
        can.add(canInfoView, 10)
        can.add(canStatusView, 4)
        root.addView(can)

        val reads = card()
        reads.add(label("Battery readings", 18f, true), 0)
        tempGauge = TempGaugeView(this).apply { setReading(null, useFahrenheit) }
        reads.addView(tempGauge, LinearLayout.LayoutParams(dp(230), dp(230)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12)
        })
        rangeBar = RangeBarView(this).apply { setReading(null, useFahrenheit) }
        reads.addView(rangeBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)).apply { topMargin = dp(4) })
        reads.add(resultView, 10)
        readAllBtn = button("Read all values") { Thread { readAll() }.start() }
        pollBtn = button("Start / stop polling (1 s)") { togglePolling() }
        reads.add(readAllBtn, 14)
        reads.add(pollBtn)
        root.addView(reads)

        statsCard = card()
        statsCard.add(label("Statistics", 18f, true), 0)
        statsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statsCard.add(statsBox, 4)
        statsHint = label("More statistics (capacity, battery health, range, regen, climate and more) appear when the app runs on an Oceanlink Pulse.", 12f)
        statsCard.add(statsHint, 10)
        statsCard.visibility = View.GONE
        root.addView(statsCard)

        advancedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun addField(l: String, f: View) { advancedBox.add(label(l), 12); advancedBox.add(f, 3) }
        addField("ELM protocol (6 = CAN 11-bit 500 kbit/s)", protocolField)
        addField("Reading to load", presetSpinner)
        addField("ECU request ID (hex)", headerField)
        addField("Response filter (hex)", filterField)
        addField("Request (hex)", requestField)
        addField("First data byte index (0 = first byte after the ID)", byteIndexField)
        addField("Number of bytes", byteLenField)
        addField("Scale", scaleField)
        addField("Offset", offsetField)
        advancedBox.add(signedBox, 10)
        advancedBox.add(button("Read once") { Thread { readOnce() }.start() }, 10)

        val adv = card()
        advCard = adv
        adv.add(button("Advanced settings") {
            advancedBox.visibility = if (advancedBox.visibility == View.GONE) View.VISIBLE else View.GONE
        }, 0)
        adv.add(advancedBox, 0)
        // the manual request fields (ELM protocol, ECU ID, raw request, "Read once") are no longer shown: they let a user
        // send arbitrary diagnostic requests onto the car's bus. The fields stay (with their defaults) for the dongle polling.

        val logCard = card()
        val logArrow = TextView(this).apply { text = "\u25B4"; textSize = 18f; setTextColor(Color.WHITE) }
        val logBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label("Log (press and hold to copy)", 14f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(logArrow)
            setOnClickListener {
                val open = logBody.visibility == View.GONE
                logBody.visibility = if (open) View.VISIBLE else View.GONE
                logArrow.text = if (open) "\u25B4" else "\u25BE"
            }
        }
        logCard.add(logHeader, 0)
        logBody.add(logView, 6)
        logCard.add(logBody, 0)
        root.addView(logCard)

        rootScroll = ScrollView(this).apply { addView(root) }
        dashRoot = buildDashboard()
        dashBtn = Button(this).apply {
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; text = "Show Dashboard"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 12f
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { showDashboard(!dashShown) }
        }
        skinBtn = Button(this).apply {
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; text = "Skin"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 12f
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            setOnClickListener { showSkinPicker() }
        }
        presetsBtn = Button(this).apply {
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; text = "Presets"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 12f
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            setOnClickListener { showPresetsMenu() }
        }
        settingsBtn = Button(this).apply {
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; text = "Settings"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 12f
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            setOnClickListener { showSettings() }   // closing the settings window leaves the dashboard as it was
        }
        exitBtn = Button(this).apply {
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; text = "Exit"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 12f
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { pulse?.stop(); finishAndRemoveTask() }
        }
        edgeBox = FrameLayout(this)
        edgeBox.addView(rootScroll, FrameLayout.LayoutParams(-1, -1))
        edgeBox.addView(dashRoot, FrameLayout.LayoutParams(-1, -1))
        // both buttons sit together at the bottom right
        btnBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(settingsBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
            addView(dashBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
            addView(exitBtn, LinearLayout.LayoutParams(-2, dp(36)))
        }
        edgeBox.addView(btnBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(16)) })
        climateView = ClimateScreenView(this).apply {
            visibility = View.GONE; onClose = { visibility = View.GONE; if (l2Applied) l2ClimClosed = true else prefs().edit().putBoolean("l1Climate", false).apply() }      // Layout 1: remember that Climate was closed (presets and restarts keep it closed)
            onPower = { turnOn ->
                val msg = pulse?.sendClimatePower(turnOn) ?: "CAN reader is not running"
                toast(msg)
            }
            onTempTap = { toast("This control is off: tick the test box in Settings and restart") }
            onTemp = { driver, dir ->
                val sn = climateView.snap
                val now = System.currentTimeMillis()
                val cur = sn.climDrvT ?: sn.climPassT
                val base = if (now - tempPendingAt < 2500 && tempPending != null) tempPending!! else (if (driver) sn.climDrvT else sn.climPassT ?: cur) ?: 22.0
                val target = (Math.round(base * 2) / 2.0 + dir * 0.5).coerceIn(18.0, 32.0)
                tempPending = target; tempPendingAt = now
                val msg = pulse?.sendTemp(driver, target, sn.climSync) ?: "CAN reader is not running"
                toast(msg)
            }
            onButton = { kind ->
                val msg = pulse?.sendButton(kind) ?: "CAN reader is not running"
                toast(msg)
            }
            onMode = { mode ->
                val msg = pulse?.sendAirMode(mode) ?: "CAN reader is not running"
                toast(msg)
            }
            onRearDefrost = { on ->
                val msg = pulse?.sendRearDefrost(on) ?: "CAN reader is not running"
                if (!msg.contains(" on IBUS")) toast(msg)
            }
            onWheel = {
                val msg = pulse?.sendWheelHeat() ?: "CAN reader is not running"
                toast(msg)
            }
            onSeat = { who, lvl ->
                val msg = pulse?.sendSeatHeat(who, lvl) ?: "CAN reader is not running"
                toast(msg)
            }
            hideSeatIcons = prefs().getBoolean("hideSeatIcons", false)
            car3d = prefs().getBoolean("l2ClimateCar3d", true)
            l1Car3d = prefs().getBoolean("l1Car3d", false)
            paintIdx = prefs().getInt("carPaint", 3)
            onPaint = { i -> setCarPaint(i) }
            onFan = { level ->
                val msg = pulse?.sendFan(level) ?: "CAN reader is not running"
                if (!msg.contains(" on IBUS")) toast(msg)       // only a failure is worth a message
            }
        }
        edgeBox.addView(climateView, FrameLayout.LayoutParams(-1, -1))
        cautionView = buildCaution()
        edgeBox.addView(cautionView, FrameLayout.LayoutParams(-1, -1))
        applySkinChrome()
        setContentView(edgeBox)
        clearOptionBackgrounds(rootScroll)
        if (!cautionAgreedThisRun) showCaution()
        applyEdgePadding()
        applyTheme(getSharedPreferences("prefs", MODE_PRIVATE).getInt("theme", 0).coerceIn(0, THEMES.size - 1))
        if (prefs().getBoolean("reopenSettings", false)) {      // the layout was just changed from Settings: back to Settings
            prefs().edit().remove("reopenSettings").apply()
            ui.postDelayed({ if (!isFinishing && !isDestroyed) showSettings() }, 800)
        }
    }

    // ------------------------------------------------------- Theme / settings
    private fun applyTheme(i: Int) {
        themeIndex = i
        val t = THEMES[i]
        rootScroll.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.top, t.bottom))
        window.statusBarColor = t.top
        window.navigationBarColor = t.bottom
        actionBar?.setBackgroundDrawable(ColorDrawable(t.top))
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("theme", i).apply()
        applyBgPic()
        paintEdges()
    }

    /** Whatever shows around or behind the app's own screens is dark (or light in day mode), never white. */
    private fun paintEdges() {
        if (!::edgeBox.isInitialized) return
        val col = if (dashShown) (if (dashDay) Color.parseColor("#E6ECF4") else Color.parseColor("#0B0F1A")) else THEMES[themeIndex].bottom
        edgeBox.setBackgroundColor(col)
        window.setBackgroundDrawable(ColorDrawable(col))
    }

    /** Settings can push everything in from the top and bottom edges, for bars that overlap the app. */
    private fun applyEdgePadding() {
        if (!::edgeBox.isInitialized) return
        edgeBox.setPadding(0, dp(prefs().getInt("padTop", 0)), 0, dp(prefs().getInt("padBot", 0)))
    }

    // ------------------------------------------------------- Background pictures (res/drawable-nodpi/bg_01 .. bg_15)
    private val bgCount = 15
    private val CUSTOM_BG = 99                      // bgPic value for the user's own picture
    private val REQ_PICK_BG = 4711
    private val REQ_PICK_AUDIO = 4712
    private fun customBgFile() = java.io.File(filesDir, "custom_bg.jpg")
    private var bgBitmap: android.graphics.Bitmap? = null
    private var bgBitmapIdx = 0

    private fun bgResId(n: Int) = resources.getIdentifier(String.format("bg_%02d", n), "drawable", packageName)

    /** The chosen picture (1..bgCount) or 0 for none, as a drawable that fills any window size without stretching. */
    private fun bgDrawable(scrim: Int, owner: View? = null): android.graphics.drawable.Drawable? {
        val n = prefs().getInt("bgPic", 0)
        if (n != CUSTOM_BG && n !in 1..bgCount) return null
        if (bgBitmapIdx != n || bgBitmap == null) {
            if (n == CUSTOM_BG) {
                bgBitmap = try { android.graphics.BitmapFactory.decodeFile(customBgFile().path) } catch (e: Throwable) { null }
                if (bgBitmap == null) return null
            } else {
                val id = bgResId(n)
                if (id == 0) return null
                bgBitmap = try { android.graphics.BitmapFactory.decodeResource(resources, id) } catch (e: Throwable) { null }
            }
            bgBitmapIdx = n
        }
        val bmp = bgBitmap ?: return null
        return CoverDrawable(bmp, scrim, owner)
    }

    private fun applyBgPic() {
        if (!::rootScroll.isInitialized) return
        bgDrawable(Color.argb(95, 0, 0, 0), rootScroll)?.let { rootScroll.background = it }
        if (::dashRoot.isInitialized) applyDashTheme(dashDay)
    }

    private fun customThumb(): android.graphics.Bitmap? = try {
        android.graphics.BitmapFactory.decodeFile(customBgFile().path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
    } catch (e: Throwable) { null }

    /** "My picture": a tip about the size first, then Android's own file picker. */
    private fun startCustomBgPick() {
        darkDialog().setTitle("Use your own picture")
            .setMessage("For the best result, use a portrait picture that is 1080 x 1920. If yours is a different size it will be cropped to fit (nothing is stretched), and you can move and zoom it to choose which part shows. The edges outside the frame will be cut off.")
            .setPositiveButton("Choose a picture") { _, _ -> chooseImageFile() }.setNegativeButton("Cancel", null).showDark()
    }

    private fun openSystemPicker() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_PICK_BG)
        } catch (e: Exception) {
            toast("This device has no file picker app, so a picture can't be chosen here.")
        }
    }

    /** Lists the pictures on the device inside the app (so the app is never sent to the background); without root it uses Android's file picker. */
    private fun chooseImageFile() {
        toast("Looking for pictures...")
        Thread {
            val found = ArrayList<String>()
            if (Updater.rootAvailable()) {
                val r = Root.exec("find /storage -maxdepth 6 -type f \\( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' \\) ! -path '*/Android/*' ! -path '*/.*' -size +20k 2>/dev/null | head -400", 25)
                for (l in r.out.lines()) { val t = l.trim(); if (t.startsWith("/") && found.none { it.substringAfterLast('/') == t.substringAfterLast('/') && it.length == t.length }) found.add(t) }
            }
            ui.post {
                if (found.isEmpty()) { openSystemPicker(); return@post }
                found.sortBy { it.substringAfterLast('/').lowercase() }
                val labels = Array(found.size) { i -> found[i].substringAfterLast('/') + "\n" + found[i].substringBeforeLast('/').removePrefix("/storage/") }
                darkDialog().setTitle("Choose a picture").setItems(labels) { _, i -> loadFromPath(found[i]) }
                    .setNeutralButton("Other...") { _, _ -> openSystemPicker() }.setNegativeButton("Cancel", null).showDark()
            }
        }.start()
    }

    /** Copies the chosen file where the app can read it (as root), then opens it for cropping. */
    private fun loadFromPath(path: String) {
        toast("Opening the picture...")
        Thread {
            val tmp = java.io.File(cacheDir, "pick.tmp")
            val q = "'" + path.replace("'", "'\\''") + "'"
            Root.exec("cp $q ${tmp.path} && chmod 666 ${tmp.path}", 20)
            val bmp = try { loadPickedBitmap { if (tmp.exists()) java.io.FileInputStream(tmp) else null } } catch (e: Throwable) { null }
            tmp.delete()
            ui.post {
                if (bmp == null) { toast("That picture could not be opened."); return@post }
                if (bmp.width == 1080 && bmp.height == 1920) saveCustomBg(bmp) else showCropDialog(bmp)
            }
        }.start()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK) {
            val au = data?.data ?: return
            var nm = "Music file"
            try {
                contentResolver.query(au, null, null, null, null)?.use { c ->
                    val ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (ix >= 0 && c.moveToFirst()) nm = c.getString(ix).substringBeforeLast('.')
                }
            } catch (e: Exception) { }
            pauseExternalMusic(); InApp.playUri(au, nm); refreshMediaCards()
            return
        }
        if (requestCode != REQ_PICK_BG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val bmp = try { loadPickedBitmap { contentResolver.openInputStream(uri) } } catch (e: Throwable) { null }
            ui.post {
                if (bmp == null) { toast("That picture could not be opened."); return@post }
                if (bmp.width == 1080 && bmp.height == 1920) saveCustomBg(bmp) else showCropDialog(bmp)
            }
        }.start()
    }

    /** Reads the picture (shrunk if huge, turned upright by its EXIF rotation). */
    private fun loadPickedBitmap(open: () -> java.io.InputStream?): Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 3000) sample *= 2
        var bmp = open()?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = try {
            open()?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        } catch (e: Throwable) { 0f }
        if (rot != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(rot) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    private fun saveCustomBg(out: Bitmap) {
        try {
            customBgFile().outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bgBitmap = null
            prefs().edit().putInt("bgPic", CUSTOM_BG).apply()
            applyTheme(themeIndex)
            EventLog.add("custom background picture saved")
            toast("Your picture is now the background.")
        } catch (e: Throwable) { toast("The picture could not be saved.") }
    }

    /** Full-screen: drag to move, pinch to zoom, then "Use picture" saves exactly what is inside the frame as 1080 x 1920. */
    private fun showCropDialog(bmp: Bitmap) {
        val d = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val crop = CropView(this, bmp)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#101418")) }
        col.addView(TextView(this).apply {
            text = "This picture is ${bmp.width} x ${bmp.height}, not 1080 x 1920, so it will be cropped. Drag to move it, pinch to zoom. Anything outside the frame is cut off."
            textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(16), dp(10), dp(16), dp(6))
        })
        col.addView(crop, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(10)) }
        row.addView(Button(this).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { d.dismiss() } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        row.addView(Button(this).apply { text = "Reset"; isAllCaps = false; setOnClickListener { crop.reset() } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        row.addView(Button(this).apply {
            text = "Use picture"; isAllCaps = false
            setOnClickListener { val o = crop.render(); d.dismiss(); saveCustomBg(o) }
        }, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        col.addView(row)
        d.setContentView(col)
        d.show()
    }

    private fun bgThumb(n: Int): android.graphics.Bitmap? {
        val id = bgResId(n)
        if (id == 0) return null
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
        return try { android.graphics.BitmapFactory.decodeResource(resources, id, o) } catch (e: Throwable) { null }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Settings").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { showSettings(); return true }
        return super.onOptionsItemSelected(item)
    }

    /** Dialogs use a dark theme so they are not glaring at night. */
    /** One car colour for both car drawings (the Climate screen and Layout 2's car). */
    private fun setCarPaint(i: Int) {
        prefs().edit().putInt("carPaint", i).apply()
        if (::climateView.isInitialized) climateView.paintIdx = i
        if (::carView.isInitialized) carView.paintIdx = i
    }

    private fun darkDialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    /** Dark background, light title / message, readable buttons: set explicitly so no theme can leave black text. */
    private fun styleDark(d: AlertDialog) {
        val light = Color.parseColor("#F0F3F8")
        d.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#262A31")))
        val ids = listOf(android.R.id.title, android.R.id.message, resources.getIdentifier("alertTitle", "id", "android"))
        for (id in ids) if (id != 0) d.findViewById<TextView>(id)?.setTextColor(light)
        for (w in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) d.getButton(w)?.setTextColor(Color.parseColor("#6FC3FF"))
    }

    private fun AlertDialog.Builder.showDark(): AlertDialog = create().also { it.show(); styleDark(it) }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        lateinit var dialog: AlertDialog
        fun relayout() { layoutKey = ""; if (::dashRoot.isInitialized && dashRoot.width > 0) { val dn = resources.displayMetrics.density; adaptDashLayout((dashRoot.width / dn).toInt(), (dashRoot.height / dn).toInt()) } }
        box.add(TextView(this).apply { text = "UPDATES AND BETA"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        box.add(Button(this).apply {
            text = "Check for updates (this is build ${Updater.installedBuild(this@MainActivity)})"; isAllCaps = false
            setOnClickListener { checkForUpdate(true) }
        }, 8)
        box.add(Button(this).apply {
            text = "Join the Beta"; isAllCaps = false
            setOnClickListener { showBetaGate() }
        }, 8)
        box.add(Button(this).apply {
            text = "Read all Release notes"; isAllCaps = false
            setOnClickListener { showAllReleaseNotes() }
        }, 8)
        box.add(Button(this).apply {
            text = "About"; isAllCaps = false
            setOnClickListener { showAbout() }
        }, 8)
        box.add(Button(this).apply {
            text = "Restart Pulse"; isAllCaps = false
            setOnClickListener {
                darkDialog().setTitle("Restart now?")
                    .setMessage("Your Pulse restarts a few seconds after you press Yes.")
                    .setPositiveButton("Yes") { _, _ ->
                        dialog.dismiss()
                        Thread {
                            val ok = try { Updater.rebootRoot(this@MainActivity) } catch (e: Throwable) { false }
                            ui.post { Toast.makeText(this@MainActivity, if (ok) "Restarting the Pulse..." else "Could not restart: root access is not available", Toast.LENGTH_LONG).show() }
                        }.also { it.isDaemon = true }.start()
                    }
                    .setNegativeButton("No", null).showDark()
            }
        }, 8)
        box.add(TextView(this).apply { text = "SKIN AND LAYOUT"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        // --- Skin
        box.add(TextView(this).apply { text = "Skin"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        box.add(Button(this).apply {
            isAllCaps = false
            text = "Skin: ${Skins.name(Skins.current)}   \u203A"
            setOnClickListener { dialog.dismiss(); showSkinPicker(true) }
        }, 6)
        // --- Layout
        box.add(TextView(this).apply { text = "Layout"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        box.add(Button(this).apply {
            isAllCaps = false
            text = "Layout: ${if (prefs().getInt("layout", 1) == 2) "Layout 2" else "Original"}   \u203A"
            setOnClickListener { dialog.dismiss(); showLayoutPicker(true) }
        }, 6)
        box.add(TextView(this).apply { text = "Dashboard theme"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val cur = prefs().getString("dispTheme", "night")
        val rn = RadioButton(this).apply { text = "Night (dark)"; id = View.generateViewId(); isChecked = cur == "night" }
        val rd = RadioButton(this).apply { text = "Day (light)"; id = View.generateViewId(); isChecked = cur == "day" }
        val ra = RadioButton(this).apply { text = "Follow the headlights (low or high beam on = night, otherwise day)"; id = View.generateViewId(); isChecked = cur == "auto" }
        val rtg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(rn); addView(rd); addView(ra) }
        rtg.setOnCheckedChangeListener { _, checkedId ->
            prefs().edit().putString("dispTheme", when (checkedId) { rd.id -> "day"; ra.id -> "auto"; else -> "night" }).apply()
            updateDashTheme(true)
        }
        box.add(rtg, 6)
        box.add(CheckBox(this).apply {
            text = "Show the Skin button on the dashboard (Layout 2 gear menu; Layout 1 has the Presets button instead)"; isChecked = showSkinBtn()
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("showSkin", on).apply()
                relayoutDash(); refreshLowerLabels(); paintLowerTabs()
            }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Right-hand drive layout (clock, compass, drive mode and the buttons swap sides)"; isChecked = prefs().getBoolean("rhd", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("rhd", on).apply()
                layoutKey = ""
                if (::dashRoot.isInitialized && dashRoot.width > 0) {
                    val dn = resources.displayMetrics.density
                    adaptDashLayout((dashRoot.width / dn).toInt(), (dashRoot.height / dn).toInt())
                }
            }
        }, 4)
        box.add(Button(this).apply {
            text = "Visualizer colours (media card)"; isAllCaps = false
            setOnClickListener {
                val cur = prefs().getInt("vizPal", 0).coerceIn(0, VizInfo.palettes.size - 1)
                darkDialog().setTitle("Visualizer colours").setSingleChoiceItems(VizInfo.palettes.toTypedArray(), cur) { dlg, i ->
                    prefs().edit().putInt("vizPal", i).apply(); dlg.dismiss()
                    if (::mediaPopup.isInitialized) mediaPopup.invalidate(); if (::mediaCard.isInitialized) mediaCard.invalidate()
                }.setNegativeButton("Close", null).showDark()
            }
        }, 8)
        box.add(TextView(this).apply { text = "BACKGROUNDS"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        // --- Background colour
        box.add(TextView(this).apply { text = "Background colour"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        THEMES.forEachIndexed { i, t ->
            val b = Button(this).apply {
                text = t.name + if (i == themeIndex) "  \u2713" else ""
                isAllCaps = false; setTextColor(Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(t.top, t.bottom))
                    .apply { cornerRadius = dp(12).toFloat() }
                setOnClickListener { prefs().edit().putInt("bgPic", 0).apply(); applyTheme(i); dialog.dismiss() }
            }
            box.add(b, 10)
        }
        // --- Background picture
        box.add(TextView(this).apply { text = "Background picture"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val picRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val curPic = prefs().getInt("bgPic", 0)
        fun tile(n: Int): View {
            val sel = n == curPic
            val v: View = if (n == 0) TextView(this).apply {
                text = "None"; tag = "keep"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(Color.DKGRAY)
                background = GradientDrawable().apply { setColor(Color.parseColor("#EEEEEE")); cornerRadius = dp(8).toFloat() }
            } else ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                (if (n == CUSTOM_BG) customThumb() else bgThumb(n))?.let { setImageBitmap(it) } ?: setBackgroundColor(Color.LTGRAY)
            }
            v.setOnClickListener { prefs().edit().putInt("bgPic", n).apply(); applyTheme(themeIndex); dialog.dismiss() }
            return FrameLayout(this).apply {
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(10).toFloat(); setStroke(dp(if (sel) 3 else 0), Color.parseColor("#2AA8FF")) }
                addView(v, FrameLayout.LayoutParams(dp(68), dp(120)))
            }
        }
        picRow.addView(tile(0))
        // second after None: the tile to choose your own picture, then the saved one (if any)
        picRow.addView(FrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(TextView(this@MainActivity).apply {
                text = "+\nUse my\nown Image"; tag = "keep"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(Color.BLACK)
                background = GradientDrawable().apply { setColor(Color.parseColor("#EEEEEE")); cornerRadius = dp(8).toFloat() }
                setOnClickListener { dialog.dismiss(); startCustomBgPick() }
            }, FrameLayout.LayoutParams(dp(68), dp(120)))
        })
        if (customBgFile().exists()) picRow.addView(tile(CUSTOM_BG))
        for (n in 1..bgCount) if (bgResId(n) != 0) picRow.addView(tile(n))
        box.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(picRow) }, 8)
        if (customBgFile().exists()) box.add(Button(this).apply {
            text = "Remove my picture"; isAllCaps = false
            setOnClickListener {
                customBgFile().delete(); bgBitmap = null
                if (prefs().getInt("bgPic", 0) == CUSTOM_BG) prefs().edit().putInt("bgPic", 0).apply()
                applyTheme(themeIndex); dialog.dismiss()
            }
        }, 8)
        box.add(TextView(this).apply { text = "UNITS"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        // --- Temperature unit (first, so it is always visible)
        box.add(TextView(this).apply { text = "Temperature unit"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 0)
        val rc = RadioButton(this).apply { text = "Celsius (\u00B0C)"; id = View.generateViewId(); isChecked = !useFahrenheit }
        val rf = RadioButton(this).apply { text = "Fahrenheit (\u00B0F)"; id = View.generateViewId(); isChecked = useFahrenheit }
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(rc); addView(rf) }
        rg.setOnCheckedChangeListener { _, checkedId -> setFahrenheit(checkedId == rf.id) }
        box.add(rg, 6)
        box.add(TextView(this).apply { text = "Speed unit"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val kmh = RadioButton(this).apply { text = "km/h"; id = View.generateViewId(); isChecked = !speedMph() }
        val mph = RadioButton(this).apply { text = "mph"; id = View.generateViewId(); isChecked = speedMph() }
        val spg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(kmh); addView(mph) }
        box.add(spg, 6)
        box.add(TextView(this).apply { text = "Efficiency units"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val dU = if (speedMph()) "mi" else "km"
        val ef1 = RadioButton(this).apply { text = "Wh/$dU  (Wh used per $dU)"; id = View.generateViewId(); isChecked = effMode() == "wh" }
        val ef2 = RadioButton(this).apply { text = "$dU/kWh  ($dU per kWh)"; id = View.generateViewId(); isChecked = effMode() == "perkwh" }
        val ef3 = RadioButton(this).apply { text = "kWh/$dU  (kWh used per $dU)"; id = View.generateViewId(); isChecked = effMode() == "kwhper" }
        val efg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(ef1); addView(ef2); addView(ef3) }
        efg.setOnCheckedChangeListener { _, checkedId -> prefs().edit().putString("effUnit", when (checkedId) { ef2.id -> "perkwh"; ef3.id -> "kwhper"; else -> "wh" }).apply() }
        box.add(efg, 6)
        // the efficiency wording follows the speed unit straight away
        spg.setOnCheckedChangeListener { _, checkedId ->
            prefs().edit().putBoolean("mph", checkedId == mph.id).apply()
            val u = if (checkedId == mph.id) "mi" else "km"
            ef1.text = "Wh/$u  (Wh used per $u)"; ef2.text = "$u/kWh  ($u per kWh)"; ef3.text = "kWh/$u  (kWh used per $u)"
        }
        box.add(TextView(this).apply { text = "DASHBOARD"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        box.add(CheckBox(this).apply {
            text = "Show the dashboard when the app starts"; isChecked = prefs().getBoolean("dashDefault", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("dashDefault", on).apply() }
        }, 4)
        box.add(TextView(this).apply { text = "When the app starts"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 14)
        val ssNone = RadioButton(this).apply { text = "Full screen"; id = View.generateViewId() }
        val ssSpotify = RadioButton(this).apply { text = "Split screen: this app on top, Spotify at the bottom"; id = View.generateViewId() }
        val ssMaps = RadioButton(this).apply { text = "Split screen: this app on top, Maps at the bottom"; id = View.generateViewId() }
        val ssCustom = RadioButton(this).apply { text = "Split screen: this app on top, my own app at the bottom"; id = View.generateViewId() }
        val ssg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(ssNone); addView(ssSpotify); addView(ssMaps); addView(ssCustom) }
        val ssPick = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#6AA8FF")); setPadding(dp(32), dp(6), 0, dp(6)) }
        fun ssPickText() { val k = prefs().getString("startSplitPkg", "") ?: ""; ssPick.text = if (k.isEmpty()) "Choose my app\u2026" else "My app: ${appLabelOf(k)}  (tap to change)"; ssPick.visibility = if (ssCustom.isChecked) View.VISIBLE else View.GONE }
        when (prefs().getString("startSplit", "")) { "spotify" -> ssSpotify.isChecked = true; "maps" -> ssMaps.isChecked = true; "custom" -> ssCustom.isChecked = true; else -> ssNone.isChecked = true }
        ssPickText()
        ssPick.setOnClickListener { pickLowerApp(-1) { pkg -> prefs().edit().putString("startSplitPkg", pkg).apply(); ssPickText() } }
        ssg.setOnCheckedChangeListener { _, checkedId ->
            prefs().edit().putString("startSplit", when (checkedId) { ssSpotify.id -> "spotify"; ssMaps.id -> "maps"; ssCustom.id -> "custom"; else -> "" }).apply()
            ssPickText()
            if (checkedId == ssCustom.id && (prefs().getString("startSplitPkg", "") ?: "").isEmpty()) ssPick.performClick()
        }
        box.add(ssg, 4)
        box.add(ssPick, 4)
        box.add(label("Gauge arcs", 13f), 8)
        val arcMirror = RadioButton(this).apply { text = "Mirrored (the two arcs curve toward each other)"; id = View.generateViewId() }
        val arcLeft = RadioButton(this).apply { text = "Both arcs the same: curved like ("; id = View.generateViewId() }
        val arcRight = RadioButton(this).apply { text = "Both arcs the same: curved like )"; id = View.generateViewId() }
        val arcG = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(arcMirror); addView(arcLeft); addView(arcRight) }
        when (prefs().getString("arcStyle", "mirror")) { "left" -> arcLeft.isChecked = true; "right" -> arcRight.isChecked = true; else -> arcMirror.isChecked = true }
        arcG.setOnCheckedChangeListener { _, checkedId ->
            prefs().edit().putString("arcStyle", when (checkedId) { arcLeft.id -> "left"; arcRight.id -> "right"; else -> "mirror" }).apply()
            applyGaugeSides()
            layoutKey = ""
            if (::dashRoot.isInitialized && dashRoot.width > 0) {
                val dn = resources.displayMetrics.density
                adaptDashLayout((dashRoot.width / dn).toInt(), (dashRoot.height / dn).toInt())
            }
        }
        box.add(arcG, 4)
        box.add(CheckBox(this).apply {
            text = "Speed gauge on the left (power on the right)"; isChecked = prefs().getBoolean("speedLeft", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("speedLeft", on).apply(); applyGaugeSides() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Hide the battery bar under the trip information (small windows give the space to the trip information)"; isChecked = prefs().getBoolean("hideBatteryBar", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("hideBatteryBar", on).apply()
                layoutKey = ""
                if (::dashRoot.isInitialized && dashRoot.width > 0) {
                    val dn = resources.displayMetrics.density
                    adaptDashLayout((dashRoot.width / dn).toInt(), (dashRoot.height / dn).toInt())
                }
            }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Show icons instead of names on the app buttons"; isChecked = prefs().getBoolean("iconBtns", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("iconBtns", on).apply()
                relayoutDash(); refreshLowerLabels(); paintLowerTabs(); refreshShortcuts()
            }
        }, 4)
        box.add(Button(this).apply {
            text = "Clear an app button"; isAllCaps = false
            setOnClickListener {
                val names = Array(slotCount) { i -> "Button ${i + 1}: " + (if (slotPkg(i).isEmpty()) "(no app)" else appLabelOf(slotPkg(i))) }
                darkDialog().setTitle("Clear which button?").setItems(names) { _, i ->
                    clearSlot(i)
                    EventLog.add("lower button ${i + 1} cleared")
                }.setNegativeButton("Cancel", null).showDark()
            }
        }, 8)
        box.add(TextView(this).apply { text = "Screen edges: move everything in from the top or bottom if a bar overlaps the app"; textSize = 13f; setTextColor(Color.DKGRAY) }, 14)
        fun padRow(label: String, key: String): View {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tv = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#F0F3F8")); text = "$label: ${prefs().getInt(key, 0)} dp" }
            fun bump(d: Int) { val v = (prefs().getInt(key, 0) + d).coerceIn(0, 300); prefs().edit().putInt(key, v).apply(); tv.text = "$label: $v dp"; applyEdgePadding() }
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = "\u2212"; setOnClickListener { bump(-8) } })
            row.addView(Button(this).apply { text = "+"; setOnClickListener { bump(8) } })
            return row
        }
        box.add(padRow("Top margin", "padTop"), 2)
        box.add(padRow("Bottom margin", "padBot"), 2)
        box.add(Button(this).apply {
            text = "Speedometer Adjustment"; isAllCaps = false
            setOnClickListener { showSpeedAdjust() }
        }, 8)
        box.add(TextView(this).apply { text = "ADAS OPTIONS"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        box.add(CheckBox(this).apply {
            text = "Road bends with the curves of the road (off: the road always stays straight, from neither the camera nor the GPS / compass)"; isChecked = prefs().getBoolean("roadCurve", true)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("roadCurve", on).apply() }
        }, 12)
        box.add(CheckBox(this).apply {
            text = "Use the car's lane camera for the curve when it sees lane lines (the GPS / compass heading is used on roads without lines, and when the camera reports straight)"; isChecked = prefs().getBoolean("curveLane", true)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("curveLane", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Show the cars and objects the camera sees (the car in front, other cars, bikes and people)"; isChecked = prefs().getBoolean("showObjects", prefs().getBoolean("l2Alerts", true))
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("showObjects", on).apply() }
        }, 12)
        box.add(CheckBox(this).apply {
            text = "Only show the lead vehicle / object (the one in front of you; the other cars, bikes and people are hidden). Applies with the ADAS view on or off"; isChecked = prefs().getBoolean("onlyLead", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("onlyLead", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Hide the gauges in the ADAS view and show compact Speed and Power numbers in the top corners instead (the gauges show by default)"; isChecked = prefs().getBoolean("adasHideGauges", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("adasHideGauges", on).apply() }
        }, 12)
        box.add(CheckBox(this).apply {
            text = "ADAS view with Climate and Media closed: move the 3D car to the middle of the screen (the road lines move up with it)"; isChecked = prefs().getBoolean("adasCenter", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("adasCenter", on).apply() }
        }, 12)
        box.add(CheckBox(this).apply {
            text = "ADAS view with the Media player on and Climate closed: move the car, road and objects down into the free space under the media player"; isChecked = prefs().getBoolean("adasLow", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("adasLow", on).apply() }
        }, 12)
        box.add(CheckBox(this).apply {
            text = "Hide the road (the shaded road surface; the road lines below, the cars and the bend are separate)"; isChecked = prefs().getBoolean("hideRoad", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideRoad", on).apply() }
        }, 12)
        box.add(TextView(this).apply { text = "Road lines (the dashed lane lines on the road)"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 12)
        val rlShow = RadioButton(this).apply { text = "Always show"; id = View.generateViewId() }
        val rlFade = RadioButton(this).apply { text = "Fade away above 5 mph (8 km/h)"; id = View.generateViewId() }
        val rlHide = RadioButton(this).apply { text = "Always hide, at any speed"; id = View.generateViewId() }
        val rlg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(rlShow); addView(rlFade); addView(rlHide) }
        when (prefs().getString("roadLines", if (prefs().getBoolean("fadeLines", false)) "fade" else "show")) { "fade" -> rlFade.isChecked = true; "hide" -> rlHide.isChecked = true; else -> rlShow.isChecked = true }
        rlg.setOnCheckedChangeListener { _, id -> prefs().edit().putString("roadLines", when (id) { rlFade.id -> "fade"; rlHide.id -> "hide"; else -> "show" }).apply() }
        box.add(rlg, 4)
        box.add(CheckBox(this).apply {
            text = "Hide the traffic light"; isChecked = prefs().getBoolean("hideLight", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideLight", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Hide the speed limit sign"; isChecked = prefs().getBoolean("hideSign", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideSign", on).apply() }
        }, 4)
        box.add(TextView(this).apply { text = "Speed limit sign style"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val curSign = prefs().getString("signStyle", "us")
        val sus = RadioButton(this).apply { text = "USA / Canada (rectangle)"; id = View.generateViewId(); isChecked = curSign != "eu" }
        val seu = RadioButton(this).apply { text = "Europe / UK (round, red ring)"; id = View.generateViewId(); isChecked = curSign == "eu" }
        val sgp = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(sus); addView(seu) }
        sgp.setOnCheckedChangeListener { _, checkedId -> prefs().edit().putString("signStyle", if (checkedId == seu.id) "eu" else "us").apply() }
        box.add(sgp, 6)
        box.add(TextView(this).apply { text = "LAYOUT 1 OPTIONS"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        box.add(CheckBox(this).apply {
            text = "Show the 3D car in the middle instead of the Trip Information (small trip readouts go under the car; the Climate screen uses the 3D car too)"; isChecked = prefs().getBoolean("l1Car3d", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("l1Car3d", on).apply()
                if (::climateView.isInitialized) { climateView.l1Car3d = on; climateView.invalidate() }
                relayout()
            }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Layout 1: turn the 3D car to match the curve of the road"; isChecked = prefs().getBoolean("l1CarFollow", true)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("l1CarFollow", on).apply() }
        }, 4)
        box.add(Button(this).apply {
            fun label() = "Solar roof: full colour at ${prefs().getInt("solarMaxW", 250)} W"
            text = label(); isAllCaps = false
            setOnClickListener {
                val opts = intArrayOf(100, 150, 200, 250, 300, 400, 500)
                val cur = opts.indexOf(prefs().getInt("solarMaxW", 250)).coerceAtLeast(0)
                darkDialog().setTitle("Solar roof: power shown as full colour").setSingleChoiceItems(opts.map { "$it W" }.toTypedArray(), cur) { dlg, i ->
                    prefs().edit().putInt("solarMaxW", opts[i]).apply(); text = label(); dlg.dismiss()
                }.setNegativeButton("Close", null).showDark()
            }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Hide the \"Brake light on\" message"; isChecked = prefs().getBoolean("hideBrakeNote1", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideBrakeNote1", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Hide the trip information"; isChecked = prefs().getBoolean("hideTrip", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideTrip", on).apply(); relayout() }
        }, 4)
        box.add(TextView(this).apply { text = "LAYOUT 2 OPTIONS"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        box.add(CheckBox(this).apply {
            text = "Layout 2: show the climate screen under the cards"; isChecked = prefs().getBoolean("l2Climate", true)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("l2Climate", on).apply()
                relayoutDash(); refreshLowerLabels(); paintLowerTabs()
            }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Layout 2: show blind-spot, door-open and parking-sensor warnings on the car"; isChecked = prefs().getBoolean("l2Alerts", true)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("l2Alerts", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Layout 2: turn the 3D car to match the curve of the road"; isChecked = prefs().getBoolean("l2CarFollow", true)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("l2CarFollow", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Layout 2: live 3D car (turn off to use the still-image car instead)"; isChecked = prefs().getBoolean("l2Live3d", true)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("l2Live3d", on).apply()
                failed3d = false
                if (::dashRoot.isInitialized) syncCar3d(dashRoot)
            }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Layout 2: show Media and Climate buttons under the gauge in the 50/50 and 2/3 splits"; isChecked = prefs().getBoolean("l2SplitBtns", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("l2SplitBtns", on).apply()
                layoutKey = ""
                if (::dashRoot.isInitialized && dashRoot.width > 0) {
                    val dn = resources.displayMetrics.density
                    adaptDashLayout((dashRoot.width / dn).toInt(), (dashRoot.height / dn).toInt())
                }
            }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Layout 2: hide the \"Brake light on\" message"; isChecked = prefs().getBoolean("hideBrakeNote", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideBrakeNote", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Layout 2: 3D car in the Climate screen (turn off to use the original drawn car)"; isChecked = prefs().getBoolean("l2ClimateCar3d", true)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("l2ClimateCar3d", on).apply()
                if (::climateView.isInitialized) { climateView.car3d = on; climateView.invalidate() }
            }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Climate screen: hide the heated seat buttons outside the car (use the seats in the car drawing instead)"; isChecked = prefs().getBoolean("hideSeatIcons", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("hideSeatIcons", on).apply()
                if (::climateView.isInitialized) climateView.hideSeatIcons = on
            }
        }, 4)
        box.add(TextView(this).apply { text = "CAR AND DATA"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        box.add(TextView(this).apply { text = "Data source"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val sd = RadioButton(this).apply { text = "vLinker dongle (Bluetooth)"; id = View.generateViewId(); isChecked = !useCan }
        val sp = RadioButton(this).apply { text = "Pulse CAN bus (run this app on the Pulse)"; id = View.generateViewId(); isChecked = useCan }
        val sg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(sd); addView(sp) }
        sg.setOnCheckedChangeListener { _, checkedId -> applySource(checkedId == sp.id) }
        box.add(sg, 6)
        // pack size when new: the total capacity is this times the battery health
        val packRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val packTv = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#F0F3F8")); text = "Usable pack size when new: ${prefs().getFloat("packKwh", 106.5f)} kWh" }
        packRow.addView(packTv, LinearLayout.LayoutParams(0, -2, 1f))
        packRow.addView(Button(this).apply {
            text = "Change"
            setOnClickListener {
                val et = EditText(context).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(prefs().getFloat("packKwh", 106.5f).toString())
                }
                darkDialog().setTitle("Usable pack size when new (kWh)").setView(et)
                    .setPositiveButton("Save") { _, _ ->
                        val v = et.text.toString().toFloatOrNull()
                        if (v != null && v in 20f..250f) { prefs().edit().putFloat("packKwh", v).apply(); packTv.text = "Usable pack size when new: $v kWh" }
                    }.setNegativeButton("Cancel", null).showDark()
            }
        })
        box.add(packRow, 8)
        box.add(CheckBox(this).apply {
            text = "Flip power direction (if drive shows as negative)"; isChecked = prefs().getBoolean("flipPower", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("flipPower", on).apply() }
        }, 0)
        box.add(CheckBox(this).apply {
            text = "Pulse version 2821 or older: use the previous CAN filters (power from pack voltage and current, 0xE9)"
            isChecked = prefs().getBoolean("legacyCan", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("legacyCan", on).apply() }
        }, 0)
        box.add(CheckBox(this).apply {
            text = "Safe mode: never write the CAN chip filters, only read them (climate kW and the heat pump may show --)"; isChecked = prefs().getBoolean("canSafe", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("canSafe", on).apply(); pulse?.safeMode = on; toast("Takes effect the next time the app starts") }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Stop reading the CAN bus while this app is not visible (the regen counter only counts while visible)"; isChecked = prefs().getBoolean("pauseHidden", true)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("pauseHidden", on).apply() }
        }, 0)
        box.add(TextView(this).apply { text = "TOOLS AND TESTS"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6AA8FF")) }, 30)
        box.add(Button(this).apply {
            text = "Pulse hardware health"; isAllCaps = false
            setOnClickListener { showHealth() }
        }, 8)
        box.add(Button(this).apply {
            text = "CAN diagnostics"; isAllCaps = false
            setOnClickListener {
                toast("Reading the CAN state\u2026")
                Thread {
                    val report = CanDiag.full(pulse?.frames ?: 0L)
                    EventLog.add("CAN diagnostics:\n" + report)
                    ui.post {
                        val tv = TextView(this@MainActivity).apply { text = report; textSize = 11f; setTextColor(Color.parseColor("#F0F3F8")); typeface = Typeface.MONOSPACE; setPadding(dp(12), dp(8), dp(12), dp(8)) }
                        val sv = ScrollView(this@MainActivity).apply { addView(tv) }
                        darkDialog().setTitle("CAN diagnostics").setView(sv)
                            .setNeutralButton("Copy") { _, _ ->
                                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("can", report))
                                toast("Copied")
                            }.setPositiveButton("Close", null).showDark()
                    }
                }.start()
            }
        }, 8)
        box.add(Button(this).apply {
            text = "Show event log"; isAllCaps = false
            setOnClickListener {
                var cleared = 0            // lines hidden by Clear log buffer (the saved log itself is kept; only Delete log removes it)
                var query = ""
                val light = Color.parseColor("#F0F3F8")
                val tv = TextView(this@MainActivity).apply { textSize = 11f; setTextColor(light); typeface = Typeface.MONOSPACE; setPadding(dp(12), dp(8), dp(12), dp(8)) }
                val sv = ScrollView(this@MainActivity).apply { addView(tv) }
                // the newest part of the log only (about 150,000 characters, cut at a line): a long text gets cut off at the END by the clipboard and by
                // whatever it is pasted into, which dropped exactly the newest lines; the screen also stays quick to refresh
                fun shownText(): String {
                    val all = EventLog.allLines()
                    val lines = all.drop(cleared.coerceAtMost(all.size))
                    // a search keeps whole entries: a line without a time stamp belongs to the entry above it (the update script log, the boot info, the crash text...)
                    val stamped = Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d ")
                    val kept: List<String> = if (query.isBlank()) lines else {
                        val entries = ArrayList<MutableList<String>>()
                        for (ln in lines) { if (entries.isEmpty() || stamped.containsMatchIn(ln)) entries.add(mutableListOf(ln)) else entries.last().add(ln) }
                        entries.filter { e -> e.any { it.contains(query, ignoreCase = true) } }.flatten()
                    }
                    val joined = kept.joinToString("\n")
                    return if (joined.length > 150_000) joined.takeLast(150_000).substringAfter('\n') else joined
                }
                fun refresh(toEnd: Boolean) {
                    val atEnd = toEnd || sv.getChildAt(0).bottom - (sv.scrollY + sv.height) < dp(40)
                    val txt = shownText()
                    if (tv.text.toString() != txt) { tv.text = txt; if (atEnd) sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
                }
                val search = EditText(this@MainActivity).apply {
                    hint = "Search the log (text)"; setHintTextColor(Color.parseColor("#8E99A8")); setTextColor(light)
                    isSingleLine = true; textSize = 14f; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    setOnEditorActionListener { v, _, _ -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); v.clearFocus(); true }      // the keyboard's own Search / Done key also puts it away
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString() ?: ""; refresh(true) }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                }
                val col = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    // the search box with a Cancel beside it: Cancel empties the search, drops the cursor and puts the keyboard away so the buttons below are reachable again
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(search, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(Button(this@MainActivity).apply {
                            text = "Cancel search"; isAllCaps = false; textSize = 13f
                            setOnClickListener { search.setText(""); search.clearFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0) }
                        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
                    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), 0) })
                    addView(sv, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6f).toInt()))
                }
                // Delete log: removes the saved log for good, so it asks for a second tap first
                val del = Button(this@MainActivity).apply {
                    text = "Delete log"; isAllCaps = false; setTextColor(Color.parseColor("#FF8A80"))
                    var armed = false
                    setOnClickListener {
                        if (!armed) {
                            armed = true; text = "Tap again to delete the saved log for good"
                            postDelayed({ armed = false; text = "Delete log" }, 4000)
                        } else {
                            armed = false; text = "Delete log"; EventLog.clear(); cleared = 0; refresh(true); toast("Log deleted")
                        }
                    }
                }
                col.addView(del, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; setMargins(dp(12), dp(4), dp(12), 0) })
                refresh(true)
                val dlg = darkDialog().setTitle("Event log (newest at the bottom)").setView(col)
                    .setNeutralButton("Copy") { _, _ ->
                        val txt = shownText()
                        try {
                            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", txt))
                            val last = txt.substringAfterLast("\n", txt).take(19)      // the time stamp on the newest line copied
                            toast("Copied ${txt.lines().size} lines, newest: $last")
                        } catch (e: Throwable) { toast("Could not copy: ${e.message}") }
                    }.setNegativeButton("Clear log buffer", null).setPositiveButton("Close", null).showDark()
                // Clear log buffer empties the screen only (the saved log is kept, and Copy then takes what is shown); the window keeps up with the log while it is open
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { cleared = EventLog.allLines().size; refresh(true) }
                val tick = object : Runnable { override fun run() { if (dlg.isShowing) { refresh(false); ui.postDelayed(this, 2000) } } }
                ui.postDelayed(tick, 2000)
            }
        }, 8)
        box.add(Button(this).apply {
            text = "Test root access"; isAllCaps = false
            setOnClickListener { Thread { val r = rootCheck(); ui.post { toast(r ?: "Root access is working (${Root.describe()})") } }.start() }
        }, 8)
        box.add(CheckBox(this).apply {
            text = "Let the Climate screen control the car: power, temperature, fan speed, air direction, A/C, front and rear defrost, recirc, sync, auto, the heated wheel and seat heating. This SENDS commands onto the car's CAN bus (everything else in the app stays read-only). On by default; untick it to make the app read-only again. Takes effect after the app restarts"
            isChecked = prefs().getBoolean("climateTx", true)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("climateTx", on).apply()
                toast("Takes effect the next time the app starts")
            }
        }, 4)
        box.add(TextView(this).apply {
            text = "This project uses software from Haibin Wen and SUNNYPILOT LLC and is licensed under a custom license requiring permission for use."
            textSize = 11f; setTextColor(Color.parseColor("#8E99A8"))
        }, 24)
        box.add(TextView(this).apply {
            text = "Layout 2 car: \"Fisker Ocean (low-poly)\" by LagzDesign, CC BY 4.0 (creativecommons.org/licenses/by/4.0), modified: rigged, lamps added, rendered to images."
            textSize = 11f; setTextColor(Color.parseColor("#8E99A8"))
        }, 6)
        // ScrollView so nothing is cut off on small screens
        // views made with the activity's (light) theme draw dark text by default; make every dark label light
        fun lightInk(v: View) {
            if (v is TextView && v !is Button && v.tag != "keep") {
                val c = v.currentTextColor
                if ((0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0 < 0.5) v.setTextColor(Color.parseColor("#F0F3F8"))
            }
            // plain theme buttons can be flat with dark text on this device: give them a visible grey face
            // radio buttons and check boxes are Buttons too: they stay plain white text on the dialog's own background
            if (v is android.widget.CompoundButton) { v.background = null; v.setTextColor(Color.WHITE) }
            else if (v is Button && v.background !is GradientDrawable) {
                v.background = GradientDrawable().apply { setColor(Color.parseColor("#3A4150")); cornerRadius = dp(10).toFloat() }
                v.setTextColor(Color.WHITE)
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) lightInk(v.getChildAt(k))
        }
        lightInk(box)
        val scroller = ScrollView(this).apply { addView(box) }
        dialog = darkDialog().setTitle("Settings").setView(scroller)
            .setNegativeButton("Close", null).create()
        dialog.show()
        styleDark(dialog)
    }

    // ------------------------------------------------------- Skins
    private fun chooseSkin(id: Int) {
        prefs().edit().putInt("skin", id).apply()
        Skins.current = id
        applySkinChrome()
        if (::dashRoot.isInitialized) applyDashTheme(dashDay)
    }

    /** A preview of a skin: the real power and speed gauges drawn with it, on its background, with its strip line. */
    private fun skinThumb(id: Int): android.graphics.Bitmap {
        val lk = Skins.lookOf(id)
        val gw = 300
        val bmp = android.graphics.Bitmap.createBitmap(gw * 2 + 40, gw + 100, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lk.bgTop, lk.bgBottom))
            .apply { cornerRadius = 24f; setBounds(0, 0, bmp.width, bmp.height) }.draw(cv)
        fun gauge(mirrored: Boolean, zero: Float, f: Float, setup: ArcGaugeView.() -> Unit): ArcGaugeView =
            ArcGaugeView(this, mirrored, zero) { t -> Skins.gradFor(id, t) }.apply {
                skinOverride = id; day = false; setup(); showStatic(f)
                measure(View.MeasureSpec.makeMeasureSpec(gw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(gw, View.MeasureSpec.EXACTLY))
                layout(0, 0, gw, gw)
            }
        val power = gauge(false, 0.2f, 0.50f) {
            topLabel = "CLIMATE"; topValue = "0.4 kW"; title = "POWER"; valueText = "38.4"; unit = "kW"; bottomLabel = "HV BATTERY"; bottomValue = "78 %"
            scale = powerScale()
        }
        val speed = gauge(true, 0f, 0.30f) {
            topLabel = "BATTERY TEMP"; topValue = "21\u00B0C"; title = "SPEED"; valueText = "72"; unit = "km/h"; bottomLabel = "THERMAL"; bottomValue = "Idle"
            scale = speedScale(false)
        }
        cv.save(); cv.translate(10f, 15f); power.draw(cv); cv.restore()
        cv.save(); cv.translate(gw + 30f, 15f); speed.draw(cv); cv.restore()
        // the stats strip: its line and four readouts
        val sp = Paint(Paint.ANTI_ALIAS_FLAG)
        val y0 = gw + 40f
        val x0 = 40f; val x1 = bmp.width - 40f
        sp.style = Paint.Style.STROKE; sp.strokeWidth = if (lk.stripFlat) 2f else 3f
        sp.shader = LinearGradient(x0, 0f, x1, 0f,
            intArrayOf(Color.argb(0, Color.red(lk.accent), Color.green(lk.accent), Color.blue(lk.accent)), lk.accent, lk.accent2, lk.accent, Color.argb(0, Color.red(lk.accent), Color.green(lk.accent), Color.blue(lk.accent))),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
        if (lk.stripFlat) cv.drawLine(x0, y0, x1, y0, sp)
        else { val pth = android.graphics.Path(); pth.moveTo(x0 - 30f, y0 + 50f); pth.lineTo(x0, y0); pth.lineTo(x1, y0); pth.lineTo(x1 + 30f, y0 + 50f); cv.drawPath(pth, sp) }
        sp.shader = null; sp.style = Paint.Style.FILL; sp.textAlign = Paint.Align.CENTER
        val labels = listOf("RANGE" to "312 km", "REGEN" to "0.4 kWh", "CELLS" to "18\u00B0C \u2013 20\u00B0C", "THERMAL" to "Idle")
        val colW = (x1 - x0) / labels.size
        for ((i, p) in labels.withIndex()) {
            val cx = x0 + colW * (i + 0.5f)
            sp.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); sp.textSize = 15f; sp.color = Color.argb(150, 255, 255, 255)
            cv.drawText(p.first, cx, y0 + 26f, sp)
            sp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); sp.textSize = 26f; sp.color = Color.WHITE
            cv.drawText(p.second, cx, y0 + 56f, sp)
        }
        return bmp
    }

    /** Layout 2 takes the middle column and the corners over; Original puts everything back where it was. */
    private fun fillMid(l2: Boolean) {
        for (v in listOf<View>(dashReady, dashSlot, dashLightDist, dashBrake, tripSpacer, tripPanel, chargeBar, l2Mid, carView, dashLight, mediaBtn, climateBtn, l2FlyBtn)) (v.parent as? ViewGroup)?.removeView(v)
        for (v in listOf<View>(dashSlot, dashLight, dashLightDist, carView)) { v.translationX = 0f; v.translationY = 0f }
        (dashSignSrc.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; topMargin = 0 }
        if (l2 || !l1cApplied) l2Mid.addView(carView, 0, LinearLayout.LayoutParams(-1, 0, 0.38f))
        if (!l1cApplied && !l2) dashSlot.addView(dashLight, 0, FrameLayout.LayoutParams(dp(42), dp(88), Gravity.CENTER))
        if (l2) {
            dashMid.addView(l2Mid, LinearLayout.LayoutParams(-1, -1))
            // Layout 2 uses the same overlays as Layout 1 with the 3D car: the sign on the speed gauge, the traffic light above the car (placed in placeL1Overlays)
            dashRoot.addView(dashSlot, FrameLayout.LayoutParams(dp(72), dp(92), Gravity.TOP or Gravity.START))
            dashRoot.addView(dashLight, FrameLayout.LayoutParams(dp(42), dp(88), Gravity.TOP or Gravity.START))
            dashRoot.addView(dashLightDist, FrameLayout.LayoutParams(dp(72), -2, Gravity.TOP or Gravity.START))
            dashRoot.addView(chargeBar, FrameLayout.LayoutParams(dp(200), dp(30), Gravity.TOP or Gravity.START))
            dashRoot.addView(dashReady, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
            dashRoot.addView(dashBrake, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(30) })
            dashRoot.addView(l2FlyBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END))
            dashRoot.addView(climateBtn, FrameLayout.LayoutParams(dp(90), dp(30), Gravity.TOP or Gravity.END))
            dashRoot.addView(mediaBtn, FrameLayout.LayoutParams(dp(90), dp(30), Gravity.TOP or Gravity.END))
            refreshShortcuts()
        } else if (l1cApplied) {
            // Layout 1 with the 3D car: the speed sign sits left of the road, the traffic light and its distance right of it (placed in adaptDashLayout)
            dashMid.addView(dashReady, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            dashMid.addView(dashBrake, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            dashMid.addView(carView, LinearLayout.LayoutParams(-1, 0, 1f))
            dashMid.addView(tripSpacer, LinearLayout.LayoutParams(-1, 0, 0f))
            dashMid.addView(tripPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(3) })
            dashMid.addView(chargeBar, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(2) })
            dashRoot.addView(dashSlot, FrameLayout.LayoutParams(dp(72), dp(92), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            dashRoot.addView(dashLight, FrameLayout.LayoutParams(dp(42), dp(88), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            dashRoot.addView(dashLightDist, FrameLayout.LayoutParams(dp(72), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        } else {
            dashMid.addView(dashReady, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            dashMid.addView(dashSlot, LinearLayout.LayoutParams(-1, dp(92)).apply { topMargin = dp(4) })
            dashMid.addView(dashLightDist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            dashMid.addView(dashBrake, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            dashMid.addView(tripSpacer, LinearLayout.LayoutParams(-1, 0, 0f))
            dashMid.addView(tripPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(3) })
            dashMid.addView(chargeBar, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(2) })
        }
        dashMid.requestLayout(); dashRoot.requestLayout()
    }

    /** The shortcut tiles are the apps chosen for the lower buttons, plus an Add app tile. */
    private fun refreshShortcuts(first: Boolean = false) {
        if (!::pageSets.isInitialized || !::rightPager.isInitialized) return
        val tiles = ArrayList<ShortTile>()
        for (i in 0 until maxSlots) {
            val pkg = slotPkg(i)
            if (pkg.isNotEmpty()) tiles.add(ShortTile(appLabelOf(pkg), try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }, i))
        }
        tiles.add(ShortTile("Add app", null, -1))
        val n = maxOf(1, (tiles.size + 3) / 4)
        for (ps in pageSets) {
            while (ps.shorts.size < n) ps.shorts.add(ShortcutsCardView(this).apply {
                embedded = true
                onTile = { t -> if (t.slot < 0) addShortcutApp() else openLower(t.slot) }
                onLong = { t -> pickLowerApp(t.slot) }
            })
            while (ps.shorts.size > n) ps.shorts.removeAt(ps.shorts.size - 1)
            for ((i, v) in ps.shorts.withIndex()) { v.setTiles(tiles); v.showPage(i); v.day = dashDay; v.iconsOnly = prefs().getBoolean("iconBtns", false) }
        }
        // both cards cycle through the same pages: trip information, compass, navigation, media, the apps, then the controls
        fun pages(ps: PageSet): List<View> = listOf<View>(ps.trip, ps.compass, ps.nav, ps.media) + ps.shorts + listOf<View>(ps.actions)
        leftPager.setPages(pages(pageSets[0]), if (first) (if (curLayout() == 2) prefs().getInt("l2PageL", 0) else 0) else -1)      // Trip Information
        rightPager.setPages(pages(pageSets[1]), if (first) (if (curLayout() == 2) prefs().getInt("l2PageR", 1) else 1) else -1)     // Compass
    }

    private fun addShortcutApp() {
        val n = (0 until maxSlots).firstOrNull { slotPkg(it).isEmpty() }
        if (n == null) { toast("All 25 app spots are in use. Long-press a tile to change it."); return }
        if (prefs().getBoolean("iconBtns", false) && n >= slotCount) prefs().edit().putInt("slotN", n + 1).apply()
        refreshLowerLabels(); paintLowerTabs()
        pickLowerApp(n)
    }

    private var layoutRecreating = false
    private var startupPresetDone: Boolean
        get() = StartupFlag.done
        set(v) { StartupFlag.done = v }

    // ------------------------------------------------------- Layout 2 presets (up to 6)
    /** Settings that belong to the car, the install or the user's data rather than to how the screen looks: a preset leaves them alone. */
    private val presetSkip = setOf("currentPreset", "startupPreset", "presets", "presetsL2", "presetsL1", "devToken", "betaOn", "betaUnlocked", "updDeferred", "source", "legacyCan", "canSafe", "climateTx", "radioFavs", "reopenSettings", "reopenDash", "adasRestore", "adasOn", "spdAdjMph", "spdAdjKmh", "packKwh", "disclaimerOk", "disclaimerAt", "disclaimerVer", "carName", "carPaint", "carWheel", "solarDay", "solarDayMin", "solarDayMax", "lastDriveMode")

    private fun curLayout() = if (prefs().getInt("layout", 1) == 2) 2 else 1
    /** One shared list of up to 6 presets, whichever layout saved them (older per-layout lists are merged in the first time). */
    private fun readPresets(): MutableList<Pair<String, org.json.JSONObject>> {
        val out = ArrayList<Pair<String, org.json.JSONObject>>()
        fun parse(key: String, lay: Int) {
            try {
                val arr = org.json.JSONArray(prefs().getString(key, "[]"))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i); val d = o.getJSONObject("data")
                    if (!d.has("layout")) d.put("layout", org.json.JSONObject().put("t", "i").put("v", lay))
                    var n = o.getString("name"); var k = 2
                    while (out.any { it.first.equals(n, true) }) { n = o.getString("name") + " (" + k + ")"; k++ }
                    out.add(n to d)
                }
            } catch (e: Exception) { }
        }
        if (prefs().contains("presets")) parse("presets", 2) else { parse("presetsL2", 2); parse("presetsL1", 1) }
        return out
    }

    private fun writePresets(list: List<Pair<String, org.json.JSONObject>>) {
        val arr = org.json.JSONArray()
        for ((n, d) in list) arr.put(org.json.JSONObject().put("name", n).put("data", d))
        prefs().edit().putString("presets", arr.toString()).remove("presetsL1").remove("presetsL2").apply()
    }

    /** Every current setting (skin, layout, cards, shortcuts, options), typed, as JSON. */
    private fun snapshotPrefs(): org.json.JSONObject {
        val o = org.json.JSONObject()
        for ((k, v) in prefs().all) {
            if (k in presetSkip) continue
            val e = org.json.JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is String -> e.put("t", "s").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "ss").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        o.put("layout", org.json.JSONObject().put("t", "i").put("v", curLayout()))
        o.put("adasOn", org.json.JSONObject().put("t", "b").put("v", adasMode))      // the ADAS view is on or off right now (not a setting of its own)
        // Layout 2: which page each of the two cards under the car is showing (Trip Information and the one behind it), kept only in a preset
        if (l2Applied && ::leftPager.isInitialized && ::rightPager.isInitialized) {
            o.put("l2PageL", org.json.JSONObject().put("t", "i").put("v", leftPager.index))
            o.put("l2PageR", org.json.JSONObject().put("t", "i").put("v", rightPager.index))
        }
        return o
    }

    /** Layout 1's Presets button: Load or Save. */
    private fun showPresetsMenu() {
        val d = darkDialog().setTitle("Presets").setItems(arrayOf("Load", "Save")) { dlg, w -> dlg.dismiss(); if (w == 0) showLoadPreset() else showSavePreset() }
            .setNegativeButton("Cancel", null).showDark()
    }

    /** Save: when a preset was loaded, offer to update it or to save as a new one; otherwise go straight to naming a new one. */
    private fun showSavePreset() {
        val list = readPresets()
        val cur = prefs().getString("currentPreset", null)
        val at = if (cur == null) -1 else list.indexOfFirst { it.first.equals(cur, true) }
        if (at < 0) { showSaveNewPreset(); return }
        val name = list[at].first
        darkDialog().setTitle("Save Preset")
            .setMessage("Update current preset or Save as a new preset?\n\nCurrent preset loaded: \"$name\". Updating replaces it with the screen and settings as they are now.")
            .setPositiveButton("Update current") { _, _ ->
                val l2 = readPresets(); val i = l2.indexOfFirst { it.first.equals(name, true) }
                if (i >= 0) { l2[i] = l2[i].first to snapshotPrefs(); writePresets(l2); Toast.makeText(this, "Current preset \"$name\" updated", Toast.LENGTH_SHORT).show() }
            }
            .setNeutralButton("Save as new") { _, _ -> showSaveNewPreset() }
            .setNegativeButton("Cancel", null).showDark()
    }

    private fun showSaveNewPreset() {
        val input = EditText(this).apply {
            hint = "Preset name"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            filters = arrayOf(android.text.InputFilter.LengthFilter(65))
        }
        val startCb = CheckBox(this).apply { text = "Load on startup (the app always starts with this preset)"; setTextColor(Color.WHITE) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { if (e.toString().trim().equals(prefs().getString("startupPreset", null), true)) startCb.isChecked = true }
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0); addView(input); addView(startCb) }
        val d = darkDialog().setTitle("Save Preset")
            .setMessage("Saves the skin, layout, cards and every setting as they are now. Up to 12 presets; a name you have used is replaced.")
            .setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", null).showDark()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) { Toast.makeText(this, "Type a name for the preset", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val list = readPresets()
            val at = list.indexOfFirst { it.first.equals(name, true) }
            if (at < 0 && list.size >= 12) { Toast.makeText(this, "12 presets are saved already: use a saved name to replace one, or delete one in Load Preset", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val entry = name to snapshotPrefs()
            if (at >= 0) list[at] = entry else list.add(entry)
            writePresets(list)
            prefs().edit().putString("currentPreset", name).apply()
            if (startCb.isChecked) prefs().edit().putString("startupPreset", name).apply()
            else if (name.equals(prefs().getString("startupPreset", null), true)) prefs().edit().remove("startupPreset").apply()
            Toast.makeText(this, "Preset \"$name\" saved", Toast.LENGTH_SHORT).show()
            d.dismiss()
        }
    }

    private fun showLoadPreset() {
        val list = readPresets()
        if (list.isEmpty()) { darkDialog().setTitle("Load Preset").setMessage("None").setPositiveButton("OK", null).showDark(); return }
        var sel = -1
        val lv = ListView(this)
        fun isStart(n: String) = n.equals(prefs().getString("startupPreset", null), true)
        val adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(position: Int) = list[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val pr = list[position]
                val lay = (pr.second.optJSONObject("layout")?.optInt("v", 0)?.takeIf { l -> l == 1 || l == 2 } ?: 2)
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(8), dp(8))
                    setBackgroundColor(if (position == sel) Color.parseColor("#2B4A6B") else Color.TRANSPARENT)
                    // a selection circle on the left: the preset that Load will open
                    addView(RadioButton(this@MainActivity).apply { isChecked = position == sel; isFocusable = false; setOnClickListener { sel = position; notifyDataSetChanged() } }, LinearLayout.LayoutParams(dp(44), -2))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@MainActivity).apply { text = pr.first; textSize = 16f; setTextColor(Color.parseColor("#F0F3F8")) })
                        addView(TextView(this@MainActivity).apply { text = "Layout $lay"; textSize = 12f; setTextColor(Color.parseColor("#9FB3C4")) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    isClickable = true; isFocusable = false
                    setOnClickListener { sel = position; notifyDataSetChanged() }
                    addView(CheckBox(this@MainActivity).apply {
                        isFocusable = false
                        isChecked = isStart(pr.first)
                        setOnClickListener {
                            // only one preset starts the app: ticking this one clears the tick on the others; unticking leaves the app starting as it is
                            if (isChecked) prefs().edit().putString("startupPreset", pr.first).apply() else prefs().edit().remove("startupPreset").apply()
                            notifyDataSetChanged()
                        }
                    }, LinearLayout.LayoutParams(dp(90), -2))
                }
            }
        }
        lv.adapter = adapter
        lv.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(8), dp(4))
            addView(TextView(this@MainActivity).apply { text = "Select a preset, then Load"; textSize = 13f; setPadding(dp(44), 0, 0, 0); setTextColor(Color.parseColor("#9FB3C4")) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply { text = "Load at Startup"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#9FB3C4")) }, LinearLayout.LayoutParams(dp(90), -2))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(head)
            // the list scrolls inside a fixed share of the screen height, so the buttons below it are never pushed off, however many presets there are
            addView(lv, LinearLayout.LayoutParams(-1, if (list.size > 7) (resources.displayMetrics.heightPixels * 0.55f).toInt() else -2))
        }
        val d = darkDialog().setTitle("Load Preset").setView(col)
            .setPositiveButton("Load", null).setNeutralButton("Delete", null).setNegativeButton("Cancel", null).showDark()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (sel < 0) { Toast.makeText(this, "Select a preset first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            d.dismiss(); applyPreset(list[sel].first, list[sel].second)
        }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (sel < 0) { Toast.makeText(this, "Select a preset first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (list[sel].first.equals(prefs().getString("startupPreset", null), true)) prefs().edit().remove("startupPreset").apply()
            if (list[sel].first.equals(prefs().getString("currentPreset", null), true)) prefs().edit().remove("currentPreset").apply()
            list.removeAt(sel); writePresets(list); d.dismiss(); showLoadPreset()
        }
    }

    /** Puts every saved setting back (settings the preset does not hold go back to their defaults), then rebuilds the screen like a restart. */
    private fun writePresetToPrefs(data: org.json.JSONObject) {
        val ed = prefs().edit()
        for (k in prefs().all.keys) if (k !in presetSkip) ed.remove(k)
        for (k in data.keys()) {
            if (k == "adasOn") continue
            val e = data.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "s" -> ed.putString(k, e.getString("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "ss" -> { val a = e.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        if (!data.has("layout")) ed.putInt("layout", curLayout())
        ed.putBoolean("reopenDash", true)
        if (data.optJSONObject("adasOn")?.optBoolean("v", false) == true) ed.putBoolean("adasRestore", true)
        ed.commit()
        // read back what was written, and log any setting that did not stick
        var bad = 0; val now = prefs().all
        for (k in data.keys()) {
            if (k == "adasOn") continue
            val e = data.getJSONObject(k)
            val want: Any? = when (e.getString("t")) { "b" -> e.getBoolean("v"); "s" -> e.getString("v"); "i" -> e.getInt("v"); "l" -> e.getLong("v"); "f" -> e.getDouble("v").toFloat(); else -> null }
            if (want != null && now[k] != want) { bad++; EventLog.add("preset: $k is ${now[k]}, wanted $want") }
        }
        if (bad > 0) EventLog.add("preset: ${data.length()} settings written, $bad differ")
    }

    private fun applyPreset(name: String, data: org.json.JSONObject) {
        writePresetToPrefs(data)
        prefs().edit().putString("currentPreset", name).apply()
        layoutRecreating = true
        recreate()
    }


    /**
     * The two layouts are independent: changing layout rebuilds the whole screen from scratch (as when the app starts), so neither
     * can leave anything of itself behind in the other. Settings comes back up afterwards when it was opened from there.
     */
    private fun chooseLayout(id: Int, backToSettings: Boolean = false) {
        if (prefs().getInt("layout", 1) == id) return
        prefs().edit().putInt("layout", id).putBoolean("reopenSettings", backToSettings).apply()
        layoutRecreating = true
        recreate()
    }

    private fun showLayoutPicker(backToSettings: Boolean = false) {
        val cur = prefs().getInt("layout", 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var d: AlertDialog
        var picked = false      // choosing one goes straight to the dashboard; Close goes back to Settings
        val items = listOf(
            Triple(1, "Original", "Gauges either side, trip information in the middle."),
            Triple(2, "Layout 2", "Car, compass and app shortcuts in the middle, speed limit sign by the speed gauge, battery bar under the power gauge. Used when the window is at least about 640 dp wide; narrower windows keep Original."),
        )
        for ((id, name, note) in items) {
            val sel = id == cur
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1D2128")); cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (sel) 3 else 1), Color.parseColor(if (sel) "#2AA8FF" else "#3A4150"))
                }
                addView(ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(LayoutThumbs.make(id)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply { text = name + if (sel) "  \u2713" else ""; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, 0) })
                addView(TextView(context).apply { text = note; textSize = 12f; setTextColor(Color.parseColor("#B8C2D0")) })
                setOnClickListener { picked = true; chooseLayout(id, false); d.dismiss() }
            }
            col.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        d = darkDialog().setTitle("Layout").setView(ScrollView(this).apply { addView(col) }).setNegativeButton("Close", null).create()
        if (backToSettings) d.setOnDismissListener { if (!picked && !layoutRecreating) ui.post { showSettings() } }   // closed without choosing: back to Settings
        d.show()
        styleDark(d)
    }

    private fun showSkinPicker(backToSettings: Boolean = false) {
        val cur = Skins.current
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var d: AlertDialog
        var picked = false      // choosing one goes straight to the dashboard; Close goes back to Settings
        for (sk in Skins.list) {
            val sel = sk.id == cur
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1D2128")); cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (sel) 3 else 1), Color.parseColor(if (sel) "#2AA8FF" else "#3A4150"))
                }
                addView(ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(skinThumb(sk.id)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply { text = sk.name + if (sel) "  \u2713" else ""; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, 0) })
                addView(TextView(context).apply { text = sk.note; textSize = 12f; setTextColor(Color.parseColor("#B8C2D0")) })
                setOnClickListener { picked = true; chooseSkin(sk.id); d.dismiss() }
            }
            col.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        d = darkDialog().setTitle("Skin").setView(ScrollView(this).apply { addView(col) }).setNegativeButton("Close", null).create()
        if (backToSettings) d.setOnDismissListener { if (!picked) ui.post { showSettings() } }
        d.show()
        styleDark(d)
    }

    private fun dotDrawable(on: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(if (on) "#2ECC71" else "#E74C3C"))
        setStroke(dp(1), Color.WHITE)
    }

    private fun paintDot(on: Boolean) { dotView.background = dotDrawable(on) }

    // ------------------------------------------------------- Pulse CAN source
    private val canTick = object : Runnable {
        override fun run() {
            renderCan()
            if (useCan) ui.postDelayed(this, 500)
        }
    }

    private fun renderCan() {
        val p = pulse ?: return
        val s = canSnap
        // a new drive begins when the car goes from not ready to ready: the notice must be agreed to again
        if (s.ready == true && lastReadySeen == false) { cautionAgreedThisRun = false; showCaution() }
        if (s.ready != null) lastReadySeen = s.ready
        p.flipPower = prefs().getBoolean("flipPower", false)
        val live = System.currentTimeMillis() - p.lastChangeAt < 3000
        canDot.background = dotDrawable(live)
        canInfoView.text = if (!live) "No changing frames from the car yet (car asleep, or the reader isn't running)"
            else if (s.packKw == null) "Car awake. Battery power not received (the Pulse's CAN filters may need root)"
            else "Car awake: frames are updating"
        if (s.avgTempC != lastTempC) { lastTempC = s.avgTempC; showTemp(s.avgTempC, useFahrenheit) }
        val demand = when (s.thermalDemand) { 0 -> "starting up"; 1 -> "heating"; 2 -> "cooling"; 3 -> "cycling"; else -> "--" }
        fun tc(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 1)
        resultView.textSize = 16f
        resultView.text = heatLine(s)
        renderStats(canStats(s).toMap())
        if (::climateView.isInitialized && climateView.visibility == View.VISIBLE) { climateView.fahrenheit = useFahrenheit; climateView.snap = s }
    }

    private fun heatLine(s: CanSnapshot): String {
        val comp = s.compWatts
        val pump = when {
            comp == null -> "Heat pump: not received (CAN filter)"
            s.compState == 2 -> "Heat pump: FAULT"
            comp > 20 -> String.format("Heat pump ON %.2f kW", comp / 1000)
            else -> "Heat pump off"
        }
        val total = s.climateKw?.let { String.format("%.2f kW", it) } ?: "--"
        val other = if (s.climateKw != null && comp != null) {
            val k = maxOf(0.0, s.climateKw - comp / 1000)
            String.format("%.2f kW", k) + if (k >= 0.3) " (likely PTC heater)" else ""
        } else "n/a without heat pump reading"
        return "Climate total: $total\n$pump\nOther climate load: $other"
    }

    // ------------------------------------------------------------ Dashboard
    private lateinit var dashRoot: FrameLayout
    private lateinit var dashBtn: Button
    private lateinit var skinBtn: Button
    private lateinit var presetsBtn: Button      // Layout 1: replaces the Skin button
    private lateinit var settingsBtn: Button
    private lateinit var dashSpeedGauge: ArcGaugeView
    private lateinit var dashPowerGauge: ArcGaugeView
    private lateinit var dashLight: TrafficLightView
    private lateinit var dashLightDist: TextView
    private var lightColor = 0
    private var lightSeenAt = 0L
    private var lightShown = false
    private lateinit var testBtn: TextView
    private val SHOW_TEST_BUTTON = false
    private var signTest = false                          // the Test sign button: shows a speed limit sign and a cycling traffic light so they can be checked parked
    private lateinit var exitBtn: Button
    private lateinit var dashStrip: StatusStripView
    private lateinit var dashSign: SpeedSignView
    private lateinit var dashSignSrc: TextView
    private var lastTsrMode = -1
    private var signShown = false
    private var readyBeside = false          // 1/3 split: READY sits in the sign slot; centred when no sign or light is showing
    private var signSeenAt = 0L
    private var signText = ""
    private var introDone = false
    private var dashDay = false
    private var dayPending = false
    private var dayPendingSince = 0L
    private lateinit var dashRow: StatusStripView
    private lateinit var dashReady: TextView
    private lateinit var edgeBox: FrameLayout
    private var drivenSinceReady = false
    private lateinit var dashMid: LinearLayout
    private lateinit var poolBar: HorizontalScrollView
    private lateinit var poolRow: LinearLayout
    private var poolKey = ""
    private lateinit var dashGaugeRow: LinearLayout
    private lateinit var lowerTabs: LinearLayout
    private lateinit var lowerAdd: TextView                     // the + just outside the scrolling strip: adds an app button
    private lateinit var lowerScroll: HorizontalScrollView      // the app buttons scroll sideways when they do not all fit
    private fun showSkinBtn() = prefs().getBoolean("showSkin", false)
    private lateinit var lowerBox: LinearLayout
    private lateinit var tripPanel: TripPanelView
    private lateinit var tripBtn: TextView
    private lateinit var dashSlot: FrameLayout
    private lateinit var chargeBar: ChargeBarView
    private lateinit var dashClock: DashClockView
    private lateinit var updateBtn: TextView
    private var pendingUpdate: Updater.Release? = null
    private var lastUpdateCheck = 0L
    private var updateDialog: AlertDialog? = null
    private var updateBusy = false
    private var updPt: TextView? = null
    private var updBar: android.widget.ProgressBar? = null
    private var updPos: Button? = null
    private var updNeg: Button? = null
    private lateinit var dashTop: LinearLayout
    private var tripMidRows = 0
    private var statusMode: String? = null
    private lateinit var tripSpacer: View
    // Layout 2: the middle column holds the car, a compass card and a shortcuts card
    private lateinit var l2Mid: LinearLayout
    private lateinit var carView: CarView
    private lateinit var pageSets: List<PageSet>
    /** One copy of every page of the two swipe cards (a view can only be in one place, so each card has its own set). */
    private inner class PageSet {
        val trip = TripPanelView(this@MainActivity).apply {
            cols = 2; title = "Trip Information"
            onReset = { pulse?.resetDrive(); Toast.makeText(context, "Trip reset", Toast.LENGTH_SHORT).show() }
        }
        val compass = CompassCardView(this@MainActivity)
        val nav = NavCardView(this@MainActivity).apply {
            onTap = { try { packageManager.getLaunchIntentForPackage(NavState.pkg)?.let { startActivity(it) } } catch (e: Exception) { } }
        }
        val media = MediaCardView(this@MainActivity).apply {
            fullBleed = true
            onPrev = { if (InApp.mode != InApp.EXTERNAL) InApp.prev() else mediaWatcher?.prev() }
            onToggle = { if (InApp.mode != InApp.EXTERNAL) InApp.toggle() else mediaWatcher?.toggle() }
            onNext = { if (InApp.mode != InApp.EXTERNAL) InApp.next() else mediaWatcher?.next() }
            onSources = { showSources() }
            onFavs = { showFavorites() }
            onTap = { if (InApp.mode == InApp.EXTERNAL) openMusicApp(false) }
        }
        val shorts = ArrayList<ShortcutsCardView>()
        val actions = ActionCardView(this@MainActivity).apply {
            provider = {
                listOf(ActionTile("\u2699", "Settings") { settingsBtn.performClick() },
                    ActionTile("\u21C5", resBtn.text.toString()) { resBtn.performClick() },
                    ActionTile("\u25A3", "Hide dashboard") { dashBtn.performClick() },
                    ActionTile("\u2715", "Exit") { exitBtn.performClick() }) +
                    (if (showSkinBtn()) listOf(ActionTile("\u2726", "Skin") { skinBtn.performClick() }) else emptyList())
            }
        }
    }
    private lateinit var l2FlyBtn: TextView
    private lateinit var roadView: RoadView
    private var leadHoldUntil = 0L
    // ADAS view: the ADAS View button at the top (full screen only) hides the trip info / battery bar (Layout 1) or the battery bar and shortcuts (Layout 2) and enlarges the car and road
    private var adasMode = false
    private var adasDy = 0f
    private var adasRestoreTries = 0
    private var adasHideWas = false
    private var adasLowOn = false; private var adasZoneTop = 0f        // ADAS view with the car moved down under the media player (option), and the top of that space
    private var adasNoCards = false                                  // ADAS view with Climate and Media both closed (set by adasFit)
    private lateinit var adasSpdBox: LinearLayout; private lateinit var adasSpdNum: TextView; private lateinit var adasSpdTitle: TextView
    private lateinit var adasPwrBox: LinearLayout; private lateinit var adasPwrNum: TextView; private lateinit var adasPwrTitle: TextView
    private lateinit var adasClockBox: LinearLayout; private lateinit var adasClockNum: TextView; private lateinit var adasClockTitle: TextView
    private lateinit var adasTempBox: LinearLayout; private lateinit var adasTempNum: TextView; private lateinit var adasTempTitle: TextView
    private val adasTimeFmt by lazy { java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", java.util.Locale.getDefault()) }
    private val adasDateFmt by lazy { java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()) }
    private var adasAnim: android.animation.ValueAnimator? = null
    private var adasBar: View? = null
    private val adasHidden = ArrayList<View>()
    private lateinit var objView: ObjectsView
    private var car3d: Car3dView? = null
    private lateinit var l2ClimBtn: TextView
    private lateinit var l2MediaBtn: TextView
    private lateinit var l2LoadBtn: TextView
    private lateinit var l2SaveBtn: TextView
    private var l2ClimTop = 0; private var l2ClimH = 0; private var l2ClimInline = false; private var wasL2Clim = false
    private var l2ClimClosed: Boolean      // Layout 2: the user closed the Climate screen; kept as a setting, so a relayout, a restart or a loaded preset leaves it closed
        get() = prefs().getBoolean("l2ClimClosed", false)
        set(v) { prefs().edit().putBoolean("l2ClimClosed", v).apply() }
    private var l2MediaInPlace = false; private var l2MiniNow = false      // 2/3 split: the Media button swaps the Climate panel for the media card
    private var l2BtnRowBottom = 0; private var l2BtnW = 60; private var l2ClimHidden = false   // the Media / Climate button row, and whether the Climate button hid the inline panel
    private var l2FlyOpen = false
    private val closeL2Fly = Runnable { l2FlyOpen = false; if (::btnBar.isInitialized && l2Applied) btnBar.visibility = View.GONE }
    private lateinit var leftPager: PagerCardView
    private lateinit var rightPager: PagerCardView

    private var l2Applied = false
    private var l1cApplied = false                       // Layout 1 with the 3D car in the middle column
    private var ovLeftDp = 0
    private var ovRightDp = 0
    private var edgeLeftDp = 0
    private var edgeRightDp = 0
    private lateinit var btnBar: LinearLayout
    private var gaugeWeight = 1.15f
    private var midWeight = 0.7f
    private var tripDlgView: TripPanelView? = null
    private lateinit var resBtn: TextView
    private lateinit var flyBtn: TextView
    private lateinit var editBackdrop: View
    private var flyOpen = false
    private var lowerInline = true
    private var resCompact = true

    /** Which of the app buttons / flyout button show: never while the stat editor is open, so they do not cover it. */
    private fun applyLowerVisibility() {
        if (!::lowerBox.isInitialized || !::flyBtn.isInitialized) return
        flyBtn.visibility = View.GONE
        lowerBox.visibility = if (l2Applied) View.GONE else View.VISIBLE
    }

    private fun updateResText() {
        resBtn.text = if (screenH == 0) "Screen size" else if (resCompact) "\u21C5 $screenH" else "Screen: 1080 \u00D7 $screenH  \u21C5"
    }
    private var screenH = 0       // 1920 or 1784 once known
    private val maxSlots = 25
    /** Four app buttons with names; with icons the user can add more with the + beside the scrolling strip (up to 25). */
    private val slotCount get() = if (prefs().getBoolean("iconBtns", false)) prefs().getInt("slotN", if (prefs().getBoolean("moreBtns", false)) 6 else 4).coerceIn(4, maxSlots) else 4
    /** Empties a button; in icon mode an extra button (5 and up) is removed and the ones after it move up. */
    private fun clearSlot(i: Int) {
        val n = slotCount
        val e = prefs().edit()
        if (prefs().getBoolean("iconBtns", false) && i >= 4 && n > 4) {
            for (j in i until n - 1) e.putString("slotPkg$j", slotPkg(j + 1))
            e.remove("slotPkg${n - 1}").putInt("slotN", n - 1)
        } else e.remove("slotPkg$i")
        e.apply()
        refreshLowerLabels(); paintLowerTabs()
    }
    /** The app a lower button opens (a package name); empty until the user picks one. */
    private fun slotPkg(i: Int): String = prefs().getString("slotPkg$i", "") ?: ""
    private fun appLabelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
    private fun slotName(i: Int): String = slotPkg(i).let { if (it.isEmpty()) "empty button" else appLabelOf(it) }
    private var lowerIdx = 0
    private val swipe by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                // a swipe that began on one of the Layout 2 swipe cards (shortcuts, media ...) only changes that card's page
                if (l2Applied && ::leftPager.isInitialized && ::rightPager.isInitialized) for (pg in listOf<View>(leftPager, rightPager)) {
                    if (pg.visibility != View.VISIBLE || pg.width == 0) continue
                    val pl = IntArray(2); pg.getLocationOnScreen(pl)
                    if (e1.rawX >= pl[0] && e1.rawX <= pl[0] + pg.width && e1.rawY >= pl[1] && e1.rawY <= pl[1] + pg.height) return false
                }
                if (ShortcutGuard.locked()) return false
                val rowLoc = IntArray(2)
                dashRow.getLocationOnScreen(rowLoc)
                if (e1.rawY >= rowLoc[1]) return false      // started on the readouts at the bottom
                if (poolBar.visibility == View.VISIBLE) {
                    val pl = IntArray(2); poolBar.getLocationOnScreen(pl)
                    if (e1.rawY >= pl[1]) return false      // started on the list of extra readouts
                }
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val d = resources.displayMetrics.density
                if (Math.abs(dx) > dp(90) && Math.abs(dx) > Math.abs(dy) * 1.5f && Math.abs(vx) > 500 * d) {
                    // finger moves left: next app; right: previous
                    val step = if (dx < 0) 1 else slotCount - 1
                    var j = lowerIdx
                    repeat(slotCount) { j = (j + step) % slotCount; if (slotPkg(j).isNotEmpty()) { openLower(j); return false } }
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        watchCarLongPress(ev)
        if (dashShown && !StatusStripView.dragging) swipe.onTouchEvent(ev)      // only watches: touches still reach the buttons
        return super.dispatchTouchEvent(ev)
    }
    private var layoutKey = ""
    private var compact = false
    private lateinit var dashHeading: TextView
    private lateinit var dashMode: TextView
    private lateinit var dashBrake: TextView
    private var dashShown = false

    private fun prefs() = getSharedPreferences("prefs", MODE_PRIVATE)
    private fun speedMph() = prefs().getBoolean("mph", false)

    // ---- speedometer adjustment: one correction per 10-unit band of speed (0-10 ... 150-160), in km/h or mph as chosen
    private val adjKmDefault = intArrayOf(1, 1, 1, 2, 2, 3, 4, 4, 5, 6, 7, 7, 7, 8, 8, 9)
    private fun adjDefaults(mph: Boolean): IntArray =
        if (!mph) adjKmDefault.clone()
        else IntArray(16) { i -> Math.round(adjKmDefault[((i * 10 + 5) * 1.609344 / 10).toInt().coerceIn(0, 15)] * 0.621371).toInt() }
    private fun adjTable(mph: Boolean): IntArray {
        val saved = prefs().getString(if (mph) "spdAdjMph" else "spdAdjKmh", null)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        return if (saved != null && saved.size == 16) saved.toIntArray() else adjDefaults(mph)
    }
    private fun speedAdjFor(v: Double, mph: Boolean): Int = adjTable(mph)[(v / 10).toInt().coerceIn(0, 15)]

    /** "Join the Beta": a password first, then the tick box that lets this app see pre-release builds. */
    /** Pulse hardware health: temperatures, CPU, memory, fan and storage of the head unit, refreshed every 2 s while open. */
    private fun showHealth() {
        val green = Color.parseColor("#4CD964"); val amber = Color.parseColor("#FFB300"); val red = Color.parseColor("#FF5A4F"); val ink = Color.parseColor("#F0F3F8"); val mute = Color.parseColor("#9AA6B6")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(6)) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(body)
        val sv = ScrollView(this).apply { addView(box) }
        val d = darkDialog().setTitle("Pulse hardware health").setView(sv).setNegativeButton("Close", null).showDark()
        fun head(t: String) = body.addView(TextView(this).apply { text = t; textSize = 12f; setTextColor(mute); setPadding(0, dp(12), 0, dp(2)); letterSpacing = 0.06f })
        fun row(label: String, value: String, col: Int = ink) {
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2))
                addView(TextView(context).apply { text = label; textSize = 14f; setTextColor(ink) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply { text = value; textSize = 14f; setTextColor(col); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END }, LinearLayout.LayoutParams(-2, -2))
            })
        }
        fun bar(frac: Float, col: Int) {
            body.addView(object : View(this) {
                val pp = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: Canvas) {
                    val w = width.toFloat(); val h = height.toFloat()
                    pp.color = Color.argb(40, 255, 255, 255); c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, pp)
                    pp.color = col; c.drawRoundRect(0f, 0f, maxOf(h, w * frac.coerceIn(0f, 1f)), h, h / 2, h / 2, pp)
                }
            }, LinearLayout.LayoutParams(-1, dp(6)).apply { bottomMargin = dp(4) })
        }
        fun tempCol(t: Float) = if (t >= 85f) red else if (t >= 72f) amber else green
        fun gb(kb: Long) = String.format("%.1f GB", kb / 1048576.0)
        fun render(h: Health.Snap) {
            body.removeAllViews()
            if (h.board.isNotBlank()) row("Board", h.board.take(40), mute)
            row("Uptime", h.uptimeSec.let { "${it / 3600} h ${(it % 3600) / 60} min" })
            head("TEMPERATURE")
            if (h.temps.isEmpty()) row("Temperature sensors", "not readable", mute)
            val hottest = h.temps.maxByOrNull { it.second }
            h.temps.forEach { (n, t) -> row(n.replace("-thermal", "").replace("_", " ").ifBlank { "zone" }, String.format("%.0f \u00B0C", t), tempCol(t)) }
            if (hottest != null) { bar(hottest.second / 100f, tempCol(hottest.second)) }
            head("PROCESSOR")
            if (h.loadPct != null) { row("CPU load", String.format("%.0f %%", h.loadPct), if (h.loadPct > 90) amber else ink); bar(h.loadPct / 100f, if (h.loadPct > 90) amber else green) }
            else row("CPU load", "measuring...", mute)
            if (h.cores.isNotEmpty()) {
                val groups = h.cores.groupBy { it.third }          // cores that share a top speed: the little and the big cores
                groups.values.forEach { g ->
                    val cur = g.map { it.second }; val mx = g.first().third
                    val throttled = cur.any { it < mx * 0.5 } && false
                    row("Cores ${g.first().first.removePrefix("cpu")}-${g.last().first.removePrefix("cpu")}", cur.joinToString(" / ") + " MHz  (max $mx)", if (throttled) amber else ink)
                }
            }
            if (h.gpu != null) row("GPU", h.gpu)
            head("MEMORY")
            if (h.memTotalKb > 0) {
                val used = h.memTotalKb - h.memAvailKb; val f = used.toFloat() / h.memTotalKb
                row("RAM used", "${gb(used)} of ${gb(h.memTotalKb)}", if (f > 0.9f) red else if (f > 0.8f) amber else ink); bar(f, if (f > 0.9f) red else if (f > 0.8f) amber else green)
                row("RAM free", gb(h.memAvailKb))
                if (h.swapTotalKb > 0) row("Swap used", "${gb(h.swapTotalKb - h.swapFreeKb)} of ${gb(h.swapTotalKb)}")
            } else row("Memory", "not readable", mute)
            head("STORAGE")
            if (h.storageTotal > 0) { val f = h.storageUsed.toFloat() / h.storageTotal; row("Free space", gb(h.storageTotal - h.storageUsed) + " of " + gb(h.storageTotal), if (f > 0.92f) red else if (f > 0.85f) amber else ink); bar(f, if (f > 0.92f) red else if (f > 0.85f) amber else green) }
            else row("Storage", "not readable", mute)
            head("FAN")
            if (h.fans.isEmpty()) row("Fan", "no readout on this board", mute) else h.fans.forEach { row("Fan", it) }
            if (!h.rootUsed) body.addView(TextView(this).apply { text = "Root is not available, so some readings may be missing."; textSize = 12f; setTextColor(mute); setPadding(0, dp(10), 0, 0) })
        }
        var alive = true
        d.setOnDismissListener { alive = false }
        body.addView(TextView(this).apply { text = "Reading..."; textSize = 14f; setTextColor(mute) })
        Thread {
            while (alive) {
                val h = try { Health.read() } catch (e: Throwable) { null }
                if (h != null) ui.post { if (alive) render(h) }
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    private var audioPermAsked = false
    /** The visualizer listens to the audio output. Grant that permission as root when possible, otherwise ask once. */
    private fun ensureAudioPermission() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED || audioPermAsked) return
        audioPermAsked = true
        Thread {
            try { if (Updater.rootAvailable()) Root.exec("pm grant $packageName android.permission.RECORD_AUDIO", 10) } catch (e: Throwable) {}
            ui.post {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED && prefs().getBoolean("vizOn", true))
                    try { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4712) } catch (e: Throwable) {}
            }
        }.start()
    }

    private fun showBetaGate() {
        // the password is asked only once: after it was entered correctly (or the beta is already on) the beta screen opens straight away
        if (prefs().getBoolean("betaUnlocked", false) || prefs().getBoolean("betaOn", false)) {
            prefs().edit().putBoolean("betaUnlocked", true).apply()
            showBetaOptions(); return
        }
        val pw = EditText(this).apply {
            hint = "Password"; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#8893A3"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val holder = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(pw, LinearLayout.LayoutParams(-1, -2)) }
        val d = darkDialog().setTitle("Join the Beta").setMessage("Enter the beta password.").setView(holder)
            .setPositiveButton("OK", null).setNegativeButton("Cancel", null).showDark()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (pw.text.toString() == "Pulse!!!") {
                d.dismiss()
                if (prefs().getBoolean("betaOn", false)) showBetaOptions()      // already in the beta: the screen with the opt-out tick box
                else {
                    // joining: remember the password was right (never asked again) and turn the beta on, then offer the newest beta build straight away
                    prefs().edit().putBoolean("betaUnlocked", true).putBoolean("betaOn", true).putInt("updDeferred", 0).apply()
                    EventLog.add("beta builds on (joined with the password)")
                    toast("You joined the beta. Checking for the latest beta build...")
                    checkForUpdate(true)
                }
            }
            else { pw.setText(""); pw.error = "Wrong password" }
        }
    }

    private fun showBetaOptions() {
        val cb = CheckBox(this).apply {
            text = "Get beta builds (test versions, before everyone else)"; setTextColor(Color.WHITE)
            isChecked = prefs().getBoolean("betaOn", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("betaOn", on).apply(); EventLog.add("beta builds " + (if (on) "on" else "off")) }
        }
        val tokenBox = EditText(this).apply {
            hint = "Developer token (optional)"; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#7C8794")); textSize = 13f
            isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs().getString("devToken", ""))
        }
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(cb)
            addView(TextView(this@MainActivity).apply { text = "Developer token: a read-only GitHub token for this repository. With it, the unreleased developer builds are offered too. Leave empty for normal use."; textSize = 12f; setTextColor(Color.parseColor("#9AA6B4")); setPadding(0, dp(10), 0, dp(4)) })
            addView(tokenBox) }
        darkDialog().setTitle("Beta").setMessage("Beta builds may have bugs. Untick this to get only the tested releases again.").setView(holder)
            .setPositiveButton("Done") { _, _ -> val t = tokenBox.text.toString().trim(); if (t != (prefs().getString("devToken", "") ?: "")) { prefs().edit().putString("devToken", t).apply(); EventLog.add("developer token " + (if (t.isEmpty()) "cleared" else "set")) } }.setNeutralButton("Check now") { _, _ -> val t = tokenBox.text.toString().trim(); prefs().edit().putString("devToken", t).apply(); checkForUpdate(true) }.showDark()
    }

    private fun showSpeedAdjust() {
        val mph = speedMph(); val u = if (mph) "mph" else "km/h"
        val cur = adjTable(mph)
        val light = Color.parseColor("#F0F3F8")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4)) }
        box.addView(TextView(this).apply {
            text = "Added to the speed shown on the speed gauge, in $u. Use a minus sign to subtract. A stopped car always shows 0."
            textSize = 13f; setTextColor(Color.parseColor("#B8C2CF")); setPadding(0, 0, 0, dp(8))
        })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply { text = "Speed ($u)"; textSize = 12f; setTextColor(Color.parseColor("#9AA6B4")) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply { text = "Adjustment"; textSize = 12f; setTextColor(Color.parseColor("#9AA6B4")); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(head)
        val fields = ArrayList<EditText>()
        for (i in 0 until 16) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = "${i * 10} \u2013 ${i * 10 + 10}"; textSize = 16f; setTextColor(light) }, LinearLayout.LayoutParams(0, -2, 1f))
            val et = EditText(this).apply {
                setText(cur[i].toString()); textSize = 16f; setTextColor(light); gravity = Gravity.END; setSelectAllOnFocus(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(3))
            }
            fields.add(et)
            row.addView(et, LinearLayout.LayoutParams(dp(90), -2))
            box.addView(row)
        }
        val sv = ScrollView(this).apply { addView(box) }
        val d = darkDialog().setTitle("Speedometer Adjustment").setView(sv)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).setNeutralButton("Reset", null).create()
        d.show(); styleDark(d)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val vals = fields.map { it.text.toString().trim().toIntOrNull() ?: 0 }
            prefs().edit().putString(if (mph) "spdAdjMph" else "spdAdjKmh", vals.joinToString(",")).apply()
            d.dismiss()
        }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { adjDefaults(mph).forEachIndexed { i, v -> fields[i].setText(v.toString()) } }
    }

    private fun wantLive3d(): Boolean = prefs().getBoolean("l2Live3d", true)

    /** The transparent ADAS View On/Off button at the very top, just left of the road (right of it in right-hand drive); full screen, Layouts 1 and 2. */
    private fun placeAdasBar(root: FrameLayout) {
        val show = (l1cApplied || l2Applied) && isFullWindow() && carView.width > 0 && dashMid.width > 0
        var b = adasBar as? TextView
        if (b == null) {
            if (!show) return
            b = TextView(this).apply {
                textSize = 13f; setTextColor(Color.argb(150, 255, 255, 255)); gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                background = GradientDrawable().apply { setColor(Color.argb(90, 128, 128, 128)); cornerRadius = dp(15).toFloat() }
                setOnClickListener { setAdas(!adasMode) }
            }
            root.addView(b, FrameLayout.LayoutParams(dp(120), dp(30), Gravity.TOP or Gravity.START).apply { topMargin = dp(5) })
            adasBar = b
        }
        b.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        b.text = if (adasMode) "ADAS View On" else "ADAS View Off"
        val r = IntArray(2); val ma = IntArray(2)
        root.getLocationInWindow(r); dashMid.getLocationInWindow(ma)
        val cx = ma[0] - r[0] + dashMid.width / 2f
        val half = 86f * carView.height / 162f
        // just left of the road; right-hand drive: just right of it
        val x = if (prefs().getBoolean("rhd", false)) cx + half + dp(2) else cx - half - dp(2) - dp(120)     // 12 dp closer to the road than before, away from the clock
        b.translationX = x.coerceIn(dp(8).toFloat(), (root.width - dp(128)).toFloat().coerceAtLeast(dp(8).toFloat()))
        b.bringToFront()
    }

    /** True when our window is the full screen (not a half or third split). The ADAS view is only offered then. */
    private fun isFullWindow(): Boolean = try {
        // the ADAS view is offered in the full screen and in the split windows down to a third of the height
        if (android.os.Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.height() >= windowManager.maximumWindowMetrics.bounds.height() * 0.28 else true
    } catch (e: Exception) { true }

    /** ADAS view + the "hide gauges" option: the two gauges fade out and compact Speed / Power numbers (title underneath) fade in at the top corners, on the side each gauge was. */
    private fun applyAdasGauges() {
        if (!::dashSpeedGauge.isInitialized || !::adasSpdBox.isInitialized) return
        val hide = adasMode && prefs().getBoolean("adasHideGauges", false)
        fun fade(v: View, show: Boolean) {
            if (show) { if (v.visibility != View.VISIBLE || v.alpha < 1f) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(150).start() } }
            else if (v.visibility == View.VISIBLE) v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.INVISIBLE }.start()
        }
        fade(dashSpeedGauge, !hide); fade(dashPowerGauge, !hide)
        fade(adasSpdBox, hide); fade(adasPwrBox, hide); fade(adasClockBox, hide); fade(adasTempBox, hide)
        // Layouts 1 and 2: with the clock in the middle, "Ready" (and the brake-light note under it) moves down to clear the clock and date
        val readyShift = if (hide && (l2Applied || l1cApplied) && adasClockBox.height > 0) (adasClockBox.height + dp(10)).toFloat() else 0f
        for (v in listOf<View>(dashReady, dashBrake)) if (v.translationY != readyShift) v.translationY = readyShift
        // Layout 1: the trip information (and the battery bar under it) move down until the road lines and sensor arcs, which run to the bottom of the car view, are clear of it;
        // never lower than the media player allows (with room kept for the word Ready when it sits under the panel)
        if (::tripPanel.isInitialized && ::carView.isInitialized) {
            var dy = 0f
            if (l1cApplied && !adasMode && tripPanel.isShown && tripPanel.height > 0 && carView.isShown && carView.height > 0) {
                val cp = IntArray(2); carView.getLocationInWindow(cp)
                val carBottom = cp[1] + carView.height * carView.scaleY
                val tp = IntArray(2); tripPanel.getLocationInWindow(tp)
                val top0 = tp[1] - tripPanel.translationY
                var bot0 = top0 + tripPanel.height
                val barOn = ::chargeBar.isInitialized && chargeBar.isShown && chargeBar.height > 0
                if (barOn) { val bp = IntArray(2); chargeBar.getLocationInWindow(bp); bot0 = maxOf(bot0, bp[1] - chargeBar.translationY + chargeBar.height) }
                val rp0 = IntArray(2); dashRoot.getLocationInWindow(rp0)
                var floor = (rp0[1] + dashRoot.height).toFloat()
                if (::mediaCard.isInitialized && mediaCard.isShown && mediaCard.height > 0) { val mp = IntArray(2); mediaCard.getLocationInWindow(mp); floor = mp[1].toFloat() }
                val readyRoom = if (dashReady.visibility == View.VISIBLE && dashLight.isShown && dashLight.alpha > 0.1f) dashReady.height + dp(4) else 0
                val room = (floor - bot0 - dp(4) - readyRoom).coerceAtLeast(0f)
                dy = (carBottom - top0 + dp(4)).coerceAtLeast(0f).coerceAtMost(room)
            }
            if (Math.abs(tripPanel.translationY - dy) > 1f) tripPanel.translationY = dy
            if (::chargeBar.isInitialized && Math.abs(chargeBar.translationY - dy) > 1f) chargeBar.translationY = dy
        }
        // Layout 1, traffic light showing (it would sit on top of the word): Ready moves to just under the trip information, above the media player
        if (l1cApplied && !adasMode && dashReady.visibility == View.VISIBLE && dashLight.isShown && dashLight.alpha > 0.1f && ::tripPanel.isInitialized && tripPanel.height > 0) {
            val tp = IntArray(2); tripPanel.getLocationInWindow(tp)
            var bottom = tp[1] + tripPanel.height
            if (::chargeBar.isInitialized && chargeBar.isShown && chargeBar.height > 0) { val cp = IntArray(2); chargeBar.getLocationInWindow(cp); bottom = maxOf(bottom, cp[1] + chargeBar.height) }
            val rp = IntArray(2); dashReady.getLocationInWindow(rp)
            val dy = (bottom - (rp[1] - dashReady.translationY) + dp(2)).toFloat()
            if (Math.abs(dashReady.translationY - dy) > 1f) dashReady.translationY = dy
        }
        val ta = if (hide) 0f else 1f
        if (::dashClock.isInitialized && dashClock.alpha != ta) dashClock.animate().alpha(ta).setDuration(150).start()      // the normal clock steps aside for the centred one
        if (hide || adasHideWas) dashRoot.post { placeL1Overlays(dashRoot) }      // also once more when it ends, so the sign and light go back
        adasHideWas = hide
        if (hide) {
            // the numbers sit on the side each gauge is on (the gauges' real positions: the swap setting, in left- and right-hand drive alike)
            val ra = IntArray(2); val ga = IntArray(2)
            dashRoot.getLocationInWindow(ra); dashSpeedGauge.getLocationInWindow(ga)
            val speedLeft = if (dashSpeedGauge.width > 0 && dashRoot.width > 0) (ga[0] - ra[0]) + dashSpeedGauge.width * dashSpeedGauge.scaleX / 2f < dashRoot.width / 2f else prefs().getBoolean("speedLeft", false)
            for ((box, left) in listOf(adasSpdBox to speedLeft, adasPwrBox to !speedLeft)) {
                val lp = box.layoutParams as FrameLayout.LayoutParams
                val g = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
                if (lp.gravity != g) { lp.gravity = g; lp.leftMargin = if (left) dp(16) else 0; lp.rightMargin = if (left) 0 else dp(16); box.layoutParams = lp }
            }
            val now = java.util.Date()
            adasClockNum.text = adasTimeFmt.format(now); adasClockTitle.text = adasDateFmt.format(now)
            adasTempNum.text = canSnap.outsideC?.let { fmt(it, "\u00B0C", 0) } ?: "--"; adasTempTitle.text = "OUTSIDE"
            val cw = adasClockBox.width; val tw = adasTempBox.width
            if (cw > 0 && tw > 0) adasTempBox.translationX = (if (prefs().getBoolean("rhd", false)) -1f else 1f) * (cw / 2f + tw / 2f + dp(22))
        }
    }

    /** While the ADAS view is on, follows changes in what is below it: Climate / Media cards coming or going move the car back up or down. */
    private fun refitAdas() {
        if (!adasMode || adasAnim?.isRunning == true || !::carView.isInitialized || carView.width <= 0) return
        val fit = adasFit()
        val k0 = carView.scaleX; val dy0 = adasDy
        if (Math.abs(fit[0] - k0) < 0.02f && Math.abs(fit[1] - dy0) < dp(2)) return
        adasAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val k = k0 + (fit[0] - k0) * t
                carView.scaleX = k; carView.scaleY = k
                adasDy = dy0 + (fit[1] - dy0) * t
                placeL1Overlays(dashRoot); syncCar3d(dashRoot)
                if (::roadView.isInitialized) roadView.invalidate()
                if (::objView.isInitialized) objView.invalidate()
            }
            start()
        }
    }

    /** Where the ADAS view should end up: the scale of the car view and how far it moves down, from what is on screen now (cards, strip). */
    private fun adasFit(): FloatArray {
        val l2 = l2Applied
        var kTo = 1f; var dyTo = 0f
            val u0 = carView.height / 162f
            val kW = 0.8f * carView.width / (114f * u0)           // keep the car well inside its column
            // the road and car grow, then the whole view moves down, until the bottom of the road meets the top of the media card
            // (or the Climate panel, or the bottom of the middle column when neither is up)
            val ra = IntArray(2); val pa = IntArray(2)
            dashRoot.getLocationInWindow(ra); (carView.parent as View).getLocationInWindow(pa)
            val carTop = (pa[1] - ra[1] + carView.top + dp(14)).toFloat()
            var target = Float.MAX_VALUE
            var mediaBottom = -1f; var climateUp = false
            for (v in listOfNotNull<View>(if (::mediaCard.isInitialized) mediaCard else null, if (::climateView.isInitialized) climateView else null, if (::mediaPopup.isInitialized) mediaPopup else null)) if (v.visibility == View.VISIBLE && v.height > 0 && v.isShown) {
                val va = IntArray(2); v.getLocationInWindow(va)
                val top = (va[1] - ra[1]).toFloat()
                if (top > carTop + carView.height * 0.6f && top < target) target = top
                if (v === (if (::mediaCard.isInitialized) mediaCard else null)) mediaBottom = top + v.height
                if (::climateView.isInitialized && v === climateView) climateUp = true
            }
            var noCards = false
            if (target == Float.MAX_VALUE) {
                noCards = true
                val da = IntArray(2); dashMid.getLocationInWindow(da)
                target = (da[1] - ra[1] + dashMid.height).toFloat()
                if (l2 && l2Mid.childCount > 1) { val c1 = l2Mid.getChildAt(1); val ca2 = IntArray(2); c1.getLocationInWindow(ca2); target = (ca2[1] - ra[1] + c1.height).toFloat() }
                // Climate and Media are off: the view runs all the way down to the top of the stat strip at the bottom (Range, Regen, ...), or the row under it
                for (sv in listOf<View>(dashStrip, dashRow)) if (sv.visibility == View.VISIBLE && sv.height > 0 && sv.isShown) {
                    val sa = IntArray(2); sv.getLocationInWindow(sa)
                    val st = (sa[1] - ra[1]).toFloat()
                    if (st > carTop + carView.height) { target = st; break }
                }
            }
            if (adasNoCards != noCards) { adasNoCards = noCards; dashRoot.post { placeL1Overlays(dashRoot) } }
            target -= dp(4)
            val kH = (target - carTop) / carView.height
            kTo = minOf(if (noCards) maxOf(kW, 1.4f * carView.width / (114f * u0)) else kW, kH, if (noCards) 3.2f else 1.8f).coerceAtLeast(1f)
            // the live 3D car's view is 2.4 car-squares wide (room for the headlight beams) and may run past the screen edges; cap it at a width the graphics can draw
            val c3 = car3d
            if (c3 != null && c3.live) kTo = minOf(kTo, 2600f / (2.4f * 150f * u0)).coerceAtLeast(1f)      // (keeps the 3D view's width within what the graphics can draw)
            dyTo = (target - carTop - kTo * carView.height).coerceAtLeast(0f)
            // option: with Climate and Media closed, the car sits in the middle of the screen instead of at the bottom (the road lines run up from it)
            if (noCards && prefs().getBoolean("adasCenter", false)) dyTo = minOf(dyTo, (dashRoot.height / 2f - carTop - kTo * carView.height / 2f).coerceAtLeast(0f))
            // option: Media on, Climate closed: the car and road move down into the free space under the media player, ending just above the stat strip
            var low = false; var zoneTop = 0f
            if (!noCards && !climateUp && mediaBottom > 0f && prefs().getBoolean("adasLow", false)) {
                var zb = Float.MAX_VALUE
                for (sv in listOf<View>(dashStrip, dashRow)) if (sv.visibility == View.VISIBLE && sv.height > 0 && sv.isShown) { val sa = IntArray(2); sv.getLocationInWindow(sa); val st = (sa[1] - ra[1]).toFloat(); if (st > mediaBottom + dp(60)) { zb = st; break } }
                if (zb == Float.MAX_VALUE) { val da = IntArray(2); dashMid.getLocationInWindow(da); zb = (da[1] - ra[1] + dashMid.height).toFloat() }
                zb -= dp(4)
                val zh = zb - (mediaBottom + dp(6))
                if (zh > dp(120)) {
                    low = true; zoneTop = mediaBottom + dp(6)
                    // the car takes the lower part of the free space, the road runs up from it to the media player
                    kTo = minOf(maxOf(kW, 1.4f * carView.width / (114f * u0)), 0.55f * zh / carView.height, 3.2f).coerceAtLeast(1f)
                    val c3b = car3d
                    if (c3b != null && c3b.live) kTo = minOf(kTo, 2600f / (2.4f * 150f * u0)).coerceAtLeast(1f)
                    dyTo = (zb - carTop - kTo * carView.height).coerceAtLeast(0f)
                }
            }
            adasLowOn = low; adasZoneTop = zoneTop
            if (::roadView.isInitialized && roadView.clipTop != zoneTop) { roadView.clipTop = zoneTop; roadView.invalidate(); if (::objView.isInitialized) objView.invalidate() }
        return floatArrayOf(kTo, dyTo)
    }

    /**
     * The ADAS view (Layouts 1 and 2, full screen only): the car and road grow and the things around them fade away.
     * Layout 1 hides the trip panel and battery bar and the car grows down into their space; Layout 2 hides the battery bar and the shortcuts
     * and the car grows up into theirs. The road view, the 3D car and the objects layer all follow the car view's scale.
     */
    private fun setAdas(on: Boolean, animate: Boolean = true) {
        if (on == adasMode) return
        if (!::carView.isInitialized || !::dashRoot.isInitialized) return
        if (on && !((l2Applied || l1cApplied) && dashShown && isFullWindow() && carView.width > 0 && carView.height > 0)) return
        adasMode = on
        val l2 = l2Applied
        // hidden in the ADAS view: Layout 2 the battery bar, the shortcut row, the Climate and Media buttons and the two swipe cards (trip / compass / shortcuts); Layout 1 the trip panel and the battery bar; both the compass reading
        val views = ArrayList<View>()
        if (l2) { views.addAll(listOf<View>(chargeBar, lowerBox, flyBtn, climateBtn, mediaBtn)); if (l2Mid.childCount > 1) views.add(l2Mid.getChildAt(1)) } else views.addAll(listOf<View>(tripPanel, tripSpacer, chargeBar))
        if (::dashHeading.isInitialized) views.add(dashHeading)
        var kTo = 1f; var dyTo = 0f
        if (on) {
            val fit = adasFit(); kTo = fit[0]; dyTo = fit[1]
            carView.pivotX = carView.width / 2f; carView.pivotY = 0f
            if (adasHidden.isEmpty()) adasHidden.addAll(views)
        }
        val k0 = carView.scaleX; val dy0 = adasDy
        adasAnim?.cancel()
        fun apply(t: Float) {
            val k = k0 + (kTo - k0) * t
            carView.scaleX = k; carView.scaleY = k
            adasDy = dy0 + (dyTo - dy0) * t
            for (v in adasHidden) { v.alpha = if (on) 1f - t else t }
            placeL1Overlays(dashRoot); syncCar3d(dashRoot)
            if (::roadView.isInitialized) roadView.invalidate()
            if (::objView.isInitialized) objView.invalidate()
        }
        for (v in adasHidden) v.isEnabled = !on
        if (::roadView.isInitialized) { roadView.adasOn = on; roadView.invalidate() }
        fun finish() {
            if (!on) { for (v in adasHidden) { v.alpha = 1f; v.isEnabled = true }; adasHidden.clear(); carView.scaleX = 1f; carView.scaleY = 1f; adasDy = 0f; adasLowOn = false; adasZoneTop = 0f; if (::roadView.isInitialized) { roadView.clipTop = 0f; roadView.invalidate() }; placeL1Overlays(dashRoot); syncCar3d(dashRoot) }
        }
        if (!animate) { apply(1f); finish(); return }
        adasAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { finish() } })
            start()
        }
    }

    /** Creates / removes the live 3D car and keeps it exactly over the car square (150 x 150 units in the 302 x 162 unit middle column). */
    /** Layout 1 with the 3D car: the speed sign sits on the speed gauge (left of its reading, wherever the gauges are), the traffic light above the car. */
    /** The bottom edge of the centred clock and date (ADAS view with the gauges hidden), or 0 when it is not showing. */
    private fun adasClockBottom(): Float =
        if (adasMode && prefs().getBoolean("adasHideGauges", false) && ::adasClockBox.isInitialized && adasClockBox.height > 0) (dp(8) + adasClockBox.height).toFloat() else 0f

    private fun placeL1Overlays(root: FrameLayout) {
        if (!(l1cApplied || l2Applied) || !::dashSlot.isInitialized || !::carView.isInitialized || !::dashSpeedGauge.isInitialized) return
        placeAdasBar(root)
        carView.translationY = (if (l1cApplied || l2Applied) dp(14).toFloat() else 0f) + adasDy        // Layouts 1 and 2: the car sits 14 dp lower (and lower still in the ADAS view)
        val g = dashSpeedGauge
        if (g.width <= 0 || carView.width <= 0 || dashSlot.width <= 0) return
        val r = IntArray(2); val ga = IntArray(2); val ca = IntArray(2)
        root.getLocationInWindow(r); g.getLocationInWindow(ga); carView.getLocationInWindow(ca)
        val diam = minOf(g.width, g.height) * g.scaleX           // Layout 2 draws the gauges 10% bigger (scale about the centre)
        val gcx = ga[0] - r[0] + g.width * g.scaleX / 2f; val gcy = ga[1] - r[1] + g.height * g.scaleY / 2f
        // the sign sits on the inner side of the speed reading (toward the middle of the screen): right of it when the speed gauge is on the left,
        // left of it when the speed gauge is on the right. Same in Layout 1 and 2, with gauges swapped or in right-hand drive.
        val inward = if (gcx < root.width / 2f) 1f else -1f
        dashSlot.translationX = gcx + inward * 0.30f * diam - dashSlot.width / 2f - inward * dp(16)       // 16 dp toward the reading
        dashSlot.translationY = gcy - 0.02f * diam - dashSlot.height / 2f + dp(8)
        // ADAS view with the gauges hidden: the speed-limit sign sits on the inner side of the compact Speed number, level with the number
        // (the number is on the side its gauge was on, so this follows the gauge swap and right-hand drive)
        if (adasMode && prefs().getBoolean("adasHideGauges", false) && ::adasSpdNum.isInitialized && adasSpdNum.width > 0 && adasSpdBox.width > 0) {
            val sa = IntArray(2); adasSpdBox.getLocationInWindow(sa)
            val na = IntArray(2); adasSpdNum.getLocationInWindow(na)
            val sl = sa[0] - r[0]; val sr = sl + adasSpdBox.width
            val signW = if (dashSign.width > 0) dashSign.width.toFloat() else dp(48).toFloat()
            val onRight = (sl + sr) / 2f > root.width / 2f
            val sx = if (onRight) sl - dp(14) - signW / 2f else sr + dp(14) + signW / 2f
            dashSlot.translationX = sx - dashSlot.width / 2f
            dashSlot.translationY = (na[1] - r[1]) + adasSpdNum.height / 2f - dashSlot.height / 2f
        }
        val u = carView.height * carView.scaleY / 162f
        val cxc = ca[0] - r[0] + carView.width * carView.scaleX / 2f
        // the light stays put when the car is nudged down; with the centred clock showing it is kept below the clock and date
        // ADAS view with the centred clock: the word Ready sits under the clock, so the light goes below the word as well
        val rdyBottom = if (adasClockBottom() > 0f && dashReady.visibility == View.VISIBLE && dashReady.isShown && dashReady.height > 0) { val q = IntArray(2); dashReady.getLocationInWindow(q); (q[1] - r[1] + dashReady.height + dp(4)).toFloat() } else 0f
        var lightY = maxOf(ca[1] - r[1] - carView.translationY + 11f * u - dashLight.height, adasClockBottom().let { if (it > 0f) it + dp(6) else 0f }, rdyBottom)
        if (adasMode && adasLowOn) lightY = adasZoneTop + dp(6)      // the car is down under the media player: the light goes with the road, at the top of it
        if (l2Applied && ::testBtn.isInitialized && testBtn.width > 0) {
            // Layout 2: the traffic light sits directly right of the Test button, its top edge level with the button's top edge, its distance under it
            val ta = IntArray(2); testBtn.getLocationInWindow(ta)
            val lx0 = ta[0] - r[0] + testBtn.width + dp(6).toFloat()
            // right-hand drive: the light moves to the other side of the car, mirrored across the screen, at the same height
            var lx = if (prefs().getBoolean("rhd", false)) root.width - lx0 - dashLight.width else lx0
            // ADAS view with Climate and Media closed: the same height, but in the middle of the screen instead of off to the side
            if (adasMode && adasNoCards) lx = (root.width - dashLight.width) / 2f
            var ly = maxOf((ta[1] - r[1]).toFloat(), adasClockBottom().let { if (it > 0f) it + dp(6) else 0f }, if (adasMode && adasNoCards) rdyBottom else 0f)
            // the ADAS View button sits just left of the road (right of it in right-hand drive): if the light would sit on it, the light goes under it
            (adasBar as? TextView)?.let { bar ->
                if (bar.visibility == View.VISIBLE) {
                    val bw = if (bar.width > 0) bar.width.toFloat() else dp(120).toFloat()
                    val barBottom = ((bar.layoutParams as FrameLayout.LayoutParams).topMargin + (if (bar.height > 0) bar.height else dp(30))).toFloat() + bar.translationY
                    val overX = lx < bar.translationX + bw + dp(4) && lx + dashLight.width > bar.translationX - dp(4)
                    if (overX && ly < barBottom + dp(4)) ly = barBottom + dp(4)
                }
            }
            dashLight.translationX = lx
            dashLight.translationY = ly
            dashLightDist.translationX = lx + dashLight.width / 2f - dashLightDist.width / 2f
            dashLightDist.translationY = ly + dashLight.height
            return
        }
        dashLight.translationX = cxc - dashLight.width / 2f
        dashLight.translationY = lightY
        dashLightDist.translationX = cxc + dashLight.width / 2f + dp(6)
        dashLightDist.translationY = lightY + dashLight.height / 2f - dashLightDist.height / 2f
    }

    /** Today's lowest (while producing) and highest solar roof power, kept across restarts; a new day starts afresh. */
    private var solarSeenAt = 0L
    private var solarUpdate: ((CanSnapshot) -> Unit)? = null
    private fun trackSolar(s: CanSnapshot) {
        val w = s.solarW ?: return
        if (s.solarAt == solarSeenAt) return
        solarSeenAt = s.solarAt
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val p = prefs()
        val fresh = p.getString("solarDay", "") != day
        var mn = if (fresh) -1 else p.getInt("solarDayMin", -1); var mx = if (fresh) -1 else p.getInt("solarDayMax", -1)
        var ch = fresh
        if (mx < 0 || w > mx) { mx = w; ch = true }
        if (w > 0 && (mn < 0 || w < mn)) { mn = w; ch = true }
        if (ch) p.edit().putString("solarDay", day).putInt("solarDayMin", mn).putInt("solarDayMax", mx).apply()
    }

    /** Sends the car's state to a 3D car: lamps, paint, wheels, doors, hatch and the solar roof. A part with no fresh signal stays closed / neutral. */
    private fun feed3d(v: Car3dView, s: CanSnapshot) {
        val now = System.currentTimeMillis()
        val db = s.doorBits
        if (db != null && now - s.doorAt < 3000) {
            fun d(i: Int) = if (((db shr i) and 1) == 1) 1f else 0f
            v.fl = d(0); v.fr = d(1); v.rl = d(2); v.rr = d(3)      // same sides as the 2D car: bit 0 driver = left, bit 1 passenger = right
        } else { v.fl = 0f; v.fr = 0f; v.rl = 0f; v.rr = 0f }
        v.tg = if (s.hatchOpen != null && now - s.hatchAt < 3000) (if ((s.hatchPos ?: 0) > 0) s.hatchPos!!.coerceAtMost(100) / 100f else if (s.hatchOpen == true) 0.04f else 0f) else 0f
        v.sr = -1f; v.hd = -1f          // no sunroof position or door-lock signal is decoded yet
        v.solar = if (s.solarW != null && now - s.solarAt < 5000) (s.solarW.coerceAtLeast(0) / prefs().getInt("solarMaxW", 250).toFloat()).coerceIn(0f, 1f) else -1f
        v.wheel = prefs().getString("carWheel", "F3A_22") ?: "F3A_22"
        v.tail = carView.headlights || carView.beamsOn || carView.highBeam
        v.push(carView.speedKmh, if (carView.highBeam) 2 else if (carView.beamsOn) 1 else if (carView.headlights) 3 else 0, carView.braking, carView.leftInd, carView.rightInd, carView.reverse, CAR_PAINTS[carView.paintIdx].first)
    }

    private fun syncCar3d(root: FrameLayout) {
        if (!::carView.isInitialized || !::roadView.isInitialized) return
        val show = wantLive3d() && (roadView.visibility == View.VISIBLE || l1cApplied) && carView.isShown && carView.width > 0
        var v = car3d
        if (!show) {
            if (v != null) { root.removeView(v); v.destroy(); car3d = null }
            carView.spriteCar = true
            return
        }
        if (v == null) {
            v = Car3dView(this) { ok -> runOnUiThread { carView.spriteCar = !ok; car3d?.visibility = if (ok) View.VISIBLE else View.GONE; if (!ok) { car3d?.let { root.removeView(it); it.destroy() }; car3d = null; failed3d = true } } }
            v.visibility = View.INVISIBLE
            car3d = v
            root.addView(v, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
            if (::objView.isInitialized) objView.bringToFront()
        }
        if (failed3d) return
        val u = carView.height * carView.scaleY / 162f
        val a = IntArray(2); val b = IntArray(2)
        root.getLocationInWindow(a); carView.getLocationInWindow(b)
        val side = (150f * u).toInt().coerceAtLeast(1)
        val x = b[0] - a[0] + ((carView.width * carView.scaleX - 302f * u) / 2f + 76f * u).toInt()
        val extra = (120f * u).toInt()         // room above the nose for the headlight beams
        val y = b[1] - a[1] + (3f * u).toInt() - extra
        val lp = v.layoutParams as FrameLayout.LayoutParams
        // the view is wider than the car square (room each side for the headlight beams, which may reach over the road edges and the gauges); the car stays in the middle of it
        // (in the ADAS view the beam room may run past the screen edges instead of being squeezed, which would stretch the car)
        val pad = if (adasMode) (side * 0.7f).toInt() else (side * 0.7f).toInt().coerceAtMost(x).coerceAtMost(root.width - x - side).coerceAtLeast(0)
        if (lp.width != side + 2 * pad || lp.height != side + extra || lp.leftMargin != x - pad || lp.topMargin != y) {
            lp.width = side + 2 * pad; lp.height = side + extra; lp.leftMargin = x - pad; lp.topMargin = y; v.layoutParams = lp
        }
        if (v.live) { v.visibility = if (viewerLayer != null) View.INVISIBLE else View.VISIBLE; carView.spriteCar = false }
        // Layout 1: the traffic light (and its distance) sit on top of the 3D car's headlight glow; it is moved once, not on every layout pass
        if (l1cApplied && ::dashLight.isInitialized) for (t in listOf<View>(dashLight, dashLightDist)) if (t.parent === root && root.indexOfChild(t) < root.indexOfChild(v)) t.bringToFront()
    }
    private var failed3d = false

    // ---- parked: press and hold the 3D car for about a second and it opens full screen, to turn with a finger and zoom with two
    private var viewerLayer: FrameLayout? = null
    private var viewer3d: Car3dView? = null
    private var lpDownX = 0f; private var lpDownY = 0f; private var lpActive = false
    private var lpRing: View? = null
    private var lpDownAt = 0L; private var carClickBlock = false      // a long press on the car must not also count as the tap that opens the colour list
    private var lpAnim: ValueAnimator? = null
    private val lpRun = Runnable { lpActive = false; carClickBlock = true; clearLpRing(); openCarViewer() }
    private fun clearLpRing() { lpAnim?.cancel(); lpAnim = null; lpRing?.let { (it.parent as? ViewGroup)?.removeView(it) }; lpRing = null }
    private fun cancelCarLongPress() { if (lpActive) { lpActive = false; dashRoot.removeCallbacks(lpRun) }; clearLpRing() }
    private fun carTouchRect(): RectF? {
        val c = car3d ?: return null
        if (!c.live || c.width <= 0 || !c.isShown) return null
        val loc = IntArray(2); c.getLocationOnScreen(loc)
        val side = c.height - 120f * (carView.height * carView.scaleY / 162f)       // the square the car sits in (the view has room above the nose for the beams)
        val cx = loc[0] + c.width / 2f
        return RectF(cx - side * 0.28f, loc[1] + c.height - side, cx + side * 0.28f, loc[1] + c.height.toFloat())
    }
    private fun watchCarLongPress(ev: android.view.MotionEvent) {
        if (!::dashRoot.isInitialized || !dashShown || viewerLayer != null) return
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                cancelCarLongPress(); carClickBlock = false; lpDownAt = 0L
                if (carView.speedKmh >= 1f) return
                val r = carTouchRect() ?: return
                if (!r.contains(ev.rawX, ev.rawY)) return
                lpDownX = ev.rawX; lpDownY = ev.rawY; lpActive = true; lpDownAt = android.os.SystemClock.uptimeMillis()
                dashRoot.postDelayed(lpRun, 1000)
                // a ring fills around the car while the finger stays down (it begins after a short pause, so a plain tap shows nothing)
                val ring = HoldRing(this)
                val sz = dp(150); val loc = IntArray(2); dashRoot.getLocationOnScreen(loc)
                val lp = FrameLayout.LayoutParams(sz, sz); lp.leftMargin = (r.centerX() - sz / 2f - loc[0]).toInt(); lp.topMargin = (r.centerY() - sz / 2f - loc[1]).toInt()
                dashRoot.addView(ring, lp); ring.isClickable = false; lpRing = ring
                lpAnim = ValueAnimator.ofFloat(0f, 1f).apply { startDelay = 250; duration = 750; addUpdateListener { ring.p = it.animatedValue as Float; ring.invalidate() }; start() }
            }
            android.view.MotionEvent.ACTION_MOVE -> if (lpActive && Math.hypot((ev.rawX - lpDownX).toDouble(), (ev.rawY - lpDownY).toDouble()) > dp(14).toDouble()) cancelCarLongPress()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL, android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                // held for more than a tap (the ring was filling, or the viewer opened): the release is not a tap on the car
                if (lpDownAt > 0L && android.os.SystemClock.uptimeMillis() - lpDownAt > 350L) carClickBlock = true
                cancelCarLongPress()
            }
        }
    }
    private fun openCarViewer() {
        if (viewerLayer != null || car3d?.live != true) return
        val layer = FrameLayout(this)
        layer.isClickable = true
        layer.setBackgroundColor((Skins.look.bgTop and 0xFFFFFF) or (0xEE shl 24))
        val v = Car3dView(this, true) { ok -> runOnUiThread { if (!ok) closeCarViewer() else viewer3d?.visibility = View.VISIBLE } }
        v.visibility = View.INVISIBLE
        viewer3d = v
        layer.addView(v, FrameLayout.LayoutParams(-1, -1))
        fun circle(glyph: String, label: String, top: Int, onTap: (TextView) -> Unit) {
            val col = LinearLayout(this); col.orientation = LinearLayout.HORIZONTAL; col.gravity = Gravity.CENTER_VERTICAL
            val lb = TextView(this).apply { text = label; setTextColor(Color.parseColor("#9FB3C4")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, 0, dp(10), 0) }
            val b = TextView(this).apply {
                text = glyph; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#223242")); setStroke(dp(1), Color.parseColor("#4B6277")) }
                setOnClickListener { onTap(this) }
            }
            col.addView(lb); col.addView(b, LinearLayout.LayoutParams(dp(52), dp(52)))
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END); lp.topMargin = top; lp.rightMargin = dp(28)
            layer.addView(col, lp)
        }
        circle("\u2715", "Close", dp(28)) { closeCarViewer() }
        circle("\u21BA", "Reset view", dp(98)) { viewer3d?.viewReset() }
        // Colour and Wheels: the only places the car's paint and wheel design are chosen
        fun picker(title: String, build: (LinearLayout, () -> Unit) -> Unit) {
            val scrim = FrameLayout(this); scrim.isClickable = true; scrim.setBackgroundColor(Color.argb(190, 0, 0, 0))
            val close = { layer.removeView(scrim) }
            scrim.setOnClickListener { close() }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)); isClickable = true
                background = GradientDrawable().apply { setColor(Color.parseColor("#18203A")); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(90, 255, 255, 255)) }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(TextView(this).apply { text = "Done"; setTextColor(Color.parseColor("#7FB4E5")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(dp(14), dp(8), dp(4), dp(8)); setOnClickListener { close() } })
            panel.addView(head)
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            build(grid, close)
            val sv = ScrollView(this); sv.addView(grid)
            panel.addView(sv, LinearLayout.LayoutParams(-1, -2))
            val w = minOf(resources.displayMetrics.widthPixels - dp(40), dp(620))
            scrim.addView(panel, FrameLayout.LayoutParams(w, -2, Gravity.CENTER))
            layer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        }
        /** A grid of tiles, `cols` to a row; a tile is a picture (or colour), a name, and a ring when it is the current one. */
        fun tiles(grid: LinearLayout, cols: Int, n: Int, size: Int, current: () -> Int, name: (Int) -> String, art: (Int, ImageView) -> Unit, pick: (Int) -> Unit) {
            val cells = ArrayList<Pair<View, GradientDrawable>>()
            fun ring() { for ((i, c) in cells.withIndex()) c.second.setStroke(dp(if (i == current()) 3 else 1), Color.parseColor(if (i == current()) "#7FB4E5" else "#33445A")) }
            var row: LinearLayout? = null
            for (i in 0 until n) {
                if (i % cols == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(8), dp(4), dp(8)) }
                val bg = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#1F2A44")) }
                cell.background = bg
                val img = ImageView(this); art(i, img)
                cell.addView(img, LinearLayout.LayoutParams(size, size))
                cell.addView(TextView(this).apply { text = name(i); setTextColor(Color.argb(225, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); gravity = Gravity.CENTER; maxLines = 2 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                cell.setOnClickListener { pick(i); ring() }
                cells.add(Pair(cell, bg))
                row!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            }
            // a last short row is padded so its tiles keep the same width
            if (n % cols != 0) for (k in n % cols until cols) row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            ring()
        }
        circle("\u25D0", "Colour", dp(238)) {
            picker("Car colour") { grid, _ ->
                tiles(grid, 5, CAR_PAINTS.size, dp(46), { prefs().getInt("carPaint", 3).coerceIn(0, CAR_PAINTS.size - 1) }, { CAR_PAINTS[it].first },
                    { i, img -> img.setImageDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CAR_PAINT_SWATCH[i]); setStroke(dp(1), Color.argb(90, 255, 255, 255)) }) },
                    { i -> setCarPaint(i); viewer3d?.setLook(CAR_PAINTS[i].first, null) })
            }
        }
        circle("\u25CE", "Wheels", dp(308)) {
            picker("Wheels") { grid, _ ->
                tiles(grid, 3, CAR_WHEELS.size, dp(92), { CAR_WHEELS.indexOfFirst { it.first == prefs().getString("carWheel", "F3A_22") }.coerceAtLeast(0) }, { CAR_WHEELS[it].second },
                    { i, img ->
                        try { assets.open("wheels/${CAR_WHEELS[i].first}.png").use { img.setImageBitmap(android.graphics.BitmapFactory.decodeStream(it)) } } catch (e: Throwable) { }
                        img.scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    { i -> prefs().edit().putString("carWheel", CAR_WHEELS[i].first).apply(); viewer3d?.setLook(null, CAR_WHEELS[i].first) })
            }
        }
        var spin = false
        circle("\u27F3", "Auto spin", dp(168)) { b -> spin = !spin; viewer3d?.viewAuto(spin); (b.background as GradientDrawable).setColor(Color.parseColor(if (spin) "#2E6FA6" else "#223242")) }
        val hint = TextView(this).apply { text = "Drag to rotate  \u00B7  Pinch to zoom"; setTextColor(Color.parseColor("#9FB3C4")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER }
        val hlp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); hlp.bottomMargin = dp(24)
        layer.addView(hint, hlp)
        // SolarSky figures in a row under the car's name: now, today's lowest (while producing) and highest, and the roof's lifetime total
        run {
            val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.gravity = Gravity.CENTER_HORIZONTAL
            box.addView(TextView(this).apply { text = "SOLAR SKY"; setTextColor(Color.parseColor("#6AA8FF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; letterSpacing = 0.1f })
            val rowL = LinearLayout(this); rowL.orientation = LinearLayout.HORIZONTAL; rowL.gravity = Gravity.CENTER
            box.addView(rowL, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            fun cell(label: String): TextView {
                val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER_HORIZONTAL; c.setPadding(dp(14), 0, dp(14), 0)
                c.addView(TextView(this).apply { text = label; setTextColor(Color.parseColor("#9FB3C4")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); gravity = Gravity.CENTER })
                val v = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
                c.addView(v); rowL.addView(c); return v
            }
            val vNow = cell("Now"); val vMin = cell("Lowest today"); val vMax = cell("Highest today"); val vLife = cell("Lifetime"); val vRange = cell("Generated range")
            solarUpdate = { s ->
                val fresh = s.solarW != null && System.currentTimeMillis() - s.solarAt < 5000
                val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                val pr = prefs(); val today = pr.getString("solarDay", "") == day
                val mn = if (today) pr.getInt("solarDayMin", -1) else -1; val mx = if (today) pr.getInt("solarDayMax", -1) else -1
                vNow.text = if (fresh) "${s.solarW} W" else "--"
                vMin.text = if (mn >= 0) "$mn W" else "--"
                vMax.text = if (mx >= 0) "$mx W" else "--"
                vLife.text = s.solarLifeKwh?.let { "${(it + 500) / 1000} kWh" } ?: "--"      // the car counts this in Wh
                // the range that energy would give at the EPA rating (360 mi on 106.5 kWh usable), in the unit chosen in Settings
                vRange.text = s.solarLifeKwh?.let { wh ->
                    val mi = wh / 1000.0 * 360.0 / 106.5
                    if (speedMph()) String.format(java.util.Locale.US, "%,.0f mi", mi) else String.format(java.util.Locale.US, "%,.0f km", mi * 1.609344)
                } ?: "--"
            }
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL); lp.topMargin = dp(92)
            layer.addView(box, lp)
        }
        // the car's name, top centre: touch it to type a new one ("Name your car" until one is saved)
        run {
            val nameBtn = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER; setPadding(dp(22), dp(10), dp(22), dp(10)); maxLines = 1
                background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(Color.parseColor("#223242")); setStroke(dp(1), Color.parseColor("#4B6277")) }
            }
            fun show() { val n = prefs().getString("carName", "").orEmpty().trim(); nameBtn.text = if (n.isEmpty()) "Name your car" else n; nameBtn.setTextColor(Color.parseColor(if (n.isEmpty()) "#9FB3C4" else "#FFFFFF")); nameBtn.typeface = if (n.isEmpty()) Typeface.DEFAULT else Typeface.DEFAULT_BOLD }
            show()
            nameBtn.setOnClickListener {
                val edit = EditText(this).apply {
                    setText(prefs().getString("carName", "").orEmpty()); setSelection(text.length); setHint("Your car's name")
                    setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(24))
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#7C8DA0")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                }
                val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(edit, FrameLayout.LayoutParams(-1, -2)) }
                darkDialog().setTitle("Name your car").setView(wrap)
                    .setPositiveButton("Save") { _, _ -> prefs().edit().putString("carName", edit.text.toString().trim()).apply(); show() }
                    .setNegativeButton("Cancel", null).showDark()
            }
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL); lp.topMargin = dp(28)
            layer.addView(nameBtn, lp)
        }
        layer.translationZ = dp(200).toFloat(); layer.outlineProvider = null      // above everything else on the screen, whatever is brought to the front later
        edgeBox.addView(layer, FrameLayout.LayoutParams(-1, -1))
        viewerLayer = layer
        car3d?.visibility = View.INVISIBLE
        // the climate screen and the media card step out of the way while the viewer is up; closing it puts back whatever was showing
        viewerHidden = listOfNotNull<View>(if (::climateView.isInitialized) climateView else null, if (::mediaCard.isInitialized) mediaCard else null, if (::mediaPopup.isInitialized) mediaPopup else null)
            .filter { it.visibility == View.VISIBLE }.onEach { it.visibility = View.INVISIBLE }
    }
    private var viewerHidden: List<View> = emptyList()
    private fun closeCarViewer() {
        val l = viewerLayer ?: return
        viewerLayer = null
        edgeBox.removeView(l); viewer3d?.destroy(); viewer3d = null; solarUpdate = null
        car3d?.let { if (it.live) it.visibility = View.VISIBLE }
        for (v in viewerHidden) if (v.visibility == View.INVISIBLE) v.visibility = View.VISIBLE
        viewerHidden = emptyList()
    }

    private fun buildDashboard(): FrameLayout {
        Skins.current = prefs().getInt("skin", 7)
        val f = FrameLayout(this)
        f.visibility = View.GONE
        f.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Skins.look.bgTop, Skins.look.bgBottom))
        roadView = RoadView(this).apply { visibility = View.GONE; onCurve = { b -> car3d?.setSteer(b) } }        // Layout 2: the road, behind everything
        f.addView(roadView, FrameLayout.LayoutParams(-1, -1))
        val blue = Color.parseColor("#1E90FF")
        val violet = Color.parseColor("#8E5CFF")
        val pink = Color.parseColor("#FF4FA3")
        val red = Color.parseColor("#FF3B4E")
        fun mix(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
        }
        // one shared gradient for both gauges, spread over the part that is filled: cyan, blue, violet, pink, red
        val stops = intArrayOf(Color.parseColor("#22D3EE"), blue, violet, pink, red)
        fun grad(t: Float): Int {
            val k = t.coerceIn(0f, 1f) * (stops.size - 1)
            val i = minOf(k.toInt(), stops.size - 2)
            return mix(stops[i], stops[i + 1], k - i)
        }
        dashPowerGauge = ArcGaugeView(this, false, 0.2f) { t -> Skins.grad(t) }
            .apply { topLabel = "CLIMATE"; title = "POWER"; unit = "kW"; bottomLabel = "HV BATTERY" }
        dashSpeedGauge = ArcGaugeView(this, true, 0f) { t -> Skins.grad(t) }
            .apply { topLabel = "BATTERY TEMP"; title = "SPEED"; unit = "km/h"; bottomLabel = "BATTERY HEATING/COOLING" }
        // tap the power or speed reading for the Pulse hardware health window
        // only the big reading and its unit (kW / km/h) answer to a tap, so a brush on the rest of the gauge does nothing
        for (gv in listOf(dashPowerGauge, dashSpeedGauge)) {
            var downIn = false
            gv.setOnTouchListener { v, ev ->
                val r = minOf(v.width, v.height) / 2f * 0.92f
                val inside = Math.abs(ev.x - v.width / 2f) <= r * 0.5f && ev.y >= v.height / 2f - r * 0.18f && ev.y <= v.height / 2f + r * 0.42f
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downIn = inside; downIn }
                    android.view.MotionEvent.ACTION_UP -> { val hit = downIn && inside; downIn = false; if (hit) showHealth(); hit }
                    android.view.MotionEvent.ACTION_CANCEL -> { val was = downIn; downIn = false; was }
                    else -> downIn
                }
            }
        }
        val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        dashSign = SpeedSignView(this).apply { alpha = 0f }
        dashLight = TrafficLightView(this).apply { alpha = 0f }
        dashLightDist = TextView(this).apply {
            textSize = 15f; setTextColor(Color.argb(200, 255, 255, 255)); typeface = light; gravity = Gravity.CENTER; alpha = 0f
        }
        dashRow = StatusStripView(this).apply {
            plain = true
            maxCount = 4
            setShown(savedList("rowShown", "rowOrder"), listOf("Range", "Regen", "Cells", "Thermal"))
            onChange = { prefs().edit().putString("rowShown", it.joinToString("|")).apply(); refreshPool() }
            onDoubleTap = { t -> if (t == "Regen") { pulse?.resetDrive(); Toast.makeText(context, "Regen counter reset", Toast.LENGTH_SHORT).show() } }
        }
        dashHeading = TextView(this).apply {
            textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-light", Typeface.ITALIC); gravity = Gravity.CENTER
        }
        dashBrake = TextView(this).apply {
            text = "Brake light on"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.INVISIBLE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(18), dp(4), dp(18), dp(4))
            background = GradientDrawable().apply { setColor(Color.parseColor("#E53935")); cornerRadius = dp(16).toFloat() }
        }
        dashMode = TextView(this).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.INVISIBLE; text = "EARTH"
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); letterSpacing = 0.10f
            minWidth = dp(84)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        dashReady = TextView(this).apply {
            text = "READY"; textSize = 17f; letterSpacing = 0.18f; gravity = Gravity.CENTER; visibility = View.INVISIBLE
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); setTextColor(Color.parseColor("#7CFF3A"))
        }
        chargeBar = ChargeBarView(this)
        tripPanel = TripPanelView(this).apply {
            cols = 2
            title = "Trip Information"
            visibility = View.GONE
            onReset = { pulse?.resetDrive(); Toast.makeText(context, "Trip reset", Toast.LENGTH_SHORT).show() }
        }
        // the speed sign and the traffic light share one slot at the top of the middle column: one fades out as the other fades in
        dashSlot = FrameLayout(this).apply {
            addView(dashLight, FrameLayout.LayoutParams(dp(42), dp(88), Gravity.CENTER))
            addView(dashSign, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
            // where the car got the limit (camera / map), in the spare strip under the sign; only in the roomy layout
            dashSignSrc = TextView(this@MainActivity).apply { textSize = 9f; gravity = Gravity.CENTER; alpha = 0f; visibility = View.GONE; setSingleLine(true); includeFontPadding = true; setPadding(0, 0, 0, dp(2)) }
            addView(dashSignSrc, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 0 })   // keeps the tail of the p inside the slot
        }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(dashReady, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(dashSlot, LinearLayout.LayoutParams(-1, dp(92)).apply { topMargin = dp(4) })
            addView(dashLightDist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(dashBrake, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            tripSpacer = View(context)
            addView(tripSpacer, LinearLayout.LayoutParams(-1, 0, 0f))
            addView(tripPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(3) })
            addView(chargeBar, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(2) })
        }
        dashMid = mid
        carView = CarView(this).apply {
            paintIdx = prefs().getInt("carPaint", 3)
            isClickable = true      // the colour is no longer picked by tapping the car: touch the car for a second to open the 3D view, where Colour and Wheels live
        }
        pageSets = listOf(PageSet(), PageSet())
        l2FlyBtn = TextView(this).apply {
            text = "\u2699"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 24, 44)); shape = GradientDrawable.OVAL; setStroke(dp(1), Color.argb(150, 255, 255, 255)) }
            setOnClickListener {
                l2FlyOpen = !l2FlyOpen
                ui.removeCallbacks(closeL2Fly)
                btnBar.visibility = if (l2FlyOpen) View.VISIBLE else View.GONE
                if (l2FlyOpen) { btnBar.bringToFront(); ui.postDelayed(closeL2Fly, 7000) }
            }
        }
        // Layout 2: bring the climate screen and the media card back after they were turned off (listed in the gear flyout)
        fun flyChip(label: String, tap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0); background = chipBg(12)
            setOnClickListener {
                l2FlyOpen = false; ui.removeCallbacks(closeL2Fly); btnBar.visibility = View.GONE
                tap()
            }
        }
        l2ClimBtn = flyChip("Climate") {
            if (prefs().getBoolean("l2Climate", true) && climateView.visibility == View.VISIBLE) { climateView.visibility = View.GONE; l2ClimClosed = true }
            else {
                l2ClimClosed = false
                prefs().edit().putBoolean("l2Climate", true).apply()
                relayoutDash()
                if (climateView.visibility != View.VISIBLE) openClimate()
            }
        }
        l2MediaBtn = flyChip("Media") {
            l2MediaTap()
        }
        l2LoadBtn = flyChip("Load Preset") { showLoadPreset() }
        l2SaveBtn = flyChip("Save Preset") { showSavePreset() }
        NavState.onChange = { for (ps in pageSets) ps.nav.refresh() }
        Thread { rootRun("cmd notification allow_listener " + NavListener.component(this)) }.start()
        leftPager = PagerCardView(this)
        rightPager = PagerCardView(this)
        refreshShortcuts(true)
        val l2Cards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(leftPager, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(5) })
            addView(rightPager, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(5) })
        }
        l2Mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(carView, LinearLayout.LayoutParams(-1, 0, 0.38f))
            addView(l2Cards, LinearLayout.LayoutParams(-1, 0, 0.62f).apply { topMargin = dp(2); bottomMargin = 0 })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        dashGaugeRow = row
        applyGaugeSides()
        f.addView(row, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(8), dp(8), dp(114)) })
        dashStrip = StatusStripView(this).apply {
            maxCount = 7
            showPlus = true
            setShown(savedList("stripShown", "stripOrder"), listOf("HV Cap", "Cabin", "Outside", "Solar now", "12V Battery", "12V SOH", "12V SOC"))
            onChange = { prefs().edit().putString("stripShown", it.joinToString("|")).apply(); refreshPool() }
            onPlus = { togglePool() }
        }
        poolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(8), dp(4)) }
        poolBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.argb(245, 10, 14, 28)); cornerRadius = dp(22).toFloat(); setStroke(dp(2), Color.argb(230, 42, 168, 255)) }
            addView(poolRow)
        }
        editBackdrop = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.argb(225, 6, 9, 20)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(150, 42, 168, 255)) }
        }
        f.addView(editBackdrop, FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM).apply { setMargins(dp(160), 0, dp(120), dp(4)) })
        f.addView(dashRow, FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM).apply { setMargins(dp(170), 0, dp(130), dp(76)) })
        f.addView(dashStrip, FrameLayout.LayoutParams(-1, dp(62), Gravity.BOTTOM).apply { setMargins(dp(170), 0, dp(130), dp(8)) })
        f.addView(poolBar, FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM).apply { setMargins(dp(170), 0, dp(130), dp(184)) })
        resBtn = TextView(this).apply {
            text = "Screen"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setOnClickListener { toggleScreenSize() }
        }
        lowerTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            for (i in 0 until maxSlots) {
                addView(TextView(context).apply {
                    textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    minHeight = dp(38); minWidth = dp(44); setPadding(dp(6), 0, dp(6), 0)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener { if (slotPkg(i).isEmpty()) pickLowerApp(i) else openLower(i) }
                    setOnLongClickListener { pickLowerApp(i); true }
                })
            }
        }
        lowerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = false
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            addView(lowerTabs, FrameLayout.LayoutParams(-2, -2))
            // the buttons scroll inside the border: clipped to the padding box and to the rounded outline
            setPadding(dp(8), dp(5), dp(8), dp(5)); clipToPadding = true      // room so square icons never touch the outline
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat()) }
            }
            clipToOutline = true
        }
        lowerTabs.setPadding(0, 0, 0, 0)
        lowerAdd = TextView(this).apply {
            text = "+"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minHeight = dp(42); minWidth = dp(42); setPadding(dp(6), 0, dp(6), 0)
            background = chipBg(18)
            visibility = View.GONE
            setOnClickListener {
                val n = slotCount
                if (n < maxSlots) {
                    prefs().edit().putInt("slotN", n + 1).apply()
                    refreshLowerLabels(); paintLowerTabs()
                    lowerScroll.post { lowerScroll.fullScroll(View.FOCUS_RIGHT) }
                    pickLowerApp(n)
                }
            }
        }
        lowerIdx = prefs().getInt("lowerApp", 0).coerceIn(0, slotCount - 1)
        refreshLowerLabels()
        paintLowerTabs()
        tripBtn = TextView(this).apply {
            text = "Trip"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            background = chipBg(12)
            visibility = View.GONE
            setOnClickListener { showTripDialog() }
        }
        climateBtn = TextView(this).apply {
            text = "Climate"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            background = chipBg(12)
            setOnClickListener {
                if (l2MediaInPlace) { l2MediaInPlace = false; l2ClimHidden = false; relayoutDash(); return@setOnClickListener }
                if (climateView.visibility == View.VISIBLE) { climateView.visibility = View.GONE; if (l2Applied && l2ClimInline) l2ClimHidden = true; if (l2Applied) l2ClimClosed = true else prefs().edit().putBoolean("l1Climate", false).apply() }
                else { l2ClimHidden = false; if (l2Applied) l2ClimClosed = false else prefs().edit().putBoolean("l1Climate", true).apply(); openClimate() }
            }
        }
        mediaBtn = TextView(this).apply {
            text = "Media"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            background = chipBg(12)
            setOnClickListener {
                if (l2Applied) { l2MediaTap(); return@setOnClickListener }
                if (fullOrderNow) {
                    // full screen: the media card and the Climate panel toggle independently and never overlap
                    prefs().edit().putBoolean("fullMedia", !prefs().getBoolean("fullMedia", true)).apply()
                    relayoutDash()
                    return@setOnClickListener
                }
                if (mediaPopup.visibility == View.VISIBLE) { ui.removeCallbacks(hideMediaPopup); mediaPopup.visibility = View.GONE }
                else {
                    ensureMediaWatcher(); mediaWatcher?.start()
                    placeMediaPopup()
                    mediaPopup.bringToFront(); mediaPopup.visibility = View.VISIBLE
                    // the button bars stay on top of the card
                    lowerBox.bringToFront(); btnBar.bringToFront(); if (::cautionView.isInitialized && cautionView.visibility == View.VISIBLE) cautionView.bringToFront()
                    ui.removeCallbacks(hideMediaPopup)
                }
            }
        }
        lowerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(mediaBtn, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
            addView(climateBtn, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
            addView(resBtn, LinearLayout.LayoutParams(-2, dp(42)))
            addView(tripBtn, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
            addView(lowerScroll, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        f.addView(lowerBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(190)) })
        flyBtn = TextView(this).apply {
            text = "\u2630"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.argb(190, 20, 20, 40)); shape = GradientDrawable.OVAL; setStroke(dp(1), Color.argb(150, 255, 255, 255)) }
            setOnClickListener {
                flyOpen = !flyOpen
                applyLowerVisibility()
            }
        }
        f.addView(flyBtn, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(190)) })
        mediaPopup = MediaCardView(this).apply {
            visibility = View.GONE
            onPrev = { if (InApp.mode != InApp.EXTERNAL) InApp.prev() else mediaWatcher?.prev(); ui.removeCallbacks(hideMediaPopup) }
            onToggle = { if (InApp.mode != InApp.EXTERNAL) InApp.toggle() else mediaWatcher?.toggle(); ui.removeCallbacks(hideMediaPopup) }
            onNext = { if (InApp.mode != InApp.EXTERNAL) InApp.next() else mediaWatcher?.next(); ui.removeCallbacks(hideMediaPopup) }
            onSources = { ui.removeCallbacks(hideMediaPopup); showSources() }
            onFavs = { ui.removeCallbacks(hideMediaPopup); showFavorites() }
            onTap = { ui.removeCallbacks(hideMediaPopup); if (InApp.mode == InApp.EXTERNAL) openMusicApp(false) }   // the card stays on screen
        }
        f.addView(mediaPopup, FrameLayout.LayoutParams(-1, dp(132), Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(66)) })
        mediaCard = MediaCardView(this).apply {
            visibility = View.GONE
            onPrev = { if (InApp.mode != InApp.EXTERNAL) InApp.prev() else mediaWatcher?.prev() }
            onToggle = { if (InApp.mode != InApp.EXTERNAL) InApp.toggle() else mediaWatcher?.toggle() }
            onNext = { if (InApp.mode != InApp.EXTERNAL) InApp.next() else mediaWatcher?.next() }
            onSources = { showSources() }
            onFavs = { showFavorites() }
            onTap = { if (InApp.mode == InApp.EXTERNAL) openMusicApp(false) }
        }
        f.addView(mediaCard, FrameLayout.LayoutParams(-1, dp(132), Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(200)) })
        f.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            adaptDashLayout((v.width / resources.displayMetrics.density).toInt(), ((bottom - top) / resources.displayMetrics.density).toInt())
            if (::mediaPopup.isInitialized && mediaPopup.visibility == View.VISIBLE) v.post { placeMediaPopup() }   // the screen size changed while the card is up
        }
        // top right: the compass heading beside the drive-mode badge
        dashTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(dashHeading, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            addView(dashMode, LinearLayout.LayoutParams(-2, -2))
        }
        f.addView(dashTop, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(8), 0) })
        dashClock = DashClockView(this).apply { visibility = View.GONE }
        f.addView(dashClock, FrameLayout.LayoutParams(dp(150), dp(80), Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(8), 0, 0) })
        // compact Speed and Power readouts for the top corners (shown instead of the gauges when the ADAS-view option hides them)
        fun readout(numSp: Float = 60f, ttlSp: Float = 14f): Triple<LinearLayout, TextView, TextView> {
            val num = TextView(this).apply { textSize = numSp; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false }
            val ttl = TextView(this).apply { textSize = ttlSp; setTextColor(Color.argb(190, 255, 255, 255)); gravity = Gravity.CENTER; includeFontPadding = false; letterSpacing = 0.08f }
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; alpha = 0f; visibility = View.INVISIBLE }
            b.addView(num, LinearLayout.LayoutParams(-2, -2)); b.addView(ttl, LinearLayout.LayoutParams(-2, -2))
            return Triple(b, num, ttl)
        }
        readout().let { adasSpdBox = it.first; adasSpdNum = it.second; adasSpdTitle = it.third }
        readout().let { adasPwrBox = it.first; adasPwrNum = it.second; adasPwrTitle = it.third }
        f.addView(adasSpdBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(70), dp(16), 0) })
        f.addView(adasPwrBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(70), 0, 0) })
        // the clock in the middle at the top, with the outside temperature beside it (right in left-hand drive, left in right-hand drive)
        readout(28f, 12f).let { adasClockBox = it.first; adasClockNum = it.second; adasClockTitle = it.third }
        readout(28f, 12f).let { adasTempBox = it.first; adasTempNum = it.second; adasTempTitle = it.third }
        f.addView(adasClockBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, dp(8), 0, 0) })
        f.addView(adasTempBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, dp(8), 0, 0) })
        // a test button in the empty space beside the clock: shows the speed sign and the traffic light (cycling red / amber / green) with nothing else moving
        testBtn = TextView(this).apply {
            text = "Test sign"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            background = chipBg(12)
            setOnClickListener {
                signTest = !signTest
                text = if (signTest) "Test: on" else "Test sign"
                renderDash()
            }
        }
        // hidden in releases: it stays in the layout (invisible, not tappable) because the Layout 2 traffic light is placed next to it.
        // Set SHOW_TEST_BUTTON to true to bring it back.
        testBtn.visibility = if (SHOW_TEST_BUTTON) View.VISIBLE else View.INVISIBLE
        f.addView(testBtn, FrameLayout.LayoutParams(-2, dp(30), Gravity.TOP or Gravity.START).apply { setMargins(dp(176), dp(14), 0, 0) })
        updateBtn = TextView(this).apply {
            text = "Update Available"; textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0); visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8761F")); cornerRadius = dp(15).toFloat(); setStroke(dp(1), Color.argb(170, 255, 255, 255)) }
            setOnClickListener { pendingUpdate?.let { showUpdateDialog(it) } }
        }
        f.addView(updateBtn, FrameLayout.LayoutParams(-2, dp(30), Gravity.TOP or Gravity.START).apply { setMargins(dp(160), dp(10), 0, 0) })
        // Layout 2: the live 3D car, a transparent layer over the car square; off (images used instead) when the setting is off or the page fails
        f.viewTreeObserver.addOnGlobalLayoutListener {
            placeL1Overlays(f); syncCar3d(f)
        }
        objView = ObjectsView(this, roadView)         // the other objects around the car: on top of everything
        f.addView(objView, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private val dashTick = object : Runnable {
        override fun run() {
            renderDash()
            // a preset saved with the ADAS view on: switch it on as soon as the screen is ready for it, trying again every tick for ten seconds
            if (prefs().getBoolean("adasRestore", false)) {
                if (adasMode) { prefs().edit().remove("adasRestore").apply(); EventLog.add("preset: ADAS view on") }
                else if (::carView.isInitialized && carView.width > 0 && carView.height > 0) {
                    setAdas(true, false)
                    if (!adasMode && ++adasRestoreTries > 50) { prefs().edit().remove("adasRestore").apply(); EventLog.add("preset: the ADAS view could not be switched on here") }
                }
            }
            applyAdasGauges()
            if (adasMode) refitAdas()
            placeUpdateBtn()
            if (dashShown) ui.postDelayed(this, 200)
        }
    }

    /** Day or night for the dashboard, from the setting (and the headlights when set to follow them). */
    private fun wantDay(): Boolean = when (prefs().getString("dispTheme", "night")) {
        "day" -> true
        "auto" -> canSnap.beamsOn != true       // low or high beam on = night; everything else (daytime running lamps, lights off, unknown) = day
        else -> false
    }

    private fun updateDashTheme(force: Boolean = false) {
        val want = wantDay()
        val now = System.currentTimeMillis()
        if (want != dayPending) { dayPending = want; dayPendingSince = now }
        // headlights must hold their state for 1.5 s before the theme flips; a setting change is immediate
        if (want != dashDay && (force || now - dayPendingSince > 1500 || prefs().getString("dispTheme", "night") != "auto")) {
            dashDay = want
            applyDashTheme(want)
            applySkinChrome()
            if (::climateView.isInitialized && climateView.visibility == View.VISIBLE) applyClimateColors()
        }
    }

    private fun applyDashTheme(day: Boolean) {
        paintEdges()
        dashRoot.background = if (day)
            bgDrawable(Color.argb(125, 255, 255, 255), dashRoot) ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E6ECF4")))
        else bgDrawable(Color.argb(120, 0, 0, 0), dashRoot) ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Skins.look.bgTop, Skins.look.bgBottom))
        dashSpeedGauge.day = day; dashPowerGauge.day = day; dashStrip.day = day; dashStrip.invalidate()
        if (::tripPanel.isInitialized) { tripPanel.day = day; tripPanel.invalidate() }
        if (::chargeBar.isInitialized) { chargeBar.day = day; chargeBar.invalidate() }
        if (::dashClock.isInitialized) { dashClock.day = day; dashClock.invalidate() }
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        dashHeading.setTextColor(ink)
        if (::adasSpdNum.isInitialized) {
            val tf = Typeface.create(Skins.look.numFont, if (Skins.look.italic) Typeface.ITALIC else Typeface.NORMAL)
            val sub = if (day) Color.argb(190, 20, 32, 46) else Color.argb(190, 255, 255, 255)
            for (t in listOf(adasSpdNum, adasPwrNum, adasClockNum, adasTempNum)) { t.setTextColor(ink); t.typeface = tf }
            for (t in listOf(adasSpdTitle, adasPwrTitle, adasClockTitle, adasTempTitle)) t.setTextColor(sub)
        }
        dashHeading.typeface = Typeface.create(Skins.look.numFont, if (Skins.look.italic) Typeface.ITALIC else Typeface.NORMAL)
        dashReady.setTextColor(Color.parseColor(if (day) "#2E9E1F" else "#7CFF3A"))
        dashLightDist.setTextColor(if (day) Color.argb(200, 20, 32, 46) else Color.argb(200, 255, 255, 255))
        dashRow.day = day; dashRow.invalidate()
        dashSpeedGauge.refresh(); dashPowerGauge.refresh()
    }

    /** Every statistic the Pulse can supply, as title to text. Used by the dashboard and the main page. */
    private fun canStats(s: CanSnapshot): List<Pair<String, String>> {
        fun t(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 0)
        fun tc(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 0)
        val demand = when (s.thermalDemand) { 0 -> "starting up"; 1 -> "heating"; 2 -> "cooling"; 3 -> "cycling"; else -> "--" }
        val rangeText = s.rangeKm?.let { if (speedMph()) String.format("%.0f mi", it * 0.621371) else String.format("%.0f km", it) } ?: "--"
        // full capacity: only the BMS's own figure (a guess from energy left and charge % was wrong on a real car)
        val sohOk = s.sohPct?.takeIf { it in 50..100 }
        // the BMS's "actual pack capacity" field turned out to equal the energy left (same as HV Cap), so the
        // total capacity is the pack size from settings times the battery health
        val estCap = sohOk?.let { prefs().getFloat("packKwh", 106.5f) * it / 100.0 }
        return listOf(
            "HV Cap" to (s.capKwh?.let { String.format("%.1f kWh", it) } ?: "--"),
            "Est. Capacity" to (estCap?.let { String.format("%.1f kWh", it) } ?: "--"),
            "Battery Health" to (s.sohPct?.takeIf { it in 1..100 }?.let { "$it %" } ?: "--"),
            "Charge" to (s.socUsable?.let { "$it %" } ?: "--"),
            "Pack Volts" to (s.packVolts?.let { String.format("%.0f V", it) } ?: "--"),
            "Pack Amps" to (s.packAmps?.let { String.format("%.0f A", it) } ?: "--"),
            "Battery Temp" to tc(s.avgTempC),
            "Climate" to (s.climateKw?.let { String.format("%.1f kW", it) } ?: "--"),
            "Heat pump" to (s.compWatts?.let { if (s.compState == 2) "FAULT" else if (it > 20) String.format("%.2f kW", it / 1000) else "off" } ?: "--"),
            "PTC / Other" to (if (s.climateKw != null && s.compWatts != null) String.format("%.2f kW", maxOf(0.0, s.climateKw - s.compWatts / 1000)) else "--"),
            "Cabin" to t(s.cabinC),
            "Outside" to t(s.outsideC),
            "Solar now" to (s.solarW?.let { "$it W" } ?: "--"),
            "12V Battery" to (s.volts12?.let { String.format("%.1f V", it) } ?: "--"),
            "12V SOH" to (s.soh12?.let { String.format("%.0f %%", it) } ?: "--"),
            "12V SOC" to (s.soc12?.let { String.format("%.0f %%", it) } ?: "--"),
            "Range" to rangeText,
            "Regen" to String.format("%.2f kWh", s.regenKwh),
            "Cells" to "${tc(s.cellMinC)} \u2013 ${tc(s.cellMaxC)}",
            "Thermal" to demand,
        )
    }

    /** Speed gauge on the left or the right (a setting); the arcs open towards the middle either way. */
    private fun applyGaugeSides() {
        val speedLeft = prefs().getBoolean("speedLeft", false)
        val arcStyle = prefs().getString("arcStyle", "mirror")
        val leftG = if (speedLeft) dashSpeedGauge else dashPowerGauge
        val rightG = if (speedLeft) dashPowerGauge else dashSpeedGauge
        leftG.mirrored = arcStyle == "right"
        rightG.mirrored = arcStyle != "left"
        dashGaugeRow.removeAllViews()
        val left = if (speedLeft) dashSpeedGauge else dashPowerGauge
        val right = if (speedLeft) dashPowerGauge else dashSpeedGauge
        dashGaugeRow.addView(left, LinearLayout.LayoutParams(0, -1, gaugeWeight).apply { rightMargin = -dp(ovLeftDp); leftMargin = dp(edgeLeftDp) })
        dashGaugeRow.addView(dashMid, LinearLayout.LayoutParams(0, -1, midWeight))
        dashGaugeRow.addView(right, LinearLayout.LayoutParams(0, -1, gaugeWeight).apply { leftMargin = -dp(ovRightDp); rightMargin = dp(edgeRightDp) })
        dashSpeedGauge.invalidate(); dashPowerGauge.invalidate()
    }

    private fun savedList(key: String, legacy: String): List<String>? {
        val p = prefs()
        if (p.contains(key)) return p.getString(key, "")!!.split("|").filter { it.isNotEmpty() }
        return p.getString(legacy, "")!!.split("|").filter { it.isNotEmpty() }.ifEmpty { null }
    }

    /** The + button: opens or closes the list of readouts that are not showing, and puts the readouts into edit mode. */
    private fun togglePool() {
        val on = poolBar.visibility != View.VISIBLE
        poolBar.visibility = if (on) View.VISIBLE else View.GONE
        editBackdrop.visibility = if (on) View.VISIBLE else View.GONE
        applyLowerVisibility()
        dashStrip.editMode = on
        dashRow.editMode = on
        poolKey = "\u0000"
        if (on) refreshPool()
    }

    /** Rebuild the list of readouts that are available but not showing. */
    private fun refreshPool() {
        if (!::poolBar.isInitialized) return
        val shown = dashStrip.shownTitles() + dashRow.shownTitles()
        val pool = dashStrip.allTitles().filter { it !in shown }
        val key = pool.joinToString("|")
        if (key == poolKey) return
        poolKey = key
        poolRow.removeAllViews()
        fun lp() = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        poolRow.addView(TextView(this).apply {
            text = if (pool.isEmpty()) "All readouts are showing. Tap a minus to remove one." else "Add:"
            textSize = 13f; setTextColor(Color.argb(190, 255, 255, 255))
        }, lp())
        for (t in pool) {
            poolRow.addView(TextView(this).apply {
                text = t; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply { setColor(Color.argb(90, 42, 168, 255)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.argb(170, 42, 168, 255)) }
                setOnClickListener {
                    if (!dashStrip.addFirstFree(t) && !dashRow.addFirstFree(t))
                        toast("Both lines are full. Remove a readout with its minus first, or press and drag this one onto the readout it should replace.")
                }
                setOnLongClickListener { v ->
                    v.startDragAndDrop(ClipData.newPlainText("stat", t), View.DragShadowBuilder(v), null, 0)
                    true
                }
            }, lp())
        }
    }

    /** Fit the dashboard to the window it is in: a narrow (portrait) screen, or a short split-screen part. */
    private class DashTier(val strip: Int, val row: Int, val res: Boolean, val lower: Boolean, val showStrip: Boolean)

    /** Fit the dashboard to the window it is in. The window can be the whole screen or a part of a split screen, so the
     *  readouts under the gauges are stepped down (smaller, then fewer) until the gauges have enough room. */
    /** Full-screen mode: the Climate screen floats just under the battery bar and covers about the middle two thirds. Split windows: the whole window. */
    private var btLink: BtLink? = null
    private var lastSrcMode = -1
    private var btReceiver: android.content.BroadcastReceiver? = null

    /** Checks (in the background) that the Bluetooth link to FiskerOcean is connected, then has the media card look at the sessions again. */
    private fun btCheckAndSync(reason: String) {
        val w = mediaWatcher ?: return
        if (w.carLinkActive) { EventLog.add("bluetooth: a CarLink session is active, so the FiskerOcean check is skipped ($reason)"); w.resync(); return }
        val b = btLink ?: BtLink(this, ui) { m -> EventLog.add(m) }.also { btLink = it }
        b.check(reason, true) { _ -> mediaWatcher?.resync(); refreshMediaCards() }
    }

    private fun ensureMediaWatcher() {
        if (mediaWatcher != null) return
        mediaWatcher = MediaWatcher(this, ui, { Thread { rootRun("cmd notification allow_listener " + ComponentName(this, MainActivity::class.java).flattenToString()) }.start() }, { m -> EventLog.add(m) }).also { w ->
            w.onChange = { refreshMediaCards() }
            w.onExternalPlay = { if (InApp.mode == InApp.EXTERNAL) btCheckAndSync("another app started playing") }
        }
        if (btReceiver == null) {
            // a phone connecting or disconnecting: look at the media sessions again shortly after
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { ui.removeCallbacks(btResync); ui.postDelayed(btResync, 1500) }
            }
            try {
                registerReceiver(r, android.content.IntentFilter().apply { addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED) })
                btReceiver = r
            } catch (e: Exception) { }
        }
    }
    private val btResync = Runnable { mediaWatcher?.resync() }

    /** Shows what the card's source is playing: the external player / Bluetooth session, or our own player. */
    private fun refreshMediaCards() {
        if (!::mediaCard.isInitialized || !::mediaPopup.isInitialized) return
        val m = InApp.mode
        if (m != lastSrcMode) { val first = lastSrcMode < 0; lastSrcMode = m; if (!first && mediaWatcher != null) ui.post { btCheckAndSync("source changed") } }
        mediaCard.srcMode = m; mediaPopup.srcMode = m
        if (::pageSets.isInitialized) for (ps in pageSets) {
            val lm = ps.media
            lm.srcMode = m; lm.rhd = prefs().getBoolean("rhd", false)
            if (m != InApp.EXTERNAL) lm.update(InApp.title, InApp.artist, InApp.art, InApp.playing, InApp.hasSession)
            else mediaWatcher.let { w -> if (w != null) lm.update(w.title, w.artist, w.art, w.playing, w.hasSession) else lm.update(null, null, null, false, false) }
        }
        if (m != InApp.EXTERNAL) {
            mediaCard.update(InApp.title, InApp.artist, InApp.art, InApp.playing, InApp.hasSession)
            mediaPopup.update(InApp.title, InApp.artist, InApp.art, InApp.playing, InApp.hasSession)
        } else {
            val w = mediaWatcher
            if (w != null) {
                mediaCard.update(w.title, w.artist, w.art, w.playing, w.hasSession)
                mediaPopup.update(w.title, w.artist, w.art, w.playing, w.hasSession)
            } else { mediaCard.update(null, null, null, false, false); mediaPopup.update(null, null, null, false, false) }
        }
    }

    /** Our own player is about to start: stop the phone's music so the two do not play together. */
    private fun pauseExternalMusic() { if (mediaWatcher?.playing == true) mediaWatcher?.toggle() }

    /** The Sources button on the media card. */
    private fun showSources() {
        val names = arrayOf("External Player / Bluetooth", "Local MP3 file", "Streaming Internet Radio / Shoutcast")
        darkDialog().setTitle("Music source")
            .setSingleChoiceItems(names, InApp.mode) { dlg, i ->
                dlg.dismiss()
                when (i) {
                    0 -> { InApp.useExternal(); refreshMediaCards() }
                    1 -> pickLocalMusic()
                    else -> showRadioPicker()
                }
            }.setNegativeButton("Cancel", null).showDark()
    }

    private fun openAudioPicker() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQ_PICK_AUDIO)
        } catch (e: Exception) {
            toast("This device has no file picker app, so a music file can't be chosen here.")
        }
    }

    /** Lists the music files on the device inside the app (needs root, as for pictures); without root it uses Android's file picker. */
    private fun pickLocalMusic() {
        toast("Looking for music files...")
        Thread {
            val found = ArrayList<String>()
            if (Updater.rootAvailable()) {
                val r = Root.exec("find /storage -maxdepth 8 -type f \\( -iname '*.mp3' -o -iname '*.m4a' -o -iname '*.flac' -o -iname '*.ogg' -o -iname '*.wav' -o -iname '*.aac' \\) ! -path '*/Android/*' ! -path '*/.*' -size +50k 2>/dev/null | head -1500", 30)
                for (l in r.out.lines()) { val t = l.trim(); if (t.startsWith("/") && found.none { it.substringAfterLast('/') == t.substringAfterLast('/') && it.length == t.length }) found.add(t) }
            }
            ui.post {
                if (found.isEmpty()) { openAudioPicker(); return@post }
                found.sortWith(compareBy({ it.substringBeforeLast('/').lowercase() }, { it.substringAfterLast('/').lowercase() }))
                val labels = Array(found.size) { i -> found[i].substringAfterLast('/') + "\n" + found[i].substringBeforeLast('/').removePrefix("/storage/") }
                darkDialog().setTitle("Choose a music file").setItems(labels) { _, i ->
                    pauseExternalMusic(); InApp.playFiles(found, i); refreshMediaCards()
                }.setNeutralButton("Other...") { _, _ -> openAudioPicker() }.setNegativeButton("Cancel", null).showDark()
            }
        }.start()
    }


    /** Rows of stations: a star (tap to make it a favourite), the logo, the name and details. */
    private fun stationAdapter(getItems: () -> List<Station>, onStar: (Station) -> Unit): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = getItems().size
        override fun getItem(i: Int): Any = getItems()[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val s = getItems()[i]
            val row = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(0), dp(8), dp(4), dp(8))
                val starView = TextView(this@MainActivity).apply { textSize = 26f; gravity = Gravity.CENTER }
                addView(starView, LinearLayout.LayoutParams(dp(44), dp(52)))
                val pic = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { setColor(Color.parseColor("#2F3A52")); cornerRadius = dp(8).toFloat() }
                    clipToOutline = true
                }
                addView(pic, LinearLayout.LayoutParams(dp(52), dp(52)))
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this@MainActivity).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(TextView(this@MainActivity).apply { setTextColor(Color.parseColor("#9AA5B8")); textSize = 12f; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(12), 0, 0, 0) })
            }
            val starView = row.getChildAt(0) as TextView
            val pic = row.getChildAt(1) as ImageView
            val col = row.getChildAt(2) as LinearLayout
            val fav = Favs.has(this@MainActivity, s)
            starView.text = if (fav) "\u2605" else "\u2606"
            starView.setTextColor(if (fav) Color.parseColor("#FFC83D") else Color.parseColor("#8A94A6"))
            starView.setOnClickListener { onStar(s); notifyDataSetChanged() }
            (col.getChildAt(0) as TextView).text = s.name
            (col.getChildAt(1) as TextView).text = s.info
            pic.setImageDrawable(null); pic.tag = s.logo
            if (s.logo.isNotEmpty()) Logos.load(s.logo, 120) { b -> if (pic.tag == s.logo && b != null) pic.setImageBitmap(b) }
            return row
        }
    }

    /** The yellow star on the media card: a list of the favourite stations to play one from. */
    private fun showFavorites() {
        var favs = Favs.load(this)
        if (favs.isEmpty()) { toast("No favourite stations yet. Open Internet Radio and tap the star beside a station."); return }
        val list = ListView(this).apply { divider = ColorDrawable(Color.parseColor("#3A4150")); dividerHeight = 1; setPadding(dp(8), 0, dp(8), 0) }
        list.adapter = stationAdapter({ favs }) { st -> Favs.toggle(this, st); favs = Favs.load(this) }
        val dlg = darkDialog().setTitle("Favourite stations").setView(list).setNegativeButton("Close", null).showDark()
        list.setOnItemClickListener { _, _, pos, _ ->
            val pick = favs
            if (pos < pick.size) { pauseExternalMusic(); InApp.playStations(pick, pos); refreshMediaCards(); dlg.dismiss() }
        }
    }

    /** The station list: the most popular stations first, a search box and a few genres, each with its logo. */
    private fun showRadioPicker() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), 0) }
        val search = EditText(this).apply {
            hint = "Search stations"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#8A94A6"))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        box.addView(search, LinearLayout.LayoutParams(-1, -2))
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        box.addView(chipScroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, 0) })
        val status = TextView(this).apply { setTextColor(Color.parseColor("#9AA5B8")); textSize = 13f; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        box.addView(status, LinearLayout.LayoutParams(-1, -2))
        val list = ListView(this).apply { divider = ColorDrawable(Color.parseColor("#3A4150")); dividerHeight = 1 }
        box.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5f).toInt()))

        var items: List<Station> = emptyList()
        val adapter = stationAdapter({ items }) { st -> Favs.toggle(this, st) }
        list.adapter = adapter

        var loadId = 0
        fun load(fetch: () -> List<Station>?) {
            val token = ++loadId
            status.text = "Loading stations..."; items = emptyList(); adapter.notifyDataSetChanged()
            Thread {
                val l = try { fetch() } catch (e: Throwable) { null }
                ui.post {
                    if (token != loadId) return@post
                    if (l == null) { status.text = "Could not reach the station list. Check the internet connection." }
                    else { items = l; status.text = if (l.isEmpty()) "No stations found." else "" }
                    adapter.notifyDataSetChanged()
                }
            }.start()
        }
        fun chip(label: String, go: () -> Unit) {
            val t = TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply { setColor(Color.parseColor("#394255")); cornerRadius = dp(18).toFloat() }
                setOnClickListener { go() }
            }
            chipRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8), 0) })
        }
        chip("Top") { load { Radio.top() } }
        for (g in listOf("pop", "rock", "jazz", "classical", "country", "electronic", "hiphop", "oldies", "news", "talk")) chip(g.replaceFirstChar { it.uppercase() }) { load { Radio.byTag(g) } }
        search.setOnEditorActionListener { v, _, _ ->
            val q = v.text.toString().trim()
            if (q.isNotEmpty()) load { Radio.search(q) }
            true
        }
        val dlg = darkDialog().setTitle("Internet radio").setView(box).setNegativeButton("Close", null).showDark()
        dlg.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        list.setOnItemClickListener { _, _, pos, _ ->
            pauseExternalMusic(); InApp.playStations(items, pos); refreshMediaCards(); dlg.dismiss()
        }
        load { Radio.top() }
    }

    private val hideMediaPopup = Runnable { if (::mediaPopup.isInitialized) mediaPopup.visibility = View.GONE }

    /**
     * The Media pop-up card: it sits in the free space between the battery bar (or trip panel) and the stat rows,
     * never over the stat rows. With no usable space it goes just above the bottom buttons as before.
     */
    private var l2FreeTop = 0           // Layout 2: free space under the cards, in dp
    private var l2FreeBottom = 0
    private var l2Room = 0

    /** Layout 2 Media: the full media card when the window has room for it, else a card in the free space under the cards. */
    private fun l2MediaTap() {
        if (l2MiniNow) { l2MediaInPlace = !l2MediaInPlace; if (!l2MediaInPlace) l2ClimHidden = false; relayoutDash(); return }
        if (mediaCard.visibility == View.VISIBLE) { prefs().edit().putBoolean("fullMedia", false).apply(); relayoutDash(); return }
        if (l2Room >= 470) { prefs().edit().putBoolean("fullMedia", true).apply(); relayoutDash(); return }
        if (mediaPopup.visibility == View.VISIBLE) { ui.removeCallbacks(hideMediaPopup); mediaPopup.visibility = View.GONE; return }
        ensureMediaWatcher(); mediaWatcher?.start()
        placeMediaPopup()
        mediaPopup.bringToFront(); mediaPopup.visibility = View.VISIBLE
        lowerBox.bringToFront(); btnBar.bringToFront(); if (::cautionView.isInitialized && cautionView.visibility == View.VISIBLE) cautionView.bringToFront()
        if (::l2FlyBtn.isInitialized) l2FlyBtn.bringToFront()
        ui.removeCallbacks(hideMediaPopup)
    }

    /** Layout 2 clock: beside the gear handle (left of it in right-hand drive, right of it in left-hand drive). */
    private fun placeL2Clock() {
        if (!l2Applied || !::dashClock.isInitialized) return
        val lp = dashClock.layoutParams as FrameLayout.LayoutParams
        val rh = prefs().getBoolean("rhd", false)
        val g = Gravity.TOP or (if (rh) Gravity.END else Gravity.START)
        if (lp.gravity != g || lp.leftMargin != (if (rh) 0 else dp(56)) || lp.rightMargin != (if (rh) dp(56) else 0)) {
            lp.gravity = g; dashClock.alignRight = rh
            if (rh) lp.setMargins(0, dp(5), dp(56), 0) else lp.setMargins(dp(56), dp(5), 0, 0)
            dashClock.requestLayout()
        }
    }

    private fun placeMediaPopup() {
        if (!::mediaPopup.isInitialized) return
        mediaPopup.l2 = l2Applied
        val lp = mediaPopup.layoutParams as FrameLayout.LayoutParams
        var bottom = dp(66); var hgt = dp(132); var wid = FrameLayout.LayoutParams.MATCH_PARENT; var side = dp(8)
        var grav = Gravity.BOTTOM
        try {
            val ref: View? = if (chargeBar.visibility != View.GONE) chargeBar else if (tripPanel.visibility == View.VISIBLE) tripPanel else null
            val root = mediaPopup.parent as View
            // a half-height window (50/50 split): the card sits in the free space under the left gauge, between the trip panel and the stat rows
            val half = try {
                android.os.Build.VERSION.SDK_INT >= 30 && windowManager.currentWindowMetrics.bounds.height().let { it >= windowManager.maximumWindowMetrics.bounds.height() * 0.42 && it < windowManager.maximumWindowMetrics.bounds.height() * 0.8 }
            } catch (e: Exception) { false }
            // a third-height window (1/3 split): the window is under 42% of the screen height
            val third = try {
                android.os.Build.VERSION.SDK_INT >= 30 && windowManager.currentWindowMetrics.bounds.height() < windowManager.maximumWindowMetrics.bounds.height() * 0.42
            } catch (e: Exception) { false }
            val rowV: View? = if (dashRow.visibility == View.VISIBLE && dashRow.height > 0) dashRow else if (dashStrip.visibility == View.VISIBLE && dashStrip.height > 0) dashStrip else null
            val rhd = prefs().getBoolean("rhd", false)
            var placed = false
            if (l2Applied && l2FreeBottom - l2FreeTop >= 70 && root.height > 0) {
                // Layout 2: in the free space under the cards, nothing else moves
                hgt = dp(minOf(132, l2FreeBottom - l2FreeTop)); side = dp(8)
                bottom = maxOf(root.height - dp(l2FreeTop) - hgt, 0)
                grav = Gravity.BOTTOM; placed = true
            }
            if (!placed && (half || !l2Applied) && ref != null && ref.width > 0 && rowV != null && root.height > 0) {
                val a = IntArray(2); val b = IntArray(2); val c = IntArray(2)
                ref.getLocationInWindow(a); root.getLocationInWindow(b); rowV.getLocationInWindow(c)
                val freeW = if (rhd) root.width - (a[0] - b[0] + ref.width) - dp(28) else (a[0] - b[0]) - dp(28)
                if (freeW >= dp(190)) {
                    wid = minOf(freeW, dp(240)); hgt = dp(102); side = dp(16)
                    bottom = root.height - (c[1] - b[1]) - dp(4)    // top edge 15 dp lower than before, 3 dp shorter
                    grav = Gravity.BOTTOM or (if (rhd) Gravity.END else Gravity.START)
                    placed = true
                }
            }
            if (!placed && ref != null && ref.height > 0 && root.height > 0) {
                val a = IntArray(2); val b = IntArray(2)
                ref.getLocationInWindow(a); root.getLocationInWindow(b)
                val top = a[1] - b[1] + ref.height + dp(5)                         // 5 dp under the battery bar
                // the same size in every layout, 5 dp under the battery bar (it can cover the stat rows in the shorter 1784 screen)
                hgt = if (third) dp(122) else dp(132)                         // 10 dp less tall in 1/3 split only
                bottom = maxOf(root.height - (top + hgt), dp(66))
            }
        } catch (e: Exception) { }
        if (lp.height != hgt || lp.width != wid || lp.bottomMargin != bottom || lp.leftMargin != side || lp.gravity != grav) {
            lp.height = hgt; lp.width = wid; lp.gravity = grav; lp.setMargins(side, 0, side, bottom)
            mediaPopup.layoutParams = lp
        }
    }

    /** The Climate screen's background follows the dashboard theme (re-applied whenever the theme changes). */
    private fun applyClimateColors() {
        run {
            // the skin's accent drives the lit buttons, fan level and power button; a very pale accent is toned down so white text stays readable
            val ac = Skins.look.accent
            val l = (0.299f * Color.red(ac) + 0.587f * Color.green(ac) + 0.114f * Color.blue(ac)) / 255f
            climateView.accent = if (l > 0.7f) Color.rgb((Color.red(ac) * 0.55f + 108 * 0.45f).toInt(), (Color.green(ac) * 0.55f + 122 * 0.45f).toInt(), (Color.blue(ac) * 0.55f + 140 * 0.45f).toInt()) else ac
        }
        // night: the skin's own dark background; Day mode: light grey with a soft tint of the skin colour
        run {
            val ac = climateView.accent
            fun sc(c: Int, k: Float) = Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
            fun mx(a: Int, b: Int, t: Float) = Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
            if (dashDay) {
                climateView.bgTopColor = mx(Color.parseColor("#DCE1E7"), ac, 0.22f)
                climateView.bgColor = mx(Color.parseColor("#BFC6CF"), ac, 0.22f)
            } else {
                // night: the skin's own dashboard background, as before
                val base = Skins.look.bgBottom or (0xFF shl 24)
                val lum = (0.299f * Color.red(base) + 0.587f * Color.green(base) + 0.114f * Color.blue(base)) / 255f
                val k = if (lum > 0.35f) 0.28f else 1f
                climateView.bgColor = sc(base, k)
                climateView.bgTopColor = sc(Skins.look.bgTop or (0xFF shl 24), k)
            }
        }
        climateView.invalidate()
    }

    /** Show the Climate screen (from its button, or automatically when the window becomes full screen). */
    private fun openClimate() {
                climateView.snap = canSnap; climateView.fahrenheit = useFahrenheit
                applyClimateColors()
                climateView.fanControl = prefs().getBoolean("climateTx", true)
                placeClimate()
                climateView.bringToFront(); climateView.visibility = View.VISIBLE
                if (::cautionView.isInitialized && cautionView.visibility == View.VISIBLE) cautionView.bringToFront()   // the beta notice stays on top
    }

    private fun placeClimate() {
        val lp = climateView.layoutParams as FrameLayout.LayoutParams
        val full = try {
            android.os.Build.VERSION.SDK_INT >= 30 && windowManager.currentWindowMetrics.bounds.height() >= windowManager.maximumWindowMetrics.bounds.height() * 0.8
        } catch (e: Exception) { false }
        if (l2Applied && l2ClimInline && edgeBox.height > 0) {
            lp.topMargin = dp(l2ClimTop); lp.height = dp(l2ClimH); lp.leftMargin = dp(8); lp.rightMargin = dp(8); lp.bottomMargin = 0
            climateView.floating = true
        } else if (full && edgeBox.height > 0) {
            var top: Int; var hgt: Int
            if (climateHDp > 0) {
                // the full-screen order: gauges, stat rows and buttons, media card, then this panel at the bottom
                top = dp(climateTopDp); hgt = dp(climateHDp)
            } else {
                val ref: View = if (chargeBar.visibility == View.VISIBLE) chargeBar else tripPanel
                val a = IntArray(2); val b = IntArray(2)
                ref.getLocationInWindow(a); edgeBox.getLocationInWindow(b)
                top = a[1] - b[1] + ref.height + dp(8)
                if (ref.height <= 0 || top > edgeBox.height / 2) top = (edgeBox.height * 0.22f).toInt()
                hgt = minOf((edgeBox.height * 0.66f).toInt(), edgeBox.height - top - dp(70))
            }
            lp.topMargin = top; lp.height = hgt; lp.leftMargin = dp(8); lp.rightMargin = dp(8); lp.bottomMargin = 0
            climateView.floating = true
        } else {
            lp.topMargin = 0; lp.height = FrameLayout.LayoutParams.MATCH_PARENT; lp.leftMargin = 0; lp.rightMargin = 0
            climateView.floating = false
        }
        climateView.layoutParams = lp
    }

    private fun adaptDashLayout(wDp: Int, hDp: Int) {
        if (wDp <= 0 || hDp <= 0) return
        if (adasMode && !(isFullWindow() && (l2Applied || l1cApplied))) setAdas(false, false)
        val narrow = wDp < 800
        val l2 = prefs().getInt("layout", 1) == 2 && wDp >= 640 && hDp >= 300
        // a third-height split window (not half or full) keeps the bottom buttons at the left edge
        val third = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.height() < windowManager.maximumWindowMetrics.bounds.height() * 0.42 else false
        } catch (e: Exception) { false }
        // a half-height split window needs the bottom buttons moved in a little further
        val half = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.height().let { it >= windowManager.maximumWindowMetrics.bounds.height() * 0.42 && it < windowManager.maximumWindowMetrics.bounds.height() * 0.8 } else false
        } catch (e: Exception) { false }
        // a third-height window starts with a smaller stats area (keeping the screen-size button) so the gauges get more height
        val tiers = (if (third) listOf(DashTier(44, 34, true, true, true)) else emptyList()) + listOf(
            DashTier(56, 46, true, true, true),
            DashTier(50, 40, false, true, true),
            DashTier(44, 34, false, true, true),
            DashTier(44, 34, false, false, true),
            DashTier(0, 34, false, false, false),
        )
        val need = (wDp * 0.30f).coerceIn(150f, 300f)
        val base = if (l2) 8 else 62                 // the strip sits above the bottom bar (app buttons on the left, Hide / Exit on the right)
        fun stack(t: DashTier): Int {
            var s = base + 8
            if (t.showStrip) s += t.strip + 4
            s += t.row + 4
            return s
        }
        var ti = tiers.size - 1
        for ((i, t) in tiers.withIndex()) if (hDp - stack(t) >= need) { ti = i; break }
        val t = tiers[ti]
        // the trip panel sits under the stats strip, only when the gauges keep a good height without squeezing
        val tripOff = 0
        val gaugeH = hDp - stack(t) - tripOff
        val midLevel = if (gaugeH >= 300) 0 else if (gaugeH >= 235) 1 else 2
        // in a tall window (full screen) the gauges stay at the top instead of being centred in all the free height
        val gb = (if (t.showStrip) base + t.strip + 4 else base) + t.row + 8 + tripOff
        val topM = if (l2) 34 else if (midLevel > 0) 20 else 8
        val wantH = maxOf((wDp - 16) * 1.15f / 3f * 1.1f, 340f)
        val tall = hDp - gb - topM > wantH + 24f
        // the trip readouts sit in the middle column under the traffic light; rows by the height left there
        val hideBar = prefs().getBoolean("hideBatteryBar", false)
        // with the battery bar hidden, short windows give its height to the trip panel; roomy ones keep the panel as it is
        val midFixed = when (midLevel) { 0 -> 168f; 1 -> 142f; else -> 98f } - (if (hideBar && midLevel > 0) 34f else 0f)
        val midFree = (if (tall) wantH else gaugeH - topM.toFloat()) - midFixed
        val hideTrip = prefs().getBoolean("hideTrip", false)
        val midRows = if (hideTrip) 0 else if (midFree >= 104f) 3 else if (midFree >= 66f) 2 else 0
        // the clock goes in the free band above the left gauge (the gauge circle is centred in its height)
        val rowHdp = if (tall) wantH else gaugeH - topM.toFloat()
        // the gauge circles are limited by the row height, so any spare width goes to the middle column (bigger trip text)
        val gwDefault = (wDp - 16) * 1.15f / 3.0f
        var gaugeWdp = maxOf(minOf(gwDefault, rowHdp), gwDefault * 0.85f)
        if (l2) gaugeWdp = minOf((wDp - 16) * 0.28f, (rowHdp - 48f) / 0.96f).coerceAtLeast(120f)
        val wInner = (wDp - 16).toFloat()
        val arcMode = prefs().getString("arcStyle", "mirror")
        val ovL: Int; val ovR: Int
        var edgeL = 0; var edgeR = 0; var sameMid = -1f
        if (!l2 && (arcMode == "left" || arcMode == "right")) {
            // both arcs curve the same way: the middle column is centred on the window, with a clear gap to each arc.
            // The gauge on the far side from the "free" side moves outward (its box may reach past the window edge, it is empty there)
            val gapDp = 10f
            val midMin = wInner * 0.25f
            fun halfW(g: Float) = wInner / 2f - g / 2f - 0.707f * (minOf(g, rowHdp) / 2f * 0.92f) - gapDp
            while (2f * halfW(gaugeWdp) < midMin && gaugeWdp > gwDefault * 0.6f) gaugeWdp -= 2f
            val g = gaugeWdp
            val r = minOf(g, rowHdp) / 2f * 0.92f
            val h = halfW(g)
            val a = wInner / 2f - h
            val b = wInner / 2f + h
            val cxL: Float; val cxR: Float
            if (arcMode == "left") { cxL = g / 2f; cxR = b + gapDp + r } else { cxR = wInner - g / 2f; cxL = a - gapDp - r }
            edgeL = (cxL - g / 2f).toInt()                      // first box starts here (negative = past the left edge)
            edgeR = (wInner - (cxR + g / 2f)).toInt()           // last box's right margin (negative = past the right edge)
            sameMid = b - a
            ovL = (edgeL + g - a).toInt()
            ovR = (b - (cxR - g / 2f)).toInt()
        } else {
            // mirrored arcs open toward the middle, so the middle column may reach over the gauge boxes
            val midW0 = wInner - 2f * gaugeWdp
            val ovCap = gaugeWdp * 0.15f
            val ovTotal = wInner * 0.275f - midW0
            ovL = (ovTotal / 2f).coerceIn(0f, ovCap).toInt(); ovR = ovL
        }
        val midWdp = wInner - 2f * gaugeWdp
        // 50/50 split: the gauges move up a little so the media card under the left gauge clears them; the clock shrinks to suit
        val up = if (half && !tall) 12 else 0
        val band = rowHdp / 2f - minOf(gaugeWdp, rowHdp) / 2f * 0.92f - 6f - up
        val clockH = if (l2) 26 else if (band >= 30f) band.toInt().coerceAtMost(110) else if (third) 24 else 0
        val rhd = prefs().getBoolean("rhd", false)
        val l1c = !l2 && prefs().getBoolean("l1Car3d", false)
        val key = "$l1c/$hDp/$narrow/$ti/$midLevel/$third/$half/$tall/$midRows/$clockH/${gaugeWdp.toInt()}/$ovL/$ovR/$edgeL/$edgeR/$up/$rhd/$hideBar/$hideTrip/${prefs().getBoolean("fullMedia", true)}/${prefs().getBoolean("iconBtns", false)}/${prefs().getBoolean("moreBtns", false)}/${showSkinBtn()}/$l2/${prefs().getBoolean("l2Climate", true)}"
        if (key == layoutKey) return
        layoutKey = key
        if (l2 != l2Applied || l1c != l1cApplied) { l2Applied = l2; l1cApplied = l1c; fillMid(l2); statusMode = null }
        compact = ti > 0 || third
        val short = midLevel > 0
        // a third-height window (1/3 split): the brake text goes back under the speed limit sign, with its full wording
        val thirdWin = try {
            android.os.Build.VERSION.SDK_INT >= 30 && windowManager.currentWindowMetrics.bounds.height() < windowManager.maximumWindowMetrics.bounds.height() * 0.42
        } catch (e: Exception) { false }
        val brakeUnder = short && thirdWin
        val stripBottom = base + tripOff
        val rowBottom = if (t.showStrip) stripBottom + t.strip + 4 else base
        tripMidRows = if (l1c) 0 else midRows
        chargeBar.visibility = if (!hideBar) View.VISIBLE else if (midLevel == 0) View.INVISIBLE else View.GONE
        // with plenty of height the panel stays a little shorter and sits just above the battery bar
        (tripSpacer.layoutParams as LinearLayout.LayoutParams).weight = if (!l1c && midRows >= 3 && midLevel > 0) 0.28f else 0f
        tripSpacer.requestLayout()
        gaugeWeight = gaugeWdp; midWeight = if (sameMid > 0f) sameMid else midWdp + ovL + ovR; ovLeftDp = ovL; ovRightDp = ovR; edgeLeftDp = edgeL; edgeRightDp = edgeR
        (dashGaugeRow.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = gaugeWdp
        (dashGaugeRow.getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = midWeight
        // 1/3 split only: the middle column starts 15 dp higher (its bottom stays), so the speed sign moves up and the trip panel is 15 dp taller
        (dashGaugeRow.getChildAt(1).layoutParams as LinearLayout.LayoutParams).topMargin = if (brakeUnder) -dp(3) else 0
        (dashGaugeRow.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { rightMargin = -dp(ovL); leftMargin = dp(edgeL) }
        dashGaugeRow.clipChildren = false
        (dashGaugeRow.getChildAt(2).layoutParams as LinearLayout.LayoutParams).apply { leftMargin = -dp(ovR); rightMargin = dp(edgeR) }
        (dashGaugeRow.getChildAt(2).layoutParams as LinearLayout.LayoutParams).weight = gaugeWdp
        dashGaugeRow.requestLayout()
        dashClock.visibility = if (clockH > 0) View.VISIBLE else View.GONE
        dashClock.alignRight = rhd
        (dashClock.layoutParams as FrameLayout.LayoutParams).apply {
            if (!l2) { width = dp(gaugeWdp.toInt() - 6); height = dp(clockH) }
            if (l2) {
                // Layout 2: the time sits in the top row beside the drive mode, away from the settings handle
                // next to the gear handle: left-hand drive: top left, just right of it; right-hand drive: top right, just left of it
                width = dp(112); height = dp(26)
                dashClock.alignRight = rhd
                gravity = Gravity.TOP or (if (rhd) Gravity.END else Gravity.START)
                if (rhd) setMargins(0, dp(5), dp(56), 0) else setMargins(dp(56), dp(5), 0, 0)
            } else {
                gravity = Gravity.TOP or (if (rhd) Gravity.END else Gravity.START)
                val ct = if (third && clockH == 24) 2 else topM
                if (rhd) setMargins(0, dp(ct), dp(14), 0) else setMargins(dp(14), dp(ct), 0, 0)
            }
        }
        dashClock.requestLayout()
        tripPanel.visibility = if (l1c) (if (hideTrip) View.GONE else View.VISIBLE) else if (midRows > 0) View.VISIBLE else View.GONE
        tripBtn.visibility = if (l1c || midRows > 0 || hideTrip) View.GONE else View.VISIBLE
        tripPanel.cols = if (l1c) 4 else 2
        tripPanel.title = if (l1c) null else "Trip Information"
        (tripPanel.layoutParams as LinearLayout.LayoutParams).apply { if (l1c) { height = dp(58); weight = 0f; topMargin = dp(2) } else { height = 0; weight = 1f; topMargin = dp(4) } }
        if (l1c) (carView.layoutParams as LinearLayout.LayoutParams).apply { width = -1; height = 0; weight = 1f; topMargin = 0 }
        tripPanel.requestLayout(); tripPanel.invalidate()
        if (!l2 && !l1c) {
            (dashSlot.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (midLevel == 2) 48 else if (short) 58 else 92); topMargin = dp(if (brakeUnder) 0 else 4) }
            dashSlot.requestLayout()
        }
        // short windows: READY, the light distance and the brake pill sit beside the sign instead of stacking, so the trip panel gets the height
        // READY / light distance / brake placement by how much height the middle column has
        //  level 0 (roomy): READY under the battery bar when tall, light distance beside the sign, brake pill in the column
        //  level 1 (a third-height window): READY centred above the sign, light distance below it, brake pill beside the sign
        //  level 2 (very short): all three beside the sign
        val readyBelow = tall && midLevel == 0 && !l1c
        // a third-height window with the battery bar hidden: READY goes directly under the trip panel
        val readyUnderPanel = midLevel == 1 && hideBar && !readyBelow && !l1c
        val modeKey = "$midLevel/$readyBelow/$readyUnderPanel/$brakeUnder"
        readyBeside = midLevel == 1 && brakeUnder && !readyBelow && !readyUnderPanel && !l1c
        if (l1c || l2) {
            if (l1c && statusMode != modeKey) {
                statusMode = modeKey
                for (v in listOf<View>(dashReady, dashBrake)) { (v.parent as? ViewGroup)?.removeView(v) }
                dashMid.addView(dashReady, 0, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                dashMid.addView(dashBrake, 1, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }
            // the speed sign is overlaid on the speed gauge, left of its reading, and the traffic light is overlaid above the car on the road;
            // both are positioned from the real gauge / car bounds in placeL1Overlays (translation only, so nothing else moves)
            val signSz = (wDp * 0.07f).toInt()
            (dashSlot.layoutParams as FrameLayout.LayoutParams).apply { width = dp(signSz); height = dp(signSz); gravity = Gravity.TOP or Gravity.START; setMargins(0, 0, 0, 0) }
            dashSlot.clipChildren = false; dashSlot.clipToPadding = false
            (dashSignSrc.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(signSz + if (l2) 35 else 0) }     // "Camera + Map" sits just below the sign (Layout 2: 35 dp lower)
            (dashLight.layoutParams as FrameLayout.LayoutParams).apply { width = dp(34); height = dp(72); gravity = Gravity.TOP or Gravity.START; setMargins(0, 0, 0, 0) }
            (dashLightDist.layoutParams as FrameLayout.LayoutParams).apply { width = -2; gravity = Gravity.TOP or Gravity.START; setMargins(0, 0, 0, 0) }
            dashSlot.requestLayout(); dashLight.requestLayout(); dashLightDist.requestLayout()
        } else if (!l2 && statusMode != modeKey) {
            statusMode = modeKey
            dashMid.removeView(dashReady); dashMid.removeView(dashLightDist); dashMid.removeView(dashBrake)
            dashSlot.removeView(dashReady); dashSlot.removeView(dashLightDist); dashSlot.removeView(dashBrake)
            dashRoot.removeView(dashReady)
            when {
                readyBelow -> dashRoot.addView(dashReady, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                readyUnderPanel -> { }
                midLevel == 1 && !brakeUnder -> dashMid.addView(dashReady, 0, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                else -> dashSlot.addView(dashReady, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(4) })
            }
            if (midLevel == 1) dashMid.addView(dashLightDist, if (readyUnderPanel) 1 else 2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            else dashSlot.addView(dashLightDist, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(4) })
            if (brakeUnder) dashMid.addView(dashBrake, dashMid.indexOfChild(dashSlot) + 1, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            else if (short) dashSlot.addView(dashBrake, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(4) })
            else dashMid.addView(dashBrake, 1, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            if (readyUnderPanel) dashMid.addView(dashReady, dashMid.indexOfChild(tripPanel) + 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 0; bottomMargin = dp(2) })
        }
        if (readyBelow && !l2 && !l1c) {
            (dashReady.layoutParams as FrameLayout.LayoutParams).topMargin = dp(topM + wantH.toInt() + 4)
            dashReady.requestLayout()
        }
        dashBrake.text = if (short && !brakeUnder) "Brake" else "Brake light on"
        val lowerBottom = rowBottom + t.row + 4          // top of the info row: the stat editor sits here
        val gaugeBottom = lowerBottom + 4
        val sideL = 8
        val sideR = 8
        // full screen only, from the top: gauges and trip panel, media card, the Climate panel, then the stat rows and buttons at the bottom
        val mediaTopY = topM + wantH.toInt() + 34
        val climBottomY = hDp - lowerBottom - 8            // the Climate panel ends just above the stat rows
        // the media card is 132 dp tall, or as little as 100 dp when the window is short, so the Climate panel keeps at least 380 dp under it
        val mediaH = (climBottomY - 8 - mediaTopY - 380).coerceIn(100, 132)
        val mediaEndY = mediaTopY + mediaH
        val climH = climBottomY - (mediaEndY + 8)
        val showMedia = !l2 && tall && climH >= 380
        val rowBottomM = rowBottom
        val stripBottomM = stripBottom
        val lowerBoxBottomM = 10
        val lowerBottomM = lowerBottom
        if (dashShown && wasFullOrder && !showMedia) {
            // leaving full screen for split screen: the Climate panel closes, and the media card stays up as the floating card
            climateView.visibility = View.GONE
            if (!l2 && prefs().getBoolean("fullMedia", true)) ui.post {
                if (dashShown && !fullOrderNow) {
                    ensureMediaWatcher(); mediaWatcher?.start()
                    placeMediaPopup()
                    mediaPopup.bringToFront(); mediaPopup.visibility = View.VISIBLE
                    lowerBox.bringToFront(); btnBar.bringToFront(); if (::cautionView.isInitialized && cautionView.visibility == View.VISIBLE) cautionView.bringToFront()
                    ui.removeCallbacks(hideMediaPopup)
                }
            }
        }
        fullOrderNow = showMedia
        val mediaOn = showMedia && prefs().getBoolean("fullMedia", true)
        // the Climate panel starts under the media card, or directly under the buttons when the card is off
        val climTop = if (mediaOn) mediaEndY + 8 else mediaTopY
        climateTopDp = if (showMedia) climTop else 0
        climateHDp = if (showMedia) climBottomY - climTop else 0
        if (climateView.visibility == View.VISIBLE) edgeBox.post { placeClimate() }
        else if (showMedia && !wasFullOrder && dashShown && prefs().getBoolean("l1Climate", true)) edgeBox.post { if (dashShown) openClimate() }   // entering full screen: the Climate section comes up by itself
        if (dashShown) wasFullOrder = showMedia
        (mediaCard.layoutParams as FrameLayout.LayoutParams).apply { height = dp(mediaH); gravity = Gravity.BOTTOM; setMargins(dp(8), 0, dp(8), dp(maxOf(hDp - mediaEndY, 0))) }
        mediaCard.rhd = prefs().getBoolean("rhd", false); mediaPopup.rhd = mediaCard.rhd; mediaCard.invalidate(); mediaPopup.invalidate()
        mediaCard.visibility = if (mediaOn) View.VISIBLE else View.GONE
        mediaBtn.visibility = View.VISIBLE
        if (showMedia) mediaPopup.visibility = View.GONE
        if (mediaOn) {
            ensureMediaWatcher()
            mediaWatcher?.start()
        }
        dashStrip.visibility = if (t.showStrip) View.VISIBLE else View.GONE
        if (!t.showStrip && poolBar.visibility == View.VISIBLE) togglePool()
        // bottom bar: Screen button and the app buttons, always on one line (Hide / Exit are on the same line, right)
        lowerInline = true; flyOpen = false
        resCompact = true
        updateResText()
        lowerBox.orientation = LinearLayout.HORIZONTAL
        lowerBox.gravity = Gravity.CENTER_VERTICAL
        (resBtn.layoutParams as? LinearLayout.LayoutParams)?.apply { rightMargin = dp(6) }
        (lowerScroll.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = 0 }
        resBtn.visibility = View.VISIBLE
        lowerBox.background = null
        lowerBox.setPadding(0, 0, 0, 0)
        applyLowerVisibility()
        val editBottom = if (t.showStrip) stripBottomM else rowBottomM
        val poolTop = lowerBottomM + 44
        (editBackdrop.layoutParams as FrameLayout.LayoutParams).apply { height = dp(poolTop - editBottom + 8); setMargins(dp(4), 0, dp(4), dp(maxOf(editBottom - 4, 0))) }
        editBackdrop.requestLayout()
        (dashStrip.layoutParams as FrameLayout.LayoutParams).apply { height = dp(maxOf(t.strip, 1)); setMargins(dp(sideL), 0, dp(sideR), dp(stripBottomM)) }
        (dashRow.layoutParams as FrameLayout.LayoutParams).apply { height = dp(t.row); setMargins(dp(sideL), 0, dp(sideR), dp(rowBottomM)) }
        val edgeOff = dp(8) + (if (third) 0 else if (half) 20 else 10)
        (lowerBox.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM or (if (rhd) Gravity.END else Gravity.START)
            if (rhd) setMargins(0, 0, dp(8), dp(lowerBoxBottomM)) else setMargins(edgeOff, 0, 0, dp(lowerBoxBottomM))
        }
        // children in mirrored order for right-hand drive
        lowerBox.removeAllViews()
        if (!l2) (resBtn.parent as? ViewGroup)?.removeView(resBtn)
        val lowerKids = if (l2) emptyList() else listOf(climateBtn, mediaBtn, resBtn, tripBtn, lowerScroll, lowerAdd)
        // Climate / Media come back from Layout 2 carrying its FrameLayout margins (a big top margin): give them fresh ones
        for (b in listOf(climateBtn, mediaBtn)) if (!l2 && b.layoutParams !is LinearLayout.LayoutParams) b.layoutParams = LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) }
        for (v in if (rhd) lowerKids.reversed() else lowerKids) lowerBox.addView(v)
        (mediaBtn.layoutParams as? LinearLayout.LayoutParams)?.apply { leftMargin = if (rhd) dp(6) else 0; rightMargin = if (rhd) 0 else dp(6) }
        (climateBtn.layoutParams as? LinearLayout.LayoutParams)?.apply { leftMargin = if (rhd) dp(6) else 0; rightMargin = if (rhd) 0 else dp(6) }
        (resBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = if (rhd) dp(6) else 0; rightMargin = if (rhd) 0 else dp(6) }
        (tripBtn.layoutParams as? LinearLayout.LayoutParams)?.apply { leftMargin = if (rhd) dp(6) else 0; rightMargin = if (rhd) 0 else dp(6) }
        (lowerAdd.layoutParams as? LinearLayout.LayoutParams)?.apply { leftMargin = if (rhd) 0 else dp(6); rightMargin = if (rhd) dp(6) else 0; height = dp(42); topMargin = 0 }
        (btnBar.layoutParams as FrameLayout.LayoutParams).apply {
            if (l2) {
                // Layout 2: the buttons live in a flyout under a round gear handle at the top right
                gravity = Gravity.TOP or (if (rhd) Gravity.END else Gravity.START)
                if (rhd) setMargins(0, dp(50), dp(8), 0) else setMargins(dp(8), dp(50), 0, 0)
            } else {
                gravity = Gravity.BOTTOM or (if (rhd) Gravity.START else Gravity.END)
                if (rhd) setMargins(edgeOff, 0, 0, dp(16)) else setMargins(0, 0, dp(8), dp(16))
            }
        }
        val barKids = (if (l2) listOf(l2ClimBtn, l2MediaBtn, resBtn) + (if (rhd) listOf(l2SaveBtn, l2LoadBtn) else listOf(l2LoadBtn, l2SaveBtn)) else emptyList()) + (if (!l2) listOf(presetsBtn, settingsBtn, dashBtn, exitBtn) else if (showSkinBtn()) listOf(skinBtn, settingsBtn, dashBtn, exitBtn) else listOf(settingsBtn, dashBtn, exitBtn))
        btnBar.removeAllViews()
        if (l2) {
            btnBar.orientation = LinearLayout.VERTICAL
            btnBar.background = GradientDrawable().apply { setColor(Color.argb(215, 14, 20, 38)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.argb(130, 255, 255, 255)) }
            btnBar.setPadding(dp(8), dp(8), dp(8), dp(4))
            btnBar.visibility = if (l2FlyOpen) View.VISIBLE else View.GONE
            l2FlyBtn.textSize = 20f
            (l2FlyBtn.layoutParams as? FrameLayout.LayoutParams)?.apply {
                width = dp(40); height = dp(40)
                gravity = Gravity.TOP or (if (rhd) Gravity.END else Gravity.START)
                if (rhd) setMargins(0, dp(6), dp(8), 0) else setMargins(dp(8), dp(6), 0, 0)
            }
        } else {
            btnBar.orientation = LinearLayout.HORIZONTAL; btnBar.background = null; btnBar.setPadding(0, 0, 0, 0)
            btnBar.visibility = View.VISIBLE; l2FlyOpen = false; ui.removeCallbacks(closeL2Fly)
        }
        for (v in barKids) (v.parent as? ViewGroup)?.removeView(v)
        for ((i, v) in (if (rhd) barKids.reversed() else barKids).withIndex()) {
            if (l2) btnBar.addView(v, LinearLayout.LayoutParams(-1, dp(36)).apply { if (i < barKids.size - 1) bottomMargin = dp(6) })
            else btnBar.addView(v, LinearLayout.LayoutParams(-2, dp(36)).apply { if (i < barKids.size - 1) rightMargin = dp(8) })
        }
        if (showSkinBtn()) skinBtn.visibility = if (dashShown) View.VISIBLE else View.GONE
        if (!l2) presetsBtn.visibility = if (dashShown) View.VISIBLE else View.GONE
        btnBar.requestLayout(); lowerBox.requestLayout()
        refreshLowerLabels()
        (poolBar.layoutParams as FrameLayout.LayoutParams).apply { setMargins(dp(8), 0, dp(8), dp(lowerBottomM)) }
        // Layout 2: the gauge circles sit at the top of the row, the battery bar and buttons right under them, and the cards end level with those
        val l2GaugeH = gaugeWdp.toInt()
        val l2CircleB = topM + (l2GaugeH * 0.96f).toInt()
        val l2BarTop = l2CircleB + 4
        var l2RowH = l2BarTop + 30 + 14 - topM + 70   // 70 dp taller: gauges, car and cards sit lower, the media card and Climate panel below them
        // a third-height window (Layout 2 only): the cards end just above the stat rows, close under the car, and the gauges, battery bar and buttons move up with them
        val l2Third = l2 && third
        var l2CardsH = 145
        if (l2Third) {
            val limit = hDp - lowerBottom - 4
            val cTop = maxOf(limit - 145, topM + 140)
            l2CardsH = (limit - cTop).coerceIn(110, 145)
            l2RowH = cTop + l2CardsH - topM
        }
        for (gi in intArrayOf(0, 2)) (dashGaugeRow.getChildAt(gi).layoutParams as LinearLayout.LayoutParams).apply {
            height = if (l2) dp(l2GaugeH) else -1
            gravity = if (l2) Gravity.TOP else Gravity.NO_GRAVITY
            topMargin = if (l2) dp(if (third) 15 else 45) else 0
        }
        // Layout 1 with the 3D car, full screen with the media card: the middle column (road and car) reaches down to the top of the media card
        val midExtraDp = if (l1c && tall && showMedia) (mediaTopY - topM - wantH.toInt()).coerceAtLeast(0) else 0
        dashGaugeRow.getChildAt(1).let { (it.layoutParams as LinearLayout.LayoutParams).apply { topMargin = if (l2) dp(2) else (if (brakeUnder) -dp(3) else 0); bottomMargin = -dp(midExtraDp) } }
        dashPowerGauge.iconLevel = if (l2) (if (half) 2 else 1) else 0; dashPowerGauge.invalidate()
        // Layout 2 (full screen and 50/50, not the third-height window): the two gauges grow 10% about their own centres and sit 28 dp higher; nothing else moves
        for (gv in listOf<View>(dashPowerGauge, dashSpeedGauge)) {
            val big = l2 && !third
            gv.scaleX = if (big) 1.10f else 1f; gv.scaleY = gv.scaleX
            gv.translationY = if (big) -dp(28).toFloat() else 0f
        }
        // Layout 2 in a 50/50 window: the grey road fades out upward to the top of the screen, under READY
        roadView.car = carView; roadView.visibility = if (l2 || l1c) View.VISIBLE else View.GONE; roadView.invalidate()
        l2Mid.clipChildren = false; dashRoot.clipChildren = false; dashGaugeRow.clipChildren = false
        // everything between the car view and the dashboard root must let the car's glow and sensor arcs draw above the view
        run { var pv: android.view.ViewParent? = carView.parent; while (pv is ViewGroup) { pv.clipChildren = false; pv.clipToPadding = false; if (pv === dashRoot) break; pv = pv.parent } }
        if (l2) {
            // middle column: the car and the road lines fill the top 162 dp (the car is drawn 114 x 104 dp inside it); the two cards 145 dp tall at the bottom of the row
            val carHdp = if (l2Third) (l2RowH - l2CardsH).coerceIn(110, 162) else 162      // third-height window: the car view stops where the cards start
            (carView.layoutParams as LinearLayout.LayoutParams).apply { width = -1; height = dp(carHdp); weight = 0f; topMargin = 0 }
            (l2Mid.getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { width = -1; height = dp(l2CardsH); weight = 0f; topMargin = dp(if (l2Third) maxOf(l2RowH - carHdp - l2CardsH, 0) else maxOf(l2RowH - 162 - 145, 0)); bottomMargin = 0 }
            carView.requestLayout(); l2Mid.getChildAt(1).requestLayout()
        }
        dashGaugeRow.requestLayout()
        (dashGaugeRow.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            if (l2) { height = dp(l2RowH); setMargins(dp(8), dp(topM), dp(8), 0) }
            else if (tall) { height = dp(wantH.toInt()); setMargins(dp(8), dp(topM), dp(8), 0) }
            else { height = FrameLayout.LayoutParams.MATCH_PARENT; setMargins(dp(8), dp(topM - up), dp(8), dp(gaugeBottom + up)) }
        }
        // full screen and the 2/3 split (room of 200 dp or more under the cards): the battery bar and buttons sit 14 dp higher and the buttons are bigger
        val l2RoomPre = (hDp - lowerBottom - 4) - (topM + l2RowH + 8)
        val l2Big = l2 && !l2Third && l2RoomPre >= 200
        // 50/50 split: the battery bar a little higher, and Media / Climate under it on the same side
        val l2Half = l2 && !l2Third && l2RoomPre < 200
        if (l2) {
            val speedLeft = prefs().getBoolean("speedLeft", false)
            val rowBottomY = if (l2Third) topM + l2RowH - 12 else l2BarTop + 34 + 68 - (if (l2Big) 14 else if (l2Half) 12 else 0)      // battery bar and the Climate / Media buttons sit 68 dp lower
            (chargeBar.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(maxOf(gaugeWdp.toInt() - 8, 100)); height = dp(30)
                gravity = Gravity.TOP or (if (speedLeft) Gravity.END else Gravity.START)
                setMargins(if (speedLeft) 0 else dp(12), dp(maxOf(rowBottomY - 34, 0)), if (speedLeft) dp(12) else 0, 0)
            }
            // Climate and Media sit under the speed gauge, level with the battery bar under the power gauge
            val bw = maxOf((gaugeWdp.toInt() - 8 - 6) / 2, 60)
            val bg2 = Gravity.TOP or (if (speedLeft) Gravity.START else Gravity.END)
            val edge = dp(12)
            val by = dp(maxOf(rowBottomY - 34, 0))
            (mediaBtn.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(bw); height = dp(if (l2Big) 38 else 30); gravity = bg2; topMargin = by
                if (speedLeft) { leftMargin = edge; rightMargin = 0 } else { rightMargin = edge + dp(bw) + dp(6); leftMargin = 0 }
            }
            (climateBtn.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(bw); height = dp(if (l2Big) 38 else 30); gravity = bg2; topMargin = by
                if (speedLeft) { leftMargin = edge + dp(bw) + dp(6); rightMargin = 0 } else { rightMargin = edge; leftMargin = 0 }
            }
            mediaBtn.textSize = if (l2Big) 15f else 13f; climateBtn.textSize = mediaBtn.textSize
            l2BtnRowBottom = rowBottomY; l2BtnW = bw
            mediaBtn.requestLayout(); climateBtn.requestLayout()
            chargeBar.requestLayout()
        }
        // Layout 2: the Climate panel sits in the free space under the cards, when there is enough of it
        run {
            val top = topM + l2RowH + 8
            val bottom = hDp - lowerBottom - 4
            var room = bottom - top
            var climTop = top
            // full screen: the media card comes back between the cards and the Climate panel
            mediaCard.l2 = l2
            val wantMedia = l2 && room >= 470 && prefs().getBoolean("fullMedia", true)
            if (wantMedia) {
                (mediaCard.layoutParams as FrameLayout.LayoutParams).apply { height = dp(132); gravity = Gravity.BOTTOM; setMargins(dp(8), 0, dp(8), dp(maxOf(hDp - (top + 132), 0))) }
                mediaCard.rhd = prefs().getBoolean("rhd", false)
                mediaCard.visibility = View.VISIBLE
                mediaCard.requestLayout()
                climTop = top + 140; room -= 140
            } else if (l2) mediaCard.visibility = View.GONE
            if (l2) { ensureMediaWatcher(); mediaWatcher?.start() }   // the media pages need the player followed even when the old media card is off
            val climOn = prefs().getBoolean("l2Climate", true)
            if (::climateView.isInitialized) { climateView.l2 = l2; climateView.rhd = prefs().getBoolean("rhd", false) }
            val inline = l2 && climOn && room >= 110
            l2ClimTop = climTop; l2ClimH = room; l2ClimInline = inline
            l2FreeTop = top; l2FreeBottom = bottom; l2Room = bottom - top
            if (l2Third) { l2FreeTop = hDp - lowerBottom - 4; l2FreeBottom = hDp - 4 }   // third-height window: the media card covers the stat rows
            val mini = inline && room >= 200 && room < 300     // a 2/3 split: the full climate screen, drawn smaller
            climateView.mini = mini
            climateView.compact = inline && room < 300 && !mini
            // Media is on the swipe cards; Climate only needs its button when the panel is not already showing
            climateBtn.visibility = if (l2 && (inline || !climOn)) View.GONE else View.VISIBLE
            mediaBtn.visibility = if (l2) climateBtn.visibility else View.VISIBLE      // Layout 2: Media sits just beside Climate
            // optional: Media and Climate buttons under the driver's side gauge in the 50/50 and 2/3 splits, where the panel is already showing
            val splitBtns = l2 && !l2Third && prefs().getBoolean("l2SplitBtns", false) && (inline || l2Half)
            if (l2Half && !splitBtns) { climateBtn.visibility = View.GONE; mediaBtn.visibility = View.GONE }     // 50/50: the buttons only show when the setting is on
            if (!splitBtns) l2ClimHidden = false
            else {
                val rhdB = prefs().getBoolean("rhd", false); val slB = prefs().getBoolean("speedLeft", false)
                val hasBar = if (rhdB) slB else !slB          // the battery bar sits under the power gauge: then the buttons go in the gap under it
                if (l2Half) {
                    // 50/50: both buttons under the battery bar, on its side, same size and font
                    val barEnd = slB != rhdB      // the bar is at the end (right) when the speed gauge is on the left; right-hand drive puts the buttons on the other side
                    val bwH = l2BtnW; val edgeH = dp(12); val hH = dp(28)
                    val gvH = Gravity.TOP or (if (barEnd) Gravity.END else Gravity.START)
                    val topH = dp(l2BtnRowBottom - 2)
                    (mediaBtn.layoutParams as FrameLayout.LayoutParams).apply {
                        width = dp(bwH); height = hH; gravity = gvH; topMargin = topH
                        if (barEnd) { rightMargin = edgeH + dp(bwH) + dp(6); leftMargin = 0 } else { leftMargin = edgeH; rightMargin = 0 }
                    }
                    (climateBtn.layoutParams as FrameLayout.LayoutParams).apply {
                        width = dp(bwH); height = hH; gravity = gvH; topMargin = topH
                        if (barEnd) { rightMargin = edgeH; leftMargin = 0 } else { leftMargin = edgeH + dp(bwH) + dp(6); rightMargin = 0 }
                    }
                    mediaBtn.textSize = 13f; climateBtn.textSize = 13f
                    mediaBtn.setPadding(0, 0, 0, 0); climateBtn.setPadding(0, 0, 0, 0)
                    mediaBtn.visibility = View.VISIBLE; climateBtn.visibility = View.VISIBLE
                    mediaBtn.requestLayout(); climateBtn.requestLayout()
                } else {
                val bw2 = l2BtnW; val edge2 = dp(12)
                val topB = if (hasBar) dp(l2BtnRowBottom - 2) else dp(maxOf(l2BtnRowBottom - 34, 0)); val hB = dp(if (hasBar) (if (l2Big) 32 else 21) else (if (l2Big) 38 else 30))
                val gv = Gravity.TOP or (if (rhdB) Gravity.END else Gravity.START)
                (mediaBtn.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dp(bw2); height = hB; gravity = gv; topMargin = topB
                    if (rhdB) { rightMargin = edge2 + dp(bw2) + dp(6); leftMargin = 0 } else { leftMargin = edge2; rightMargin = 0 }
                }
                (climateBtn.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dp(bw2); height = hB; gravity = gv; topMargin = topB
                    if (rhdB) { rightMargin = edge2; leftMargin = 0 } else { leftMargin = edge2 + dp(bw2) + dp(6); rightMargin = 0 }
                }
                val ts = if (hasBar) (if (l2Big) 13f else 11f) else (if (l2Big) 15f else 13f)
                mediaBtn.textSize = ts; climateBtn.textSize = ts
                mediaBtn.visibility = View.VISIBLE; climateBtn.visibility = View.VISIBLE
                mediaBtn.requestLayout(); climateBtn.requestLayout()
                }
            }
            if (l2Third && climateBtn.visibility == View.VISIBLE) {
                // third-height window: Media / Climate under the battery bar, on its side, the same as in the 50/50 split
                val barEnd3 = prefs().getBoolean("speedLeft", false) != prefs().getBoolean("rhd", false)
                val bw3 = l2BtnW; val edge3 = dp(12); val gv3 = Gravity.TOP or (if (barEnd3) Gravity.END else Gravity.START)
                val top3 = dp(l2BtnRowBottom - 2)
                (mediaBtn.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dp(bw3); height = dp(26); gravity = gv3; topMargin = top3
                    if (barEnd3) { rightMargin = edge3 + dp(bw3) + dp(6); leftMargin = 0 } else { leftMargin = edge3; rightMargin = 0 }
                }
                (climateBtn.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dp(bw3); height = dp(26); gravity = gv3; topMargin = top3
                    if (barEnd3) { rightMargin = edge3; leftMargin = 0 } else { leftMargin = edge3 + dp(bw3) + dp(6); rightMargin = 0 }
                }
                mediaBtn.textSize = 12f; climateBtn.textSize = 12f
                mediaBtn.setPadding(0, 0, 0, 0); climateBtn.setPadding(0, 0, 0, 0)
                mediaBtn.requestLayout(); climateBtn.requestLayout()
            }
            // right-hand drive: the Media / Climate pair is mirrored too (Climate on the outside), after every placement above has been worked out
            if (prefs().getBoolean("rhd", false)) {
                val mp = mediaBtn.layoutParams as? FrameLayout.LayoutParams; val cp = climateBtn.layoutParams as? FrameLayout.LayoutParams
                if (mp != null && cp != null) {
                    val ml = mp.leftMargin; val mr = mp.rightMargin
                    mp.leftMargin = cp.leftMargin; mp.rightMargin = cp.rightMargin; cp.leftMargin = ml; cp.rightMargin = mr
                    mediaBtn.requestLayout(); climateBtn.requestLayout()
                }
            }
            l2MiniNow = mini && !l2Third
            if (l2MediaInPlace && !l2MiniNow) l2MediaInPlace = false
            if (l2MediaInPlace) {
                // 2/3 split: the Media button swapped the Climate panel for the media card, in the same space
                l2ClimHidden = true; climateView.visibility = View.GONE
                (mediaCard.layoutParams as FrameLayout.LayoutParams).apply { height = dp(minOf(room, 150)); gravity = Gravity.TOP; setMargins(dp(8), dp(climTop), dp(8), 0) }
                mediaCard.rhd = prefs().getBoolean("rhd", false)
                mediaCard.visibility = View.VISIBLE; mediaCard.bringToFront(); mediaCard.requestLayout()
            }
            if (inline) edgeBox.post { if (dashShown && l2Applied) { if (climateView.visibility == View.VISIBLE) placeClimate() else if (!l2ClimHidden && !l2ClimClosed) openClimate() } }
            else if (wasL2Clim) { climateView.visibility = View.GONE }   // no longer room for it under the cards
            wasL2Clim = inline
        }
        if (!l2) { mediaBtn.textSize = 12f; climateBtn.textSize = 12f }
        if (l2) {
            // the two buttons always use exactly the same type
            climateBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, mediaBtn.textSize)
            climateBtn.typeface = mediaBtn.typeface; climateBtn.letterSpacing = mediaBtn.letterSpacing; climateBtn.setIncludeFontPadding(mediaBtn.includeFontPadding)
        }
        if (!l1c && !l2) (dashLight.layoutParams as FrameLayout.LayoutParams).apply { width = dp(if (midLevel == 2) 22 else if (short) 26 else 42); height = dp(if (midLevel == 2) 46 else if (short) 56 else 88) }
        dashReady.textSize = if (midLevel == 2 || (midLevel == 1 && brakeUnder)) 11f else if (midLevel == 1) 12f else 15f
        dashReady.letterSpacing = if (midLevel == 1 && brakeUnder && !readyUnderPanel) 0.06f else 0.18f
        // in a short window the gauges reach the top corners, so the mode badge gets its own strip above them
        dashMode.textSize = if (short) 9f else 11f
        dashMode.minWidth = dp(if (short) 60 else 84)
        dashMode.setPadding(dp(if (short) 6 else 10), dp(if (short) 1 else 3), dp(if (short) 6 else 10), dp(if (short) 1 else 3))
        (dashTop.layoutParams as FrameLayout.LayoutParams).apply {
            val topAtRight = !rhd
            gravity = Gravity.TOP or (if (topAtRight) Gravity.END else Gravity.START)
            if (topAtRight) setMargins(0, dp(if (short) 2 else 6), dp(8), 0) else setMargins(dp(8), dp(if (short) 2 else 6), 0, 0)
        }
        dashTop.removeAllViews()
        if (rhd) {
            dashTop.addView(dashMode, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            dashTop.addView(dashHeading, LinearLayout.LayoutParams(-2, -2))
        } else {
            dashTop.addView(dashHeading, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            dashTop.addView(dashMode, LinearLayout.LayoutParams(-2, -2))
        }
        dashTop.requestLayout()
        dashMode.requestLayout()
        dashLightDist.textSize = if (short) 12f else 15f
        dashLightDist.visibility = if (midLevel == 2) View.GONE else View.VISIBLE
        dashHeading.visibility = View.VISIBLE
        dashHeading.textSize = if (short) 12f else 16f
        dashHeading.gravity = Gravity.CENTER_VERTICAL
        dashBrake.textSize = if (midLevel == 2) 11f else if (short) 13f else 16f
        (dashBrake.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = dp(if (brakeUnder) 1 else if (midLevel == 2) 3 else 8) }
        dashBrake.setPadding(dp(if (short) 10 else 18), dp(if (midLevel == 2) 1 else 4), dp(if (short) 10 else 18), dp(if (midLevel == 2) 1 else 4))
        if (l2) {
            // READY and the brake-light note sit one above the other at the top centre, clear of the car and the cards
            dashReady.textSize = 14f; dashReady.letterSpacing = 0.18f; dashReady.setPadding(0, 0, 0, 0)
            dashBrake.textSize = 12f; dashBrake.text = "Brake light on"; dashBrake.setPadding(dp(12), dp(2), dp(12), dp(2))
            (dashReady.layoutParams as? FrameLayout.LayoutParams)?.apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(2) }
            (dashBrake.layoutParams as? FrameLayout.LayoutParams)?.apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(28); leftMargin = 0 }
        }
        (dashSign.layoutParams as FrameLayout.LayoutParams).apply { val sz = if (l2) (wDp * 0.06f).toInt() else if (l1c) (wDp * 0.07f).toInt() else if (midLevel == 2) 38 else if (short) 48 else 64; width = dp(sz); height = dp(sz) }
        poolBar.requestLayout(); lowerBox.requestLayout()
        dashSignSrc.visibility = if (midLevel == 0 || l1c || l2) View.VISIBLE else View.GONE
        dashStrip.requestLayout(); dashRow.requestLayout(); dashGaugeRow.requestLayout(); dashLight.requestLayout(); dashSign.requestLayout()
    }

    /** Numbers printed on the numbered-scale skin, placed where the gauges' own scales put those values. */
    private fun speedScale(mph: Boolean): List<Pair<Float, String>> {
        val max = if (mph) 112.0 else 180.0
        return (if (mph) listOf(0, 20, 40, 60, 80, 100) else listOf(0, 40, 80, 120, 160))
            .map { Math.pow(it / max, 0.75).toFloat() to it.toString() }
    }
    private fun powerScale(): List<Pair<Float, String>> =
        listOf(-50 to "-50", 0 to "0", 50 to "50", 100 to "100", 200 to "200").map { (kw, t) ->
            (if (kw >= 0) 0.2 + 0.8 * Math.sqrt(kw / 250.0) else 0.2 - 0.2 * Math.sqrt(-kw / 80.0)).toFloat() to t
        }
    private var scaleMph: Boolean? = null

    /** Names on the four lower buttons: the chosen app, or a "+" while empty. */
    private fun refreshLowerLabels() {
        // every name is cut with "..." so the row of app buttons never runs into the Hide / Exit buttons on the right
        val un = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val ready = ::btnBar.isInitialized && ::tripBtn.isInitialized && ::climateBtn.isInitialized && ::mediaBtn.isInitialized && ::resBtn.isInitialized
        var per = dp(120)
        var avail = Int.MAX_VALUE
        if (ready) {
            var others = 0
            val addOn = ::lowerAdd.isInitialized && prefs().getBoolean("iconBtns", false) && slotCount < maxSlots
            if (::lowerAdd.isInitialized) lowerAdd.visibility = if (addOn) View.VISIBLE else View.GONE
            for (b in listOf(climateBtn, mediaBtn, resBtn, tripBtn)) if (b.visibility != View.GONE) { b.measure(un, un); others += b.measuredWidth + dp(6) }
            if (addOn) { lowerAdd.measure(un, un); others += lowerAdd.measuredWidth + dp(6) }
            btnBar.measure(un, un)
            val totalW = if (::dashRoot.isInitialized && dashRoot.width > 0) dashRoot.width else resources.displayMetrics.widthPixels
            val room = totalW - dp(8 + 20 + 16) - others - btnBar.measuredWidth - dp(6 + 6 * slotCount)
            per = (room / slotCount).coerceIn(dp(44), dp(120))
            avail = totalW - dp(8 + 20 + 16) - others - btnBar.measuredWidth - dp(6)   // the scrolling area ends before the Skin / Settings / Hide / Exit buttons
        }
        if (lowerIdx >= slotCount) lowerIdx = 0
        for (i in 0 until lowerTabs.childCount) {
            val v = lowerTabs.getChildAt(i) as? TextView ?: continue
            v.visibility = if (i < slotCount) View.VISIBLE else View.GONE
            val pkg = slotPkg(i)
            val icons = prefs().getBoolean("iconBtns", false) && pkg.isNotEmpty()
            val ic = if (icons) try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null } else null
            if (ic != null) {
                ic.setBounds(0, 0, dp(28), dp(28))
                v.text = ""
                v.setCompoundDrawables(ic, null, null, null)
                v.maxWidth = minOf(dp(56), per)
            } else {
                v.setCompoundDrawables(null, null, null, null)
                v.text = if (pkg.isEmpty()) "+" else appLabelOf(pkg)
                v.maxWidth = per
            }
            v.alpha = if (pkg.isEmpty()) 0.55f else 1f
        }
        if (::lowerScroll.isInitialized && avail != Int.MAX_VALUE) {
            lowerTabs.measure(un, un)
            val w = minOf(lowerTabs.measuredWidth + dp(16), avail).coerceAtLeast(dp(44))   // + the strip's own padding
            val lp = lowerScroll.layoutParams
            if (lp != null && lp.width != w) { lp.width = w; lowerScroll.layoutParams = lp }
        }
        if (l2Applied) refreshShortcuts()
    }

    /** Let the user choose which installed app a lower button opens (long press on a button changes it later). */
    private fun pickLowerApp(slot: Int, onPick: ((String) -> Unit)? = null) {
        Thread {
            val pm = packageManager
            val found = LinkedHashMap<String, Pair<String, android.graphics.drawable.Drawable?>>()
            for (cat in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
                val ris = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) } catch (e: Exception) { emptyList() }
                for (ri in ris) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == packageName || found.containsKey(pkg)) continue
                    found[pkg] = Pair(try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }, try { ri.loadIcon(pm) } catch (e: Exception) { null })
                }
            }
            val apps = found.entries.sortedBy { it.value.first.lowercase() }
            ui.post {
                if (isFinishing) return@post
                val list = ListView(this)
                var dlg: AlertDialog? = null
                list.adapter = object : BaseAdapter() {
                    override fun getCount() = apps.size
                    override fun getItem(position: Int) = apps[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                        val e = apps[position]
                        return LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), dp(6), dp(14), dp(6))
                            addView(ImageView(this@MainActivity).apply { setImageDrawable(e.value.second) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                            addView(TextView(this@MainActivity).apply { text = e.value.first; textSize = 16f; setTextColor(Color.parseColor("#F0F3F8")); setPadding(dp(14), 0, 0, 0) })
                        }
                    }
                }
                list.setOnItemClickListener { _, _, pos, _ ->
                    if (onPick != null) { onPick(apps[pos].key); dlg?.dismiss(); return@setOnItemClickListener }
                    prefs().edit().putString("slotPkg$slot", apps[pos].key).apply()
                    EventLog.add("lower button ${slot + 1} set to ${apps[pos].key}")
                    refreshLowerLabels(); dlg?.dismiss()
                }
                val b = darkDialog().setTitle("Choose the app for this button").setView(list).setNegativeButton("Cancel", null)
                if (onPick == null && slotPkg(slot).isNotEmpty()) b.setNeutralButton("Clear button") { _, _ ->
                    clearSlot(slot)
                }
                dlg = b.showDark()
            }
        }.start()
    }

    private fun paintLowerTabs() {
        val acc = Skins.look.accent
        val pale = (0.299 * Color.red(acc) + 0.587 * Color.green(acc) + 0.114 * Color.blue(acc)) / 255.0 > 0.62
        for (i in 0 until lowerTabs.childCount) {
            // with icons showing, the selected button gets no highlight: just the icon
            val sel = i == lowerIdx && !(prefs().getBoolean("iconBtns", false) && slotPkg(i).isNotEmpty())
            lowerTabs.getChildAt(i).background = if (sel) GradientDrawable().apply { setColor(acc); cornerRadius = dp(15).toFloat() } else null
            (lowerTabs.getChildAt(i) as? TextView)?.setTextColor(if (sel && pale) Color.parseColor("#10151C") else Color.WHITE)
        }
    }

    private fun chipBg(radiusDp: Int) = GradientDrawable().apply {
        val lk = Skins.look
        if (dashDay) { setColor(Color.parseColor("#98A1AD")); setStroke(dp(1), Color.argb(150, 255, 255, 255)) }   // Day mode: lighter grey buttons
        else { setColor(lk.chip); setStroke(dp(1), lk.chipStroke) }
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** Give the dashboard's buttons, tabs and bars the skin's colours. */
    private fun applySkinChrome() {
        val lk = Skins.look
        if (::dashBtn.isInitialized) dashBtn.background = chipBg(22)
        if (::skinBtn.isInitialized) skinBtn.background = chipBg(22)
        if (::presetsBtn.isInitialized) presetsBtn.background = chipBg(22)
        if (::settingsBtn.isInitialized) settingsBtn.background = chipBg(22)
        if (::exitBtn.isInitialized) exitBtn.background = chipBg(22)
        if (::resBtn.isInitialized) resBtn.background = chipBg(12)
        if (::l2ClimBtn.isInitialized) { l2ClimBtn.background = chipBg(12); l2MediaBtn.background = chipBg(12); l2LoadBtn.background = chipBg(12); l2SaveBtn.background = chipBg(12) }
        if (::lowerAdd.isInitialized) lowerAdd.background = chipBg(18)
        if (::climateBtn.isInitialized) climateBtn.background = chipBg(12)
        if (::mediaBtn.isInitialized) mediaBtn.background = chipBg(12)
        if (::tripBtn.isInitialized) tripBtn.background = chipBg(12)
        if (::lowerScroll.isInitialized) { lowerScroll.background = chipBg(18); paintLowerTabs() }
        if (::climateView.isInitialized) applyClimateColors()
        if (::poolBar.isInitialized) poolBar.background = GradientDrawable().apply {
            setColor(Color.argb(245, 10, 14, 28)); cornerRadius = dp(22).toFloat()
            setStroke(dp(2), Color.argb(230, Color.red(lk.accent), Color.green(lk.accent), Color.blue(lk.accent)))
        }
        if (::editBackdrop.isInitialized) editBackdrop.background = GradientDrawable().apply {
            setColor(Color.argb(225, 6, 9, 20)); cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(150, Color.red(lk.accent), Color.green(lk.accent), Color.blue(lk.accent)))
        }
        if (::dashStrip.isInitialized) dashStrip.invalidate()
        if (::dashRow.isInitialized) dashRow.invalidate()
    }

    // ---------------------------------------------------------------- updates

    /** The Update Available button shows beside the clock after "Update later". */
    private fun refreshUpdateBtn() {
        if (!::updateBtn.isInitialized) return
        val u = pendingUpdate
        val show = u != null && prefs().getInt("updDeferred", 0) == u.build && updateDialog?.isShowing != true
        updateBtn.visibility = if (show) View.VISIBLE else View.GONE
        if (show) placeUpdateBtn()
    }

    private fun placeUpdateBtn() {
        if (!::updateBtn.isInitialized || updateBtn.visibility != View.VISIBLE) return
        val lp = updateBtn.layoutParams as FrameLayout.LayoutParams
        val par = updateBtn.parent as? View
        val rhd = prefs().getBoolean("rhd", false)
        // under the kW gauge, centred on it (the gauge sits on the other side on right-hand drive, so the button follows it)
        if (par != null && ::dashPowerGauge.isInitialized && dashPowerGauge.isShown && dashPowerGauge.width > 0 && par.width > 0) {
            val gp = IntArray(2); val pp = IntArray(2)
            dashPowerGauge.getLocationInWindow(gp); par.getLocationInWindow(pp)
            val bw = if (updateBtn.width > 0) updateBtn.width else dp(130)
            val gcx = gp[0] - pp[0] + dashPowerGauge.width / 2f
            val gcy = gp[1] - pp[1] + dashPowerGauge.height / 2f
            val gr = minOf(dashPowerGauge.width, dashPowerGauge.height) / 2f
            val x = (gcx - bw / 2f).toInt().coerceIn(dp(4), (par.width - bw - dp(4)).coerceAtLeast(dp(4)))
            val y = (gcy + gr + dp(4)).toInt().coerceIn(dp(2), (par.height - dp(34)).coerceAtLeast(dp(2)))
            if (lp.gravity != (Gravity.TOP or Gravity.START) || lp.leftMargin != x || lp.topMargin != y || lp.rightMargin != 0) {
                lp.gravity = Gravity.TOP or Gravity.START; lp.leftMargin = x; lp.topMargin = y; lp.rightMargin = 0
                updateBtn.layoutParams = lp
            }
            return
        }
        // beside the word Ready, on the side away from the ADAS View button (which sits to the left of the road; right of it on right-hand drive)
        if (par != null && ::dashReady.isInitialized && dashReady.isShown && dashReady.width > 0 && par.width > 0) {
            val rp = IntArray(2); val pp = IntArray(2)
            dashReady.getLocationInWindow(rp); par.getLocationInWindow(pp)
            val txtW = dashReady.paint.measureText(dashReady.text.toString()).coerceAtMost(dashReady.width.toFloat())
            val cxr = rp[0] - pp[0] + dashReady.width / 2f
            val bw = if (updateBtn.width > 0) updateBtn.width else dp(130)
            val x = (if (rhd) cxr - txtW / 2f - dp(12) - bw else cxr + txtW / 2f + dp(12)).toInt().coerceIn(dp(4), (par.width - bw - dp(4)).coerceAtLeast(dp(4)))
            val y = (rp[1] - pp[1] + (dashReady.height - dp(30)) / 2).coerceAtLeast(dp(2))
            if (lp.gravity != (Gravity.TOP or Gravity.START) || lp.leftMargin != x || lp.topMargin != y || lp.rightMargin != 0) {
                lp.gravity = Gravity.TOP or Gravity.START; lp.leftMargin = x; lp.topMargin = y; lp.rightMargin = 0
                updateBtn.layoutParams = lp
            }
            return
        }
        // the Ready word is not showing (Layout 1 in some views): beside the clock, as before
        val clp = dashClock.layoutParams as FrameLayout.LayoutParams
        val clockOn = dashClock.visibility == View.VISIBLE
        val side = (if (rhd) clp.rightMargin else clp.leftMargin) + (if (clockOn) dashClock.textW.toInt() + dp(12) else dp(0))
        val top = clp.topMargin + (if (clockOn && clp.height >= dp(52)) dp(12) else 0)
        val grav = Gravity.TOP or (if (rhd) Gravity.END else Gravity.START)
        if (lp.gravity != grav || lp.topMargin != top || (if (rhd) lp.rightMargin else lp.leftMargin) != side) {
            lp.gravity = grav; lp.topMargin = top
            if (rhd) { lp.rightMargin = side; lp.leftMargin = 0 } else { lp.leftMargin = side; lp.rightMargin = 0 }
            updateBtn.layoutParams = lp
        }
    }

    /** Looks at the newest GitHub release; a newer build than this one brings up the update window (once per build). */
    private fun checkForUpdate(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && (now - lastUpdateCheck < 30 * 60 * 1000L || updateBusy)) return
        lastUpdateCheck = now
        Thread {
            val r = Updater.latest(prefs().getBoolean("betaOn", false), Updater.installedBuild(this), prefs().getString("devToken", "") ?: "")
            val have = Updater.installedBuild(this)
            ui.post {
                if (r != null && r.build > have) {
                    pendingUpdate = r
                    if (manual || prefs().getInt("updDeferred", 0) != r.build) showUpdateDialog(r) else refreshUpdateBtn()
                } else {
                    pendingUpdate = null; refreshUpdateBtn()
                    if (manual) toast(if (r == null) "Could not check for updates (is the internet connected?)" else "You have the latest version (build $have)")
                }
            }
        }.start()
    }

    private val installReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
            if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                updateFailed(i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed")
            }
        }
    }
    private var installReceiverOn = false

    private fun updateFailed(msg: String) {
        updateBusy = false
        EventLog.add("update failed: $msg")
        updPt?.text = "Update failed: $msg"
        updBar?.visibility = View.GONE
        updPos?.apply { isEnabled = true; text = "Try again" }
        updNeg?.isEnabled = true
        updateDialog?.setCancelable(true)
    }

    /** Settings > "Read all Release notes": every release's notes since the first build, newest first. */
    private fun showAllReleaseNotes() {
        val light = Color.parseColor("#F0F3F8")
        val tv = TextView(this).apply { text = "Loading\u2026"; textSize = 14f; setTextColor(light); setLineSpacing(0f, 1.1f) }
        val sv = ScrollView(this).apply { addView(tv); setPadding(dp(22), dp(10), dp(22), dp(4)) }
        darkDialog().setTitle("Release notes").setView(sv).setPositiveButton("Close", null).showDark()
        val beta = prefs().getBoolean("betaOn", false); val tok = prefs().getString("devToken", "") ?: ""
        Thread {
            val list = Updater.allNotes(beta, tok)
            runOnUiThread {
                tv.text = if (list == null) "Couldn't load the release notes. Check the internet connection and try again."
                    else if (list.isEmpty()) "No release notes yet."
                    else list.joinToString("\n\n") { (t, n) -> t + "\n" + n }
            }
        }.start()
    }

    private fun showUpdateDialog(r: Updater.Release) {
        if (updateDialog?.isShowing == true) return
        val light = Color.parseColor("#F0F3F8")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(6), dp(22), dp(4)) }
        body.addView(TextView(this).apply {
            text = "${r.title} is ready (you have build ${Updater.installedBuild(this@MainActivity)})."; textSize = 15f; setTextColor(light)
        })
        body.addView(TextView(this).apply { text = "RELEASE NOTES"; textSize = 11f; setTextColor(Color.parseColor("#9AA6B4")); letterSpacing = 0.1f; setPadding(0, dp(14), 0, dp(4)) })
        val sv = ScrollView(this)
        sv.addView(TextView(this).apply { text = r.notes; textSize = 14f; setTextColor(light); setLineSpacing(0f, 1.1f) })
        body.addView(sv, LinearLayout.LayoutParams(-1, dp(200)))
        val pt = TextView(this).apply { textSize = 13f; setTextColor(light); setPadding(0, dp(12), 0, dp(4)); visibility = View.GONE }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        body.addView(pt); body.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val d = darkDialog().setTitle("Update available").setView(body)
            .setPositiveButton("Update Now?", null).setNegativeButton("Update later", null).create()
        updateDialog = d; updPt = pt; updBar = bar
        d.setOnDismissListener {
            updateDialog = null
            if (!updateBusy) { prefs().edit().putInt("updDeferred", r.build).apply(); refreshUpdateBtn() }
        }
        d.show(); styleDark(d)
        updPos = d.getButton(AlertDialog.BUTTON_POSITIVE); updNeg = d.getButton(AlertDialog.BUTTON_NEGATIVE)
        updPos?.setOnClickListener {
            // the app cannot restart the Pulse itself, so it asks the user to do it after the install
            darkDialog().setTitle("Please Reboot your Pulse after Installing the Update")
                .setMessage("${r.title} installs now and the app restarts by itself. When it is done, please reboot your Pulse.")
                .setPositiveButton("OK, I will") { _, _ -> startUpdate(r, d, pt, bar) }
                .showDark()
        }
        updNeg?.setOnClickListener { d.dismiss() }     // "Update later": the dismiss listener leaves the button beside the clock
        refreshUpdateBtn()
    }

    private fun startUpdate(r: Updater.Release, d: AlertDialog, pt: TextView, bar: android.widget.ProgressBar) {
        updateBusy = true
        d.setCancelable(false)
        updPos?.apply { isEnabled = false; text = "Updating\u2026" }
        updNeg?.isEnabled = false
        pt.visibility = View.VISIBLE; bar.visibility = View.VISIBLE; bar.progress = 0; pt.text = "Downloading\u2026 0%"
        Thread {
            try {
                val dir = File(cacheDir, "update"); dir.deleteRecursively()
                val apk = File(dir, "FiskerBattery-build${r.build}.apk")
                Updater.download(r, apk) { p -> ui.post { bar.progress = p; pt.text = "Downloading\u2026 $p%" } }
                ui.post { pt.text = "Installing\u2026 the app restarts by itself and keeps your settings"; bar.isIndeterminate = true }
                if (Updater.rootAvailable()) {
                    prefs().edit().commit()
                    if (!Updater.installRoot(this, apk)) throw RuntimeException("could not start the installer")
                } else {
                    ui.post {
                        if (!installReceiverOn) {
                            val f = IntentFilter(Updater.ACTION_STATUS)
                            if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(installReceiver, f)
                            installReceiverOn = true
                        }
                    }
                    Thread.sleep(300)
                    Updater.installSession(this, apk)
                }
            } catch (e: Exception) { ui.post { bar.isIndeterminate = false; updateFailed(e.message ?: "unknown error") } }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Run a command as root; returns what it printed ("" if there is no root or it failed). */
    private fun rootRun(cmd: String): String = Root.exec(cmd).let { if (it.ok) it.out else "" }

    /** Null if this app has root, otherwise a sentence saying what is wrong. */
    private fun rootCheck(): String? = Root.check()

    private val appCache = HashMap<String, String>()

    /** Find an installed app by its name or package name (any app, launcher icon or not). */
    private fun findApp(key: String, words: List<String>): String? {
        appCache[key]?.let { return it }
        val pm = packageManager
        val apps = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList() }
        var best: String? = null
        for (ai in apps) {
            if (ai.packageName == packageName) continue
            val label = try { pm.getApplicationLabel(ai).toString().lowercase() } catch (e: Exception) { "" }
            val pkgL = ai.packageName.lowercase()
            if (words.any { label.contains(it) }) { best = ai.packageName; break }
            if (best == null && words.any { pkgL.contains(it) }) best = ai.packageName
        }
        best?.let { appCache[key] = it }
        return best
    }

    private fun findOceanscape(): String? = findApp("oceanscape", listOf("oceanscape"))
    private fun findPulse(): String? = findApp("pulse", listOf("pulse", "oceanlink"))

    /** Installed packages whose name or label mentions any of [words], for the "not found" message. */
    private fun candidates(words: List<String>): String {
        val pm = packageManager
        val apps = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList() }
        return apps.filter { ai ->
            val l = try { pm.getApplicationLabel(ai).toString().lowercase() } catch (e: Exception) { "" }
            words.any { l.contains(it) || ai.packageName.lowercase().contains(it) }
        }.joinToString(", ") { it.packageName }.ifEmpty { "none" }
    }

    /** The way to start [pkg]: its normal launcher entry, a TV/car style entry, or any main activity it has. */
    private fun launchIntentFor(pkg: String): Intent? {
        val pm = packageManager
        pm.getLaunchIntentForPackage(pkg)?.let { return it }
        pm.getLeanbackLaunchIntentForPackage(pkg)?.let { return it }
        val any = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0).firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN).setClassName(pkg, any.activityInfo.name)
    }

    /** Turn on the helper that can put this app into split screen (needs root once, to enable it). */
    private fun ensureSplitService(): Boolean {
        if (SplitService.instance != null) return true
        val me = "$packageName/${SplitService::class.java.name}"
        val cur = rootRun("settings get secure enabled_accessibility_services").trim()
        val others = if (cur.isEmpty() || cur == "null") emptyList() else cur.split(":").filter { it.isNotEmpty() && it != me }
        rootRun("settings put secure enabled_accessibility_services ${others.joinToString(":").ifEmpty { "null" }}")
        Thread.sleep(300)
        rootRun("settings put secure enabled_accessibility_services ${(others + me).joinToString(":")}; settings put secure accessibility_enabled 1")
        repeat(25) { if (SplitService.instance != null) { Thread.sleep(600); EventLog.add("split helper is running"); return true }; Thread.sleep(150) }
        EventLog.add("split helper did not start (accessibility service not connected)")
        return SplitService.instance != null
    }

    /** Open app number [i] (0 Maps, 1 Spotify, 2 Pulse, 3 Oceanscape) in the lower half. If this app is not in split screen yet, put it there first. */
    /** A tap on the media card: bring up the app that is playing the music in the lower half. */
    private fun openMusicApp(hidePopup: Boolean) {
        val pkg = mediaWatcher?.pkg
        if (pkg.isNullOrEmpty()) { toast("No music app is playing right now"); return }
        if (pkg.contains("bluetooth", true)) { toast("This music is coming over Bluetooth from the phone, so there is no app here to open"); return }
        if (hidePopup) { ui.removeCallbacks(hideMediaPopup); mediaPopup.visibility = View.GONE }
        val slot = (0 until slotCount).firstOrNull { slotPkg(it) == pkg }
        if (slot != null) { openLower(slot); return }
        pendingPkg = pkg
        openLower(-1)
    }

    private var pendingPkg = ""

    private fun openLower(i: Int) {
        if (i >= 0 && slotPkg(i).isEmpty()) { pickLowerApp(i); return }
        if (isInMultiWindowMode) { launchLower(i); return }
        EventLog.add("starting split screen for ${if (i >= 0) slotName(i) else appLabelOf(pendingPkg)}")
        Toast.makeText(this, "Switching to split screen\u2026", Toast.LENGTH_SHORT).show()
        val myTask = taskId
        Thread {
            val bad = rootCheck()
            if (bad != null && SplitService.instance == null) { ui.post { toast("$bad Or use the Pulse's own split-screen controls.") }; return@Thread }
            // Android 12L and later: the system UI's shell can put a task into split screen. Position 0 = top.
            val out = if (bad == null) rootRun("dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage $myTask 0") else ""
            splitOut = out.trim().take(300)
            EventLog.add("split shell command (task $myTask, Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}): ${(if (bad == null) out.trim().replace("\n", " | ") else "no root: $bad").take(700).ifEmpty { "no output" }}")
            ui.post { waitForSplit(i, 0, false) }
        }.start()
    }

    private var splitOut = ""

    /** When split screen fails, write what this device is and what its system UI says to the event log, so it can be diagnosed from the log alone. */
    private fun logSplitDiag() {
        val myTask = taskId
        Thread {
            try {
                EventLog.add("split diag: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), task $myTask, helper running=${SplitService.instance != null}")
                val bad = rootCheck()
                EventLog.add("split diag: root ${if (bad == null) "ok" else "not available: $bad"}")
                if (bad == null) {
                    EventLog.add("split diag: WMShell says: " + rootRun("dumpsys activity service SystemUIService WMShell 2>&1 | head -c 700").trim().replace("\n", " | ").ifEmpty { "nothing" })
                    EventLog.add("split diag: windowing mode of this task: " + rootRun("am task get-windowing-mode $myTask 2>&1 | head -c 120").trim().replace("\n", " ").ifEmpty { "nothing" })
                    EventLog.add("split diag: accessibility services: " + rootRun("settings get secure enabled_accessibility_services").trim())
                    EventLog.add("split diag: split-screen support: " + rootRun("getprop persist.wm.debug.enable_split_screen; getprop ro.config.low_ram; dumpsys activity | grep -i -m3 'multi.window\\|split' ").trim().replace("\n", " | ").take(300))
                }
            } catch (e: Throwable) { EventLog.add("split diag failed: ${e.message}") }
        }.start()
    }

    private fun waitForSplit(i: Int, tries: Int, second: Boolean) {
        if (isInMultiWindowMode) { EventLog.add("split screen is on"); ui.postDelayed({ launchLower(i) }, 500); return }
        if (tries >= 15) {
            if (!second) {
                // the shell command did not do it: try the accessibility action
                EventLog.add("shell command did not split; trying the accessibility action")
                Thread {
                    val ok = ensureSplitService()
                    ui.post {
                        if (!ok) { EventLog.add("split screen did not start"); logSplitDiag(); toast("Could not start split screen. Use the Pulse's split-screen controls (this app on top), then tap again. Shell said: ${splitOut.ifEmpty { "nothing" }}"); return@post }
                        val r = SplitService.instance?.splitScreen()
                        EventLog.add("split screen action returned $r")
                        waitForSplit(i, 0, true)
                    }
                }.start()
            } else {
                EventLog.add("split screen did not start (multi-window=$isInMultiWindowMode)")
                logSplitDiag()
                toast("Split screen did not start. Use the Pulse's split-screen controls, then tap again. Shell said: ${splitOut.ifEmpty { "nothing" }}")
            }
            return
        }
        ui.postDelayed({ waitForSplit(i, tries + 1, second) }, 200)
    }

    private fun launchLower(i: Int) {
        val pkg = if (i >= 0) slotPkg(i) else pendingPkg
        if (pkg.isEmpty()) { if (i >= 0) pickLowerApp(i); return }
        val name = if (i >= 0) slotName(i) else appLabelOf(pkg)
        if (try { packageManager.getApplicationInfo(pkg, 0); false } catch (e: Exception) { true }) { toast("$name is not installed on this device. Long press the button to choose another app."); return }
        EventLog.add("launching $name ($pkg)")
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        val tries = mutableListOf<Intent>()
        launchIntentFor(pkg)?.let { tries.add(it) }
        tries.add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg))
        for (t in tries) {
            try {
                t.addFlags(flags); startActivity(t)
                if (i >= 0) { lowerIdx = i; prefs().edit().putInt("lowerApp", i).apply() }
                paintLowerTabs()
                return
            } catch (e: Exception) { /* try the next one */ }
        }
        // last resort: start it through root (it comes up as a normal window)
        Thread { rootRun("monkey -p $pkg -c android.intent.category.LAUNCHER 1") }.start()
        if (i >= 0) { lowerIdx = i; prefs().edit().putInt("lowerApp", i).apply() }
        paintLowerTabs()
        toast("Starting $name through root")
    }

    /** Read the screen size (wm size) and show it on the button. */
    private fun refreshScreenSize() {
        Thread {
            val out = rootRun("wm size")
            val m = Regex("Override size: *(\\d+)x(\\d+)").find(out) ?: Regex("Physical size: *(\\d+)x(\\d+)").find(out)
            val h = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
            runOnUiThread { screenH = h; updateResText() }
        }.start()
    }

    /** Switch between the full 1080x1920 and the 1080x1784 screen. */
    private fun toggleScreenSize() {
        val target = if (screenH == 1784) 1920 else 1784
        EventLog.add("screen size -> 1080x$target")
        Thread {
            val bad = rootCheck()
            if (bad != null) { ui.post { toast(bad) }; return@Thread }
            val out = rootRun("wm size 1080x$target")
            refreshScreenSize()
            if (out.isNotBlank()) ui.post { toast("Screen size: ${out.trim().take(120)}") }
        }.start()
    }

    private var pulseAsked = false

    /** Is the Pulse app running? Needs root (nothing else can see another app's process). */
    private fun pulseRunning(pkg: String): Boolean {
        val pid = rootRun("pidof $pkg").trim()
        if (pid.isNotEmpty() && pid.matches(Regex("[0-9 ]+"))) return true
        return rootRun("ps -A -o NAME | grep -x $pkg").trim() == pkg
    }

    /** With the Pulse CAN source, the Pulse app also reads the CAN bus; offer to close it so there is less traffic. */
    private fun offerClosePulse() {
        if (!useCan || pulseAsked || !prefs().getBoolean("askClosePulse", false)) return
        val pkg = findPulse() ?: return
        Thread {
            if (Root.check() != null || !pulseRunning(pkg)) return@Thread
            ui.post {
                if (isFinishing || pulseAsked) return@post
                pulseAsked = true
                darkDialog().setTitle("Close the Pulse app?")
                    .setMessage("When using this app, it is recommended to close the Pulse app to minimize CAN bus traffic.\n\nClose the Pulse app now?")
                    .setPositiveButton("Close Pulse app") { _, _ ->
                        Thread {
                            rootRun("am force-stop $pkg")
                            EventLog.add("closed the Pulse app ($pkg)")
                            ui.post { Toast.makeText(this, "Pulse app closed", Toast.LENGTH_SHORT).show() }
                        }.start()
                    }
                    .setNegativeButton("Not now", null).showDark()
            }
        }.start()
    }

    private fun showDashboard(on: Boolean) {
        if (!on) setAdas(false, false)
        dashShown = on
        paintEdges()
        ui.removeCallbacks(dashTick)
        dashRoot.visibility = if (on) View.VISIBLE else View.GONE
        rootScroll.visibility = if (on) View.GONE else View.VISIBLE
        dashBtn.text = if (on) "Hide" else "Show Dashboard"
        skinBtn.visibility = if (on) View.VISIBLE else View.GONE
        presetsBtn.visibility = if (on) View.VISIBLE else View.GONE
        settingsBtn.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            actionBar?.hide()
            @Suppress("DEPRECATION")
            // the Pulse keeps its own status bar showing whatever an app asks, so the app must not extend under it
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            updateDashTheme(true)
            refreshScreenSize()
            offerClosePulse()
            checkForUpdate(false)
            // full screen: the Climate panel comes back with the dashboard
            if (fullOrderNow && prefs().getBoolean("l1Climate", true)) edgeBox.post { if (dashShown && climateView.visibility != View.VISIBLE) openClimate() }
            if (!introDone) {
                introDone = true
                ui.postDelayed({ dashPowerGauge.intro(); dashSpeedGauge.intro() }, 400)
            }
            ui.post(dashTick)
        } else {
            actionBar?.show()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            // hiding the dashboard closes the Climate screen and the media card with it
            if (::climateView.isInitialized) climateView.visibility = View.GONE
            if (::mediaPopup.isInitialized) mediaPopup.visibility = View.GONE
        }
    }

    private fun renderDash() {
        val s = canSnap
        val mph = speedMph()
        // the speedometer adjustment (Settings) is added to the speed shown, in the unit chosen; a stopped car stays at 0
        val spd = s.speedKmh?.let { k -> (if (mph) k * 0.621371 else k).let { v -> if (v >= 1.0) maxOf(0.0, v + speedAdjFor(v, mph)) else v } }
        if (scaleMph != mph) { scaleMph = mph; dashSpeedGauge.scale = speedScale(mph); dashPowerGauge.scale = powerScale() }
        dashSpeedGauge.valueText = spd?.let { String.format("%.0f", it) } ?: "--"
        dashSpeedGauge.unit = if (mph) "mph" else "km/h"
        if (::adasSpdNum.isInitialized) { adasSpdNum.text = dashSpeedGauge.valueText; adasSpdTitle.text = "SPEED  " + dashSpeedGauge.unit }
        dashSpeedGauge.setFraction(spd?.let { Math.pow((it / (if (mph) 112.0 else 180.0)).coerceIn(0.0, 1.0), 0.75).toFloat() })
        dashSpeedGauge.topValue = s.avgTempC?.let { fmt(it, "\u00B0C", 0) } ?: "--"
        // high-voltage battery thermal state, from the BMS: heating orange, cooling blue
        when (s.thermalDemand) {
            1 -> { dashSpeedGauge.bottomValue = "Heating"; dashSpeedGauge.bottomColor = Color.parseColor("#FF8A1F") }
            2 -> { dashSpeedGauge.bottomValue = "Cooling"; dashSpeedGauge.bottomColor = Color.parseColor("#2AA8FF") }
            3 -> { dashSpeedGauge.bottomValue = "Idle"; dashSpeedGauge.bottomColor = null }
            0 -> { dashSpeedGauge.bottomValue = "Starting"; dashSpeedGauge.bottomColor = null }
            else -> { dashSpeedGauge.bottomValue = "--"; dashSpeedGauge.bottomColor = null }
        }
        dashSpeedGauge.hold = s.hold == true || s.holdEsp == true
        if (s.ready != true) drivenSinceReady = false
        else if ((s.speedKmh ?: 0.0) > 2.0) drivenSinceReady = true
        dashReady.visibility = if (s.ready == true && !drivenSinceReady) View.VISIBLE else View.INVISIBLE
        dashSpeedGauge.refresh()

        val kw = s.packKw?.let { it * (if (prefs().getBoolean("flipPower", false)) -1 else 1) }
        dashPowerGauge.valueText = kw?.let { String.format("%.1f", it) } ?: "--"
        if (::adasPwrNum.isInitialized) { adasPwrNum.text = dashPowerGauge.valueText; adasPwrTitle.text = "POWER  kW" }
        dashPowerGauge.setFraction(kw?.let {
            // square-root scale so everyday power fills a good part of the arc; zero sits at 20%
            if (it >= 0) (0.2 + 0.8 * Math.sqrt((it / 250.0).coerceIn(0.0, 1.0))).toFloat()
            else (0.2 - 0.2 * Math.sqrt((-it / 80.0).coerceIn(0.0, 1.0))).toFloat()
        })
        dashPowerGauge.topValue = s.climateKw?.let { String.format("%.1f kW", it) } ?: "--"
        dashPowerGauge.bottomValue = s.socUsable?.let { "$it %" } ?: "--"
        chargeBar.frac = s.socUsable?.let { it / 100f }
        if (dashClock.visibility == View.VISIBLE) dashClock.tick()
        placeUpdateBtn()
        checkForUpdate(false)
        dashPowerGauge.refresh()

        dashBrake.visibility = if ((s.brakeBcm ?: s.brakeVcu ?: false) && !prefs().getBoolean(if (l2Applied) "hideBrakeNote" else "hideBrakeNote1", false)) View.VISIBLE else View.INVISIBLE
        updateDashTheme()
        fun t(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 0)
        val nowMs = System.currentTimeMillis()
        // speed limit sign: shown in the dashboard's own speed unit; fades in and out with the car's knowledge of the limit
        val lim = if (signTest) 50 else if (prefs().getBoolean("hideSign", false)) null else s.limit
        if (lim != null) {
            val mphWanted = speedMph()
            val shownLim = when {
                signTest || s.limitMph == mphWanted -> lim
                mphWanted -> Math.round(lim * 0.621371 / 5.0).toInt() * 5     // km/h -> mph, to the nearest 5
                else -> Math.round(lim * 1.609344 / 5.0).toInt() * 5          // mph -> km/h, to the nearest 5
            }
            signText = shownLim.toString()
            signSeenAt = nowMs
            dashSign.number = signText
            dashSign.metric = !mphWanted
            dashSign.euro = prefs().getString("signStyle", "us") == "eu"
            dashSign.invalidate()
            val tm = if (signTest) 2 else (s.tsrMode ?: 0)
            dashSignSrc.text = when (tm) { 2 -> "Camera + Map"; 3 -> "Camera"; 4 -> "Map"; 5 -> "sign data error"; else -> "" }
            dashSignSrc.setTextColor(if (dashDay) Color.argb(200, 20, 32, 46) else Color.argb(200, 255, 255, 255))
        }
        val lc = if (signTest) 1 + ((nowMs / 3000L) % 3L).toInt() else if (prefs().getBoolean("hideLight", false)) null else s.lightColor
        if (lc != null) { lightColor = lc; lightSeenAt = nowMs; dashLight.setColor(lc) }
        val seen = lc != null || nowMs - lightSeenAt < 1500   // brief hold so one missed frame does not flicker
        val lightEnded = lightShown && !seen
        if (seen != lightShown) {
            lightShown = seen
            val to = if (seen) 1f else 0f
            dashLight.animate().alpha(to).setDuration(if (seen) 200L else 700L).start()
            dashLightDist.animate().alpha(to).setDuration(if (seen) 200L else 700L).start()
        }
        // the speed sign gives way to the traffic light (same spot), and fades back in once the light has gone
        val wantSign = (lim != null || nowMs - signSeenAt < 1500) && (!seen || signTest)
        if (wantSign != signShown) {
            signShown = wantSign
            dashSignSrc.animate().alpha(if (wantSign) 1f else 0f).setDuration(if (wantSign) 300L else 250L).start()
            dashSign.animate().alpha(if (wantSign) 1f else 0f)
                .setStartDelay(if (wantSign && lightEnded) 700L else 0L)
                .setDuration(if (wantSign) 300L else 250L).start()
        }
        if (readyBeside && !l2Applied) {
            val rlp = dashReady.layoutParams as? FrameLayout.LayoutParams
            val g = if (!wantSign && !seen) Gravity.CENTER else (Gravity.CENTER_VERTICAL or Gravity.START)
            if (rlp != null && rlp.gravity != g) { rlp.gravity = g; rlp.leftMargin = if (g == Gravity.CENTER) 0 else dp(4); dashReady.layoutParams = rlp }
        }
        dashLightDist.text = if (signTest) "120 m" else if (lc == null) "" else s.lightDistM?.let { "$it m" } ?: ""
        // In Park the car keeps sending Earth: hold the last real mode (saved, so a restart keeps it) until a different mode comes through
        val dm = run {
            val raw = s.driveMode
            if (s.gear == 1 && (raw == null || raw == 0)) prefs().getInt("lastDriveMode", -1).takeIf { it in 0..2 }
            else { if (raw != null && prefs().getInt("lastDriveMode", -1) != raw) prefs().edit().putInt("lastDriveMode", raw).apply(); raw }
        }
        if (dm == null) dashMode.visibility = View.INVISIBLE else {
            val (name, col) = when (dm) {
                0 -> "EARTH" to "#4FC3F7"
                1 -> "FUN" to "#8E5CFF"
                else -> "HYPER" to "#FF8A1F"
            }
            dashMode.text = name
            dashMode.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor(col)) }
            dashMode.visibility = View.VISIBLE
        }
        val hd = s.headingDeg ?: gpsHeadingNow()      // the car's heading, or the Pulse's GPS heading when the car sends none
        if (l2Applied || l1cApplied) {
            carView.speedKmh = (s.speedKmh ?: 0.0).toFloat(); roadView.speedKmh = carView.speedKmh
            // the bend of the road: the car's lane camera when it sees our lane (radius R: 45 m ahead turns the road by 45 / R rad), else the compass / GPS heading
            roadView.curveOn = prefs().getBoolean("roadCurve", true)
            roadView.hideRoad = prefs().getBoolean("hideRoad", false)
            roadView.linesMode = when (prefs().getString("roadLines", if (prefs().getBoolean("fadeLines", false)) "fade" else "show")) { "fade" -> 1; "hide" -> 2; else -> 0 }
            roadView.laneDeg = run {
                val rl = s.laneRadiusM
                if (!prefs().getBoolean("curveLane", true) || rl == null || rl == 0.0 || System.currentTimeMillis() - s.laneAt > 1000) null
                else (-(45.0 / rl) * 180.0 / Math.PI).toFloat()      // R > 0 = curving left = a negative (anticlockwise) bend
            }
            roadView.noteHeading(hd)
            run {
                val alerts = prefs().getBoolean("l2Alerts", true); val nowMs = System.currentTimeMillis()
                val showObj = prefs().getBoolean("showObjects", alerts)       // ADAS options: the car in front and the other objects the camera sees
                val bFresh = alerts && nowMs - s.bsdAt < 2500
                carView.bsdL = if (bFresh && s.bsdLeft in 1..3) s.bsdLeft else 0; carView.bsdR = if (bFresh && s.bsdRight in 1..3) s.bsdRight else 0
                carView.dowL = if (bFresh && s.dowLeft in 1..4) s.dowLeft else 0; carView.dowR = if (bFresh && s.dowRight in 1..4) s.dowRight else 0
                carView.uss = if (alerts && s.uss != null && nowMs - s.ussAt < 3000) s.uss.toIntArray() else null
                carView.hatchPct = if (alerts && s.hatchOpen != null && nowMs - s.hatchAt < 3000) (if ((s.hatchPos ?: 0) > 0) s.hatchPos!!.coerceAtMost(100) else if (s.hatchOpen == true) 4 else 0) else -1
                carView.doorBits = if (alerts && s.doorBits != null && nowMs - s.doorAt < 3000) s.doorBits else -1
                val ld = s.lead
                if (showObj && ld != null && nowMs - s.leadAt < 1500) { roadView.setLead(ld.gapM.toFloat(), ld.latM.toFloat(), ld.widthM.toFloat(), ld.cls, ld.brake, ld.src); leadHoldUntil = nowMs + 4000L } else roadView.clearLead()
                if (showObj && !prefs().getBoolean("onlyLead", false) && nowMs - s.leadAt < 1500) roadView.setObjects(s.others) else roadView.setObjects(emptyList())
                carView.holdTilt = nowMs < leadHoldUntil      // stopped behind a car: keep the road and the rear view (4 s after it is lost, so a flicker does not tip us over)
            }; carView.braking = (s.brakeBcm ?: s.brakeVcu ?: false); carView.headlights = (s.headlights == true); carView.beamsOn = (s.beamsOn == true); carView.highBeam = (s.highBeamOn == true); carView.leftInd = s.leftInd; carView.rightInd = s.rightInd; carView.redInd = prefs().getString("signStyle", "us") != "eu"
            car3d?.red = carView.redInd       // USA / Canada: the turn signals glow red, as the rear lamps do on those cars
            car3d?.follow = prefs().getBoolean(if (l2Applied) "l2CarFollow" else "l1CarFollow", true)
            if (viewerLayer != null && carView.speedKmh > 3f) closeCarViewer()      // driving off closes the viewer
            trackSolar(s); solarUpdate?.invoke(s)
            viewer3d?.let { it.red = carView.redInd; feed3d(it, s) }
            car3d?.let { feed3d(it, s) }
            if (l2Applied) {
            fun shortLabel(l: String) = when (l.lowercase()) { "distance driven" -> "Distance"; "energy consumed" -> "Energy"; "average speed" -> "Avg speed"; else -> l }
            val cells = tripCells(s, true).take(4).map { it.copy(label = shortLabel(it.label)) }
            for (ps in pageSets) {
                ps.compass.heading = hd; ps.compass.day = dashDay; ps.trip.day = dashDay; ps.trip.setCells(cells); ps.nav.day = dashDay; ps.actions.day = dashDay
                for (v in ps.shorts) v.day = dashDay
            }
            leftPager.day = dashDay; rightPager.day = dashDay
            }
        }
        val hadHeading = dashHeading.text.isNotEmpty()
        dashHeading.text = if (hd == null) "" else {
            val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            "${names[((hd + 22.5) / 45.0).toInt() % 8]}  ${hd.toInt()}\u00B0"
        }
        if (hadHeading != dashHeading.text.isNotEmpty()) placeL2Clock()
        val demand = when (s.thermalDemand) { 0 -> "starting up"; 1 -> "heating"; 2 -> "cooling"; 3 -> "cycling"; else -> "--" }
        fun tc(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 0)
        pulse?.flipPower = prefs().getBoolean("flipPower", false)
        val rangeText = s.rangeKm?.let { if (speedMph()) String.format("%.0f mi", it * 0.621371) else String.format("%.0f km", it) } ?: "--"
        val everything = canStats(s)
        dashStrip.setItems(everything)
        dashRow.setItems(everything)
        if (tripPanel.visibility == View.VISIBLE || tripDlgView != null) {
            if (l1cApplied) {
                val short = listOf("EFF", "DIST", "AVG", "kWh")
                tripPanel.setCells(tripCells(s, true).take(4).mapIndexed { i, c -> c.copy(label = short[i], unit = if (i == 3) "" else c.unit) })
            } else tripPanel.setCells(tripCells(s, true).take(if (tripMidRows >= 3) 6 else 4))
            tripDlgView?.setCells(tripCells(s))
        }
    }

    private fun fmtDur(secs: Double): String {
        val t = secs.toLong().coerceAtLeast(0)
        return if (t >= 3600) String.format("%dh %02dm", t / 3600, (t % 3600) / 60)
        else if (t >= 60) String.format("%dm %02ds", t / 60, t % 60)
        else "${t}s"
    }

    /** The trip readouts: counted by this app (distance, time, energy) and the car's own figures. */
    private fun tripCells(s: CanSnapshot, compact: Boolean = false): List<TripCell> {
        val mph = speedMph()
        val dU = if (mph) "mi" else "km"
        fun dist(km: Double?): String = km?.let { String.format("%.1f", if (mph) it * 0.621371 else it) } ?: "--"
        fun dur(secs: Double): String {
            val t = secs.toLong().coerceAtLeast(0)
            return if (t >= 3600) String.format("%dh%02dm", t / 3600, (t % 3600) / 60) else if (t >= 60) String.format("%dm%02ds", t / 60, t % 60) else "${t}s"
        }
        val km = s.tripKm
        val elapsed = if (s.tripStartMs == 0L) null else (System.currentTimeMillis() - s.tripStartMs) / 1000.0
        val avgSpeed = if (s.tripMoveSecs >= 30.0) km / (s.tripMoveSecs / 3600.0) else null
        val effUnit = when (effMode()) { "perkwh" -> "$dU/kWh"; "kwhper" -> "kWh/$dU"; else -> "Wh/$dU" }
        return listOf(
            TripCell(5, "Efficiency", effText(avgEffKwh100(s)), effUnit, effTint(avgEffKwh100(s))),
            TripCell(0, "Distance Driven", dist(km), dU),
            TripCell(3, "Average Speed", avgSpeed?.let { String.format("%.0f", if (mph) it * 0.621371 else it) } ?: "--", if (mph) "mph" else "km/h"),
            TripCell(4, "Energy Consumed", String.format("%.1f", s.tripKwh), "kWh"),
            TripCell(1, "Elapsed Time", elapsed?.let { dur(it) } ?: "--"),
            TripCell(7, "Regen", String.format("%.2f", s.regenKwh), "kWh"),
        )
    }

    // ---- efficiency units: Wh per distance, distance per kWh, or kWh per distance (km or miles follow the speed unit)
    private fun effMode(): String = prefs().getString("effUnit", "wh") ?: "wh"

    private fun effUnitLabel(): String {
        val d = if (speedMph()) "MI" else "KM"
        return when (effMode()) { "perkwh" -> "$d/KWH"; "kwhper" -> "KWH/$d"; else -> "WH/$d" }
    }

    /** [k] is kWh per 100 km, which is how the trip is worked out; converted to the chosen unit. */
    private fun effText(k: Double?): String {
        if (k == null) return "--"
        val mph = speedMph()
        return when (effMode()) {
            "perkwh" -> if (k <= 0.5) "--" else String.format("%.1f", (if (mph) 62.1371 else 100.0) / k)
            "kwhper" -> String.format("%.3f", if (mph) k * 0.01609344 else k / 100.0)
            else -> String.format("%.0f", if (mph) k * 16.09344 else k * 10.0)
        }
    }

    /** Colour for the efficiency readout from Wh/km: dark green up to 100, light green at 200, yellow at 250, orange at 300, red at 400 and above. */
    private fun effTint(k: Double?): Int? {
        if (k == null) return null
        val wh = k * 10.0
        val pos = doubleArrayOf(0.0, 100.0, 200.0, 250.0, 300.0, 400.0)
        val col = intArrayOf(Color.parseColor("#14803F"), Color.parseColor("#22A455"), Color.parseColor("#A6E36B"), Color.parseColor("#F2D338"), Color.parseColor("#FF9F1C"), Color.parseColor("#FF3B4E"))
        if (wh <= pos[0]) return col[0]
        for (i in 1 until pos.size) if (wh <= pos[i]) {
            val t = ((wh - pos[i - 1]) / (pos[i] - pos[i - 1])).toFloat()
            val a = col[i - 1]; val b = col[i]
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
        }
        return col.last()
    }

    private fun avgEffKwh100(s: CanSnapshot): Double? = if (s.tripKm >= 0.3) s.tripKwh / s.tripKm * 100.0 else null

    /** The trip readouts in a pop-up, for windows too small to show the panel. */
    private fun showTripDialog() {
        val v = TripPanelView(this).apply { cols = 2; day = false }
        v.setCells(tripCells(canSnap))
        tripDlgView = v
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5 * 56)))
        val d = darkDialog().setTitle("Trip").setView(col)
            .setNeutralButton("Reset trip", null).setNegativeButton("Close", null).create()
        d.setOnDismissListener { tripDlgView = null }
        d.show()
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            pulse?.resetDrive(); Toast.makeText(this, "Trip reset", Toast.LENGTH_SHORT).show()
        }
        styleDark(d)
    }

    private fun applySource(pulseMode: Boolean) {
        useCan = pulseMode
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("source", if (pulseMode) "pulse" else "dongle").apply()
        dongleCard.visibility = if (pulseMode) View.GONE else View.VISIBLE
        advCard.visibility = if (pulseMode) View.GONE else View.VISIBLE
        readAllBtn.visibility = if (pulseMode) View.GONE else View.VISIBLE
        pollBtn.visibility = if (pulseMode) View.GONE else View.VISIBLE
        canCard.visibility = if (pulseMode) View.VISIBLE else View.GONE
        lastTempC = null
        renderLast = null
        statKey = ""
        renderStats(if (pulseMode) emptyMap() else listOf("Battery Temp", "Pack Volts", "Pack Amps", "Charge", "Cell V max", "Cell V min", "12V Battery").associateWith { "--" })
        showTemp(null, useFahrenheit)
        resultView.text = ""
        ui.removeCallbacks(canTick)
        if (pulseMode) {
            close()                                   // let go of the dongle
            val p = pulse ?: PulseCan(this, { m -> log(m) }, { snap -> canSnap = snap }).also { pulse = it; it.flipPower = prefs().getBoolean("flipPower", false) }
            p.safeMode = prefs().getBoolean("canSafe", false)
            p.start()
            Thread { rootCheck()?.let { w -> ui.post { toast(w) } } }.start()
            ui.post(canTick)
        } else {
            pulse?.stop()
            ensurePermissions()
        }
    }

    private fun setConnected(on: Boolean) {
        connected = on
        if (::dotView.isInitialized) runOnUiThread { paintDot(on) }
    }

    @Volatile private var uiPong = 0L

    /** Every 30 s, from its own thread (so it keeps going if the screen thread hangs): load, memory, and whether the screen thread answers. */
    private var heartbeat: Thread? = null
    private fun startHeartbeat() {
        heartbeat = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(30_000) } catch (e: InterruptedException) { return@Thread }
                val sent = System.currentTimeMillis()
                ui.post { uiPong = System.currentTimeMillis() }
                try { Thread.sleep(2_000) } catch (e: InterruptedException) { return@Thread }
                val answered = uiPong >= sent
                val n = pulse?.frames ?: 0L
                EventLog.add("alive up=" + uptimeSecs() + "s " + EventLog.system() + " frames=$n ui=" + (if (answered) "ok" else "NOT ANSWERING") + " " + CanDiag.quick())
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun uptimeSecs(): String = try { java.io.File("/proc/uptime").readText().trim().split(" ")[0].toDouble().toInt().toString() } catch (e: Exception) { "?" }

    /** At start: how long the Pulse has been up and what Android and the kernel kept about the last crash or restart (needs root). */
    private fun logBootInfo() {
        Thread {
            try {
                EventLog.add("boot: Pulse up for " + uptimeSecs() + " s")
                val r = Root.exec("getprop sys.boot.reason; getprop ro.boot.bootreason; echo '--pstore'; ls -l /sys/fs/pstore 2>&1 | head -n 8; echo '--dropbox'; ls -l /data/system/dropbox 2>&1 | tail -n 14; echo '--crash log'; logcat -b crash -d -t 25 2>&1 | cut -c1-200")
                if (r.timedOut) EventLog.add("boot info: root shell timed out")
                else EventLog.add("boot info:\n" + r.out.trim().take(3000))
                // what the system itself wrote about the last restart: the newest dropbox reports, pstore text, saved logs
                val r2 = Root.exec("getprop persist.sys.boot.reason; getprop sys.boot.reason.last; " +
                    "echo '--pstore text'; for f in /sys/fs/pstore/console-ramoops* /sys/fs/pstore/dmesg-ramoops* /sys/fs/pstore/pmsg-ramoops*; do [ -f \"${'$'}f\" ] && { echo \"== ${'$'}f\"; tail -n 25 \"${'$'}f\" | cut -c1-200; }; done; " +
                    "echo '--newest dropbox'; for f in ${'$'}(ls -t /data/system/dropbox 2>/dev/null | head -n 3); do echo \"== ${'$'}f\"; head -c 1300 \"/data/system/dropbox/${'$'}f\" | cut -c1-200; done; " +
                    "echo '--saved logs'; ls -l /data/misc/logd 2>&1 | head -n 6; ls -l /data/misc/bootstat 2>&1 | head -n 6")
                if (!r2.timedOut) EventLog.add("restart evidence:\n" + r2.out.trim().take(4500))
            } catch (e: Throwable) { EventLog.add("boot info failed: ${e.message}") }
        }.also { it.isDaemon = true }.start()
    }

    /** Why the app's previous runs ended, as Android recorded it (crash, ANR, low memory, killed...). */
    private fun logLastExits() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val f = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            for (x in am.getHistoricalProcessExitReasons(packageName, 0, 3)) {
                val why = when (x.reason) {
                    1 -> "exit by itself"; 2 -> "KILLED BY SIGNAL"; 3 -> "LOW MEMORY"; 4 -> "CRASH"; 5 -> "NATIVE CRASH"; 6 -> "ANR (not responding)"
                    7 -> "start failed"; 8 -> "permission change"; 9 -> "too many resources"; 10 -> "user requested"; 11 -> "user stopped"
                    12 -> "dependency died"; 13 -> "other"; 14 -> "frozen"; else -> "reason ${x.reason}"
                }
                EventLog.add("previous run ended ${f.format(java.util.Date(x.timestamp))}: $why status=${x.status} ${x.description ?: ""} importance=${x.importance} rss=${x.rss / 1024}MB")
            }
        } catch (e: Throwable) { EventLog.add("could not read exit reasons: ${e.message}") }
    }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); EventLog.add("trim memory level $level") }
    override fun onLowMemory() { super.onLowMemory(); EventLog.add("LOW MEMORY warning") }
    /** Was this app in split screen when it was last put in the background? (kept in memory only: it applies while the app keeps running) */
    private var splitWhenLeft = false

    override fun onPause() {
        super.onPause()
        splitWhenLeft = isInMultiWindowMode
        EventLog.add("paused" + if (splitWhenLeft) " (in split screen)" else "")
    }

    override fun onResume() {
        ensureGps()
        super.onResume()
        ensureAudioPermission()
        EventLog.add("resumed")
        // another app took over the screen in full screen and the user came back: put the split screen and the lower app back as they were
        if (splitWhenLeft && !isInMultiWindowMode) {
            splitWhenLeft = false
            EventLog.add("coming back from full screen: restoring split screen")
            ui.postDelayed({
                if (!isInMultiWindowMode) {
                    if (lowerIdx >= 0 && slotPkg(lowerIdx).isNotEmpty()) openLower(lowerIdx) else { pendingPkg = ""; openLower(-1) }
                }
            }, 900)
        } else splitWhenLeft = false
    }

    private fun log(s: String) {
        EventLog.add(s)
        showLog(s)
    }

    private fun showLog(s: String) = runOnUiThread {
        logView.append(s + "\n"); statusView.text = "Status: $s"
        if (::canStatusView.isInitialized) canStatusView.text = s
    }

    private fun refreshDeviceSpinner() {
        val names = devices.map { "${it.name} (${it.address})" }
        deviceSpinner.adapter = whiteAdapter(names)
    }

    // ---------------------------------------------------------- Permissions
    private fun ensurePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else loadPairedDevices()
    }

    // ---------------------------------------------------------- GPS heading (used only when the car sends no compass heading)
    private var gpsHeading = -1.0
    private var gpsHeadingAt = 0L
    private var gpsListener: android.location.LocationListener? = null
    private var gpsAsked = false
    private fun gpsHeadingNow(): Double? = if (gpsHeading >= 0 && android.os.SystemClock.uptimeMillis() - gpsHeadingAt < 120_000L) gpsHeading else null
    private fun ensureGps() {
        if (gpsListener != null) return
        val perm = Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) { startGps(); return }
        if (gpsAsked) return
        gpsAsked = true
        Thread {
            // the Pulse is rooted: grant it quietly first, and only ask on screen if that does not work
            try { Root.exec("pm grant $packageName $perm") } catch (e: Exception) { }
            ui.post {
                if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) startGps()
                else { requestPermissions(arrayOf(perm), 2) }
            }
        }.start()
    }
    private fun startGps() {
        if (gpsListener != null) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val l = object : android.location.LocationListener {
                override fun onLocationChanged(loc: android.location.Location) {
                    if (loc.hasBearing() && loc.hasSpeed() && loc.speed >= 1.5f) {
                        gpsHeading = loc.bearing.toDouble(); gpsHeadingAt = android.os.SystemClock.uptimeMillis()
                    }
                }
                @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            val got = ArrayList<String>()
            for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)) {
                try { if (lm.allProviders.contains(p)) { lm.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper()); got.add(p) } } catch (e: Exception) { }
            }
            gpsListener = l
        } catch (e: Exception) { }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == 2) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startGps()
            return
        }
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) loadPairedDevices()
        else log("Bluetooth permission denied.")
    }

    private fun adapter(): BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun loadPairedDevices() {
        val ad = adapter()
        if (ad == null || !ad.isEnabled) { log("Bluetooth is off or unavailable."); return }
        devices.clear()
        // Put vLinker devices first, and "FD-Android" (the classic-SPP personality) before "FD-iOS".
        devices += ad.bondedDevices.sortedBy {
            val n = it.name.orEmpty().lowercase()
            when {
                n.contains("vlinker") && n.contains("android") -> 0
                n.contains("vlinker") -> 1
                else -> 2
            }
        }
        refreshDeviceSpinner()
    }

    // ------------------------------------------------------------- BLE scan
    private fun scanBle() {
        val scanner = adapter()?.bluetoothLeScanner ?: return log("BLE scanner unavailable (is Bluetooth on?).")
        log("Scanning for vLinker (BLE) for 8 s ...")
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val d = r.device
                if (d.name?.contains("vlinker", true) == true && devices.none { it.address == d.address }) {
                    runOnUiThread { devices.add(0, d); refreshDeviceSpinner(); log("Found ${d.name} (${d.address})") }
                }
            }
            override fun onScanFailed(errorCode: Int) { log("Scan failed: $errorCode") }
        }
        scanner.startScan(cb)
        Handler(Looper.getMainLooper()).postDelayed({ scanner.stopScan(cb); log("Scan finished.") }, 8000)
    }

    // ----------------------------------------------------------- Connection
    private fun connect() {
        val dev = devices.getOrNull(deviceSpinner.selectedItemPosition) ?: return log("No device selected.")
        val proto = protocolField.text.toString().trim().ifEmpty { "6" }
        Thread {
            try {
                close()
                adapter()?.cancelDiscovery()
                val name = dev.name.orEmpty()
                log("Connecting to $name (${dev.address}) ...")
                val bleOnly = name.contains("ios", true) || dev.type == BluetoothDevice.DEVICE_TYPE_LE
                var ok = false
                if (!bleOnly) ok = connectClassic(dev)
                if (!ok) ok = connectBle(dev)
                if (!ok) { setConnected(false); log("FAILED: could not connect over Classic or BLE."); return@Thread }
                setConnected(true)
                log("Connected to $name")
                // ELM327/STN init: reset, echo off, linefeeds off, spaces off, headers off,
                // auto-format CAN on, fixed protocol (no slow auto-search), 1 s ECU timeout
                for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATCAF1", "ATSP$proto", "ATSTFF")) {
                    log("> $c  <  ${send(c)}")
                }
                log("Adapter ready.")
            } catch (t: Throwable) { setConnected(false); log("Connect error: ${t.javaClass.simpleName}: ${t.message}") }
        }.start()
    }

    private fun connectClassic(dev: BluetoothDevice): Boolean {
        for (secure in listOf(true, false)) {      // some adapters only accept the insecure RFCOMM variant
            try {
                val s = if (secure) dev.createRfcommSocketToServiceRecord(sppUuid)
                        else dev.createInsecureRfcommSocketToServiceRecord(sppUuid)
                s.connect()
                socket = s; input = s.inputStream; output = s.outputStream
                log("Classic SPP connected (${if (secure) "secure" else "insecure"}).")
                return true
            } catch (e: Exception) { log("SPP ${if (secure) "secure" else "insecure"} failed: ${e.message}") }
        }
        try {   // last resort: raw RFCOMM channel 1
            val s = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket
            s.connect()
            socket = s; input = s.inputStream; output = s.outputStream
            log("Classic RFCOMM channel 1 connected.")
            return true
        } catch (e: Exception) { log("RFCOMM ch.1 failed: ${e.message}") }
        return false
    }

    /** BLE UART-style link: finds a service with one notify and one writable characteristic (FFF0/FFE0 on vLinker/ELM clones). */
    private fun connectBle(dev: BluetoothDevice): Boolean {
        log("Trying BLE ...")
        val inStream = QueueInputStream()
        val ready = CountDownLatch(1)
        val written = Semaphore(0)
        var writeChar: BluetoothGattCharacteristic? = null
        var g: BluetoothGatt? = null

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) { setConnected(false); log("BLE disconnected") }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                var notify: BluetoothGattCharacteristic? = null
                var write: BluetoothGattCharacteristic? = null
                val preferred = gatt.services.sortedBy {
                    val u = it.uuid.toString().substring(4, 8).uppercase()
                    if (u == "FFF0" || u == "FFE0") 0 else 1
                }
                for (svc in preferred) {
                    for (c in svc.characteristics) {
                        val p = c.properties
                        if (notify == null && p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) notify = c
                        if (write == null && p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 && c.service == svc) write = c
                    }
                    if (notify != null && write != null) break
                    notify = null; write = null
                }
                if (notify == null || write == null) { log("No UART-like GATT service found."); ready.countDown(); return }
                writeChar = write
                gatt.setCharacteristicNotification(notify, true)
                val d = notify.getDescriptor(cccUuid)
                if (d == null) { ready.countDown(); return }
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(d)
            }
            override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { ready.countDown() }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) { c.value?.let { inStream.push(it) } }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { written.release() }
        }

        g = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
            else dev.connectGatt(this, false, cb)
        if (!ready.await(10, TimeUnit.SECONDS) || writeChar == null) { g?.close(); log("BLE setup failed."); return false }

        val wc = writeChar!!
        gatt = g; input = inStream
        output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var i = off
                while (i < off + len) {                       // 20-byte chunks fit the default ATT MTU
                    val chunk = b.copyOfRange(i, minOf(i + 20, off + len))
                    wc.value = chunk
                    g!!.writeCharacteristic(wc)
                    written.tryAcquire(1, TimeUnit.SECONDS)
                    i += 20
                }
            }
        }
        log("BLE UART connected.")
        return true
    }

    private fun close() {
        polling = false
        try { socket?.close() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        socket = null; gatt = null; input = null; output = null
        setConnected(false)
    }

    private var pausedHidden = false

    /** Not visible any more (another app full screen, or the screen is off): stop reading the CAN bus until we are seen again. */
    override fun onStop() {
        super.onStop()
        EventLog.add("stopped")
        if (useCan && prefs().getBoolean("pauseHidden", true) && pulse != null) {
            pulse?.stop(); pausedHidden = true
            EventLog.add("hidden: CAN reading paused")
        }
    }

    override fun onStart() {
        super.onStart()
        EventLog.add("started")
        if (pausedHidden) {
            pausedHidden = false
            if (useCan) { pulse?.start(); EventLog.add("visible: CAN reading resumed") }
        }
    }

    override fun onDestroy() { EventLog.add("app stopped"); heartbeat?.interrupt(); try { gpsListener?.let { (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).removeUpdates(it) } } catch (e: Exception) { }; InApp.useExternal(); if (layoutRecreating) pulse?.saveTrip(); mediaWatcher?.stop(); try { btReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }; pulse?.stop(); ui.removeCallbacksAndMessages(null); close(); super.onDestroy() }

    /** Send an AT or OBD command; read until the ELM '>' prompt (3 s timeout). */
    @Synchronized
    private fun send(cmd: String): String {
        try { return sendRaw(cmd) }
        catch (e: java.io.IOException) { setConnected(false); throw e }
    }

    @Synchronized
    private fun sendRaw(cmd: String): String {
        val out = output ?: throw IllegalStateException("Not connected")
        val inp = input ?: throw IllegalStateException("Not connected")
        while (inp.available() > 0) inp.read()          // flush stale bytes
        out.write((cmd + "\r").toByteArray()); out.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + (if (cmd.startsWith("ATZ")) 5000 else if (cmd.startsWith("AT")) 3000 else 8000)
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val ch = inp.read().toChar()
                if (ch == '>') break
                sb.append(ch)
            } else Thread.sleep(10)
        }
        return sb.toString().trim().replace("\r", "\n")
    }

    // -------------------------------------------------------------- Reading
    private fun applyPreset(p: Preset) {
        requestField.setText(p.request); byteIndexField.setText(p.byteIndex.toString())
        byteLenField.setText(p.byteLen.toString()); scaleField.setText(p.scale.toString())
        offsetField.setText(p.offset.toString()); signedBox.isChecked = p.signed
    }

    // Temperatures are decoded in °C; this converts for display only.
    private var useFahrenheit = false
    private var renderLast: (() -> Unit)? = null

    // ------------------------------------------------------- Statistics card (main page)
    private lateinit var statsCard: LinearLayout
    private lateinit var statsBox: LinearLayout
    private lateinit var statsHint: TextView
    private val statViews = HashMap<String, TextView>()
    private var statKey = ""
    private val statGroups = listOf(
        "BATTERY" to listOf("Charge", "HV Cap", "Est. Capacity", "Battery Health", "Battery Temp", "Pack Volts", "Pack Amps", "Cells", "Cell V max", "Cell V min"),
        "CLIMATE" to listOf("Climate", "Heat pump", "PTC / Other", "Thermal", "Cabin", "Outside", "Solar now"),
        "12V SYSTEM" to listOf("12V Battery", "12V SOC", "12V SOH"),
        "TRIP" to listOf("Range", "Regen"),
    )

    private fun statTile(title: String): LinearLayout {
        val value = TextView(this).apply {
            textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; text = "--"
        }
        statViews[title] = value
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(28, 14)
            setPadding(dp(12), dp(9), dp(12), dp(10))
            addView(TextView(this@MainActivity).apply {
                text = title.uppercase(); textSize = 11f; letterSpacing = 0.08f
                setTextColor(Color.argb(170, 255, 255, 255)); setSingleLine()
            })
            addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
    }

    /** Show [values] (title to text) as tiles in groups, two to a row. The tiles are rebuilt only when the set of titles changes. */
    private fun renderStats(values: Map<String, String>) {
        statsCard.visibility = View.VISIBLE
        statsHint.visibility = if (useCan) View.GONE else View.VISIBLE
        val key = statGroups.flatMap { it.second }.filter { it in values }.joinToString("|")
        if (key != statKey) {
            statKey = key
            statsBox.removeAllViews()
            statViews.clear()
            var first = true
            for ((group, titles) in statGroups) {
                val have = titles.filter { it in values }
                if (have.isEmpty()) continue
                statsBox.addView(TextView(this).apply {
                    text = group; textSize = 12f; letterSpacing = 0.14f; setTextColor(Color.argb(200, 150, 215, 255))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (first) 6 else 16); bottomMargin = dp(6) })
                first = false
                for (pair in have.chunked(2)) {
                    val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for ((i, t) in pair.withIndex())
                        rowL.addView(statTile(t), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = dp(4) else leftMargin = dp(4) })
                    if (pair.size == 1) rowL.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(4) })
                    statsBox.addView(rowL, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
            }
        }
        for ((k, v) in values) statViews[k]?.text = v
    }

    private fun fmt(v: Double, unit: String, decimals: Int): String =
        if (unit == "\u00B0C" && useFahrenheit) String.format("%.${decimals}f \u00B0F", v * 9.0 / 5.0 + 32.0)
        else String.format("%.${decimals}f %s", v, unit)

    private fun showTemp(c: Double?, f: Boolean) {
        tempGauge.setReading(c, f)
        rangeBar.setReading(c, f)
    }

    private fun setFahrenheit(on: Boolean) {
        useFahrenheit = on
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("fahrenheit", on).apply()
        showTemp(lastTempC, on)
        renderLast?.let { runOnUiThread { it() } }      // refresh what is already on screen
    }

    private fun readOnce() {
        val unit = PRESETS.getOrNull(presetSpinner.selectedItemPosition)?.unit ?: ""
        val v = readValue(headerField.text.toString().trim(), filterField.text.toString().trim(),
            requestField.text.toString().trim().replace(" ", "").uppercase(),
            byteIndexField.text.toString().toIntOrNull() ?: 0, byteLenField.text.toString().toIntOrNull() ?: 1,
            scaleField.text.toString().toDoubleOrNull() ?: 1.0, offsetField.text.toString().toDoubleOrNull() ?: 0.0,
            signedBox.isChecked)
        val isTemp = PRESETS.getOrNull(presetSpinner.selectedItemPosition)?.name == "Battery temp"
        if (isTemp) {
            lastTempC = v
            runOnUiThread { showTemp(v, useFahrenheit) }
        } else if (v != null) {
            renderLast = { resultView.textSize = 28f; resultView.text = fmt(v, unit, 1) }
            runOnUiThread { renderLast?.invoke() }
        }
    }

    private fun readAll() {
        val header = headerField.text.toString().trim(); val filter = filterField.text.toString().trim()
        val rows = mutableListOf<() -> String>()
        val vals = LinkedHashMap<String, Pair<Double, String>>()
        val names = mapOf("Pack voltage" to "Pack Volts", "Pack current" to "Pack Amps", "State of charge" to "Charge",
            "Cell V max" to "Cell V max", "Cell V min" to "Cell V min", "12V battery (unconfirmed)" to "12V Battery")
        var tempC: Double? = null
        for (p in PRESETS) {
            val v = readValue(header, filter, p.request, p.byteIndex, p.byteLen, p.scale, p.offset, p.signed)
            if (p.name == "Battery temp") { tempC = v; continue }     // shown in the gauge
            if (v != null) { names[p.name]?.let { vals[it] = v to p.unit } ?: run { rows += { p.name + ": " + fmt(v, p.unit, 2) } } }
            else if (p.name.startsWith("12V")) {
                // Fallback: adapter's own supply voltage on OBD pin 16 (~\u00B10.2 V)
                try { val t = send("ATRV"); rows += { "12V (ATRV fallback): $t" } } catch (e: Exception) { log("ATRV failed") }
            } else rows += { "${p.name}: no data" }
        }
        if (tempC != null) vals["Battery Temp"] = tempC to "\u00B0C"
        renderLast = {
            resultView.text = rows.joinToString("\n") { it() }; resultView.textSize = 16f
            renderStats(vals.mapValues { fmt(it.value.first, it.value.second, if (it.value.second == "mV") 0 else 1) })
        }
        lastTempC = tempC
        runOnUiThread { showTemp(tempC, useFahrenheit); renderLast?.invoke() }
    }

    /** One read-only request. Returns the decoded value, or null on failure. */
    private fun readValue(header: String, filter: String, req: String, idx: Int, len: Int,
                          scale: Double, offset: Double, signed: Boolean): Double? {
        try {
            if (req.length < 2 || req.length % 2 != 0) { log("Bad request hex."); return null }
            val service = req.substring(0, 2)
            if (service != "01" && service != "22") { log("Only services 01 and 22 are allowed."); return null }

            if (header.isNotEmpty()) send("ATSH$header")
            if (filter.isNotEmpty()) send("ATCRA$filter") else send("ATCRA")

            val raw = send(req)
            val payload = extractPayload(raw, req)
            if (payload == null) { log("$req: no/unsupported reply: ${raw.replace("\n", " | ")}"); return null }
            if (idx + len > payload.size) { log("$req: reply has only ${payload.size} data bytes: ${payload.hex()}"); return null }

            var v = 0
            for (i in 0 until len) v = (v shl 8) or payload[idx + i]
            if (signed && v and (1 shl (len * 8 - 1)) != 0) v -= (1 shl (len * 8))
            val value = v * scale + offset
            log("$req raw: ${payload.hex()}  ->  $value")
            return value
        } catch (e: Exception) {
            log("Read error: ${e.message}"); return null
        }
    }

    /**
     * Strips the positive-response header (0x41 for service 01, 0x62 for service 22)
     * plus the echoed PID/DID and returns the remaining data bytes.
     * Handles ELM327 multi-line ISO-TP output ("014", "0:...", "1:...").
     */
    private fun extractPayload(raw: String, req: String): List<Int>? {
        val lines = raw.split("\n").map { it.replace(" ", "").uppercase() }.filter { it.isNotEmpty() && !it.contains("SEARCHING") && !it.contains("BUSINIT") }
        if (lines.any { it.contains("NODATA") || it.contains("ERROR") || it.contains("UNABLE") }) return null

        val hex = if (lines.any { Regex("^[0-9A-F]:").containsMatchIn(it) }) {
            lines.filter { Regex("^[0-9A-F]:").containsMatchIn(it) }.joinToString("") { it.substring(2) }
        } else lines.joinToString("")

        val prefix = if (req.startsWith("22")) "62" + req.substring(2) else "41" + req.substring(2)
        val start = hex.indexOf(prefix)
        if (start < 0) return null
        val body = hex.substring(start + prefix.length)
        return body.chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
    }

    private fun List<Int>.hex() = joinToString(" ") { "%02X".format(it) }

    private fun togglePolling() {
        if (polling) { polling = false; log("Polling stopped"); return }
        polling = true
        Thread {
            while (polling) { readOnce(); Thread.sleep(1000) }
        }.start()
    }
}


/** Thin gradient bar showing the state of charge. */
class SocBarView(ctx: Context) : View(ctx) {
    private var pct = 0
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setPercent(p: Int?) { val n = (p ?: 0).coerceIn(0, 100); if (n != pct) { pct = n; invalidate() } }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, track)
        if (pct > 0) {
            fill.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#FF3B4E"), Color.parseColor("#2ECC71"), android.graphics.Shader.TileMode.CLAMP)
            rect.set(0f, 0f, w * pct / 100f, h)
            c.drawRoundRect(rect, r, r, fill)
        }
    }
}

/** The skins. A skin is a Look: which gauge drawing to use, its colours and fonts, and how the strip and buttons are
 *  styled. To add one: add a Look to [list] (and, for a new gauge drawing, a function in ArcGaugeView). The picker and
 *  its thumbnails are made from the list, and each thumbnail is the real gauge drawn with that skin. */
object Skins {
    class Look(
        val id: Int, val name: String, val note: String,
        val gauge: Int,              // 0 segmented arc, 1 fine ticks, 2 minimal, 3 analog pointer, 4 bold blocks, 5 glowing ring, 6 wedge bars, 7 numbered scale, 8 frosted glass
        val accent: Int,             // units, strip line, selected button
        val accentDay: Int,          // the same on the light (day) background
        val accent2: Int,            // second colour in the strip line
        val bgTop: Int, val bgBottom: Int,   // dashboard background at night
        val ramp: IntArray,          // gauge colours, low to high
        val numFont: String, val italic: Boolean,
        val stripFlat: Boolean,      // strip is a single hairline instead of the tapered outline
        val chip: Int, val chipStroke: Int,  // buttons and tabs
    )

    private fun c(s: String) = Color.parseColor(s)

    val list = listOf(
        Look(7, "Modern 1", "A numbered scale with a marker riding on it and a lit band, in steel blues", 7,
            c("#5BB8FF"), c("#1F6FB8"), c("#3D7BFF"), c("#050A14"), c("#0C1830"),
            intArrayOf(c("#86E4FF"), c("#3DB0FF"), c("#2A7BFF"), c("#2457E6")),
            "sans-serif-condensed", false, false, Color.argb(160, 10, 20, 40), Color.argb(120, 91, 184, 255)),
        Look(1, "Modern 2", "A fine dial with tick marks, upright numbers and a glowing pointer", 1,
            c("#8FD8FF"), c("#0B6FD6"), c("#4C7DFF"), c("#03060B"), c("#0A1424"),
            intArrayOf(c("#22D3EE"), c("#1E90FF"), c("#8E5CFF"), c("#FF4FA3"), c("#FF3B4E")),
            "sans-serif-light", false, false, Color.argb(150, 12, 20, 36), Color.argb(120, 143, 216, 255)),
        Look(2, "Modern 3", "Minimal: one thin arc, very large numbers, nothing else", 2,
            c("#EEF2F7"), c("#2A3340"), c("#9AA7B8"), c("#050506"), c("#0C0D10"),
            intArrayOf(c("#FFFFFF"), c("#E8EDF2")),
            "sans-serif-light", false, true, Color.argb(160, 18, 18, 20), Color.argb(90, 255, 255, 255)),
        Look(3, "Modern 4", "An analog dial with tick marks and an amber pointer", 3,
            c("#FF9F1C"), c("#C26A00"), c("#FF5A36"), c("#07090C"), c("#14171D"),
            intArrayOf(c("#FFC53D"), c("#FF9F1C"), c("#FF5A36")),
            "sans-serif-condensed", false, false, Color.argb(170, 22, 18, 12), Color.argb(130, 255, 159, 28)),
        Look(4, "Modern 5", "Bold separate blocks in teal", 4,
            c("#3DE0C8"), c("#0E9C8A"), c("#2AA8FF"), c("#04090B"), c("#0A171C"),
            intArrayOf(c("#3DE0C8"), c("#2AD0F0"), c("#2AA8FF"), c("#6C8CFF")),
            "sans-serif-medium", false, false, Color.argb(160, 8, 22, 26), Color.argb(120, 61, 224, 200)),
        Look(5, "Modern 6", "A thick glowing ring in rose gold with fine inner lines", 5,
            c("#FFB48A"), c("#C0583A"), c("#FF6B8A"), c("#08060C"), c("#15111D"),
            intArrayOf(c("#FFD7A8"), c("#FFB48A"), c("#FF7A6B"), c("#FF4F8A")),
            "sans-serif-light", false, false, Color.argb(165, 22, 14, 24), Color.argb(120, 255, 180, 138)),
        Look(6, "Modern 7", "Radial bars that grow taller as the value rises, in red", 6,
            c("#FF3B3B"), c("#C41E1E"), c("#D9DEE5"), c("#050505"), c("#101113"),
            intArrayOf(c("#EDEFF2"), c("#FF8A5B"), c("#FF3B3B")),
            "sans-serif-medium", false, true, Color.argb(170, 16, 16, 18), Color.argb(120, 255, 59, 59)),
        Look(0, "Modern 8", "Segmented arcs with italic numbers (the original look)", 0,
            c("#2AA8FF"), c("#2A78C8"), c("#8E5CFF"), c("#04070D"), c("#0B1626"),
            intArrayOf(c("#22D3EE"), c("#1E90FF"), c("#8E5CFF"), c("#FF4FA3"), c("#FF3B4E")),
            "sans-serif-light", true, false, Color.argb(150, 20, 20, 40), Color.argb(120, 255, 255, 255)),
        Look(8, "Glass", "A thick frosted-glass band with outer tick marks, scale numbers and a small blue marker", 8,
            c("#6FD8F0"), c("#0B7C95"), c("#4C8DFF"), c("#070B12"), c("#121A26"),
            intArrayOf(c("#FFFFFF"), c("#E6F0FA")),
            "sans-serif-light", false, true, Color.argb(120, 40, 52, 70), Color.argb(110, 255, 255, 255)),
    )

    @Volatile var current = 7
    fun lookOf(id: Int): Look = list.firstOrNull { it.id == id } ?: list[0]
    val look: Look get() = lookOf(current)
    fun name(id: Int) = lookOf(id).name

    /** A colour on a skin's ramp, t from 0 (low) to 1 (high). */
    fun gradFor(id: Int, t: Float): Int {
        val stops = lookOf(id).ramp
        if (stops.size == 1) return stops[0]
        val k = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = minOf(k.toInt(), stops.size - 2)
        val f = k - i
        val a = stops[i]; val b = stops[i + 1]
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
    }
    fun grad(t: Float) = gradFor(current, t)
}

/** Segmented arc gauge in the style of a modern instrument cluster: a "(" arc (or ")" when mirrored) with the
 *  readout in the middle. Fills from the zero position, so a regen value fills the other way. */
class ArcGaugeView(ctx: Context, var mirrored: Boolean, private val zeroFrac: Float, private val colorAt: (Float) -> Int) : View(ctx) {
    /** Layout 2: 1 = the battery-chart tile a little smaller and higher, 2 = higher still (50/50 window). */
    var iconLevel = 0
    var topLabel = ""; var topValue = ""
    var title = ""; var valueText = "--"; var unit = ""
    var bottomLabel = ""; var bottomValue = ""; var bottomColor: Int? = null
    var day = false
    var hold = false
    var ready = false
    var barFrac: Float? = null
    private var shown = 0f
    private var target = 0f
    private var has = false
    private var anim: ValueAnimator? = null
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val lightItalic = Typeface.create("sans-serif-light", Typeface.ITALIC)
    private val medium = Typeface.create("sans-serif-medium", Typeface.ITALIC)

    fun refresh() = invalidate()

    /** Draw with this skin instead of the current one (used for the previews in the skin picker). */
    var skinOverride: Int? = null
    private val upright = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val uprightMed = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    /** Numbers to print along the dial (position 0..1 along the sweep, and the text); used by the numbered-scale skin. */
    var scale: List<Pair<Float, String>> = emptyList()
    private val rect2 = RectF()
    private val needle = android.graphics.Path()
    private val look: Skins.Look get() = Skins.lookOf(skinOverride ?: Skins.current)
    private var tfKey = ""
    private var tfNum: Typeface = upright
    /** The skin's font for the big numbers. */
    private fun numTf(): Typeface {
        val k = look.numFont + look.italic
        if (k != tfKey) { tfKey = k; tfNum = Typeface.create(look.numFont, if (look.italic) Typeface.ITALIC else Typeface.NORMAL) }
        return tfNum
    }

    /** Show a fixed value at once, with no animation (previews). */
    fun showStatic(f: Float) {
        anim?.cancel(); sweeping = false
        val v = f.coerceIn(0f, 1f)
        has = true; liveHas = true; liveTarget = v; target = v; shown = v
    }

    private var sweeping = false
    private var liveHas = false
    private var liveTarget = 0f

    fun setFraction(f: Float?) {
        liveHas = f != null
        liveTarget = (f ?: zeroFrac).coerceIn(0f, 1f)
        if (!sweeping) follow()
    }

    /** Move the arc to the live value. */
    private fun follow() {
        has = liveHas
        if (liveTarget == target) return
        target = liveTarget
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, liveTarget).apply {
            duration = 250
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Start-up show: sweep the arc all the way up and back down, then settle on the live value. */
    fun intro() {
        anim?.cancel()
        sweeping = true
        has = true
        anim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 2200
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    sweeping = false
                    shown = zeroFrac  // nothing is filled here, so there is no jump
                    target = -1f      // force the move to the live value
                    follow()
                }
            })
            start()
        }
    }

    private fun text(s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface, ls: Float = 0f, limit: Float = 0f) {
        txt.letterSpacing = ls
        txt.textSize = size; txt.color = color; txt.typeface = tf
        // keep long text inside the arc
        val maxW = if (limit > 0f) minOf(limit, width * 0.62f) else width * 0.62f
        val w = txt.measureText(s)
        if (w > maxW) txt.textSize = size * maxW / w
        arcCanvas?.drawText(s, x, y, txt)
    }

    private var arcCanvas: Canvas? = null

    /** The auto-hold symbol (an H in a circle between two arcs) with READY in green under it, in the open side of the gauge. */
    private fun drawHoldAndReady(c: Canvas, x: Float, cy: Float, r: Float) {
        if (!hold) return
        val sym = if (day) Color.parseColor("#3A4656") else Color.WHITE
        val green = if (day) Color.parseColor("#2E9E1F") else Color.parseColor("#7CFF3A")
        val rho = r * 0.13f
        val y = cy
        if (hold) {
            arc.style = Paint.Style.STROKE
            arc.strokeWidth = rho * 0.09f
            arc.color = sym
            c.drawCircle(x, y, rho, arc)
            rect.set(x - rho * 1.45f, y - rho * 1.45f, x + rho * 1.45f, y + rho * 1.45f)
            arc.strokeCap = Paint.Cap.ROUND
            c.drawArc(rect, 145f, 70f, false, arc)    // "(" on the left
            c.drawArc(rect, -35f, 70f, false, arc)    // ")" on the right
            arc.strokeCap = Paint.Cap.BUTT
            txt.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            txt.textSize = rho * 1.3f
            txt.color = sym
            c.drawText("H", x, y + rho * 0.45f, txt)
        }
    }

    /** Working values shared by the skin drawings. */
    private inner class Geo(w: Float, h: Float, startAt: Float? = null) {
        val cx = w / 2
        val cy = h / 2
        val r = minOf(w, h) / 2 * 0.92f
        val start = startAt ?: if (mirrored) 75f else 105f
        val dir = if (mirrored) -1f else 1f
        val sweep = 210f
        val ink = if (day) Color.parseColor("#14202E") else Color.parseColor("#F2F6FB")
        val dim = if (day) Color.argb(150, 20, 32, 46) else Color.argb(140, 220, 232, 245)
        val faint = if (day) Color.argb(46, 20, 32, 46) else Color.argb(58, 220, 232, 245)
        val accent = look.let { if (day) it.accentDay else it.accent }
        val live = sweeping || has
        val lo = if (sweeping) 0f else if (has) minOf(zeroFrac, shown) else 0f
        val hi = if (sweeping) shown else if (has) maxOf(zeroFrac, shown) else 0f
        fun x(rad: Float, f: Float): Float = cx + Math.cos(Math.toRadians((start + dir * sweep * f).toDouble())).toFloat() * rad
        fun y(rad: Float, f: Float): Float = cy + Math.sin(Math.toRadians((start + dir * sweep * f).toDouble())).toFloat() * rad
    }

    /** Colour of a filled stretch of the arc. [mid] is the position along the sweep; regen (below zero) is green. */
    private fun segColor(mid: Float, lo: Float, hi: Float): Int {
        val rel = if (hi > lo) ((mid - lo) / (hi - lo)).coerceIn(0f, 1f) else 0f
        fun regen(k: Float) = Color.rgb((46 + (31 - 46) * k).toInt(), (204 + (163 - 204) * k).toInt(), (113 + (122 - 113) * k).toInt())
        return if (sweeping && zeroFrac > 0f) {
            if (mid < zeroFrac) regen(1f - mid / zeroFrac) else colorAt(((mid - zeroFrac) / maxOf(hi - zeroFrac, 0.001f)).coerceIn(0f, 1f))
        } else if (shown < zeroFrac) regen(1f - rel) else colorAt(rel)
    }

    /** A soft dial face behind the gauge, tinted with the skin's accent. */
    private fun face(c: Canvas, g: Geo) {
        arc.style = Paint.Style.FILL
        arc.shader = android.graphics.RadialGradient(g.cx, g.cy, g.r * 1.04f,
            intArrayOf(if (day) Color.argb(120, 255, 255, 255) else Color.argb(52, Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent)),
                if (day) Color.argb(50, 190, 205, 228) else Color.argb(70, 8, 14, 26), Color.TRANSPARENT),
            floatArrayOf(0f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(g.cx, g.cy, g.r * 1.04f, arc)
        arc.shader = null
        arc.style = Paint.Style.STROKE
        arc.strokeCap = Paint.Cap.BUTT
    }

    /** The texts in the middle of the gauge, the same places in every skin. */
    private fun drawReadouts(c: Canvas, g: Geo, valueScale: Float, topIn: Float = 0f, barW: Float = 0.5f, ring: Float = 0f) {
        val r = g.r; val cx = g.cx; val cy = g.cy
        // with [ring] set, a line of text is kept narrower than the chord of that circle where its far edge is
        fun lim(yEdge: Float): Float {
            if (ring <= 0f) return 0f
            val e = yEdge * r
            return maxOf(r * 0.3f, 2f * Math.sqrt(maxOf(0.0, (ring * r * ring * r - e * e).toDouble())).toFloat() * 0.94f)
        }
        text(topLabel, cx, cy - r * (0.60f - topIn), r * 0.080f, g.dim, upright, 0.10f, lim(0.60f - topIn + 0.07f))
        text(topValue, cx, cy - r * (0.48f - topIn * 0.75f), r * 0.12f, g.ink, numTf(), 0f, lim(0.48f - topIn * 0.75f + 0.09f))
        text(title, cx, cy - r * 0.25f, r * 0.11f, g.dim, upright, 0.16f)
        if (!hold) text(valueText, cx, cy + r * 0.14f, r * valueScale * 0.84f, g.ink, numTf())
        text(unit, cx, cy + r * 0.34f, r * 0.13f, g.accent, uprightMed, 0.06f)
        text(bottomLabel, cx, cy + r * 0.50f, r * 0.080f, g.dim, upright, 0.10f, lim(0.52f))
        text(bottomValue, cx, cy + r * 0.62f, r * 0.12f, bottomColor ?: g.ink, numTf(), 0f, lim(0.65f))
        drawHoldAndReady(c, cx, cy - r * 0.01f, r)
        val bf = barFrac
        if (bf != null) {
            val bw = r * barW
            val by = cy + r * 0.70f
            arc.style = Paint.Style.FILL
            arc.color = g.faint
            rect.set(cx - bw / 2, by, cx + bw / 2, by + r * 0.04f)
            c.drawRoundRect(rect, 6f, 6f, arc)
            arc.color = g.accent
            rect.set(cx - bw / 2, by, cx - bw / 2 + bw * bf.coerceIn(0f, 1f), by + r * 0.04f)
            c.drawRoundRect(rect, 6f, 6f, arc)
            arc.style = Paint.Style.STROKE
        }
    }

    /** Skin "fine ticks": a dial face, tick marks that light up, a thin glowing arc and a bright pointer. */
    private fun drawCluster(c: Canvas, w: Float, h: Float) {
        val g = Geo(w, h)
        face(c, g)
        for (i in 0..42) {
            val f = i / 42f
            val major = i % 6 == 0
            val r1 = g.r * 0.99f
            val r2 = if (major) g.r * 0.89f else g.r * 0.94f
            val lit = g.live && g.hi > g.lo && f >= g.lo - 0.002f && f <= g.hi + 0.002f
            arc.strokeWidth = maxOf(1.5f, g.r * (if (major) 0.020f else 0.011f))
            arc.color = if (lit) segColor(f, g.lo, g.hi) else if (major) g.dim else g.faint
            c.drawLine(g.x(r1, f), g.y(r1, f), g.x(r2, f), g.y(r2, f), arc)
        }
        val ra = g.r * 0.80f
        val sw = g.r * 0.045f
        rect.set(g.cx - ra, g.cy - ra, g.cx + ra, g.cy + ra)
        arc.strokeWidth = sw; arc.color = g.faint
        c.drawArc(rect, g.start, g.dir * g.sweep, false, arc)
        if (g.live && g.hi > g.lo) {
            val seg = 120
            for (i in (g.lo * seg).toInt() until minOf(seg, Math.ceil((g.hi * seg).toDouble()).toInt())) {
                val f0 = i.toFloat() / seg
                val f1 = (i + 1).toFloat() / seg
                val s0 = maxOf(f0, g.lo)
                val s1 = minOf(f1, g.hi)
                if (s1 <= s0) continue
                val col = segColor((f0 + f1) / 2, g.lo, g.hi)
                arc.strokeWidth = sw * 2.6f
                arc.color = Color.argb(38, Color.red(col), Color.green(col), Color.blue(col))
                c.drawArc(rect, g.start + g.dir * s0 * g.sweep, g.dir * (s1 - s0) * g.sweep, false, arc)
                arc.strokeWidth = sw
                arc.color = col
                c.drawArc(rect, g.start + g.dir * s0 * g.sweep, g.dir * (s1 - s0) * g.sweep, false, arc)
            }
        }
        if (zeroFrac > 0f) {
            arc.strokeWidth = maxOf(2f, g.r * 0.022f); arc.color = g.accent
            c.drawLine(g.x(g.r * 0.70f, zeroFrac), g.y(g.r * 0.70f, zeroFrac), g.x(g.r * 0.88f, zeroFrac), g.y(g.r * 0.88f, zeroFrac), arc)
        }
        if (g.live) {
            val px = g.x(ra, shown); val py = g.y(ra, shown)
            val tip = segColor(shown, g.lo, g.hi)
            arc.style = Paint.Style.FILL
            arc.color = Color.argb(70, Color.red(tip), Color.green(tip), Color.blue(tip))
            c.drawCircle(px, py, sw * 2.3f, arc)
            arc.color = if (day) Color.parseColor("#14202E") else Color.WHITE
            c.drawCircle(px, py, sw * 0.95f, arc)
            arc.style = Paint.Style.STROKE
        }
        drawReadouts(c, g, 0.44f, 0f, 0.5f, 0.76f)
    }

    /** Skin "minimal": one thin arc in a single colour and very large numbers. */
    private fun drawMinimal(c: Canvas, w: Float, h: Float) {
        val g = Geo(w, h)
        val ra = g.r * 0.86f
        val sw = maxOf(2f, g.r * 0.022f)
        rect.set(g.cx - ra, g.cy - ra, g.cx + ra, g.cy + ra)
        arc.style = Paint.Style.STROKE; arc.strokeCap = Paint.Cap.BUTT
        arc.strokeWidth = sw; arc.color = g.faint
        c.drawArc(rect, g.start, g.dir * g.sweep, false, arc)
        if (g.live && g.hi > g.lo) {
            arc.strokeWidth = sw * 1.8f
            arc.strokeCap = Paint.Cap.ROUND
            arc.color = if (!sweeping && shown < zeroFrac) Color.parseColor("#4CD98A") else g.accent
            c.drawArc(rect, g.start + g.dir * g.lo * g.sweep, g.dir * (g.hi - g.lo) * g.sweep, false, arc)
            arc.strokeCap = Paint.Cap.BUTT
        }
        if (zeroFrac > 0f) {
            arc.strokeWidth = maxOf(2f, g.r * 0.018f); arc.color = g.dim
            c.drawLine(g.x(ra - g.r * 0.05f, zeroFrac), g.y(ra - g.r * 0.05f, zeroFrac), g.x(ra + g.r * 0.05f, zeroFrac), g.y(ra + g.r * 0.05f, zeroFrac), arc)
        }
        drawReadouts(c, g, 0.50f, 0f, 0.5f, 0.82f)
    }

    /** Skin "analog": a dial with tick marks and a short pointer blade riding on the outside of the dial. */
    private fun drawAnalog(c: Canvas, w: Float, h: Float) {
        val g = Geo(w, h)
        face(c, g)
        for (i in 0..42) {
            val f = i / 42f
            val major = i % 6 == 0
            val r1 = g.r * 0.99f
            val r2 = if (major) g.r * 0.86f else g.r * 0.93f
            val lit = g.live && g.hi > g.lo && f >= g.lo - 0.002f && f <= g.hi + 0.002f
            arc.strokeWidth = maxOf(1.5f, g.r * (if (major) 0.024f else 0.012f))
            arc.color = if (lit) segColor(f, g.lo, g.hi) else if (major) g.dim else g.faint
            c.drawLine(g.x(r1, f), g.y(r1, f), g.x(r2, f), g.y(r2, f), arc)
        }
        if (g.live) {
            val a = Math.toRadians((g.start + g.dir * g.sweep * shown).toDouble())
            val ux = Math.cos(a).toFloat(); val uy = Math.sin(a).toFloat()
            val r0 = g.r * 0.70f; val r1 = g.r * 0.97f; val hw = g.r * 0.030f
            needle.reset()
            needle.moveTo(g.cx + ux * r0 - uy * hw, g.cy + uy * r0 + ux * hw)
            needle.lineTo(g.cx + ux * r1, g.cy + uy * r1)
            needle.lineTo(g.cx + ux * r0 + uy * hw, g.cy + uy * r0 - ux * hw)
            needle.close()
            arc.style = Paint.Style.STROKE
            arc.strokeWidth = g.r * 0.05f
            arc.strokeJoin = Paint.Join.ROUND
            arc.color = Color.argb(50, Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent))
            c.drawPath(needle, arc)
            arc.style = Paint.Style.FILL
            arc.color = g.accent
            c.drawPath(needle, arc)
            arc.style = Paint.Style.STROKE
            arc.strokeJoin = Paint.Join.MITER
        }
        if (zeroFrac > 0f) {
            arc.strokeWidth = maxOf(2f, g.r * 0.022f); arc.color = g.ink
            c.drawLine(g.x(g.r * 0.80f, zeroFrac), g.y(g.r * 0.80f, zeroFrac), g.x(g.r * 0.99f, zeroFrac), g.y(g.r * 0.99f, zeroFrac), arc)
        }
        drawReadouts(c, g, 0.44f, 0f, 0.5f, 0.68f)
    }

    /** Skin "bold blocks": 28 chunky separate blocks, lit one by one. */
    private fun drawBold(c: Canvas, w: Float, h: Float) {
        val g = Geo(w, h)
        val stroke = g.r * 0.105f
        val rr = g.r * 0.97f - stroke / 2
        rect.set(g.cx - rr, g.cy - rr, g.cx + rr, g.cy + rr)
        rect2.set(g.cx - g.r * 1.02f, g.cy - g.r * 1.02f, g.cx + g.r * 1.02f, g.cy + g.r * 1.02f)
        arc.style = Paint.Style.STROKE; arc.strokeCap = Paint.Cap.BUTT
        arc.strokeWidth = 2f; arc.color = g.faint
        c.drawArc(rect2, g.start - g.dir * 4f, g.dir * (g.sweep + 8f), false, arc)
        val n = 28
        val gap = 2.6f
        for (i in 0 until n) {
            val f0 = i.toFloat() / n
            val f1 = (i + 1).toFloat() / n
            val a0 = f0 * g.sweep + gap / 2
            val a1 = f1 * g.sweep - gap / 2
            arc.strokeWidth = stroke; arc.color = g.faint
            c.drawArc(rect, g.start + g.dir * a0, g.dir * (a1 - a0), false, arc)
            if (g.live) {
                val s0 = maxOf(a0, g.lo * g.sweep)
                val s1 = minOf(a1, g.hi * g.sweep)
                if (s1 > s0) {
                    arc.color = segColor((f0 + f1) / 2, g.lo, g.hi)
                    c.drawArc(rect, g.start + g.dir * s0, g.dir * (s1 - s0), false, arc)
                }
            }
        }
        drawReadouts(c, g, 0.42f, 0f, 0.5f, 0.84f)
    }

    /** Skin "glowing ring": a thick glowing ring with a bead at its tip and fine rings inside and outside. */
    private fun drawRing(c: Canvas, w: Float, h: Float) {
        val g = Geo(w, h)
        face(c, g)
        arc.style = Paint.Style.STROKE; arc.strokeCap = Paint.Cap.BUTT
        rect2.set(g.cx - g.r * 0.995f, g.cy - g.r * 0.995f, g.cx + g.r * 0.995f, g.cy + g.r * 0.995f)
        arc.strokeWidth = 2f; arc.color = g.faint
        c.drawArc(rect2, g.start - g.dir * 5f, g.dir * (g.sweep + 10f), false, arc)
        rect2.set(g.cx - g.r * 0.73f, g.cy - g.r * 0.73f, g.cx + g.r * 0.73f, g.cy + g.r * 0.73f)
        c.drawArc(rect2, g.start, g.dir * g.sweep, false, arc)
        val ra = g.r * 0.88f
        val sw = g.r * 0.070f
        rect.set(g.cx - ra, g.cy - ra, g.cx + ra, g.cy + ra)
        arc.strokeWidth = sw; arc.color = g.faint
        c.drawArc(rect, g.start, g.dir * g.sweep, false, arc)
        if (g.live && g.hi > g.lo) {
            val seg = 140
            for (i in (g.lo * seg).toInt() until minOf(seg, Math.ceil((g.hi * seg).toDouble()).toInt())) {
                val f0 = i.toFloat() / seg
                val f1 = (i + 1).toFloat() / seg
                val s0 = maxOf(f0, g.lo)
                val s1 = minOf(f1, g.hi)
                if (s1 <= s0) continue
                val col = segColor((f0 + f1) / 2, g.lo, g.hi)
                arc.strokeWidth = sw * 2.8f
                arc.color = Color.argb(30, Color.red(col), Color.green(col), Color.blue(col))
                c.drawArc(rect, g.start + g.dir * s0 * g.sweep, g.dir * (s1 - s0) * g.sweep, false, arc)
                arc.strokeWidth = sw
                arc.color = col
                c.drawArc(rect, g.start + g.dir * s0 * g.sweep, g.dir * (s1 - s0) * g.sweep, false, arc)
            }
            val px = g.x(ra, shown); val py = g.y(ra, shown)
            arc.style = Paint.Style.FILL
            arc.color = if (day) Color.parseColor("#14202E") else Color.WHITE
            c.drawCircle(px, py, sw * 0.62f, arc)
            arc.style = Paint.Style.STROKE
        }
        drawReadouts(c, g, 0.46f, 0f, 0.5f, 0.70f)
    }

    /** Skin "numbered scale": ticks round the edge, numbers inside them, a lit band, and a marker riding on the scale. */
    private fun drawScale(c: Canvas, w: Float, h: Float) {
        val g = Geo(w, h)
        face(c, g)
        arc.style = Paint.Style.STROKE; arc.strokeCap = Paint.Cap.BUTT
        for (i in 0..42) {
            val f = i / 42f
            val major = i % 6 == 0
            val r1 = g.r * 0.995f
            val r2 = if (major) g.r * 0.915f else g.r * 0.955f
            val lit = g.live && g.hi > g.lo && f >= g.lo - 0.002f && f <= g.hi + 0.002f
            arc.strokeWidth = maxOf(1.2f, g.r * (if (major) 0.016f else 0.009f))
            arc.color = if (lit) segColor(f, g.lo, g.hi) else if (major) g.dim else g.faint
            c.drawLine(g.x(r1, f), g.y(r1, f), g.x(r2, f), g.y(r2, f), arc)
        }
        // the wide band between the tick marks and an inner circle fills with the value, in a blue gradient
        val ra = g.r * 0.825f
        val sw = g.r * 0.17f
        rect.set(g.cx - ra, g.cy - ra, g.cx + ra, g.cy + ra)
        arc.strokeWidth = sw; arc.color = g.faint
        c.drawArc(rect, g.start, g.dir * g.sweep, false, arc)
        if (g.live && g.hi > g.lo) {
            val seg = 120
            for (i in (g.lo * seg).toInt() until minOf(seg, Math.ceil((g.hi * seg).toDouble()).toInt())) {
                val f0 = i.toFloat() / seg
                val f1 = (i + 1).toFloat() / seg
                val s0 = maxOf(f0, g.lo)
                val s1 = minOf(f1, g.hi)
                if (s1 <= s0) continue
                val col = segColor((f0 + f1) / 2, g.lo, g.hi)
                arc.strokeWidth = sw
                arc.color = Color.argb(205, Color.red(col), Color.green(col), Color.blue(col))
                c.drawArc(rect, g.start + g.dir * s0 * g.sweep, g.dir * (s1 - s0) * g.sweep, false, arc)
            }
        }
        // the inner circle that closes the band
        rect.set(g.cx - g.r * 0.74f, g.cy - g.r * 0.74f, g.cx + g.r * 0.74f, g.cy + g.r * 0.74f)
        arc.strokeWidth = 2f; arc.color = g.dim
        c.drawArc(rect, g.start - g.dir * 3f, g.dir * (g.sweep + 6f), false, arc)
        // the numbers sit on the band
        for ((f, label) in scale) text(label, g.x(g.r * 0.825f, f), g.y(g.r * 0.825f, f) + g.r * 0.032f, g.r * 0.090f, g.ink, numTf())
        // the marker: a small triangle on the scale, pointing in
        if (g.live) {
            val a = Math.toRadians((g.start + g.dir * g.sweep * shown).toDouble())
            val ux = Math.cos(a).toFloat(); val uy = Math.sin(a).toFloat()
            val rt = g.r * 0.895f; val rb = g.r * 1.01f; val hw = g.r * 0.045f
            needle.reset()
            needle.moveTo(g.cx + ux * rt, g.cy + uy * rt)
            needle.lineTo(g.cx + ux * rb - uy * hw, g.cy + uy * rb + ux * hw)
            needle.lineTo(g.cx + ux * rb + uy * hw, g.cy + uy * rb - ux * hw)
            needle.close()
            arc.style = Paint.Style.STROKE
            arc.strokeWidth = g.r * 0.05f
            arc.strokeJoin = Paint.Join.ROUND
            arc.color = Color.argb(60, Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent))
            c.drawPath(needle, arc)
            arc.style = Paint.Style.FILL
            arc.color = g.accent
            c.drawPath(needle, arc)
            arc.style = Paint.Style.STROKE
            arc.strokeJoin = Paint.Join.MITER
        }
        // the labels at the top sit a little lower and the charge bar is narrower, so neither touches the band or its numbers
        drawReadouts(c, g, 0.42f, 0.04f, 0.30f, 0.72f)
    }

    /** Skin "glass": a thick frosted-glass band, tick marks on its outer edge, numbers on the band and a small marker. */
    private fun drawGlass(c: Canvas, w: Float, h: Float) {
        val g = Geo(w, h, if (mirrored) 137f else 43f)
        arc.style = Paint.Style.STROKE; arc.strokeCap = Paint.Cap.BUTT
        // ticks on the outside
        for (i in 0..40) {
            val f = i / 40f
            val major = i % 5 == 0
            val r1 = g.r * 0.995f
            val r2 = if (major) g.r * 0.925f else g.r * 0.96f
            arc.strokeWidth = maxOf(1.2f, g.r * (if (major) 0.016f else 0.009f))
            arc.color = if (major) g.ink else g.dim
            c.drawLine(g.x(r1, f), g.y(r1, f), g.x(r2, f), g.y(r2, f), arc)
        }
        val sw = g.r * 0.21f
        val ra = g.r * 0.80f
        rect.set(g.cx - ra, g.cy - ra, g.cx + ra, g.cy + ra)
        // the glass: a milky band with a bright rim on each edge
        arc.strokeWidth = sw
        arc.color = if (day) Color.argb(70, 255, 255, 255) else Color.argb(55, 235, 244, 255)
        c.drawArc(rect, g.start, g.dir * g.sweep, false, arc)
        // the part showing the value is a little brighter, with a cool tint
        if (g.live && g.hi > g.lo) {
            arc.color = if (day) Color.argb(120, 120, 200, 225) else Color.argb(105, 215, 238, 255)
            c.drawArc(rect, g.start + g.dir * g.lo * g.sweep, g.dir * (g.hi - g.lo) * g.sweep, false, arc)
        }
        arc.strokeWidth = maxOf(1.5f, g.r * 0.012f)
        arc.color = if (day) Color.argb(150, 255, 255, 255) else Color.argb(120, 255, 255, 255)
        val ro = ra + sw / 2
        rect2.set(g.cx - ro, g.cy - ro, g.cx + ro, g.cy + ro)
        c.drawArc(rect2, g.start, g.dir * g.sweep, false, arc)
        val ri = ra - sw / 2
        rect2.set(g.cx - ri, g.cy - ri, g.cx + ri, g.cy + ri)
        arc.color = if (day) Color.argb(90, 255, 255, 255) else Color.argb(70, 255, 255, 255)
        c.drawArc(rect2, g.start, g.dir * g.sweep, false, arc)
        // numbers on the band
        for ((f, label) in scale) text(label, g.x(ra, f), g.y(ra, f) + g.r * 0.032f, g.r * 0.090f, g.ink, numTf())
        // the marker: a small blue triangle outside the ticks, pointing in
        if (g.live) {
            val a = Math.toRadians((g.start + g.dir * g.sweep * shown).toDouble())
            val ux = Math.cos(a).toFloat(); val uy = Math.sin(a).toFloat()
            val rt = g.r * 0.90f; val rb = g.r * 1.03f; val hw = g.r * 0.05f
            needle.reset()
            needle.moveTo(g.cx + ux * rt, g.cy + uy * rt)
            needle.lineTo(g.cx + ux * rb - uy * hw, g.cy + uy * rb + ux * hw)
            needle.lineTo(g.cx + ux * rb + uy * hw, g.cy + uy * rb - ux * hw)
            needle.close()
            arc.style = Paint.Style.FILL
            arc.color = if (day) Color.parseColor("#1F6FE0") else Color.parseColor("#4C9BFF")
            c.drawPath(needle, arc)
            arc.style = Paint.Style.STROKE
        }
        drawReadouts(c, g, 0.42f, 0.04f, 0.30f, 0.66f)
        if (title == "POWER") {
            if (iconLevel == 0) drawBatteryChartIcon(c, g.cx + g.r * 0.12f, g.cy - g.r * 0.86f, g.r * 0.44f)
            else drawBatteryChartIcon(c, g.cx + g.r * 0.12f, g.cy - g.r * (if (iconLevel == 2) 0.98f else 0.91f), g.r * 0.40f)
        }
    }

    /** A rounded glass tile holding a battery with a rising line, above the power gauge in the Glass skin. */
    private fun drawBatteryChartIcon(c: Canvas, cx: Float, cy: Float, s: Float) {
        val line = if (day) Color.parseColor("#0B7C95") else Color.parseColor("#7FE6F5")
        arc.style = Paint.Style.FILL
        arc.color = if (day) Color.argb(90, 255, 255, 255) else Color.argb(60, 200, 235, 245)
        rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        c.drawRoundRect(rect, s * 0.22f, s * 0.22f, arc)
        arc.style = Paint.Style.STROKE
        arc.strokeWidth = maxOf(1.5f, s * 0.03f); arc.color = line
        c.drawRoundRect(rect, s * 0.22f, s * 0.22f, arc)
        arc.strokeWidth = maxOf(1.5f, s * 0.045f); arc.strokeJoin = Paint.Join.ROUND; arc.strokeCap = Paint.Cap.ROUND
        val bl = cx - s * 0.30f; val br = cx + s * 0.30f; val bt = cy - s * 0.18f; val bb = cy + s * 0.28f
        rect2.set(bl, bt, br, bb)
        c.drawRoundRect(rect2, s * 0.06f, s * 0.06f, arc)
        c.drawLine(cx - s * 0.17f, bt - s * 0.07f, cx - s * 0.17f, bt, arc)
        c.drawLine(cx + s * 0.17f, bt - s * 0.07f, cx + s * 0.17f, bt, arc)
        needle.reset()
        needle.moveTo(bl + s * 0.07f, bb - s * 0.10f)
        needle.lineTo(cx - s * 0.10f, cy + s * 0.04f)
        needle.lineTo(cx + s * 0.02f, cy + s * 0.12f)
        needle.lineTo(br - s * 0.06f, bt + s * 0.10f)
        c.drawPath(needle, arc)
        arc.strokeJoin = Paint.Join.MITER; arc.strokeCap = Paint.Cap.BUTT
    }

    /** Skin "wedge bars": radial bars that get taller towards the high end; the lit ones show the value. */
    private fun drawWedge(c: Canvas, w: Float, h: Float) {
        val g = Geo(w, h)
        val nb = 56
        val rIn = g.r * 0.78f
        val len = g.r * 0.20f
        arc.style = Paint.Style.STROKE; arc.strokeCap = Paint.Cap.BUTT
        val rb = rIn - g.r * 0.02f
        rect.set(g.cx - rb, g.cy - rb, g.cx + rb, g.cy + rb)
        arc.strokeWidth = 2f; arc.color = g.faint
        c.drawArc(rect, g.start, g.dir * g.sweep, false, arc)
        for (i in 0 until nb) {
            val f = (i + 0.5f) / nb
            val l = len * (0.32f + 0.68f * f)
            val lit = g.live && g.hi > g.lo && f >= g.lo && f <= g.hi
            arc.strokeWidth = maxOf(2f, g.r * 0.034f)
            arc.color = if (lit) segColor(f, g.lo, g.hi) else g.faint
            c.drawLine(g.x(rIn, f), g.y(rIn, f), g.x(rIn + l, f), g.y(rIn + l, f), arc)
        }
        if (g.live) {
            arc.strokeWidth = maxOf(3f, g.r * 0.05f); arc.color = g.ink
            c.drawLine(g.x(rIn - g.r * 0.03f, shown), g.y(rIn - g.r * 0.03f, shown), g.x(rIn + len + g.r * 0.03f, shown), g.y(rIn + len + g.r * 0.03f, shown), arc)
        }
        drawReadouts(c, g, 0.44f, 0f, 0.5f, 0.70f)
    }

    override fun onDraw(c: Canvas) {
        arcCanvas = c
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gs = look.gauge
        if (gs != 0) {
            when (gs) {
                1 -> drawCluster(c, w, h)
                2 -> drawMinimal(c, w, h)
                3 -> drawAnalog(c, w, h)
                4 -> drawBold(c, w, h)
                5 -> drawRing(c, w, h)
                7 -> drawScale(c, w, h)
                8 -> drawGlass(c, w, h)
                else -> drawWedge(c, w, h)
            }
            arcCanvas = null
            return
        }
        val cx = w / 2
        val cy = h / 2
        val r = minOf(w, h) / 2 * 0.92f
        val stroke = r * 0.13f
        val rr = r - stroke / 2
        val start = if (mirrored) 75f else 105f
        val dir = if (mirrored) -1f else 1f
        val sweep = 210f
        // thin outer ring
        arc.strokeWidth = 2f
        arc.color = if (day) Color.argb(70, 20, 32, 46) else Color.argb(60, 255, 255, 255)
        rect.set(cx - r - stroke * 0.5f, cy - r - stroke * 0.5f, cx + r + stroke * 0.5f, cy + r + stroke * 0.5f)
        c.drawArc(rect, start - dir * 6f, dir * (sweep + 12f), false, arc)
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        val n = 48
        val gap = 1.2f
        // while sweeping, the arc fills from its very start (the whole regen area included) up to the sweep position
        val lo = if (sweeping) 0f else if (has) minOf(zeroFrac, shown) else 0f
        val hi = if (sweeping) shown else if (has) maxOf(zeroFrac, shown) else 0f
        for (i in 0 until n) {
            val f0 = i.toFloat() / n
            val f1 = (i + 1).toFloat() / n
            val a0 = f0 * sweep + gap / 2
            val a1 = f1 * sweep - gap / 2
            arc.strokeWidth = stroke
            arc.color = if (day) Color.argb(34, 20, 32, 46) else Color.argb(46, 255, 255, 255)
            c.drawArc(rect, start + dir * a0, dir * (a1 - a0), false, arc)
            val s0 = maxOf(a0, lo * sweep)
            val s1 = minOf(a1, hi * sweep)
            if (has && s1 > s0) {
                val rel = if (hi > lo) (((f0 + f1) / 2 - lo) / (hi - lo)).coerceIn(0f, 1f) else 0f
                val mid = (f0 + f1) / 2
                val col = if (sweeping && zeroFrac > 0f) {
                    if (mid < zeroFrac) {
                        val k = 1f - mid / zeroFrac
                        Color.rgb((46 + (31 - 46) * k).toInt(), (204 + (163 - 204) * k).toInt(), (113 + (122 - 113) * k).toInt())
                    } else colorAt(((mid - zeroFrac) / maxOf(hi - zeroFrac, 0.001f)).coerceIn(0f, 1f))
                } else if (shown < zeroFrac) {
                    // regen: green fading to teal, away from the zero mark
                    val k = 1f - rel
                    Color.rgb((46 + (31 - 46) * k).toInt(), (204 + (163 - 204) * k).toInt(), (113 + (122 - 113) * k).toInt())
                } else colorAt(rel)
                arc.strokeWidth = stroke * 1.6f
                arc.color = Color.argb(55, Color.red(col), Color.green(col), Color.blue(col))
                c.drawArc(rect, start + dir * s0, dir * (s1 - s0), false, arc)
                arc.strokeWidth = stroke
                arc.color = col
                c.drawArc(rect, start + dir * s0, dir * (s1 - s0), false, arc)
            }
        }
        val grey = if (day) Color.argb(165, 20, 32, 46) else Color.argb(150, 255, 255, 255)
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        text(topLabel, cx, cy - r * 0.60f, r * 0.085f, grey, light)
        text(topValue, cx, cy - r * 0.48f, r * 0.12f, ink, lightItalic)
        text(title, cx, cy - r * 0.25f, r * 0.13f, grey, lightItalic)
        if (!hold) text(valueText, cx, cy + r * 0.13f, r * 0.40f * 0.84f, ink, lightItalic)
        text(unit, cx, cy + r * 0.34f, r * 0.14f, Color.parseColor("#2AA8FF"), medium)
        text(bottomLabel, cx, cy + r * 0.50f, r * 0.085f, grey, light)
        text(bottomValue, cx, cy + r * 0.62f, r * 0.12f, bottomColor ?: ink, lightItalic)
        drawHoldAndReady(c, cx, cy - r * 0.01f, r)
        val bf = barFrac
        if (bf != null) {
            val bw = r * 0.5f
            val by = cy + r * 0.70f
            arc.style = Paint.Style.FILL
            arc.color = if (day) Color.argb(34, 20, 32, 46) else Color.argb(50, 255, 255, 255)
            rect.set(cx - bw / 2, by, cx + bw / 2, by + r * 0.045f)
            c.drawRoundRect(rect, 6f, 6f, arc)
            arc.color = Color.parseColor("#2AA8FF")
            rect.set(cx - bw / 2, by, cx - bw / 2 + bw * bf.coerceIn(0f, 1f), by + r * 0.045f)
            c.drawRoundRect(rect, 6f, 6f, arc)
            arc.style = Paint.Style.STROKE
        }
        arcCanvas = null
    }
}

/** A traffic light: three lamps in a dark housing, with the active one lit. */
class TrafficLightView(ctx: Context) : View(ctx) {
    private var lit = 0     // 1 red, 2 amber, 3 green
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cols = intArrayOf(Color.parseColor("#FF3B3B"), Color.parseColor("#FFB020"), Color.parseColor("#2ECC71"))

    fun setColor(c: Int) { if (c != lit) { lit = c; invalidate() } }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.argb(235, 18, 22, 30)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, w * 0.3f, w * 0.3f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.argb(90, 255, 255, 255)
        rect.set(1f, 1f, w - 1f, h - 1f)
        c.drawRoundRect(rect, w * 0.3f, w * 0.3f, p)
        p.style = Paint.Style.FILL
        val r = w * 0.30f
        for (i in 0..2) {
            val cy = h * (0.19f + 0.31f * i)
            val on = lit == i + 1
            if (on) {
                p.color = Color.argb(70, Color.red(cols[i]), Color.green(cols[i]), Color.blue(cols[i]))
                c.drawCircle(w / 2, cy, minOf(r * 1.45f, cy - 2f), p)
            }
            p.color = if (on) cols[i] else Color.argb(60, Color.red(cols[i]), Color.green(cols[i]), Color.blue(cols[i]))
            c.drawCircle(w / 2, cy, r, p)
        }
    }
}

/** A row of titled readouts under a line that runs across the top and tapers down at both ends.
 *  Press and hold a readout, then drag it sideways to move it. The + at the left opens the list of extra readouts;
 *  while it is open each readout has a minus that removes it, and readouts can be dropped in from the list.
 *  The number of slots stays fixed ([maxCount]). */
class StatusStripView(ctx: Context) : View(ctx) {
    var day = false
    var plain = false     // no outline or panel, just the readouts and dividers
    var maxCount = 7
    var showPlus = false
    var onChange: ((List<String>) -> Unit)? = null
    var onDoubleTap: ((String) -> Unit)? = null
    var onPlus: (() -> Unit)? = null
    var editMode = false
        set(v) { field = v; invalidate() }
    private var items: List<Pair<String, String>> = emptyList()     // every readout that exists
    private var order = mutableListOf<String>()                     // the ones that are showing, in order
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val div = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = android.graphics.Path()
    private val rect = RectF()
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private var dragTitle: String? = null
    private var dragX = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapAt = 0L
    private var lastTapTitle: String? = null
    private var dropCol = -1
    private val longPress = Runnable { startDrag() }

    companion object {
        /** True while a readout is being dragged (the dashboard's swipe gesture stays out of the way). */
        @Volatile var dragging = false
    }

    init {
        // readouts dragged in from the list of extras
        setOnDragListener { _, e ->
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED ->
                    e.clipDescription?.label?.toString() == "stat" && editMode
                android.view.DragEvent.ACTION_DRAG_ENTERED, android.view.DragEvent.ACTION_DRAG_LOCATION -> { dropCol = colAt(e.x); invalidate(); true }
                android.view.DragEvent.ACTION_DRAG_EXITED -> { dropCol = -1; invalidate(); true }
                android.view.DragEvent.ACTION_DROP -> {
                    dropCol = -1
                    val t = e.clipData?.getItemAt(0)?.text?.toString()
                    invalidate()
                    t != null && accept(t, e.x)
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> { dropCol = -1; invalidate(); true }
                else -> true
            }
        }
    }

    /** [saved] is the user's choice (null if they never changed it); [defaults] is what shows otherwise. */
    fun setShown(saved: List<String>?, defaults: List<String>) { order = (saved ?: defaults).distinct().take(maxCount).toMutableList(); invalidate() }

    fun setItems(list: List<Pair<String, String>>) {
        if (list != items) { items = list; invalidate() }
    }

    fun allTitles(): List<String> = items.map { it.first }
    fun shownTitles(): List<String> = shown().map { it.first }

    private fun shown(): List<Pair<String, String>> {
        val byTitle = items.associateBy { it.first }
        return order.mapNotNull { byTitle[it] }.take(maxCount)
    }

    private fun commit(list: List<String>) { order = list.toMutableList(); onChange?.invoke(list); invalidate() }

    /** Put [title] in at the end if there is a free slot. */
    fun addFirstFree(title: String): Boolean {
        val cur = shownTitles()
        if (cur.size >= maxCount || title in cur || items.none { it.first == title }) return false
        commit(cur + title)
        return true
    }

    /** Dropped at x: insert there when there is room, otherwise replace the readout under the finger. */
    private fun accept(title: String, x: Float): Boolean {
        val cur = shownTitles().toMutableList()
        if (title in cur || items.none { it.first == title }) return false
        val col = colAt(x)
        if (cur.size < maxCount) cur.add(col.coerceAtMost(cur.size), title)
        else cur[col.coerceIn(0, cur.size - 1)] = title
        commit(cur)
        return true
    }

    private fun remove(title: String) { commit(shownTitles().filter { it != title }) }

    private fun geometry(): FloatArray {            // x0, column width, taper
        val w = width.toFloat()
        val h = height.toFloat()
        val taper = if (plain) 0f else minOf(h * 0.55f, w * 0.06f)
        val plusW = if (showPlus && !plain) h * 0.5f else 0f
        val x0 = (if (plain) 0f else taper * 0.6f) + plusW
        val right = if (plain) w else w - taper * 0.6f
        return floatArrayOf(x0, (right - x0) / maxOf(maxCount, 1), taper)
    }

    private fun colAt(x: Float): Int {
        val g = geometry()
        return ((x - g[0]) / g[1]).toInt().coerceIn(0, maxOf(maxCount - 1, 0))
    }

    private fun startDrag() {
        val list = shown()
        if (list.isEmpty() || downX < geometry()[0]) return
        val col = colAt(downX)
        if (col >= list.size) return
        order = list.map { it.first }.toMutableList()
        dragTitle = order[col]
        dragX = downX
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun endDrag() {
        dragTitle = null
        dragging = false
        commit(order.toList())
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val slop = 10f * resources.displayMetrics.density
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false
                postDelayed(longPress, 450)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (dragTitle != null) {
                    dragX = e.x
                    val cur = order.indexOf(dragTitle)
                    val tgt = colAt(dragX).coerceIn(0, order.size - 1)
                    if (cur >= 0 && tgt != cur) { order.removeAt(cur); order.add(tgt, dragTitle!!) }
                    invalidate()
                } else if (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop) {
                    moved = true
                    removeCallbacks(longPress)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragTitle != null) endDrag()
                else if (e.actionMasked == android.view.MotionEvent.ACTION_UP && !moved) {
                    val g = geometry()
                    val list = shown()
                    if (showPlus && !plain && e.x < g[0]) { onPlus?.invoke(); return true }
                    val i = colAt(e.x)
                    if (i in list.indices) {
                        val title = list[i].first
                        val inRight = e.x > g[0] + g[1] * (i + 0.55f)
                        if (editMode && inRight) { remove(title); return true }
                        val now = System.currentTimeMillis()
                        if (title == lastTapTitle && now - lastTapAt < 450) { lastTapTitle = null; onDoubleTap?.invoke(title) }
                        else { lastTapTitle = title; lastTapAt = now }
                    }
                }
            }
        }
        return true
    }

    private fun fit(s: String, size: Float, maxW: Float, tf: Typeface, color: Int, x: Float, y: Float, c: Canvas) {
        txt.typeface = tf; txt.color = color; txt.textSize = size
        val w = txt.measureText(s)
        if (w > maxW) txt.textSize = size * maxW / w
        c.drawText(s, x, y, txt)
    }

    private val ico = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icoPath = android.graphics.Path()
    private val icoCut = android.graphics.Path()

    /** A soft cloud with its flat base on [base], [sz] wide, centred on [cx]. */
    private fun cloud(c: Canvas, cx: Float, base: Float, sz: Float, top: Int, bottom: Int) {
        val ch = sz * 0.52f
        ico.shader = LinearGradient(0f, base - ch, 0f, base, top, bottom, android.graphics.Shader.TileMode.CLAMP)
        ico.style = Paint.Style.FILL
        rect.set(cx - sz * 0.5f, base - ch * 0.46f, cx + sz * 0.5f, base)
        c.drawRoundRect(rect, ch * 0.23f, ch * 0.23f, ico)
        c.drawCircle(cx - sz * 0.17f, base - ch * 0.52f, ch * 0.36f, ico)
        c.drawCircle(cx + sz * 0.12f, base - ch * 0.62f, ch * 0.46f, ico)
        c.drawCircle(cx + sz * 0.33f, base - ch * 0.36f, ch * 0.26f, ico)
        ico.shader = null
    }

    /** Stands in for the solar reading when there is no sun: a crescent moon at night, clouds otherwise. */
    private fun drawSkyIcon(c: Canvas, cx: Float, h: Float) {
        val cy = h * 0.66f
        val sz = h * 0.44f
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 20 || hour < 6) {
            // a full moon: halo, a warm pale disc, the dark seas of the near side (soft edged) and a bright crater with rays
            val r = sz * 0.40f
            val my = cy + sz * 0.04f
            ico.style = Paint.Style.FILL
            ico.shader = android.graphics.RadialGradient(cx, my, r * 2.2f, Color.argb(if (day) 0 else 110, 255, 244, 200), Color.argb(0, 255, 244, 200), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, my, r * 2.2f, ico)
            ico.shader = android.graphics.RadialGradient(cx - r * 0.30f, my - r * 0.30f, r * 1.6f,
                intArrayOf(if (day) Color.parseColor("#F7E3A0") else Color.parseColor("#FFF9DC"), if (day) Color.parseColor("#E8CF86") else Color.parseColor("#F4E8B8"), if (day) Color.parseColor("#C9AE63") else Color.parseColor("#D8C98F")),
                floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, my, r, ico)
            c.save()
            icoPath.reset(); icoPath.addCircle(cx, my, r, android.graphics.Path.Direction.CW)
            c.clipPath(icoPath)
            fun sea(x: Float, y: Float, rad: Float, a: Int) {
                ico.shader = android.graphics.RadialGradient(cx + x * r, my + y * r, rad * r,
                    intArrayOf(Color.argb(a, 92, 96, 112), Color.argb(a * 2 / 3, 92, 96, 112), Color.argb(0, 92, 96, 112)),
                    floatArrayOf(0f, 0.65f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(cx + x * r, my + y * r, rad * r, ico)
            }
            sea(-0.38f, -0.38f, 0.34f, 190)   // Imbrium
            sea(0.08f, -0.42f, 0.24f, 185)    // Serenitatis
            sea(0.30f, -0.05f, 0.28f, 190)    // Tranquillitatis
            sea(0.66f, -0.30f, 0.15f, 170)    // Crisium
            sea(-0.62f, 0.05f, 0.30f, 170)    // Procellarum
            sea(-0.22f, 0.42f, 0.22f, 175)    // Nubium
            sea(0.10f, 0.15f, 0.16f, 150)     // Vapors / Medii
            // a bright crater with rays near the south
            ico.shader = android.graphics.RadialGradient(cx - r * 0.10f, my + r * 0.66f, r * 0.30f, Color.argb(255, 255, 255, 245), Color.argb(0, 255, 255, 245), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx - r * 0.10f, my + r * 0.66f, r * 0.30f, ico)
            // gentle shading toward the rim so it reads as a sphere
            ico.shader = android.graphics.RadialGradient(cx, my, r, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(if (day) 70 else 90, 60, 52, 30)), floatArrayOf(0f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, my, r, ico)
            ico.shader = null
            c.restore()
        } else {
            // a darker cloud behind and to the right, a lighter one in front
            cloud(c, cx + sz * 0.22f, cy + sz * 0.10f, sz * 0.80f,
                if (day) Color.parseColor("#8FA3B8") else Color.parseColor("#8197AE"), if (day) Color.parseColor("#6F859C") else Color.parseColor("#5F7690"))
            cloud(c, cx - sz * 0.10f, cy + sz * 0.34f, sz * 0.92f,
                if (day) Color.parseColor("#FFFFFF") else Color.parseColor("#F4F8FC"), if (day) Color.parseColor("#C9D6E4") else Color.parseColor("#B9C9DB"))
        }
    }

    private fun drawItem(c: Canvas, p: Pair<String, String>, cx: Float, colW: Float, h: Float, ink: Int, grey: Int) {
        fit((if (p.first == "HV Cap") "HV Cap Rem" else p.first).uppercase(), h * 0.19f, colW * 0.92f, light, grey, cx, h * 0.40f, c)
        if (p.first == "Solar now" && (p.second == "--" || Regex("0(\\.0+)? W").matches(p.second))) drawSkyIcon(c, cx, h)
        else fit(p.second, h * 0.36f, colW * 0.92f, medium, ink, cx, h * 0.82f, c)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val list = shown()
        if (w <= 0f || h <= 0f) return
        val g = geometry()
        val x0 = g[0]
        val colW = g[1]
        val taper = g[2]
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        val grey = if (day) Color.argb(160, 20, 32, 46) else Color.argb(150, 255, 255, 255)
        val look = Skins.look
        val accent = look.accent
        val flat = look.stripFlat
        // outline: down the left side, across the top, down the right side; fading towards the ends
        path.reset()
        path.moveTo(0f, h - 2f)
        path.lineTo(taper, 3f)
        path.lineTo(w - taper, 3f)
        path.lineTo(w, h - 2f)
        if (flat) { path.reset(); path.moveTo(0f, 3f); path.lineTo(w, 3f) }
        line.strokeWidth = if (flat) 2f else 3f
        line.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, look.accent2, accent, Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent))),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
        val panel = android.graphics.Path(path).apply { lineTo(0f, h - 2f); close() }
        fill.shader = LinearGradient(0f, 0f, 0f, h,
            if (day) intArrayOf(Color.argb(28, Color.red(look.accentDay), Color.green(look.accentDay), Color.blue(look.accentDay)), Color.argb(0, Color.red(look.accentDay), Color.green(look.accentDay), Color.blue(look.accentDay)))
            else intArrayOf(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent))),
            null, android.graphics.Shader.TileMode.CLAMP)
        if (!plain) { if (!flat) c.drawPath(panel, fill); c.drawPath(path, line) }
        line.shader = null
        // the + that opens the list of extra readouts (turns into an x while it is open)
        if (showPlus && !plain) {
            val cx = taper * 0.6f + (x0 - taper * 0.6f) * 0.5f
            val cy = h * 0.6f
            val r = h * 0.11f
            line.color = accent
            line.strokeWidth = 2.5f
            c.drawCircle(cx, cy, r * 1.6f, line)
            c.save()
            c.rotate(if (editMode) 45f else 0f, cx, cy)
            c.drawLine(cx - r, cy, cx + r, cy, line)
            c.drawLine(cx, cy - r, cx, cy + r, line)
            c.restore()
        }
        div.color = if (day) Color.argb(40, 20, 32, 46) else Color.argb(45, 255, 255, 255)
        var dragged: Pair<String, String>? = null
        for ((i, p) in list.withIndex()) {
            if (p.first == dragTitle) { dragged = p; continue }
            drawItem(c, p, x0 + colW * (i + 0.5f), colW, h, ink, grey)
            if (i > 0) c.drawLine(x0 + colW * i, h * 0.28f, x0 + colW * i, h * 0.90f, div)
        }
        if (editMode) {
            // empty slots, and a minus on each readout to remove it
            line.color = if (day) Color.argb(110, 20, 60, 120) else Color.argb(120, 42, 168, 255)
            line.strokeWidth = 1.5f
            for (i in list.size until maxCount) {
                rect.set(x0 + colW * i + colW * 0.08f, h * 0.18f, x0 + colW * (i + 1) - colW * 0.08f, h * 0.94f)
                c.drawRoundRect(rect, 12f, 12f, line)
            }
            val br = minOf(h * 0.13f, colW * 0.14f)
            for ((i, p) in list.withIndex()) {
                if (p.first == dragTitle) continue
                val bx = x0 + colW * (i + 1) - br * 1.6f
                val by = h * 0.24f
                fill.shader = null
                fill.color = Color.parseColor("#E53935")
                c.drawCircle(bx, by, br, fill)
                line.color = Color.WHITE
                line.strokeWidth = 2f
                c.drawLine(bx - br * 0.55f, by, bx + br * 0.55f, by, line)
            }
        }
        if (dropCol >= 0) {
            fill.shader = null
            fill.color = if (day) Color.argb(50, 42, 120, 200) else Color.argb(70, 42, 168, 255)
            rect.set(x0 + colW * dropCol + colW * 0.04f, h * 0.12f, x0 + colW * (dropCol + 1) - colW * 0.04f, h * 0.96f)
            c.drawRoundRect(rect, 12f, 12f, fill)
        }
        // the readout being dragged follows the finger, lifted on a highlight
        dragged?.let { p ->
            val cx = dragX.coerceIn(x0 + colW * 0.5f, x0 + colW * (list.size - 0.5f))
            fill.shader = null
            fill.color = if (day) Color.argb(40, 42, 120, 200) else Color.argb(60, 42, 168, 255)
            rect.set(cx - colW * 0.48f, h * 0.12f, cx + colW * 0.48f, h * 0.96f)
            c.drawRoundRect(rect, 12f, 12f, fill)
            drawItem(c, p, cx, colW, h, ink, grey)
        }
    }
}

/** The battery charge level: a square-ended bar, red at 0 % to green at 100 %, with "BATTERY" centred in it and the percentage beside it. */
class ChargeBarView(ctx: Context) : View(ctx) {
    var day = false
    var frac: Float? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.14f }
    private val rect = RectF()
    private val line3 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // red at 0 %, through amber and yellow, to green at 100 %
    private val stopPos = floatArrayOf(0f, 0.25f, 0.55f, 1f)
    private val stopCol = intArrayOf(Color.parseColor("#FF3B4E"), Color.parseColor("#FFB020"), Color.parseColor("#E3D43A"), Color.parseColor("#2ECC71"))

    private fun colorAt(f: Float): Int {
        val x = f.coerceIn(0f, 1f)
        for (i in 1 until stopPos.size) if (x <= stopPos[i]) {
            val k = (x - stopPos[i - 1]) / (stopPos[i] - stopPos[i - 1])
            val a = stopCol[i - 1]; val b = stopCol[i]
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
        }
        return stopCol.last()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val f = frac
        // the bar runs the full width, so it lines up with the trip readouts above it
        val barW = w
        val bh = h * 0.76f
        val top = h * 0.05f
        val bot = top + bh
        val capW = minOf(bh * 0.5f, barW * 0.2f)
        val clamp = android.graphics.Shader.TileMode.CLAMP
        // soft shadow on the "floor" under the cylinder
        for (k in 0 until 5) {
            val gr = (k + 1) * h * 0.03f
            p.shader = null
            p.color = Color.argb(if (day) 18 else 30, 0, 0, 0)
            rect.set(barW * 0.05f + gr, bot + h * 0.01f, barW * 0.95f - gr, bot + h * 0.01f + gr * 0.9f)
            c.drawOval(rect, p)
        }
        // the cylinder's outline: a body with an elliptical cap at each end
        val body = android.graphics.Path()
        body.addRect(capW, top, barW - capW, bot, android.graphics.Path.Direction.CW)
        rect.set(0f, top, capW * 2f, bot); body.addOval(rect, android.graphics.Path.Direction.CW)
        rect.set(barW - capW * 2f, top, barW, bot); body.addOval(rect, android.graphics.Path.Direction.CW)
        c.save()
        c.clipPath(body)
        // empty glass
        p.shader = null
        p.color = if (day) Color.argb(40, 20, 32, 46) else Color.argb(48, 190, 210, 255)
        rect.set(0f, top, barW, bot)
        c.drawRect(rect, p)
        if (f != null && f > 0f) {
            val fw = barW * f.coerceIn(0f, 1f)
            val tr = IntArray(stopCol.size) { Color.argb(185, Color.red(stopCol[it]), Color.green(stopCol[it]), Color.blue(stopCol[it])) }
            p.shader = LinearGradient(0f, 0f, barW, 0f, tr, stopPos, clamp)
            rect.set(0f, top, fw, bot)
            c.drawRect(rect, p)
            p.shader = null
        }
        // round shading over the whole tube: dark edge, bright highlight a third of the way down, dark underside
        p.shader = LinearGradient(0f, top, 0f, bot,
            intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(10, 0, 0, 0), Color.argb(110, 255, 255, 255), Color.argb(22, 255, 255, 255),
                Color.argb(0, 0, 0, 0), Color.argb(95, 0, 0, 0), Color.argb(170, 0, 0, 0)),
            floatArrayOf(0f, 0.10f, 0.24f, 0.36f, 0.55f, 0.85f, 1f), clamp)
        rect.set(0f, top, barW, bot)
        c.drawRect(rect, p)
        // darken the two ends so they curve away
        p.shader = LinearGradient(0f, 0f, capW * 1.2f, 0f, Color.argb(95, 0, 0, 0), Color.argb(0, 0, 0, 0), clamp)
        rect.set(0f, top, capW * 1.2f, bot); c.drawRect(rect, p)
        p.shader = LinearGradient(barW - capW * 1.2f, 0f, barW, 0f, Color.argb(0, 0, 0, 0), Color.argb(95, 0, 0, 0), clamp)
        rect.set(barW - capW * 1.2f, top, barW, bot); c.drawRect(rect, p)
        p.shader = null
        c.restore()
        // the end of the charge, drawn as a cut across the tube
        if (f != null && f > 0.02f && f < 0.995f) {
            val fw = barW * f.coerceIn(0f, 1f)
            line3.color = Color.argb(110, 0, 0, 0); line3.strokeWidth = 1.5f
            rect.set(fw - capW * 0.5f, top, fw + capW * 0.5f, bot)
            c.drawArc(rect, -90f, 180f, false, line3)
        }
        // faint marks inside the bar at 30 % and 20 %
        p.color = if (day) Color.argb(55, 20, 32, 46) else Color.argb(75, 255, 255, 255)
        for (m in floatArrayOf(0.20f, 0.30f)) {
            rect.set(barW * m - 0.75f, top, barW * m + 0.75f, bot)
            c.drawRect(rect, p)
        }
        // "BATTERY" and the percentage, together, centred in the bar
        val label = if (f == null) "--" else "${Math.round(f.coerceIn(0f, 1f) * 100)}%"
        cap.textAlign = Paint.Align.LEFT
        t.textAlign = Paint.Align.LEFT
        cap.textSize = bh * 0.46f
        t.textSize = bh * 0.58f
        var gap = bh * 0.40f
        var total = cap.measureText("BATTERY") + gap + t.measureText(label)
        if (total > barW * 0.92f) {
            val k = barW * 0.92f / total
            cap.textSize *= k; t.textSize *= k; gap *= k
            total = cap.measureText("BATTERY") + gap + t.measureText(label)
        }
        val ink = if (day && (f ?: 0f) < 0.5f) Color.argb(235, 20, 32, 46) else Color.argb(248, 255, 255, 255)
        cap.color = ink; t.color = ink
        val shadow = Color.argb(if (day) 70 else 150, 0, 0, 0)
        cap.setShadowLayer(bh * 0.12f, 0f, 0f, shadow)
        t.setShadowLayer(bh * 0.12f, 0f, 0f, shadow)
        val x0 = (barW - total) / 2f
        val my = top + bh / 2f
        val base = my - (cap.descent() + cap.ascent()) / 2f
        c.drawText("BATTERY", x0, base, cap)
        c.drawText(label, x0 + cap.measureText("BATTERY") + gap, my - (t.descent() + t.ascent()) / 2f, t)
    }
}

/** The time with seconds, and the date under it when there is room. */
class DashClockView(ctx: Context) : View(ctx) {
    var day = false
    var alignRight = false
    var textW = 0f                                    // how wide the time text is drawn (the Update Available button sits beside it)
    private val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    private val tf = java.text.SimpleDateFormat(if (is24) "HH:mm:ss" else "h:mm:ss", java.util.Locale.getDefault())
    private val af = java.text.SimpleDateFormat("a", java.util.Locale.getDefault())
    private val df = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastSec = -1L

    /** Called often; redraws once a second. */
    fun tick() {
        val sec = System.currentTimeMillis() / 1000
        if (sec != lastSec) { lastSec = sec; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = java.util.Date()
        val look = Skins.look
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        val grey = if (day) Color.argb(170, 20, 32, 46) else Color.argb(165, 255, 255, 255)
        val tfc = Typeface.create("sans-serif-light", if (look.italic) Typeface.ITALIC else Typeface.NORMAL)
        val withDate = h >= 52f * resources.displayMetrics.density
        val timeH = if (withDate) h * 0.70f else h
        tp.typeface = tfc; tp.color = ink
        var size = timeH * 0.64f
        tp.textSize = size
        val timeStr = tf.format(now)
        val ap = if (is24) "" else af.format(now)
        var tw = tp.measureText(timeStr)
        val apGap = size * 0.12f
        var aw = if (is24) 0f else tp.measureText(ap) * 0.38f
        val total = tw + (if (is24) 0f else apGap + aw)
        if (total > w) { size *= w / total; tp.textSize = size; tw = tp.measureText(timeStr); aw = if (is24) 0f else tp.measureText(ap) * 0.38f }
        val base = timeH * 0.80f
        textW = tw + (if (is24) 0f else size * 0.12f + aw)
        val tx = if (alignRight) w - (tw + (if (is24) 0f else size * 0.12f + aw)) else 0f
        c.drawText(timeStr, tx, base, tp)
        if (!is24) {
            tp.textSize = size * 0.38f; tp.color = grey
            c.drawText(ap, tx + tw + size * 0.12f, base, tp)
        }
        if (withDate) {
            dp2.typeface = tfc; dp2.color = grey
            dp2.textSize = minOf(h * 0.26f, size * 0.45f)
            val ds = df.format(now)
            val dw = dp2.measureText(ds)
            if (dw > w) dp2.textSize = dp2.textSize * w / dw
            c.drawText(ds, if (alignRight) w - minOf(dw, w) else 0f, h * 0.96f, dp2)
        }
    }
}

/** One readout of the trip panel: an icon, a caption, the value and its unit. */
data class TripCell(val icon: Int, val label: String, val value: String, val unit: String = "", val tint: Int? = null)

/**
 * The trip readouts as a card: a title, then a grid ([cols] columns) of readouts, each with a small icon and caption
 * above a large value and a smaller unit. Double-tap resets the trip.
 */
class TripPanelView(ctx: Context) : View(ctx) {
    var day = false
    var cols = 2
    var title: String? = null
    var onReset: (() -> Unit)? = null
    private var cells: List<TripCell> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rect = RectF()
    private val path = android.graphics.Path()
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val taps = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onDoubleTap(e: android.view.MotionEvent): Boolean { onReset?.invoke(); return true }
    })

    fun setCells(list: List<TripCell>) { if (list != cells) { cells = list; invalidate() } }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean = taps.onTouchEvent(e)

    /** Text from x, shrunk to fit [maxW]; returns its width. */
    private fun put(s: String, size: Float, maxW: Float, tf: Typeface, color: Int, x: Float, y: Float, c: Canvas): Float {
        txt.typeface = tf; txt.color = color; txt.textSize = size
        val w = txt.measureText(s)
        if (w > maxW) txt.textSize = size * maxW / w
        c.drawText(s, x, y, txt)
        return minOf(w, maxW)
    }

    /** Small line icons drawn in a box of size [s] with its top left at ([x], [y]). */
    private fun icon(c: Canvas, kind: Int, x: Float, y: Float, s: Float, color: Int) {
        line.color = color; fill.color = color
        line.strokeWidth = maxOf(1.5f, s * 0.09f)
        val cx = x + s / 2; val cy = y + s / 2
        when (kind) {
            0 -> { // road
                c.drawLine(x + 0.26f * s, y + s, x + 0.42f * s, y, line)
                c.drawLine(x + 0.74f * s, y + s, x + 0.58f * s, y, line)
                c.drawLine(cx, y + 0.12f * s, cx, y + 0.30f * s, line)
                c.drawLine(cx, y + 0.46f * s, cx, y + 0.64f * s, line)
                c.drawLine(cx, y + 0.80f * s, cx, y + 0.96f * s, line)
            }
            1 -> { // clock
                c.drawCircle(cx, cy, 0.44f * s, line)
                c.drawLine(cx, cy, cx, cy - 0.26f * s, line)
                c.drawLine(cx, cy, cx + 0.20f * s, cy + 0.10f * s, line)
            }
            2 -> { // stopwatch
                c.drawCircle(cx, cy + 0.08f * s, 0.38f * s, line)
                c.drawLine(cx - 0.12f * s, y + 0.03f * s, cx + 0.12f * s, y + 0.03f * s, line)
                c.drawLine(cx, y + 0.03f * s, cx, y + 0.14f * s, line)
                c.drawLine(cx, cy + 0.08f * s, cx + 0.16f * s, cy - 0.08f * s, line)
            }
            3 -> { // speedometer
                rect.set(x + 0.04f * s, y + 0.10f * s, x + 0.96f * s, y + 1.02f * s)
                c.drawArc(rect, 160f, 220f, false, line)
                c.drawLine(cx, y + 0.56f * s, cx + 0.22f * s, y + 0.30f * s, line)
            }
            4 -> { // bolt
                path.reset()
                path.moveTo(x + 0.58f * s, y); path.lineTo(x + 0.20f * s, y + 0.56f * s); path.lineTo(x + 0.48f * s, y + 0.56f * s)
                path.lineTo(x + 0.40f * s, y + s); path.lineTo(x + 0.80f * s, y + 0.42f * s); path.lineTo(x + 0.52f * s, y + 0.42f * s); path.close()
                c.drawPath(path, fill)
            }
            5 -> { // leaf
                path.reset()
                path.moveTo(x + 0.14f * s, y + 0.86f * s)
                path.quadTo(x + 0.04f * s, y + 0.22f * s, x + 0.86f * s, y + 0.10f * s)
                path.quadTo(x + 0.96f * s, y + 0.76f * s, x + 0.14f * s, y + 0.86f * s)
                c.drawPath(path, line)
                c.drawLine(x + 0.14f * s, y + 0.86f * s, x + 0.56f * s, y + 0.44f * s, line)
            }
            7 -> { // regen: a circular arrow
                rect.set(x + 0.08f * s, y + 0.08f * s, x + 0.92f * s, y + 0.92f * s)
                c.drawArc(rect, 40f, 280f, false, line)
                path.reset()
                path.moveTo(x + 0.60f * s, y + 0.02f * s); path.lineTo(x + 0.92f * s, y + 0.30f * s); path.lineTo(x + 0.52f * s, y + 0.34f * s); path.close()
                c.drawPath(path, fill)
            }
            else -> { // car
                rect.set(x, y + 0.36f * s, x + s, y + 0.76f * s)
                c.drawRoundRect(rect, 0.12f * s, 0.12f * s, line)
                path.reset()
                path.moveTo(x + 0.20f * s, y + 0.36f * s); path.lineTo(x + 0.32f * s, y + 0.12f * s)
                path.lineTo(x + 0.68f * s, y + 0.12f * s); path.lineTo(x + 0.80f * s, y + 0.36f * s)
                c.drawPath(path, line)
                c.drawCircle(x + 0.26f * s, y + 0.80f * s, 0.10f * s, fill)
                c.drawCircle(x + 0.74f * s, y + 0.80f * s, 0.10f * s, fill)
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || cells.isEmpty()) return
        val d = resources.displayMetrics.density
        val look = Skins.look
        val accent = if (day) look.accentDay else look.accent
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        val grey = if (day) Color.argb(160, 20, 32, 46) else Color.argb(160, 255, 255, 255)
        val rows = (cells.size + cols - 1) / cols
        // the card
        val rad = 10f * d
        fill.color = if (day) Color.argb(170, (Color.red(accent) * 0.4f + 255 * 0.6f).toInt(), (Color.green(accent) * 0.4f + 255 * 0.6f).toInt(), (Color.blue(accent) * 0.4f + 255 * 0.6f).toInt()) else Color.argb(100, 8, 14, 34)   // Day mode: tinted with the skin colour
        rect.set(1f, 1f, w - 1f, h - 1f)
        c.drawRoundRect(rect, rad, rad, fill)
        line.color = Color.argb(if (day) 120 else 130, Color.red(accent), Color.green(accent), Color.blue(accent))
        line.strokeWidth = 1.5f * d
        c.drawRoundRect(rect, rad, rad, line)
        // title
        val titleH = if (title != null) minOf(h * 0.20f, 24f * d) else 0f
        val padX = w * 0.045f
        title?.let {
            txt.typeface = medium; txt.textSize = titleH * 0.66f
            val tw = minOf(txt.measureText(it), w - 2 * padX)
            put(it, titleH * 0.66f, w - 2 * padX, medium, ink, (w - tw) / 2f, titleH * 0.86f, c)
        }
        val cw = w / cols
        val ch = (h - titleH - 4f) / rows
        val div = if (day) Color.argb(40, 20, 32, 46) else Color.argb(50, 255, 255, 255)
        line.color = div; line.strokeWidth = 1f
        for (k in 1 until cols) c.drawLine(cw * k, titleH + ch * 0.10f, cw * k, h - ch * 0.10f, line)
        for (r in 1 until rows) c.drawLine(w * 0.04f, titleH + ch * r, w * 0.96f, titleH + ch * r, line)
        // one label size and one value size for every cell: the largest that lets the longest of each fit
        val inner = cw * 0.84f
        val isz = minOf(ch * 0.30f, 22f * d)
        var ls = isz * 0.66f
        for (cell in cells) {
            txt.typeface = light; txt.textSize = ls
            val lw = txt.measureText(cell.label.uppercase())
            val room = inner - isz * 1.4f
            if (lw > room) ls *= room / lw
        }
        var vs = ch * 0.40f
        for (cell in cells) {
            txt.typeface = medium; txt.textSize = vs
            val vw = txt.measureText(cell.value)
            txt.typeface = light; txt.textSize = vs * 0.48f
            val uw = if (cell.unit.isEmpty()) 0f else txt.measureText(cell.unit)
            val total = vw + (if (cell.unit.isEmpty()) 0f else vs * 0.14f) + uw
            if (total > inner) vs *= inner / total
        }
        for ((i, cell) in cells.withIndex()) {
            val r = i / cols
            val k = i % cols
            val mid = cw * k + cw / 2f
            val top = titleH + ch * r
            // icon and caption, left aligned
            txt.typeface = light; txt.textSize = ls
            val lw = txt.measureText(cell.label.uppercase())
            val gw = isz * 1.35f + lw
            val lx = cw * k + cw * 0.10f   // icons line up on the left of every cell
            icon(c, cell.icon, lx, top + ch * 0.10f, isz, cell.tint ?: accent)
            put(cell.label.uppercase(), ls, 1e6f, light, grey, lx + isz * 1.35f, top + ch * 0.10f + isz * 0.78f, c)
            // value and unit, centred together
            val base = top + ch * 0.90f
            txt.typeface = medium; txt.textSize = vs
            val vw2 = txt.measureText(cell.value)
            txt.typeface = light; txt.textSize = vs * 0.48f
            val uw2 = if (cell.unit.isEmpty()) 0f else txt.measureText(cell.unit)
            val g2 = if (cell.unit.isEmpty()) 0f else vs * 0.14f
            val vx = mid - (vw2 + g2 + uw2) / 2f
            txt.typeface = medium; txt.textSize = vs; txt.color = ink
            c.drawText(cell.value, vx, base, txt)
            if (cell.unit.isNotEmpty()) {
                txt.typeface = light; txt.textSize = vs * 0.48f; txt.color = grey
                c.drawText(cell.unit, vx + vw2 + g2, base, txt)
            }
        }
    }
}

/** A speed limit sign: a US/Canada rectangle, or a Euro/UK round sign with a red ring. */
class SpeedSignView(ctx: Context) : View(ctx) {
    var number = ""
    var euro = false
    var metric = true      // Canada says MAXIMUM (km/h); the US says SPEED LIMIT (mph)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun fitText(c: Canvas, s: String, cx: Float, base: Float, size: Float, maxW: Float, tf: Typeface) {
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = tf; p.color = Color.BLACK
        p.textSize = size
        val w = p.measureText(s)
        if (w > maxW) p.textSize = size * maxW / w
        c.drawText(s, cx, base, p)
    }

    override fun onDraw(c: Canvas) {
        if (number.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        if (euro) {
            val d = minOf(w, h)
            val cx = w / 2
            val cy = h / 2
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            c.drawCircle(cx, cy, d / 2, p)
            p.style = Paint.Style.STROKE; p.color = Color.parseColor("#D8202A"); p.strokeWidth = d * 0.13f
            c.drawCircle(cx, cy, d / 2 - d * 0.065f, p)
            val size = if (number.length >= 3) d * 0.34f else d * 0.46f
            fitText(c, number, cx, cy + size * 0.35f, size, d * 0.66f, cond)
        } else {
            val sw = h * 0.80f
            val x0 = (w - sw) / 2
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            rect.set(x0, 0f, x0 + sw, h)
            c.drawRoundRect(rect, sw * 0.12f, sw * 0.12f, p)
            p.style = Paint.Style.STROKE; p.color = Color.BLACK; p.strokeWidth = sw * 0.05f
            rect.set(x0 + sw * 0.07f, h * 0.05f, x0 + sw * 0.93f, h * 0.95f)
            c.drawRoundRect(rect, sw * 0.08f, sw * 0.08f, p)
            val cx = w / 2
            if (metric) {
                fitText(c, "MAXIMUM", cx, h * 0.27f, h * 0.13f, sw * 0.64f, bold)
            } else {
                fitText(c, "SPEED", cx, h * 0.25f, h * 0.15f, sw * 0.78f, bold)
                fitText(c, "LIMIT", cx, h * 0.40f, h * 0.15f, sw * 0.78f, bold)
            }
            fitText(c, number, cx, h * 0.84f, h * 0.38f, sw * 0.78f, bold)
        }
    }
}

/** A picture drawn to fill its bounds (cropped, never stretched), with a dark layer on top so text stays readable. */
/** Shows a picture under a fixed 9:16 frame; drag and pinch move it. [render] gives exactly the framed part as 1080 x 1920. */
class CropView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
    private val frame = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f }
    private var minS = 1f; private var s = 1f; private var tx = 0f; private var ty = 0f
    private var lx = 0f; private var ly = 0f; private var dragging = false
    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val ns = (s * d.scaleFactor).coerceIn(minS, minS * 6f)
            val k = ns / s
            tx = d.focusX - (d.focusX - tx) * k; ty = d.focusY - (d.focusY - ty) * k
            s = ns; clamp(); invalidate(); return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val m = 16f * resources.displayMetrics.density
        val aw = w - 2 * m; val ah = h - 2 * m
        val fw: Float; val fh: Float
        if (aw / ah > 1080f / 1920f) { fh = ah; fw = ah * 1080f / 1920f } else { fw = aw; fh = aw * 1920f / 1080f }
        frame.set((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
        minS = maxOf(frame.width() / bmp.width, frame.height() / bmp.height)
        reset()
    }

    fun reset() {
        s = minS
        tx = frame.centerX() - bmp.width * s / 2f; ty = frame.centerY() - bmp.height * s / 2f
        clamp(); invalidate()
    }

    private fun clamp() {
        tx = tx.coerceIn(frame.right - bmp.width * s, frame.left)
        ty = ty.coerceIn(frame.bottom - bmp.height * s, frame.top)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; dragging = true }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            android.view.MotionEvent.ACTION_POINTER_UP -> { dragging = false }
            android.view.MotionEvent.ACTION_MOVE -> if (dragging && e.pointerCount == 1 && !scaler.isInProgress) {
                tx += e.x - lx; ty += e.y - ly; lx = e.x; ly = e.y; clamp(); invalidate()
            } else if (e.pointerCount == 1 && !scaler.isInProgress) { lx = e.x; ly = e.y; dragging = true }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.save(); c.translate(tx, ty); c.scale(s, s); c.drawBitmap(bmp, 0f, 0f, paint); c.restore()
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, frame.top, dim); c.drawRect(0f, frame.bottom, w, h, dim)
        c.drawRect(0f, frame.top, frame.left, frame.bottom, dim); c.drawRect(frame.right, frame.top, w, frame.bottom, dim)
        c.drawRect(frame, line)
    }

    fun render(): Bitmap {
        val l = (frame.left - tx) / s; val t = (frame.top - ty) / s
        val src = android.graphics.Rect(l.toInt().coerceAtLeast(0), t.toInt().coerceAtLeast(0),
            (l + frame.width() / s).toInt().coerceAtMost(bmp.width), (t + frame.height() / s).toInt().coerceAtMost(bmp.height))
        val out = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, src, android.graphics.Rect(0, 0, 1080, 1920), paint)
        return out
    }
}

class CoverDrawable(private val bmp: android.graphics.Bitmap, private val scrim: Int, private val owner: View? = null) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = android.graphics.Rect()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        // In split screen the window is only a part of the screen: show that part of the picture as it looks full screen (not the picture re-fitted to the small window)
        var fw = 0; var fh = 0; var ox = 0; var oy = 0
        val act = owner?.context as? android.app.Activity
        if (owner != null && act != null && act.isInMultiWindowMode && android.os.Build.VERSION.SDK_INT >= 30) try {
            val fb = act.windowManager.maximumWindowMetrics.bounds
            val loc = IntArray(2); owner.getLocationOnScreen(loc)
            if (fb.width() > 0 && fb.height() > 0) { fw = fb.width(); fh = fb.height(); ox = loc[0] - fb.left; oy = loc[1] - fb.top }
        } catch (e: Exception) { fw = 0 }
        val refW = if (fw > 0) fw else b.width()
        val refH = if (fw > 0) fh else b.height()
        val boundsRatio = refW.toFloat() / refH
        val cropW: Float
        val cropH: Float
        if (boundsRatio > bw / bh) { cropW = bw; cropH = bw / boundsRatio } else { cropH = bh; cropW = bh * boundsRatio }
        val l0 = (bw - cropW) / 2f
        val t0 = (bh - cropH) / 2f
        if (fw > 0) {
            val k = cropW / fw                                  // bitmap pixels per screen pixel
            val l = (l0 + ox * k).coerceIn(0f, bw - 1f); val t = (t0 + oy * k).coerceIn(0f, bh - 1f)
            val r = (l + b.width() * k).coerceIn(l + 1f, bw); val bt = (t + b.height() * k).coerceIn(t + 1f, bh)
            src.set(l.toInt(), t.toInt(), r.toInt(), bt.toInt())
        } else {
            src.set(l0.toInt(), t0.toInt(), (l0 + cropW).toInt(), (t0 + cropH).toInt())
        }
        c.drawBitmap(bmp, src, b, paint)
        c.drawColor(scrim)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/** The auto-hold symbol: an H in a circle between two arcs. */
class HoldView(ctx: Context) : View(ctx) {
    var day = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val x = w / 2
        val y = h / 2
        val rho = minOf(w, h) * 0.30f
        val sym = if (day) Color.parseColor("#B5532F") else Color.parseColor("#F2CBB8")
        p.style = Paint.Style.STROKE
        p.strokeWidth = rho * 0.22f
        p.color = sym
        c.drawCircle(x, y, rho, p)
        rect.set(x - rho * 1.45f, y - rho * 1.45f, x + rho * 1.45f, y + rho * 1.45f)
        p.strokeCap = Paint.Cap.ROUND
        c.drawArc(rect, 145f, 70f, false, p)
        c.drawArc(rect, -35f, 70f, false, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = rho * 1.25f
        c.drawText("H", x, y + rho * 0.43f, p)
    }
}

/** A caution symbol: a rounded amber warning triangle with an exclamation mark. */
class CautionSymbolView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val rect = RectF()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val amber = Color.parseColor("#FFB300")
        path.reset()
        path.moveTo(w * 0.5f, h * 0.10f); path.lineTo(w * 0.93f, h * 0.86f); path.lineTo(w * 0.07f, h * 0.86f); path.close()
        p.style = Paint.Style.FILL_AND_STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeWidth = w * 0.10f; p.color = amber
        c.drawPath(path, p)
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#1B2030")
        rect.set(w * 0.455f, h * 0.36f, w * 0.545f, h * 0.62f)
        c.drawRoundRect(rect, w * 0.045f, w * 0.045f, p)
        c.drawCircle(w * 0.5f, h * 0.73f, w * 0.052f, p)
    }
}

/**
 * The Climate screen: a full-window, read-only picture of what the car's A/C unit reports (CAN 0x373, steering wheel 0x386).
 * Nothing here sends anything to the car; the controls are state indicators only.
 */
class ClimateScreenView(ctx: Context) : View(ctx) {
    var snap: CanSnapshot = CanSnapshot()
        set(v) { field = v; invalidate() }
    var fahrenheit = false
        set(v) { field = v; invalidate() }
    var onClose: (() -> Unit)? = null
    var fanControl = false                       // test: show - / + for the fan (sends CAN frames)
        set(v) { field = v; invalidate() }
    var onFan: ((Int) -> Unit)? = null
    var onWheel: (() -> Unit)? = null
    var onRearDefrost: ((Boolean) -> Unit)? = null     // true = switch the rear window heater on
    private var fanPend = -1; private var fanPendAt = 0L
    private val wheelRect = RectF()
    private val fanMinus = RectF()
    private val fanPlus = RectF()
    private val fanSegs = ArrayList<RectF>()
    private val tempBtns = ArrayList<RectF>()          // driver -, driver +, passenger -, passenger +
    var onTempTap: (() -> Unit)? = null
    var onTemp: ((Boolean, Int) -> Unit)? = null     // driver?, +1 / -1 (half a degree each)
    var onMode: ((Int) -> Unit)? = null              // air direction 1..5
    var onButton: ((Int) -> Unit)? = null            // 0 A/C, 1 defrost, 2 recirculation, 3 sync, 4 auto
    private val btnRects = ArrayList<RectF>()          // the function tiles that are buttons: A/C (0), defrost (2), recirculation (4), sync (5), auto (6)
    private val modeRects = ArrayList<RectF>()
    // left to right, in every layout: Feet + Screen sits beside Screen, and the whole row mirrors for right-hand drive
    private val modeOrder: IntArray get() = if (rhd) intArrayOf(3, 2, 1, 4, 5) else intArrayOf(5, 4, 1, 2, 3)
    private val tempBtnInfo = ArrayList<Pair<Boolean, Int>>()
    var compact = false                               // Layout 2: a short panel under the cards: the two temperatures and the function tiles only
        set(v) { if (field != v) { field = v; invalidate() } }
    var mini = false                                  // Layout 2, a 2/3 split: the whole climate screen drawn smaller (about 390 dp worth of layout) so it fits the short panel
        set(v) { if (field != v) { field = v; invalidate() } }
    var l2 = false                                    // Layout 2: one Close button, dials follow the drive side, seat heating buttons
        set(v) { if (field != v) { field = v; invalidate() } }
    var rhd = false                                   // right-hand drive: the driver's dial is on the right (Layout 2 only)
        set(v) { if (field != v) { field = v; invalidate() } }
    var car3d = true                                  // Settings (Layout 2): the 3D Ocean seen from above instead of the drawn car; the cabin graphic stays
    var l1Car3d = false                               // Settings (Layout 1): the 3D car replaces the Trip Information, and the climate screen uses it too
    private val use3d get() = (l2 && car3d) || l1Car3d
    private val rhdSide get() = rhd && (l2 || l1Car3d)
    private val C3F = minOf(300f / 311.76f, 676f * 0.88f / 695.88f)      // the 3D car's picture squeezed into the same box as the drawn car
    private val c3Dest = RectF(-5.88f, -166.55f, 305.88f, 529.33f)
    private val c3Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val c3Mask by lazy { android.graphics.BitmapFactory.decodeResource(resources, R.drawable.climate_mask) }
    private val c3Trim by lazy { android.graphics.BitmapFactory.decodeResource(resources, R.drawable.climate_trim) }
    private val c3BodyRaw by lazy { android.graphics.BitmapFactory.decodeResource(resources, R.drawable.climate_body) }
    private var c3Body: Bitmap? = null; private var c3BodyIdx = -1
    private fun c3BodyFor(idx: Int): Bitmap {
        c3Body?.let { if (c3BodyIdx == idx) return it }
        val b0 = c3BodyRaw; val w = b0.width; val h = b0.height
        val px = IntArray(w * h); b0.getPixels(px, 0, w, 0, 0, w, h)
        val pc = paints[idx].second
        val pr = Color.red(pc); val pg = Color.green(pc); val pb = Color.blue(pc)
        for (i in px.indices) {
            val c = px[i]; val a = c ushr 24
            if (a == 0) continue
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val sat = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            if (sat > 0.2f || lum < 0.3f) continue
            val wgt = (((lum - 0.3f) / 0.12f).coerceIn(0f, 1f)) * (1f - ((sat - 0.1f) / 0.1f).coerceIn(0f, 1f))
            val shade = (lum / 0.62f).coerceIn(0.2f, 1.45f)
            val nr = (pr * shade).coerceIn(0f, 255f); val ng = (pg * shade).coerceIn(0f, 255f); val nb = (pb * shade).coerceIn(0f, 255f)
            px[i] = (a shl 24) or ((r + (nr - r) * wgt).toInt() shl 16) or ((g + (ng - g) * wgt).toInt() shl 8) or (b + (nb - b) * wgt).toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); out.setPixels(px, 0, w, 0, 0, w, h)
        c3Body = out; c3BodyIdx = idx
        return out
    }
    var hideSeatIcons = false                         // Settings: no seat buttons outside the car (the seats in the car drawing still work)
        set(v) { if (field != v) { field = v; invalidate() } }
    var onSeat: ((Int, Int) -> Unit)? = null          // seat heating: which (0 driver, 1 passenger, 2 rear left, 3 rear right), new level 0..3
    private val seatRects = ArrayList<RectF>()
    private val seatWho = ArrayList<Int>()
    private val seatOver = IntArray(4) { -1 }
    private val seatOverAt = LongArray(4)
    var floating = false                              // full-screen mode: a rounded panel instead of the whole window
        set(v) { field = v; invalidate() }
    var bgColor = Color.parseColor("#0B0F1A")          // solid, from the dashboard's current background
    var bgTopColor = Color.parseColor("#050A14")       // the top of that gradient
    var onPower: ((Boolean) -> Unit)? = null        // true = turn the climate system on, false = shut it down
    private val powerRect = RectF()
    // two fixed gradients of the car drawing, made once instead of on every frame
    private val gradBody by lazy { LinearGradient(0f, -10f, 0f, 460f, 0xFF14213B.toInt(), 0xFF0B1426.toInt(), Shader.TileMode.CLAMP) }
    private val gradRoof by lazy { LinearGradient(0f, 64f, 0f, 134f, 0xFF26375A.toInt(), 0xFF121D35.toInt(), Shader.TileMode.CLAMP) }
    private var lastFanTap = 0L
    private val fanRect = RectF(); private val rearRect = RectF()
    private var noticeText = ""; private var noticeAt = 0L

    /** A short message that stays 3 s, then fades out. */
    private fun showNotice(t: String) { noticeText = t; noticeAt = System.currentTimeMillis(); invalidate() }
    private fun drawNotice(cx: Float, cy: Float, maxW: Float) {
        if (noticeText.isEmpty()) return
        val age = System.currentTimeMillis() - noticeAt
        if (age > 3500) { noticeText = ""; return }
        val a = if (age < 3000) 1f else 1f - (age - 3000) / 500f
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = dp(14f)
        val tw = minOf(p.measureText(noticeText), maxW - dp(40f))
        val r = RectF(cx - tw / 2 - dp(18f), cy - dp(22f), cx + tw / 2 + dp(18f), cy + dp(22f))
        p.style = Paint.Style.FILL; p.color = Color.argb((235 * a).toInt(), 18, 22, 34); canvas.drawRoundRect(r, dp(22f), dp(22f), p)
        stroke(Color.argb((200 * a).toInt(), Color.red(on), Color.green(on), Color.blue(on)), 1.5f); canvas.drawRoundRect(r, dp(22f), dp(22f), p)
        fitText(noticeText, cx, cy, 14f, Color.argb((255 * a).toInt(), 255, 255, 255), true, tw)
        postInvalidateOnAnimation()
    }

    private val baseD = ctx.resources.displayMetrics.density
    private var d = baseD                              // the drawing scale: baseD, or smaller while [mini] draws the full screen into a short panel
    private fun dp(v: Float) = v * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var accent = Color.parseColor("#3B9DFF")                         // the selected skin's accent: lit buttons, fan level, power button
    private val on get() = accent
    /** Day theme (a light background): the climate readings are drawn near-black instead of white. */
    private val dayBg get() = (0.299f * Color.red(bgColor) + 0.587f * Color.green(bgColor) + 0.114f * Color.blue(bgColor)) > 140f
    private fun ink(a: Int = 255): Int = if (dayBg) Color.argb(a, 14, 18, 24) else Color.argb(a, 255, 255, 255)
    private val glassBase get() = mixC(Color.WHITE, accent, 0.28f)    // unlit glass buttons pick up a little of the skin colour
    private val warm = Color.parseColor("#FF8A3D")
    private val cool = Color.parseColor("#4DA3FF")
    private val off = Color.argb(120, 255, 255, 255)
    private val closeRect = RectF()
    private val closeRect2 = RectF()
    private lateinit var canvas: Canvas
    private val path = Path()

    private fun temp(c: Double?): String = when {
        c == null -> "--"
        fahrenheit -> String.format("%.0f\u00B0", c * 9 / 5 + 32)
        else -> String.format("%.1f\u00B0", c)
    }

    private fun text(s: String, x: Float, y: Float, sizeDp: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) {
        p.style = Paint.Style.FILL; p.color = color; p.textSize = dp(sizeDp); p.textAlign = align
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(s, x, y + p.textSize * 0.35f, p)
    }
    /** Like text(), but shrinks the size so the string never wider than maxW pixels. */
    private fun fitText(s: String, x: Float, y: Float, sizeDp: Float, color: Int, bold: Boolean, maxW: Float) {
        p.textSize = dp(sizeDp); p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val wd = p.measureText(s)
        text(s, x, y, if (wd > maxW && wd > 0f) sizeDp * maxW / wd else sizeDp, color, bold)
    }
    private fun stroke(color: Int, w: Float) { p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = dp(w); p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND }
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, p)
    private fun card(r: RectF, title: String) {
        p.style = Paint.Style.FILL; p.color = Color.argb(40, 255, 255, 255)
        canvas.drawRoundRect(r, dp(18f), dp(18f), p)
        text(title, r.left + dp(14f), r.top + dp(16f), 12f, Color.argb(170, 255, 255, 255), true, Paint.Align.LEFT)
    }

    // ---------------------------------------------------------------- icons (all drawn, r = radius)
    private fun wave(x: Float, y: Float, h: Float, up: Boolean = true) {
        path.reset(); val s = if (up) -1f else 1f
        path.moveTo(x, y)
        path.cubicTo(x - h * 0.45f, y + s * h * 0.30f, x + h * 0.45f, y + s * h * 0.70f, x, y + s * h)
        canvas.drawPath(path, p)
    }
    private fun snowflake(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f)
        for (k in 0 until 3) {
            val a = Math.toRadians(k * 60.0 + 90).toFloat()
            val dx = Math.cos(a.toDouble()).toFloat() * r; val dy = Math.sin(a.toDouble()).toFloat() * r
            line(cx - dx, cy - dy, cx + dx, cy + dy)
            for (sgn in listOf(-1f, 1f)) {
                val bx = cx + dx * 0.62f * sgn; val by = cy + dy * 0.62f * sgn
                val pa = a + Math.toRadians(90.0).toFloat()
                val px = Math.cos(pa.toDouble()).toFloat() * r * 0.22f; val py = Math.sin(pa.toDouble()).toFloat() * r * 0.22f
                line(bx - px, by - py, bx + px, by + py)
            }
        }
    }
    private fun fan(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f); canvas.drawCircle(cx, cy, r * 0.16f, p)
        for (k in 0 until 3) {
            canvas.save(); canvas.rotate(k * 120f, cx, cy)
            path.reset(); path.moveTo(cx, cy - r * 0.16f)
            path.cubicTo(cx - r * 0.7f, cy - r * 0.5f, cx - r * 0.3f, cy - r * 1.0f, cx + r * 0.2f, cy - r * 0.95f)
            path.cubicTo(cx + r * 0.35f, cy - r * 0.6f, cx + r * 0.2f, cy - r * 0.35f, cx, cy - r * 0.16f)
            canvas.drawPath(path, p); canvas.restore()
        }
    }
    private fun carSide(cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx - r, cy + r * 0.35f); path.lineTo(cx - r, cy)
        path.lineTo(cx - r * 0.55f, cy - r * 0.08f); path.lineTo(cx - r * 0.3f, cy - r * 0.5f)
        path.lineTo(cx + r * 0.4f, cy - r * 0.5f); path.lineTo(cx + r * 0.75f, cy - r * 0.05f)
        path.lineTo(cx + r, cy); path.lineTo(cx + r, cy + r * 0.35f); path.close()
        canvas.drawPath(path, p)
    }
    private fun recirc(cx: Float, cy: Float, r: Float, col: Int, outside: Boolean) {
        stroke(col, 2f); carSide(cx, cy + r * 0.15f, r)
        if (outside) { line(cx - r * 0.55f, cy + r * 0.2f, cx + r * 0.5f, cy + r * 0.2f); line(cx + r * 0.25f, cy, cx + r * 0.5f, cy + r * 0.2f); line(cx + r * 0.25f, cy + r * 0.4f, cx + r * 0.5f, cy + r * 0.2f) }
        else {
            canvas.drawArc(RectF(cx - r * 0.45f, cy - r * 0.05f, cx + r * 0.45f, cy + r * 0.45f), 200f, 280f, false, p)
            line(cx + r * 0.38f, cy + r * 0.02f, cx + r * 0.46f, cy + r * 0.2f); line(cx + r * 0.38f, cy + r * 0.02f, cx + r * 0.2f, cy + r * 0.05f)
        }
    }
    private fun airClean(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f)
        for (k in -1..1) { canvas.drawArc(RectF(cx - r, cy + k * r * 0.55f - r * 0.18f, cx, cy + k * r * 0.55f + r * 0.18f), 180f, 180f, false, p); canvas.drawArc(RectF(cx, cy + k * r * 0.55f - r * 0.18f, cx + r, cy + k * r * 0.55f + r * 0.18f), 0f, -180f, false, p) }
        // sparkle
        val sx = cx + r * 0.7f; val sy = cy - r * 0.8f; val q = r * 0.3f
        line(sx - q, sy, sx + q, sy); line(sx, sy - q, sx, sy + q)
    }
    private fun defrost(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f)
        path.reset(); path.moveTo(cx - r, cy + r * 0.55f); path.lineTo(cx - r * 0.65f, cy - r * 0.15f); path.lineTo(cx + r * 0.65f, cy - r * 0.15f); path.lineTo(cx + r, cy + r * 0.55f); path.close()
        canvas.drawPath(path, p)
        for (k in -1..1) wave(cx + k * r * 0.45f, cy + r * 0.25f, r * 0.9f)
    }
    private fun heat(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f)
        for (k in -1..1) wave(cx + k * r * 0.6f, cy + r * 0.8f, r * 1.5f)
    }
    private fun wheel(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2.6f)
        canvas.drawCircle(cx, cy + r * 0.3f, r * 0.72f, p)
        stroke(col, 2f)
        line(cx - r * 0.72f, cy + r * 0.2f, cx - r * 0.22f, cy + r * 0.3f); line(cx + r * 0.72f, cy + r * 0.2f, cx + r * 0.22f, cy + r * 0.3f)
        line(cx - r * 0.12f, cy + r * 0.45f, cx - r * 0.2f, cy + r * 1.0f); line(cx + r * 0.12f, cy + r * 0.45f, cx + r * 0.2f, cy + r * 1.0f)
        canvas.drawCircle(cx, cy + r * 0.3f, r * 0.22f, p)
        for (k in -1..1) wave(cx + k * r * 0.4f, cy - r * 0.35f, r * 0.55f)
    }
    private fun rearDefrost(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f)
        canvas.drawRoundRect(RectF(cx - r, cy - r * 0.6f, cx + r, cy + r * 0.8f), r * 0.2f, r * 0.2f, p)
        for (k in -1..1) wave(cx + k * r * 0.5f, cy + r * 0.45f, r * 0.9f)
    }
    private fun powerIcon(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2.8f)
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -50f, 280f, false, p)
        line(cx, cy - r * 1.15f, cx, cy - r * 0.1f)
    }
    private fun sync(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f)
        line(cx - r, cy - r * 0.35f, cx + r, cy - r * 0.35f); line(cx + r * 0.6f, cy - r * 0.7f, cx + r, cy - r * 0.35f); line(cx + r * 0.6f, cy, cx + r, cy - r * 0.35f)
        line(cx + r, cy + r * 0.45f, cx - r, cy + r * 0.45f); line(cx - r * 0.6f, cy + r * 0.1f, cx - r, cy + r * 0.45f); line(cx - r * 0.6f, cy + r * 0.8f, cx - r, cy + r * 0.45f)
    }
    private fun remote(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f)
        canvas.drawRoundRect(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), r * 0.15f, r * 0.15f, p)
        line(cx - r * 0.15f, cy + r * 0.72f, cx + r * 0.15f, cy + r * 0.72f)
        canvas.drawArc(RectF(cx + r * 0.2f, cy - r * 0.9f, cx + r * 1.1f, cy), 300f, 120f, false, p)
    }
    private fun hwave(x1: Float, x2: Float, y: Float, amp: Float) {
        path.reset(); path.moveTo(x1, y)
        val seg = (x2 - x1) / 4
        for (i in 0 until 4) path.quadTo(x1 + seg * (i + 0.5f), y + (if (i % 2 == 0) -amp else amp), x1 + seg * (i + 1), y)
        canvas.drawPath(path, p)
    }
    private fun thick(x1: Float, y1: Float, x2: Float, y2: Float, wd: Float, col: Int) {
        p.style = Paint.Style.STROKE; p.color = col; p.strokeWidth = wd; p.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x1, y1, x2, y2, p)
    }
    /** A seated occupant (solid shapes) with wavy air lines: mode 1 face, 2 face+feet, 3 feet, 4 feet+windshield, 5 windshield. */
    private fun modeIcon(cx: Float, cy: Float, r: Float, mode: Int, col: Int) {
        val soft = Color.argb(Color.alpha(col) * 6 / 10, Color.red(col), Color.green(col), Color.blue(col))
        thick(cx + r * 0.15f, cy - r * 0.35f, cx + r * 0.32f, cy + r * 0.2f, r * 0.36f, soft)          // back
        thick(cx + r * 0.32f, cy + r * 0.25f, cx - r * 0.25f, cy + r * 0.3f, r * 0.3f, soft)           // thigh
        thick(cx - r * 0.25f, cy + r * 0.3f, cx - r * 0.3f, cy + r * 0.78f, r * 0.26f, soft)           // shin
        p.style = Paint.Style.FILL; p.color = col; canvas.drawCircle(cx + r * 0.1f, cy - r * 0.75f, r * 0.22f, p)
        val face = mode == 1 || mode == 2; val feet = mode == 2 || mode == 3 || mode == 4; val win = mode == 4 || mode == 5
        stroke(col, 1.6f)
        if (face) { hwave(cx - r * 1.05f, cx - r * 0.35f, cy - r * 0.85f, r * 0.1f); hwave(cx - r * 1.05f, cx - r * 0.35f, cy - r * 0.6f, r * 0.1f) }
        if (feet) { hwave(cx - r * 1.05f, cx - r * 0.55f, cy + r * 0.62f, r * 0.09f); hwave(cx - r * 1.05f, cx - r * 0.55f, cy + r * 0.9f, r * 0.09f) }
        if (win) {
            thick(cx - r * 0.95f, cy - r * 0.05f, cx - r * 0.5f, cy - r * 1.0f, r * 0.12f, col)
            stroke(col, 1.6f)
            wave(cx - r * 0.78f, cy - r * 0.1f, r * 0.45f); wave(cx - r * 0.58f, cy - r * 0.3f, r * 0.45f)
        }
    }
    private fun autoIcon(cx: Float, cy: Float, r: Float, col: Int) {
        stroke(col, 2f)
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, p)
        line(cx + r * 0.5f, cy - r * 0.87f, cx + r * 0.95f, cy - r * 0.35f); line(cx + r * 0.5f, cy - r * 0.87f, cx + r * 0.02f, cy - r * 0.72f)
        text("AUTO", cx, cy, 7.5f, col, true)
    }

    private fun tileGap(small: Boolean) = dp(if (small) 6f else 8f)

    private fun tile(i: Int, cols: Int, left: Float, top: Float, w: Float, h: Float, label: String, lit: Boolean, col: Int, small: Boolean, icon: (Float, Float, Float, Int) -> Unit) {
        val rr = i / cols; val cc = i % cols
        val gp = tileGap(small)
        val tw = (w - gp * (cols - 1)) / cols
        val x = left + cc * (tw + gp); val y = top + rr * (h + gp)
        val r = RectF(x, y, x + tw, y + h)
        if (i == 3) rearRect.set(r)
        val cr = dp(if (small) 14f else 20f)
        drawLiquidGlass(canvas, p, r, cr, dp(1f), if (lit) col else null, glassBase)
        icon(r.centerX(), r.top + h * 0.39f, h * 0.21f, if (lit) Color.WHITE else Color.argb(150, 255, 255, 255))
        fitText(label, r.centerX(), r.bottom - h * 0.19f, (h / d * 0.17f).coerceIn(10f, 14f), if (lit) Color.WHITE else Color.argb(160, 255, 255, 255), true, tw - dp(8f))
    }

    private fun mixC(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** A glass rounded-square button with a - or + on it (sym 0 = minus, 1 = plus). */
    private fun glassBtn(r: RectF, sym: Int) {
        val cr = r.width() * 0.28f
        drawLiquidGlass(canvas, p, r, cr, dp(1f), null, glassBase)
        val l = r.width() * 0.22f
        stroke(Color.WHITE, 2.8f)
        line(r.centerX() - l, r.centerY(), r.centerX() + l, r.centerY())
        if (sym == 1) line(r.centerX(), r.centerY() - l, r.centerX(), r.centerY() + l)
    }

    /** One temperature dial: a 270 degree ring open at the bottom that fills from blue to orange, the value inside, - / + at the ring's two ends. */
    private fun dial(x0: Float, areaW: Float, top: Float, areaH: Float, name: String, t: Double?, driver: Boolean) {
        // the ring is sized so the ring and its - / + buttons always fit inside the area given (nothing below it is touched)
        val bs = (areaH * 0.20f).coerceIn(dp(34f), dp(52f))
        // ring + glow + tick marks reach 0.827*dd below the top at the open ends; the buttons go under that
        val dd = minOf(areaH * 0.80f, areaW * 0.88f, (areaH - bs - dp(6f)) / 0.827f)
        val sw = dd * 0.075f
        val r = dd / 2f - sw * 1.6f
        val cx = x0 + areaW / 2f; val cy = top + dd / 2f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        // tick marks just outside the ring
        val ro = r + sw * 1.1f
        p.color = Color.argb(60, 255, 255, 255); p.strokeWidth = dp(1.3f); p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(1.5f), dp(9f)), 0f)
        canvas.drawArc(RectF(cx - ro, cy - ro, cx + ro, cy + ro), 135f, 270f, false, p); p.pathEffect = null
        p.color = Color.argb(28, 255, 255, 255); p.strokeWidth = sw
        canvas.drawArc(oval, 135f, 270f, false, p)
        if (t != null) {
            val f = ((t - 16.0) / 17.0).toFloat().coerceIn(0f, 1f)      // 16 or Lo = blue, 33 = red
            val sweep = 270f * f
            // colour by temperature along the arc (16..33): 16 blue, 17.5 light blue, 19 yellow, 20 orange, 26.5 deep orange, 33 red.
            // the arc covers 0..0.75 of the sweep gradient, so a temperature T sits at (T - 16) / 17 * 0.75
            val sg = SweepGradient(cx, cy,
                intArrayOf(Color.parseColor("#2F6BFF"), Color.parseColor("#4DB8FF"), Color.parseColor("#FFD93D"), Color.parseColor("#FF9A2E"), Color.parseColor("#FF6A2E"), Color.parseColor("#FF2D2D"), Color.parseColor("#FF2D2D"), Color.parseColor("#2F6BFF"), Color.parseColor("#2F6BFF")),
                floatArrayOf(0f, 0.0662f, 0.1324f, 0.1765f, 0.4632f, 0.75f, 0.92f, 0.96f, 1f))   // past the end the gradient comes back to blue, so the round start cap (which wraps around) is blue, not red
            val mtx = android.graphics.Matrix(); mtx.setRotate(135f, cx, cy); sg.setLocalMatrix(mtx)
            p.shader = sg
            p.strokeWidth = sw * 2.0f; p.alpha = 40; canvas.drawArc(oval, 135f, sweep, false, p)
            p.strokeWidth = sw * 1.45f; p.alpha = 70; canvas.drawArc(oval, 135f, sweep, false, p)
            p.strokeWidth = sw; p.alpha = 255; canvas.drawArc(oval, 135f, sweep, false, p)
            p.shader = null
            val ang = Math.toRadians(135.0 + sweep)
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            canvas.drawCircle(cx + r * Math.cos(ang).toFloat(), cy + r * Math.sin(ang).toFloat(), sw * 0.62f, p)
        }
        val dpD = dd / d
        text(name, cx, cy - dd * 0.20f, (dpD * 0.062f).coerceIn(9f, 13f), ink(if (dayBg) 230 else 190), true)
        fitText(temp(t), cx, cy + dd * 0.06f, (dpD * 0.22f).coerceIn(20f, 44f), if (t == null) off else ink(), true, dd * 0.50f)
        // - and + at the two ends of the ring
        val by = top + dd * 0.827f + dp(5f) + bs / 2f
        val minus = RectF(cx - r * 0.7071f - bs / 2, by - bs / 2, cx - r * 0.7071f + bs / 2, by + bs / 2)
        val plus = RectF(cx + r * 0.7071f - bs / 2, by - bs / 2, cx + r * 0.7071f + bs / 2, by + bs / 2)
        glassBtn(minus, 0); glassBtn(plus, 1)
        tempBtns.add(minus); tempBtns.add(plus)
        tempBtnInfo.add(Pair(driver, -1)); tempBtnInfo.add(Pair(driver, 1))
    }

    // ---------------------------------------------------------------- overhead car with animated airflow
    private fun tempColor(t: Double?): Int {
        if (t == null) return Color.parseColor("#7CF0DE")
        val st = doubleArrayOf(16.0, 17.5, 19.0, 20.0, 28.0, 33.0)
        val cs = intArrayOf(0x2F6BFF, 0x4DB8FF, 0xFFD93D, 0xFF9A2E, 0xFF6A2E, 0xFF2D2D)
        if (t <= st[0]) return Color.rgb(Color.red(cs[0]), Color.green(cs[0]), Color.blue(cs[0]))
        for (i in 1 until st.size) if (t <= st[i]) {
            val f = ((t - st[i - 1]) / (st[i] - st[i - 1])).toFloat()
            val a = cs[i - 1]; val b = cs[i]
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
        }
        return Color.rgb(Color.red(cs[5]), Color.green(cs[5]), Color.blue(cs[5]))
    }

    private val tmpR = RectF()
    // ---- paint colour: tap the car to pick one of Fisker's paints
    private val paints = CAR_PAINTS
    var paintIdx = 3
        set(v) { field = v.coerceIn(0, 13); invalidate() }
    var onPaint: ((Int) -> Unit)? = null
    private var paintOpen = false
    private val carRect = RectF()
    private val carZones = ArrayList<Pair<RectF, Int>>()     // touchable parts of the car: 0..3 a seat, 10 front window, 11 rear window, 12 steering wheel
    private var carZoneTop = 0f; private var carZoneK = 1f
    /** Remembers a touchable part of the car; the numbers are in the car's own cabin drawing units. */
    private fun carZone(x0: Float, y0: Float, x1: Float, y1: Float, id: Int) {
        val sy = 0.88f
        if (use3d) {
            // 3D car: the numbers are in its own drawing units (150 is the middle, 0 the top of the cabin drawing, -166.55 the nose)
            carZones.add(Pair(RectF(carRect.left + (150f + (x0 - 150f) * C3F) * carZoneK, carZoneTop + (y0 + 166.55f) * C3F * carZoneK, carRect.left + (150f + (x1 - 150f) * C3F) * carZoneK, carZoneTop + (y1 + 166.55f) * C3F * carZoneK), id))
            return
        }
        carZones.add(Pair(RectF(carRect.left + x0 * carZoneK, carZoneTop + (y0 + 142f) * carZoneK * sy, carRect.left + x1 * carZoneK, carZoneTop + (y1 + 142f) * carZoneK * sy), id))
    }
    private val paintRects = ArrayList<RectF>()
    private val paintPanel = RectF()
    private fun adj(c: Int, k: Float): Int {
        val t = if (k < 0f) 0 else 255; val f = Math.abs(k)
        return Color.rgb((Color.red(c) + (t - Color.red(c)) * f).toInt(), (Color.green(c) + (t - Color.green(c)) * f).toInt(), (Color.blue(c) + (t - Color.blue(c)) * f).toInt())
    }
    private fun paintPopup() {
        val c = canvas; val w = width.toFloat(); val h = height.toFloat()
        p.shader = null; p.style = Paint.Style.FILL; p.color = Color.argb(170, 0, 0, 0); c.drawRect(0f, 0f, w, h, p)
        val pw = minOf(w - dp(24f), dp(580f)); val pad = dp(16f); val cols = 7
        val cell = (pw - 2 * pad) / cols
        val sw = minOf(cell * 0.66f, dp(58f)); val rowH = sw + dp(32f); val headH = dp(40f)
        val ph = pad + headH + 2 * rowH + pad * 0.5f
        val left = (w - pw) / 2f; val top = (h - ph) / 2f
        paintPanel.set(left, top, left + pw, top + ph)
        p.color = 0xFF18203A.toInt(); c.drawRoundRect(paintPanel, dp(22f), dp(22f), p)
        stroke(Color.argb(90, 255, 255, 255), 1.2f); c.drawRoundRect(paintPanel, dp(22f), dp(22f), p)
        text("Paint colour", left + pad, top + pad + dp(10f), 18f, Color.WHITE, true, Paint.Align.LEFT)
        text(paints[paintIdx].first, left + pw - pad, top + pad + dp(10f), 14f, Color.argb(200, 255, 255, 255), false, Paint.Align.RIGHT)
        paintRects.clear()
        for (i in paints.indices) {
            val r = i / cols; val cc = i % cols
            val cx = left + pad + cell * (cc + 0.5f); val cy = top + pad + headH + rowH * r + sw / 2f
            p.style = Paint.Style.FILL; p.color = paints[i].second; c.drawCircle(cx, cy, sw / 2f, p)
            stroke(Color.argb(70, 255, 255, 255), 1.2f); c.drawCircle(cx, cy, sw / 2f, p)
            if (i == paintIdx) { stroke(on, 3f); c.drawCircle(cx, cy, sw / 2f + dp(5f), p) }
            fitText(paints[i].first, cx, cy + sw / 2f + dp(15f), 10.5f, Color.argb(215, 255, 255, 255), false, cell - dp(4f))
            paintRects.add(RectF(cx - cell / 2f, cy - sw / 2f - dp(6f), cx + cell / 2f, cy + sw / 2f + dp(28f)))
        }
    }
    private val carMat = android.graphics.Matrix()

    /** The car from above (white body, glass, dash, seats) with the air streams, coloured by the set temperatures. Drawn in a 320 x 716 box, squeezed to 88% in height. */
    private fun overheadCar(cx: Float, top: Float, areaW: Float, carH: Float, s: CanSnapshot, flow: Boolean, mode: Int) {
        val sy = 0.88f
        val k = minOf(carH / (676f * sy), areaW / 320f)
        val now = android.os.SystemClock.uptimeMillis()
        val ph = (now % 1600L) / 1600f
        val pulse = (0.65f + 0.35f * Math.sin(ph * 2.0 * Math.PI)).toFloat()
        val dt = s.climDrvT; val pt = s.climPassT
        val cL = tempColor(if (l2 && rhd) pt else dt); val cR = tempColor(if (l2 && rhd) dt else pt)
        val baseCol = paints[paintIdx].second
        val lightPaint = (0.299 * Color.red(baseCol) + 0.587 * Color.green(baseCol) + 0.114 * Color.blue(baseCol)) / 255.0 > 0.6
        val bodyCols = if (paintIdx == 3) intArrayOf(0xFFC9D3E2.toInt(), 0xFFF6F9FD.toInt(), 0xFFFFFFFF.toInt(), 0xFFF6F9FD.toInt(), 0xFFC9D3E2.toInt())
            else intArrayOf(adj(baseCol, -0.28f), adj(baseCol, 0.05f), adj(baseCol, 0.13f), adj(baseCol, 0.05f), adj(baseCol, -0.28f))
        val cC = if (dt != null && pt != null) tempColor((dt + pt) / 2.0) else if (dt != null) cL else cR
        fun sideCol(x: Float) = if (x < 149f) cL else if (x > 151f) cR else cC
        fun withA(col: Int, a: Int) = (col and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
        fun fillP(col: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = col }
        fun strokeP(col: Int, w: Float) { p.shader = null; p.style = Paint.Style.STROKE; p.color = col; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND }
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) { tmpR.set(x, y, x + w, y + h); canvas.drawRoundRect(tmpR, r, r, p) }
        fun both(x: Float, y: Float, w: Float, h: Float, r: Float, f: Int, st: Int, sw: Float) { fillP(f); rr(x, y, w, h, r); strokeP(st, sw); rr(x, y, w, h, r) }
        fun circ(x: Float, y: Float, r: Float) = canvas.drawCircle(x, y, r, p)
        fun flowShader(col: Int, up: Boolean): Shader {
            val sh = LinearGradient(0f, 0f, 0f, 64f, intArrayOf(withA(col, 31), withA(col, 217), withA(col, 31)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.REPEAT)
            carMat.setTranslate(0f, if (up) -ph * 64f else ph * 64f); sh.setLocalMatrix(carMat); return sh
        }
        fun wedge(x: Float, y: Float, x2: Float, y2: Float, w0: Float, w1: Float, sh: Shader) {
            val l = Math.hypot((x2 - x).toDouble(), (y2 - y).toDouble()).toFloat()
            val dx = (x2 - x) / l; val dy = (y2 - y) / l; val px = -dy; val py = dx
            path.reset()
            path.moveTo(x + px * w0 / 2, y + py * w0 / 2); path.lineTo(x2 + px * w1 / 2, y2 + py * w1 / 2)
            path.quadTo(x2 + dx * 10f, y2 + dy * 10f, x2 - px * w1 / 2, y2 - py * w1 / 2)
            path.lineTo(x - px * w0 / 2, y - py * w0 / 2); path.close()
            p.style = Paint.Style.FILL; p.shader = sh; canvas.drawPath(path, p); p.shader = null
        }
        fun glow(x: Float, y: Float, r: Float, col: Int) {
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(x, y, r, withA(col, (230 * pulse).toInt()), withA(col, 0), Shader.TileMode.CLAMP)
            circ(x, y, r); p.shader = null
        }
        fun mirror() {
            path.reset(); path.moveTo(24f, 190f); path.lineTo(2f, 203f); path.quadTo(-11f, 211f, -11f, 223f); path.quadTo(-9f, 234f, 3f, 233f); path.lineTo(24f, 217f); path.close()
            fillP(0xFF0B101B.toInt()); canvas.drawPath(path, p); strokeP(0xFF6F86AD.toInt(), 1f); canvas.drawPath(path, p)
            path.reset(); path.moveTo(22f, 219f); path.lineTo(4f, 232f); path.quadTo(-6f, 234f, -9f, 224f)
            strokeP(0xFFF2F6FB.toInt(), 2.4f); canvas.drawPath(path, p)
            fillP(0xFF0B101B.toInt()); rr(18f, 198f, 9f, 9f, 2f)
        }

        val carTop0 = top + (carH - 676f * sy * k) / 2f
        carRect.set(cx - 150f * k, carTop0, cx + 150f * k, carTop0 + 676f * sy * k)
        carZones.clear(); carZoneTop = carTop0; carZoneK = k
        if (use3d) {
            // ---- 3D Ocean from above over the cabin graphic: the seats, wheel and windows sit where they can be tapped
            canvas.save()
            canvas.translate(cx - 150f * k, carTop0)
            canvas.scale(k, k * sy)
            canvas.translate(150f, 0f); canvas.scale(C3F, C3F / sy); canvas.translate(-150f, 166.55f)
            val d = c3Dest
            val lay = canvas.saveLayer(d.left, d.top, d.right, d.bottom, null)
            p.style = Paint.Style.FILL; p.shader = gradBody
            canvas.drawRect(d.left, d.top, d.right, d.bottom, p); p.shader = null
            val wsP = Path()
            wsP.moveTo(36f, 64f); wsP.cubicTo(36f, 22f, 62f, -16f, 112f, -22f); wsP.quadTo(150f, -26f, 188f, -22f); wsP.cubicTo(238f, -16f, 264f, 22f, 264f, 64f); wsP.close()
            val rwP = Path(); rwP.moveTo(72f, 440f); rwP.lineTo(228f, 440f); rwP.lineTo(246f, 492f); rwP.lineTo(54f, 492f); rwP.close()
            // dash
            path.reset(); path.moveTo(38f, 64f); path.lineTo(262f, 64f); path.lineTo(262f, 122f); path.quadTo(150f, 136f, 38f, 122f); path.close()
            p.style = Paint.Style.FILL; p.shader = gradRoof; canvas.drawPath(path, p); p.shader = null
            strokeP(0xFF4A6190.toInt(), 1.4f); canvas.drawPath(path, p)
            both(58f, 78f, 56f, 10f, 3f, 0xFF0A1220.toInt(), 0xFF5B7299.toInt(), 1f)
            fillP(0xFF6F86AD.toInt()); for (vx in floatArrayOf(38f, 112f, 164f, 238f)) rr(vx, 72f, 24f, 5f, 2f); rr(137f, 62f, 26f, 5f, 2f)
            // steering wheel: round, flat along the bottom, with the Fisker badge in the middle
            val wx = if (rhdSide) 214f else 86f
            val wheelHot = s.climWheelOn()
            fun ring(r: Float, c: Float) {
                val al = Math.toDegrees(Math.asin((c / r).toDouble())).toFloat()
                path.reset(); tmpR.set(wx - r, 116f - r, wx + r, 116f + r); path.arcTo(tmpR, 180f - al, 180f + 2f * al); path.close()
            }
            if (wheelHot) { fillP(Color.argb(70, 255, 60, 60)); ring(29f, 27f); canvas.drawPath(path, p) }
            strokeP(if (wheelHot) Color.argb(235, 255, 80, 80) else 0xFF8AA0C6.toInt(), if (wheelHot) 4.6f else 4f); ring(24f, 22f); canvas.drawPath(path, p)
            strokeP(if (wheelHot) Color.argb(190, 255, 80, 80) else 0xFF5B7299.toInt(), 3f)
            canvas.drawLine(wx - 22f, 114f, wx - 9f, 114f, p); canvas.drawLine(wx + 9f, 114f, wx + 22f, 114f, p); canvas.drawLine(wx - 8f, 124f, wx + 8f, 124f, p)
            fillP(0xFF161D30.toInt()); circ(wx, 114f, 8.5f)
            strokeP(if (wheelHot) Color.argb(240, 255, 120, 120) else 0xFFC9D3E2.toInt(), 1.4f); circ(wx, 114f, 8.5f)
            tmpR.set(wx - 5.95f, 114f - 5.95f, wx + 5.95f, 114f + 5.95f)
            fillP(0xFF4DB8FF.toInt()); canvas.drawArc(tmpR, 200f, 130f, true, p)
            fillP(0xFFFF9A2E.toInt()); canvas.drawArc(tmpR, 20f, 130f, true, p)
            carZone(wx - 30f, 92f, wx + 30f, 142f, 12)
            both(135f, 83f, 30f, 47f, 5f, 0xFF0A1220.toInt(), 0xFF8AA0C6.toInt(), 1.4f)
            // console
            both(132f, 138f, 36f, 128f, 10f, 0xFF16233D.toInt(), 0xFF3D5280.toInt(), 1.2f)
            fillP(0xFF0E182C.toInt()); rr(136f, 144f, 13f, 34f, 3f); rr(151f, 144f, 13f, 34f, 3f)
            fillP(0xFF0A1220.toInt()); circ(143f, 172f, 8.5f); circ(157f, 172f, 8.5f); strokeP(0xFF4A6190.toInt(), 1f); circ(143f, 172f, 8.5f); circ(157f, 172f, 8.5f)
            both(133f, 208f, 34f, 50f, 10f, 0xFF22335A.toInt(), 0xFF44597F.toInt(), 1.2f)
            fun seat(x0: Float, y0: Float, w: Float, hh: Float, r: Float) {
                p.style = Paint.Style.FILL; p.shader = LinearGradient(x0, y0, x0 + w, y0 + hh, 0xFF2F4268.toInt(), 0xFF1A2744.toInt(), Shader.TileMode.CLAMP); rr(x0, y0, w, hh, r); p.shader = null
                strokeP(0xFF44597F.toInt(), 1.2f); rr(x0, y0, w, hh, r)
            }
            for (x0 in floatArrayOf(45f, 169f)) { seat(x0, 158f, 86f, 72f, 14f); seat(x0 + 3f, 230f, 80f, 36f, 12f); seat(x0 + 27f, 266f, 32f, 15f, 6f) }
            strokeP(Color.argb(140, 159, 184, 200), 2f); canvas.drawLine(88f, 162f, 88f, 260f, p); canvas.drawLine(212f, 162f, 212f, 260f, p)
            fillP(Color.argb(235, 74, 95, 138))
            for (hx in floatArrayOf(88f, 212f)) { tmpR.set(hx - 34f, 237f, hx + 34f, 259f); canvas.drawOval(tmpR, p); circ(hx, 270f, 10f) }
            canvas.save(); canvas.translate(0f, -36f)
            seat(46f, 324f, 68f, 66f, 12f); seat(116f, 324f, 68f, 66f, 8f); seat(186f, 324f, 68f, 66f, 12f)
            seat(46f, 390f, 68f, 30f, 10f); seat(116f, 390f, 68f, 30f, 8f); seat(186f, 390f, 68f, 30f, 10f)
            seat(64f, 420f, 32f, 13f, 6f); seat(134f, 420f, 32f, 13f, 6f); seat(204f, 420f, 32f, 13f, 6f)
            strokeP(Color.argb(128, 159, 184, 200), 2f); canvas.drawLine(80f, 328f, 80f, 418f, p); canvas.drawLine(220f, 328f, 220f, 418f, p)
            fillP(Color.argb(235, 74, 95, 138))
            for (hx in floatArrayOf(80f, 220f)) { tmpR.set(hx - 24f, 395f, hx + 24f, 413f); canvas.drawOval(tmpR, p); circ(hx, 426f, 10f) }
            canvas.restore()
            fillP(0xFF6F86AD.toInt()); rr(74f, 216f, 28f, 5f, 2f); rr(198f, 216f, 28f, 5f, 2f)
            // heated seats: a transparent red and three dots; the seat is the touch area
            val drvR = rhdSide
            fun heatDots(cxx: Float, cyy: Float, lvl: Int) {
                for (i in 0 until 3) { fillP(if (i < lvl) Color.argb(255, 255, 110, 110) else Color.argb(90, 255, 255, 255)); circ(cxx + (i - 1) * 13f, cyy, 4.6f) }
            }
            fun seatHeat3(who: Int, front: Boolean, left: Boolean) {
                val lvl = seatLevel(who, s)
                val x0 = if (front) (if (left) 45f else 169f) else (if (left) 46f else 186f)
                if (lvl > 0) {
                    fillP(Color.argb(55 + 35 * lvl, 255, 50, 50))
                    if (front) { rr(x0, 158f, 86f, 72f, 14f); rr(x0 + 3f, 230f, 80f, 36f, 12f); rr(x0 + 27f, 266f, 32f, 15f, 6f) }
                    else { rr(x0, 288f, 68f, 66f, 12f); rr(x0, 354f, 68f, 30f, 10f); rr(x0 + 18f, 384f, 32f, 13f, 6f) }
                }
                heatDots(x0 + (if (front) 43f else 34f), if (front) 196f else 322f, lvl)
                if (front) carZone(x0, 158f, x0 + 86f, 284f, who) else carZone(x0, 288f, x0 + 68f, 398f, who)
            }
            seatHeat3(if (drvR) 1 else 0, true, true); seatHeat3(if (drvR) 0 else 1, true, false)
            seatHeat3(2, false, true); seatHeat3(3, false, false)
            if (s.climMaxDefrost) { fillP(Color.argb(95, 255, 50, 50)); canvas.drawPath(wsP, p) }
            if (s.rearDefrost == true) { fillP(Color.argb(110, 255, 50, 50)); canvas.drawPath(rwP, p) }
            carZone(40f, -58f, 260f, 64f, 10); carZone(50f, 410f, 250f, 495f, 11)
            // air streams
            if (flow) {
                val faceOn = mode in 0..2; val feetOn = mode in 2..4; val winOn = mode == 4 || mode == 5
                if (faceOn) {
                    for (x in floatArrayOf(50f, 124f, 176f, 250f)) { glow(x, 78f, 14f, sideCol(x)); wedge(x, 80f, x, 134f, 10f, 44f, flowShader(sideCol(x), false)) }
                    glow(150f, 268f, 12f, cC)
                    wedge(146f, 272f, 114f, 306f, 10f, 38f, flowShader(cC, false)); wedge(154f, 272f, 186f, 306f, 10f, 38f, flowShader(cC, false))
                }
                if (feetOn) {
                    glow(86f, 126f, 16f, cL); glow(214f, 126f, 16f, cR)
                    wedge(86f, 130f, 86f, 168f, 14f, 46f, flowShader(cL, false)); wedge(214f, 130f, 214f, 168f, 14f, 46f, flowShader(cR, false))
                }
                if (winOn) {
                    glow(150f, 64f, 22f, cC)
                    canvas.save(); canvas.clipPath(wsP)
                    p.style = Paint.Style.FILL
                    p.shader = LinearGradient(0f, 64f, 0f, -24f, intArrayOf(withA(cC, (242 * pulse).toInt()), withA(cC, (128 * pulse).toInt()), withA(cC, (38 * pulse).toInt())), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, -40f, 300f, 70f, p)
                    val sw = LinearGradient(0f, 0f, 0f, 50f, intArrayOf(withA(cC, 0), withA(cC, 140), withA(cC, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.REPEAT)
                    carMat.setTranslate(0f, -ph * 50f); sw.setLocalMatrix(carMat); p.shader = sw
                    canvas.drawRect(0f, -40f, 300f, 70f, p); p.shader = null
                    canvas.restore()
                }
                postInvalidateDelayed(40)
            }
            // keep only what is inside the glass, then the 3D body over it
            c3Paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(c3Mask, null, d, c3Paint); c3Paint.xfermode = null
            canvas.restoreToCount(lay)
            c3Paint.alpha = 77; canvas.drawBitmap(c3Trim, null, d, c3Paint); c3Paint.alpha = 255
            canvas.drawBitmap(c3BodyFor(paintIdx), null, d, c3Paint)
            canvas.restore()
            p.shader = null
            return
        }
        canvas.save()
        canvas.translate(cx - 150f * k, carTop0)
        canvas.scale(k, k * sy)

        // ---- exterior: body, hood, cowl, lights, mirrors, rear panel
        path.reset()
        path.moveTo(79f, 8f); path.cubicTo(118f, 1f, 182f, 1f, 221f, 8f); path.cubicTo(252f, 15f, 271f, 44f, 279f, 90f)
        path.cubicTo(285f, 122f, 290f, 152f, 292f, 200f); path.lineTo(294f, 354f); path.lineTo(292f, 604f)
        path.cubicTo(292f, 640f, 270f, 666f, 216f, 670f); path.cubicTo(184f, 674f, 116f, 674f, 84f, 670f)
        path.cubicTo(30f, 666f, 8f, 640f, 8f, 604f); path.lineTo(6f, 354f); path.lineTo(8f, 200f)
        path.cubicTo(10f, 152f, 15f, 122f, 21f, 90f); path.cubicTo(29f, 44f, 48f, 15f, 79f, 8f); path.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(6f, 0f, 294f, 0f, bodyCols, floatArrayOf(0f, 0.18f, 0.5f, 0.82f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, p)
        p.shader = LinearGradient(0f, 0f, 0f, 676f, if (lightPaint) 0x00FFFFFF else 0x00000000, if (lightPaint) 0x599FB0C8 else 0x44000000, Shader.TileMode.CLAMP); canvas.drawPath(path, p); p.shader = null
        strokeP(if (paintIdx == 3) 0xFF8FA2BF.toInt() else adj(baseCol, -0.4f), 1.4f); canvas.drawPath(path, p)
        strokeP(0x29, 1.3f); p.color = if (lightPaint) Color.argb(41, 30, 45, 80) else Color.argb(50, 255, 255, 255)
        path.reset(); path.moveTo(94f, 14f); path.cubicTo(92f, 54f, 88f, 90f, 86f, 120f); canvas.drawPath(path, p)
        path.reset(); path.moveTo(206f, 14f); path.cubicTo(208f, 54f, 212f, 90f, 214f, 120f); canvas.drawPath(path, p)
        fillP(0xFF0F1A30.toInt())
        path.reset(); path.moveTo(16f, 174f); path.cubicTo(20f, 132f, 68f, 112f, 150f, 112f); path.cubicTo(232f, 112f, 280f, 132f, 284f, 174f); path.close(); canvas.drawPath(path, p)
        fun badge() {
            fillP(0xFF161D30.toInt()); circ(150f, 20f, 10f); strokeP(0xFFC9D3E2.toInt(), 1.2f); circ(150f, 20f, 10f)
            tmpR.set(143f, 13f, 157f, 27f)
            fillP(0xFF4DB8FF.toInt()); canvas.drawArc(tmpR, 200f, 130f, true, p)
            fillP(0xFFFF9A2E.toInt()); canvas.drawArc(tmpR, 20f, 130f, true, p)
        }
        badge()
        canvas.save(); canvas.translate(0f, 637f); badge(); canvas.restore()
        strokeP(0xFF10141F.toInt(), 3.4f)
        path.reset(); path.moveTo(82f, 11f); path.cubicTo(56f, 15f, 40f, 30f, 31f, 60f); canvas.drawPath(path, p)
        path.reset(); path.moveTo(218f, 11f); path.cubicTo(244f, 15f, 260f, 30f, 269f, 60f); canvas.drawPath(path, p)
        path.reset(); path.moveTo(106f, 8f); path.quadTo(150f, 3f, 194f, 8f); canvas.drawPath(path, p)
        canvas.save(); canvas.translate(0f, 12f); mirror()
        canvas.save(); canvas.translate(300f, 0f); canvas.scale(-1f, 1f); mirror(); canvas.restore(); canvas.restore()
        fillP(0xFF0D121C.toInt())
        path.reset(); path.moveTo(28f, 610f); path.lineTo(272f, 610f); path.cubicTo(270f, 628f, 256f, 640f, 220f, 644f); path.lineTo(80f, 644f); path.cubicTo(44f, 640f, 30f, 628f, 28f, 610f); path.close(); canvas.drawPath(path, p)
        strokeP(0xFFE0323C.toInt(), 3f)
        path.reset(); path.moveTo(38f, 638f); path.cubicTo(54f, 656f, 92f, 666f, 126f, 667f); canvas.drawPath(path, p)
        path.reset(); path.moveTo(262f, 638f); path.cubicTo(246f, 656f, 208f, 666f, 174f, 667f); canvas.drawPath(path, p)
        fillP(0xFFFF9A2E.toInt()); rr(8f, 580f, 4f, 14f, 2f); rr(288f, 580f, 4f, 14f, 2f)

        // ---- cabin (everything below sits 130 lower)
        canvas.translate(0f, 142f)
        path.reset()
        path.moveTo(52f, -10f); path.lineTo(248f, -10f); path.quadTo(280f, -10f, 280f, 40f); path.lineTo(280f, 422f); path.quadTo(280f, 460f, 248f, 460f)
        path.lineTo(52f, 460f); path.quadTo(20f, 460f, 20f, 422f); path.lineTo(20f, 40f); path.quadTo(20f, -10f, 52f, -10f); path.close()
        p.style = Paint.Style.FILL; p.shader = gradBody; canvas.drawPath(path, p); p.shader = null
        strokeP(0xFF1B2540.toInt(), 3f); canvas.drawPath(path, p)
        val wsPath = Path()
        wsPath.moveTo(34f, 63f); wsPath.cubicTo(34f, 34f, 48f, 2f, 84f, -5f); wsPath.quadTo(150f, -15f, 216f, -5f); wsPath.cubicTo(252f, 2f, 266f, 34f, 266f, 63f); wsPath.close()
        fillP(Color.argb(26, 120, 180, 255)); canvas.drawPath(wsPath, p); strokeP(Color.argb(90, 160, 200, 255), 1.2f); canvas.drawPath(wsPath, p)
        path.reset(); path.moveTo(44f, 450f); path.lineTo(256f, 450f); path.lineTo(264f, 430f); path.lineTo(36f, 430f); path.close()
        fillP(Color.argb(20, 120, 180, 255)); canvas.drawPath(path, p); strokeP(Color.argb(76, 160, 200, 255), 1.2f); canvas.drawPath(path, p)
        both(46f, 150f, 208f, 234f, 16f, Color.argb(10, 120, 180, 255), Color.argb(50, 160, 200, 255), 1f)
        both(24f, 80f, 12f, 344f, 5f, 0xFF18264A.toInt(), 0xFF2F4270.toInt(), 1f); both(264f, 80f, 12f, 344f, 5f, 0xFF18264A.toInt(), 0xFF2F4270.toInt(), 1f)
        // dash
        path.reset(); path.moveTo(38f, 64f); path.lineTo(262f, 64f); path.lineTo(262f, 122f); path.quadTo(150f, 136f, 38f, 122f); path.close()
        p.style = Paint.Style.FILL; p.shader = gradRoof; canvas.drawPath(path, p); p.shader = null
        strokeP(0xFF4A6190.toInt(), 1.4f); canvas.drawPath(path, p)
        both(58f, 78f, 56f, 10f, 3f, 0xFF0A1220.toInt(), 0xFF5B7299.toInt(), 1f)
        fillP(0xFF6F86AD.toInt()); for (vx in floatArrayOf(38f, 112f, 164f, 238f)) rr(vx, 72f, 24f, 5f, 2f); rr(137f, 62f, 26f, 5f, 2f)
        val wx = if (l2 && rhd) 214f else 86f
        val wheelHot = s.climWheelOn()
        if (wheelHot) { fillP(Color.argb(70, 255, 60, 60)); tmpR.set(wx - 26f, 104f, wx + 26f, 128f); canvas.drawOval(tmpR, p) }
        strokeP(if (wheelHot) Color.argb(235, 255, 80, 80) else 0xFF8AA0C6.toInt(), if (wheelHot) 4.2f else 3.4f); tmpR.set(wx - 22f, 109f, wx + 22f, 123f); canvas.drawOval(tmpR, p)
        fillP(if (wheelHot) Color.argb(235, 255, 80, 80) else 0xFF3D5280.toInt()); circ(wx, 116f, 5f)
        carZone(wx - 28f, 98f, wx + 28f, 134f, 12)
        both(135f, 83f, 30f, 47f, 5f, 0xFF0A1220.toInt(), 0xFF8AA0C6.toInt(), 1.4f)
        // console
        both(132f, 138f, 36f, 166f, 10f, 0xFF16233D.toInt(), 0xFF3D5280.toInt(), 1.2f)
        fillP(0xFF0E182C.toInt()); rr(136f, 144f, 13f, 34f, 3f); rr(151f, 144f, 13f, 34f, 3f)
        fillP(0xFF0A1220.toInt()); circ(143f, 172f, 8.5f); circ(157f, 172f, 8.5f); strokeP(0xFF4A6190.toInt(), 1f); circ(143f, 172f, 8.5f); circ(157f, 172f, 8.5f)
        both(133f, 238f, 34f, 62f, 10f, 0xFF22335A.toInt(), 0xFF44597F.toInt(), 1.2f)
        // seats
        fun seat(x0: Float, y0: Float, w: Float, hh: Float, r: Float) {
            p.style = Paint.Style.FILL; p.shader = LinearGradient(x0, y0, x0 + w, y0 + hh, 0xFF2F4268.toInt(), 0xFF1A2744.toInt(), Shader.TileMode.CLAMP); rr(x0, y0, w, hh, r); p.shader = null
            strokeP(0xFF44597F.toInt(), 1.2f); rr(x0, y0, w, hh, r)
        }
        for (x0 in floatArrayOf(54f, 178f)) { seat(x0, 172f, 68f, 62f, 14f); seat(x0 + 2f, 234f, 64f, 30f, 12f); seat(x0 + 18f, 264f, 32f, 14f, 6f) }
        strokeP(Color.argb(140, 159, 184, 200), 2f); canvas.drawLine(88f, 176f, 88f, 272f, p); canvas.drawLine(212f, 176f, 212f, 272f, p)
        fillP(Color.argb(235, 74, 95, 138))
        for (hx in floatArrayOf(88f, 212f)) { tmpR.set(hx - 28f, 238f, hx + 28f, 258f); canvas.drawOval(tmpR, p); circ(hx, 268f, 11f) }
        seat(46f, 324f, 68f, 66f, 12f); seat(116f, 324f, 68f, 66f, 8f); seat(186f, 324f, 68f, 66f, 12f)
        seat(46f, 390f, 68f, 30f, 10f); seat(116f, 390f, 68f, 30f, 8f); seat(186f, 390f, 68f, 30f, 10f)
        seat(64f, 420f, 32f, 13f, 6f); seat(134f, 420f, 32f, 13f, 6f); seat(204f, 420f, 32f, 13f, 6f)
        strokeP(Color.argb(128, 159, 184, 200), 2f); canvas.drawLine(80f, 328f, 80f, 418f, p); canvas.drawLine(220f, 328f, 220f, 418f, p)
        fillP(Color.argb(235, 74, 95, 138))
        for (hx in floatArrayOf(80f, 220f)) { tmpR.set(hx - 24f, 395f, hx + 24f, 413f); canvas.drawOval(tmpR, p); circ(hx, 426f, 10f) }
        fillP(0xFF6F86AD.toInt()); rr(74f, 226f, 28f, 5f, 2f); rr(198f, 226f, 28f, 5f, 2f)

        // ---- touchable parts of the car: the four heated seats (a transparent red and three small dots), the two windows and the wheel
        val drvR = l2 && rhd
        fun heatDots(cxx: Float, cyy: Float, lvl: Int) {
            for (i in 0 until 3) { fillP(if (i < lvl) Color.argb(255, 255, 110, 110) else Color.argb(90, 255, 255, 255)); circ(cxx + (i - 1) * 13f, cyy, 4.6f) }
        }
        fun seatHeat(who: Int, front: Boolean, left: Boolean) {
            val lvl = seatLevel(who, s)
            val x0 = if (front) (if (left) 54f else 178f) else (if (left) 46f else 186f)
            if (lvl > 0) {
                fillP(Color.argb(55 + 35 * lvl, 255, 50, 50))
                if (front) { rr(x0, 172f, 68f, 62f, 14f); rr(x0 + 2f, 234f, 64f, 30f, 12f); rr(x0 + 18f, 264f, 32f, 14f, 6f) }
                else { rr(x0, 324f, 68f, 66f, 12f); rr(x0, 390f, 68f, 30f, 10f); rr(x0 + 18f, 420f, 32f, 13f, 6f) }
            }
            heatDots(x0 + 34f, if (front) 208f else 358f, lvl)
            if (front) carZone(x0, 172f, x0 + 68f, 280f, who) else carZone(x0, 324f, x0 + 68f, 434f, who)
        }
        seatHeat(if (drvR) 1 else 0, true, true); seatHeat(if (drvR) 0 else 1, true, false)
        seatHeat(2, false, true); seatHeat(3, false, false)
        if (s.climMaxDefrost) { fillP(Color.argb(95, 255, 50, 50)); canvas.drawPath(wsPath, p) }
        if (s.rearDefrost == true) {
            path.reset(); path.moveTo(44f, 450f); path.lineTo(256f, 450f); path.lineTo(264f, 430f); path.lineTo(36f, 430f); path.close()
            fillP(Color.argb(110, 255, 50, 50)); canvas.drawPath(path, p)
        }
        carZone(36f, -8f, 264f, 66f, 10); carZone(36f, 430f, 264f, 452f, 11)

        // ---- air streams
        if (flow) {
            val faceOn = mode in 0..2; val feetOn = mode in 2..4; val winOn = mode == 4 || mode == 5
            if (faceOn) {
                for (x in floatArrayOf(50f, 124f, 176f, 250f)) { glow(x, 78f, 14f, sideCol(x)); wedge(x, 80f, x, 134f, 10f, 44f, flowShader(sideCol(x), false)) }
                glow(150f, 306f, 12f, cC)
                wedge(146f, 310f, 114f, 350f, 10f, 38f, flowShader(cC, false)); wedge(154f, 310f, 186f, 350f, 10f, 38f, flowShader(cC, false))
            }
            if (feetOn) {
                glow(86f, 126f, 16f, cL); glow(214f, 126f, 16f, cR)
                wedge(86f, 130f, 86f, 168f, 14f, 46f, flowShader(cL, false)); wedge(214f, 130f, 214f, 168f, 14f, 46f, flowShader(cR, false))
                glow(88f, 276f, 14f, cL); glow(212f, 276f, 14f, cR)
                wedge(88f, 280f, 88f, 326f, 14f, 48f, flowShader(cL, false)); wedge(212f, 280f, 212f, 326f, 14f, 48f, flowShader(cR, false))
            }
            if (winOn) {
                glow(150f, 64f, 22f, cC)
                canvas.save(); canvas.clipPath(wsPath)
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(0f, 64f, 0f, 14f, intArrayOf(withA(cC, (242 * pulse).toInt()), withA(cC, (128 * pulse).toInt()), withA(cC, (38 * pulse).toInt())), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, 300f, 70f, p)
                val sw = LinearGradient(0f, 0f, 0f, 50f, intArrayOf(withA(cC, 0), withA(cC, 140), withA(cC, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.REPEAT)
                carMat.setTranslate(0f, -ph * 50f); sw.setLocalMatrix(carMat); p.shader = sw
                canvas.drawRect(0f, 0f, 300f, 70f, p); p.shader = null
                canvas.restore()
                wedge(42f, 100f, 28f, 84f, 10f, 26f, flowShader(cL, true)); wedge(258f, 100f, 272f, 84f, 10f, 26f, flowShader(cR, true))
            }
            postInvalidateDelayed(40)
        }
        canvas.restore()
        p.shader = null
    }

    /** The cabin from above with the airflow lit by the driver's air direction, and the fan speed bars under it. */
    private fun cabin(cx: Float, top: Float, areaW: Float, areaH: Float, s: CanSnapshot, small: Boolean) {
        val barsH = (areaH * 0.15f).coerceIn(dp(20f), dp(40f))
        val labH = dp(if (small) 16f else 20f)
        val carH = areaH - barsH - labH - dp(10f)
        overheadCar(cx, top - dp(10f), areaW, carH, s, s.climSeen && s.climFan > 0, s.climDrvMode)
        if (!seatsPlaced && !hideSeatIcons) seatButtons(cx, areaW, s)
        // fan speed bars
        val bt = top + carH + dp(10f)
        val bw = minOf(dp(13f), areaW * 0.07f); val bg = dp(5f)
        val x0 = cx - (7 * bw + 6 * bg) / 2f
        for (i in 0 until 7) {
            val bh = barsH * (0.24f + 0.76f * i / 6f)
            val rr = RectF(x0 + i * (bw + bg), bt + barsH - bh, x0 + i * (bw + bg) + bw, bt + barsH)
            p.style = Paint.Style.FILL
            if (i < s.climFan) {
                p.color = Color.argb(60, Color.red(on), Color.green(on), Color.blue(on)); canvas.drawRoundRect(RectF(rr.left - dp(2f), rr.top - dp(2f), rr.right + dp(2f), rr.bottom + dp(2f)), bw * 0.4f, bw * 0.4f, p)
                p.color = on
            } else p.color = Color.argb(36, 255, 255, 255)
            canvas.drawRoundRect(rr, bw * 0.3f, bw * 0.3f, p)
        }
        val label = "FAN  " + (if (s.climFan == 0) "Off" else "${s.climFan}")
        p.textSize = dp(if (small) 13f else 15f); p.typeface = Typeface.DEFAULT_BOLD
        val tw0 = p.measureText(label)
        val ly = bt + barsH + labH * 0.55f
        fanRect.set(x0 - dp(24f), bt - dp(6f), x0 + 7 * bw + 6 * bg + dp(24f), ly + dp(16f))
        // - and + for the fan speed, either side of the bars
        run {
            val br = dp(if (small) 17f else 20f); val fy = bt + barsH * 0.5f + dp(5f)
            val mx = x0 - dp(14f) - br; val px = x0 + 7 * bw + 6 * bg + dp(14f) + br
            for ((fx, sym) in listOf(mx to "\u2212", px to "+")) {
                val sq = RectF(fx - br, fy - br, fx + br, fy + br); val cr = br * 0.38f
                p.style = Paint.Style.FILL; p.color = Color.argb(40, 255, 255, 255); canvas.drawRoundRect(sq, cr, cr, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.3f); p.color = Color.argb(110, 255, 255, 255); canvas.drawRoundRect(sq, cr, cr, p)
                text(sym, fx, fy, if (small) 22f else 26f, ink(), true)
            }
            val hr = br + dp(6f)
            fanMinus.set(mx - hr, fy - hr, mx + hr, fy + hr); fanPlus.set(px - hr, fy - hr, px + hr, fy + hr)
        }
        fan(cx - tw0 / 2 - dp(14f), ly, dp(if (small) 7f else 9f), ink(if (dayBg) 230 else 190))
        text(label, cx + dp(8f), ly, if (small) 13f else 15f, ink(), true)
    }

    private var seatsPlaced = false
    private val seatBtn = RectF()
    private var seatPanelOpen = false
    private fun seatLevel(who: Int, s: CanSnapshot): Int {
        if (seatOver[who] >= 0 && System.currentTimeMillis() - seatOverAt[who] < 3500) return seatOver[who]
        return (when (who) { 0 -> s.seatDrv; 1 -> s.seatPass; 2 -> s.seatRL; else -> s.seatRR }) ?: 0
    }

    /** Layout 2: four seat heating buttons either side of the car (a seat with 0 to 3 wavy lines). Nothing else moves. */
    private fun seatButtons(cx: Float, areaW: Float, s: CanSnapshot) {
        if (carRect.isEmpty()) return
        val k = carRect.width() / 300f
        val sy = 0.88f
        val side = areaW / 2f - 161f * k
        val gapV = 153f * sy * k
        val isz = minOf(side - dp(3f), dp(46f), gapV - dp(4f)).coerceAtLeast(dp(20f))
        val xl = maxOf(cx - 161f * k - dp(3f) - isz / 2f, cx - areaW / 2f + isz / 2f)
        val xr = minOf(cx + 161f * k + dp(3f) + isz / 2f, cx + areaW / 2f - isz / 2f)
        val yF = if (use3d) carRect.top + (220f + 166.55f) * C3F * k else carRect.top + 225f * sy * k
        val yR = if (use3d) carRect.top + (342f + 166.55f) * C3F * k else carRect.top + 378f * sy * k
        val drvRight = rhdSide
        // left column first: front, rear; then the right column
        val spec = listOf(
            Triple(xl, yF, if (drvRight) 1 else 0), Triple(xl, yR, 2),
            Triple(xr, yF, if (drvRight) 0 else 1), Triple(xr, yR, 3),
        )
        seatRects.clear(); seatWho.clear()
        for ((x, y, who) in spec) drawSeat(RectF(x - isz / 2f, y - isz / 2f, x + isz / 2f, y + isz / 2f), who, s)
    }

    /** One seat heating button: a glass tile with a tall Ocean-style seat (headrest, high back, cushion) and 0 to 3 wavy lines on its back. */
    private fun drawSeat(r: RectF, who: Int, s: CanSnapshot) {
        drawSeatIcon(r, seatLevel(who, s))
        seatRects.add(r); seatWho.add(who)
    }

    private fun drawSeatIcon(r: RectF, lvl: Int) {
        val isz = r.width()
        drawLiquidGlass(canvas, p, r, isz * 0.30f, dp(1f), null, glassBase)
        val x0 = r.left; val y0 = r.top
        fun px(v: Float) = x0 + v * isz
        fun py(v: Float) = y0 + v * isz
        val white = Color.WHITE
        // outlined side view of the seat, facing right
        p.shader = null; p.style = Paint.Style.STROKE; p.color = white
        p.strokeWidth = maxOf(1.5f, isz * 0.022f); p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
        path.reset()
        for (k in 0 until SEAT_OUTLINE.size / 2) {
            val sx = px(SEAT_OUTLINE[2 * k]); val sy = py(SEAT_OUTLINE[2 * k + 1])
            if (k == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close(); canvas.drawPath(path, p)
        // heat waves rising from the cushion: one to three
        if (lvl > 0) {
            p.color = Color.parseColor("#FF7B7B"); p.strokeWidth = maxOf(1.6f, isz * 0.025f)
            for (i in 0 until lvl.coerceAtMost(3)) {
                val w = SEAT_WAVES[i]
                path.reset()
                for (k in 0 until w.size / 2) {
                    val wx = px(w[2 * k]); val wy = py(w[2 * k + 1])
                    if (k == 0) path.moveTo(wx, wy) else path.lineTo(wx, wy)
                }
                canvas.drawPath(path, p)
            }
        }
        // three dots under the seat show the level
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        for (i in 0 until 3) {
            p.color = if (i < lvl) white else Color.argb(90, 255, 255, 255)
            canvas.drawCircle(px(0.34f + 0.16f * i), py(0.935f), isz * 0.036f, p)
        }
    }

    /** A short panel: the two seat buttons of each side in one row under the dial, with the dial made a little smaller to make room. */
    private fun seatRow(dialLeft: Float, dialW: Float, dialRight: Float, rowTop: Float, rowH: Float, s: CanSnapshot): Boolean {
        val isz = minOf(rowH - dp(2f), dp(42f))
        if (isz < dp(26f)) return false
        seatRects.clear(); seatWho.clear()
        val drvRight = rhdSide
        val frontL = if (drvRight) 1 else 0; val frontR = if (drvRight) 0 else 1
        p.typeface = Typeface.DEFAULT_BOLD
        val sp = dp(14f); val lg = dp(6f)
        // one label size for both sides: the largest (13 down to 9) at which the longest name still fits beside the buttons, so the right side is never left without labels
        val allNames = listOf(seatName(frontL), seatName(frontR), seatName(2))
        var ts = 13f
        fun labW(t: Float): Float { p.textSize = dp(t); return allNames.maxOf { p.measureText(it) } }
        while (ts > 9f && 2 * (isz + lg + labW(ts)) + sp > dialW - dp(8f)) ts -= 0.5f
        val withLab = 2 * (isz + lg + labW(ts)) + sp <= dialW - dp(8f)
        val lw = labW(ts)
        fun row(colX: Float, front: Int, rear: Int) {
            val names = listOf(seatName(front), seatName(rear))
            val total = if (withLab) 2 * (isz + lg + lw) + sp else 2 * isz + sp
            var x = colX + dialW / 2f - total / 2f
            val y = rowTop + (rowH - isz) / 2f
            for ((i, who) in listOf(front, rear).withIndex()) {
                drawSeat(RectF(x, y, x + isz, y + isz), who, s)
                x += isz
                if (withLab) { text(names[i], x + lg, y + isz / 2f, ts, Color.argb(220, 255, 255, 255), true, Paint.Align.LEFT); x += lg + lw }
                x += sp
            }
        }
        row(dialLeft, frontL, 2); row(dialRight, frontR, 3)
        return true
    }

    private fun seatName(who: Int) = when (who) { 0 -> "Driver"; 1 -> "Front Pass"; else -> "Rear" }

    /** The full climate screen: the seat buttons go in the empty space under each dial, beside the car. False when that space is too small. */
    private fun seatBesideCar(dialLeft: Float, dialW: Float, dialRight: Float, dialTop: Float, dialH: Float, s: CanSnapshot): Boolean {
        val bs = (dialH * 0.20f).coerceIn(dp(34f), dp(52f))
        val dd = minOf(dialH * 0.80f, dialW * 0.88f, (dialH - bs - dp(6f)) / 0.827f)
        val freeTop = dialTop + dd * 0.827f + dp(6f) + bs + dp(10f)
        val freeH = dialTop + dialH - freeTop
        if (freeH < dp(44f)) return false
        val gap = dp(8f)
        val labW = dp(84f)
        val isz = minOf((freeH - gap) / 2f - dp(2f), dp(72f), dialW - labW - dp(26f))
        if (isz < dp(30f)) return false
        seatRects.clear(); seatWho.clear()
        val drvRight = rhdSide
        val frontL = if (drvRight) 1 else 0; val frontR = if (drvRight) 0 else 1
        val unitW = isz + dp(10f) + labW
        val topY = freeTop + (freeH - (2 * isz + gap)) / 2f
        fun unit(left: Boolean, colX: Float, front: Int, rear: Int) {
            val bx = if (left) colX + dialW / 2f - unitW / 2f else colX + dialW / 2f + unitW / 2f - isz   // left side: button then name; right side: name then button
            for ((i, who) in listOf(front, rear).withIndex()) {
                val y = topY + i * (isz + gap)
                drawSeat(RectF(bx, y, bx + isz, y + isz), who, s)
                if (left) text(seatName(who), bx + isz + dp(10f), y + isz / 2f, 15f, Color.argb(220, 255, 255, 255), true, Paint.Align.LEFT)
                else text(seatName(who), bx - dp(10f), y + isz / 2f, 15f, Color.argb(220, 255, 255, 255), true, Paint.Align.RIGHT)
            }
        }
        unit(true, dialLeft, frontL, 2)
        unit(false, dialRight, frontR, 3)
        return true
    }

    private fun pill(right: Float, cy: Float, ph: Float, label: String, value: String, small: Boolean): Float {
        p.typeface = Typeface.DEFAULT; p.textSize = dp(if (small) 11f else 12f); val lw = p.measureText(label)
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = dp(if (small) 13f else 15f); val vw = p.measureText(value)
        val pad = dp(12f); val gp = dp(6f)
        val r = RectF(right - (pad * 2 + lw + gp + vw), cy - ph / 2, right, cy + ph / 2)
        p.style = Paint.Style.FILL; p.color = Color.argb(18, 255, 255, 255); canvas.drawRoundRect(r, ph / 2, ph / 2, p)
        stroke(Color.argb(34, 255, 255, 255), 1f); canvas.drawRoundRect(r, ph / 2, ph / 2, p)
        text(label, r.left + pad, cy, if (small) 11f else 12f, ink(if (dayBg) 230 else 190), false, Paint.Align.LEFT)
        text(value, r.left + pad + lw + gp, cy, if (small) 13f else 15f, ink(), true, Paint.Align.LEFT)
        return r.left
    }

    override fun onDraw(c: Canvas) {
        canvas = c
        val w = width.toFloat(); val h = height.toFloat()
        val s = snap
        d = if (mini && !compact && h > 0f) baseD * (h / baseD / 390f).coerceIn(0.55f, 1f) else baseD
        val hd = h / d
        val small = hd < 470f                          // a third-height window: everything a little tighter
        // background: the dashboard's colour, a touch lighter at the top
        val rad = if (floating) dp(28f) else 0f
        p.style = Paint.Style.FILL
        // fully opaque: a solid base first, then the skin's top-to-bottom gradient (the same colours as behind the kW and Speed gauges)
        val solid = bgColor or (0xFF shl 24)
        p.color = solid
        c.drawRoundRect(RectF(0f, 0f, w, h), rad, rad, p)
        p.shader = LinearGradient(0f, 0f, 0f, h, bgTopColor or (0xFF shl 24), solid, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, w, h), rad, rad, p); p.shader = null
        if (floating) { stroke(Color.argb(70, 255, 255, 255), 1.5f); c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), rad, rad, p) }
        if (compact) { drawCompact(w, h, s); return }
        val m = dp(if (small) 12f else 16f)
        val gap = dp(if (small) 6f else 8f)
        val topY = dp(if (small) 8f else 12f)
        val headH = dp(if (small) 40f else 56f)
        val footH = dp(if (small) 40f else 50f)
        val tileH = (h * 0.155f).coerceIn(dp(60f), dp(100f))
        val chipH = (h * 0.104f).coerceIn(dp(40f), dp(62f))
        val tilesTop = h - footH - gap - tileH
        val chipsTop = tilesTop - gap - chipH
        val dialTop = topY + headH
        val dialH = chipsTop - gap - dialTop

        // ---- header: power, title, cabin and outside readings
        run {
            val ps = headH * 0.80f
            // right-hand drive: the whole header is mirrored (power button on the right, readings on the left)
            val flip = rhdSide
            if (flip) powerRect.set(w - m - ps, topY + (headH - ps) / 2, w - m, topY + (headH + ps) / 2)
            else powerRect.set(m, topY + (headH - ps) / 2, m + ps, topY + (headH + ps) / 2)
            val isOff = s.climSeen && s.climFan == 0
            val pc = if (isOff) Color.parseColor("#FF5A5A") else on
            p.style = Paint.Style.FILL; p.color = Color.argb(22, Color.red(pc), Color.green(pc), Color.blue(pc))
            c.drawRoundRect(RectF(powerRect.left - dp(3f), powerRect.top - dp(3f), powerRect.right + dp(3f), powerRect.bottom + dp(3f)), ps * 0.34f, ps * 0.34f, p)
            p.color = Color.argb(if (isOff) 70 else 40, Color.red(pc), Color.green(pc), Color.blue(pc))
            c.drawRoundRect(powerRect, ps * 0.30f, ps * 0.30f, p)
            stroke(pc, 1.8f); c.drawRoundRect(powerRect, ps * 0.30f, ps * 0.30f, p)
            powerIcon(powerRect.centerX(), powerRect.centerY() + ps * 0.03f, ps * 0.26f, Color.WHITE)
            if (flip) text("Climate", powerRect.left - dp(12f), powerRect.centerY(), if (small) 18f else 22f, ink(), true, Paint.Align.RIGHT)
            else text("Climate", powerRect.right + dp(12f), powerRect.centerY(), if (small) 18f else 22f, ink(), true, Paint.Align.LEFT)
            val ph = dp(if (small) 26f else 32f)
            if (flip) {
                val out = temp(s.outsideC); val cab = temp(s.cabinC)
                val pw = { lab: String, v: String -> p.typeface = Typeface.DEFAULT; p.textSize = dp(if (small) 11f else 12f); val a = p.measureText(lab)
                    p.typeface = Typeface.DEFAULT_BOLD; p.textSize = dp(if (small) 13f else 15f); dp(12f) * 2 + a + dp(6f) + p.measureText(v) }
                val oW = pw("Outside", out)
                pill(m + oW, powerRect.centerY(), ph, "Outside", out, small)
                pill(m + oW + dp(8f) + pw("Cabin", cab), powerRect.centerY(), ph, "Cabin", cab, small)
            } else {
                val l1 = pill(w - m, powerRect.centerY(), ph, "Outside", temp(s.outsideC), small)
                pill(l1 - dp(8f), powerRect.centerY(), ph, "Cabin", temp(s.cabinC), small)
            }
        }

        // ---- the two dials and the cabin between them
        tempBtns.clear(); tempBtnInfo.clear(); modeRects.clear(); seatRects.clear(); seatWho.clear()
        val cabinW = w * 0.30f
        val dialW = (w - cabinW - 3 * m) / 2f
        val drvRight = rhdSide
        seatRects.clear(); seatWho.clear()
        seatBtn.setEmpty(); seatPanelOpen = false
        val bs0 = (dialH * 0.20f).coerceIn(dp(34f), dp(52f))
        val dd0 = minOf(dialH * 0.80f, dialW * 0.88f, (dialH - bs0 - dp(6f)) / 0.827f)
        val free0 = dialH - (dd0 * 0.827f + dp(6f) + bs0 + dp(10f))
        var dialH2 = dialH
        if (hideSeatIcons) seatsPlaced = true
        else if (free0 >= dp(44f)) seatsPlaced = seatBesideCar(m, dialW, w - m - dialW, dialTop, dialH, s)
        else {
            // not enough room under the dials (the media card is also showing): shrink the dials a little and put the seat buttons in a row under them
            val rowH = dp(42f)
            val dh2 = dialH - rowH - dp(4f)
            seatsPlaced = dh2 >= dp(130f) && seatRow(m, dialW, w - m - dialW, dialTop + dh2 + dp(4f), rowH, s)
            if (seatsPlaced) dialH2 = dh2
        }
        dial(m, dialW, dialTop, dialH2, if (drvRight) "PASSENGER" else "DRIVER", if (drvRight) s.climPassT else s.climDrvT, !drvRight)
        dial(w - m - dialW, dialW, dialTop, dialH2, if (drvRight) "DRIVER" else "PASSENGER", if (drvRight) s.climDrvT else s.climPassT, drvRight)
        cabin(w / 2f, dialTop, cabinW, dialH, s, small)

        // ---- air direction (the driver's; the car has no separate passenger airflow)
        run {
            val names = listOf("Face", "Face + Feet", "Feet", "Feet + Screen", "Screen")
            val cg = dp(if (small) 6f else 10f)
            val cw = (w - 2 * m - 4 * cg) / 5f
            for (i in 0 until 5) {
                val rr = RectF(m + i * (cw + cg), chipsTop, m + i * (cw + cg) + cw, chipsTop + chipH)
                val md = modeOrder[i]
                val lit = s.climSeen && s.climDrvMode == md
                val cr = chipH * 0.32f
                drawLiquidGlass(c, p, rr, cr, dp(1f), if (lit) on else null, glassBase)
                modeIcon(rr.left + chipH * 0.78f, rr.centerY(), chipH * 0.30f, md, if (lit) Color.WHITE else Color.argb(150, 255, 255, 255))
                val tx0 = rr.left + chipH * 1.18f
                fitText(names[md - 1], (tx0 + rr.right - dp(4f)) / 2f, rr.centerY(), if (small) 11f else 12f, if (lit) Color.WHITE else Color.argb(170, 255, 255, 255), true, rr.right - dp(6f) - tx0)
                modeRects.add(rr)
            }
        }

        // ---- function tiles
        val cols = 7
        tile(0, cols, m, tilesTop, w - 2 * m, tileH, "A/C", s.climAc, on, small) { x, yy, r, col -> snowflake(x, yy, r, col) }
        tile(1, cols, m, tilesTop, w - 2 * m, tileH, "Heated wheel", s.climWheelOn(), Color.parseColor("#F23A32"), small) { x, yy, r, col -> wheel(x, yy, r, col) }
        tile(2, cols, m, tilesTop, w - 2 * m, tileH, "Defrost", s.climMaxDefrost, Color.parseColor("#F23A32"), small) { x, yy, r, col -> defrost(x, yy, r, col) }
        tile(3, cols, m, tilesTop, w - 2 * m, tileH, "Rear Defrost", s.rearDefrost == true, Color.parseColor("#F23A32"), small) { x, yy, r, col -> rearDefrost(x, yy, r, col) }
        tile(4, cols, m, tilesTop, w - 2 * m, tileH, "Recirc", s.climSeen && !s.climOuterAir, cool, small) { x, yy, r, col -> recirc(x, yy, r, col, s.climOuterAir) }
        tile(5, cols, m, tilesTop, w - 2 * m, tileH, "Sync", s.climSync, on, small) { x, yy, r, col -> sync(x, yy, r, col) }
        tile(6, cols, m, tilesTop, w - 2 * m, tileH, "Auto", s.climAuto, on, small) { x, yy, r, col -> autoIcon(x, yy, r, col) }
        wheelRect.setEmpty(); btnRects.clear()
        if (fanControl) {
            val gp = tileGap(small); val tw1 = (w - 2 * m - gp * (cols - 1)) / cols
            val wx = m + 1 * (tw1 + gp); wheelRect.set(wx, tilesTop, wx + tw1, tilesTop + tileH)
            for (ti in listOf(0, 2, 4, 5, 6)) { val xx = m + ti * (tw1 + gp); btnRects.add(RectF(xx, tilesTop, xx + tw1, tilesTop + tileH)) }
        }

        // ---- close buttons at both bottom corners, status between them
        val xs = dp(if (small) 34f else 38f)
        val cw = dp(if (small) 84f else 96f)
        val bp = dp(if (small) 6f else 10f)
        if (l2) {
            // Layout 2: a single Close, at the driver's side (left in left-hand drive, right in right-hand drive)
            if (rhd) closeRect.set(w - m - cw, h - bp - xs, w - m, h - bp) else closeRect.set(m, h - bp - xs, m + cw, h - bp)
            closeRect2.set(1e4f, 1e4f, 1e4f + 1f, 1e4f + 1f)
        } else {
            closeRect.set(m, h - bp - xs, m + cw, h - bp)
            closeRect2.set(w - m - cw, h - bp - xs, w - m, h - bp)
        }
        for (cr in listOf(closeRect, closeRect2)) {
            if (cr.left > w) continue
            p.style = Paint.Style.FILL; p.color = Color.argb(40, 255, 255, 255); c.drawRoundRect(cr, xs / 2, xs / 2, p)
            stroke(Color.argb(100, 255, 255, 255), 1.4f); c.drawRoundRect(cr, xs / 2, xs / 2, p)
            text("Close", cr.centerX(), cr.centerY(), if (small) 13f else 15f, ink(), true)
        }
        val state = when (s.climState) { 0 -> "starting"; 1 -> "low-voltage"; 2 -> "high-voltage on"; 3 -> "charging"; 4 -> "remote"; 5 -> "HV discharge"; 6 -> "delay"; else -> "--" }
        val advice = when (s.climAdvice) { 1 -> "natural air"; 2 -> "cold air"; 3 -> "hot air"; else -> "" }
        val parts = ArrayList<String>()
        if (s.climSeen) parts.add("A/C unit: $state")
        if (s.climRemote == 1) parts.add("remote A/C running")
        if (advice.isNotEmpty()) parts.add(advice)
        val fy = h - bp - xs / 2
        val footW = if (l2) w - 2 * (m + cw + dp(16f)) else closeRect2.left - closeRect.right - dp(16f)
        if (!s.climSeen) fitText("Waiting for the A/C unit (no 0x373 frames yet)", w / 2, fy, if (small) 11f else 12f, warm, false, footW)
        else fitText(parts.joinToString("  ·  "), w / 2, fy - (if (s.climFault || s.climLimited || s.wheelHeat == null) dp(9f) else 0f), if (small) 11f else 12f, Color.argb(190, 255, 255, 255), false, footW)
        if (s.climSeen) {
            if (s.climFault) text("Thermal system fault", w / 2, fy + dp(10f), 11f, Color.parseColor("#FF5A5A"), true)
            else if (s.climLimited) text("Thermal functions limited", w / 2, fy + dp(10f), 11f, warm, true)
            else if (s.wheelHeat == null) text("Heated wheel status not received", w / 2, fy + dp(10f), 10f, Color.argb(120, 255, 255, 255))
        }
        drawNotice(w / 2, tilesTop - dp(26f), w - 2 * m)
        if (paintOpen) paintPopup()
    }

    private fun CanSnapshot.climWheelOn() = wheelHeat == true

    /** The short version for Layout 2: driver and passenger temperatures with - / +, then one row of function tiles. */
    private fun drawCompact(w: Float, h: Float, s: CanSnapshot) {
        closeRect.set(-1e4f, -1e4f, -1e4f + 1f, -1e4f + 1f); closeRect2.set(1e4f, 1e4f, 1e4f + 1f, 1e4f + 1f)
        powerRect.setEmpty(); fanRect.setEmpty(); fanMinus.setEmpty(); fanPlus.setEmpty(); carRect.setEmpty(); wheelRect.setEmpty(); btnRects.clear(); carZones.clear()
        tempBtns.clear(); tempBtnInfo.clear(); modeRects.clear(); seatRects.clear(); seatWho.clear()
        val m = dp(10f); val gap = dp(6f); val pad = dp(6f)
        val topH = (h * 0.40f).coerceIn(dp(38f), dp(64f))
        val tileH = (h - 2 * pad - topH - gap).coerceAtLeast(dp(40f))
        val cy = pad + topH / 2f
        val bs = minOf(topH, dp(38f))
        val blockW = (w - 2 * m) * 0.34f
        fun block(x0: Float, name: String, t: Double?, driver: Boolean) {
            val minus = RectF(x0, cy - bs / 2, x0 + bs, cy + bs / 2)
            val plus = RectF(x0 + blockW - bs, cy - bs / 2, x0 + blockW, cy + bs / 2)
            glassBtn(minus, 0); glassBtn(plus, 1)
            tempBtns.add(minus); tempBtns.add(plus)
            tempBtnInfo.add(Pair(driver, -1)); tempBtnInfo.add(Pair(driver, 1))
            val mx = x0 + blockW / 2f
            text(name, mx, cy - topH * 0.30f, 10f, ink(if (dayBg) 230 else 190), true)
            fitText(temp(t), mx, cy + topH * 0.10f, (topH / d * 0.46f).coerceIn(16f, 30f), if (t == null) off else ink(), true, blockW - 2 * bs - dp(6f))
        }
        val drvRight = rhdSide
        block(m, if (drvRight) "PASSENGER" else "DRIVER", if (drvRight) s.climPassT else s.climDrvT, !drvRight)
        block(w - m - blockW, if (drvRight) "DRIVER" else "PASSENGER", if (drvRight) s.climDrvT else s.climPassT, drvRight)
        val midW0 = w - 2 * m - 2 * blockW - 2 * gap
        // one seat heating button at the left of the middle: it opens a small panel with the four seats
        seatBtn.setEmpty()
        var midX = w / 2f; var midW = midW0
        if (l2 && !hideSeatIcons && midW0 > dp(150f)) {
            val sb = minOf(topH, dp(44f))
            seatBtn.set(w / 2f - midW0 / 2f, cy - sb / 2f, w / 2f - midW0 / 2f + sb, cy + sb / 2f)
            drawSeatIcon(seatBtn, maxOf(maxOf(seatLevel(0, s), seatLevel(1, s)), maxOf(seatLevel(2, s), seatLevel(3, s))))
            midX = seatBtn.right + dp(6f) + (midW0 - sb - dp(6f)) / 2f; midW = midW0 - sb - dp(6f)
        }
        text("Climate", midX, cy - topH * 0.22f, 16f, ink(), true)
        fitText("Cabin " + temp(s.cabinC) + "   Outside " + temp(s.outsideC), midX, cy + topH * 0.22f, 11f, ink(if (dayBg) 235 else 200), false, midW)
        val tilesTop = pad + topH + gap
        val cols = 7
        tile(0, cols, m, tilesTop, w - 2 * m, tileH, "A/C", s.climAc, on, true) { x, yy, r, col -> snowflake(x, yy, r, col) }
        tile(1, cols, m, tilesTop, w - 2 * m, tileH, "Heated wheel", s.climWheelOn(), Color.parseColor("#F23A32"), true) { x, yy, r, col -> wheel(x, yy, r, col) }
        tile(2, cols, m, tilesTop, w - 2 * m, tileH, "Defrost", s.climMaxDefrost, Color.parseColor("#F23A32"), true) { x, yy, r, col -> defrost(x, yy, r, col) }
        tile(3, cols, m, tilesTop, w - 2 * m, tileH, "Rear Defrost", s.rearDefrost == true, Color.parseColor("#F23A32"), true) { x, yy, r, col -> rearDefrost(x, yy, r, col) }
        tile(4, cols, m, tilesTop, w - 2 * m, tileH, "Recirc", s.climSeen && !s.climOuterAir, cool, true) { x, yy, r, col -> recirc(x, yy, r, col, s.climOuterAir) }
        tile(5, cols, m, tilesTop, w - 2 * m, tileH, "Sync", s.climSync, on, true) { x, yy, r, col -> sync(x, yy, r, col) }
        tile(6, cols, m, tilesTop, w - 2 * m, tileH, "Auto", s.climAuto, on, true) { x, yy, r, col -> autoIcon(x, yy, r, col) }
        if (fanControl) {
            val gp = tileGap(true); val tw1 = (w - 2 * m - gp * (cols - 1)) / cols
            val wx = m + 1 * (tw1 + gp); wheelRect.set(wx, tilesTop, wx + tw1, tilesTop + tileH)
            for (ti in listOf(0, 2, 4, 5, 6)) { val xx = m + ti * (tw1 + gp); btnRects.add(RectF(xx, tilesTop, xx + tw1, tilesTop + tileH)) }
        }
        drawNotice(w / 2, tilesTop - dp(2f), w - 2 * m)
        if (seatPanelOpen && !seatBtn.isEmpty) {
            // the seat panel: dims the screen, four seats in a row with names under them
            p.style = Paint.Style.FILL; p.color = Color.argb(185, 0, 0, 0); canvas.drawRect(0f, 0f, w, h, p)
            val pw = minOf(w - 2 * m, dp(460f)); val ph = h - 2 * pad
            val pr = RectF(w / 2f - pw / 2f, pad, w / 2f + pw / 2f, pad + ph)
            drawLiquidGlass(canvas, p, pr, dp(16f), dp(1f), null, glassBase)
            val labH = dp(16f)
            val isz = minOf(ph - labH - dp(14f), pw / 4f - dp(14f), dp(64f)).coerceAtLeast(dp(24f))
            val cw = pw / 4f
            val ordL = if (drvRight) 1 else 0; val ordR = if (drvRight) 0 else 1
            val order = intArrayOf(ordL, ordR, 2, 3)
            val names = arrayOf(seatName(ordL), seatName(ordR), "Rear left", "Rear right")
            seatRects.clear(); seatWho.clear()
            val top = pr.top + (ph - isz - labH) / 2f
            for (i in 0 until 4) {
                val cx0 = pr.left + cw * (i + 0.5f)
                drawSeat(RectF(cx0 - isz / 2f, top, cx0 + isz / 2f, top + isz), order[i], s)
                text(names[i], cx0, top + isz + labH * 0.7f, 11f, Color.argb(230, 255, 255, 255), true)
            }
        }
    }

    /** A tap on a seat heating button: the next level (off, low, medium, high). */
    private fun tapSeat(si: Int) = cycleSeat(seatWho[si])

    /** A tap on part of the car drawing. */
    private fun carZoneTap(id: Int) {
        when (id) {
            in 0..3 -> cycleSeat(id)
            10 -> {
                if (System.currentTimeMillis() - lastFanTap <= 600) return
                lastFanTap = System.currentTimeMillis()
                if (!fanControl) showNotice("Tick the climate control option in Settings to use the front defrost here, or use the button bar below the screen") else onButton?.invoke(1)
            }
            11 -> showNotice("Please use the button bar below the screen to adjust Rear Defrost")
            12 -> {
                if (System.currentTimeMillis() - lastFanTap <= 600) return
                lastFanTap = System.currentTimeMillis()
                if (!fanControl) showNotice("Tick the climate control option in Settings to use the heated steering wheel here") else onWheel?.invoke()
            }
        }
    }

    private fun cycleSeat(who: Int) {
        if (System.currentTimeMillis() - lastFanTap <= 350) return
        lastFanTap = System.currentTimeMillis()
        if (!fanControl) showNotice("Tick the climate control option in Settings to use the seat heating")
        else {
            val next = (seatLevel(who, snap) + 1) % 4
            seatOver[who] = next; seatOverAt[who] = System.currentTimeMillis(); postInvalidateDelayed(3600)
            onSeat?.invoke(who, next); invalidate()
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action != android.view.MotionEvent.ACTION_UP) return true
        if (paintOpen) {
            val pi = paintRects.indexOfFirst { it.contains(e.x, e.y) }
            if (pi >= 0) { paintIdx = pi; onPaint?.invoke(pi); paintOpen = false }
            else if (!paintPanel.contains(e.x, e.y)) paintOpen = false
            invalidate(); return true
        }
        if (seatPanelOpen) {
            val pi = seatRects.indexOfFirst { it.contains(e.x, e.y) }
            if (pi >= 0) tapSeat(pi) else seatPanelOpen = false
            invalidate(); return true
        }
        if (!seatBtn.isEmpty && seatBtn.contains(e.x, e.y)) { seatPanelOpen = true; invalidate(); return true }
        val slop = dp(10f)
        if (l2) {
            if (e.y > closeRect.top - slop && e.x > closeRect.left - slop && e.x < closeRect.right + slop) { onClose?.invoke(); return true }
        } else if (e.y > closeRect.top - slop && (e.x < closeRect.right + slop || e.x > closeRect2.left - slop)) { onClose?.invoke(); return true }
        val si = seatRects.indexOfFirst { it.contains(e.x, e.y) }
        if (si >= 0) { tapSeat(si); return true }
        if (!powerRect.isEmpty() && powerRect.contains(e.x, e.y) && System.currentTimeMillis() - lastFanTap > 800) {
            lastFanTap = System.currentTimeMillis()
            if (fanControl) onPower?.invoke(snap.climSeen && snap.climFan == 0) else onTempTap?.invoke()
            return true
        }
        val ti = tempBtns.indexOfFirst { it.contains(e.x, e.y) }
        if (ti >= 0) {
            if (fanControl && ti < tempBtnInfo.size && System.currentTimeMillis() - lastFanTap > 250) {
                lastFanTap = System.currentTimeMillis(); onTemp?.invoke(tempBtnInfo[ti].first, tempBtnInfo[ti].second)
            } else if (!fanControl) onTempTap?.invoke()
            return true
        }
        if (fanControl && System.currentTimeMillis() - lastFanTap > 400) {
            val mi = modeRects.indexOfFirst { it.contains(e.x, e.y) }
            if (mi >= 0) { lastFanTap = System.currentTimeMillis(); onMode?.invoke(modeOrder[mi]); return true }
        }
        if (fanControl && btnRects.size == 5 && System.currentTimeMillis() - lastFanTap > 600) {
            val bi = btnRects.indexOfFirst { it.contains(e.x, e.y) }
            if (bi >= 0) { lastFanTap = System.currentTimeMillis(); onButton?.invoke(bi); return true }
        }
        if (fanControl && !wheelRect.isEmpty() && wheelRect.contains(e.x, e.y) && System.currentTimeMillis() - lastFanTap > 600) { lastFanTap = System.currentTimeMillis(); onWheel?.invoke(); return true }
        for ((fr, dir) in listOf(fanMinus to -1, fanPlus to 1)) if (!fr.isEmpty && fr.contains(e.x, e.y)) {
            val now = System.currentTimeMillis()
            if (!fanControl) { showNotice("Tick the climate control option in Settings to change the fan speed here"); return true }
            if (now - lastFanTap < 250) return true
            lastFanTap = now
            val base = if (fanPend >= 0 && now - fanPendAt < 2500) fanPend else snap.climFan
            val t = (base + dir).coerceIn(1, 7)
            if (dir < 0 && base <= 1) return true       // below 1 is off: that is the power button
            fanPend = t; fanPendAt = now; onFan?.invoke(t)
            return true
        }
        if (!fanRect.isEmpty() && fanRect.contains(e.x, e.y)) { showNotice("Use the \u2212 and + buttons to change the fan speed"); return true }
        if (!rearRect.isEmpty() && rearRect.contains(e.x, e.y)) {
            if (!fanControl) { showNotice("Tick the climate control option in Settings to use rear defrost here"); return true }
            if (System.currentTimeMillis() - lastFanTap > 600) { lastFanTap = System.currentTimeMillis(); onRearDefrost?.invoke(snap.rearDefrost != true) }
            return true
        }
        val cz = carZones.indexOfFirst { it.first.contains(e.x, e.y) }
        if (cz >= 0) { carZoneTap(carZones[cz].second); invalidate(); return true }
        return true
    }
}


/** Follows the active media session (the phone over Bluetooth, Spotify, ...): title, artist, album art, play state, and transport controls. */
class MediaWatcher(private val ctx: Context, private val ui: Handler, private val grant: () -> Unit, private val log: (String) -> Unit) {
    var title: String? = null
    var artist: String? = null
    var art: Bitmap? = null
    var playing = false
    var hasSession = false
    var onChange: (() -> Unit)? = null
    /** The app whose media session the card follows. */
    val pkg: String? get() = ctl?.packageName
    /** Android Auto / CarPlay (CarLink) is the session being followed: the Bluetooth link is not what carries that audio. */
    val carLinkActive: Boolean get() = ctl?.packageName?.contains("carlink", true) == true
    /** An app other than ours has just started playing. */
    var onExternalPlay: (() -> Unit)? = null

    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val comp = ComponentName(ctx, MainActivity::class.java)
    private var ctl: MediaController? = null
    private var started = false
    private var grantTried = false
    private var gaveUp = false

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { readMeta(metadata); push() }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val was = playing
            playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing && !was) ui.post { onExternalPlay?.invoke() }
            push()
        }
        override fun onSessionDestroyed() { ui.post { refresh() } }
    }
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> ui.post { pick(list ?: emptyList()) } }

    private fun push() { ui.post { onChange?.invoke() } }

    fun start() {
        if (started || gaveUp) return
        try {
            msm.addOnActiveSessionsChangedListener(listener, comp, ui)
            started = true
            refresh()
            ui.removeCallbacks(ticker); ui.postDelayed(ticker, 3000)
        } catch (e: SecurityException) {
            if (!grantTried) {
                grantTried = true
                log("media: asking for notification access (root) so the active session can be read")
                grant()
                ui.postDelayed({ start() }, 1500)
            } else { gaveUp = true; log("media: no access to media sessions; the buttons still send media keys") }
        } catch (e: Exception) { log("media: " + e.message) }
    }

    fun stop() {
        ui.removeCallbacks(ticker)
        try { msm.removeOnActiveSessionsChangedListener(listener) } catch (e: Exception) {}
        try { ctl?.unregisterCallback(cb) } catch (e: Exception) {}
        started = false
    }

    private fun refresh() {
        try { pick(msm.getActiveSessions(comp)) } catch (e: Exception) { }
    }

    /** Which session to show: whatever is playing, then one with a real title (a Bluetooth one first), and never a "Not connected" placeholder first. */
    private fun score(c: MediaController): Int {
        var sc = 0
        when (c.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> sc += 100
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING -> sc += 20
        }
        val t = c.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val real = !t.isNullOrBlank() && !t.equals("Not connected", true)
        if (real) sc += 30
        if (c.packageName.contains("bluetooth", true)) sc += 15
        if (c.packageName.contains("carlink", true) && !real) sc -= 60
        return sc
    }

    private var lastSig = ""

    private fun pick(list: List<MediaController>) {
        var c: MediaController? = null
        var best = Int.MIN_VALUE
        for (x in list) { val sc = score(x); if (sc > best) { best = sc; c = x } }
        if (c?.sessionToken != ctl?.sessionToken) {
            try { ctl?.unregisterCallback(cb) } catch (e: Exception) {}
            ctl = c
            c?.registerCallback(cb, ui)
        }
        hasSession = c != null
        val was = playing
        playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing && !was) ui.post { onExternalPlay?.invoke() }
        readMeta(c?.metadata)
        push()
    }

    /** Looks at the media sessions again from scratch (the listener is attached again, then the best session is picked). */
    fun resync() {
        ui.post {
            if (started) {
                try { msm.removeOnActiveSessionsChangedListener(listener) } catch (e: Exception) { }
                try { msm.addOnActiveSessionsChangedListener(listener, comp, ui) } catch (e: Exception) { }
            }
            refresh()
        }
    }

    // look again every few seconds: a Bluetooth session can start playing without any signal reaching the one being followed
    private val ticker = object : Runnable { override fun run() { if (started) { refresh(); ui.postDelayed(this, 3000) } } }

    private fun readMeta(md: MediaMetadata?) {
        title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) { art = bmp; artUri = null; return }
        // some apps only publish a link to the picture: load it in the background
        val uri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri.isNullOrEmpty()) { art = null; artUri = null; return }
        if (uri == artUri) return                       // already loaded (or loading) this one
        artUri = uri
        art = null
        Thread {
            val b = loadArt(uri)
            ui.post { if (artUri == uri) { art = b; if (b == null) log("media: could not load the art from $uri"); onChange?.invoke() } }
        }.start()
    }

    private var artUri: String? = null

    private fun loadArt(uri: String): Bitmap? = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
        val stream = if (uri.startsWith("http://") || uri.startsWith("https://")) {
            val conn = java.net.URL(uri).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 8000; conn.instanceFollowRedirects = true
            conn.inputStream
        } else ctx.contentResolver.openInputStream(Uri.parse(uri))
        val bytes = stream?.use { it.readBytes() }
        if (bytes == null) null else {
            // keep memory small: the card is only a few hundred pixels
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var ss = 1
            while (bounds.outWidth / (ss * 2) >= 1000 && bounds.outHeight / (ss * 2) >= 1000) ss *= 2
            opts.inSampleSize = ss
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    } catch (e: Exception) { null }

    private fun key(code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    fun prev() { val t = ctl?.transportControls; if (t != null) t.skipToPrevious() else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
    fun next() { val t = ctl?.transportControls; if (t != null) t.skipToNext() else key(KeyEvent.KEYCODE_MEDIA_NEXT) }
    fun toggle() {
        val t = ctl?.transportControls
        if (t != null) { if (playing) t.pause() else t.play() } else key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
}

/** The media card: album art behind, title and artist, and drawn previous / play-pause / next buttons. */
class MediaCardView(ctx: Context) : View(ctx) {
    var onPrev: (() -> Unit)? = null
    var onToggle: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null                  // a tap anywhere on the card that is not one of the three buttons
    var onSources: (() -> Unit)? = null              // the Sources button (wide card)
    var onFavs: (() -> Unit)? = null                 // the yellow favourites star beside it
    var srcMode = 0                                  // 0 external player / Bluetooth, 1 local file, 2 internet radio
    var rhd = false                                   // right-hand drive: controls on the right
    var l2 = false                                    // Layout 2: the button column and star sit clear of the transport buttons
    var fullBleed = false                             // Layout 2: the art fills the card, the text sits centred over its lower part
    private var title: String? = null
    private var artist: String? = null
    private var art: Bitmap? = null
    private var playing = false
    private var hasSession = false
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rPrev = RectF(); private val rPlay = RectF(); private val rNext = RectF()

    // ---- visualizer (wide card only): on the right, tap it for the next style; the small button turns it on / off
    private val vizRect = RectF(); private val vizBtn = RectF(); private val vizColBtn = RectF(); private val srcBtn = RectF(); private val favBtn = RectF()
    private var palNameAt = 0L
    private val painter = VizPainter(ctx.resources.displayMetrics.density)
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private var vizOn = true; private var vizStyle = 0; private var vizPal = 0; private var vizRead = 0L
    private var vizLive = false
    private fun readViz() {
        val now = System.currentTimeMillis()
        if (now - vizRead < 500) return
        vizRead = now
        vizOn = sp.getBoolean("vizOn", true); vizStyle = sp.getInt("vizStyle", 0).coerceIn(0, VizInfo.styles.size - 1); vizPal = sp.getInt("vizPal", 0).coerceIn(0, VizInfo.palettes.size - 1)
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); AudioViz.stop() }
    override fun onVisibilityChanged(v: View, vis: Int) { super.onVisibilityChanged(v, vis); if (vis != View.VISIBLE) AudioViz.stop() }

    fun update(t: String?, a: String?, b: Bitmap?, pl: Boolean, has: Boolean) {
        title = t; artist = a; art = b; playing = pl; hasSession = has; invalidate()
    }

    private fun fitText(s: String, x: Float, y: Float, sizeDp: Float, color: Int, bold: Boolean, maxW: Float, center: Boolean = false) {
        p.style = Paint.Style.FILL; p.color = color; p.textAlign = Paint.Align.LEFT
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.textSize = dp(sizeDp)
        var out = s
        if (p.measureText(out) > maxW) {
            while (out.length > 1 && p.measureText("$out\u2026") > maxW) out = out.dropLast(1)
            out = "$out\u2026"
        }
        canvas0.drawText(out, if (center) x + (maxW - p.measureText(out)) / 2 else x, y + p.textSize * 0.35f, p)
    }
    private lateinit var canvas0: Canvas

    override fun onDraw(c: Canvas) {
        canvas0 = c
        val w = width.toFloat(); val h = height.toFloat()
        val rad = dp(20f)
        val card = RectF(0f, 0f, w, h)
        c.save()
        path.reset(); path.addRoundRect(card, rad, rad, Path.Direction.CW); c.clipPath(path)
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#141C2C"); c.drawRect(card, p)
        val bmp = art
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            // album art as the background, centre-cropped to the card
            val sc = maxOf(w / bmp.width, h / bmp.height)
            val dw = bmp.width * sc; val dh = bmp.height * sc
            c.drawBitmap(bmp, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
            if (fullBleed) {
                p.shader = LinearGradient(0f, h * 0.32f, 0f, h, Color.argb(0, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
                c.drawRect(card, p); p.shader = null
            } else { p.color = Color.argb(150, 0, 0, 0); c.drawRect(card, p) }
        } else {
            p.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#26334D"), Color.parseColor("#111827"), Shader.TileMode.CLAMP)
            c.drawRect(card, p); p.shader = null
        }
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f); p.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), rad, rad, p)

        // controls on the driver's side: previous, play / pause, next, three identical round glass buttons
        val small = w < dp(380f)                       // a narrow card: the text on top, the three buttons in a row underneath
        readViz()
        val wide = w >= dp(520f) && !small
        val vz = wide                                  // wide card: text and small buttons on one side, the visualizer area on the other (the layout stays the same when the visualizer is off)
        val vizDraw = wide && vizOn
        val colW = minOf(dp(250f), w * 0.38f)
        val colX = if (rhd) w - dp(16f) - colW else dp(16f)
        val gap = dp(if (small) 10f else if (vz) 8f else 16f)
        val bsz = if (small) minOf(h * 0.40f, (w - dp(28f) - 2 * gap) / 3) else if (vz) minOf(h * 0.34f, dp(40f)) else h * 0.58f
        val cy = if (small) h - dp(8f) - bsz / 2 else if (vz) h * 0.76f else h / 2
        val total = bsz * 3 + gap * 2
        val left = if (small) (w - total) / 2 else if (vz) colX else if (rhd) w - dp(20f) - total else dp(20f)
        rPrev.set(left, cy - bsz / 2, left + bsz, cy + bsz / 2)
        rPlay.set(rPrev.right + gap, cy - bsz / 2, rPrev.right + gap + bsz, cy + bsz / 2)
        rNext.set(rPlay.right + gap, cy - bsz / 2, rPlay.right + gap + bsz, cy + bsz / 2)
        glass(c, rPrev); glass(c, rPlay); glass(c, rNext)
        icon(c, rPrev, 0); icon(c, rPlay, if (playing) 3 else 2); icon(c, rNext, 1)

        // title and artist on the other side
        val tx = if (small) dp(14f) else if (vz) colX else if (rhd) dp(20f) else rNext.right + dp(24f)
        val maxW = if (small) w - dp(28f) else if (vz) colW - dp(26f) else if (rhd) rPrev.left - tx - dp(24f) else w - tx - dp(20f)
        val bleed = fullBleed && small && hasSession && !title.isNullOrEmpty()
        val y1 = if (bleed) h * 0.52f else if (small) h * 0.17f else if (vz) h * 0.25f else h * 0.38f; val y2 = if (bleed) h * 0.64f else if (small) h * 0.35f else if (vz) h * 0.45f else h * 0.64f
        val s1 = if (small) 15f else if (vz) 17f else 20f; val s2 = if (small) 12f else if (vz) 13f else 14f
        if (hasSession && !title.isNullOrEmpty()) {
            fitText(title!!, tx, y1, s1, Color.WHITE, true, maxW, bleed)
            if (!artist.isNullOrEmpty()) fitText(artist!!, tx, y2, s2, Color.argb(210, 255, 255, 255), false, maxW, bleed)
        } else {
            fitText("Nothing playing", tx, y1, if (small) 14f else 18f, Color.argb(220, 255, 255, 255), true, maxW)
            val hint = when (srcMode) {
                1 -> "Choose a music file with the Sources button"
                2 -> "Choose a radio station with the Sources button"
                else -> if (small) "Bluetooth music shows here" else "Music from the phone over Bluetooth shows here"
            }
            fitText(hint, tx, y2, 12f, Color.argb(150, 255, 255, 255), false, maxW)
        }

        vizRect.setEmpty(); vizBtn.setEmpty(); vizColBtn.setEmpty(); srcBtn.setEmpty(); favBtn.setEmpty()
        if (wide) {
            val bs = dp(26f)
            val compact = l2 && h < dp(130f)       // Layout 2, short card (50/50 window): two columns of buttons, so none is cut off
            val clear = if (compact) dp(92f) else if (l2) dp(60f) else 0f
            if (rhd) vizRect.set(dp(12f), dp(12f), colX - dp(14f) - maxOf(clear, dp(72f)), h - dp(12f)) else vizRect.set(colX + colW + dp(14f) + clear, dp(12f), w - dp(12f), h - dp(12f))
            // the on / off button sits outside the visualizer window, just beside its top corner (and stays there when the visualizer is off)
            val bcx = if (rhd) vizRect.right + dp(23f) else vizRect.left - dp(23f); val bcy = vizRect.top + dp(15f)
            vizBtn.set(bcx - bs / 2, bcy - bs / 2, bcx + bs / 2, bcy + bs / 2)
            if (vizDraw) vizColBtn.set(vizBtn.left, vizBtn.bottom + dp(8f), vizBtn.right, vizBtn.bottom + dp(8f) + bs)
            // the Sources button is always in the third place, just below where the colour button is
            if (compact) {
                // the favourites star and Sources button make a second column beside the visualizer and colour buttons
                val sx = if (rhd) vizBtn.right + dp(6f) else vizBtn.left - dp(6f) - bs
                favBtn.set(sx, vizBtn.top, sx + bs, vizBtn.bottom)
                srcBtn.set(sx, vizBtn.bottom + dp(8f), sx + bs, vizBtn.bottom + dp(8f) + bs)
            } else {
            srcBtn.set(vizBtn.left, vizBtn.bottom + dp(16f) + bs, vizBtn.right, vizBtn.bottom + dp(16f) + 2 * bs)
            // the favourites star sits beside it, on the text side (left of the Sources button; mirrored for right-hand drive)
            if (rhd) favBtn.set(srcBtn.right + dp(6f), srcBtn.top, srcBtn.right + dp(6f) + bs, srcBtn.bottom) else favBtn.set(srcBtn.left - dp(6f) - bs, srcBtn.top, srcBtn.left - dp(6f), srcBtn.bottom)
            }
            if (vizDraw) {
                // real audio when the device allows it and sound is actually coming through, otherwise a simulated beat
                var bands: FloatArray? = null
                if (playing && AudioViz.start(context)) { vizLive = true; if (AudioViz.hasSignal()) bands = AudioViz.bands } else { vizLive = false; if (!playing) AudioViz.stop() }
                val animating = painter.step(System.currentTimeMillis(), playing, bands)
                painter.draw(c, vizRect, vizStyle, vizPal, Skins.look.accent)
                // the name of the colour set, shown for a moment after it is changed
                val since = System.currentTimeMillis() - palNameAt
                if (since < 1600) {
                    p.style = Paint.Style.FILL; p.shader = null; p.color = Color.WHITE; p.alpha = (255 * (1f - (since / 1600f) * (since / 1600f) * (since / 1600f))).toInt().coerceIn(0, 255)
                    p.textSize = dp(13f); p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER; p.setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
                    c.drawText(VizInfo.palettes[vizPal], vizRect.centerX(), vizRect.bottom - dp(10f), p)
                    p.clearShadowLayer(); p.textAlign = Paint.Align.LEFT; p.alpha = 255
                    postInvalidateDelayed(33)
                }
                if (animating) postInvalidateDelayed(33)
            }
            // the on / off button: three little equalizer bars on a glass circle, a little see-through
            c.saveLayerAlpha(RectF(vizBtn.left - dp(8f), vizBtn.top - dp(8f), vizBtn.right + dp(8f), vizBtn.bottom + dp(10f)), 165)
            drawLiquidGlass(c, p, vizBtn, bs / 2, dp(1f), if (vizDraw) Skins.look.accent else null)
            p.style = Paint.Style.FILL; p.shader = null; p.color = Color.WHITE; p.alpha = if (vizDraw) 255 else 190
            val bw = bs * 0.12f; val bx = vizBtn.centerX() - bw * 2.1f; val bb = vizBtn.centerY() + bs * 0.2f
            val hs = floatArrayOf(0.34f, 0.5f, 0.26f)
            for (i in 0..2) c.drawRoundRect(RectF(bx + i * bw * 2.1f, bb - bs * hs[i], bx + i * bw * 2.1f + bw, bb), bw / 2, bw / 2, p)
            p.alpha = 255
            c.restore()
            if (vizDraw) {
                // the colour button: three dots in the colours of the current set; each tap goes to the next set
                c.saveLayerAlpha(RectF(vizColBtn.left - dp(8f), vizColBtn.top - dp(8f), vizColBtn.right + dp(8f), vizColBtn.bottom + dp(10f)), 165)
                drawLiquidGlass(c, p, vizColBtn, bs / 2, dp(1f), null)
                val pl = VizInfo.pal.getOrNull(vizPal)
                val dots = if (pl == null) intArrayOf(Skins.look.accent, Skins.look.accent2, Color.WHITE) else intArrayOf(pl.first(), pl[pl.size / 2], pl.last())
                p.style = Paint.Style.FILL; p.shader = null
                val dr = bs * 0.1f
                for (i in 0..2) { p.color = dots[i]; c.drawCircle(vizColBtn.centerX() + (i - 1) * dr * 2.5f, vizColBtn.centerY(), dr, p) }
                p.alpha = 255
                c.restore()
            }
            drawSrcFav(c, bs)
        } else if (small) {
            // a narrow card (split screen): the two buttons go on the right, in the free space beside the three round buttons; nothing else moves
            val bs = dp(26f)
            val free = w - rNext.right
            if (free >= bs + dp(16f)) {
                val mid = rNext.right + free / 2
                if (free >= 2 * bs + dp(22f)) {
                    favBtn.set(mid - dp(3f) - bs, cy - bs / 2, mid - dp(3f), cy + bs / 2)
                    srcBtn.set(mid + dp(3f), cy - bs / 2, mid + dp(3f) + bs, cy + bs / 2)
                } else {
                    favBtn.set(mid - bs / 2, cy - bs - dp(2f), mid + bs / 2, cy - dp(2f))
                    srcBtn.set(mid - bs / 2, cy + dp(2f), mid + bs / 2, cy + bs + dp(2f))
                }
                drawSrcFav(c, bs)
            }
        }
    }

    /** The Sources button and the favourites star, drawn on their rectangles. [bs] is the button size. */
    private fun drawSrcFav(c: Canvas, bs: Float) {
        // the Sources button: a music note on a glass circle, lit when our own player is the source
        c.saveLayerAlpha(RectF(srcBtn.left - dp(8f), srcBtn.top - dp(8f), srcBtn.right + dp(8f), srcBtn.bottom + dp(10f)), 165)
        drawLiquidGlass(c, p, srcBtn, bs / 2, dp(1f), if (srcMode != 0) Skins.look.accent else null)
        p.style = Paint.Style.FILL; p.shader = null; p.color = Color.WHITE
        val nx = srcBtn.centerX(); val ny = srcBtn.centerY()
        c.drawOval(RectF(nx - bs * 0.22f, ny + bs * 0.06f, nx - bs * 0.02f, ny + bs * 0.22f), p)
        c.drawRect(RectF(nx - bs * 0.06f, ny - bs * 0.24f, nx - bs * 0.02f, ny + bs * 0.14f), p)
        path.reset(); path.moveTo(nx - bs * 0.02f, ny - bs * 0.24f); path.lineTo(nx + bs * 0.2f, ny - bs * 0.1f); path.lineTo(nx + bs * 0.2f, ny - bs * 0.02f); path.lineTo(nx - bs * 0.02f, ny - bs * 0.14f); path.close()
        c.drawPath(path, p)
        c.restore()
        // the favourites button: a yellow star on a glass circle
        c.saveLayerAlpha(RectF(favBtn.left - dp(8f), favBtn.top - dp(8f), favBtn.right + dp(8f), favBtn.bottom + dp(10f)), 190)
        drawLiquidGlass(c, p, favBtn, bs / 2, dp(1f), null)
        p.style = Paint.Style.FILL; p.shader = null; p.color = Color.parseColor("#FFC83D")
        path.reset()
        val sx = favBtn.centerX(); val sy = favBtn.centerY() + bs * 0.02f
        for (k in 0 until 10) {
            val ang = -Math.PI / 2 + k * Math.PI / 5
            val rr = if (k % 2 == 0) bs * 0.31f else bs * 0.13f
            val px = sx + (rr * Math.cos(ang)).toFloat(); val py = sy + (rr * Math.sin(ang)).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); c.drawPath(path, p)
        c.restore()
    }

    /** A transparent round glass button: soft drop shadow, a lit curved face, a bright top gloss and a rim lit from above. */
    private fun glass(c: Canvas, r: RectF) { drawLiquidGlass(c, p, r, r.width() / 2f, dp(1f), null) }

    /** kind: 0 previous, 1 next, 2 play, 3 pause. All four fit the same box, so the three buttons look the same size. */
    private fun icon(c: Canvas, r: RectF, kind: Int) {
        val cx = r.centerX(); val cy = r.centerY(); val u = r.width() * 0.17f
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.shader = null
        p.setShadowLayer(dp(3f), 0f, dp(2f), Color.argb(150, 0, 0, 0))
        when (kind) {
            0, 1 -> {
                val s = if (kind == 0) -1f else 1f
                path.reset()
                path.moveTo(cx + s * u * 1.0f, cy); path.lineTo(cx - s * u * 0.6f, cy - u * 1.25f); path.lineTo(cx - s * u * 0.6f, cy + u * 1.25f); path.close()
                c.drawPath(path, p)
                c.drawRoundRect(RectF(cx + s * u * 1.1f - u * 0.2f, cy - u * 1.25f, cx + s * u * 1.1f + u * 0.2f, cy + u * 1.25f), u * 0.12f, u * 0.12f, p)
            }
            2 -> {
                path.reset(); path.moveTo(cx - u * 0.8f, cy - u * 1.4f); path.lineTo(cx + u * 1.3f, cy); path.lineTo(cx - u * 0.8f, cy + u * 1.4f); path.close()
                c.drawPath(path, p)
            }
            else -> {
                c.drawRoundRect(RectF(cx - u * 1.0f, cy - u * 1.3f, cx - u * 0.25f, cy + u * 1.3f), u * 0.18f, u * 0.18f, p)
                c.drawRoundRect(RectF(cx + u * 0.25f, cy - u * 1.3f, cx + u * 1.0f, cy + u * 1.3f), u * 0.18f, u * 0.18f, p)
            }
        }
        p.clearShadowLayer()
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action == android.view.MotionEvent.ACTION_UP) {
            val s = dp(8f)
            fun hit(r: RectF) = e.x >= r.left - s && e.x <= r.right + s && e.y >= r.top - s && e.y <= r.bottom + s
            when {
                hit(rPlay) -> onToggle?.invoke(); hit(rPrev) -> onPrev?.invoke(); hit(rNext) -> onNext?.invoke()
                !favBtn.isEmpty && favBtn.contains(e.x, e.y) -> onFavs?.invoke()
                !srcBtn.isEmpty && srcBtn.contains(e.x, e.y) -> onSources?.invoke()
                !vizColBtn.isEmpty && hit(vizColBtn) -> { vizPal = (vizPal + 1) % VizInfo.palettes.size; sp.edit().putInt("vizPal", vizPal).apply(); palNameAt = System.currentTimeMillis(); invalidate() }
                !srcBtn.isEmpty && hit(srcBtn) -> onSources?.invoke()
                !favBtn.isEmpty && hit(favBtn) -> onFavs?.invoke()
                !vizBtn.isEmpty && hit(vizBtn) -> { vizOn = !vizOn; sp.edit().putBoolean("vizOn", vizOn).apply(); if (!vizOn) AudioViz.stop(); invalidate() }
                !vizRect.isEmpty && vizOn && vizRect.contains(e.x, e.y) -> { vizStyle = (vizStyle + 1) % VizInfo.styles.size; sp.edit().putInt("vizStyle", vizStyle).apply(); invalidate() }
                else -> onTap?.invoke()
            }
        }
        return true
    }
}


/** Liquid-glass button: soft drop shadow, a frosted translucent fill (tinted when [tint] is given, with a glow), a bright
 *  specular highlight across the top and a rim that is brightest at the upper left and lower right. [u] is one dp in pixels. */
fun drawLiquidGlass(c: Canvas, p: Paint, r: RectF, cr: Float, u: Float, tint: Int?, base: Int = Color.WHITE) {
    p.shader = null; p.style = Paint.Style.FILL; p.pathEffect = null
    p.color = Color.argb(70, 0, 0, 0)
    c.drawRoundRect(RectF(r.left, r.top + 3f * u, r.right, r.bottom + 3f * u), cr, cr, p)
    if (tint != null) {
        val tr = Color.red(tint); val tg = Color.green(tint); val tb = Color.blue(tint)
        p.color = Color.argb(34, tr, tg, tb); c.drawRoundRect(RectF(r.left - 5f * u, r.top - 5f * u, r.right + 5f * u, r.bottom + 5f * u), cr + 4f * u, cr + 4f * u, p)
        p.color = Color.argb(50, tr, tg, tb); c.drawRoundRect(RectF(r.left - 2f * u, r.top - 2f * u, r.right + 2f * u, r.bottom + 2f * u), cr + 2f * u, cr + 2f * u, p)
    }
    val cols = if (tint != null) intArrayOf(Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint)), Color.argb(70, Color.red(tint), Color.green(tint), Color.blue(tint)), Color.argb(105, Color.red(tint), Color.green(tint), Color.blue(tint)))
        else intArrayOf(Color.argb(78, Color.red(base), Color.green(base), Color.blue(base)), Color.argb(20, Color.red(base), Color.green(base), Color.blue(base)), Color.argb(42, Color.red(base), Color.green(base), Color.blue(base)))
    p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, cols, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    c.drawRoundRect(r, cr, cr, p); p.shader = null
    // specular highlight across the top
    val hl = RectF(r.left + r.width() * 0.09f, r.top + 1.6f * u, r.right - r.width() * 0.09f, r.top + r.height() * 0.46f)
    p.shader = LinearGradient(0f, hl.top, 0f, hl.bottom, Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
    c.drawRoundRect(hl, cr * 0.8f, cr * 0.8f, p); p.shader = null
    // rim
    p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * u
    p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
        intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(170, 255, 255, 255)),
        floatArrayOf(0f, 0.4f, 0.6f, 1f), Shader.TileMode.CLAMP)
    c.drawRoundRect(RectF(r.left + 0.65f * u, r.top + 0.65f * u, r.right - 0.65f * u, r.bottom - 0.65f * u), cr, cr, p)
    p.shader = null; p.style = Paint.Style.FILL
}

// heated-seat icon geometry (unit square), traced from the chosen design
private val SEAT_OUTLINE = floatArrayOf(
    0.118f, 0.083f, 0.107f, 0.096f, 0.100f, 0.132f, 0.106f, 0.198f, 0.111f, 0.213f, 0.112f, 0.229f, 0.225f, 0.637f, 0.231f, 0.651f, 0.237f, 0.677f, 0.256f, 0.726f, 0.288f, 0.777f, 0.325f, 0.808f, 0.350f, 0.820f, 0.361f, 0.821f, 0.371f, 0.827f, 0.767f, 0.827f, 0.794f, 0.812f, 0.820f, 0.780f, 0.837f, 0.741f, 0.851f, 0.699f, 0.859f, 0.655f, 0.858f, 0.637f, 0.853f, 0.628f, 0.837f, 0.616f, 0.818f, 0.610f, 0.750f, 0.610f, 0.593f, 0.636f, 0.579f, 0.641f, 0.562f, 0.641f, 0.544f, 0.647f, 0.526f, 0.647f, 0.506f, 0.654f, 0.422f, 0.654f, 0.400f, 0.639f, 0.396f, 0.628f, 0.395f, 0.591f, 0.385f, 0.526f, 0.359f, 0.453f, 0.340f, 0.418f, 0.315f, 0.380f, 0.236f, 0.285f, 0.239f, 0.231f, 0.235f, 0.201f, 0.223f, 0.165f, 0.211f, 0.139f, 0.194f, 0.116f, 0.162f, 0.088f, 0.141f, 0.080f, 0.124f, 0.080f
)
private val SEAT_WAVES = arrayOf(
    floatArrayOf(0.518f, 0.205f, 0.516f, 0.218f, 0.512f, 0.230f, 0.506f, 0.243f, 0.500f, 0.255f, 0.495f, 0.267f, 0.490f, 0.280f, 0.488f, 0.292f, 0.488f, 0.305f, 0.490f, 0.317f, 0.494f, 0.330f, 0.498f, 0.342f, 0.503f, 0.354f, 0.507f, 0.367f, 0.508f, 0.379f, 0.508f, 0.392f, 0.505f, 0.404f, 0.500f, 0.417f, 0.494f, 0.429f, 0.488f, 0.441f, 0.483f, 0.454f, 0.479f, 0.466f, 0.478f, 0.479f, 0.479f, 0.491f, 0.482f, 0.503f, 0.486f, 0.516f, 0.491f, 0.528f, 0.495f, 0.541f, 0.498f, 0.553f, 0.499f, 0.566f, 0.497f, 0.578f),
    floatArrayOf(0.629f, 0.187f, 0.627f, 0.199f, 0.623f, 0.211f, 0.618f, 0.224f, 0.612f, 0.236f, 0.606f, 0.249f, 0.602f, 0.261f, 0.599f, 0.274f, 0.599f, 0.286f, 0.601f, 0.298f, 0.605f, 0.311f, 0.609f, 0.323f, 0.614f, 0.336f, 0.618f, 0.348f, 0.620f, 0.361f, 0.619f, 0.373f, 0.617f, 0.385f, 0.612f, 0.398f, 0.606f, 0.410f, 0.600f, 0.423f, 0.595f, 0.435f, 0.591f, 0.448f, 0.589f, 0.460f, 0.590f, 0.472f, 0.593f, 0.485f, 0.597f, 0.497f, 0.602f, 0.510f, 0.607f, 0.522f, 0.610f, 0.535f, 0.611f, 0.547f, 0.609f, 0.559f),
    floatArrayOf(0.741f, 0.169f, 0.739f, 0.181f, 0.735f, 0.194f, 0.729f, 0.206f, 0.723f, 0.219f, 0.718f, 0.231f, 0.713f, 0.244f, 0.711f, 0.256f, 0.711f, 0.268f, 0.713f, 0.281f, 0.716f, 0.293f, 0.721f, 0.306f, 0.726f, 0.318f, 0.729f, 0.331f, 0.731f, 0.343f, 0.731f, 0.355f, 0.728f, 0.368f, 0.723f, 0.380f, 0.718f, 0.393f, 0.711f, 0.405f, 0.706f, 0.418f, 0.702f, 0.430f, 0.701f, 0.442f, 0.702f, 0.455f, 0.705f, 0.467f, 0.709f, 0.480f, 0.714f, 0.492f, 0.718f, 0.505f, 0.721f, 0.517f, 0.722f, 0.529f, 0.721f, 0.542f)
)

/** Fisker's 14 paint colours: name and the colour the car drawings use. */
/** Swatch colours for the 14 paints, as the 3D car shows them (same order as CAR_PAINTS). */
val CAR_PAINT_SWATCH = listOf("#4b627b","#121a25","#245c99","#e7e8e3","#69747d","#202e40","#d7e0e9","#10151b","#a51e2c","#4d5c50","#a5adb6","#d36529","#485247","#a28368").map { Color.parseColor(it) }

/** The 3D car's wheel designs (ids as in the model): id and name. */
val CAR_WHEELS = listOf("F3A_22" to "22 in F3a SlipStream", "F3B_22" to "22 in F3b SlipStream Black", "F5A_22" to "22 in F5a AirGlider", "F5B_22" to "22 in F5b AirGlider Black", "F6_22" to "22 in F6 Vortex", "F7_20" to "20 in F7 AeroStealth")

val CAR_PAINTS = listOf(
        "Big Sur Blue" to 0xFF1A2130.toInt(), "Black Pearl" to 0xFF17181D.toInt(), "Blue Planet" to 0xFF2A4163.toInt(), "Great White" to 0xFFE6E8EA.toInt(),
        "Horizon Grey" to 0xFF2F3038.toInt(), "Mariana" to 0xFF1D4F7E.toInt(), "Marine Layer" to 0xFFE0E1DA.toInt(), "Night Drive" to 0xFF1A1A1A.toInt(),
        "Red Planet" to 0xFF7F1510.toInt(), "Sea Grass" to 0xFF222B27.toInt(), "Silver Lining" to 0xFF9DB1C9.toInt(), "Solar Orange" to 0xFFC4502A.toInt(),
        "Stealth Green" to 0xFF161F1B.toInt(), "Sun Soaked" to 0xFFC9B0A3.toInt())

/** Process-wide: the startup preset has been put in place for this run of the app. */
object StartupFlag { @Volatile var done = false }
