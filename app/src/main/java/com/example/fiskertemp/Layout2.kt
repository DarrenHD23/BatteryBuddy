package com.example.fiskertemp

// ---------------------------------------------------------------------------
// Pieces of "Layout 2": a top-down car, a compass card and a shortcuts card in the middle column,
// and the thumbnails for the layout picker in Settings.
// ---------------------------------------------------------------------------

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The translucent rounded card the middle column pieces sit on. */
private fun drawCardBg(c: Canvas, p: Paint, r: RectF, rad: Float, day: Boolean, u: Float) {
    p.shader = null; p.style = Paint.Style.FILL
    p.color = if (day) Color.argb(120, 255, 255, 255) else Color.argb(80, 8, 20, 36)
    c.drawRoundRect(r, rad, rad, p)
    p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * u
    p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
        intArrayOf(Color.argb(210, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(170, 255, 255, 255)),
        floatArrayOf(0f, 0.4f, 0.6f, 1f), Shader.TileMode.CLAMP)
    c.drawRoundRect(RectF(r.left + 0.7f * u, r.top + 0.7f * u, r.right - 0.7f * u, r.bottom - 0.7f * u), rad, rad, p)
    p.shader = null; p.style = Paint.Style.FILL
}

/** The Fisker Ocean seen from behind (a rendered vector drawing), with its lamps lit by stacking transparent overlays. */
class CarView(ctx: Context) : View(ctx) {
    var braking = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var headlights = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var highBeam = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var reverse = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var leftInd = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var rightInd = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val res = ctx.resources
    private fun load(id: Int): Bitmap? = try {
        BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
    } catch (e: Throwable) { null }
    private val base0 by lazy { load(R.drawable.car_base) }
    /** Fisker paint colour (index into CAR_PAINTS); the silver body of the photo is recoloured, the glass, trim, lights and badge are left alone. */
    var paintIdx = 3
        set(v) { val n = v.coerceIn(0, CAR_PAINTS.size - 1); if (field != n) { field = n; tinted = null; invalidate() } }
    private var tinted: Bitmap? = null
    private val base: Bitmap? get() {
        val b0 = base0 ?: return null
        tinted?.let { return it }
        val w = b0.width; val h = b0.height
        val px = IntArray(w * h); b0.getPixels(px, 0, w, 0, 0, w, h)
        val pc = CAR_PAINTS[paintIdx].second
        val pr = Color.red(pc); val pg = Color.green(pc); val pb = Color.blue(pc)
        for (i in px.indices) {
            val c = px[i]; val a = c ushr 24
            if (a == 0) continue
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val sat = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            if (sat > 0.2f || lum < 0.3f) continue                       // glass, bumper, lights, badge
            val wgt = (((lum - 0.3f) / 0.12f).coerceIn(0f, 1f)) * (1f - ((sat - 0.1f) / 0.1f).coerceIn(0f, 1f))
            val shade = (lum / 0.62f).coerceIn(0.2f, 1.45f)
            val nr = (pr * shade).coerceIn(0f, 255f); val ng = (pg * shade).coerceIn(0f, 255f); val nb = (pb * shade).coerceIn(0f, 255f)
            px[i] = (a shl 24) or ((r + (nr - r) * wgt).toInt() shl 16) or ((g + (ng - g) * wgt).toInt() shl 8) or (b + (nb - b) * wgt).toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); out.setPixels(px, 0, w, 0, 0, w, h)
        tinted = out
        return out
    }
    private val dipped by lazy { load(R.drawable.car_dipped) }
    private val beam by lazy { load(R.drawable.car_highbeam) }
    private val left by lazy { load(R.drawable.car_left) }
    private val right by lazy { load(R.drawable.car_right) }
    private val brake by lazy { load(R.drawable.car_brake) }
    private val rev by lazy { load(R.drawable.car_reverse) }

    /** Speed in km/h; the car tilts forward, the road lines run past on both sides and the rear tyre lines turn. */
    var speedKmh = 0f
        set(v) { field = v; if (v >= 1f && !animating) postInvalidateOnAnimation() }
    /** Layout 2 in a 50/50 window: how far above this view the road fill reaches (it fades out to nothing at the top). */
    var roadAbovePx = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    private var animating = false
    private var shown = 0f              // smoothed speed
    private var roadOff = 0f            // how far the road has run
    private var lastMs = 0L
    private val quadSrc = FloatArray(8)
    private val quadDst = FloatArray(8)
    private val mat = android.graphics.Matrix()
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lanePath = Path()
    private val tyrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(128, 190, 196, 205); strokeWidth = 0.7f }
    private val tyrePath = Path()
    private val brakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val brakePath = Path()

    /** Draws in a 302 x 162 unit space (the middle column); the car is 114 x 104 units, 15% bigger than before. */
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val b = base ?: return
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        val target = if (speedKmh < 1f) 0f else speedKmh.coerceAtMost(220f)
        shown += (target - shown) * min(1f, dt * 4f)
        if (target == 0f && shown < 0.5f) shown = 0f
        roadOff += 200f * Math.pow((shown / 200f).toDouble(), 0.4).toFloat() * dt * 1.1f
        val u = h / 162f
        c.save()
        c.translate((w - 302f * u) / 2f, 0f)
        c.scale(u, u)
        // road lines on both sides, wide at the rear of the car and narrow at the top
        val hh = 162f; val smin = 0.45f; val per = 0.3f; val len = 0.15f; val cx = 151f; val half = 86f
        // the road between the lines: a light, transparent grey (in a 50/50 window it carries on up to the top of the screen, fading out)
        val ext = roadAbovePx / u
        val sTop = if (ext > 0f) Math.max(smin - ext * (1f - smin) / hh, 0.15f) else smin
        val yTop = if (ext > 0f) -ext else 0f
        lanePath.reset()
        lanePath.moveTo(cx - half * sTop, yTop); lanePath.lineTo(cx + half * sTop, yTop); lanePath.lineTo(cx + half, hh); lanePath.lineTo(cx - half, hh); lanePath.close()
        lanePaint.shader = if (ext > 0f) LinearGradient(0f, yTop, 0f, 0f, Color.argb(0, 200, 206, 216), Color.argb(48, 200, 206, 216), Shader.TileMode.CLAMP) else null
        lanePaint.color = if (ext > 0f) Color.WHITE else Color.argb(48, 200, 206, 216)     // with the fade, the gradient carries the transparency
        c.drawPath(lanePath, lanePaint)
        lanePaint.shader = null
        for (side in intArrayOf(-1, 1)) {
            for (k in -2..(if (ext > 0f) 22 else 8)) {
                var z1 = 1f + k * per - (roadOff * 0.006f) % per
                var z2 = z1 + len
                z1 = Math.max(z1, 1f)
                if (z2 <= 1f || z1 >= 1f / sTop) continue
                z2 = Math.min(z2, 1f / sTop)
                val s1 = 1f / z1; val s2 = 1f / z2
                val y1 = hh * (s1 - smin) / (1f - smin); val y2 = hh * (s2 - smin) / (1f - smin)
                val w1 = 3.4f * s1; val w2 = 3.4f * s2
                val x1 = cx + side * half * s1; val x2 = cx + side * half * s2
                val fade = if (ext > 0f) (((y1 + y2) / 2f + ext) / ext).coerceIn(0f, 1f) else 1f      // the lines fade out towards the top of the screen
                lanePaint.color = Color.argb(((0.35f + 0.4f * s1) * 255 * fade).toInt().coerceIn(0, 255), 255, 255, 255)
                lanePath.reset()
                lanePath.moveTo(x1 - w1 / 2, y1); lanePath.lineTo(x1 + w1 / 2, y1); lanePath.lineTo(x2 + w2 / 2, y2); lanePath.lineTo(x2 - w2 / 2, y2); lanePath.close()
                c.drawPath(lanePath, lanePaint)
            }
        }
        // the car: 114 x 104 units. At rest it is seen almost from above (squashed, parallel sides); once moving it leans
        // forward like it is driving (taller, rear wider), a little more as it gets faster
        val f = (shown / 200f).coerceIn(0f, 1f)
        val g0 = (shown / 35f).coerceIn(0f, 1f); val g = g0 * g0 * (3f - 2f * g0)
        val cw = 114f; val ch = cw * b.height / b.width
        val cl = cx - cw / 2f; val ct = 26f
        dst.set(cl, ct, cl + cw, ct + ch)
        val sh = 1f; val topW = 1f; val botW = 1f      // no tilt: the car stays in its normal pose
        val cyc = ct + ch / 2f
        quadDst[0] = cx - cw * topW / 2f; quadDst[1] = cyc - ch * sh / 2f
        quadDst[2] = cx + cw * topW / 2f; quadDst[3] = quadDst[1]
        quadDst[4] = cx + cw * botW / 2f; quadDst[5] = cyc + ch * sh / 2f
        quadDst[6] = cx - cw * botW / 2f; quadDst[7] = quadDst[5]
        quadSrc[0] = cl; quadSrc[1] = ct; quadSrc[2] = cl + cw; quadSrc[3] = ct
        quadSrc[4] = cl + cw; quadSrc[5] = ct + ch; quadSrc[6] = cl; quadSrc[7] = ct + ch
        mat.setPolyToPoly(quadSrc, 0, quadDst, 0, 4)
        c.save()
        c.concat(mat)
        c.drawBitmap(b, null, dst, p)
        if (highBeam) beam?.let { c.drawBitmap(it, null, dst, p) } else if (headlights) dipped?.let { c.drawBitmap(it, null, dst, p) }
        if (braking) {
            brake?.let { c.drawBitmap(it, null, dst, p) }
            // the upper brake light: a thin line, curved up slightly at the ends to follow the car
            val bx = cl + 0.35f * cw; val bw = 0.30f * cw; val by = ct + 0.446f * ch; val bh = 0.034f * ch
            brakePath.reset()
            brakePath.moveTo(bx + 0.01f * bw, by + 0.2f * bh); brakePath.quadTo(bx + 0.5f * bw, by + 0.65f * bh * 1.0f, bx + 0.99f * bw, by + 0.2f * bh)
            brakePaint.color = Color.argb(90, 255, 40, 40); brakePaint.strokeWidth = 2.6f; c.drawPath(brakePath, brakePaint)
            brakePaint.color = Color.rgb(255, 42, 42); brakePaint.strokeWidth = 0.9f; c.drawPath(brakePath, brakePaint)
        }
        if (reverse) rev?.let { c.drawBitmap(it, null, dst, p) }
        if (leftInd || rightInd) {
            if ((SystemClock.uptimeMillis() / 380) % 2 == 0L) {
                if (leftInd) left?.let { c.drawBitmap(it, null, dst, p) }
                if (rightInd) right?.let { c.drawBitmap(it, null, dst, p) }
            }
        }
        // small grey lines on the strip of rear tyre that shows, turning with the road
        if (shown > 0f) {
            val sx = cw / 114f; val sy = ch / 104f
            tyrePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 1.6f), (roadOff * 0.2f) % 4f)
            val xs = floatArrayOf(17.4f, 19.6f, 94.4f, 96.6f); val ds = floatArrayOf(-1f, -1f, 1f, 1f)
            for (i in xs.indices) tyreLine(c, cl, ct, sx, sy, xs[i], ds[i])
            c.save(); c.clipRect(cl, ct + 90.2f * sy, cl + cw, ct + 94.6f * sy)
            tyreLine(c, cl, ct, sx, sy, 15.2f, -1f); tyreLine(c, cl, ct, sx, sy, 98.8f, 1f)
            c.restore()
        }
        c.restore()
        c.restore()
        if (leftInd || rightInd) postInvalidateDelayed(120)
        animating = shown > 0f || target > 0f
        if (animating) postInvalidateDelayed(30)
    }

    private val tdPath = Path()
    private val tdRect = RectF()
    /** The car seen from straight above (front at the top), in the paint colour; drawn at the given opacity when parked. */
    private fun drawTopDown(c: Canvas, cx: Float, a: Float) {
        val a255 = (a.coerceIn(0f, 1f) * 255).toInt()
        c.saveLayerAlpha(0f, 0f, 302f, 162f, a255)
        val col = CAR_PAINTS[paintIdx].second
        fun shade(k: Float) = Color.rgb((Color.red(col) * k).toInt().coerceIn(0, 255), (Color.green(col) * k).toInt().coerceIn(0, 255), (Color.blue(col) * k).toInt().coerceIn(0, 255))
        val fl = Paint(Paint.ANTI_ALIAS_FLAG)
        // tyres peeking out at the sides
        fl.style = Paint.Style.FILL; fl.color = Color.rgb(20, 22, 26)
        for (yy in floatArrayOf(36f, 118f)) for (sd in intArrayOf(-1, 1)) {
            tdRect.set(cx + sd * 29.5f - 2.5f, yy, cx + sd * 29.5f + 2.5f, yy + 20f); c.drawRoundRect(tdRect, 2f, 2f, fl)
        }
        // body
        tdPath.reset()
        tdPath.moveTo(cx - 18f, 20f); tdPath.quadTo(cx, 15f, cx + 18f, 20f)
        tdPath.cubicTo(cx + 27f, 28f, cx + 29f, 46f, cx + 29f, 68f); tdPath.lineTo(cx + 29f, 108f)
        tdPath.cubicTo(cx + 29f, 130f, cx + 26f, 144f, cx + 18f, 151f); tdPath.quadTo(cx, 155f, cx - 18f, 151f)
        tdPath.cubicTo(cx - 26f, 144f, cx - 29f, 130f, cx - 29f, 108f); tdPath.lineTo(cx - 29f, 68f)
        tdPath.cubicTo(cx - 29f, 46f, cx - 27f, 28f, cx - 18f, 20f); tdPath.close()
        fl.shader = LinearGradient(cx - 29f, 0f, cx + 29f, 0f, intArrayOf(shade(0.55f), shade(1.05f), shade(1.2f), shade(1.05f), shade(0.55f)), floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(tdPath, fl); fl.shader = null
        fl.style = Paint.Style.STROKE; fl.strokeWidth = 0.8f; fl.color = shade(0.4f); c.drawPath(tdPath, fl)
        // hood lines
        fl.strokeWidth = 0.6f; fl.color = Color.argb(70, 255, 255, 255)
        tdPath.reset(); tdPath.moveTo(cx - 10f, 24f); tdPath.cubicTo(cx - 12f, 36f, cx - 14f, 46f, cx - 16f, 54f); c.drawPath(tdPath, fl)
        tdPath.reset(); tdPath.moveTo(cx + 10f, 24f); tdPath.cubicTo(cx + 12f, 36f, cx + 14f, 46f, cx + 16f, 54f); c.drawPath(tdPath, fl)
        fl.style = Paint.Style.FILL
        // windscreen, glass roof, rear window
        fl.color = Color.rgb(16, 24, 40)
        tdPath.reset(); tdPath.moveTo(cx - 22f, 80f); tdPath.cubicTo(cx - 21f, 68f, cx - 17f, 58f, cx - 12f, 54f); tdPath.quadTo(cx, 51f, cx + 12f, 54f)
        tdPath.cubicTo(cx + 17f, 58f, cx + 21f, 68f, cx + 22f, 80f); tdPath.quadTo(cx, 75f, cx - 22f, 80f); tdPath.close(); c.drawPath(tdPath, fl)
        fl.color = Color.rgb(26, 38, 62); tdRect.set(cx - 21f, 82f, cx + 21f, 112f); c.drawRoundRect(tdRect, 4f, 4f, fl)
        fl.color = Color.rgb(16, 24, 40)
        tdPath.reset(); tdPath.moveTo(cx - 20f, 114f); tdPath.quadTo(cx, 110f, cx + 20f, 114f); tdPath.cubicTo(cx + 19f, 122f, cx + 17f, 128f, cx + 15f, 132f)
        tdPath.quadTo(cx, 135f, cx - 15f, 132f); tdPath.cubicTo(cx - 17f, 128f, cx - 19f, 122f, cx - 20f, 114f); tdPath.close(); c.drawPath(tdPath, fl)
        // mirrors
        fl.color = shade(0.8f)
        for (sd in intArrayOf(-1, 1)) { tdRect.set(cx + sd * 33f - 4f, 68f, cx + sd * 33f + 4f, 78f); c.drawRoundRect(tdRect, 3f, 3f, fl) }
        // tail lights across the back
        fl.style = Paint.Style.STROKE; fl.strokeCap = Paint.Cap.ROUND
        for (sd in intArrayOf(-1, 1)) {
            tdPath.reset(); tdPath.moveTo(cx + sd * 25f, 140f); tdPath.cubicTo(cx + sd * 22f, 146f, cx + sd * 15f, 150f, cx + sd * 6f, 151f)
            if (braking) { fl.color = Color.argb(110, 255, 40, 40); fl.strokeWidth = 4.5f; c.drawPath(tdPath, fl) }
            fl.color = if (braking) Color.rgb(255, 50, 50) else Color.rgb(190, 40, 48); fl.strokeWidth = 1.6f; c.drawPath(tdPath, fl)
        }
        c.restore()
    }

    private fun tyreLine(c: Canvas, cl: Float, ct: Float, sx: Float, sy: Float, x: Float, d: Float) {
        tyrePath.reset()
        tyrePath.moveTo(cl + (x + d * 0.7f) * sx, ct + 88.5f * sy)
        tyrePath.quadTo(cl + (x + d * 1.1f) * sx, ct + 92.5f * sy, cl + (x + d * 0.7f) * sx, ct + 96.5f * sy)
        c.drawPath(tyrePath, tyrePaint)
    }
}

/** Compass card: a static dial with a needle that turns to the car's heading, and the heading in degrees and points. */
class CompassCardView(ctx: Context) : View(ctx) {
    var heading: Double? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var day = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val names16 = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        drawCardBg(c, p, RectF(0f, 0f, w, h), 22f * d, day, d)
        p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL
        p.color = ink; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = min(20f * d, h * 0.11f)
        c.drawText("Compass", w / 2, 8f * d + p.textSize * 0.9f, p)
        val titleB = 8f * d + p.textSize * 1.2f
        val bigSz = min(30f * d, h * 0.14f); val smallSz = min(17f * d, h * 0.085f)
        val textH = bigSz + smallSz * 1.2f + 4f * d
        val r = (min(w * 0.34f, (h - titleB - textH - 37f * d) / 2.24f)).coerceAtLeast(10f * d)
        val cx = w / 2f; val cy = titleB + 20f * d + 1.12f * r
        // dial
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = Color.argb(150, if (day) 20 else 255, if (day) 32 else 255, if (day) 46 else 255)
        c.drawCircle(cx, cy, r, p)
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0)
            val major = i % 9 == 0
            val r1 = r * (if (major) 0.86f else if (i % 3 == 0) 0.92f else 0.95f)
            p.strokeWidth = (if (major) 1.6f else 0.9f) * d
            c.drawLine(cx + (r * 0.99f * sin(a)).toFloat(), cy - (r * 0.99f * cos(a)).toFloat(), cx + (r1 * sin(a)).toFloat(), cy - (r1 * cos(a)).toFloat(), p)
        }
        // points of the compass
        p.style = Paint.Style.FILL; p.color = ink; p.typeface = Typeface.DEFAULT_BOLD
        val pts = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        for (i in pts.indices) {
            val a = Math.toRadians(i * 45.0)
            val big = i % 2 == 0
            p.textSize = (if (big) 15f else 10f) * d * min(1f, r / (52f * d))
            val rr = r * 1.12f + (if (big) 6f * d else 0f)
            val tx = cx + (rr * sin(a)).toFloat(); val ty = cy - (rr * cos(a)).toFloat()
            if (i == 0) { p.color = Color.parseColor("#FF5A4F") } else p.color = ink
            c.drawText(pts[i], tx, ty + p.textSize * 0.35f, p)
        }
        // needle
        val hd = heading
        c.save(); c.rotate((hd ?: 0.0).toFloat(), cx, cy)
        val nl = r * 0.78f; val nw = r * 0.12f
        path.reset(); path.moveTo(cx, cy - nl); path.lineTo(cx + nw, cy); path.lineTo(cx - nw, cy); path.close()
        p.color = Color.parseColor("#F0463C"); c.drawPath(path, p)
        path.reset(); path.moveTo(cx, cy + nl); path.lineTo(cx + nw, cy); path.lineTo(cx - nw, cy); path.close()
        p.color = if (day) Color.parseColor("#E8EEF6") else Color.WHITE; c.drawPath(path, p)
        p.color = Color.argb(255, 120, 130, 150); c.drawCircle(cx, cy, nw * 0.7f, p)
        c.restore()
        // heading
        p.color = ink; p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = bigSz
        val num = if (hd == null) "--" else String.format("%03d°", hd.toInt() % 360)
        c.drawText(num, cx, h - smallSz * 1.5f - 6f * d, p)
        p.textSize = smallSz; p.typeface = Typeface.DEFAULT
        val nm = if (hd == null) "" else names16[(((hd + 11.25) / 22.5).toInt()) % 16]
        c.drawText(nm, cx, h - 8f * d, p)
    }
}

/** Navigation card: the next turn from Google Maps' notification (arrow, distance, instruction); tap to open Maps. */
class NavCardView(ctx: Context) : View(ctx) {
    var day = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var onTap: (() -> Unit)? = null
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    fun refresh() { invalidate() }
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap?.invoke(); return true }
    })
    override fun onTouchEvent(e: MotionEvent): Boolean = gd.onTouchEvent(e)

    private fun fit(s: String, size: Float, maxW: Float): Float { p.textSize = size; val w = p.measureText(s); return if (w > maxW) size * maxW / w else size }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        drawCardBg(c, p, RectF(0f, 0f, w, h), 22f * d, day, d)
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.color = ink; p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = min(20f * d, h * 0.11f)
        c.drawText("Navigation", w / 2, 8f * d + p.textSize * 0.9f, p)
        val top = 8f * d + p.textSize * 1.3f
        if (!NavState.active) {
            p.typeface = Typeface.DEFAULT; p.textSize = fit("No route in progress", min(18f * d, h * 0.09f), w - 20f * d)
            c.drawText("No route in progress", w / 2, h * 0.5f, p)
            p.textSize = fit("Start navigation in Google Maps", min(13f * d, h * 0.07f), w - 20f * d); p.alpha = 170
            c.drawText("Start navigation in Google Maps", w / 2, h * 0.5f + p.textSize * 1.8f, p); p.alpha = 255
            return
        }
        val pad = 10f * d
        val aSize = min(h * 0.40f, w * 0.30f)
        val arrow = NavState.arrow
        if (arrow != null) {
            dst.set(pad, top + 4f * d, pad + aSize, top + 4f * d + aSize)
            // Maps' arrow can be dark on transparent: put it on a light tile so it shows either way
            p.color = Color.argb(if (day) 0 else 200, 255, 255, 255)
            if (!day) c.drawRoundRect(dst, 12f * d, 12f * d, p)
            p.color = Color.WHITE
            c.drawBitmap(arrow, null, dst, p)
        }
        val tx = pad + (if (arrow != null) aSize + 8f * d else 0f)
        val tw = w - tx - pad
        p.textAlign = Paint.Align.LEFT; p.color = ink; p.typeface = Typeface.DEFAULT_BOLD
        val big = fit(NavState.distance, min(34f * d, h * 0.18f), tw)
        p.textSize = big
        c.drawText(NavState.distance, tx, top + big * 1.05f + 4f * d, p)
        p.typeface = Typeface.DEFAULT
        val ins = NavState.instruction
        val isz = min(19f * d, h * 0.10f)
        // wrap the instruction onto up to two lines
        p.textSize = isz
        val words = ins.split(" ")
        var l1 = ""; var l2 = ""
        for (wd in words) { if (l2.isEmpty() && p.measureText("$l1 $wd".trim()) <= tw) l1 = "$l1 $wd".trim() else l2 = "$l2 $wd".trim() }
        var y = top + big * 1.05f + 4f * d + isz * 1.3f
        c.drawText(l1, tx, y, p)
        if (l2.isNotEmpty()) { p.textSize = fit(l2, isz, tw); c.drawText(l2, tx, y + isz * 1.2f, p) }
        p.textAlign = Paint.Align.CENTER; p.alpha = 190; p.textSize = min(14f * d, h * 0.07f)
        val foot = if (NavState.eta.isNotEmpty()) NavState.eta else "Google Maps"
        c.drawText(foot, w / 2, h - 10f * d, p); p.alpha = 255
    }
}

class ShortTile(val label: String, val icon: Drawable?, val slot: Int)

/** Shortcuts card: app tiles, four to a page (2 x 2), swipe for more pages; the last tile adds an app. */
class ShortcutsCardView(ctx: Context) : View(ctx) {
    var onTile: ((ShortTile) -> Unit)? = null
    var onLong: ((ShortTile) -> Unit)? = null
    var day = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private var tiles: List<ShortTile> = emptyList()
    private var page = 0
    /** In a pager card: this view shows one fixed page of tiles, and the pager swipes and draws the dots. */
    var embedded = false
    fun showPage(i: Int) { page = i; invalidate() }
    fun pageTotal() = pageCount()
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rects = Array(4) { RectF() }
    private var shownTiles: List<ShortTile?> = emptyList()

    fun setTiles(t: List<ShortTile>) {
        tiles = t
        val pages = pageCount()
        if (page >= pages) page = pages - 1
        invalidate()
    }

    private fun pageCount() = maxOf(1, (tiles.size + 3) / 4)

    var iconsOnly = false                             // Settings > "show icons instead of names": tiles show just the app icon
        set(v) { if (field != v) { field = v; invalidate() } }
    private var downTile: ShortTile? = null
    private var downX = 0f; private var downY = 0f
    private var longDone = false
    private val slopPx = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private val longRun = Runnable {
        val t = downTile
        if (t != null && t.slot >= 0) { longDone = true; onLong?.invoke(t) }
    }

    private fun tileAt(x: Float, y: Float): ShortTile? {
        for (i in 0 until 4) if (rects[i].contains(x, y)) return shownTiles.getOrNull(i)
        return null
    }

    // a tile fires when the finger lifts on it (not when it touches down); moving away or a long press cancels the tap
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; longDone = false
                downTile = tileAt(e.x, e.y)
                if (downTile != null) postDelayed(longRun, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (downTile != null && (Math.abs(e.x - downX) > slopPx || Math.abs(e.y - downY) > slopPx)) {
                removeCallbacks(longRun); downTile = null; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                val t = downTile; downTile = null
                invalidate()
                if (t != null && !longDone && tileAt(e.x, e.y) === t) onTile?.invoke(t)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longRun); downTile = null; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        drawCardBg(c, p, RectF(0f, 0f, w, h), 22f * d, day, d)
        p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL
        p.color = ink; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = min(20f * d, h * 0.11f)
        c.drawText("Shortcuts", w / 2, 8f * d + p.textSize * 0.9f, p)
        val top = 8f * d + p.textSize * 1.25f
        val dotsH = if (embedded) 4f * d else 16f * d
        val gx = 10f * d; val gy = 6f * d; val pad = 10f * d
        val tw = (w - 2 * pad - gx) / 2f
        val th = (h - top - dotsH - pad - gy - 4f * d) / 2f
        val first = page * 4
        val list = ArrayList<ShortTile?>()
        for (i in 0 until 4) list.add(tiles.getOrNull(first + i))
        shownTiles = list
        for (i in 0 until 4) {
            val col = i % 2; val row = i / 2
            val r = rects[i]
            r.set(pad + col * (tw + gx), top + row * (th + gy), pad + col * (tw + gx) + tw, top + row * (th + gy) + th)
            val t = list[i] ?: continue
            p.style = Paint.Style.FILL; p.shader = null
            p.color = if (day) Color.argb(70, 20, 40, 70) else Color.argb(60, 255, 255, 255)
            if (downTile === t) p.color = if (day) Color.argb(130, 20, 40, 70) else Color.argb(120, 255, 255, 255)
            c.drawRoundRect(r, 14f * d, 14f * d, p)
            val icons = iconsOnly && t.slot >= 0 && t.icon != null
            val isize = if (icons) minOf(r.height() * 0.7f, r.width() * 0.6f, 56f * d) else min(r.height() * 0.46f, 40f * d)
            val icy = if (icons) r.centerY() else r.top + r.height() * 0.40f
            val ic = t.icon
            if (t.slot < 0) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * d; p.color = ink; p.strokeCap = Paint.Cap.ROUND
                val a = isize * 0.4f
                c.drawLine(r.centerX() - a, icy, r.centerX() + a, icy, p); c.drawLine(r.centerX(), icy - a, r.centerX(), icy + a, p)
                p.strokeCap = Paint.Cap.BUTT; p.style = Paint.Style.FILL
            } else if (ic != null) {
                ic.setBounds((r.centerX() - isize / 2).toInt(), (icy - isize / 2).toInt(), (r.centerX() + isize / 2).toInt(), (icy + isize / 2).toInt())
                ic.draw(c)
            }
            if (icons) continue
            p.style = Paint.Style.FILL; p.color = ink; p.typeface = Typeface.DEFAULT
            p.textSize = min(13f * d, r.height() * 0.2f)
            var s = t.label
            val maxW = r.width() - 8f * d
            if (p.measureText(s) > maxW) { while (s.length > 1 && p.measureText("$s…") > maxW) s = s.dropLast(1); s = "$s…" }
            c.drawText(s, r.centerX(), r.bottom - 7f * d, p)
        }
        // page dots
        val pages = if (embedded) 0 else pageCount()
        val dy = h - 11f * d
        val total = pages * 9f * d
        for (i in 0 until pages) {
            val x = w / 2 - total / 2 + 4.5f * d + i * 9f * d
            if (i == page) { p.style = Paint.Style.FILL; p.color = Color.parseColor("#2AA8FF"); c.drawCircle(x, dy, 3.2f * d, p) }
            else { p.style = Paint.Style.STROKE; p.strokeWidth = 1.1f * d; p.color = ink; c.drawCircle(x, dy, 3f * d, p) }
        }
        p.style = Paint.Style.FILL
    }
}

/** Small drawn previews of the layouts, for the picker in Settings. */
object LayoutThumbs {
    fun make(id: Int, w: Int = 520, h: Int = 300): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val look = Skins.look
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), look.bgTop, look.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        fun arc(cx: Float, cy: Float, r: Float, mirror: Boolean) {
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
            p.color = Color.argb(70, 255, 255, 255)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val start = if (mirror) 300f else 120f
            c.drawArc(rect, if (mirror) -60f else 120f, 300f, false, p)
            p.color = look.accent
            c.drawArc(rect, if (mirror) -60f else 120f, 120f, false, p)
            p.strokeCap = Paint.Cap.BUTT
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = r * 0.5f
            c.drawText(if (mirror) "0" else "2.1", cx, cy + r * 0.18f, p)
            if (start < 0f) p.color = Color.WHITE
        }
        fun box(l: Float, t: Float, r: Float, b: Float, a: Int = 60) {
            p.style = Paint.Style.FILL; p.color = Color.argb(a, 255, 255, 255)
            c.drawRoundRect(RectF(l, t, r, b), 14f, 14f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.argb(140, 255, 255, 255)
            c.drawRoundRect(RectF(l, t, r, b), 14f, 14f, p)
            p.style = Paint.Style.FILL
        }
        val W = w.toFloat(); val H = h.toFloat()
        if (id == 2) {
            arc(W * 0.17f, H * 0.38f, H * 0.27f, false)
            arc(W * 0.83f, H * 0.38f, H * 0.27f, true)
            // car
            p.color = Color.parseColor("#2A3446"); c.drawRoundRect(RectF(W * 0.45f, H * 0.06f, W * 0.55f, H * 0.34f), 14f, 14f, p)
            p.color = Color.parseColor("#C8161E"); c.drawRect(W * 0.46f, H * 0.30f, W * 0.54f, H * 0.315f, p)
            // cards
            box(W * 0.33f, H * 0.40f, W * 0.49f, H * 0.72f); box(W * 0.51f, H * 0.40f, W * 0.67f, H * 0.72f)
            p.color = Color.argb(160, 255, 255, 255); c.drawCircle(W * 0.41f, H * 0.57f, H * 0.09f, p)
            for (i in 0 until 4) box(W * (0.525f + (i % 2) * 0.062f), H * (0.46f + (i / 2) * 0.12f), W * (0.575f + (i % 2) * 0.062f), H * (0.54f + (i / 2) * 0.12f), 90)
            // battery bar and stat rows
            box(W * 0.04f, H * 0.70f, W * 0.30f, H * 0.77f, 90)
            box(W * 0.04f, H * 0.82f, W * 0.96f, H * 0.95f, 50)
        } else {
            arc(W * 0.2f, H * 0.42f, H * 0.3f, false)
            arc(W * 0.8f, H * 0.42f, H * 0.3f, true)
            p.color = Color.argb(160, 255, 255, 255); c.drawCircle(W * 0.5f, H * 0.17f, H * 0.08f, p)
            box(W * 0.37f, H * 0.30f, W * 0.63f, H * 0.62f)
            box(W * 0.37f, H * 0.66f, W * 0.63f, H * 0.74f, 90)
            box(W * 0.04f, H * 0.82f, W * 0.96f, H * 0.95f, 50)
        }
        return bmp
    }
}

/** A card that holds several pages (each a card view): swipe sideways to change page; dots under the card show where you are. */
class PagerCardView(ctx: Context) : android.widget.FrameLayout(ctx) {
    var day = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pages: List<View> = emptyList()
    var index = 0
        private set
    private var downX = 0f; private var downY = 0f; private var swiping = false
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

    init { setWillNotDraw(false); clipChildren = true }

    fun setPages(list: List<View>, start: Int = -1) {
        // the card list is refreshed on every layout pass: when nothing changed, leave the views alone so a swipe in progress is not cut off
        if (start < 0 && list == pages && childCount == list.size) return
        val keepIndex = if (start >= 0) start else index
        removeAllViews()
        pages = list
        for (v in list) addView(v, LayoutParams(-1, -1).apply { bottomMargin = (14f * d).toInt() })
        index = keepIndex.coerceIn(0, maxOf(0, list.size - 1))
        for ((i, v) in list.withIndex()) { v.visibility = if (i == index) View.VISIBLE else View.GONE; v.translationX = 0f }
        invalidate()
    }

    fun show(i: Int) {
        if (i < 0 || i >= pages.size || i == index) return
        val old = pages[index]; val new = pages[i]
        val dirn = if (i > index) 1f else -1f
        val w = width.toFloat().coerceAtLeast(1f)
        new.visibility = View.VISIBLE
        new.translationX = dirn * w
        new.animate().translationX(0f).setDuration(220).start()
        old.animate().translationX(-dirn * w).setDuration(220).withEndAction { old.visibility = View.GONE; old.translationX = 0f }.start()
        index = i
        invalidate()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiping = false }
            MotionEvent.ACTION_MOVE -> if (!swiping && Math.abs(e.x - downX) > slop * 1.5f && Math.abs(e.x - downX) > Math.abs(e.y - downY)) { swiping = true; parent?.requestDisallowInterceptTouchEvent(true); return true }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                // judged here from the finger's travel, so a page that handles no touches of its own (the compass) still swipes
                val dx = e.x - downX; val dy = e.y - downY
                if (Math.abs(dx) > 28f * d && Math.abs(dx) > Math.abs(dy) * 1.0f) show(if (dx < 0) index + 1 else index - 1)
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val n = pages.size
        if (n < 2) return
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        val dy = height - 7f * d
        val total = n * 9f * d
        for (i in 0 until n) {
            val x = width / 2f - total / 2 + 4.5f * d + i * 9f * d
            if (i == index) { p.style = Paint.Style.FILL; p.color = Color.parseColor("#2AA8FF"); c.drawCircle(x, dy, 3.2f * d, p) }
            else { p.style = Paint.Style.STROKE; p.strokeWidth = 1.1f * d; p.color = ink; c.drawCircle(x, dy, 3f * d, p) }
        }
    }
}

class ActionTile(val glyph: String, val label: String, val onTap: () -> Unit)

/** A card of big buttons (Settings, Screen size, Hide, Exit ...), one per row, used as a page of the swipe cards. */
class ActionCardView(ctx: Context) : View(ctx) {
    var day = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var provider: (() -> List<ActionTile>)? = null
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rects = ArrayList<RectF>()
    private var shown: List<ActionTile> = emptyList()
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            for ((i, r) in rects.withIndex()) if (r.contains(e.x, e.y)) { shown.getOrNull(i)?.onTap?.invoke(); invalidate(); return true }
            return true
        }
    })
    override fun onTouchEvent(e: MotionEvent): Boolean = gd.onTouchEvent(e)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ink = if (day) Color.parseColor("#14202E") else Color.WHITE
        drawCardBg(c, p, RectF(0f, 0f, w, h), 22f * d, day, d)
        p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL
        p.color = ink; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = min(20f * d, h * 0.11f)
        c.drawText("Controls", w / 2, 8f * d + p.textSize * 0.9f, p)
        val top = 8f * d + p.textSize * 1.3f
        shown = provider?.invoke() ?: emptyList()
        val n = shown.size
        if (n == 0) return
        val gap = 5f * d; val pad = 10f * d
        val th = ((h - top - pad - gap * (n - 1)) / n).coerceAtMost(46f * d)
        rects.clear()
        for ((i, t) in shown.withIndex()) {
            val r = RectF(pad, top + i * (th + gap), w - pad, top + i * (th + gap) + th)
            rects.add(r)
            p.style = Paint.Style.FILL; p.shader = null
            p.color = if (day) Color.argb(70, 20, 40, 70) else Color.argb(60, 255, 255, 255)
            c.drawRoundRect(r, 12f * d, 12f * d, p)
            p.color = ink; p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = min(th * 0.5f, 18f * d)
            p.textAlign = Paint.Align.CENTER
            c.drawText(t.glyph, r.left + th * 0.6f, r.centerY() + p.textSize * 0.35f, p)
            p.typeface = Typeface.DEFAULT; p.textAlign = Paint.Align.LEFT
            p.textSize = min(th * 0.42f, 15f * d)
            var lab = t.label
            val maxW = r.width() - th * 1.2f - 6f * d
            if (p.measureText(lab) > maxW) { while (lab.length > 1 && p.measureText("$lab\u2026") > maxW) lab = lab.dropLast(1); lab = "$lab\u2026" }
            c.drawText(lab, r.left + th * 1.2f, r.centerY() + p.textSize * 0.35f, p)
        }
    }
}
