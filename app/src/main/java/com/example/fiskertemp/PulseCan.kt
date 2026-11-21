package com.example.fiskertemp

// ---------------------------------------------------------------------------
// Listen-only CAN reader for the Pulse head unit (Orange Pi 5 Pro or Radxa Rock 5C).
//
// This project uses software from Haibin Wen and SUNNYPILOT LLC and is licensed under a
// custom license requiring permission for use.
//
// The approach (a small bundled helper, libcanbridge.so, that reads the car's CAN buses and
// prints one line per frame, which this class parses) and the helper itself come from the
// sunnypilot "oceanfront-view" project (android/canbridge/main.go). See THIRD_PARTY_NOTICE.md.
//
// RECEIVE ONLY: the helper is started WITHOUT any "tx:" argument and nothing is ever written to
// its stdin, so this app cannot send a frame to the car.
// ---------------------------------------------------------------------------

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/** Latest decoded values. null = not received yet, or the car marks it invalid. */
data class CanSnapshot(
    val avgTempC: Double? = null,      // pack average temperature (frame 0x382, byte 5)
    val cellMinC: Double? = null,      // coolest cell (0x382, byte 2)
    val cellMaxC: Double? = null,      // hottest cell (0x382, byte 4)
    val cellDiffC: Double? = null,     // spread between cells (0x382, byte 3)
    val thermalDemand: Int? = null,    // 0 starting up, 1 heating, 2 cooling, 3 cycling (0x382, byte 6)
    val socUsable: Int? = null,        // % (0x2F5, byte 0)
    val socReal: Int? = null,          // % (0x2F5, byte 6)
    val socRealRaw: Int? = null,       // the raw byte, kept so an invalid value can be shown
    val sohPct: Int? = null,           // % (0x2F5, byte 5)
    val packCapKwh: Double? = null,    // full pack capacity the BMS reports, kWh (0x2F5, bytes 3-4, 0.01)
    val packVolts: Double? = null,     // V (0xE9 on IBUS2, bytes 6-7)  needs the chip filters opened
    val packAmps: Double? = null,      // A (0xE9 on IBUS2, bytes 4-5, signed)
    val limit: Int? = null,            // road speed limit the car knows, in limitMph units (0x311 on IBUS2, byte 6); null = none
    val tsrMode: Int? = null,          // where the car gets the limit: 2 camera + map, 3 camera, 4 map, 1 off, 5 error (0x313 ADAS_TSRSts, byte 6 bits 5-3)
    val bsdLeft: Int = 0,              // blind spot, left: 0 none, 1 threat, 2 threat + indicator on, 3 critical (0x315 ADAS_BSD_CID_LeDispReq, byte 5 bits 2-0)
    val bsdRight: Int = 0,             // the same on the right (byte 5 bits 5-3)
    val dowLeft: Int = 0,              // door-open warning, left: 1-4 = a threat is approaching (0x315 ADAS_DOW_CID_LeDispReq, byte 2 bits 2-0)
    val dowRight: Int = 0,             // the same on the right (byte 2 bits 5-3)
    val bsdAt: Long = 0L,              // when 0x315 was last seen (ms), so old values can be ignored
    val uss: List<Int>? = null,        // 16 parking-sensor zones (0x352): rear 0-3, front 4-7, left 8-11, right 12-15; 0 off, 1-8 zone, 9 clear, 15 fault
    val ussAt: Long = 0L,
    val limitMph: Boolean = false,     // true if that limit is in mph, false for km/h (0x311 byte 3 bit 6)
    val hold: Boolean? = null,         // auto hold is holding the car (0x332 on IBUS2, byte 2 bit 2)
    val holdEsp: Boolean? = null,      // ESP is holding the car with brake pressure (0x1B8 on IBUS1, byte 2 bit 0)
    val ready: Boolean? = null,        // READY lamp: ready to drive (0x234 on IBUS1, byte 1 bit 4)
    val capKwh: Double? = null,        // energy left in the HV battery, kWh (0x580 on IBUS2, bytes 6-7, 0.01)
    val cabinC: Double? = null,        // cabin temperature, C (0x582 on IBUS2, byte 0, 0.5 per bit, -48)
    val outsideC: Double? = null,      // outside temperature, C (0x373 on IBUS1, byte 5, 0.5 per bit, -48)
    val solarW: Int? = null,           // solar roof power, W (0x6DD on IBUS1, 10 bits from byte 0)
    val volts12: Double? = null,       // 12V battery voltage (0x507 on IBUS1, bytes 6-7, 0.001 V)
    val soc12: Double? = null,         // 12V battery state of charge, % (0x2AC on IBUS1, byte 0, 0.5)
    val soh12: Double? = null,         // 12V battery state of health, % (0x2AC on IBUS1, byte 1, 0.5)
    val rangeKm: Double? = null,       // estimated range left, km (0x504 on IBUS2, bytes 5-6, 0.1 per bit)
    val beamsOn: Boolean? = null,      // low or high beam on (0x335 byte 5 bits 3-4); the position / daytime lamps do not count: the dashboard theme follows this
    val highBeamOn: Boolean? = null,   // high beam on (0x335 byte 5 bit 3, seen as 0x68 while the stalk is held; the low beam is bit 4, 0x70)
    val headlights: Boolean? = null,   // low beam, high beam or position lamps on (0x335 on IBUS1, byte 5 bits 6-3)
    val lightColor: Int? = null,       // traffic light the car sees ahead: 1 red, 2 amber, 3 green (0x210 on IBUS2); null = none
    val lightDistM: Int? = null,       // distance to it in metres (0x351 on IBUS2, byte 6)
    val brakeBcm: Boolean? = null,     // brake lamp output command (0x335 on IBUS1, byte 5 bit 7)
    val brakeVcu: Boolean? = null,     // stop lamp request from the drive unit (0x234 on IBUS1, byte 1 bit 6)
    val driveMode: Int? = null,        // 0 Earth, 1 Fun, 2 Hyper (0x234 on IBUS1, byte 7 bits 5-3)
    val regenKwh: Double = 0.0,        // energy put back into the battery while moving, this drive (from 0xE9 and 0x318)
    val headingDeg: Double? = null,    // compass heading, degrees from north (0x179 on IBUS1, bytes 2-3, 0.01 per bit)
    val speedKmh: Double? = null,      // vehicle speed, km/h (0x318 on IBUS1, bytes 5-6, 0.1 per bit)
    val climateKw: Double? = null,     // total power the climate system draws, kW (0x1A9 on IBUS2, bytes 2-3)
    val compState: Int? = null,        // heat-pump compressor: 0 standby, 1 working, 2 fault (0x491 on IBUS2, byte 2)
    val compWatts: Double? = null,     // heat-pump compressor power, W (0x491 on IBUS2, bytes 6-7)
    // trip, counted by this app since the last reset (speed from 0x318, power from 0xE9)
    val tripKm: Double = 0.0,          // distance driven
    val tripKwh: Double = 0.0,         // net energy used (regen taken off); idle use counts only while the car is READY
    val tripMoveSecs: Double = 0.0,    // seconds spent moving
    val tripStartMs: Long = 0L,        // when this trip started
    // the car's own trip figures
    val carTripKm: Double? = null,         // trip distance, km (0x5EA on IBUS2, bytes 2-3, 0.1 per bit)
    val carTripWhKm: Int? = null,          // trip average consumption, Wh/km (0x5EA, byte 4 and the top 2 bits of byte 5)
    val carInstKwh100: Double? = null,     // instantaneous consumption, kWh/100 km (0x505 on IBUS2, bytes 2-3, 0.01, offset -100)
    // climate status as the car's A/C unit reports it (0x373), read-only
    val climSeen: Boolean = false,         // at least one 0x373 frame decoded
    val climAc: Boolean = false,
    val climAuto: Boolean = false,
    val climSync: Boolean = false,
    val climHeat: Boolean = false,
    val climAirClean: Boolean = false,
    val climOuterAir: Boolean = false,     // false = recirculating inside air
    val climMaxDefrost: Boolean = false,
    val climFan: Int = 0,                  // 0-7
    val climDrvT: Double? = null,          // driver set temperature, C (null = inactive)
    val climPassT: Double? = null,
    val climDrvMode: Int = 0,              // 1 face, 2 face+foot, 3 foot, 4 foot+window, 5 window
    val climPassMode: Int = 0,             // 1 face, 2 face+foot, 3 foot, 4 foot+window
    val climRearMode: Int = 0,             // 0 rear face, 1 face+foot, 2 foot, 3 cut off
    val climState: Int = 0,                // ECC_CrtSts
    val climAdvice: Int = 0,               // ECC_InfoSysWrmColdDisp: 0 none, 1 natural, 2 cold, 3 hot
    val climFault: Boolean = false,
    val climLimited: Boolean = false,
    val climRemote: Int = 0,               // 0 standby, 1 remote A/C working, 2 fault
    val leftInd: Boolean = false,          // left turn signal lamp lit right now (0x335 byte 4 bit 6; flashes with the car, both sides for hazards)
    val rightInd: Boolean = false,         // right turn signal lamp lit right now (0x335 byte 4 bit 7)
    val rearDefrost: Boolean? = null,      // rear window heater on (0x335 BCM_ReDefrstHeatgCmd, IBUS1), status only
    val wheelHeat: Boolean? = null,        // heated steering wheel on (0x386 BCM_HeatedSteerWhlSt, IBUS1); null = not received
    val packKw: Double? = null,            // battery power, kW, positive = drawing (0xE9 on IBUS2, or 0x2F4 on IBUS1 when 0xE9 is filtered out)
    val seatDrv: Int? = null,              // seat heating level 0 off, 1 low, 2 medium, 3 high (0x518 / 0x512, IBUS1); null = not received
    val seatPass: Int? = null,
    val seatRL: Int? = null,
    val seatRR: Int? = null,
)

class PulseCan(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onUpdate: (CanSnapshot) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var filtersDone = false
    @Volatile var frames = 0L          // frames received, for the event log
    /** Safe mode: never touch the CAN chips' filters (power and climate may then not show). Set by the app. */
    @Volatile var safeMode = false
    private val lastSeen = HashMap<String, Long>()
    @Volatile private var filterReadback = ""
    @Volatile private var proc: Process? = null
    @Volatile private var snap = CanSnapshot()

    /** When a frame last CHANGED on any bus. The helper repeats unchanged frames once a second, even
     *  with the car asleep, so a repeat does not count as the car being awake. */
    @Volatile var lastChangeAt = 0L
        private set

    /** Set by the app: true if the pack current sign is the other way round (see Settings). */
    @Volatile var flipPower = false
    // energy put back while the car is moving (so plugging in to charge does not count)
    @Volatile private var regenWh = 0.0
    private var lastE9Nanos = 0L
    private var lastE9Ms = 0L
    private var lastP2F4Nanos = 0L
    // trip counters (reader thread adds to them)
    @Volatile private var tripKm = 0.0
    @Volatile private var tripWh = 0.0
    @Volatile private var tripMoveSecs = 0.0
    @Volatile private var tripStartMs = System.currentTimeMillis()
    private var lastSpdNanos = 0L

    // the trip counters survive the screen being rebuilt for a layout change (kept in memory for a minute)
    init {
        TripKeep.take()?.let { k ->
            regenWh = k.regenWh; tripKm = k.tripKm; tripWh = k.tripWh; tripMoveSecs = k.moveSecs; tripStartMs = k.startMs
            snap = snap.copy(regenKwh = regenWh / 1000.0, tripKm = tripKm, tripKwh = tripWh / 1000.0, tripMoveSecs = tripMoveSecs, tripStartMs = tripStartMs)
        }
    }
    fun saveTrip() { TripKeep.put(TripKeep.Data(regenWh, tripKm, tripWh, tripMoveSecs, tripStartMs, System.currentTimeMillis())) }

    /** Start the trip again from zero: regen, distance, energy and time. */
    fun resetDrive() {
        regenWh = 0.0; tripKm = 0.0; tripWh = 0.0; tripMoveSecs = 0.0
        tripStartMs = System.currentTimeMillis()
        snap = snap.copy(regenKwh = 0.0, tripKm = 0.0, tripKwh = 0.0, tripMoveSecs = 0.0, tripStartMs = tripStartMs)
    }

    private val seenFirst = HashSet<String>()      // "bus/id" of the uncommon IDs already reported (reader thread only)

    private val held = HashMap<String, String>()   // "bus/id" -> last payload (reader thread only)

    companion object {
        private const val TAG = "FiskerBuddyCAN"

        private fun present(): Set<String> =
            listOf("can0", "can1", "can2").filter { File("/sys/class/net/$it").exists() }.toSet()

        /** True on a device that has CAN interfaces (the Pulse). */
        fun available(): Boolean = present().isNotEmpty()

        /**
         * Which interface carries which bus. Orange Pi 5 Pro units: IBUS1 = can1, IBUS2 = can2.
         * Radxa Rock 5C units: IBUS1 = can0, IBUS2 = can1.
         */
        fun interfaces(): Pair<String, String> {
            val p = present()
            return if ("can2" in p || "can0" !in p) "can1" to "can2" else "can0" to "can1"
        }
    }

    /** One CAN chip's receive filters as found, and what (if anything) would be added to them. */
    private class ChipPlan(
        val chip: Int, val mask: List<Int>, val filt: List<Int>,
        val newFilt: List<Int>, val missing: List<Int>, val added: List<Int>, val unfit: List<Int>,
    )

    /** The IDs this app reads, per chip (0 = IBUS1, 1 = IBUS2). Must match the helper's ID lists in loop(). */
    private val wanted = mapOf(
        0 to listOf(0x382, 0x2F5, 0x318, 0x179, 0x234, 0x335, 0x2AC, 0x373, 0x507, 0x6DD),
        1 to listOf(0x0E9, 0x1A9, 0x210, 0x351, 0x504, 0x580, 0x582, 0x332, 0x311, 0x491, 0x5EA, 0x505),
    )

    private fun hex(l: List<Int>) = l.joinToString(",") { "0x" + Integer.toHexString(it).uppercase() }

    /** Which IDs the chip lets through at the moment: buffer 0 (filters 0-1, mask 0) and buffer 1 (filters 2-5, mask 1). */
    private fun admits(mask: List<Int>, f: List<Int>, id: Int): Boolean =
        (id and mask[0]) == (f[0] and mask[0]) || (id and mask[0]) == (f[1] and mask[0]) ||
            (2..5).any { (id and mask[1]) == (f[it] and mask[1]) }

    /**
     * Reads the chips' current filters (set by the Pulse itself) and works out which of our IDs they already let
     * through. Only the IDs that are missing get a place, and only in a SPARE filter slot (one that repeats another
     * slot of the same buffer), so nothing the Pulse itself receives is ever taken away. Nothing is written here.
     */
    private fun readAndPlan(dir: File): Pair<List<ChipPlan>?, String> {
        val q = Root.exec("cat ${dir.path}/rxb_mask_0; cat ${dir.path}/rxb_filter_0; cat ${dir.path}/rxb_mask_1; cat ${dir.path}/rxb_filter_1")
        if (q.timedOut) return null to "root shell timed out (is root allowed for this app?)"
        if (!q.ok) return null to "no root: ${q.out}"
        val rows = q.out.lines().map { it.trim() }.filter { it.isNotEmpty() && it.all { c -> c.isDigit() || c == ',' || c == ' ' } }
            .map { r -> r.split(',', ' ').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() } }
        if (rows.size != 4 || rows[0].size != 2 || rows[2].size != 2 || rows[1].size != 6 || rows[3].size != 6)
            return null to "could not read the filters (${q.out.trim().replace('\n', ' ').take(120)})"
        val plans = (0..1).map { c ->
            val mask = rows[c * 2]
            val filt = rows[c * 2 + 1]
            val nf = filt.toMutableList()
            val missing = wanted.getValue(c).filter { !admits(mask, filt, it) }
            val added = mutableListOf<Int>()
            val unfit = mutableListOf<Int>()
            for (id in missing) {
                if (admits(mask, nf, id)) { added.add(id); continue }          // an earlier addition already covers it
                // buffer 1: a slot that repeats an earlier slot (same value under the mask) is spare
                var slot = (3..5).firstOrNull { k -> (2 until k).any { (nf[it] and mask[1]) == (nf[k] and mask[1]) } }
                var m = mask[1]
                if (slot == null && (nf[1] and mask[0]) == (nf[0] and mask[0])) { slot = 1; m = mask[0] }
                if (slot == null) { unfit.add(id); continue }
                // a slot lets through a whole block of IDs (everything that matches under the mask): never open a wide one, it floods the chip
                val block = 1 shl (11 - Integer.bitCount(m and 0x7FF))
                if (block > 16) { unfit.add(id); continue }
                nf[slot] = id and m
                added.add(id)
            }
            ChipPlan(c, mask, filt, nf, missing, added, unfit)
        }
        return plans to ""
    }

    /**
     * The Pulse's CAN chips only let through the IDs their acceptance filters allow. This looks at what the Pulse
     * set (chip 0 = IBUS1, chip 1 = IBUS2) and adds only what is missing, in spare slots; if nothing is missing,
     * nothing is written. In safe mode it only reads and reports. It changes what is RECEIVED; nothing is sent.
     * Needs root. Returns null on success, else why not.
     */
    private fun openFilters(): String? {
        val dir = File("/sys/module/mcp251x/parameters")
        if (!dir.isDirectory) return "no mcp251x driver parameters on this device"
        val (plans, err) = try { readAndPlan(dir) } catch (e: Exception) { null to "filter check failed: ${e.message}" }
        if (plans == null) return err
        val found = plans.joinToString("; ") { "chip${it.chip} mask=${hex(it.mask)} filters=${hex(it.filt)}" }
        val gaps = plans.joinToString("; ") { "chip${it.chip} not admitted: ${if (it.missing.isEmpty()) "none" else hex(it.missing)}" }
        if (safeMode) return "safe mode, nothing written. As found: $found. $gaps"
        val changes = plans.filter { it.newFilt != it.filt }
        val left = plans.flatMap { p -> p.unfit.map { "chip${p.chip}:0x" + Integer.toHexString(it).uppercase() } }
        if (changes.isEmpty()) {
            filterReadback = "as found, nothing written. $found. $gaps" + (if (left.isNotEmpty()) " (no safe slot for ${left.joinToString(",")}: none spare, or the slot would let through too many IDs)" else "")
            return null
        }
        val sb = StringBuilder()
        for (p in changes) sb.append("echo '${p.newFilt.joinToString(",")}' > ${dir.path}/rxb_filter_${p.chip}; ")
        sb.append("echo 1 > ${dir.path}/apply_filters; echo '--'; cat ${dir.path}/rxb_filter_0 ${dir.path}/rxb_filter_1")
        return try {
            val r = Root.exec(sb.toString())
            if (r.timedOut) return "root shell timed out (is root allowed for this app?)"
            if (!r.ok) return "no root: ${r.out}"
            val out = r.out
            val addedTxt = changes.joinToString("; ") { "chip${it.chip} added ${hex(it.added)}" }
            filterReadback = "$addedTxt. Before: $found. Now: " + out.substringAfter("--", "").trim().replace('\n', ' ') +
                (if (left.isNotEmpty()) " (no safe slot for ${left.joinToString(",")}: none spare, or the slot would let through too many IDs)" else "")
            if (out.contains("denied", true) || out.contains("not found", true) || r.code != 0) "root command failed: ${out.trim()}" else null
        } catch (e: Exception) {
            "no root shell (${e.message})"
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        val bin = File(context.applicationInfo.nativeLibraryDir, "libcanbridge.so")
        if (!bin.canExecute()) {
            onStatus("CAN helper not found or not executable: ${bin.path}")
            return
        }
        running = true
        // a generation number, so a reader that is still winding down after stop() can never carry on beside a new one
        val my = ++generation
        thread(name = "pulse-can", isDaemon = true) { loop(bin.path, my) }
    }

    @Synchronized
    fun stop() {
        running = false
        proc?.destroy()
        proc = null
    }

    @Volatile private var generation = 0

    // ---- test: change the fan speed by sending the car screen's 0x530 command frame (only when enabled in Settings)
    private val last530 = arrayOfNulls<IntArray>(3)          // newest 0x530 seen from the car's own screen, per bus
    private val last530At = LongArray(3)
    private val last528 = arrayOfNulls<IntArray>(3)          // newest 0x528 seen from the car's own screen (carries the heated-wheel request)
    private val last528At = LongArray(3)
    private val last534 = arrayOfNulls<IntArray>(3)          // newest 0x534 from the car's screen (carries the climate power command)
    private val last534At = LongArray(3)
    @Volatile private var txOut: java.io.OutputStream? = null
    @Volatile private var txIfaces: Pair<String, String>? = null
    @Volatile private var txActive = false
    private val txLock = Any()

    /**
     * Ask for fan level 1-7. The frame is the car screen's own last 0x530 with every momentary request bit cleared
     * (A/C, AUTO, SYNC, recirculation, air clean, defrost, windshield blow) and only the fan field set, sent a few times on the
     * bus where that screen's 0x530 was seen. The A/C unit's 0x373 status shows whether it was accepted.
     */
    fun sendFan(level: Int): String {
        if (!txActive) return "Fan control is off: tick it in Settings and restart the app"
        if (level !in 1..7) return "Fan level out of range"
        val ifs = txIfaces ?: return "CAN reader not running"
        val out = txOut ?: return "CAN reader not running"
        var bus = 0
        for (b in 1..2) if (last530[b] != null && (bus == 0 || last530At[b] > last530At[bus])) bus = b
        if (bus == 0) return "No 0x530 seen from the car's screen yet: change the fan once on the car's own climate page, then try again"
        val d = last530[bus]!!.copyOf()
        if (d.size != 8) return "0x530 frame had an unexpected length"
        d[2] = d[2] and 0xC7       // AUTO, SYNC, A/C requests off (bits 3-5)
        d[3] = (d[3] and 0x8F) or level                            // recirculation, air clean, defrost requests off; fan = level
        d[6] = d[6] and 0x7F                                       // windshield blow button off
        val iface = if (bus == 1) ifs.first else ifs.second
        val line = "tx $iface 530 " + d.joinToString("") { String.format("%02X", it and 0xFF) } + "\n"
        Thread {
            synchronized(txLock) {
                try {
                    // one frame only: repeating it made the car keep stepping the fan up to 7
                    out.write(line.toByteArray()); out.flush()
                } catch (e: Exception) { onStatus("Climate TX failed: ${e.message}") }
            }
        }.start()
        return "Fan $level sent on IBUS$bus"
    }

    /**
     * Test: ask for the heated steering wheel. The frame is the car screen's own last 0x528 with the seat-heat and other
     * requests cleared ("not change") and only ICC_SWH_Req = 1 set, sent a few times on the bus where 0x528 was seen.
     * The request only has an "on" value, so it probably acts like the button press: watch 0x386 for the result.
     */
    fun sendWheelHeat(): String = sendOn528()

    /**
     * Test: climate system power. The frame is the car screen's own last 0x534 with every airflow button request cleared
     * and only ECC_ECCSysSwtCmd set (1 = power on, 2 = shutdown), sent a few times on the bus where 0x534 was seen.
     */
    fun sendClimatePower(on: Boolean): String {
        if (!txActive) return "Control is off: tick it in Settings and restart the app"
        val ifs = txIfaces ?: return "CAN reader not running"
        val out = txOut ?: return "CAN reader not running"
        val (bus, d) = tpl534()
        d[6] = 0                                  // face / foot button requests and rear outlet request: none
        d[7] = if (on) 1 else 2                   // system command; the blow-mode requests are 'inactive'
        val iface = if (bus == 1) ifs.first else ifs.second
        val line = "tx $iface 534 " + d.joinToString("") { String.format("%02X", it and 0xFF) } + "\n"
        Thread {
            synchronized(txLock) {
                try {
                    for (i in 0 until 3) { out.write(line.toByteArray()); out.flush(); Thread.sleep(100) }
                } catch (e: Exception) { onStatus("Climate TX failed: ${e.message}") }
            }
        }.start()
        return if (on) "Climate power ON sent on IBUS$bus" else "Climate power OFF sent on IBUS$bus"
    }

    /**
     * Test: set the driver (or both, when SYNC is on) set temperature. The frame is the car screen's own last 0x530 with every
     * request bit cleared and the fan request 'inactive'; only the set-temperature field is filled (0 = no change for the other side).
     */
    fun sendTemp(driver: Boolean, tempC: Double, sync: Boolean): String {
        if (!txActive) return "Control is off: tick it in Settings and restart the app"
        val ifs = txIfaces ?: return "CAN reader not running"
        val out = txOut ?: return "CAN reader not running"
        val raw = Math.round(tempC.coerceIn(18.0, 32.0) * 2).toInt()
        val (bus, d) = tpl530()
        if (d.size != 8) return "0x530 frame had an unexpected length"
        d[2] = d[2] and 0xC7                       // AUTO, SYNC, A/C requests off
        d[3] = d[3] and 0x80                       // fan request inactive; recirculation, air clean, defrost requests off
        d[6] = d[6] and 0x7F                       // windshield blow button off
        d[0] = if (driver || sync) raw else 0
        d[1] = if (!driver || sync) raw else 0
        val iface = if (bus == 1) ifs.first else ifs.second
        val line = "tx $iface 530 " + d.joinToString("") { String.format("%02X", it and 0xFF) } + "\n"
        Thread {
            synchronized(txLock) {
                try { out.write(line.toByteArray()); out.flush() } catch (e: Exception) { onStatus("Climate TX failed: ${e.message}") }
            }
        }.start()
        return "${if (driver) "Driver" else "Passenger"}${if (sync) " + sync" else ""} temperature ${raw / 2.0} sent on IBUS$bus"
    }

    /**
     * Test: set the driver's air direction (1 face, 2 face+feet, 3 feet, 4 feet+windshield, 5 windshield). The frame is the car
     * screen's own last 0x534 with every button request cleared and only the driver blow mode set.
     */
    fun sendAirMode(mode: Int): String {
        if (!txActive) return "Control is off: tick it in Settings and restart the app"
        if (mode !in 1..5) return "Air direction out of range"
        val ifs = txIfaces ?: return "CAN reader not running"
        val out = txOut ?: return "CAN reader not running"
        val (bus, d) = tpl534()
        d[6] = 0                                   // face / foot button requests and rear outlet request: none
        d[7] = mode shl 2               // driver blow mode; system command and passenger mode 'inactive'
        val iface = if (bus == 1) ifs.first else ifs.second
        val line = "tx $iface 534 " + d.joinToString("") { String.format("%02X", it and 0xFF) } + "\n"
        Thread {
            synchronized(txLock) {
                try { out.write(line.toByteArray()); out.flush() } catch (e: Exception) { onStatus("Climate TX failed: ${e.message}") }
            }
        }.start()
        return "Air direction $mode sent on IBUS$bus"
    }

    /**
     * Test: press one of the A/C buttons: 0 = A/C, 1 = max defrost, 2 = recirculation / fresh air, 3 = sync, 4 = auto. The frame is the car screen's own
     * last 0x530 with every request cleared, the fan and temperature requests 'inactive', and only that one button request set.
     * These are press requests (each one toggles), so a single frame is sent.
     */
    fun sendButton(kind: Int): String {
        if (!txActive) return "Control is off: tick it in Settings and restart the app"
        val ifs = txIfaces ?: return "CAN reader not running"
        val out = txOut ?: return "CAN reader not running"
        val (bus, d) = tpl530()
        if (d.size != 8) return "0x530 frame had an unexpected length"
        d[0] = 0; d[1] = 0                         // set temperatures: no change
        d[2] = d[2] and 0xC7                       // AUTO, SYNC, A/C requests off
        d[3] = d[3] and 0x80                       // fan request inactive; recirculation, air clean, defrost requests off
        d[6] = d[6] and 0x7F                       // windshield blow button off
        when (kind) {
            0 -> d[2] = d[2] or 0x20               // ICC_ACSwtReq
            1 -> d[3] = d[3] or 0x40               // ICC_MaxFrntDefrstSet
            2 -> d[3] = d[3] or 0x10               // ICC_ECCIntExtCircReq
            3 -> d[2] = d[2] or 0x10               // ICC_DrvrSYNCReq
            4 -> d[2] = d[2] or 0x08               // ICC_ECCAUTOReq
            else -> return "Unknown button"
        }
        val iface = if (bus == 1) ifs.first else ifs.second
        val line = "tx $iface 530 " + d.joinToString("") { String.format("%02X", it and 0xFF) } + "\n"
        Thread {
            synchronized(txLock) {
                try { out.write(line.toByteArray()); out.flush() } catch (e: Exception) { onStatus("Climate TX failed: ${e.message}") }
            }
        }.start()
        return "${listOf("A/C", "Defrost", "Recirculation", "Sync", "Auto")[kind]} request sent on IBUS$bus"
    }

    /** The bus the car screen is on: where its steady 0x528 turns up, else IBUS1 (the DBC puts the screen's frames there). */
    private fun screenBus(): Int = if (last528[2] != null && (last528[1] == null || last528At[2] > last528At[1])) 2 else 1

    /**
     * A frame to start from for 0x530: the car screen's own last one if the app has seen it; the screen sends 0x530 only when someone
     * presses something on the climate page, so otherwise one is built here: no requests, temperatures and fan 'inactive',
     * and the navigation speed-limit fields set to their 'invalid / unknown' values.
     */
    private fun tpl530(): Pair<Int, IntArray> {
        var bus = 0
        for (b in 1..2) if (last530[b] != null && (bus == 0 || last530At[b] > last530At[bus])) bus = b
        if (bus != 0) return Pair(bus, last530[bus]!!.copyOf())
        return Pair(screenBus(), intArrayOf(0, 0, 0, 0, 0xFF, 0x0F, 0, 0xFF))
    }

    /** The same for 0x534 (airflow and power): no requests; minimum-charge setting 'invalid'; the battery-heating switch state 'off'. */
    private fun tpl534(): Pair<Int, IntArray> {
        var bus = 0
        for (b in 1..2) if (last534[b] != null && (bus == 0 || last534At[b] > last534At[bus])) bus = b
        if (bus != 0) return Pair(bus, last534[bus]!!.copyOf())
        return Pair(screenBus(), intArrayOf(0, 0, 0, 0, 0xFF, 0, 0, 0))
    }

    private fun sendOn528(): String {
        if (!txActive) return "Control is off: tick it in Settings and restart the app"
        val ifs = txIfaces ?: return "CAN reader not running"
        val out = txOut ?: return "CAN reader not running"
        var bus = 0
        for (b in 1..2) if (last528[b] != null && (bus == 0 || last528At[b] > last528At[bus])) bus = b
        if (bus == 0) return "No 0x528 seen from the car's screen yet: press the heated wheel (or a seat heat) once on the car's own screen, then try again"
        val d = last528[bus]!!.copyOf()
        d[1] = d[1] and 0xE3                  // driver seat heat request: not change
        d[3] = d[3] and 0xF0                  // seat-belt and anti-theft requests: not change
        d[4] = (d[4] and 0xE0) or 1           // passenger seat heat: not change; heated wheel request = on
        d[5] = d[5] and 0x18                  // rear seat heat requests: not change (keeps the KL15 flag)
        val iface = if (bus == 1) ifs.first else ifs.second
        val line = "tx $iface 528 " + d.joinToString("") { String.format("%02X", it and 0xFF) } + "\n"
        Thread {
            synchronized(txLock) {
                try {
                    for (i in 0 until 3) { out.write(line.toByteArray()); out.flush(); Thread.sleep(100) }
                } catch (e: Exception) { onStatus("Climate TX failed: ${e.message}") }
            }
        }.start()
        return "Heated wheel request sent on IBUS$bus"
    }

    /**
     * Seat heating: which = 0 driver, 1 passenger, 2 rear left, 3 rear right; level 0 off, 1 low, 2 medium, 3 high.
     * Same method as the heated wheel: the car screen's own last 0x528 with every request set to "not change"
     * (0) and only this seat's request filled in (4 off, 3 low, 2 medium, 1 high), sent a few times.
     */
    fun sendSeatHeat(which: Int, level: Int): String {
        if (!txActive) return "Control is off: tick it in Settings and restart the app"
        val ifs = txIfaces ?: return "CAN reader not running"
        val out = txOut ?: return "CAN reader not running"
        var bus = 0
        for (b in 1..2) if (last528[b] != null && (bus == 0 || last528At[b] > last528At[bus])) bus = b
        if (bus == 0) return "No 0x528 seen from the car's screen yet: press a seat heat once on the car's own screen, then try again"
        val d = last528[bus]!!.copyOf()
        val req = intArrayOf(4, 3, 2, 1)[level.coerceIn(0, 3)]
        d[1] = d[1] and 0xE3                  // driver seat request: not change
        d[2] = d[2] and 0xF8                  // rear left display sync: not change
        d[3] = d[3] and 0x80                  // seat-belt, anti-theft requests and rear right display sync: not change
        d[4] = d[4] and 0xE0                  // passenger seat request and heated wheel request: not change
        d[5] = d[5] and 0x18                  // rear seat requests: not change (keeps the KL15 flag)
        when (which) {
            0 -> d[1] = d[1] or (req shl 2)
            1 -> d[4] = d[4] or (req shl 2)
            2 -> d[5] = d[5] or req
            else -> d[5] = d[5] or (req shl 5)
        }
        val iface = if (bus == 1) ifs.first else ifs.second
        val line = "tx $iface 528 " + d.joinToString("") { String.format("%02X", it and 0xFF) } + "\n"
        Thread {
            synchronized(txLock) {
                try {
                    for (i in 0 until 3) { out.write(line.toByteArray()); out.flush(); Thread.sleep(100) }
                } catch (e: Exception) { onStatus("Climate TX failed: ${e.message}") }
            }
        }.start()
        return "Seat heat ${listOf("driver", "passenger", "rear left", "rear right")[which.coerceIn(0, 3)]} level $level sent on IBUS$bus"
    }

    private fun loop(bin: String, my: Int) {
        var tries = 0
        while (running && generation == my) {
            val (b1, b2) = interfaces()
            val startedAt = System.currentTimeMillis()
            try {
                // receive only: no "tx:" arguments
                // the filters are written once per run of the app, and only if they are not already as wanted
                if (!filtersDone) {
                    val why = openFilters()
                    if (why == null) filtersDone = true
                    onStatus(if (why == null) "CAN filters: $filterReadback" else "CAN filters not changed: $why")
                }
                txActive = context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("climateTx", false)
                val base = listOf(bin, "$b1=382,2F5,2F4,554,318,179,234,335,2AC,373,507,6DD,1A9,5EA,505,530,534,386,528,512,518", "$b2=0E9,1A9,210,351,504,580,582,332,311,491,5EA,505,373,530,534,528,313,315,352")
                // transmit is allowed only for 0x530, 0x528 and 0x534 and only when the test is ticked in Settings; otherwise the helper is receive-only
                val args = if (txActive) base + listOf("tx:$b1=530,528,534", "tx:$b2=530,528,534") else base
                onStatus("Starting CAN reader: IBUS1 = $b1, IBUS2 = $b2")
                val p = ProcessBuilder(args).redirectErrorStream(false).start()
                proc = p
                txIfaces = b1 to b2
                txOut = if (txActive) p.outputStream else null
                // the helper's error output must be drained or it can block; pass on changes only
                thread(name = "pulse-can-err", isDaemon = true) {
                    var lastMsg = ""
                    var lastAt = 0L
                    try {
                        p.errorStream.bufferedReader().forEachLine { line ->
                            Log.w(TAG, "helper: $line")
                            val now = System.currentTimeMillis()
                            if (line != lastMsg || now - lastAt > 10_000) {
                                lastMsg = line; lastAt = now
                                onStatus("Helper: $line")
                            }
                        }
                    } catch (e: Exception) { /* ended */ }
                }
                // the helper ends when its stdin closes, so we keep it open (we only write to it for the fan test, when enabled)
                p.inputStream.bufferedReader().forEachLine { handle(it, b1, b2) }
                val code = p.waitFor()
                onStatus("CAN reader stopped (code $code)")
            } catch (e: Exception) {
                onStatus("CAN reader failed: ${e.message}")
            }
            txOut = null
            proc?.destroy()
            proc = null
            if (System.currentTimeMillis() - startedAt > 30_000) tries = 0
            if (running && generation == my && tries > 8) {
                onStatus("CAN reader kept stopping, so it was switched off to protect the Pulse. Restart the app to try again.")
                running = false
                return
            }
            if (running && generation == my) {
                try { Thread.sleep(minOf(30_000L, 1000L shl minOf(tries++, 5))) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun handle(line: String, b1: String, b2: String) {
        if (line.startsWith("#")) {
            if (line.contains("ready")) onStatus("CAN reader ready")
            else if (line.startsWith("# tx")) onStatus("Helper " + line.removePrefix("# "))
            return
        }
        val parts = line.trim().split(' ')
        if (parts.size != 3) return
        val bus = when (parts[0]) {
            b1 -> 1
            b2 -> 2
            else -> return
        }
        val id = parts[1].toIntOrNull(16) ?: return
        val hex = parts[2]
        if (hex.length % 2 != 0) return
        val bytes = IntArray(hex.length / 2)
        for (i in bytes.indices) bytes[i] = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return

        frames++
        if (id == 0x530 && bytes.size == 8) { last530[bus] = bytes; last530At[bus] = System.currentTimeMillis() }
        if (id == 0x534 && bytes.size == 8) { last534[bus] = bytes; last534At[bus] = System.currentTimeMillis() }
        if (id == 0x528 && bytes.size == 8) { last528[bus] = bytes; last528At[bus] = System.currentTimeMillis() }
        // note once where the less common IDs turn up (they may be on either bus)
        if ((id == 0x1A9 || id == 0x5EA || id == 0x505 || id == 0x491 || id == 0x373 || id == 0x386 || id == 0x313 || id == 0x530 || id == 0x534) && seenFirst.add("$bus/$id"))
            onStatus("First frame of 0x" + Integer.toHexString(id).uppercase() + " seen on IBUS$bus")
        val key = "$bus/$id"
        // keep the load down: a frame of the same ID is used at most every 90 ms (40 ms for brake and drive mode)
        val tnow = System.currentTimeMillis()
        val gap = if (id == 0x234 || id == 0x335) 40L else 90L
        val prev = lastSeen[key]
        if (prev != null && tnow - prev < gap) return
        lastSeen[key] = tnow
        if (held.put(key, hex) != hex) {
            val now = System.currentTimeMillis()
            // nothing changed for 10 minutes: the car slept, so this is a new drive
            if (lastChangeAt != 0L && now - lastChangeAt > 600_000L) resetDrive()
            lastChangeAt = now
        }

        val next = decode(snap, bus, id, bytes) ?: return
        snap = next
        onUpdate(next)
    }

    private fun decode(s: CanSnapshot, bus: Int, id: Int, b: IntArray): CanSnapshot? {
        if (b.size < 8) return null
        // 0x382 BMS_CellTemp_CoolingHeating (IBUS1, every 100 ms): temperatures are raw - 48, 255 = invalid
        if (bus == 1 && id == 0x382) {
            fun t(raw: Int): Double? = if (raw == 255) null else (raw - 48).toDouble()
            return s.copy(
                cellMinC = t(b[2]), cellDiffC = b[3].toDouble(), cellMaxC = t(b[4]),
                avgTempC = t(b[5]), thermalDemand = b[6] and 0x03,
            )
        }
        // 0x234 VCU_0x234 (IBUS1): VCU_DrvModSigFb 0 Eco (Earth), 1 Normal (Fun), 2 Sport (Hyper), 7 invalid
        if (bus == 1 && id == 0x234) {
            val m = (b[7] shr 3) and 7
            return s.copy(driveMode = if (m <= 2) m else null, brakeVcu = ((b[1] shr 6) and 1) == 1, ready = ((b[1] shr 4) and 1) == 1)
        }
        // 0x335 BCM_0x335 (IBUS1): BCM_BrkLampOutpCmd, the brake lamp output command (1 = on)
        if (bus == 1 && id == 0x335) {
            val lamps = ((b[5] shr 3) and 1) == 1 || ((b[5] shr 4) and 1) == 1 || ((b[5] shr 5) and 3) != 0
            return s.copy(brakeBcm = ((b[5] shr 7) and 1) == 1, headlights = lamps, highBeamOn = ((b[5] shr 3) and 1) == 1, beamsOn = ((b[5] shr 3) and 1) == 1 || ((b[5] shr 4) and 1) == 1, rearDefrost = ((b[4] shr 3) and 1) == 1, leftInd = ((b[4] shr 6) and 1) == 1, rightInd = ((b[4] shr 7) and 1) == 1)
        }
        // 0x179 TBOX_0x179 (IBUS1, every 100 ms): vehicle heading, 0.01 degrees per bit
        if (bus == 1 && id == 0x179) {
            val deg = ((b[2] shl 8) or b[3]) * 0.01
            return s.copy(headingDeg = if (deg <= 359.99) deg else null)
        }
        // 0x318 ESP_0x318 (IBUS1, every 20 ms): vehicle speed, 0.1 km/h per bit; ESP_VehSpdVld 2 = fault
        if (bus == 1 && id == 0x318) {
            val bad = (b[2] and 3) == 2
            val kmh = if (bad) null else ((b[5] shl 8) or b[6]) * 0.1
            val nowN = System.nanoTime()
            val dt = if (lastSpdNanos == 0L) 0.0 else (nowN - lastSpdNanos) / 1e9
            lastSpdNanos = nowN
            if (kmh != null && dt in 0.0..0.5) {
                tripKm += kmh * dt / 3600.0
                if (kmh > 1.0) tripMoveSecs += dt
            }
            return s.copy(speedKmh = kmh, tripKm = tripKm, tripMoveSecs = tripMoveSecs, tripStartMs = tripStartMs)
        }
        // 0x2F5 BMS_SOC (IBUS1, every 100 ms)
        if (bus == 1 && id == 0x2F5) {
            return s.copy(
                socUsable = b[0].takeIf { it <= 100 },
                sohPct = b[5],
                packCapKwh = ((b[3] shl 8) or b[4]).let { if (it == 0 || it == 0xFFFF) null else it * 0.01 },
                socReal = b[6].takeIf { it <= 100 },
                socRealRaw = b[6],
            )
        }
        // 0x554 VCU_0x554 (IBUS1, every 100 ms): VCU_HVBattActPwr, actual HV battery power, 14 bits (byte 4 low 6 bits, byte 5) x 0.1 kW - 600.
        // The Pulse's newer CAN filters no longer let 0xE9 through, so this is the power source when 0xE9 is silent.
        if (id == 0x2F4 && seenFirst.add("raw/2F4")) onStatus("0x2F4 raw " + b.joinToString("") { String.format("%02X", it and 0xFF) })
        if (bus == 1 && id == 0x554) {
            val raw = ((b[4] and 0x3F) shl 8) or b[5]
            // older Pulse builds (2821 and earlier) pass 0xE9, so the option keeps the original source
            if (context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("legacyCan", false)) return null
            val kw = 600.0 - raw * 0.1                  // this signal counts charging as positive, the app counts drawing as positive
            if (seenFirst.add("raw/554")) onStatus("0x554 raw " + b.joinToString("") { String.format("%02X", it and 0xFF) } + " = " + String.format("%.1f", kw) + " kW (drawing positive)")
            val nowN = System.nanoTime()
            val dt = if (lastP2F4Nanos == 0L) 0.0 else (nowN - lastP2F4Nanos) / 1e9
            lastP2F4Nanos = nowN
            if (System.currentTimeMillis() - lastE9Ms < 1500) return null     // 0xE9 is arriving: it does the counting
            val watts = kw * 1000.0 * (if (flipPower) -1 else 1)
            val moving = (s.speedKmh ?: 0.0) > 1.0
            if (moving && watts < 0 && dt in 0.0..0.5) regenWh += -watts * dt / 3600.0
            if (dt in 0.0..0.5 && (moving || (s.ready == true && watts > 0))) tripWh += watts * dt / 3600.0
            return s.copy(packKw = kw, regenKwh = regenWh / 1000.0, tripKwh = tripWh / 1000.0)
        }
        // 0xE9 BMS_ActualPack_U_I (IBUS2, every 20 ms): current signed 16-bit x 0.05 A, voltage x 0.01 V
        if (bus == 2 && id == 0xE9) {
            val rawCur = (b[4] shl 8) or b[5]
            val cur = if (rawCur >= 0x8000) rawCur - 0x10000 else rawCur
            val volts = ((b[6] shl 8) or b[7]) * 0.01
            val nowN = System.nanoTime()
            val dt = if (lastE9Nanos == 0L) 0.0 else (nowN - lastE9Nanos) / 1e9
            lastE9Nanos = nowN
            lastE9Ms = System.currentTimeMillis()
            var watts = volts * cur * 0.05
            if (flipPower) watts = -watts
            val moving = (s.speedKmh ?: 0.0) > 1.0
            if (moving && watts < 0 && dt in 0.0..0.5) regenWh += -watts * dt / 3600.0
            // net energy of the trip: everything while moving, and the draw while stopped but READY (not charging)
            if (dt in 0.0..0.5 && (moving || (s.ready == true && watts > 0))) tripWh += watts * dt / 3600.0
            return s.copy(packKw = volts * cur * 0.05 / 1000.0, packAmps = cur * 0.05, packVolts = volts, regenKwh = regenWh / 1000.0, tripKwh = tripWh / 1000.0)
        }
        // 0x210 ADAS_0x210 (IBUS2, 50 ms): ego-lane traffic light colour (byte 1 low nibble) and light shape (byte 1 bits 6-4);
        // 1 red, 2 amber, 3 green; 4-7 are red/orange variants; 0 = none, 7/8 = unknown
        if (bus == 2 && id == 0x210) {
            val c = b[1] and 0xF
            val sh = (b[1] shr 4) and 7
            fun map(v: Int): Int? = when (v) { 1, 4, 5, 7 -> 1; 2, 6 -> 2; 3 -> 3; else -> null }
            val col = if (c in 1..7) map(c) else map(sh.takeIf { it in 1..3 } ?: 0)
            return s.copy(lightColor = col)
        }
        // 0x311 ADAS_0x311 (IBUS2, 20 ms): ADAS_TSRSpeedLimit (byte 6; 0 and 255 mean none) and ADAS_SpeedLimitUnit (0 km/h, 1 mph)
        if (bus == 2 && id == 0x311) {
            val lim = b[6]
            return s.copy(limit = if (lim in 1..254) lim else null, limitMph = ((b[3] shr 6) and 1) == 1)
        }
        // 0x315 ADAS_0x315 (IBUS2, 50 ms): blind spot and door-open warning display requests, left and right (read only)
        if (bus == 2 && id == 0x315 && b.size >= 6) {
            return s.copy(bsdLeft = b[5] and 7, bsdRight = (b[5] shr 3) and 7, dowLeft = b[2] and 7, dowRight = (b[2] shr 3) and 7, bsdAt = System.currentTimeMillis())
        }
        // 0x352 ADAS_0x352 (IBUS2, 100 ms): ultrasonic parking sensors, 16 sectors of 4 bits (read only)
        if (bus == 2 && id == 0x352 && b.size >= 8) {
            // rear B0-B3: low nibbles of bytes 0-3; front F0-F3: high nibbles of bytes 0-3; left L0-L3: nibbles of bytes 4-5; right R0-R3: nibbles of bytes 6-7
            val rear = (0..3).map { b[it] and 15 }; val front = (0..3).map { (b[it] shr 4) and 15 }
            val left = listOf(b[4] and 15, (b[4] shr 4) and 15, b[5] and 15, (b[5] shr 4) and 15)
            val right = listOf(b[6] and 15, (b[6] shr 4) and 15, b[7] and 15, (b[7] shr 4) and 15)
            return s.copy(uss = rear + front + left + right, ussAt = System.currentTimeMillis())
        }
        // 0x1B8 ESP_0x1B8 (IBUS1, 20 ms): ESP_ActvVehHldFlg, the car is actively held by brake pressure
        if (bus == 1 && id == 0x1B8) {
            return s.copy(holdEsp = (b[2] and 1) == 1)
        }
        // 0x332 ESP_0x332 (IBUS2, 50 ms): ESP_ActvIndcn_AVH, auto vehicle hold active (1 = holding the car)
        if (bus == 2 && id == 0x332) {
            return s.copy(hold = ((b[2] shr 2) and 1) == 1)
        }
        // 0x580 VCU_0x580 (IBUS2, 250 ms): VCU_PwrBattAvlEgy, energy available in the HV battery, 0.01 kWh (65535 = invalid)
        if (bus == 2 && id == 0x580) {
            val raw = (b[6] shl 8) or b[7]
            return s.copy(capKwh = if (raw == 0xFFFF) null else raw * 0.01)
        }
        // 0x582 ECC_0x582 (IBUS2, 100 ms): ECC_InsdT, cabin temperature (255 = invalid)
        if (bus == 2 && id == 0x582) {
            return s.copy(cabinC = if (b[0] == 255) null else b[0] * 0.5 - 48.0)
        }
        // 0x373 ECC_0x373 (100 ms, either bus): the A/C unit's status. Outside temperature plus the whole climate state (read-only)
        if (id == 0x373 && b.size >= 8) {
            fun bit(v: Int, n: Int) = ((v shr n) and 1) == 1
            return s.copy(
                outsideC = if ((b[6] and 3) == 1) b[5] * 0.5 - 48.0 else null,
                climSeen = true,
                climState = b[0] and 15,
                climAc = bit(b[0], 7),
                climAirClean = bit(b[1], 0),
                climFan = (b[1] shr 1) and 15,
                climRearMode = (b[1] shr 5) and 7,
                climDrvT = if (b[2] == 0) null else b[2] * 0.5,
                climPassT = if (b[3] == 0) null else b[3] * 0.5,
                climHeat = bit(b[4], 0),
                climAuto = bit(b[4], 1),
                climSync = bit(b[4], 2),
                climAdvice = (b[4] shr 3) and 3,
                climOuterAir = bit(b[4], 5),
                climMaxDefrost = bit(b[6], 2),
                climFault = bit(b[6], 3),
                climLimited = bit(b[6], 4),
                climDrvMode = (b[6] shr 5) and 7,
                climPassMode = b[7] and 7,
                climRemote = (b[7] shr 3) and 3,
            )
        }
        // 0x386 BCM_0x386 (IBUS1): BCM_HeatedSteerWhlSt, heated steering wheel (byte 0, low 2 bits: 0 off, 1 on)
        if (id == 0x386 && b.isNotEmpty()) {
            return s.copy(wheelHeat = when (b[0] and 3) { 1 -> true; 0 -> false; else -> null })
        }
        // seat heating status: 0x518 DSMC (driver byte 0 bits 0-2, rear left byte 3 bits 0-2, rear right byte 3 bits 3-5), 0x512 PSM (passenger byte 0 bits 2-4)
        // values: 1 High, 2 Med, 3 Low, 4 Off
        fun seatLvl(v: Int): Int? = when (v) { 1 -> 3; 2 -> 2; 3 -> 1; 4 -> 0; else -> null }
        if (id == 0x518 && b.size >= 4) return s.copy(seatDrv = seatLvl(b[0] and 7), seatRL = seatLvl(b[3] and 7), seatRR = seatLvl((b[3] shr 3) and 7))
        if (id == 0x512 && b.isNotEmpty()) return s.copy(seatPass = seatLvl((b[0] shr 2) and 7))
        // 0x313 ADAS_0x313: ADAS_TSRSts, where the speed limit comes from (camera, navigation map or both)
        if (id == 0x313 && b.size >= 7) {
            return s.copy(tsrMode = ((b[6] shr 3) and 7).takeIf { it in 1..5 })
        }
        // 0x6DD BMS_PVIU_ActualPower (IBUS1, 1 s): solar roof power in W, 10 bits starting at byte 0
        if (bus == 1 && id == 0x6DD) {
            return s.copy(solarW = (b[0] shl 2) or (b[1] shr 6))
        }
        // 0x507 VCU_0x507 (IBUS1, 100 ms): VCU_BattVolt, 12V battery voltage, 0.001 V (65535 = invalid)
        if (bus == 1 && id == 0x507) {
            val raw = (b[6] shl 8) or b[7]
            return s.copy(volts12 = if (raw == 0xFFFF) null else raw * 0.001)
        }
        // 0x2AC VCU_IBS_BZE1 (IBUS1, 100 ms): 12V battery sensor, state of charge and health, 0.5 % per bit
        if (bus == 1 && id == 0x2AC) {
            return s.copy(soc12 = b[0] * 0.5, soh12 = b[1] * 0.5)
        }
        // 0x504 VCU_0x504 (IBUS2): VCU_DrvgMilg remaining driving range, 0.1 km per bit; VCU_DrvRngVld 1 = valid
        if (bus == 2 && id == 0x504) {
            val valid = (b[7] and 3) == 1
            return s.copy(rangeKm = if (valid) ((b[5] shl 8) or b[6]) * 0.1 else null)
        }
        // 0x351 ADAS_0x351 (IBUS2): ADAS_TrafficLiDst, metres to the most critical traffic light
        if (bus == 2 && id == 0x351) {
            return s.copy(lightDistM = b[6].takeIf { it in 1..254 })
        }
        // 0x1A9 ECC_0x1A9 (IBUS2, 100 ms): power requested by the climate system's HV parts, 0.01 kW
        if (id == 0x1A9) {
            return s.copy(climateKw = ((b[2] shl 8) or b[3]) * 0.01)
        }
        // 0x5EA VCU_0x5EA (IBUS2): VCU_TripMilg trip distance 0.1 km (bytes 2-3), VCU_TripAvgEgyConsmd average Wh/km (10 bits from byte 4)
        if (id == 0x5EA) {
            val km = (b[2] shl 8) or b[3]
            val wh = (b[4] shl 2) or (b[5] shr 6)
            return s.copy(carTripKm = if (km == 0xFFFF) null else km * 0.1, carTripWhKm = if (wh >= 1023) null else wh)
        }
        // 0x505 VCU_0x505 (IBUS2): VCU_InsntEgyCnse, instantaneous consumption, 0.01 kWh/100 km with offset -100 (bytes 2-3)
        if (id == 0x505) {
            val raw = (b[2] shl 8) or b[3]
            return s.copy(carInstKwh100 = if (raw == 0xFFFF) null else raw * 0.01 - 100.0)
        }
        // 0x491 EAS_0x491 (IBUS2, 100 ms): the A/C (heat pump) compressor. Power 0.2 W per bit
        if (bus == 2 && id == 0x491) {
            return s.copy(compState = (b[2] shr 5) and 3, compWatts = ((b[6] shl 8) or b[7]) * 0.2)
        }
        return null
    }
}

/**
 * A small log of what the app did and how the Pulse was doing, written to the flash with a sync after every line
 * so it survives a hard reset. After a crash, Settings > "Show event log" shows what happened just before.
 */
object EventLog {
    private var file: java.io.File? = null
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    @Synchronized fun init(ctx: Context) {
        val f = java.io.File(ctx.filesDir, "events.log")
        file = f
        try {
            if (f.length() > 200_000) f.writeText(f.readLines().takeLast(800).joinToString("\n") + "\n")
        } catch (e: Exception) { /* ignore */ }
    }

    // writes go through one background thread, so logging never waits on storage (and no forced disk sync per line)
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "eventlog").apply { isDaemon = true } }

    fun add(msg: String) {
        val f = file ?: return
        val line = "${stamp()} $msg\n"
        try { io.execute { write(f, line) } } catch (e: Exception) { /* ignore */ }
    }

    @Synchronized private fun stamp(): String = fmt.format(java.util.Date())

    @Synchronized private fun write(f: java.io.File, line: String) {
        try { java.io.FileOutputStream(f, true).use { o -> o.write(line.toByteArray()) } } catch (e: Exception) { /* ignore */ }
    }

    @Synchronized fun text(maxLines: Int = 250): String = try {
        file?.readLines()?.takeLast(maxLines)?.joinToString("\n") ?: ""
    } catch (e: Exception) { "" }

    /** Load, free memory and the hottest temperature sensor: what a crash from heat or memory would show. */
    fun system(): String {
        fun rd(p: String) = try { java.io.File(p).readText().trim() } catch (e: Exception) { "" }
        val load = rd("/proc/loadavg").split(" ").take(3).joinToString("/")
        val mem = Regex("MemAvailable:\\s+(\\d+)").find(rd("/proc/meminfo"))?.groupValues?.get(1)?.toLongOrNull()?.div(1024)
        var hot = 0
        try {
            java.io.File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach {
                val t = rd(it.path + "/temp").toIntOrNull() ?: 0
                if (t > hot) hot = t
            }
        } catch (e: Exception) { /* ignore */ }
        return "load=$load free=${mem ?: "?"}MB hottest=${if (hot > 1000) hot / 1000 else hot}C"
    }
}


/**
 * Runs commands as root. Android's own su (as on the Pulse) does not accept "su -c cmd" and says
 * "invalid uid/gid '-c'", while Magisk-style su wants exactly that, so a few forms are tried once and the
 * one that gives uid=0 is remembered.
 */
object Root {
    class Result(val ok: Boolean, val out: String, val code: Int, val timedOut: Boolean)

    private val forms = listOf<Pair<String, (String) -> List<String>>>(
        "su -c" to { c -> listOf("su", "-c", c) },
        "su 0 sh -c" to { c -> listOf("su", "0", "sh", "-c", c) },
        "su root sh -c" to { c -> listOf("su", "root", "sh", "-c", c) },
    )
    @Volatile private var form = -1

    fun describe(): String = if (form >= 0) forms[form].first else "none"

    private fun run(args: List<String>, seconds: Long): Result = try {
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        if (!p.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); Result(false, "", -1, true) }
        else Result(true, p.inputStream.bufferedReader().readText(), p.exitValue(), false)
    } catch (e: Exception) { Result(false, e.message ?: "su not found", -1, false) }

    /** ok = a root shell ran the command (its output and exit code are in the result). */
    @Synchronized
    fun exec(cmd: String, seconds: Long = 10): Result {
        if (form < 0) {
            val said = StringBuilder()
            for ((i, f) in forms.withIndex()) {
                val r = run(f.second("id"), 12)
                if (r.timedOut) return r
                if (r.out.contains("uid=0")) { form = i; EventLog.add("root works with: ${f.first}"); break }
                said.append(f.first).append(": ").append(r.out.trim().take(80)).append("; ")
            }
            if (form < 0) return Result(false, said.toString().trim(), -1, false)
        }
        return run(forms[form].second(cmd), seconds)
    }

    /** Null if root works, otherwise a sentence saying what is wrong. */
    fun check(): String? {
        val r = exec("id", 12)
        return when {
            r.timedOut -> "Root request timed out. The root manager may be waiting for you to allow Fisker Battery Buddy. Open it, allow this app, and try again."
            r.ok && r.out.contains("uid=0") -> null
            else -> "Root access was denied or is not available (${r.out.trim().take(200)}). Allow Fisker Battery Buddy in the root manager."
        }
    }
}

/** CAN health: the counters the kernel keeps for the two CAN interfaces, and a fuller root report for the settings screen. */
object CanDiag {
    private fun rd(path: String): String = try { File(path).readText().trim() } catch (e: Exception) { "?" }

    /** One line of receive counters per interface. Readable without root, so it is safe to log often. */
    fun quick(): String {
        val (a, b) = PulseCan.interfaces()
        return listOf(a, b).joinToString(" ") { i ->
            val d = "/sys/class/net/$i/statistics/"
            "$i[rx=${rd(d + "rx_packets")} tx=${rd(d + "tx_packets")} drop=${rd(d + "rx_dropped")} err=${rd(d + "rx_errors")} over=${rd(d + "rx_over_errors")} fifo=${rd(d + "rx_fifo_errors")} ${rd("/sys/class/net/$i/operstate")}]"
        }
    }

    /** The full report: interface state and error counters, the chip filters in force, the Pulse's processes. Needs root. */
    fun full(frames: Long): String {
        val (a, b) = PulseCan.interfaces()
        val p = "/sys/module/mcp251x/parameters"
        val cmd = "for i in $a $b; do echo \"== \$i\"; ip -details -statistics link show \$i 2>&1; done; " +
            "echo; echo '== chip filters (mask0 filter0 mask1 filter1)'; cat $p/rxb_mask_0 $p/rxb_filter_0 $p/rxb_mask_1 $p/rxb_filter_1 2>&1; " +
            "echo; echo '== Pulse processes'; ps -A | grep -i -E 'pulse|oceanlink|ocean' | grep -v grep; " +
            "echo; echo '== CAN totals'; cat /proc/net/can/stats 2>&1 | head -25"
        val r = Root.exec(cmd, 20)
        return "This app has received $frames frames.\nCounters: ${quick()}\n\n" + (if (r.ok) r.out else "no root: ${r.out}")
    }
}

/** Holds the trip counters for a moment while the activity is rebuilt (a layout change), so efficiency does not start again. */
object TripKeep {
    class Data(val regenWh: Double, val tripKm: Double, val tripWh: Double, val moveSecs: Double, val startMs: Long, val savedAt: Long)
    @Volatile private var kept: Data? = null
    fun put(d: Data) { kept = d }
    fun take(): Data? { val d = kept; kept = null; return if (d != null && System.currentTimeMillis() - d.savedAt < 60_000L) d else null }
}
