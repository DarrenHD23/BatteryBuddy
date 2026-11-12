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
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.util.TypedValue
import android.graphics.RectF
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
        title = "Fisker Battery Buddy"
        useFahrenheit = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("fahrenheit", false)
        buildUi()
        val saved = getSharedPreferences("prefs", MODE_PRIVATE).getString("source", null)
        applySource(when (saved) { "pulse" -> true; "dongle" -> false; else -> PulseCan.available() })
        if (getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("dashDefault", false)) showDashboard(true)
    }

    // ------------------------------------------------------------------ UI
    private var themeIndex = 0
    private lateinit var rootScroll: ScrollView
    private lateinit var advancedBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(alpha: Int, radiusDp: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(alpha, 255, 255, 255)); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun LinearLayout.add(v: View, topDp: Int = 8) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(topDp) })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(40)
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
        root.addView(adv)

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
            text = "Show Dashboard"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { showDashboard(!dashShown) }
        }
        exitBtn = Button(this).apply {
            text = "Exit App"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
            background = GradientDrawable().apply { setColor(Color.argb(150, 20, 20, 40)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { pulse?.stop(); finishAndRemoveTask() }
        }
        val container = FrameLayout(this)
        container.addView(rootScroll, FrameLayout.LayoutParams(-1, -1))
        container.addView(dashRoot, FrameLayout.LayoutParams(-1, -1))
        container.addView(dashBtn, FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), 0, 0, dp(16)) })
        container.addView(exitBtn, FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })
        setContentView(container)
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
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Settings").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { showSettings(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        // --- Temperature unit (first, so it is always visible)
        box.add(TextView(this).apply { text = "Temperature unit"; textSize = 14f; setTextColor(Color.DKGRAY) }, 0)
        val rc = RadioButton(this).apply { text = "Celsius (\u00B0C)"; id = View.generateViewId(); isChecked = !useFahrenheit }
        val rf = RadioButton(this).apply { text = "Fahrenheit (\u00B0F)"; id = View.generateViewId(); isChecked = useFahrenheit }
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(rc); addView(rf) }
        rg.setOnCheckedChangeListener { _, checkedId -> setFahrenheit(checkedId == rf.id) }
        box.add(rg, 6)

        box.add(TextView(this).apply { text = "Dashboard theme"; textSize = 14f; setTextColor(Color.DKGRAY) }, 20)
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
        box.add(TextView(this).apply { text = "Speed unit"; textSize = 14f; setTextColor(Color.DKGRAY) }, 20)
        val kmh = RadioButton(this).apply { text = "km/h"; id = View.generateViewId(); isChecked = !speedMph() }
        val mph = RadioButton(this).apply { text = "mph"; id = View.generateViewId(); isChecked = speedMph() }
        val spg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(kmh); addView(mph) }
        spg.setOnCheckedChangeListener { _, checkedId -> prefs().edit().putBoolean("mph", checkedId == mph.id).apply() }
        box.add(spg, 6)
        box.add(TextView(this).apply { text = "Dashboard"; textSize = 14f; setTextColor(Color.DKGRAY) }, 20)
        box.add(CheckBox(this).apply {
            text = "Show the dashboard when the app starts"; isChecked = prefs().getBoolean("dashDefault", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("dashDefault", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Flip power direction (if drive shows as negative)"; isChecked = prefs().getBoolean("flipPower", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("flipPower", on).apply() }
        }, 0)

        box.add(TextView(this).apply { text = "Data source"; textSize = 14f; setTextColor(Color.DKGRAY) }, 20)
        val sd = RadioButton(this).apply { text = "vLinker dongle (Bluetooth)"; id = View.generateViewId(); isChecked = !useCan }
        val sp = RadioButton(this).apply { text = "Pulse CAN bus (run this app on the Pulse)"; id = View.generateViewId(); isChecked = useCan }
        val sg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(sd); addView(sp) }
        sg.setOnCheckedChangeListener { _, checkedId -> applySource(checkedId == sp.id) }
        box.add(sg, 6)

        // --- Background colour
        box.add(TextView(this).apply { text = "Background colour"; textSize = 14f; setTextColor(Color.DKGRAY) }, 20)
        lateinit var dialog: AlertDialog
        THEMES.forEachIndexed { i, t ->
            val b = Button(this).apply {
                text = t.name + if (i == themeIndex) "  \u2713" else ""
                isAllCaps = false; setTextColor(Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(t.top, t.bottom))
                    .apply { cornerRadius = dp(12).toFloat() }
                setOnClickListener { applyTheme(i); dialog.dismiss() }
            }
            box.add(b, 10)
        }
        box.add(TextView(this).apply {
            text = "This project uses software from Haibin Wen and SUNNYPILOT LLC and is licensed under a custom license requiring permission for use."
            textSize = 11f; setTextColor(Color.GRAY)
        }, 24)
        // ScrollView so nothing is cut off on small screens
        val scroller = ScrollView(this).apply { addView(box) }
        dialog = AlertDialog.Builder(this).setTitle("Settings").setView(scroller)
            .setNegativeButton("Close", null).create()
        dialog.show()
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
        val live = System.currentTimeMillis() - p.lastChangeAt < 3000
        canDot.background = dotDrawable(live)
        canInfoView.text = if (!live) "No changing frames from the car yet (car asleep, or the reader isn't running)"
            else if (s.packVolts == null) "Car awake. Voltage and current not received (the Pulse's CAN filters may need root)"
            else "Car awake: frames are updating"
        if (s.avgTempC != lastTempC) { lastTempC = s.avgTempC; showTemp(s.avgTempC, useFahrenheit) }
        val demand = when (s.thermalDemand) { 0 -> "starting up"; 1 -> "heating"; 2 -> "cooling"; 3 -> "cycling"; else -> "--" }
        fun tc(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 1)
        resultView.textSize = 16f
        resultView.text = listOf(
            "Cell temp, coolest / hottest: ${tc(s.cellMinC)} / ${tc(s.cellMaxC)}",
            "State of charge: ${s.socUsable?.let { "$it %" } ?: "--"}",
            "Pack voltage: ${s.packVolts?.let { String.format("%.1f V", it) } ?: "--"}",
            "Pack current: ${s.packAmps?.let { String.format("%.1f A", it) } ?: "--"}",
            "Thermal management: $demand",
            heatLine(s),
        ).joinToString("\n")
    }

    private fun heatLine(s: CanSnapshot): String {
        val comp = s.compWatts
        val pump = when {
            comp == null -> "heat pump: not received (CAN filter)"
            s.compState == 2 -> "heat pump: FAULT"
            comp > 20 -> String.format("heat pump ON %.2f kW", comp / 1000)
            else -> "heat pump off"
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
    private lateinit var dashSpeedGauge: ArcGaugeView
    private lateinit var dashPowerGauge: ArcGaugeView
    private lateinit var dashLight: TrafficLightView
    private lateinit var dashLightDist: TextView
    private var lightColor = 0
    private var lightSeenAt = 0L
    private var lightShown = false
    private lateinit var exitBtn: Button
    private var dashDay = false
    private var dayPending = false
    private var dayPendingSince = 0L
    private lateinit var dashInfo: TextView
    private lateinit var dashHeading: TextView
    private lateinit var dashMode: TextView
    private lateinit var dashBrake: TextView
    private var dashShown = false

    private fun prefs() = getSharedPreferences("prefs", MODE_PRIVATE)
    private fun speedMph() = prefs().getBoolean("mph", false)

    private fun buildDashboard(): FrameLayout {
        val f = FrameLayout(this)
        f.visibility = View.GONE
        f.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#04070D"), Color.parseColor("#0B1626")))
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
        dashPowerGauge = ArcGaugeView(this, false, 0.2f) { t -> grad(t) }
            .apply { topLabel = "CLIMATE"; title = "POWER"; unit = "kW"; bottomLabel = "CHARGE" }
        dashSpeedGauge = ArcGaugeView(this, true, 0f) { t -> grad(t) }
            .apply { topLabel = "BATTERY TEMP"; title = "SPEED"; unit = "km/h"; bottomLabel = "BATTERY" }
        val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        dashLight = TrafficLightView(this).apply { alpha = 0f }
        dashLightDist = TextView(this).apply {
            textSize = 15f; setTextColor(Color.argb(200, 255, 255, 255)); typeface = light; gravity = Gravity.CENTER; alpha = 0f
        }
        dashInfo = TextView(this).apply {
            textSize = 15f; setTextColor(Color.argb(200, 120, 190, 255)); typeface = light; gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setOnLongClickListener { pulse?.resetDrive(); Toast.makeText(context, "Regen counter reset", Toast.LENGTH_SHORT).show(); true }
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
            textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.INVISIBLE; text = "EARTH"
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD_ITALIC); letterSpacing = 0.12f
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(dashLight, LinearLayout.LayoutParams(dp(46), dp(100)).apply { topMargin = dp(6) })
            addView(dashLightDist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(dashBrake, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            addView(dashHeading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(dashInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dashPowerGauge, LinearLayout.LayoutParams(0, -1, 1.15f))
            addView(mid, LinearLayout.LayoutParams(0, -1, 0.7f))
            addView(dashSpeedGauge, LinearLayout.LayoutParams(0, -1, 1.15f))
        }
        f.addView(row, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(8), dp(8), dp(64)) })
        f.addView(dashMode, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
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
        }
    }

    private fun applyDashTheme(day: Boolean) {
        dashRoot.background = if (day)
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E6ECF4")))
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#04070D"), Color.parseColor("#0B1626")))
        dashSpeedGauge.day = day; dashPowerGauge.day = day
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        dashHeading.setTextColor(ink)
        dashLightDist.setTextColor(if (day) Color.argb(200, 20, 32, 46) else Color.argb(200, 255, 255, 255))
        dashInfo.setTextColor(if (day) Color.parseColor("#2A6FB0") else Color.argb(200, 120, 190, 255))
        dashSpeedGauge.refresh(); dashPowerGauge.refresh()
    }

    private fun showDashboard(on: Boolean) {
        dashShown = on
        ui.removeCallbacks(dashTick)
        dashRoot.visibility = if (on) View.VISIBLE else View.GONE
        rootScroll.visibility = if (on) View.GONE else View.VISIBLE
        dashBtn.text = if (on) "Hide Dashboard" else "Show Dashboard"
        if (on) {
            actionBar?.hide()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            updateDashTheme(true)
            ui.post(dashTick)
        } else {
            actionBar?.show()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun renderDash() {
        val s = canSnap
        val mph = speedMph()
        val spd = s.speedKmh?.let { if (mph) it * 0.621371 else it }
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
        dashSpeedGauge.refresh()

        val v = s.packVolts
        val a = s.packAmps
        val kw = if (v != null && a != null) v * a / 1000.0 * (if (prefs().getBoolean("flipPower", false)) -1 else 1) else null
        dashPowerGauge.valueText = kw?.let { String.format("%.0f", it) } ?: "--"
        dashPowerGauge.setFraction(kw?.let {
            // square-root scale so everyday power fills a good part of the arc; zero sits at 20%
            if (it >= 0) (0.2 + 0.8 * Math.sqrt((it / 250.0).coerceIn(0.0, 1.0))).toFloat()
            else (0.2 - 0.2 * Math.sqrt((-it / 80.0).coerceIn(0.0, 1.0))).toFloat()
        })
        dashPowerGauge.topValue = s.climateKw?.let { String.format("%.1f kW", it) } ?: "--"
        dashPowerGauge.bottomValue = s.socUsable?.let { "$it %" } ?: "--"
        dashPowerGauge.barFrac = s.socUsable?.let { it / 100f }
        dashPowerGauge.refresh()

        dashBrake.visibility = if (s.brakeBcm ?: s.brakeVcu ?: false) View.VISIBLE else View.INVISIBLE
        updateDashTheme()
        val nowMs = System.currentTimeMillis()
        val lc = s.lightColor
        if (lc != null) { lightColor = lc; lightSeenAt = nowMs; dashLight.setColor(lc) }
        val seen = lc != null || nowMs - lightSeenAt < 1500   // brief hold so one missed frame does not flicker
        if (seen != lightShown) {
            lightShown = seen
            val to = if (seen) 1f else 0f
            dashLight.animate().alpha(to).setDuration(if (seen) 200L else 700L).start()
            dashLightDist.animate().alpha(to).setDuration(if (seen) 200L else 700L).start()
        }
        dashLightDist.text = s.lightDistM?.let { "$it m" } ?: ""
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
        dashInfo.text = "Regen ${String.format("%.2f", s.regenKwh)} kWh\nCells ${tc(s.cellMinC)} \u2013 ${tc(s.cellMaxC)}\nThermal: $demand"
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
        showTemp(null, useFahrenheit)
        resultView.text = ""
        ui.removeCallbacks(canTick)
        if (pulseMode) {
            close()                                   // let go of the dongle
            val p = pulse ?: PulseCan(this, { m -> log(m) }, { snap -> canSnap = snap }).also { pulse = it }
            p.start()
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

    private fun log(s: String) = runOnUiThread {
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

    override fun onDestroy() { pulse?.stop(); ui.removeCallbacksAndMessages(null); close(); super.onDestroy() }

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
        var tempC: Double? = null
        for (p in PRESETS) {
            val v = readValue(header, filter, p.request, p.byteIndex, p.byteLen, p.scale, p.offset, p.signed)
            if (p.name == "Battery temp") { tempC = v; continue }     // shown in the gauge
            if (v != null) rows += { p.name + ": " + fmt(v, p.unit, 2) }
            else if (p.name.startsWith("12V")) {
                // Fallback: adapter's own supply voltage on OBD pin 16 (~\u00B10.2 V)
                try { val t = send("ATRV"); rows += { "12V (ATRV fallback): $t" } } catch (e: Exception) { log("ATRV failed") }
            } else rows += { "${p.name}: no data" }
        }
        renderLast = { resultView.text = rows.joinToString("\n") { it() }; resultView.textSize = 16f }
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

/** Segmented arc gauge in the style of a modern instrument cluster: a "(" arc (or ")" when mirrored) with the
 *  readout in the middle. Fills from the zero position, so a regen value fills the other way. */
class ArcGaugeView(ctx: Context, private val mirrored: Boolean, private val zeroFrac: Float, private val colorAt: (Float) -> Int) : View(ctx) {
    var topLabel = ""; var topValue = ""
    var title = ""; var valueText = "--"; var unit = ""
    var bottomLabel = ""; var bottomValue = ""; var bottomColor: Int? = null
    var day = false
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

    fun setFraction(f: Float?) {
        has = f != null
        val t = (f ?: zeroFrac).coerceIn(0f, 1f)
        if (t == target) return
        target = t
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, t).apply {
            duration = 250
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun text(s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface) {
        txt.textSize = size; txt.color = color; txt.typeface = tf
        // keep long text inside the arc
        val maxW = width * 0.62f
        val w = txt.measureText(s)
        if (w > maxW) txt.textSize = size * maxW / w
        arcCanvas?.drawText(s, x, y, txt)
    }

    private var arcCanvas: Canvas? = null

    override fun onDraw(c: Canvas) {
        arcCanvas = c
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
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
        val lo = if (has) minOf(zeroFrac, shown) else 0f
        val hi = if (has) maxOf(zeroFrac, shown) else 0f
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
                val col = if (shown < zeroFrac) {
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
        text(valueText, cx, cy + r * 0.13f, r * 0.40f, ink, lightItalic)
        text(unit, cx, cy + r * 0.34f, r * 0.14f, Color.parseColor("#2AA8FF"), medium)
        text(bottomLabel, cx, cy + r * 0.50f, r * 0.085f, grey, light)
        text(bottomValue, cx, cy + r * 0.62f, r * 0.12f, bottomColor ?: ink, lightItalic)
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
                c.drawCircle(w / 2, cy, r * 1.45f, p)
            }
            p.color = if (on) cols[i] else Color.argb(60, Color.red(cols[i]), Color.green(cols[i]), Color.blue(cols[i]))
            c.drawCircle(w / 2, cy, r, p)
        }
    }
}
