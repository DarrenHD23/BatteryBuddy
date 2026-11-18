package com.example.fiskertemp

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.audiofx.Visualizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Listens to the audio that is playing (Android's output-mix visualizer) and keeps 32 smoothed frequency bands, 0..1. */
object AudioViz {
    const val N = 32
    @Volatile var bands = FloatArray(N)
    @Volatile var lastSignalMs = 0L
    private var viz: Visualizer? = null
    private var failedAt = 0L
    @Volatile var status = "not started"

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
            if (fft == null) return
            val n = fft.size
            val bins = n / 2
            val out = FloatArray(N)
            var peak = 0f
            for (i in 0 until N) {
                val a = max(1, Math.round(1.0 * (bins * 0.55).pow(i.toDouble() / N)).toInt())
                val b = max(a + 1, Math.round(1.0 * (bins * 0.55).pow((i + 1).toDouble() / N)).toInt())
                var m = 0f
                for (k in a until min(b, bins)) {
                    val re = fft[2 * k].toInt().toFloat(); val im = fft[2 * k + 1].toInt().toFloat()
                    m = max(m, hypot(re, im))
                }
                val x = (ln(1f + m) / ln(1f + 70f)) * (0.75f + 0.75f * i / N)
                out[i] = x.coerceIn(0f, 1f)
                peak = max(peak, out[i])
            }
            bands = out
            if (peak > 0.04f) lastSignalMs = System.currentTimeMillis()
        }
    }

    /** True when the capture is running. Tries again every 30 s if it could not start (no permission, or the device refuses). */
    @Synchronized
    fun start(ctx: Context): Boolean {
        if (viz != null) return true
        val now = System.currentTimeMillis()
        if (now - failedAt < 30000) return false
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { failedAt = now; status = "no audio permission"; return false }
        return try {
            val v = Visualizer(0)
            v.enabled = false
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = min(range[1], 1024).coerceAtLeast(range[0])
            v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            viz = v; status = "listening"; true
        } catch (e: Throwable) { failedAt = now; status = "unavailable: " + (e.message ?: e.javaClass.simpleName); false }
    }

    @Synchronized
    fun stop() {
        try { viz?.enabled = false; viz?.release() } catch (e: Throwable) {}
        viz = null
    }

    fun hasSignal(): Boolean = System.currentTimeMillis() - lastSignalMs < 2500
}

object VizInfo {
    val styles = listOf("Spectrum bars", "Mirror", "Glass tubes", "LED blocks", "Mountain", "Oscilloscope", "Radial burst", "Pulse rings", "Moving circles",
        "Fine analyzer", "Classic (peak drop)", "Bars + reflection", "Dot matrix", "Waterfall", "Stereo L / R", "VU meters", "Ring spectrum", "Particles")
    val palettes = listOf("Skin colour", "Rainbow", "Fire", "Ocean", "Neon", "Sunset", "Matrix", "Classic green / red")
    val pal: List<IntArray?> = listOf(
        null,
        intArrayOf(0xFFFF3B6B.toInt(), 0xFFFFB02E.toInt(), 0xFFF5E642.toInt(), 0xFF37D67A.toInt(), 0xFF2AA8FF.toInt(), 0xFFB06CFF.toInt()),
        intArrayOf(0xFF7A0A00.toInt(), 0xFFFF3B00.toInt(), 0xFFFF9D00.toInt(), 0xFFFFE36A.toInt()),
        intArrayOf(0xFF0A3D91.toInt(), 0xFF1E88FF.toInt(), 0xFF25D0E0.toInt(), 0xFFB8FFF0.toInt()),
        intArrayOf(0xFFFF2BD6.toInt(), 0xFF7A3BFF.toInt(), 0xFF18E0FF.toInt()),
        intArrayOf(0xFF5B2A86.toInt(), 0xFFE8457A.toInt(), 0xFFFF9A4A.toInt(), 0xFFFFD36B.toInt()),
        intArrayOf(0xFF003B12.toInt(), 0xFF00C43A.toInt(), 0xFFB6FFB0.toInt()),
        intArrayOf(0xFF13B83A.toInt(), 0xFFA6E22E.toInt(), 0xFFF2E11C.toInt(), 0xFFFF9A1F.toInt(), 0xFFE8321E.toInt()),
    )
}

/** Draws the 18 visualizer styles into a rectangle, from 32 smoothed bands (the real audio, or a simulated beat). */
class VizPainter(private val d: Float) {
    private val N = AudioViz.N
    private val lv = FloatArray(N); private val pk = FloatArray(N); private val pv = FloatArray(N)
    private val hist = ArrayList<FloatArray>()
    private val rings = ArrayList<FloatArray>()                 // radius, alpha
    private val parts = ArrayList<FloatArray>()                 // x, y, vy, size, colour position
    private var needleL = 0f; private var needleR = 0f
    private var bass = 0f; private var mid = 0f; private var tre = 0f; private var hit = 0f
    private var fr = 0; private var lastBeat = -1L; private var lastBeatMs = 0L; private var bassAvg = 0f
    private var tSec = 0f; private var lastMs = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var accent = Color.parseColor("#2AA8FF")
    private var palette: IntArray? = null
    private val rnd = java.util.Random(7)

    /** Advances the animation; true while anything is still moving. [bands] is null to simulate the beat. */
    fun step(now: Long, playing: Boolean, bands: FloatArray?): Boolean {
        if (lastMs == 0L) lastMs = now
        tSec += (now - lastMs) / 1000f; lastMs = now
        val t = tSec
        val ph = t * 118f / 60f
        val synthHit = (1f - (ph % 1f)).pow(3); val off = (1f - ((ph + 0.5f) % 1f)).pow(4)
        for (i in 0 until N) {
            val f = i.toFloat() / N; val b = exp(-f * 3.2f)
            val tg = if (!playing) 0f else if (bands != null) bands[i] else
                min(1f, (0.18f + 0.75f * synthHit) * b + 0.35f * off * (1 - b) * (0.5f + 0.5f * sin(t * 3 + i * 1.7f)) + 0.12f * abs(sin(t * 5.1f + i * 0.9f)) * (1 - f * 0.5f))
            lv[i] += (tg - lv[i]) * (if (tg > lv[i]) 0.6f else 0.14f)
            if (lv[i] >= pk[i]) { pk[i] = lv[i]; pv[i] = 0f } else { pv[i] += 0.0009f; pk[i] = max(lv[i], pk[i] - pv[i]) }
        }
        var b = 0f; var m = 0f; var h = 0f
        for (i in 0 until N) { if (i < 6) b += lv[i] / 6 else if (i < 16) m += lv[i] / 10 else h += lv[i] / 16 }
        bass = b; mid = m; tre = h
        var beatNow = false
        if (bands == null) {
            hit = if (playing) synthHit else 0f
            val bi = ph.toLong(); if (playing && bi != lastBeat) { lastBeat = bi; beatNow = true }
        } else {
            hit *= 0.88f; bassAvg += (b - bassAvg) * 0.05f
            if (playing && b > 0.22f && b > bassAvg * 1.3f && now - lastBeatMs > 260) { lastBeatMs = now; hit = 1f; beatNow = true }
        }
        if (beatNow) rings.add(floatArrayOf(0f, 1f))
        for (r in rings) { r[0] += 0.012f; r[1] -= 0.014f }
        rings.removeAll { it[1] <= 0f }
        fr++
        if (fr % 3 == 0) { hist.add(0, lv.copyOf()); if (hist.size > 30) hist.removeAt(hist.size - 1) }
        needleL += ((if (playing) min(1f, b * 1.25f + 0.12f * abs(sin(t * 3.1f))) else 0f) - needleL) * (if (playing) 0.35f else 0.08f)
        needleR += ((if (playing) min(1f, b * 1.1f + 0.14f * abs(sin(t * 2.3f + 1f))) else 0f) - needleR) * (if (playing) 0.3f else 0.08f)
        if (playing && fr % 2 == 0 && parts.size < 70) parts.add(floatArrayOf(rnd.nextFloat(), 1f, 0.004f + rnd.nextFloat() * 0.01f + bass * 0.012f, 1.5f + rnd.nextFloat() * 3f, rnd.nextFloat()))
        for (q in parts) { q[1] -= q[2] * (if (playing) 1f else 0.2f); q[0] += sin(q[1] * 9 + q[4] * 6) * 0.0015f }
        parts.removeAll { it[1] < -0.05f }
        var mx = 0f; for (v in lv) mx = max(mx, v)
        return playing || mx > 0.01f || rings.isNotEmpty() || parts.isNotEmpty() || needleL > 0.01f
    }

    private fun lerp(a: Int, b: Int, f: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())

    private fun col(u0: Float): Int {
        val u = u0.coerceIn(0f, 1f); val pl = palette ?: return accent
        val x = u * (pl.size - 1); val i = min(pl.size - 2, x.toInt())
        return lerp(pl[i], pl[i + 1], x - i)
    }

    private fun fillC(c: Int, a: Float = 1f) { p.style = Paint.Style.FILL; p.shader = null; p.color = c; p.alpha = (255 * a).toInt().coerceIn(0, 255) }
    private fun strokeC(c: Int, w: Float, a: Float = 1f) { p.style = Paint.Style.STROKE; p.shader = null; p.color = c; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.alpha = (255 * a).toInt().coerceIn(0, 255) }
    private fun hGrad(w: Float): android.graphics.Shader {
        val cs = IntArray(7) { col(it / 6f) }
        return android.graphics.LinearGradient(0f, 0f, w, 0f, cs, null, android.graphics.Shader.TileMode.CLAMP)
    }

    fun draw(c: Canvas, r: RectF, style: Int, palIdx: Int, accentColor: Int) {
        accent = accentColor; palette = VizInfo.pal.getOrNull(palIdx)
        c.save(); c.translate(r.left, r.top)
        val W = r.width(); val H = r.height()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, W, H), 12f * d, 12f * d, Path.Direction.CW) }
        c.clipPath(clip)
        // a dark glass floor for the visualizer
        fillC(Color.BLACK, 0.30f); c.drawRect(0f, 0f, W, H, p)
        val pad = 8f * d; val cap = Color.WHITE
        val iw = W - pad * 2; val ih = H - pad * 2
        when (style) {
            0 -> for (i in 0 until N) {
                val bw = iw / N; val x = pad + i * bw; val h = lv[i] * ih
                fillC(col(i.toFloat() / N)); c.drawRect(x, H - pad - h.coerceAtLeast(2f), x + bw - bw * 0.22f, H - pad, p)
                fillC(cap); c.drawRect(x, H - pad - pk[i] * ih - 3f * d, x + bw - bw * 0.22f, H - pad - pk[i] * ih - 1f * d, p)
            }
            1 -> for (i in 0 until N) {
                val bw = iw / N; val x = pad + i * bw; val h = lv[i] * H * 0.46f; val w = bw - bw * 0.22f
                fillC(col(i.toFloat() / N)); c.drawRect(x, H / 2 - h, x + w, H / 2, p)
                fillC(col(i.toFloat() / N), 0.4f); c.drawRect(x, H / 2, x + w, H / 2 + h, p)
                fillC(cap); c.drawRect(x, H / 2 - pk[i] * H * 0.46f - 3f * d, x + w, H / 2 - pk[i] * H * 0.46f - 1f * d, p)
            }
            2 -> { val n = 24; val bw = iw / n; val w = bw * 0.78f
                for (i in 0 until n) {
                    val x = pad + i * bw; val h = max(w, lv[i * N / n] * ih); val cc = col(i.toFloat() / n)
                    fillC(Color.WHITE, 0.07f); c.drawRoundRect(RectF(x, pad, x + w, H - pad), w / 2, w / 2, p)
                    fillC(cc); c.drawRoundRect(RectF(x, H - pad - h, x + w, H - pad), w / 2, w / 2, p)
                    fillC(Color.WHITE, 0.45f); c.drawRoundRect(RectF(x + w * 0.2f, H - pad - h + w * 0.25f, x + w * 0.4f, H - pad - w * 0.25f), w * 0.1f, w * 0.1f, p)
                } }
            3 -> { val n = 24; val rows = 8; val bw = iw / n; val w = bw * 0.78f; val rh = ih / rows
                for (i in 0 until n) { val on = Math.round(lv[i * N / n] * rows)
                    for (k in 0 until rows) {
                        if (k < on) fillC(col(k.toFloat() / (rows - 1))) else fillC(Color.WHITE, 0.06f)
                        c.drawRoundRect(RectF(pad + i * bw, H - pad - (k + 1) * rh + 1.5f * d, pad + i * bw + w, H - pad - k * rh - 1.5f * d), 2f * d, 2f * d, p)
                    } } }
            4 -> { path.reset(); path.moveTo(pad, H - pad)
                for (i in 0 until N) path.lineTo(pad + (i + 0.5f) * iw / N, H - pad - (lv[i] * 0.92f + 0.02f) * ih)
                path.lineTo(W - pad, H - pad); path.close()
                p.style = Paint.Style.FILL; p.shader = hGrad(W); p.alpha = 140; c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.alpha = 255; c.drawPath(path, p); p.shader = null }
            5 -> { path.reset(); var x = 0f; val a = if (hit > 0f || bass > 0.02f) (0.15f + 0.85f * hit.coerceAtLeast(bass)) * 0.38f else 0.02f
                while (x <= iw) { val u = x / iw; val y = H / 2 + sin(u * 14 + tSec * 9) * H * a * sin(u * PI.toFloat()) + sin(u * 37 + tSec * 21) * H * a * 0.25f * sin(u * PI.toFloat())
                    if (x == 0f) path.moveTo(pad + x, y) else path.lineTo(pad + x, y); x += 3f * d }
                p.style = Paint.Style.STROKE; p.shader = hGrad(W); p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 7f * d; p.alpha = 50; c.drawPath(path, p)
                p.strokeWidth = 2.5f * d; p.alpha = 255; c.drawPath(path, p); p.shader = null }
            6 -> { val cx = W / 2; val cy = H / 2; val r0 = min(W, H) * 0.2f; val r1 = min(W, H) * 0.46f; val M = 48
                for (i in 0 until M) { val ang = i.toFloat() / M * PI.toFloat() * 2 + tSec * 0.25f; val v = lv[if (i < M / 2) i % N else (M - 1 - i) % N]; val r2 = r0 + v * (r1 - r0) + 2f
                    strokeC(col(i.toFloat() / M), max(2f, min(W, H) * 0.035f)); c.drawLine(cx + cos(ang) * r0, cy + sin(ang) * r0, cx + cos(ang) * r2, cy + sin(ang) * r2, p) }
                fillC(col(0.5f), 0.25f + 0.5f * bass); c.drawCircle(cx, cy, r0 * 0.7f * (1 + 0.25f * bass), p) }
            7 -> { val cx = W / 2; val cy = H / 2; val mx = max(W, H) * 0.55f
                for (rg in rings) { strokeC(col(1f - rg[1]), 2.5f * d, max(0f, rg[1]) * 0.9f); c.drawCircle(cx, cy, rg[0] * mx, p) }
                fillC(col(0.3f)); c.drawCircle(cx, cy, min(W, H) * (0.09f + 0.07f * bass), p) }
            8 -> { val K = 7
                for (i in 0 until K) { val v2 = if (i < 3) bass else if (i < 5) mid else tre
                    val px = W * (0.1f + 0.8f * ((i + 0.5f) / K)) + sin(tSec * 0.9f + i * 1.3f) * W * 0.04f; val py = H * (0.5f + 0.22f * sin(tSec * 1.3f + i * 2.1f))
                    val rad = min(W, H) * (0.08f + 0.26f * v2 * (1 - i * 0.05f))
                    fillC(col(i.toFloat() / (K - 1)), 0.8f); c.drawCircle(px, py, rad, p)
                    fillC(Color.WHITE, 0.35f); c.drawCircle(px - rad * 0.3f, py - rad * 0.3f, rad * 0.3f, p) } }
            9 -> { val D = 64; val dw = iw / D
                for (i in 0 until D) { val vv = lv[min(N - 1, i * N / D)] * (0.85f + 0.15f * sin(i * 1.9f + tSec * 7)); val hh = max(1.5f * d, vv * ih)
                    fillC(col(i.toFloat() / D)); c.drawRect(pad + i * dw, H - pad - hh, pad + i * dw + max(1f, dw - d), H - pad, p) } }
            10 -> { val cw = iw / N; val rows = 14; val rh = ih / rows
                for (i in 0 until N) { val on = Math.round(lv[i] * rows)
                    for (k in 0 until on) { fillC(col(k.toFloat() / (rows - 1)), if (palette == null) 0.55f + 0.45f * k / rows else 1f); c.drawRect(pad + i * cw, H - pad - (k + 1) * rh + d, pad + i * cw + cw - 2f * d, H - pad - k * rh - d, p) }
                    val pp = min(rows, Math.round(pk[i] * rows)); if (pp > 0) { fillC(cap); c.drawRect(pad + i * cw, H - pad - pp * rh - 1f * d, pad + i * cw + cw - 2f * d, H - pad - pp * rh + 1f * d, p) } } }
            11 -> { val base = H * 0.64f; val bw = iw / N
                for (i in 0 until N) { val h = lv[i] * (base - pad); val cc = col(i.toFloat() / N)
                    fillC(cc); c.drawRect(pad + i * bw, base - max(h, 2f), pad + i * bw + bw - 2.5f * d, base, p)
                    fillC(cc, 0.32f); c.drawRect(pad + i * bw, base + 2f * d, pad + i * bw + bw - 2.5f * d, base + 2f * d + min(h * 0.55f, H - base - pad), p) }
                fillC(Color.WHITE, 0.25f); c.drawRect(pad, base, W - pad, base + d, p) }
            12 -> { val cols = 32; val rows = 7; val cw = iw / cols; val rh = ih / rows; val rd = min(cw, rh) * 0.36f
                for (i in 0 until cols) { val on = Math.round(lv[i * N / cols] * rows)
                    for (k in 0 until rows) { if (k < on) fillC(col(k.toFloat() / (rows - 1))) else fillC(Color.WHITE, 0.07f); c.drawCircle(pad + (i + 0.5f) * cw, H - pad - (k + 0.5f) * rh, rd, p) } } }
            13 -> { val cw = iw / N; val rh = ih / 30
                for (j in 0 until hist.size) for (i in 0 until N) { val q = hist[j][i]; if (q < 0.03f) continue
                    fillC(col(q), min(1f, q * 1.1f) * (1f - j / 36f)); c.drawRect(pad + i * cw, pad + j * rh, pad + i * cw + cw - 0.5f, pad + j * rh + rh - 0.5f, p) } }
            14 -> { val hh = ih / 2 - 3f * d; val segs = 26; val sw = iw / segs
                for (ch in 0..1) { val lvl = if (ch == 1) needleR else needleL; val yy = pad + ch * (hh + 6f * d)
                    for (i in 0 until segs) { val lt = i.toFloat() / segs < lvl * (0.8f + 0.2f * abs(sin(tSec * 8 + ch * 2)))
                        if (lt) fillC(col(i.toFloat() / segs)) else fillC(Color.WHITE, 0.07f)
                        c.drawRoundRect(RectF(pad + i * sw, yy, pad + i * sw + sw - 2.5f * d, yy + hh), 3f * d, 3f * d, p) }
                    fillC(cap, 0.7f); p.textSize = 10f * d; p.textAlign = Paint.Align.LEFT; c.drawText(if (ch == 1) "R" else "L", 2f * d, yy + hh / 2 + 3.5f * d, p) } }
            15 -> for (m2 in 0..1) { val mw = (W - pad * 3) / 2; val mx2 = pad + m2 * (mw + pad)
                fillC(Color.parseColor("#FFF0C8"), 0.14f); c.drawRoundRect(RectF(mx2, pad, mx2 + mw, H - pad), 10f * d, 10f * d, p)
                val pxv = mx2 + mw / 2; val pyv = pad + (H - pad * 2) * 0.98f; val Rr = min(mw * 0.46f, (H - pad * 2) * 0.85f)
                for (tk in 0..10) { val aa = PI.toFloat() * (1.22f + 0.56f * tk / 10); val big = tk % 5 == 0
                    strokeC(if (tk > 7) Color.parseColor("#FF4A3A") else Color.WHITE, 1.5f * d, 0.7f)
                    c.drawLine(pxv + cos(aa) * Rr * 0.84f, pyv + sin(aa) * Rr * 0.84f, pxv + cos(aa) * Rr * (if (big) 1f else 0.92f), pyv + sin(aa) * Rr * (if (big) 1f else 0.92f), p) }
                val nv = if (m2 == 1) needleR else needleL; val na = PI.toFloat() * (1.22f + 0.56f * nv)
                strokeC(col(0.9f), 2.2f * d); c.drawLine(pxv, pyv, pxv + cos(na) * Rr * 0.95f, pyv + sin(na) * Rr * 0.95f, p)
                fillC(cap, 0.7f); p.textSize = 10f * d; p.textAlign = Paint.Align.CENTER; c.drawText(if (m2 == 1) "R" else "L", pxv, pad + 12f * d, p); p.textAlign = Paint.Align.LEFT }
            16 -> { val cx = W / 2; val cy = H / 2; val r0 = min(W, H) * 0.24f; val r1 = min(W, H) * 0.2f; val M2 = 64
                path.reset()
                for (i in 0..M2) { val ii = i % M2; val a3 = ii.toFloat() / M2 * PI.toFloat() * 2 - PI.toFloat() / 2 + tSec * 0.15f
                    val v3 = lv[min(N - 1, (if (ii < M2 / 2) ii else M2 - ii) * N / (M2 / 2))]; val r4 = r0 + v3 * r1
                    if (i == 0) path.moveTo(cx + cos(a3) * r4, cy + sin(a3) * r4) else path.lineTo(cx + cos(a3) * r4, cy + sin(a3) * r4) }
                path.close(); p.shader = hGrad(W); p.style = Paint.Style.FILL; p.alpha = 80; c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.alpha = 255; c.drawPath(path, p); p.shader = null }
            17 -> { for (q in parts) { fillC(col(q[4]), min(1f, q[1] * 1.3f + 0.2f)); c.drawCircle(pad + q[0] * iw, q[1] * H, q[3] * d * (1 + bass * 0.8f), p) }
                val fh = 3f * d + bass * 8f * d; fillC(col(0.2f), 0.55f); c.drawRect(pad, H - pad - fh, W - pad, H - pad, p) }
        }
        p.shader = null; p.alpha = 255
        c.restore()
    }
}
