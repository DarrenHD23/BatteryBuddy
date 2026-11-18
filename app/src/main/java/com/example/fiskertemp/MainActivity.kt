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
        EventLog.add("app start")
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
        if (getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dashDefault", false)) showDashboard(true)
        // optionally go into split screen right after a fresh start (not when the screen is only being rebuilt)
        val startSplit = prefs().getString("startSplit", "")
        if (savedInstanceState == null && !splitOnStartDone && (startSplit == "spotify" || startSplit == "maps")) {
            splitOnStartDone = true
            ui.postDelayed({
                if (!isInMultiWindowMode && !isFinishing) {
                    EventLog.add("start option: split screen with $startSplit at the bottom")
                    openLower(if (startSplit == "spotify") 1 else 0)
                }
            }, 2500)
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
            visibility = View.GONE; onClose = { visibility = View.GONE }
            onPower = { turnOn ->
                val msg = pulse?.sendClimatePower(turnOn) ?: "CAN reader is not running"
                EventLog.add("Climate power test (" + (if (turnOn) "on" else "off") + "): $msg")
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
                EventLog.add("Climate temperature test: ${if (driver) "driver" else "passenger"} $target (was $base): $msg")
                toast(msg)
            }
            onButton = { kind ->
                val msg = pulse?.sendButton(kind) ?: "CAN reader is not running"
                EventLog.add("Climate button test (${listOf("A/C", "defrost", "recirculation", "sync", "auto")[kind]}): $msg")
                toast(msg)
            }
            onMode = { mode ->
                val msg = pulse?.sendAirMode(mode) ?: "CAN reader is not running"
                EventLog.add("Climate air direction test: $mode: $msg")
                toast(msg)
            }
            onWheel = {
                val msg = pulse?.sendWheelHeat() ?: "CAN reader is not running"
                EventLog.add("Heated wheel test: $msg")
                toast(msg)
            }
            paintIdx = prefs().getInt("carPaint", 3)
            onPaint = { i -> prefs().edit().putInt("carPaint", i).apply() }
            onFan = { level ->
                val was = climateView.snap.climFan
                val msg = pulse?.sendFan(level) ?: "CAN reader is not running"
                EventLog.add("Climate fan test: requested $level (car was at $was): $msg")
                ui.postDelayed({ EventLog.add("Climate fan test: asked $level, was $was, car reports ${climateView.snap.climFan} 1.5 s later") }, 1500)
                toast(msg)
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
    private var bgBitmap: android.graphics.Bitmap? = null
    private var bgBitmapIdx = 0

    private fun bgResId(n: Int) = resources.getIdentifier(String.format("bg_%02d", n), "drawable", packageName)

    /** The chosen picture (1..bgCount) or 0 for none, as a drawable that fills any window size without stretching. */
    private fun bgDrawable(scrim: Int): android.graphics.drawable.Drawable? {
        val n = prefs().getInt("bgPic", 0)
        if (n !in 1..bgCount) return null
        if (bgBitmapIdx != n || bgBitmap == null) {
            val id = bgResId(n)
            if (id == 0) return null
            bgBitmap = try { android.graphics.BitmapFactory.decodeResource(resources, id) } catch (e: Throwable) { null }
            bgBitmapIdx = n
        }
        val bmp = bgBitmap ?: return null
        return CoverDrawable(bmp, scrim)
    }

    private fun applyBgPic() {
        if (!::rootScroll.isInitialized) return
        bgDrawable(Color.argb(95, 0, 0, 0))?.let { rootScroll.background = it }
        if (::dashRoot.isInitialized) applyDashTheme(dashDay)
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
        // --- Temperature unit (first, so it is always visible)
        box.add(TextView(this).apply { text = "Temperature unit"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 0)
        val rc = RadioButton(this).apply { text = "Celsius (\u00B0C)"; id = View.generateViewId(); isChecked = !useFahrenheit }
        val rf = RadioButton(this).apply { text = "Fahrenheit (\u00B0F)"; id = View.generateViewId(); isChecked = useFahrenheit }
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(rc); addView(rf) }
        rg.setOnCheckedChangeListener { _, checkedId -> setFahrenheit(checkedId == rf.id) }
        box.add(rg, 6)

        lateinit var dialog: AlertDialog
        // --- Skin
        box.add(TextView(this).apply { text = "Skin"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        box.add(Button(this).apply {
            isAllCaps = false
            text = "Skin: ${Skins.name(Skins.current)}   \u203A"
            setOnClickListener { dialog.dismiss(); showSkinPicker() }
        }, 6)

        box.add(TextView(this).apply { text = "Dashboard theme"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val cur = prefs().getString("dispTheme", "night")
        val rn = RadioButton(this).apply { text = "Night (dark)"; id = View.generateViewId(); isChecked = cur == "night" }
        val rd = RadioButton(this).apply { text = "Day (light)"; id = View.generateViewId(); isChecked = cur == "day" }
        val ra = RadioButton(this).apply { text = "Follow the headlights (on = night, off = day)"; id = View.generateViewId(); isChecked = cur == "auto" }
        val rtg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(rn); addView(rd); addView(ra) }
        rtg.setOnCheckedChangeListener { _, checkedId ->
            prefs().edit().putString("dispTheme", when (checkedId) { rd.id -> "day"; ra.id -> "auto"; else -> "night" }).apply()
            updateDashTheme(true)
        }
        box.add(rtg, 6)
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
                bgThumb(n)?.let { setImageBitmap(it) } ?: setBackgroundColor(Color.LTGRAY)
            }
            v.setOnClickListener { prefs().edit().putInt("bgPic", n).apply(); applyTheme(themeIndex); dialog.dismiss() }
            return FrameLayout(this).apply {
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(10).toFloat(); setStroke(dp(if (sel) 3 else 0), Color.parseColor("#2AA8FF")) }
                addView(v, FrameLayout.LayoutParams(dp(68), dp(120)))
            }
        }
        picRow.addView(tile(0))
        for (n in 1..bgCount) if (bgResId(n) != 0) picRow.addView(tile(n))
        box.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(picRow) }, 8)
        box.add(TextView(this).apply { text = "Speed limit sign style"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val curSign = prefs().getString("signStyle", "us")
        val sus = RadioButton(this).apply { text = "USA / Canada (rectangle)"; id = View.generateViewId(); isChecked = curSign != "eu" }
        val seu = RadioButton(this).apply { text = "Europe / UK (round, red ring)"; id = View.generateViewId(); isChecked = curSign == "eu" }
        val sgp = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(sus); addView(seu) }
        sgp.setOnCheckedChangeListener { _, checkedId -> prefs().edit().putString("signStyle", if (checkedId == seu.id) "eu" else "us").apply() }
        box.add(sgp, 6)
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
        box.add(TextView(this).apply { text = "Dashboard"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        box.add(CheckBox(this).apply {
            text = "Show the dashboard when the app starts"; isChecked = prefs().getBoolean("dashDefault", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("dashDefault", on).apply() }
        }, 4)
        box.add(TextView(this).apply { text = "When the app starts"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 14)
        val ssNone = RadioButton(this).apply { text = "Full screen"; id = View.generateViewId() }
        val ssSpotify = RadioButton(this).apply { text = "Split screen: this app on top, Spotify at the bottom"; id = View.generateViewId() }
        val ssMaps = RadioButton(this).apply { text = "Split screen: this app on top, Maps at the bottom"; id = View.generateViewId() }
        val ssg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(ssNone); addView(ssSpotify); addView(ssMaps) }
        when (prefs().getString("startSplit", "")) { "spotify" -> ssSpotify.isChecked = true; "maps" -> ssMaps.isChecked = true; else -> ssNone.isChecked = true }
        ssg.setOnCheckedChangeListener { _, checkedId ->
            prefs().edit().putString("startSplit", when (checkedId) { ssSpotify.id -> "spotify"; ssMaps.id -> "maps"; else -> "" }).apply()
        }
        box.add(ssg, 4)
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
        fun relayout() { layoutKey = ""; if (::dashRoot.isInitialized && dashRoot.width > 0) { val dn = resources.displayMetrics.density; adaptDashLayout((dashRoot.width / dn).toInt(), (dashRoot.height / dn).toInt()) } }
        box.add(CheckBox(this).apply {
            text = "Hide the traffic light"; isChecked = prefs().getBoolean("hideLight", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideLight", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Hide the speed limit sign"; isChecked = prefs().getBoolean("hideSign", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideSign", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Hide the trip information"; isChecked = prefs().getBoolean("hideTrip", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("hideTrip", on).apply(); relayout() }
        }, 4)
        val moreCb = CheckBox(this).apply {
            text = "Show more app buttons (6 instead of 4)"; isChecked = prefs().getBoolean("moreBtns", false)
            visibility = if (prefs().getBoolean("iconBtns", false)) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("moreBtns", on).apply(); relayoutDash(); refreshLowerLabels(); paintLowerTabs() }
        }
        box.add(CheckBox(this).apply {
            text = "Show icons instead of names on the app buttons"; isChecked = prefs().getBoolean("iconBtns", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("iconBtns", on).apply()
                moreCb.visibility = if (on) View.VISIBLE else View.GONE
                relayoutDash(); refreshLowerLabels(); paintLowerTabs()
            }
        }, 4)
        box.add(moreCb, 4)
        box.add(Button(this).apply {
            text = "Speedometer Adjustment"; isAllCaps = false
            setOnClickListener { showSpeedAdjust() }
        }, 8)
        box.add(Button(this).apply {
            text = "Join the Beta"; isAllCaps = false
            setOnClickListener { showBetaGate() }
        }, 8)
        box.add(Button(this).apply {
            text = "Check for updates (this is build ${Updater.installedBuild(this@MainActivity)})"; isAllCaps = false
            setOnClickListener { checkForUpdate(true) }
        }, 8)
        box.add(Button(this).apply {
            text = "Clear an app button"; isAllCaps = false
            setOnClickListener {
                val names = Array(slotCount) { i -> "Button ${i + 1}: " + (if (slotPkg(i).isEmpty()) "(no app)" else appLabelOf(slotPkg(i))) }
                darkDialog().setTitle("Clear which button?").setItems(names) { _, i ->
                    prefs().edit().remove("slotPkg$i").apply(); refreshLowerLabels()
                    EventLog.add("lower button ${i + 1} cleared")
                }.setNegativeButton("Cancel", null).showDark()
            }
        }, 8)
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
            text = "Speed gauge on the left (power on the right)"; isChecked = prefs().getBoolean("speedLeft", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("speedLeft", on).apply(); applyGaugeSides() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Safe mode: never write the CAN chip filters, only read them (climate kW and the heat pump may show --)"; isChecked = prefs().getBoolean("canSafe", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("canSafe", on).apply(); pulse?.safeMode = on; toast("Takes effect the next time the app starts") }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "TEST: let the Climate screen control the car: power, temperature, fan speed, air direction, A/C, defrost, recirc, sync, auto and the heated wheel. This SENDS commands onto the car's CAN bus (everything else in the app stays read-only). Takes effect after the app restarts"
            isChecked = prefs().getBoolean("climateTx", false)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("climateTx", on).apply()
                EventLog.add("Setting: climate fan test control " + (if (on) "ON" else "off"))
                toast("Takes effect the next time the app starts")
            }
        }, 4)
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
                val tv = TextView(this@MainActivity).apply { text = EventLog.text(); textSize = 11f; setTextColor(Color.parseColor("#F0F3F8")); typeface = Typeface.MONOSPACE; setPadding(dp(12), dp(8), dp(12), dp(8)) }
                val sv = ScrollView(this@MainActivity).apply { addView(tv); post { fullScroll(View.FOCUS_DOWN) } }
                darkDialog().setTitle("Event log (newest at the bottom)").setView(sv)
                    .setNeutralButton("Copy") { _, _ ->
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", EventLog.text()))
                        toast("Copied")
                    }.setPositiveButton("Close", null).showDark()
            }
        }, 8)
        box.add(Button(this).apply {
            text = "Test root access"; isAllCaps = false
            setOnClickListener { Thread { val r = rootCheck(); ui.post { toast(r ?: "Root access is working (${Root.describe()})") } }.start() }
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
        box.add(CheckBox(this).apply {
            text = "Stop reading the CAN bus while this app is not visible (the regen counter only counts while visible)"; isChecked = prefs().getBoolean("pauseHidden", true)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("pauseHidden", on).apply() }
        }, 0)
        box.add(CheckBox(this).apply {
            text = "Offer to close the Pulse app when the dashboard opens"; isChecked = prefs().getBoolean("askClosePulse", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("askClosePulse", on).apply(); if (on) pulseAsked = false }
        }, 0)
        box.add(CheckBox(this).apply {
            text = "Flip power direction (if drive shows as negative)"; isChecked = prefs().getBoolean("flipPower", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("flipPower", on).apply() }
        }, 0)

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

        box.add(TextView(this).apply { text = "Data source"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        val sd = RadioButton(this).apply { text = "vLinker dongle (Bluetooth)"; id = View.generateViewId(); isChecked = !useCan }
        val sp = RadioButton(this).apply { text = "Pulse CAN bus (run this app on the Pulse)"; id = View.generateViewId(); isChecked = useCan }
        val sg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(sd); addView(sp) }
        sg.setOnCheckedChangeListener { _, checkedId -> applySource(checkedId == sp.id) }
        box.add(sg, 6)

        box.add(TextView(this).apply {
            text = "This project uses software from Haibin Wen and SUNNYPILOT LLC and is licensed under a custom license requiring permission for use."
            textSize = 11f; setTextColor(Color.parseColor("#8E99A8"))
        }, 24)
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
        EventLog.add("skin: ${Skins.name(id)}")
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

    private fun showSkinPicker() {
        val cur = Skins.current
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var d: AlertDialog
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
                setOnClickListener { chooseSkin(sk.id); d.dismiss() }
            }
            col.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        d = darkDialog().setTitle("Skin").setView(ScrollView(this).apply { addView(col) }).setNegativeButton("Close", null).create()
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
            else if (s.packVolts == null) "Car awake. Voltage and current not received (the Pulse's CAN filters may need root)"
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
    private lateinit var settingsBtn: Button
    private lateinit var dashSpeedGauge: ArcGaugeView
    private lateinit var dashPowerGauge: ArcGaugeView
    private lateinit var dashLight: TrafficLightView
    private lateinit var dashLightDist: TextView
    private var lightColor = 0
    private var lightSeenAt = 0L
    private var lightShown = false
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
        lowerBox.visibility = View.VISIBLE
    }

    private fun updateResText() {
        resBtn.text = if (screenH == 0) "Screen size" else if (resCompact) "\u21C5 $screenH" else "Screen: 1080 \u00D7 $screenH  \u21C5"
    }
    private var screenH = 0       // 1920 or 1784 once known
    private val maxSlots = 6
    /** Four app buttons, or six when icons are shown and "more buttons" is on. */
    private val slotCount get() = if (prefs().getBoolean("iconBtns", false) && prefs().getBoolean("moreBtns", false)) maxSlots else 4
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
    private fun showBetaGate() {
        val pw = EditText(this).apply {
            hint = "Password"; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#8893A3"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val holder = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(pw, LinearLayout.LayoutParams(-1, -2)) }
        val d = darkDialog().setTitle("Join the Beta").setMessage("Enter the beta password.").setView(holder)
            .setPositiveButton("OK", null).setNegativeButton("Cancel", null).showDark()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (pw.text.toString() == "Pulse!!!") { d.dismiss(); showBetaOptions() }
            else { pw.setText(""); pw.error = "Wrong password" }
        }
    }

    private fun showBetaOptions() {
        val cb = CheckBox(this).apply {
            text = "Get beta builds (test versions, before everyone else)"; setTextColor(Color.WHITE)
            isChecked = prefs().getBoolean("betaOn", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("betaOn", on).apply(); EventLog.add("beta builds " + (if (on) "on" else "off")) }
        }
        val holder = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(cb) }
        darkDialog().setTitle("Beta").setMessage("Beta builds may have bugs. Turn this off to get only the tested releases.").setView(holder)
            .setPositiveButton("Done", null).setNeutralButton("Check now") { _, _ -> checkForUpdate(true) }.showDark()
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

    private fun buildDashboard(): FrameLayout {
        Skins.current = prefs().getInt("skin", 7)
        val f = FrameLayout(this)
        f.visibility = View.GONE
        f.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Skins.look.bgTop, Skins.look.bgBottom))
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
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            for (i in 0 until maxSlots) {
                addView(TextView(context).apply {
                    textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    minHeight = dp(42); minWidth = dp(44); setPadding(dp(6), 0, dp(6), 0)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener { if (slotPkg(i).isEmpty()) pickLowerApp(i) else openLower(i) }
                    setOnLongClickListener { pickLowerApp(i); true }
                })
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
                if (climateView.visibility == View.VISIBLE) climateView.visibility = View.GONE else openClimate()
            }
        }
        mediaBtn = TextView(this).apply {
            text = "Media"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            background = chipBg(12)
            setOnClickListener {
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
            addView(lowerTabs, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
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
            onPrev = { mediaWatcher?.prev(); ui.removeCallbacks(hideMediaPopup) }
            onToggle = { mediaWatcher?.toggle(); ui.removeCallbacks(hideMediaPopup) }
            onNext = { mediaWatcher?.next(); ui.removeCallbacks(hideMediaPopup) }
            onTap = { ui.removeCallbacks(hideMediaPopup); openMusicApp(false) }   // the card stays on screen
        }
        f.addView(mediaPopup, FrameLayout.LayoutParams(-1, dp(132), Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(66)) })
        mediaCard = MediaCardView(this).apply {
            visibility = View.GONE
            onPrev = { mediaWatcher?.prev() }
            onToggle = { mediaWatcher?.toggle() }
            onNext = { mediaWatcher?.next() }
            onTap = { openMusicApp(false) }
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
        updateBtn = TextView(this).apply {
            text = "Update Available"; textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0); visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8761F")); cornerRadius = dp(15).toFloat(); setStroke(dp(1), Color.argb(170, 255, 255, 255)) }
            setOnClickListener { pendingUpdate?.let { showUpdateDialog(it) } }
        }
        f.addView(updateBtn, FrameLayout.LayoutParams(-2, dp(30), Gravity.TOP or Gravity.START).apply { setMargins(dp(160), dp(10), 0, 0) })
        return f
    }

    private val dashTick = object : Runnable {
        override fun run() {
            renderDash()
            if (dashShown) ui.postDelayed(this, 200)
        }
    }

    /** Day or night for the dashboard, from the setting (and the headlights when set to follow them). */
    private fun wantDay(): Boolean = when (prefs().getString("dispTheme", "night")) {
        "day" -> true
        "auto" -> canSnap.headlights == false   // lights off = day; on or unknown = night
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
            bgDrawable(Color.argb(125, 255, 255, 255)) ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E6ECF4")))
        else bgDrawable(Color.argb(120, 0, 0, 0)) ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Skins.look.bgTop, Skins.look.bgBottom))
        dashSpeedGauge.day = day; dashPowerGauge.day = day; dashStrip.day = day; dashStrip.invalidate()
        if (::tripPanel.isInitialized) { tripPanel.day = day; tripPanel.invalidate() }
        if (::chargeBar.isInitialized) { chargeBar.day = day; chargeBar.invalidate() }
        if (::dashClock.isInitialized) { dashClock.day = day; dashClock.invalidate() }
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        dashHeading.setTextColor(ink)
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
    private fun ensureMediaWatcher() {
        if (mediaWatcher != null) return
        mediaWatcher = MediaWatcher(this, ui, { Thread { rootRun("cmd notification allow_listener " + ComponentName(this, MainActivity::class.java).flattenToString()) }.start() }, { m -> EventLog.add(m) }).also { w ->
            w.onChange = {
                mediaCard.update(w.title, w.artist, w.art, w.playing, w.hasSession)
                mediaPopup.update(w.title, w.artist, w.art, w.playing, w.hasSession)
            }
        }
    }

    private val hideMediaPopup = Runnable { if (::mediaPopup.isInitialized) mediaPopup.visibility = View.GONE }

    /**
     * The Media pop-up card: it sits in the free space between the battery bar (or trip panel) and the stat rows,
     * never over the stat rows. With no usable space it goes just above the bottom buttons as before.
     */
    private fun placeMediaPopup() {
        if (!::mediaPopup.isInitialized) return
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
            if (half && ref != null && ref.width > 0 && rowV != null && root.height > 0) {
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
                climateView.fanControl = prefs().getBoolean("climateTx", false)
                placeClimate()
                climateView.bringToFront(); climateView.visibility = View.VISIBLE
                if (::cautionView.isInitialized && cautionView.visibility == View.VISIBLE) cautionView.bringToFront()   // the beta notice stays on top
    }

    private fun placeClimate() {
        val lp = climateView.layoutParams as FrameLayout.LayoutParams
        val full = try {
            android.os.Build.VERSION.SDK_INT >= 30 && windowManager.currentWindowMetrics.bounds.height() >= windowManager.maximumWindowMetrics.bounds.height() * 0.8
        } catch (e: Exception) { false }
        if (full && edgeBox.height > 0) {
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
        val narrow = wDp < 800
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
        val base = 62                                // the strip sits above the bottom bar (app buttons on the left, Hide / Exit on the right)
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
        val topM = if (midLevel > 0) 20 else 8
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
        val wInner = (wDp - 16).toFloat()
        val arcMode = prefs().getString("arcStyle", "mirror")
        val ovL: Int; val ovR: Int
        var edgeL = 0; var edgeR = 0; var sameMid = -1f
        if (arcMode == "left" || arcMode == "right") {
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
        val clockH = if (band >= 30f) band.toInt().coerceAtMost(110) else if (third) 24 else 0
        val rhd = prefs().getBoolean("rhd", false)
        val key = "$hDp/$narrow/$ti/$midLevel/$third/$half/$tall/$midRows/$clockH/${gaugeWdp.toInt()}/$ovL/$ovR/$edgeL/$edgeR/$up/$rhd/$hideBar/$hideTrip/${prefs().getBoolean("fullMedia", true)}/${prefs().getBoolean("iconBtns", false)}/${prefs().getBoolean("moreBtns", false)}"
        if (key == layoutKey) return
        layoutKey = key
        EventLog.add("layout ${wDp}x${hDp}dp tier=$ti trip=$midRows third=$third half=$half tall=$tall")
        compact = ti > 0 || third
        val short = midLevel > 0
        // a third-height window (1/3 split): the brake text goes back under the speed limit sign, with its full wording
        val thirdWin = try {
            android.os.Build.VERSION.SDK_INT >= 30 && windowManager.currentWindowMetrics.bounds.height() < windowManager.maximumWindowMetrics.bounds.height() * 0.42
        } catch (e: Exception) { false }
        val brakeUnder = short && thirdWin
        val stripBottom = base + tripOff
        val rowBottom = if (t.showStrip) stripBottom + t.strip + 4 else base
        tripMidRows = midRows
        chargeBar.visibility = if (!hideBar) View.VISIBLE else if (midLevel == 0) View.INVISIBLE else View.GONE
        // with plenty of height the panel stays a little shorter and sits just above the battery bar
        (tripSpacer.layoutParams as LinearLayout.LayoutParams).weight = if (midRows >= 3 && midLevel > 0) 0.28f else 0f
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
            width = dp(gaugeWdp.toInt() - 6); height = dp(clockH)
            gravity = Gravity.TOP or (if (rhd) Gravity.END else Gravity.START)
            val ct = if (third && clockH == 24) 2 else topM
            if (rhd) setMargins(0, dp(ct), dp(14), 0) else setMargins(dp(14), dp(ct), 0, 0)
        }
        dashClock.requestLayout()
        tripPanel.visibility = if (midRows > 0) View.VISIBLE else View.GONE
        tripBtn.visibility = if (midRows > 0 || hideTrip) View.GONE else View.VISIBLE
        (dashSlot.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (midLevel == 2) 48 else if (short) 58 else 92); topMargin = dp(if (brakeUnder) 0 else 4) }
        dashSlot.requestLayout()
        // short windows: READY, the light distance and the brake pill sit beside the sign instead of stacking, so the trip panel gets the height
        // READY / light distance / brake placement by how much height the middle column has
        //  level 0 (roomy): READY under the battery bar when tall, light distance beside the sign, brake pill in the column
        //  level 1 (a third-height window): READY centred above the sign, light distance below it, brake pill beside the sign
        //  level 2 (very short): all three beside the sign
        val readyBelow = tall && midLevel == 0
        // a third-height window with the battery bar hidden: READY goes directly under the trip panel
        val readyUnderPanel = midLevel == 1 && hideBar && !readyBelow
        val modeKey = "$midLevel/$readyBelow/$readyUnderPanel/$brakeUnder"
        readyBeside = midLevel == 1 && brakeUnder && !readyBelow && !readyUnderPanel
        if (statusMode != modeKey) {
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
        if (readyBelow) {
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
        val showMedia = tall && climH >= 380
        val rowBottomM = rowBottom
        val stripBottomM = stripBottom
        val lowerBoxBottomM = 10
        val lowerBottomM = lowerBottom
        if (dashShown && wasFullOrder && !showMedia) {
            // leaving full screen for split screen: the Climate panel closes, and the media card stays up as the floating card
            climateView.visibility = View.GONE
            if (prefs().getBoolean("fullMedia", true)) ui.post {
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
        else if (showMedia && !wasFullOrder && dashShown) edgeBox.post { if (dashShown) openClimate() }   // entering full screen: the Climate section comes up by itself
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
        (resBtn.layoutParams as LinearLayout.LayoutParams).apply { rightMargin = dp(6) }
        (lowerTabs.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0 }
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
        val lowerKids = listOf(climateBtn, mediaBtn, resBtn, tripBtn, lowerTabs)
        lowerBox.removeAllViews()
        for (v in if (rhd) lowerKids.reversed() else lowerKids) lowerBox.addView(v)
        (mediaBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = if (rhd) dp(6) else 0; rightMargin = if (rhd) 0 else dp(6) }
        (climateBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = if (rhd) dp(6) else 0; rightMargin = if (rhd) 0 else dp(6) }
        (resBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = if (rhd) dp(6) else 0; rightMargin = if (rhd) 0 else dp(6) }
        (tripBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = if (rhd) dp(6) else 0; rightMargin = if (rhd) 0 else dp(6) }
        (btnBar.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM or (if (rhd) Gravity.START else Gravity.END)
            if (rhd) setMargins(edgeOff, 0, 0, dp(16)) else setMargins(0, 0, dp(8), dp(16))
        }
        val barKids = listOf(settingsBtn, dashBtn, exitBtn)
        btnBar.removeAllViews()
        for ((i, v) in (if (rhd) barKids.reversed() else barKids).withIndex()) {
            btnBar.addView(v, LinearLayout.LayoutParams(-2, dp(36)).apply { if (i < barKids.size - 1) rightMargin = dp(8) })
        }
        btnBar.requestLayout(); lowerBox.requestLayout()
        refreshLowerLabels()
        (poolBar.layoutParams as FrameLayout.LayoutParams).apply { setMargins(dp(8), 0, dp(8), dp(lowerBottomM)) }
        (dashGaugeRow.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            if (tall) { height = dp(wantH.toInt()); setMargins(dp(8), dp(topM), dp(8), 0) }
            else { height = FrameLayout.LayoutParams.MATCH_PARENT; setMargins(dp(8), dp(topM - up), dp(8), dp(gaugeBottom + up)) }
        }
        (dashLight.layoutParams as FrameLayout.LayoutParams).apply { width = dp(if (midLevel == 2) 22 else if (short) 26 else 42); height = dp(if (midLevel == 2) 46 else if (short) 56 else 88) }
        dashReady.textSize = if (midLevel == 2 || (midLevel == 1 && brakeUnder)) 11f else if (midLevel == 1) 12f else 15f
        dashReady.letterSpacing = if (midLevel == 1 && brakeUnder && !readyUnderPanel) 0.06f else 0.18f
        // in a short window the gauges reach the top corners, so the mode badge gets its own strip above them
        dashMode.textSize = if (short) 9f else 11f
        dashMode.minWidth = dp(if (short) 60 else 84)
        dashMode.setPadding(dp(if (short) 6 else 10), dp(if (short) 1 else 3), dp(if (short) 6 else 10), dp(if (short) 1 else 3))
        (dashTop.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or (if (rhd) Gravity.START else Gravity.END)
            if (rhd) setMargins(dp(8), dp(if (short) 2 else 6), 0, 0) else setMargins(0, dp(if (short) 2 else 6), dp(8), 0)
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
        (dashSign.layoutParams as FrameLayout.LayoutParams).apply { width = dp(if (midLevel == 2) 38 else if (short) 48 else 64); height = dp(if (midLevel == 2) 38 else if (short) 48 else 64) }
        poolBar.requestLayout(); lowerBox.requestLayout()
        dashSignSrc.visibility = if (midLevel == 0) View.VISIBLE else View.GONE
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
        if (ready) {
            var others = 0
            for (b in listOf(climateBtn, mediaBtn, resBtn, tripBtn)) if (b.visibility != View.GONE) { b.measure(un, un); others += b.measuredWidth + dp(6) }
            btnBar.measure(un, un)
            val totalW = if (::dashRoot.isInitialized && dashRoot.width > 0) dashRoot.width else resources.displayMetrics.widthPixels
            val room = totalW - dp(8 + 20 + 16) - others - btnBar.measuredWidth - dp(6 + 6 * slotCount)
            per = (room / slotCount).coerceIn(dp(44), dp(120))
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
    }

    /** Let the user choose which installed app a lower button opens (long press on a button changes it later). */
    private fun pickLowerApp(slot: Int) {
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
                    prefs().edit().putString("slotPkg$slot", apps[pos].key).apply()
                    EventLog.add("lower button ${slot + 1} set to ${apps[pos].key}")
                    refreshLowerLabels(); dlg?.dismiss()
                }
                val b = darkDialog().setTitle("Choose the app for this button").setView(list).setNegativeButton("Cancel", null)
                if (slotPkg(slot).isNotEmpty()) b.setNeutralButton("Clear button") { _, _ ->
                    prefs().edit().remove("slotPkg$slot").apply(); refreshLowerLabels()
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
        if (::settingsBtn.isInitialized) settingsBtn.background = chipBg(22)
        if (::exitBtn.isInitialized) exitBtn.background = chipBg(22)
        if (::resBtn.isInitialized) resBtn.background = chipBg(12)
        if (::climateBtn.isInitialized) climateBtn.background = chipBg(12)
        if (::mediaBtn.isInitialized) mediaBtn.background = chipBg(12)
        if (::tripBtn.isInitialized) tripBtn.background = chipBg(12)
        if (::lowerTabs.isInitialized) { lowerTabs.background = chipBg(18); paintLowerTabs() }
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
        val clp = dashClock.layoutParams as FrameLayout.LayoutParams
        val clockOn = dashClock.visibility == View.VISIBLE
        val rhd = prefs().getBoolean("rhd", false)
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
            val r = Updater.latest(prefs().getBoolean("betaOn", false))
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
        updPos?.setOnClickListener { startUpdate(r, d, pt, bar) }
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
            EventLog.add("split shell command (task $myTask): ${splitOut.ifEmpty { "no output" }}")
            ui.post { waitForSplit(i, 0, false) }
        }.start()
    }

    private var splitOut = ""

    private fun waitForSplit(i: Int, tries: Int, second: Boolean) {
        if (isInMultiWindowMode) { EventLog.add("split screen is on"); ui.postDelayed({ launchLower(i) }, 500); return }
        if (tries >= 15) {
            if (!second) {
                // the shell command did not do it: try the accessibility action
                EventLog.add("shell command did not split; trying the accessibility action")
                Thread {
                    val ok = ensureSplitService()
                    ui.post {
                        if (!ok) { EventLog.add("split screen did not start"); toast("Could not start split screen. Use the Pulse's split-screen controls (this app on top), then tap again. Shell said: ${splitOut.ifEmpty { "nothing" }}"); return@post }
                        val r = SplitService.instance?.splitScreen()
                        EventLog.add("split screen action returned $r")
                        waitForSplit(i, 0, true)
                    }
                }.start()
            } else {
                EventLog.add("split screen did not start")
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
        dashShown = on
        paintEdges()
        ui.removeCallbacks(dashTick)
        dashRoot.visibility = if (on) View.VISIBLE else View.GONE
        rootScroll.visibility = if (on) View.GONE else View.VISIBLE
        dashBtn.text = if (on) "Hide" else "Show Dashboard"
        skinBtn.visibility = if (on) View.VISIBLE else View.GONE
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
            if (fullOrderNow) edgeBox.post { if (dashShown && climateView.visibility != View.VISIBLE) openClimate() }
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

        val v = s.packVolts
        val a = s.packAmps
        val kw = if (v != null && a != null) v * a / 1000.0 * (if (prefs().getBoolean("flipPower", false)) -1 else 1) else null
        dashPowerGauge.valueText = kw?.let { String.format("%.1f", it) } ?: "--"
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

        dashBrake.visibility = if (s.brakeBcm ?: s.brakeVcu ?: false) View.VISIBLE else View.INVISIBLE
        updateDashTheme()
        fun t(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 0)
        val nowMs = System.currentTimeMillis()
        // speed limit sign: shown in the dashboard's own speed unit; fades in and out with the car's knowledge of the limit
        val lim = if (prefs().getBoolean("hideSign", false)) null else s.limit
        if (lim != null) {
            val mphWanted = speedMph()
            val shownLim = when {
                s.limitMph == mphWanted -> lim
                mphWanted -> Math.round(lim * 0.621371 / 5.0).toInt() * 5     // km/h -> mph, to the nearest 5
                else -> Math.round(lim * 1.609344 / 5.0).toInt() * 5          // mph -> km/h, to the nearest 5
            }
            signText = shownLim.toString()
            signSeenAt = nowMs
            dashSign.number = signText
            dashSign.metric = !mphWanted
            dashSign.euro = prefs().getString("signStyle", "us") == "eu"
            dashSign.invalidate()
            val tm = s.tsrMode ?: 0
            dashSignSrc.text = when (tm) { 2 -> "Camera + Map"; 3 -> "Camera"; 4 -> "Map"; 5 -> "sign data error"; else -> "" }
            dashSignSrc.setTextColor(if (dashDay) Color.argb(200, 20, 32, 46) else Color.argb(200, 255, 255, 255))
            if (tm != lastTsrMode) { lastTsrMode = tm; EventLog.add("speed limit source: " + (dashSignSrc.text.ifEmpty { "unknown" })) }
        }
        val lc = if (prefs().getBoolean("hideLight", false)) null else s.lightColor
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
        val wantSign = (lim != null || nowMs - signSeenAt < 1500) && !seen
        if (wantSign != signShown) {
            signShown = wantSign
            dashSignSrc.animate().alpha(if (wantSign) 1f else 0f).setDuration(if (wantSign) 300L else 250L).start()
            dashSign.animate().alpha(if (wantSign) 1f else 0f)
                .setStartDelay(if (wantSign && lightEnded) 700L else 0L)
                .setDuration(if (wantSign) 300L else 250L).start()
        }
        if (readyBeside) {
            val rlp = dashReady.layoutParams as? FrameLayout.LayoutParams
            val g = if (!wantSign && !seen) Gravity.CENTER else (Gravity.CENTER_VERTICAL or Gravity.START)
            if (rlp != null && rlp.gravity != g) { rlp.gravity = g; rlp.leftMargin = if (g == Gravity.CENTER) 0 else dp(4); dashReady.layoutParams = rlp }
        }
        dashLightDist.text = if (lc == null) "" else s.lightDistM?.let { "$it m" } ?: ""
        val dm = s.driveMode
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
        val hd = s.headingDeg
        dashHeading.text = if (hd == null) "" else {
            val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            "${names[((hd + 22.5) / 45.0).toInt() % 8]}  ${hd.toInt()}\u00B0"
        }
        val demand = when (s.thermalDemand) { 0 -> "starting up"; 1 -> "heating"; 2 -> "cooling"; 3 -> "cycling"; else -> "--" }
        fun tc(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 0)
        pulse?.flipPower = prefs().getBoolean("flipPower", false)
        val rangeText = s.rangeKm?.let { if (speedMph()) String.format("%.0f mi", it * 0.621371) else String.format("%.0f km", it) } ?: "--"
        val everything = canStats(s)
        dashStrip.setItems(everything)
        dashRow.setItems(everything)
        if (tripPanel.visibility == View.VISIBLE || tripDlgView != null) {
            tripPanel.setCells(tripCells(s, true).take(if (tripMidRows >= 3) 6 else 4))
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

    /** Colour for the efficiency readout from Wh/km: green up to 100, through yellow and orange to red at 400 and above. */
    private fun effTint(k: Double?): Int? {
        if (k == null) return null
        val wh = k * 10.0
        val pos = doubleArrayOf(0.0, 100.0, 150.0, 200.0, 400.0)
        val col = intArrayOf(Color.parseColor("#2ECC71"), Color.parseColor("#4FD66A"), Color.parseColor("#F2D338"), Color.parseColor("#FF9F1C"), Color.parseColor("#FF3B4E"))
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
    private fun startHeartbeat() {
        Thread {
            while (true) {
                try { Thread.sleep(30_000) } catch (e: InterruptedException) { return@Thread }
                val sent = System.currentTimeMillis()
                ui.post { uiPong = System.currentTimeMillis() }
                try { Thread.sleep(2_000) } catch (e: InterruptedException) { return@Thread }
                val answered = uiPong >= sent
                val n = pulse?.frames ?: 0L
                EventLog.add("alive up=" + uptimeSecs() + "s " + EventLog.system() + " frames=$n ui=" + (if (answered) "ok" else "NOT ANSWERING") + " " + CanDiag.quick())
            }
        }.also { it.isDaemon = true }.start()
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
        super.onResume()
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

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
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

    override fun onDestroy() { EventLog.add("app stopped"); mediaWatcher?.stop(); pulse?.stop(); ui.removeCallbacksAndMessages(null); close(); super.onDestroy() }

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
        val gauge: Int,              // 0 segmented arc, 1 fine ticks, 2 minimal, 3 analog pointer, 4 bold blocks, 5 glowing ring, 6 wedge bars, 7 numbered scale
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
    private inner class Geo(w: Float, h: Float) {
        val cx = w / 2
        val cy = h / 2
        val r = minOf(w, h) / 2 * 0.92f
        val start = if (mirrored) 75f else 105f
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
class CoverDrawable(private val bmp: android.graphics.Bitmap, private val scrim: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = android.graphics.Rect()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val boundsRatio = b.width().toFloat() / b.height()
        val cropW: Float
        val cropH: Float
        if (boundsRatio > bw / bh) { cropW = bw; cropH = bw / boundsRatio } else { cropH = bh; cropW = bh * boundsRatio }
        val l = ((bw - cropW) / 2f).toInt()
        val t = ((bh - cropH) / 2f).toInt()
        src.set(l, t, l + cropW.toInt(), t + cropH.toInt())
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
    private val modeOrder = intArrayOf(5, 1, 2, 3, 4)      // left to right: Screen first, then Face, Face + Feet, Feet, Feet + Screen
    private val tempBtnInfo = ArrayList<Pair<Boolean, Int>>()
    var floating = false                              // full-screen mode: a rounded panel instead of the whole window
        set(v) { field = v; invalidate() }
    var bgColor = Color.parseColor("#0B0F1A")          // solid, from the dashboard's current background
    var bgTopColor = Color.parseColor("#050A14")       // the top of that gradient
    var onPower: ((Boolean) -> Unit)? = null        // true = turn the climate system on, false = shut it down
    private val powerRect = RectF()
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

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var accent = Color.parseColor("#3B9DFF")                         // the selected skin's accent: lit buttons, fan level, power button
    private val on get() = accent
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
        text(name, cx, cy - dd * 0.20f, (dpD * 0.062f).coerceIn(9f, 13f), Color.argb(190, 255, 255, 255), true)
        fitText(temp(t), cx, cy + dd * 0.06f, (dpD * 0.22f).coerceIn(20f, 44f), if (t == null) off else Color.WHITE, true, dd * 0.50f)
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
    private val paints = listOf(
        "Big Sur Blue" to 0xFF1A2130.toInt(), "Black Pearl" to 0xFF17181D.toInt(), "Blue Planet" to 0xFF2A4163.toInt(), "Great White" to 0xFFE6E8EA.toInt(),
        "Horizon Grey" to 0xFF2F3038.toInt(), "Mariana" to 0xFF1D4F7E.toInt(), "Marine Layer" to 0xFFE0E1DA.toInt(), "Night Drive" to 0xFF1A1A1A.toInt(),
        "Red Planet" to 0xFF7F1510.toInt(), "Sea Grass" to 0xFF222B27.toInt(), "Silver Lining" to 0xFF9DB1C9.toInt(), "Solar Orange" to 0xFFC4502A.toInt(),
        "Stealth Green" to 0xFF161F1B.toInt(), "Sun Soaked" to 0xFFC9B0A3.toInt())
    var paintIdx = 3
        set(v) { field = v.coerceIn(0, 13); invalidate() }
    var onPaint: ((Int) -> Unit)? = null
    private var paintOpen = false
    private val carRect = RectF()
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
        val cL = tempColor(dt); val cR = tempColor(pt)
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
        path.moveTo(52f, 12f); path.lineTo(248f, 12f); path.quadTo(280f, 12f, 280f, 50f); path.lineTo(280f, 422f); path.quadTo(280f, 460f, 248f, 460f)
        path.lineTo(52f, 460f); path.quadTo(20f, 460f, 20f, 422f); path.lineTo(20f, 50f); path.quadTo(20f, 12f, 52f, 12f); path.close()
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, 12f, 0f, 460f, 0xFF14213B.toInt(), 0xFF0B1426.toInt(), Shader.TileMode.CLAMP); canvas.drawPath(path, p); p.shader = null
        strokeP(0xFF1B2540.toInt(), 3f); canvas.drawPath(path, p)
        val wsPath = Path()
        wsPath.moveTo(36f, 63f); wsPath.cubicTo(38f, 42f, 50f, 24f, 84f, 19f); wsPath.quadTo(150f, 11f, 216f, 19f); wsPath.cubicTo(250f, 24f, 262f, 42f, 264f, 63f); wsPath.close()
        fillP(Color.argb(26, 120, 180, 255)); canvas.drawPath(wsPath, p); strokeP(Color.argb(90, 160, 200, 255), 1.2f); canvas.drawPath(wsPath, p)
        path.reset(); path.moveTo(44f, 450f); path.lineTo(256f, 450f); path.lineTo(264f, 430f); path.lineTo(36f, 430f); path.close()
        fillP(Color.argb(20, 120, 180, 255)); canvas.drawPath(path, p); strokeP(Color.argb(76, 160, 200, 255), 1.2f); canvas.drawPath(path, p)
        both(46f, 150f, 208f, 234f, 16f, Color.argb(10, 120, 180, 255), Color.argb(50, 160, 200, 255), 1f)
        both(24f, 80f, 12f, 344f, 5f, 0xFF18264A.toInt(), 0xFF2F4270.toInt(), 1f); both(264f, 80f, 12f, 344f, 5f, 0xFF18264A.toInt(), 0xFF2F4270.toInt(), 1f)
        // dash
        path.reset(); path.moveTo(38f, 64f); path.lineTo(262f, 64f); path.lineTo(262f, 122f); path.quadTo(150f, 136f, 38f, 122f); path.close()
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, 64f, 0f, 134f, 0xFF26375A.toInt(), 0xFF121D35.toInt(), Shader.TileMode.CLAMP); canvas.drawPath(path, p); p.shader = null
        strokeP(0xFF4A6190.toInt(), 1.4f); canvas.drawPath(path, p)
        both(58f, 78f, 56f, 10f, 3f, 0xFF0A1220.toInt(), 0xFF5B7299.toInt(), 1f)
        fillP(0xFF6F86AD.toInt()); for (vx in floatArrayOf(38f, 112f, 164f, 238f)) rr(vx, 72f, 24f, 5f, 2f); rr(137f, 62f, 26f, 5f, 2f)
        strokeP(0xFF8AA0C6.toInt(), 3.4f); tmpR.set(64f, 109f, 108f, 123f); canvas.drawOval(tmpR, p)
        fillP(0xFF3D5280.toInt()); circ(86f, 116f, 5f)
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
        fan(cx - tw0 / 2 - dp(14f), ly, dp(if (small) 7f else 9f), Color.argb(190, 255, 255, 255))
        text(label, cx + dp(8f), ly, if (small) 13f else 15f, Color.WHITE, true)
    }

    private fun pill(right: Float, cy: Float, ph: Float, label: String, value: String, small: Boolean): Float {
        p.typeface = Typeface.DEFAULT; p.textSize = dp(if (small) 11f else 12f); val lw = p.measureText(label)
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = dp(if (small) 13f else 15f); val vw = p.measureText(value)
        val pad = dp(12f); val gp = dp(6f)
        val r = RectF(right - (pad * 2 + lw + gp + vw), cy - ph / 2, right, cy + ph / 2)
        p.style = Paint.Style.FILL; p.color = Color.argb(18, 255, 255, 255); canvas.drawRoundRect(r, ph / 2, ph / 2, p)
        stroke(Color.argb(34, 255, 255, 255), 1f); canvas.drawRoundRect(r, ph / 2, ph / 2, p)
        text(label, r.left + pad, cy, if (small) 11f else 12f, Color.argb(190, 255, 255, 255), false, Paint.Align.LEFT)
        text(value, r.left + pad + lw + gp, cy, if (small) 13f else 15f, Color.WHITE, true, Paint.Align.LEFT)
        return r.left
    }

    override fun onDraw(c: Canvas) {
        canvas = c
        val w = width.toFloat(); val h = height.toFloat()
        val s = snap
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
            powerRect.set(m, topY + (headH - ps) / 2, m + ps, topY + (headH + ps) / 2)
            val isOff = s.climSeen && s.climFan == 0
            val pc = if (isOff) Color.parseColor("#FF5A5A") else on
            p.style = Paint.Style.FILL; p.color = Color.argb(22, Color.red(pc), Color.green(pc), Color.blue(pc))
            c.drawRoundRect(RectF(powerRect.left - dp(3f), powerRect.top - dp(3f), powerRect.right + dp(3f), powerRect.bottom + dp(3f)), ps * 0.34f, ps * 0.34f, p)
            p.color = Color.argb(if (isOff) 70 else 40, Color.red(pc), Color.green(pc), Color.blue(pc))
            c.drawRoundRect(powerRect, ps * 0.30f, ps * 0.30f, p)
            stroke(pc, 1.8f); c.drawRoundRect(powerRect, ps * 0.30f, ps * 0.30f, p)
            powerIcon(powerRect.centerX(), powerRect.centerY() + ps * 0.03f, ps * 0.26f, Color.WHITE)
            text("Climate", powerRect.right + dp(12f), powerRect.centerY(), if (small) 18f else 22f, Color.WHITE, true, Paint.Align.LEFT)
            val ph = dp(if (small) 26f else 32f)
            val l1 = pill(w - m, powerRect.centerY(), ph, "Outside", temp(s.outsideC), small)
            pill(l1 - dp(8f), powerRect.centerY(), ph, "Cabin", temp(s.cabinC), small)
        }

        // ---- the two dials and the cabin between them
        tempBtns.clear(); tempBtnInfo.clear(); modeRects.clear()
        val cabinW = w * 0.30f
        val dialW = (w - cabinW - 3 * m) / 2f
        dial(m, dialW, dialTop, dialH, "DRIVER", s.climDrvT, true)
        dial(w - m - dialW, dialW, dialTop, dialH, "PASSENGER", s.climPassT, false)
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
        tile(1, cols, m, tilesTop, w - 2 * m, tileH, "Heated wheel", s.climWheelOn(), warm, small) { x, yy, r, col -> wheel(x, yy, r, col) }
        tile(2, cols, m, tilesTop, w - 2 * m, tileH, "Defrost", s.climMaxDefrost, warm, small) { x, yy, r, col -> defrost(x, yy, r, col) }
        tile(3, cols, m, tilesTop, w - 2 * m, tileH, "Rear Defrost", s.rearDefrost == true, warm, small) { x, yy, r, col -> rearDefrost(x, yy, r, col) }
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
        closeRect.set(m, h - bp - xs, m + cw, h - bp)
        closeRect2.set(w - m - cw, h - bp - xs, w - m, h - bp)
        for (cr in listOf(closeRect, closeRect2)) {
            p.style = Paint.Style.FILL; p.color = Color.argb(40, 255, 255, 255); c.drawRoundRect(cr, xs / 2, xs / 2, p)
            stroke(Color.argb(100, 255, 255, 255), 1.4f); c.drawRoundRect(cr, xs / 2, xs / 2, p)
            text("Close", cr.centerX(), cr.centerY(), if (small) 13f else 15f, Color.WHITE, true)
        }
        val state = when (s.climState) { 0 -> "starting"; 1 -> "low-voltage"; 2 -> "high-voltage on"; 3 -> "charging"; 4 -> "remote"; 5 -> "HV discharge"; 6 -> "delay"; else -> "--" }
        val advice = when (s.climAdvice) { 1 -> "natural air"; 2 -> "cold air"; 3 -> "hot air"; else -> "" }
        val parts = ArrayList<String>()
        if (s.climSeen) parts.add("A/C unit: $state")
        if (s.climRemote == 1) parts.add("remote A/C running")
        if (advice.isNotEmpty()) parts.add(advice)
        val fy = h - bp - xs / 2
        val footW = closeRect2.left - closeRect.right - dp(16f)
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

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action != android.view.MotionEvent.ACTION_UP) return true
        if (paintOpen) {
            val pi = paintRects.indexOfFirst { it.contains(e.x, e.y) }
            if (pi >= 0) { paintIdx = pi; onPaint?.invoke(pi); paintOpen = false }
            else if (!paintPanel.contains(e.x, e.y)) paintOpen = false
            invalidate(); return true
        }
        val slop = dp(10f)
        if (e.y > closeRect.top - slop && (e.x < closeRect.right + slop || e.x > closeRect2.left - slop)) { onClose?.invoke(); return true }
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
        if (!fanRect.isEmpty() && fanRect.contains(e.x, e.y)) { showNotice("Please use the button bar below the screen to adjust fan speed"); return true }
        if (!rearRect.isEmpty() && rearRect.contains(e.x, e.y)) { showNotice("Please use the button bar below the screen to adjust Rear Defrost"); return true }
        if (!carRect.isEmpty() && carRect.contains(e.x, e.y)) { paintOpen = true; invalidate(); return true }
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

    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val comp = ComponentName(ctx, MainActivity::class.java)
    private var ctl: MediaController? = null
    private var started = false
    private var grantTried = false
    private var gaveUp = false

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { readMeta(metadata); push() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { playing = state?.state == PlaybackState.STATE_PLAYING; push() }
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
            log("media: following the active media session")
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
        val sig = list.joinToString(" | ") { c -> "${c.packageName} state=${c.playbackState?.state} title=${c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}" }
        if (sig != lastSig) { lastSig = sig; log("media sessions: " + sig.ifEmpty { "none" }) }
        var c: MediaController? = null
        var best = Int.MIN_VALUE
        for (x in list) { val sc = score(x); if (sc > best) { best = sc; c = x } }
        if (c?.sessionToken != ctl?.sessionToken) {
            try { ctl?.unregisterCallback(cb) } catch (e: Exception) {}
            ctl = c
            c?.registerCallback(cb, ui)
            if (c != null) log("media: showing ${c.packageName}")
        }
        hasSession = c != null
        playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        readMeta(c?.metadata)
        push()
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
    var rhd = false                                   // right-hand drive: controls on the right
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

    fun update(t: String?, a: String?, b: Bitmap?, pl: Boolean, has: Boolean) {
        title = t; artist = a; art = b; playing = pl; hasSession = has; invalidate()
    }

    private fun fitText(s: String, x: Float, y: Float, sizeDp: Float, color: Int, bold: Boolean, maxW: Float) {
        p.style = Paint.Style.FILL; p.color = color; p.textAlign = Paint.Align.LEFT
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.textSize = dp(sizeDp)
        var out = s
        if (p.measureText(out) > maxW) {
            while (out.length > 1 && p.measureText("$out\u2026") > maxW) out = out.dropLast(1)
            out = "$out\u2026"
        }
        canvas0.drawText(out, x, y + p.textSize * 0.35f, p)
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
            p.color = Color.argb(150, 0, 0, 0); c.drawRect(card, p)
        } else {
            p.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#26334D"), Color.parseColor("#111827"), Shader.TileMode.CLAMP)
            c.drawRect(card, p); p.shader = null
        }
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f); p.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), rad, rad, p)

        // controls on the driver's side: previous, play / pause, next, three identical round glass buttons
        val small = w < dp(380f)                       // a narrow card: the text on top, the three buttons in a row underneath
        val gap = dp(if (small) 10f else 16f)
        val bsz = if (small) minOf(h * 0.40f, (w - dp(28f) - 2 * gap) / 3) else h * 0.58f
        val cy = if (small) h - dp(8f) - bsz / 2 else h / 2
        val total = bsz * 3 + gap * 2
        val left = if (small) (w - total) / 2 else if (rhd) w - dp(20f) - total else dp(20f)
        rPrev.set(left, cy - bsz / 2, left + bsz, cy + bsz / 2)
        rPlay.set(rPrev.right + gap, cy - bsz / 2, rPrev.right + gap + bsz, cy + bsz / 2)
        rNext.set(rPlay.right + gap, cy - bsz / 2, rPlay.right + gap + bsz, cy + bsz / 2)
        glass(c, rPrev); glass(c, rPlay); glass(c, rNext)
        icon(c, rPrev, 0); icon(c, rPlay, if (playing) 3 else 2); icon(c, rNext, 1)

        // title and artist on the other side
        val tx = if (small) dp(14f) else if (rhd) dp(20f) else rNext.right + dp(24f)
        val maxW = if (small) w - dp(28f) else if (rhd) rPrev.left - tx - dp(24f) else w - tx - dp(20f)
        val y1 = if (small) h * 0.17f else h * 0.38f; val y2 = if (small) h * 0.35f else h * 0.64f
        val s1 = if (small) 15f else 20f; val s2 = if (small) 12f else 14f
        if (hasSession && !title.isNullOrEmpty()) {
            fitText(title!!, tx, y1, s1, Color.WHITE, true, maxW)
            if (!artist.isNullOrEmpty()) fitText(artist!!, tx, y2, s2, Color.argb(210, 255, 255, 255), false, maxW)
        } else {
            fitText("Nothing playing", tx, y1, if (small) 14f else 18f, Color.argb(220, 255, 255, 255), true, maxW)
            fitText(if (small) "Bluetooth music shows here" else "Music from the phone over Bluetooth shows here", tx, y2, 12f, Color.argb(150, 255, 255, 255), false, maxW)
        }
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
            when { hit(rPlay) -> onToggle?.invoke(); hit(rPrev) -> onPrev?.invoke(); hit(rNext) -> onNext?.invoke(); else -> onTap?.invoke() }
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
