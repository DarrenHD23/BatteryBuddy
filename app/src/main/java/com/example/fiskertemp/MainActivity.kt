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
        EventLog.init(this)
        EventLog.add("app start")
        ui.postDelayed(heartbeat, 30_000)
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
        val btnBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dashBtn, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })
            addView(exitBtn, LinearLayout.LayoutParams(-2, dp(36)))
        }
        edgeBox.addView(btnBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(16)) })
        setContentView(edgeBox)
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
        spg.setOnCheckedChangeListener { _, checkedId -> prefs().edit().putBoolean("mph", checkedId == mph.id).apply() }
        box.add(spg, 6)
        box.add(TextView(this).apply { text = "Dashboard"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        box.add(CheckBox(this).apply {
            text = "Show the dashboard when the app starts"; isChecked = prefs().getBoolean("dashDefault", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("dashDefault", on).apply() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Speed gauge on the left (power on the right)"; isChecked = prefs().getBoolean("speedLeft", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("speedLeft", on).apply(); applyGaugeSides() }
        }, 4)
        box.add(CheckBox(this).apply {
            text = "Safe mode: do not change the CAN chip filters (power and climate may show --)"; isChecked = prefs().getBoolean("canSafe", false)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("canSafe", on).apply(); pulse?.safeMode = on; toast("Takes effect the next time the app starts") }
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

        // --- Background colour
        box.add(TextView(this).apply { text = "Background colour"; textSize = 14f; setTextColor(Color.parseColor("#B8C2D0")) }, 20)
        lateinit var dialog: AlertDialog
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
            if (v is Button && v.background !is GradientDrawable) {
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
        resultView.text = heatLine(s)
        renderStats(canStats(s).toMap())
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
    private lateinit var dashStrip: StatusStripView
    private lateinit var dashSign: SpeedSignView
    private var signShown = false
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
    private val lowerNames = listOf("Maps", "Spotify", "Pulse", "Oceanscape")
    private val lowerPkgs = listOf("com.google.android.apps.maps", "com.spotify.music", "", "")
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
                    openLower((lowerIdx + (if (dx < 0) 1 else lowerNames.size - 1)) % lowerNames.size)
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
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(dashReady, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(dashLight, LinearLayout.LayoutParams(dp(42), dp(88)).apply { topMargin = dp(4) })
            addView(dashLightDist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(dashBrake, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            addView(dashHeading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(dashSign, LinearLayout.LayoutParams(dp(64), dp(64)).apply { topMargin = dp(8) })
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
            lowerNames.forEachIndexed { i, name ->
                addView(TextView(context).apply {
                    text = name; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    minHeight = dp(42); setPadding(dp(6), 0, dp(6), 0)
                    setOnClickListener { openLower(i) }
                })
            }
        }
        lowerIdx = prefs().getInt("lowerApp", 0).coerceIn(0, lowerNames.size - 1)
        paintLowerTabs()
        lowerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(resBtn, LinearLayout.LayoutParams(-2, dp(42)))
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
        f.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ -> adaptDashLayout((v.width / resources.displayMetrics.density).toInt(), ((bottom - top) / resources.displayMetrics.density).toInt()) }
        f.addView(dashMode, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(8), 0) })
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
        paintEdges()
        dashRoot.background = if (day)
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E6ECF4")))
        else bgDrawable(Color.argb(120, 0, 0, 0)) ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#04070D"), Color.parseColor("#0B1626")))
        dashSpeedGauge.day = day; dashPowerGauge.day = day; dashStrip.day = day; dashStrip.invalidate()
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        dashHeading.setTextColor(ink)
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
        val estCap = s.packCapKwh?.takeIf { it > 10.0 }
            ?: sohOk?.let { prefs().getFloat("packKwh", 106.5f) * it / 100.0 }
        return listOf(
            "HV Cap" to (s.capKwh?.let { String.format("%.1f kWh", it) } ?: "--"),
            "Est. Capacity" to (estCap?.let { String.format("%.1f kWh", it) } ?: "--"),
            "Battery Health" to (s.sohPct?.takeIf { it in 1..100 }?.let { "$it %" } ?: "--"),
            "Charge" to (s.socUsable?.let { "$it %" } ?: "--"),
            "Pack Volts" to (s.packVolts?.let { String.format("%.0f V", it) } ?: "--"),
            "Pack Amps" to (s.packAmps?.let { String.format("%.0f A", it) } ?: "--"),
            "Battery Temp" to tc(s.avgTempC),
            "Climate" to (s.climateKw?.let { String.format("%.1f kW", it) } ?: "--"),
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
        dashSpeedGauge.mirrored = !speedLeft
        dashPowerGauge.mirrored = speedLeft
        dashGaugeRow.removeAllViews()
        val left = if (speedLeft) dashSpeedGauge else dashPowerGauge
        val right = if (speedLeft) dashPowerGauge else dashSpeedGauge
        dashGaugeRow.addView(left, LinearLayout.LayoutParams(0, -1, 1.15f))
        dashGaugeRow.addView(dashMid, LinearLayout.LayoutParams(0, -1, 0.7f))
        dashGaugeRow.addView(right, LinearLayout.LayoutParams(0, -1, 1.15f))
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
    private fun adaptDashLayout(wDp: Int, hDp: Int) {
        if (wDp <= 0 || hDp <= 0) return
        val narrow = wDp < 800
        val tiers = listOf(
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
        val gaugeH = hDp - stack(t)
        val midLevel = if (gaugeH >= 300) 0 else if (gaugeH >= 235) 1 else 2
        // a third-height split window (not half or full) keeps the bottom buttons at the left edge
        val third = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.height() < windowManager.maximumWindowMetrics.bounds.height() * 0.42 else false
        } catch (e: Exception) { false }
        val key = "$narrow/$ti/$midLevel/$third"
        if (key == layoutKey) return
        layoutKey = key
        compact = ti > 0
        val short = midLevel > 0
        val stripBottom = base
        val rowBottom = if (t.showStrip) stripBottom + t.strip + 4 else base
        val lowerBottom = rowBottom + t.row + 4          // top of the info row: the stat editor sits here
        val gaugeBottom = lowerBottom + 4
        val sideL = 8
        val sideR = 8
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
        val editBottom = if (t.showStrip) stripBottom else rowBottom
        val poolTop = lowerBottom + 44
        (editBackdrop.layoutParams as FrameLayout.LayoutParams).apply { height = dp(poolTop - editBottom + 8); setMargins(dp(4), 0, dp(4), dp(maxOf(editBottom - 4, 0))) }
        editBackdrop.requestLayout()
        (dashStrip.layoutParams as FrameLayout.LayoutParams).apply { height = dp(maxOf(t.strip, 1)); setMargins(dp(sideL), 0, dp(sideR), dp(stripBottom)) }
        (dashRow.layoutParams as FrameLayout.LayoutParams).apply { height = dp(t.row); setMargins(dp(sideL), 0, dp(sideR), dp(rowBottom)) }
        (lowerBox.layoutParams as FrameLayout.LayoutParams).apply { setMargins(dp(8) + (if (third) 0 else 10), 0, 0, dp(10)) }
        (poolBar.layoutParams as FrameLayout.LayoutParams).apply { setMargins(dp(8), 0, dp(8), dp(lowerBottom)) }
        (dashGaugeRow.layoutParams as FrameLayout.LayoutParams).apply { setMargins(dp(8), dp(if (short) 20 else 8), dp(8), dp(gaugeBottom)) }
        (dashLight.layoutParams as LinearLayout.LayoutParams).apply { width = dp(if (midLevel == 2) 22 else if (short) 26 else 42); height = dp(if (midLevel == 2) 46 else if (short) 56 else 88); topMargin = dp(if (short) 6 else 4) }
        dashReady.textSize = if (short) 11f else 13f
        // in a short window the gauges reach the top corners, so the mode badge gets its own strip above them
        dashMode.textSize = if (short) 9f else 11f
        dashMode.minWidth = dp(if (short) 60 else 84)
        dashMode.setPadding(dp(if (short) 6 else 10), dp(if (short) 1 else 3), dp(if (short) 6 else 10), dp(if (short) 1 else 3))
        (dashMode.layoutParams as FrameLayout.LayoutParams).apply { setMargins(0, dp(if (short) 2 else 6), dp(8), 0) }
        dashMode.requestLayout()
        dashLightDist.textSize = if (short) 12f else 15f
        dashLightDist.visibility = if (midLevel == 2) View.GONE else View.VISIBLE
        dashHeading.visibility = View.VISIBLE
        dashHeading.textSize = if (midLevel == 2) 12f else if (short) 15f else 20f
        (dashHeading.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(if (midLevel == 2) 2 else if (short) 6 else 10) }
        dashBrake.textSize = if (midLevel == 2) 11f else if (short) 13f else 16f
        (dashBrake.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(if (midLevel == 2) 3 else 8) }
        dashBrake.setPadding(dp(if (midLevel == 2) 10 else 18), dp(if (midLevel == 2) 1 else 4), dp(if (midLevel == 2) 10 else 18), dp(if (midLevel == 2) 1 else 4))
        (dashSign.layoutParams as LinearLayout.LayoutParams).apply { width = dp(if (midLevel == 2) 38 else if (short) 48 else 64); height = dp(if (midLevel == 2) 38 else if (short) 48 else 64); topMargin = dp(if (midLevel == 2) 3 else 8) }
        poolBar.requestLayout(); lowerBox.requestLayout()
        dashStrip.requestLayout(); dashRow.requestLayout(); dashGaugeRow.requestLayout(); dashLight.requestLayout(); dashSign.requestLayout()
    }

    private fun paintLowerTabs() {
        for (i in 0 until lowerTabs.childCount) {
            lowerTabs.getChildAt(i).background = if (i == lowerIdx)
                GradientDrawable().apply { setColor(Color.parseColor("#2AA8FF")); cornerRadius = dp(15).toFloat() } else null
        }
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
    private fun openLower(i: Int) {
        if (isInMultiWindowMode) { launchLower(i); return }
        EventLog.add("starting split screen for ${lowerNames[i]}")
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
        val pkg = when (i) { 2 -> findPulse(); 3 -> findOceanscape(); else -> lowerPkgs[i] }
        val words = if (i == 2) listOf("pulse", "oceanlink") else listOf("oceanscape")
        if (pkg == null) { toast("The ${lowerNames[i]} app was not found on this device. Similar installed apps: " + (if (i >= 2) candidates(words + listOf("ocean", "fisker")) else "none")); return }
        EventLog.add("launching ${lowerNames[i]} ($pkg)")
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        val tries = mutableListOf<Intent>()
        launchIntentFor(pkg)?.let { tries.add(it) }
        tries.add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg))
        if (i == 0) tries.add(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
        for (t in tries) {
            try {
                t.addFlags(flags); startActivity(t)
                lowerIdx = i
                prefs().edit().putInt("lowerApp", i).apply()
                paintLowerTabs()
                return
            } catch (e: Exception) { /* try the next one */ }
        }
        // last resort: start it through root (it comes up as a normal window)
        if (i >= 2) {
            Thread { rootRun("monkey -p $pkg -c android.intent.category.LAUNCHER 1") }.start()
            lowerIdx = i; prefs().edit().putInt("lowerApp", i).apply(); paintLowerTabs()
            toast("Starting ${lowerNames[i]} ($pkg) through root")
        } else toast("${lowerNames[i]} could not be started")
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
        if ((canSnap.speedKmh ?: 0.0) > 5.0) { toast("The screen size can only be changed when the car is stopped"); return }
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
        if (on) {
            actionBar?.hide()
            @Suppress("DEPRECATION")
            // the Pulse keeps its own status bar showing whatever an app asks, so the app must not extend under it
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            updateDashTheme(true)
            refreshScreenSize()
            offerClosePulse()
            if (!introDone) {
                introDone = true
                ui.postDelayed({ dashPowerGauge.intro(); dashSpeedGauge.intro() }, 400)
            }
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
        dashSpeedGauge.hold = s.hold == true || s.holdEsp == true
        if (s.ready != true) drivenSinceReady = false
        else if ((s.speedKmh ?: 0.0) > 2.0) drivenSinceReady = true
        dashReady.visibility = if (s.ready == true && !drivenSinceReady) View.VISIBLE else View.INVISIBLE
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
        fun t(c: Double?) = if (c == null) "--" else fmt(c, "\u00B0C", 0)
        val nowMs = System.currentTimeMillis()
        // speed limit sign: shown in the dashboard's own speed unit; fades in and out with the car's knowledge of the limit
        val lim = s.limit
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
        }
        val signSeen = lim != null || nowMs - signSeenAt < 1500
        if (signSeen != signShown) {
            signShown = signSeen
            dashSign.animate().alpha(if (signSeen) 1f else 0f).setDuration(if (signSeen) 250L else 700L).start()
        }
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
        val rangeText = s.rangeKm?.let { if (speedMph()) String.format("%.0f mi", it * 0.621371) else String.format("%.0f km", it) } ?: "--"
        val everything = canStats(s)
        dashStrip.setItems(everything)
        dashRow.setItems(everything)
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
            val p = pulse ?: PulseCan(this, { m -> log(m) }, { snap -> canSnap = snap }).also { pulse = it }
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

    private val heartbeat = object : Runnable {
        override fun run() {
            val n = pulse?.frames ?: 0L
            Thread { EventLog.add("alive " + EventLog.system() + " frames=$n " + CanDiag.quick()) }.start()
            ui.postDelayed(this, 30_000)
        }
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

    override fun onDestroy() { EventLog.add("app stopped"); pulse?.stop(); ui.removeCallbacksAndMessages(null); close(); super.onDestroy() }

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
        "CLIMATE" to listOf("Climate", "Thermal", "Cabin", "Outside", "Solar now"),
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

    private fun text(s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface) {
        txt.textSize = size; txt.color = color; txt.typeface = tf
        // keep long text inside the arc
        val maxW = width * 0.62f
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
        if (!hold) text(valueText, cx, cy + r * 0.13f, r * 0.40f, ink, lightItalic)
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

    private fun drawItem(c: Canvas, p: Pair<String, String>, cx: Float, colW: Float, h: Float, ink: Int, grey: Int) {
        fit(p.first.uppercase(), h * 0.19f, colW * 0.92f, light, grey, cx, h * 0.40f, c)
        fit(p.second, h * 0.36f, colW * 0.92f, medium, ink, cx, h * 0.82f, c)
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
        val accent = Color.parseColor("#2AA8FF")
        // outline: down the left side, across the top, down the right side; fading towards the ends
        path.reset()
        path.moveTo(0f, h - 2f)
        path.lineTo(taper, 3f)
        path.lineTo(w - taper, 3f)
        path.lineTo(w, h - 2f)
        line.strokeWidth = 3f
        line.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Color.argb(0, 42, 168, 255), accent, Color.parseColor("#8E5CFF"), accent, Color.argb(0, 42, 168, 255)),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
        val panel = android.graphics.Path(path).apply { lineTo(0f, h - 2f); close() }
        fill.shader = LinearGradient(0f, 0f, 0f, h,
            if (day) intArrayOf(Color.argb(28, 42, 120, 200), Color.argb(0, 42, 120, 200)) else intArrayOf(Color.argb(40, 42, 168, 255), Color.argb(0, 42, 168, 255)),
            null, android.graphics.Shader.TileMode.CLAMP)
        if (!plain) { c.drawPath(panel, fill); c.drawPath(path, line) }
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
                fitText(c, "MAXIMUM", cx, h * 0.27f, h * 0.15f, sw * 0.78f, bold)
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
