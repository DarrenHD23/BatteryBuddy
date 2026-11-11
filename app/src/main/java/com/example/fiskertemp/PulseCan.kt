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
    val packVolts: Double? = null,     // V (0xE9 on IBUS2, bytes 6-7)  needs the chip filters opened
    val packAmps: Double? = null,      // A (0xE9 on IBUS2, bytes 4-5, signed)
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
                val args = listOf(bin, "$b1=382,2F5", "$b2=0E9")
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
        if (held.put(key, hex) != hex) lastChangeAt = System.currentTimeMillis()

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
        // 0x2F5 BMS_SOC (IBUS1, every 100 ms)
        if (bus == 1 && id == 0x2F5) {
            return s.copy(
                socUsable = b[0].takeIf { it <= 100 },
                sohPct = b[5],
                socReal = b[6].takeIf { it <= 100 },
            )
        }
        // 0xE9 BMS_ActualPack_U_I (IBUS2, every 20 ms): current signed 16-bit x 0.05 A, voltage x 0.01 V
        if (bus == 2 && id == 0xE9) {
            val rawCur = (b[4] shl 8) or b[5]
            val cur = if (rawCur >= 0x8000) rawCur - 0x10000 else rawCur
            val volts = ((b[6] shl 8) or b[7]) * 0.01
            return s.copy(packAmps = cur * 0.05, packVolts = volts)
        }
        return null
    }
}
