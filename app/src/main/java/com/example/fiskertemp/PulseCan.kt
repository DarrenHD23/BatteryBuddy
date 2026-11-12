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
    val sohPct: Int? = null,           // % (0x2F5, byte 5)
    val packCapKwh: Double? = null,    // full pack capacity the BMS reports, kWh (0x2F5, bytes 3-4, 0.01)
    val packVolts: Double? = null,     // V (0xE9 on IBUS2, bytes 6-7)  needs the chip filters opened
    val packAmps: Double? = null,      // A (0xE9 on IBUS2, bytes 4-5, signed)
    val limit: Int? = null,            // road speed limit the car knows, in limitMph units (0x311 on IBUS2, byte 6); null = none
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
)

class PulseCan(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onUpdate: (CanSnapshot) -> Unit,
) {
    @Volatile private var running = false
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

    /** Start counting regenerated energy again from zero. */
    fun resetDrive() { regenWh = 0.0; snap = snap.copy(regenKwh = 0.0) }

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

    /**
     * The Pulse's CAN chips only let through the IDs their acceptance filters allow, and the defaults
     * block 0x491 (compressor). This programs exact filters for just the IDs this app reads
     * (chip 0 = IBUS1, chip 1 = IBUS2), the same driver parameters the sunnypilot HwFilters code uses.
     * It only changes what is RECEIVED; nothing is sent. Needs root. Returns null on success, else why not.
     */
    private fun openFilters(): String? {
        val dir = File("/sys/module/mcp251x/parameters")
        if (!dir.isDirectory) return "no mcp251x driver parameters on this device"
        // Each chip has two receive buffers: buffer 0 (2 filters, one mask) and buffer 1 (4 filters, one mask).
        // The six filters are given buffer 0 first. Masks let one filter cover a block of IDs; the helper's own
        // socket filters then keep only the exact IDs this app wants.
        val plan = listOf(
            // IBUS1: 0x507 and 0x6DD exactly; 0x100-0x3FF (0x179 0x234 0x2AC 0x2F5 0x318 0x335 0x373 0x382)
            Triple(0, "0x7FF,0x700", "0x507,0x6DD,0x100,0x200,0x300,0x300"),
            // IBUS2: 0xE9 and 0x1A9 exactly (buffer 0, as before: these carry the power readings);
            // buffer 1 takes four 128-ID blocks: 0x200 (0x210), 0x300 (0x311 0x332 0x351), 0x500 (0x504), 0x580 (0x580 0x582)
            Triple(1, "0x7FF,0x780", "0x0E9,0x1A9,0x200,0x300,0x500,0x580"),
        )
        fun dec(csv: String) = csv.split(',').joinToString(",") { Integer.decode(it.trim()).toString() }
        val sb = StringBuilder()
        for ((chip, mask, filt) in plan) {
            sb.append("echo '${dec(mask)}' > ${dir.path}/rxb_mask_$chip; ")
            sb.append("echo '${dec(filt)}' > ${dir.path}/rxb_filter_$chip; ")
        }
        sb.append("echo 1 > ${dir.path}/apply_filters")
        return try {
            val p = ProcessBuilder("su", "-c", sb.toString()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return "root shell timed out" }
            if (out.contains("denied", true) || out.contains("not found", true) || p.exitValue() != 0) "root command failed: ${out.trim()}" else null
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
        thread(name = "pulse-can", isDaemon = true) { loop(bin.path) }
    }

    @Synchronized
    fun stop() {
        running = false
        proc?.destroy()
        proc = null
    }

    private fun loop(bin: String) {
        var tries = 0
        while (running) {
            val (b1, b2) = interfaces()
            val startedAt = System.currentTimeMillis()
            try {
                // receive only: no "tx:" arguments
                val why = openFilters()
                onStatus(if (why == null) "CAN filters opened for climate frames" else "CAN filters not changed: $why")
                val args = listOf(bin, "$b1=382,2F5,318,179,234,335,2AC,373,507,6DD,1B8", "$b2=0E9,1A9,210,351,504,580,582,332,311")
                onStatus("Starting CAN reader: IBUS1 = $b1, IBUS2 = $b2")
                val p = ProcessBuilder(args).redirectErrorStream(false).start()
                proc = p
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
                // the helper ends when its stdin closes, so we keep it open (we never write to it)
                p.inputStream.bufferedReader().forEachLine { handle(it, b1, b2) }
                val code = p.waitFor()
                onStatus("CAN reader stopped (code $code)")
            } catch (e: Exception) {
                onStatus("CAN reader failed: ${e.message}")
            }
            proc?.destroy()
            proc = null
            if (System.currentTimeMillis() - startedAt > 30_000) tries = 0
            if (running) {
                try { Thread.sleep(minOf(30_000L, 1000L shl minOf(tries++, 5))) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun handle(line: String, b1: String, b2: String) {
        if (line.startsWith("#")) {
            if (line.contains("ready")) onStatus("CAN reader ready")
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

        val key = "$bus/$id"
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
            return s.copy(brakeBcm = ((b[5] shr 7) and 1) == 1, headlights = lamps)
        }
        // 0x179 TBOX_0x179 (IBUS1, every 100 ms): vehicle heading, 0.01 degrees per bit
        if (bus == 1 && id == 0x179) {
            val deg = ((b[2] shl 8) or b[3]) * 0.01
            return s.copy(headingDeg = if (deg <= 359.99) deg else null)
        }
        // 0x318 ESP_0x318 (IBUS1, every 20 ms): vehicle speed, 0.1 km/h per bit; ESP_VehSpdVld 2 = fault
        if (bus == 1 && id == 0x318) {
            val bad = (b[2] and 3) == 2
            return s.copy(speedKmh = if (bad) null else ((b[5] shl 8) or b[6]) * 0.1)
        }
        // 0x2F5 BMS_SOC (IBUS1, every 100 ms)
        if (bus == 1 && id == 0x2F5) {
            return s.copy(
                socUsable = b[0].takeIf { it <= 100 },
                sohPct = b[5],
                packCapKwh = ((b[3] shl 8) or b[4]).let { if (it == 0 || it == 0xFFFF) null else it * 0.01 },
                socReal = b[6].takeIf { it <= 100 },
            )
        }
        // 0xE9 BMS_ActualPack_U_I (IBUS2, every 20 ms): current signed 16-bit x 0.05 A, voltage x 0.01 V
        if (bus == 2 && id == 0xE9) {
            val rawCur = (b[4] shl 8) or b[5]
            val cur = if (rawCur >= 0x8000) rawCur - 0x10000 else rawCur
            val volts = ((b[6] shl 8) or b[7]) * 0.01
            val nowN = System.nanoTime()
            val dt = if (lastE9Nanos == 0L) 0.0 else (nowN - lastE9Nanos) / 1e9
            lastE9Nanos = nowN
            var watts = volts * cur * 0.05
            if (flipPower) watts = -watts
            val moving = (s.speedKmh ?: 0.0) > 1.0
            if (moving && watts < 0 && dt in 0.0..0.5) regenWh += -watts * dt / 3600.0
            return s.copy(packAmps = cur * 0.05, packVolts = volts, regenKwh = regenWh / 1000.0)
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
        // 0x373 ECC_0x373 (IBUS1, 100 ms): ECC_OutdT outside temperature, valid when ECC_OutdTVld (byte 6, low 2 bits) = 1
        if (bus == 1 && id == 0x373) {
            return s.copy(outsideC = if ((b[6] and 3) == 1) b[5] * 0.5 - 48.0 else null)
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
        if (bus == 2 && id == 0x1A9) {
            return s.copy(climateKw = ((b[2] shl 8) or b[3]) * 0.01)
        }
        // 0x491 EAS_0x491 (IBUS2, 100 ms): the A/C (heat pump) compressor. Power 0.2 W per bit
        if (bus == 2 && id == 0x491) {
            return s.copy(compState = (b[2] shr 5) and 3, compWatts = ((b[6] shl 8) or b[7]) * 0.2)
        }
        return null
    }
}
