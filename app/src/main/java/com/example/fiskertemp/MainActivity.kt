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
import android.graphics.Color
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
    private lateinit var protocolField: EditText
    private val devices = mutableListOf<BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useFahrenheit = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("fahrenheit", false)
        buildUi()
        ensurePermissions()
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
        resultView = TextView(this).apply { textSize = 28f; text = "--"; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        logView = TextView(this).apply {
            textSize = 12f; setTextIsSelectable(true); text = "Log:\n"; setTextColor(Color.parseColor("#E6FFFFFF"))
        }
        statusView = TextView(this).apply {
            textSize = 14f; text = "Status: not connected"; setTextColor(Color.WHITE)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        val conn = card()
        conn.add(label("Connection", 18f, true), 0)
        conn.add(deviceSpinner, 10)
        conn.add(button("Scan BLE for vLinker") { scanBle() })
        conn.add(button("Connect") { connect() })
        conn.add(statusView, 10)
        root.addView(conn)

        val reads = card()
        reads.add(label("Battery readings", 18f, true), 0)
        reads.add(resultView, 14)
        reads.add(button("Read all values") { Thread { readAll() }.start() }, 14)
        reads.add(button("Start / stop polling (1 s)") { togglePolling() })
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
        adv.add(button("Advanced settings") {
            advancedBox.visibility = if (advancedBox.visibility == View.GONE) View.VISIBLE else View.GONE
        }, 0)
        adv.add(advancedBox, 0)
        root.addView(adv)

        val logCard = card()
        logCard.add(label("Log (press and hold to copy)", 14f, true), 0)
        logCard.add(logView, 6)
        root.addView(logCard)

        rootScroll = ScrollView(this).apply { addView(root) }
        setContentView(rootScroll)
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
        box.add(TextView(this).apply { text = "Background colour"; textSize = 14f; setTextColor(Color.DKGRAY) }, 0)
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
        box.add(TextView(this).apply { text = "Temperature unit"; textSize = 14f; setTextColor(Color.DKGRAY) }, 20)
        val rc = RadioButton(this).apply { text = "Celsius (\u00B0C)"; id = View.generateViewId(); isChecked = !useFahrenheit }
        val rf = RadioButton(this).apply { text = "Fahrenheit (\u00B0F)"; id = View.generateViewId(); isChecked = useFahrenheit }
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(rc); addView(rf) }
        rg.setOnCheckedChangeListener { _, checkedId -> setFahrenheit(checkedId == rf.id) }
        box.add(rg, 6)
        dialog = AlertDialog.Builder(this).setTitle("Settings").setView(box)
            .setNegativeButton("Close", null).create()
        dialog.show()
    }

    private fun log(s: String) = runOnUiThread { logView.append(s + "\n"); statusView.text = "Status: $s" }

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

    private fun adapter(): BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

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
                if (!ok) { log("FAILED: could not connect over Classic or BLE."); return@Thread }
                log("Connected to $name")
                // ELM327/STN init: reset, echo off, linefeeds off, spaces off, headers off,
                // auto-format CAN on, fixed protocol (no slow auto-search), 1 s ECU timeout
                for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATCAF1", "ATSP$proto", "ATSTFF")) {
                    log("> $c  <  ${send(c)}")
                }
                log("Adapter ready.")
            } catch (t: Throwable) { log("Connect error: ${t.javaClass.simpleName}: ${t.message}") }
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
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) log("BLE disconnected")
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
    }

    override fun onDestroy() { close(); super.onDestroy() }

    /** Send an AT or OBD command; read until the ELM '>' prompt (3 s timeout). */
    @Synchronized
    private fun send(cmd: String): String {
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

    private fun setFahrenheit(on: Boolean) {
        useFahrenheit = on
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("fahrenheit", on).apply()
        renderLast?.let { runOnUiThread { it() } }      // refresh what is already on screen
    }

    private fun readOnce() {
        val unit = PRESETS.getOrNull(presetSpinner.selectedItemPosition)?.unit ?: ""
        val v = readValue(headerField.text.toString().trim(), filterField.text.toString().trim(),
            requestField.text.toString().trim().replace(" ", "").uppercase(),
            byteIndexField.text.toString().toIntOrNull() ?: 0, byteLenField.text.toString().toIntOrNull() ?: 1,
            scaleField.text.toString().toDoubleOrNull() ?: 1.0, offsetField.text.toString().toDoubleOrNull() ?: 0.0,
            signedBox.isChecked)
        if (v != null) {
            renderLast = { resultView.textSize = 28f; resultView.text = fmt(v, unit, 1) }
            runOnUiThread { renderLast?.invoke() }
        }
    }

    private fun readAll() {
        val header = headerField.text.toString().trim(); val filter = filterField.text.toString().trim()
        val rows = mutableListOf<() -> String>()
        for (p in PRESETS) {
            val v = readValue(header, filter, p.request, p.byteIndex, p.byteLen, p.scale, p.offset, p.signed)
            if (v != null) rows += { p.name + ": " + fmt(v, p.unit, 2) }
            else if (p.name.startsWith("12V")) {
                // Fallback: adapter's own supply voltage on OBD pin 16 (~\u00B10.2 V)
                try { val t = send("ATRV"); rows += { "12V (ATRV fallback): $t" } } catch (e: Exception) { log("ATRV failed") }
            } else rows += { "${p.name}: no data" }
        }
        renderLast = { resultView.text = rows.joinToString("\n") { it() }; resultView.textSize = 16f }
        runOnUiThread { renderLast?.invoke() }
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
