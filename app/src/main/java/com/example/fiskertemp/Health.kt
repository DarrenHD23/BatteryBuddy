package com.example.fiskertemp

import java.io.File

/** Reads the head unit's own health numbers (temperatures, CPU, memory, fan, storage) from Linux. One shell call as root; plain file reads without root. */
object Health {
    class Snap(
        val board: String, val temps: List<Pair<String, Float>>, val cores: List<Triple<String, Int, Int>>, val loadPct: Float?,
        val memTotalKb: Long, val memAvailKb: Long, val swapTotalKb: Long, val swapFreeKb: Long,
        val fans: List<String>, val gpu: String?, val storageUsed: Long, val storageTotal: Long, val uptimeSec: Long, val rootUsed: Boolean,
    )

    private const val SCRIPT = """
echo "BOARD $(cat /proc/device-tree/model 2>/dev/null | tr -d '\000') / $(getprop ro.product.model 2>/dev/null)"
for z in /sys/class/thermal/thermal_zone*; do echo "T $(cat ${'$'}z/type 2>/dev/null) $(cat ${'$'}z/temp 2>/dev/null)"; done
for c in /sys/devices/system/cpu/cpu[0-9]*; do echo "F $(basename ${'$'}c) $(cat ${'$'}c/cpufreq/scaling_cur_freq 2>/dev/null) $(cat ${'$'}c/cpufreq/cpuinfo_max_freq 2>/dev/null)"; done
echo "S $(head -1 /proc/stat)"
echo "M $(grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo | tr -s ' ' | tr '\n' ' ')"
echo "U $(cut -d' ' -f1 /proc/uptime)"
for h in /sys/class/hwmon/hwmon*; do n=$(cat ${'$'}h/name 2>/dev/null); for f in ${'$'}h/fan*_input; do [ -f ${'$'}f ] && echo "FAN ${'$'}n $(cat ${'$'}f 2>/dev/null) rpm"; done; [ -f ${'$'}h/pwm1 ] && echo "FAN ${'$'}n pwm $(cat ${'$'}h/pwm1 2>/dev/null)/255"; done
for d in /sys/class/thermal/cooling_device*; do t=$(cat ${'$'}d/type 2>/dev/null); case "${'$'}t" in *fan*) echo "FAN ${'$'}t step $(cat ${'$'}d/cur_state 2>/dev/null)/$(cat ${'$'}d/max_state 2>/dev/null)";; esac; done
for g in /sys/class/devfreq/*gpu*; do [ -d ${'$'}g ] && echo "G $(cat ${'$'}g/cur_freq 2>/dev/null) $(cat ${'$'}g/max_freq 2>/dev/null) $(cat ${'$'}g/load 2>/dev/null)"; done
echo "D $(df -k /data 2>/dev/null | tail -1 | tr -s ' ')"
"""

    private var lastStat: LongArray? = null

    private fun direct(): String {
        val sb = StringBuilder()
        fun rd(p: String) = try { File(p).readText().trim() } catch (e: Exception) { "" }
        sb.append("BOARD ").append(rd("/proc/device-tree/model").replace("\u0000", "")).append('\n')
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.sortedBy { it.name }?.forEach { sb.append("T ${rd(it.path + "/type")} ${rd(it.path + "/temp")}\n") }
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }?.sortedBy { it.name.drop(3).toInt() }?.forEach {
            sb.append("F ${it.name} ${rd(it.path + "/cpufreq/scaling_cur_freq")} ${rd(it.path + "/cpufreq/cpuinfo_max_freq")}\n") }
        sb.append("S ").append(rd("/proc/stat").lines().firstOrNull() ?: "").append('\n')
        val mi = rd("/proc/meminfo").lines().filter { Regex("^(MemTotal|MemAvailable|SwapTotal|SwapFree):").containsMatchIn(it) }.joinToString(" ") { it.replace(Regex("\\s+"), " ") }
        sb.append("M ").append(mi).append('\n')
        sb.append("U ").append(rd("/proc/uptime").substringBefore(' ')).append('\n')
        return sb.toString()
    }

    fun read(): Snap {
        var text = ""; var root = false
        try { if (Updater.rootAvailable()) { val r = Root.exec(SCRIPT, 12); if (r.ok && r.out.isNotBlank()) { text = r.out; root = true } } } catch (e: Throwable) {}
        if (text.isBlank()) text = direct()
        var board = ""; val temps = ArrayList<Pair<String, Float>>(); val cores = ArrayList<Triple<String, Int, Int>>(); var load: Float? = null
        var mt = 0L; var ma = 0L; var st = 0L; var sf = 0L; val fans = ArrayList<String>(); var gpu: String? = null; var du = 0L; var dt = 0L; var up = 0L
        for (line in text.lines()) {
            val t = line.trim(); if (t.isEmpty()) continue
            val w = t.split(' ').filter { it.isNotEmpty() }
            try {
                when (w[0]) {
                    "BOARD" -> board = t.removePrefix("BOARD").trim().trim('/', ' ')
                    "T" -> if (w.size >= 3) { val v = w.last().toFloat(); temps.add(Pair(w[1], if (v > 1000f) v / 1000f else v)) }
                    "F" -> if (w.size >= 4) cores.add(Triple(w[1], w[2].toInt() / 1000, w[3].toInt() / 1000))
                    "S" -> if (w.size >= 9) {
                        val n = LongArray(8) { w[2 + it].toLong() }      // user nice system idle iowait irq softirq steal
                        val idle = n[3] + n[4]; val total = n.sum()
                        val prev = lastStat
                        if (prev != null && total > prev[1]) load = (100f * (1f - (idle - prev[0]).toFloat() / (total - prev[1]).toFloat())).coerceIn(0f, 100f)
                        lastStat = longArrayOf(idle, total)
                    }
                    "M" -> { var i = 1; while (i + 1 < w.size) { val k = w[i]; val v = w[i + 1].toLongOrNull() ?: 0L
                        when (k) { "MemTotal:" -> mt = v; "MemAvailable:" -> ma = v; "SwapTotal:" -> st = v; "SwapFree:" -> sf = v }; i += 3 } }
                    "U" -> up = w[1].substringBefore('.').toLong()
                    "FAN" -> fans.add(t.removePrefix("FAN").trim())
                    "G" -> if (w.size >= 3) gpu = "${w[1].toLong() / 1000000} MHz" + (if (w.size >= 4) " (${w[3].substringBefore('@')})" else "") + " of ${w[2].toLong() / 1000000}"
                    "D" -> { val p = w.drop(1); if (p.size >= 4) { dt = p[1].toLongOrNull() ?: 0L; du = p[2].toLongOrNull() ?: 0L } }
                }
            } catch (e: Exception) {}
        }
        return Snap(board, temps, cores, load, mt, ma, st, sf, fans, gpu, du, dt, up, root)
    }
}
