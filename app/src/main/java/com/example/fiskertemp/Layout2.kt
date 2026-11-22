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
    // the same look as the Trip Information card: a translucent fill and a 1.5 dp outline in the skin's accent colour
    val look = Skins.look
    val accent = if (day) look.accentDay else look.accent
    p.shader = null; p.style = Paint.Style.FILL
    p.color = if (day) Color.argb(170, (Color.red(accent) * 0.4f + 255 * 0.6f).toInt(), (Color.green(accent) * 0.4f + 255 * 0.6f).toInt(), (Color.blue(accent) * 0.4f + 255 * 0.6f).toInt()) else Color.argb(100, 8, 14, 34)
    val rr = RectF(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f)
    c.drawRoundRect(rr, rad, rad, p)
    p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * u
    p.color = Color.argb(if (day) 120 else 130, Color.red(accent), Color.green(accent), Color.blue(accent))
    c.drawRoundRect(rr, rad, rad, p)
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
    /** Low or high beam on: two soft straight-sided beams of light are drawn on the road ahead of the car. */
    var beamsOn = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var reverse = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lampCache = HashMap<Bitmap, FloatArray?>()
    /** The centre of the rear lamp in a turn signal sprite (0..1 of the sprite): the lower half of its lit pixels, found once per sprite. */
    private fun rearLamp(b: Bitmap): FloatArray? {
        if (lampCache.containsKey(b)) return lampCache[b]
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val ys = ArrayList<Int>()
        for (i in px.indices) if ((px[i] ushr 24) > 40) ys.add(i / w)
        var res: FloatArray? = null
        if (ys.isNotEmpty()) {
            ys.sort(); val med = ys[ys.size / 2]
            var sx = 0L; var sy = 0L; var n = 0
            for (i in px.indices) if ((px[i] ushr 24) > 40 && i / w >= med) { sx += i % w; sy += i / w; n++ }
            if (n > 0) res = floatArrayOf(sx.toFloat() / n / w, sy.toFloat() / n / h)
        }
        lampCache[b] = res
        return res
    }
    var redInd = false      // USA / Canada: the turn signal lamps are drawn red instead of orange
    private val redFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0.1f, 0f, 0f, 0f, 0f, 0f, 0.45f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    var leftInd = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var rightInd = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Blind-spot (0 none, 1 threat, 2 threat + indicator, 3 critical) and door-open warnings (1-4 = threat), left and right, read from the car. */
    var bsdL = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var bsdR = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var dowL = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Open doors from the car (0x343): bit0 driver, 1 passenger, 2 rear left, 3 rear right; -1 = nothing received. */
    var doorBits = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The hatch from the car (0x471): how far open, 0-100 %; -1 = nothing received. */
    var hatchPct = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    var dowR = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The 16 parking-sensor zones: rear 0-3, front 4-7, left 8-11, right 12-15 (0 off, 1-8 zone, 9 clear, 15 fault). */
    var uss: IntArray? = null
        set(v) { if (!(field?.contentEquals(v) ?: (v == null))) { field = v; invalidate() } }
    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val alertPath = Path()
    private val alertRect = RectF()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val res = ctx.resources
    private fun load(id: Int): Bitmap? = try {
        BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
    } catch (e: Throwable) { null }
    private val base0 by lazy { load(R.drawable.car_base) }
    /** Fisker paint colour (index into CAR_PAINTS); the silver body of the photo is recoloured, the glass, trim, lights and badge are left alone. */
    var paintIdx = 3
        set(v) { val n = v.coerceIn(0, CAR_PAINTS.size - 1); if (field != n) { field = n; tinted = null; tintCache.clear(); invalidate() } }
    private var tinted: Bitmap? = null
    private fun tintBody(b0: Bitmap): Bitmap {
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
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPath = Path()

    // the 3D Ocean, pre-rendered from above (frame 0, parked) to behind-and-above (last frame, driving) in 9 steps; the view tips back as the car gets going
    private val F_BASE = intArrayOf(R.drawable.car3d_0_base, R.drawable.car3d_1_base, R.drawable.car3d_2_base, R.drawable.car3d_3_base, R.drawable.car3d_4_base, R.drawable.car3d_5_base, R.drawable.car3d_6_base, R.drawable.car3d_7_base, R.drawable.car3d_8_base)
    private val F_HEAD = intArrayOf(R.drawable.car3d_0_head, R.drawable.car3d_1_head, R.drawable.car3d_2_head, R.drawable.car3d_3_head, R.drawable.car3d_4_head, R.drawable.car3d_5_head, R.drawable.car3d_6_head, R.drawable.car3d_7_head, R.drawable.car3d_8_head)
    private val F_BRAKE = intArrayOf(R.drawable.car3d_0_brake, R.drawable.car3d_1_brake, R.drawable.car3d_2_brake, R.drawable.car3d_3_brake, R.drawable.car3d_4_brake, R.drawable.car3d_5_brake, R.drawable.car3d_6_brake, R.drawable.car3d_7_brake, R.drawable.car3d_8_brake)
    private val F_LEFT = intArrayOf(R.drawable.car3d_0_left, R.drawable.car3d_1_left, R.drawable.car3d_2_left, R.drawable.car3d_3_left, R.drawable.car3d_4_left, R.drawable.car3d_5_left, R.drawable.car3d_6_left, R.drawable.car3d_7_left, R.drawable.car3d_8_left)
    private val F_RIGHT = intArrayOf(R.drawable.car3d_0_right, R.drawable.car3d_1_right, R.drawable.car3d_2_right, R.drawable.car3d_3_right, R.drawable.car3d_4_right, R.drawable.car3d_5_right, R.drawable.car3d_6_right, R.drawable.car3d_7_right, R.drawable.car3d_8_right)
    private val F_REV = intArrayOf(R.drawable.car3d_0_rev, R.drawable.car3d_1_rev, R.drawable.car3d_2_rev, R.drawable.car3d_3_rev, R.drawable.car3d_4_rev, R.drawable.car3d_5_rev, R.drawable.car3d_6_rev, R.drawable.car3d_7_rev, R.drawable.car3d_8_rev)
    private val spriteCache = HashMap<Int, Bitmap>()
    private val tintCache = HashMap<Int, Bitmap>()
    private fun sprite(id: Int): Bitmap? {
        spriteCache[id]?.let { return it }
        if (spriteCache.size > 40) spriteCache.clear()
        val b = load(id) ?: return null
        spriteCache[id] = b; return b
    }
    private fun tintedSprite(id: Int): Bitmap? {
        tintCache[id]?.let { return it }
        val b0 = sprite(id) ?: return null
        if (tintCache.size > 6) tintCache.clear()
        val out = tintBody(b0); tintCache[id] = out; return out
    }
    /** false while the live 3D car (Car3dView) is showing: the pre-rendered car images are then not drawn (glow, warnings and the tilt still are). */
    var spriteCar = true
        set(v) { if (field != v) { field = v; invalidate() } }
    /** True while a car is ahead: the view stays tipped back (road and rear view) even when stopped. */
    var holdTilt = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** 0 = seen from straight above (parked), 1 = seen from behind and above (driving); eases slowly. RoadView follows it. */
    var tilt = 0f
        private set

    /** Draws in a 302 x 162 unit space (the middle column); the car is 114 x 104 units, 15% bigger than before. */
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        val target = if (speedKmh < 1f) 0f else speedKmh.coerceAtMost(220f)
        shown += (target - shown) * min(1f, dt * 4f)
        if (target == 0f && shown < 0.5f) shown = 0f
        roadOff += 200f * Math.pow((shown / 200f).toDouble(), 0.4).toFloat() * dt * 1.1f
        val g0 = (shown / 10f).coerceIn(0f, 1f); val g = if (holdTilt) 1f else g0 * g0 * (3f - 2f * g0)      // fully tipped back by 10 km/h (or held tipped back while a car is ahead)
        tilt += (g - tilt) * min(1f, dt * 1.1f)
        if (Math.abs(tilt - g) < 0.004f) tilt = g
        val pos = tilt * (F_BASE.size - 1)
        val i0 = pos.toInt().coerceIn(0, F_BASE.size - 1); val i1 = min(i0 + 1, F_BASE.size - 1); val fr = pos - i0
        val b0 = tintedSprite(F_BASE[i0]) ?: return
        val b1 = if (i1 != i0 && fr > 0.01f) tintedSprite(F_BASE[i1]) else null
        val u = h / 162f
        c.save()
        c.translate((w - 302f * u) / 2f, 0f)
        c.scale(u, u)
        val cx = 151f      // (the road itself is drawn by RoadView, behind everything)
        // the car sprite is a 150 x 150 unit square centred on the middle of the old 114 x 104 car slot (front of the car at y = 26 when parked)
        val ct = 26f
        dst.set(cx - 75f, 3f, cx + 75f, 153f)
        if (beamsOn && spriteCar) {      // the live 3D car has its own light beams
            // headlight beams: from just inside the car's width at its front, leaning in toward the centre line, fading out up the road
            // (drawn before the car so the car stays on top; the view's parents do not clip, so it may reach above the view)
            val front = ct + (if (spriteCar) 3f else -9f); val len = 117f; val top = front - len
            glowPaint.maskFilter = android.graphics.BlurMaskFilter(2.4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            glowPaint.shader = LinearGradient(0f, front, 0f, top, intArrayOf(Color.argb(95, 255, 240, 205), Color.argb(47, 255, 238, 200), Color.argb(0, 255, 238, 200)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            for (d in intArrayOf(-1, 1)) {
                val x0 = cx + d * 26f; val x1 = cx + d * 25f; val wb = 20.5f; val wt = 43f
                glowPath.reset()
                glowPath.moveTo(x0 - wb, front); glowPath.lineTo(x0 + wb, front); glowPath.lineTo(x1 + wt, top); glowPath.lineTo(x1 - wt, top); glowPath.close()
                c.drawPath(glowPath, glowPaint)
            }
            glowPaint.shader = null; glowPaint.maskFilter = null
        }
        if (spriteCar) {
        c.drawBitmap(b0, null, dst, p)
        if (b1 != null) { p.alpha = (fr * 255).toInt(); c.drawBitmap(b1, null, dst, p); p.alpha = 255 }
        fun layer(ids: IntArray) {
            val a0 = sprite(ids[i0]); val a1 = if (i1 != i0 && fr > 0.01f) sprite(ids[i1]) else null
            if (a1 == null) { if (a0 != null) c.drawBitmap(a0, null, dst, p); return }
            p.alpha = ((1f - fr) * 255).toInt(); if (a0 != null) c.drawBitmap(a0, null, dst, p)
            p.alpha = (fr * 255).toInt(); c.drawBitmap(a1, null, dst, p); p.alpha = 255
        }
        if (highBeam || beamsOn) layer(F_HEAD)      // the daytime running lights alone do not light the still-image car
        if (braking) layer(F_BRAKE)
        if (reverse) layer(F_REV)
        if (redInd) p.colorFilter = redFilter
        if (leftInd) layer(F_LEFT)       // the car flashes the lamps itself, so these follow its state directly
        if (rightInd) layer(F_RIGHT)
        p.colorFilter = null
        // a small soft glow on the rear lamp that is flashing: red for USA / Canada, amber for Euro / UK (the same look as the 3D car)
        fun lampGlow(ids: IntArray) {
            val a = sprite(ids[if (i1 != i0 && fr > 0.5f) i1 else i0]) ?: return
            val q = rearLamp(a) ?: return
            val gx = dst.left + q[0] * dst.width(); val gy = dst.top + q[1] * dst.height(); val gr = dst.width() * 0.085f
            glowP.shader = RadialGradient(gx, gy, gr, if (redInd) Color.argb(110, 255, 42, 26) else Color.argb(110, 255, 115, 0), Color.argb(0, 255, 60, 0), Shader.TileMode.CLAMP)
            c.drawCircle(gx, gy, gr, glowP); glowP.shader = null
        }
        if (leftInd) lampGlow(F_LEFT)
        if (rightInd) lampGlow(F_RIGHT)
        }
        drawAlerts(c, cx)
        drawDoors(c, cx)
        drawHatch(c, cx)
        c.restore()
        if (bsdL > 0 || bsdR > 0 || dowL > 0 || dowR > 0) postInvalidateDelayed(40)
        animating = shown > 0f || target > 0f || tilt != g
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

    private fun zoneColor(z: Int) = if (z >= 6) Color.argb(230, 255, 222, 70) else if (z >= 3) Color.argb(230, 255, 150, 40) else Color.argb(230, 255, 59, 78)

    /** A glow along one side of the car (blind spot or door-open warning), with two chevrons pointing away from the car. */
    private fun sideGlow(c: Canvas, x: Float, sg: Int, top: Float, bottom: Float, crit: Boolean, slow: Boolean, t: Float) {
        val pulse = if (crit) 0.55f + 0.45f * Math.abs(Math.sin(t * 6.0)).toFloat() else if (slow) 0.6f + 0.4f * Math.abs(Math.sin(t * 3.0)).toFloat() else 0.85f
        val rgb = if (crit) intArrayOf(255, 59, 78) else intArrayOf(255, 176, 32)
        val left = if (sg < 0) x - 16f else x - 10f
        alertPaint.style = Paint.Style.FILL
        alertPaint.shader = LinearGradient(x - sg * 16f, 0f, x + sg * 10f, 0f, Color.argb(0, rgb[0], rgb[1], rgb[2]), Color.argb((140 * pulse).toInt(), rgb[0], rgb[1], rgb[2]), Shader.TileMode.CLAMP)
        alertRect.set(left, top, left + 26f, bottom)
        c.drawRoundRect(alertRect, 10f, 10f, alertPaint)
        alertPaint.shader = null
        alertPaint.style = Paint.Style.STROKE; alertPaint.strokeCap = Paint.Cap.ROUND
        alertPaint.color = Color.argb((255 * pulse).toInt(), rgb[0], rgb[1], rgb[2]); alertPaint.strokeWidth = 3.2f
        c.drawLine(x + sg * 4f, top + 6f, x + sg * 4f, bottom - 6f, alertPaint)
        alertPaint.color = Color.argb((255 * pulse).toInt(), 255, 255, 255); alertPaint.strokeWidth = 2f; alertPaint.strokeJoin = Paint.Join.ROUND
        val mid = (top + bottom) / 2f
        for (i in 0..1) {
            val yy = mid - 7f + i * 14f; val xx = x - sg * 6f
            alertPath.reset(); alertPath.moveTo(xx, yy - 5f); alertPath.lineTo(xx + sg * 5f, yy); alertPath.lineTo(xx, yy + 5f)
            c.drawPath(alertPath, alertPaint)
        }
    }

    /** An open door shows as an amber bar on that side of the car. */
    private fun drawDoors(c: Canvas, cx: Float) {
        val b = doorBits; if (b <= 0) return
        fun on(i: Int) = ((b shr i) and 1) == 1
        val carL = cx - 57f; val carR = cx + 57f
        alertPaint.shader = null
        fun bar(l: Float, t: Float, r: Float, bt: Float) {
            alertRect.set(l, t, r, bt)
            alertPaint.color = Color.argb(235, 255, 150, 40); alertPaint.style = Paint.Style.FILL; c.drawRoundRect(alertRect, 3f, 3f, alertPaint)
            alertPaint.color = Color.WHITE; alertPaint.style = Paint.Style.STROKE; alertPaint.strokeWidth = 1.2f; c.drawRoundRect(alertRect, 3f, 3f, alertPaint)
        }
        if (on(0)) bar(carL - 9f, 52f, carL - 2f, 88f)
        if (on(1)) bar(carR + 2f, 52f, carR + 9f, 88f)
        if (on(2)) bar(carL - 9f, 91f, carL - 2f, 125f)
        if (on(3)) bar(carR + 2f, 91f, carR + 9f, 125f)
    }

    /** The hatch shows as a lid behind the car that grows with how far it is open, so it is seen opening and closing. */
    private fun drawHatch(c: Canvas, cx: Float) {
        val p = hatchPct; if (p <= 0) return
        val top = if (spriteCar) 131f else 139f
        alertPaint.shader = null
        alertRect.set(cx - 27f, top, cx + 27f, top + 3f + 16f * p / 100f)
        alertPaint.color = Color.argb(235, 255, 150, 40); alertPaint.style = Paint.Style.FILL; c.drawRoundRect(alertRect, 3f, 3f, alertPaint)
        alertPaint.color = Color.WHITE; alertPaint.style = Paint.Style.STROKE; alertPaint.strokeWidth = 1.2f; c.drawRoundRect(alertRect, 3f, 3f, alertPaint)
    }

    /** Blind-spot and door-open glows beside the car, and the parking-sensor rings around it (in the 302 x 162 unit space). */
    private fun drawAlerts(c: Canvas, cx: Float) {
        val t = (SystemClock.uptimeMillis() % 100000L) / 1000f
        val carL = cx - 57f; val carR = cx + 57f
        for (sg in intArrayOf(-1, 1)) {
            val x = if (sg < 0) carL - 9f else carR + 9f
            val dow = if (sg < 0) dowL else dowR
            val bsd = if (sg < 0) bsdL else bsdR
            if (dow in 1..4) sideGlow(c, x, sg, 40f, 132f, true, false, t)         // a car is coming while a door could open
            if (bsd in 1..3) sideGlow(c, x, sg, 66f, 142f, bsd == 3, bsd == 2, t)
        }
        val u = uss ?: return
        if (u.size < 16) return
        alertPaint.style = Paint.Style.STROKE; alertPaint.strokeCap = Paint.Cap.ROUND; alertPaint.strokeWidth = 2f; alertPaint.shader = null
        // rear: 4 sectors of 8 rings, lit from the far ring in to the sensor's zone (zone 1 taken as the closest)
        for (s in 0..3) {
            val z = u[s]; if (z !in 1..8) continue
            val x0 = carL + 7f + s * 25f; val x1 = x0 + 21f; val bulge = if (s == 1 || s == 2) 2.2f else 0.4f
            for (r in 8 downTo z) {
                alertPaint.color = zoneColor(r)
                if (!spriteCar) {
                    val d = 4f + (r - 1) * 3.2f; val rx = 27f + d; val ry = 26f + d
                    alertRect.set(cx - rx, 115f - ry, cx + rx, 115f + ry)
                    c.drawArc(alertRect, 22f + s * 34f, 30f, false, alertPaint)
                } else {
                    val y = 134f + (r - 1) * 3.2f
                    alertPath.reset(); alertPath.moveTo(x0, y); alertPath.quadTo((x0 + x1) / 2f, y + bulge * 2f, x1, y); c.drawPath(alertPath, alertPaint)
                }
            }
        }
        // front: 4 sectors of 4 rings
        for (s in 0..3) {
            val z = u[4 + s]; if (z !in 1..4) continue
            val x0 = carL + 7f + s * 25f; val x1 = x0 + 21f
            for (r in 4 downTo z) {
                alertPaint.color = zoneColor(r * 2)
                if (!spriteCar) {
                    // the live car: arcs that follow the rounded nose (an ellipse round the front of the car), one sector each
                    val d = 5f + (r - 1) * 5f; val rx = 27f + d; val ry = 26f + d
                    alertRect.set(cx - rx, 41f - ry, cx + rx, 41f + ry)
                    c.drawArc(alertRect, 202f + s * 34f, 30f, false, alertPaint)
                } else {
                    val y = 24f - (r - 1) * 5f
                    alertPath.reset(); alertPath.moveTo(x0, y); alertPath.quadTo((x0 + x1) / 2f, y - 3f, x1, y); c.drawPath(alertPath, alertPaint)
                }
            }
        }
        // sides: 4 sectors from the front to the back, 4 rings each
        for (sg in intArrayOf(-1, 1)) for (s in 0..3) {
            val z = u[(if (sg < 0) 8 else 12) + s]; if (z !in 1..4) continue
            val y0 = 36f + s * 24f; val y1 = y0 + 20f
            for (r in 4 downTo z) {
                val x = if (!spriteCar) cx + sg * (32f + 10f * tilt + (r - 1) * 4.5f) else (if (sg < 0) carL else carR) + sg * (3f + (r - 1) * 4.5f + 7f)
                alertPaint.color = zoneColor(r * 2)
                c.drawLine(x, y0, x, y1, alertPaint)
            }
        }
    }

    private fun tyreLine(c: Canvas, cl: Float, ct: Float, sx: Float, sy: Float, x: Float, d: Float) {
        tyrePath.reset()
        tyrePath.moveTo(cl + (x + d * 0.7f) * sx, ct + 88.5f * sy)
        tyrePath.quadTo(cl + (x + d * 1.1f) * sx, ct + 92.5f * sy, cl + (x + d * 0.7f) * sx, ct + 96.5f * sy)
        c.drawPath(tyrePath, tyrePaint)
    }
}

/**
 * Layout 2 road: a light grey strip with broken lane lines that runs from behind the car right up to the top of the screen,
 * fading a little towards the top. It is its own full-size layer behind everything, so nothing clips it; it lines itself up
 * with the car view and uses the same perspective and speed as the car.
 */
class RoadView(ctx: Context) : View(ctx) {
    var car: View? = null
    var speedKmh = 0f
        set(v) { field = v; if (v >= 1f && !animating) postInvalidateOnAnimation() }
    private var animating = false
    private var shown = 0f
    private var roadOff = 0f
    private var lastMs = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val la = IntArray(2); private val lb = IntArray(2)
    // the road bends with the compass: the heading change over the last 1.6 seconds, scaled up to a 3 second change (up to 30 degrees), turns the far road left or right.
    // A shorter window lets the bend fall away sooner when the road straightens.
    private val histT = ArrayList<Long>(); private val histH = ArrayList<Double>()
    private var curveTarget = 0f
    private var curveShown = 0f
    var onCurve: ((Float) -> Unit)? = null        // the 3D car steers its front wheels with the same bend

    // the car ahead (from the car's ADAS object list): drawn on the road at the spot the road's own perspective gives for its distance
    private var lOn = false
    private var lGap = 0f; private var lLat = 0f; private var lW = 1.85f; private var lCls = 0; private var lBrake = false; private var lSrc = 2
    private var lGapS = 0f; private var lLatS = 0f; private var lAlpha = 0f
    /** True while the ADAS view is on: the road stays wider in the distance. Eases in and out. */
    var adasOn = false
    private var adasE = 0f
    private var par = 0f        // 0 = road lines angled in perspective (driving), 1 = parallel lines (stopped behind a car); eases both ways
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lr = RectF()
    private val lPath = Path()
    /** The car ahead seen from straight above (our own car is top-down while stopped); its rear edge sits at (x, y) and it extends up the screen. */
    private fun drawLeadTop(c: Canvas, x: Float, y: Float, wU: Float, truck: Boolean, small: Boolean, a: Int) {
        if (a <= 2) return
        val len = wU * (if (truck) 2.8f else if (small) 2.2f else 2.4f)
        lp.shader = null; lp.style = Paint.Style.FILL
        lp.color = Color.argb(a * 3 / 10, 0, 0, 0)
        lr.set(x - wU * 0.56f, y - len * 0.02f, x + wU * 0.56f, y + len * 0.05f); c.drawOval(lr, lp)
        lp.color = Color.argb(a, 146, 158, 172)
        lr.set(x - wU / 2f, y - len, x + wU / 2f, y); c.drawRoundRect(lr, wU * 0.38f, wU * 0.38f, lp)
        if (!small) {
            // windscreen and rear glass either side of the roof
            lp.color = Color.argb(a, 32, 42, 54)
            lr.set(x - wU * 0.36f, y - len * (if (truck) 0.97f else 0.66f), x + wU * 0.36f, y - len * (if (truck) 0.80f else 0.56f)); c.drawRoundRect(lr, wU * 0.1f, wU * 0.1f, lp)
            if (!truck) { lr.set(x - wU * 0.33f, y - len * 0.30f, x + wU * 0.33f, y - len * 0.21f); c.drawRoundRect(lr, wU * 0.09f, wU * 0.09f, lp) }
            lp.color = Color.argb(a, 108, 120, 135)
            lr.set(x - wU * 0.38f, y - len * (if (truck) 0.76f else 0.55f), x + wU * 0.38f, y - len * (if (truck) 0.04f else 0.31f)); c.drawRoundRect(lr, wU * 0.1f, wU * 0.1f, lp)
            // tail lamps along the rear edge
            val lw = wU * 0.24f; val lh = wU * 0.07f
            for (d in intArrayOf(-1, 1)) {
                val lx = x + d * wU * 0.3f; val ly = y - len * 0.035f
                if (lBrake) {
                    lp.shader = RadialGradient(lx, ly, wU * 0.3f, Color.argb(a * 6 / 10, 255, 40, 30), Color.argb(0, 255, 40, 30), Shader.TileMode.CLAMP)
                    c.drawCircle(lx, ly, wU * 0.3f, lp); lp.shader = null
                    lp.color = Color.argb(a, 255, 70, 55)
                } else lp.color = Color.argb(a, 140, 30, 30)
                lr.set(lx - lw / 2f, ly - lh / 2f, lx + lw / 2f, ly + lh / 2f); c.drawRoundRect(lr, lh / 2f, lh / 2f, lp)
            }
        }
    }

    // the other objects around the car, drawn by [ObjectsView] on top of everything: a state per object ID that fades in and out
    private class ObjSt(var gap: Float, var lat: Float, var w: Float, var cls: Int, var brake: Boolean) {
        var gapS = gap; var latS = lat; var alpha = 0f; var on = true
    }
    private val objs = HashMap<Int, ObjSt>()
    private var objLastMs = 0L
    var overlay: View? = null
    private var mapU = 0f; private var mapLeft = 0f; private var mapTop = 0f
    private var parNow = 0f; private var tiltNow = 1f
    private var bendFn: (Float) -> Float = { 0f }

    /** The other cars, bikes and people the car's object list shows (not the lead car), each with a stable id. */
    fun setObjects(list: List<LeadCar>) {
        for (o in objs.values) o.on = false
        for (l in list) {
            val st = objs.getOrPut(l.id) { ObjSt(l.gapM.toFloat(), l.latM.toFloat(), l.widthM.toFloat(), l.cls, l.brake) }
            st.gap = l.gapM.toFloat(); st.lat = l.latM.toFloat(); st.w = l.widthM.toFloat(); st.cls = l.cls; st.brake = l.brake; st.on = true
        }
        if (objs.isNotEmpty()) overlay?.postInvalidateOnAnimation()
    }

    /** Called by the overlay: draws the other objects with the same map as the road (including its bend and its parallel/angled lines). */
    fun drawObjects(c: Canvas) {
        if (objs.isEmpty() || mapU <= 0f || visibility != View.VISIBLE) return
        val now = SystemClock.uptimeMillis()
        val dt = if (objLastMs == 0L) 0f else ((now - objLastMs) / 1000f).coerceIn(0f, 0.1f)
        objLastMs = now
        var anim = false
        c.save(); c.translate(mapLeft, mapTop); c.scale(mapU, mapU)
        val it = objs.values.iterator()
        while (it.hasNext()) {
            val o = it.next()
            o.gapS += (o.gap - o.gapS) * min(1f, dt * 6f); o.latS += (o.lat - o.latS) * min(1f, dt * 6f)
            o.alpha += ((if (o.on) 1f else 0f) - o.alpha) * min(1f, dt * 4f)
            if (o.on && o.alpha > 0.98f) o.alpha = 1f
            if (!o.on && o.alpha < 0.02f) { it.remove(); continue }
            if (!o.on || o.alpha < 1f || Math.abs(o.gap - o.gapS) > 0.05f || Math.abs(o.lat - o.latS) > 0.01f) anim = true
            drawOther(c, o)
        }
        c.restore()
        if (anim) overlay?.postInvalidateDelayed(30)
    }

    private fun drawOther(c: Canvas, o: ObjSt) {
        val par = parNow
        val gap = o.gapS.coerceIn(-6f, 45f)
        val s = 1f / (1.86f + gap / 7.7f)
        val sE = s + (1f - s) * par
        val yP = 162f * (s - 0.45f) / 0.55f
        val y = yP + ((20f - gap * 6f) - yP) * par
        val known = o.w in 1.4f..3.2f
        val wM = when (o.cls) { 1, 8 -> if (known) o.w else 2.5f; 2 -> 0.8f; 3, 4, 5 -> 0.6f; else -> if (known) o.w else 1.85f }
        val rel = wM / 1.85f
        val wPersp = 172f * s * 0.72f * rel          // inside its own lane, like the lead car does far away
        val wU = wPersp + (52f * rel - wPersp) * par
        val x = 151f + o.latS * 49f * sE * (1f - 0.42f * par) + bendFn(y)
        // fade at the ends of the range (behind us and far ahead), and with the road view's tilt (the road is not drawn parked)
        val ends = Math.min(((45f - gap) / 10f).coerceIn(0f, 1f), ((gap + 6f) / 1.5f).coerceIn(0f, 1f))
        val a0 = (o.alpha * ends * tiltNow * 0.85f * 255f).toInt().coerceIn(0, 255)
        if (a0 > 2) drawObj(c, x, y, wU, o.cls, o.brake, a0, 1f, null)
    }

    /** gap = metres ahead of the front bumper, lat = metres to the right of our centre line, w = width in metres (0 = unknown), cls = the car's object class. */
    fun setLead(gap: Float, lat: Float, w: Float, cls: Int, brake: Boolean, src: Int) {
        if (!lOn || lAlpha < 0.02f) { lGapS = gap; lLatS = lat }
        lOn = true; lGap = gap; lLat = lat; lW = w; lCls = cls; lBrake = brake; lSrc = src
        postInvalidateOnAnimation()
    }
    fun clearLead() { if (lOn) { lOn = false; postInvalidateOnAnimation() } }

    /**
     * Draws the lead car from behind, standing on the road. The ground mapping is the one the lane dashes use (screen position is linear in 1 / depth),
     * with our car's front bumper at depth 1.86 (y = 26) and about 7.7 m to one depth unit; width scales with the same 1 / depth,
     * 57 units a metre at our own front. [bend] is the road's bend at a given y, so the car follows a curve.
     */
    private fun drawLead(c: Canvas, tilt: Float, par: Float, bend: (Float) -> Float) {
        if (lAlpha <= 0.02f) return
        val gap = lGapS.coerceIn(0.5f, 60f)
        val s = 1f / (1.86f + gap / 7.7f)
        val sE = s + (1f - s) * par                       // 1 when the road lines are parallel (no perspective)
        val yP = 162f * (s - 0.45f) / 0.55f
        val y = yP + ((20f - gap * 6f) - yP) * par        // parallel: a plain 6 units a metre up the road
        val known = lW in 1.4f..3.2f
        val wM = when (lCls) { 1, 8 -> if (known) lW else 2.5f; 2 -> 0.8f; 3, 4, 5 -> 0.6f; else -> if (known) lW else 1.85f }
        val rel = wM / 1.85f
        // width: as wide as our own car graphic (114 units) when it is close, then it shrinks with the road lines until it sits inside them
        // (72% of the lane) from 22 m on
        val t = ((gap - 3f) / 19f).coerceIn(0f, 1f)
        val k = 1.49f + (0.72f - 1.49f) * (t * t * (3f - 2f * t))
        val wPersp = Math.min(172f * s * k * rel, 114f * rel)
        val wU = wPersp + (52f * rel - wPersp) * par
        val x = 151f + lLatS * 49f * sE * (1f - 0.42f * par) + bend(y)         // 49 units a metre = a 3.5 m lane across the 172-unit road at full size
        val a0 = (lAlpha * (if (lSrc == 0) 0.6f else 1f) * 255f).toInt().coerceIn(0, 255)
        drawObj(c, x, y, wU, lCls, lBrake, a0, tilt * (1f - par), Math.round(lGap).toString() + " m")      // stopped behind it (road lines parallel) it is seen from above, like our own car
    }

    /** One vehicle (or bike, or person) from behind, standing with its rear edge on (x, y); fades from top-down to the rear view with [tilt]. */
    private fun drawObj(c: Canvas, x: Float, y: Float, wU: Float, cls: Int, brake: Boolean, a0: Int, tilt: Float, label: String?) {
        val truck = cls == 1 || cls == 8
        val small = cls in 2..5
        val hU = wU * (if (truck) 0.95f else if (small) 1.3f else 0.6f)
        val a = (a0 * tilt).toInt()          // the rear view fades in as we tip back to driving, the top-down one fades out
        lp.shader = null; lp.style = Paint.Style.FILL
        if (tilt < 0.99f) drawLeadTop(c, x, y, wU, truck, small, (a0 * (1f - tilt)).toInt())
        // shadow on the road
        lp.color = Color.argb(a * 3 / 10, 0, 0, 0)
        lr.set(x - wU * 0.56f, y - hU * 0.07f, x + wU * 0.56f, y + hU * 0.07f); c.drawOval(lr, lp)
        if (a > 2) {
            if (small) {
                lp.color = Color.argb(a, 150, 162, 176)
                lr.set(x - wU / 2f, y - hU, x + wU / 2f, y); c.drawRoundRect(lr, wU / 2f, wU / 2f, lp)
            } else if (truck) {
                lp.shader = LinearGradient(0f, y - hU, 0f, y, Color.argb(a, 168, 180, 194), Color.argb(a, 124, 136, 151), Shader.TileMode.CLAMP)
                lr.set(x - wU / 2f, y - hU, x + wU / 2f, y - hU * 0.04f); c.drawRoundRect(lr, wU * 0.1f, wU * 0.1f, lp); lp.shader = null
                lp.color = Color.argb(a, 22, 29, 38); lr.set(x - wU * 0.4f, y - hU * 0.06f, x + wU * 0.4f, y); c.drawRoundRect(lr, wU * 0.03f, wU * 0.03f, lp)
            } else {
                // a modern fastback seen from behind: low, rounded body, a smooth domed cabin with one wide rear window, a thin full-width light bar
                val sh = y - hU * 0.50f; val top = y - hU
                lp.color = Color.argb(a, 20, 26, 34)        // tyres peeking out below the body
                lr.set(x - wU * 0.41f, y - hU * 0.10f, x - wU * 0.25f, y + hU * 0.02f); c.drawRoundRect(lr, wU * 0.04f, wU * 0.04f, lp)
                lr.set(x + wU * 0.25f, y - hU * 0.10f, x + wU * 0.41f, y + hU * 0.02f); c.drawRoundRect(lr, wU * 0.04f, wU * 0.04f, lp)
                lp.shader = LinearGradient(0f, sh, 0f, y, Color.argb(a, 172, 184, 198), Color.argb(a, 116, 128, 144), Shader.TileMode.CLAMP)
                lr.set(x - wU / 2f, sh - hU * 0.05f, x + wU / 2f, y - hU * 0.03f); c.drawRoundRect(lr, wU * 0.22f, wU * 0.22f, lp); lp.shader = null
                lPath.reset()
                lPath.moveTo(x - wU * 0.46f, sh + hU * 0.02f)
                lPath.quadTo(x - wU * 0.38f, top + hU * 0.04f, x - wU * 0.22f, top)
                lPath.lineTo(x + wU * 0.22f, top)
                lPath.quadTo(x + wU * 0.38f, top + hU * 0.04f, x + wU * 0.46f, sh + hU * 0.02f)
                lPath.close()
                lp.shader = LinearGradient(0f, top, 0f, sh, Color.argb(a, 184, 195, 208), Color.argb(a, 146, 158, 172), Shader.TileMode.CLAMP)
                c.drawPath(lPath, lp); lp.shader = null
                lPath.reset()
                lPath.moveTo(x - wU * 0.38f, sh - hU * 0.01f)
                lPath.quadTo(x - wU * 0.32f, top + hU * 0.15f, x - wU * 0.19f, top + hU * 0.10f)
                lPath.lineTo(x + wU * 0.19f, top + hU * 0.10f)
                lPath.quadTo(x + wU * 0.32f, top + hU * 0.15f, x + wU * 0.38f, sh - hU * 0.01f)
                lPath.close()
                lp.color = Color.argb(a, 26, 36, 48); c.drawPath(lPath, lp)
                // lower valance a little darker
                lp.color = Color.argb(a * 6 / 10, 40, 48, 60)
                lr.set(x - wU * 0.34f, y - hU * 0.16f, x + wU * 0.34f, y - hU * 0.05f); c.drawRoundRect(lr, wU * 0.05f, wU * 0.05f, lp)
            }
            // tail lamps: a thin light bar across the back, dim red normally, bright with a glow when its brake lights are on
            if (!small) {
                val ly = y - (if (truck) hU * 0.30f else hU * 0.285f)
                val half = wU * (if (truck) 0.40f else 0.37f)
                if (brake) {
                    for (d in intArrayOf(-1, 1)) {
                        val lx = x + d * half * 0.8f
                        lp.shader = RadialGradient(lx, ly, wU * 0.28f, Color.argb(a * 6 / 10, 255, 40, 30), Color.argb(0, 255, 40, 30), Shader.TileMode.CLAMP)
                        c.drawCircle(lx, ly, wU * 0.28f, lp); lp.shader = null
                    }
                }
                lp.style = Paint.Style.STROKE; lp.strokeCap = Paint.Cap.ROUND
                lp.strokeWidth = Math.max(1.4f, wU * (if (brake) 0.065f else 0.045f))
                lp.color = if (brake) Color.argb(a, 255, 74, 58) else Color.argb(a, 150, 34, 34)
                c.drawLine(x - half, ly, x + half, ly, lp)
                lp.style = Paint.Style.FILL
            }
        }
        // distance under it
        lp.color = Color.argb((a0 * 0.85f).toInt(), 255, 255, 255)
        lp.textAlign = Paint.Align.CENTER; lp.typeface = Typeface.DEFAULT_BOLD; lp.textSize = 9f
        if (label != null) c.drawText(label, x, y + 11f, lp)
    }
    fun noteHeading(h: Double?) {
        val now = SystemClock.uptimeMillis()
        if (h == null) { histT.clear(); histH.clear(); curveTarget = 0f; invalidate(); return }
        histT.add(now); histH.add(h)
        while (histT.size > 1 && now - histT[0] > 1600) { histT.removeAt(0); histH.removeAt(0) }
        var d = (h - histH[0]) * (3000.0 / 1600.0)
        if (d > 180) d -= 360; if (d < -180) d += 360
        curveTarget = if (speedKmh >= 5f) d.coerceIn(-30.0, 30.0).toFloat() else 0f
        if (!animating) postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val cv = car ?: return
        if (cv.width <= 0 || cv.height <= 0 || cv.visibility != View.VISIBLE) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        val target = if (speedKmh < 1f) 0f else speedKmh.coerceAtMost(220f)
        shown += (target - shown) * min(1f, dt * 4f)
        if (target == 0f && shown < 0.5f) shown = 0f
        roadOff += 200f * Math.pow((shown / 200f).toDouble(), 0.4).toFloat() * dt * 1.1f
        curveShown += (curveTarget - curveShown) * min(1f, dt * (if (Math.abs(curveTarget) < Math.abs(curveShown)) 8f else 4f))     // it eases back to straight about twice as fast as it eases into a bend
        if (Math.abs(curveShown - curveTarget) < 0.05f) curveShown = curveTarget
        onCurve?.invoke(curveShown)
        lGapS += (lGap - lGapS) * min(1f, dt * 6f); lLatS += (lLat - lLatS) * min(1f, dt * 6f)
        lAlpha += ((if (lOn) 1f else 0f) - lAlpha) * min(1f, dt * 5f)
        if (lOn && lAlpha > 0.98f) lAlpha = 1f
        if (!lOn && lAlpha < 0.02f) lAlpha = 0f
        val parTarget = if ((car as? CarView)?.holdTilt == true && speedKmh < 1f) 1f else 0f
        par += (parTarget - par) * min(1f, dt * 1.6f)
        if (Math.abs(par - parTarget) < 0.004f) par = parTarget
        val parAnim = par != parTarget
        val adasT = if (adasOn) 1f else 0f
        adasE += (adasT - adasE) * min(1f, dt * 4f)
        if (Math.abs(adasE - adasT) < 0.01f) adasE = adasT
        val adasAnimNow = adasE != adasT
        val leadAnim = lAlpha > 0f && (!lOn || lAlpha < 1f || Math.abs(lGap - lGapS) > 0.05f || Math.abs(lLat - lLatS) > 0.01f)
        getLocationInWindow(la); cv.getLocationInWindow(lb)
        val u = cv.height * cv.scaleY / 162f           // (the ADAS view scales the car view up)
        val carTop = (lb[1] - la[1]).toFloat()
        val carLeft = (lb[0] - la[0]).toFloat()
        c.save()
        mapU = u; mapLeft = carLeft + (cv.width * cv.scaleX - 302f * u) / 2f; mapTop = carTop; parNow = par; tiltNow = (car as? CarView)?.tilt ?: 1f; bendFn = { 0f }
        c.translate(carLeft + (cv.width * cv.scaleX - 302f * u) / 2f, carTop)
        c.scale(u, u)
        // seen from straight above (parked) the road is two straight parallel lines either side of the car; as the car gets going the view tips back
        // and the road narrows toward the horizon. The two are cross-faded by the car's tilt (0 = parked, 1 = driving).
        val tilt = ((car as? CarView)?.tilt ?: 1f).coerceIn(0f, 1f)
        // (overhead view: no road at all; the perspective road below fades in with the tilt and out again when the car parks)
        val lay = if (tilt < 0.999f) c.saveLayerAlpha(-2000f, -2000f, 4000f, 2000f, (tilt * 255).toInt()) else -1
        if (tilt <= 0.001f) { c.restoreToCount(lay); drawLead(c, 0f, 0f) { 0f }; c.restore(); animating = shown > 0f || target > 0f || curveShown != curveTarget || leadAnim || parAnim || adasAnimNow; if (animating) postInvalidateDelayed(30); return }
        val hh = 162f; val smin = 0.45f; val per = 0.3f; val len = 0.15f; val cx = 151f; val half = 86f * (1f - 0.42f * par)      // stopped behind a car the road is only a little wider than our car
        val ext = carTop.coerceAtLeast(0f) / u                    // how far up the road has to go to reach the top of the screen
        val sTop = Math.max(smin - ext * (1f - smin) / hh, 0.03f)
        val yTop0 = hh * (sTop - smin) / (1f - smin)
        val dpar = Math.max(par, adasE)                // parallel (stopped) or the ADAS view: the road runs right up to the top of the screen
        val yTop = yTop0 + (-ext - yTop0) * dpar
        // ADAS view: the road edges are straight lines from full width at the car to 40% of it at the top of the screen
        fun adasW(y: Float): Float = (1f - 0.6f * (hh - y) / (hh + ext)).coerceIn(0.4f, 1f)
        // the bend starts partway down the car and grows smoothly to the top; the tangent angle at the top is the compass change (x 1.75)
        val bendFrom = 76f
        val ltot = bendFrom - yTop
        val bendA = if (ltot > 1f) Math.tan(Math.toRadians((curveShown * 1.75f).toDouble())).toFloat() * ltot / 2f else 0f
        fun sh(y: Float): Float { if (y >= bendFrom || ltot <= 1f) return 0f; val t = (bendFrom - y) / ltot; return bendA * t * t }
        path.reset()
        val steps = 24
        for (i in 0..steps) { val y = yTop + (hh - yTop) * i / steps; val sv = Math.max(smin + y * (1f - smin) / hh, sTop).let { val w0 = it + (adasW(y) - it) * adasE; w0 + (1f - w0) * par }; val x = cx - half * sv + sh(y); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        for (i in steps downTo 0) { val y = yTop + (hh - yTop) * i / steps; val sv = Math.max(smin + y * (1f - smin) / hh, sTop).let { val w0 = it + (adasW(y) - it) * adasE; w0 + (1f - w0) * par }; path.lineTo(cx + half * sv + sh(y), y) }
        path.close()
        p.shader = LinearGradient(0f, yTop, 0f, 0f, Color.argb(20, 200, 206, 216), Color.argb(48, 200, 206, 216), Shader.TileMode.CLAMP)
        p.color = Color.WHITE
        c.drawPath(path, p)
        p.shader = null
        for (side in intArrayOf(-1, 1)) {
            for (k in -2..40) {
                var z1 = 1f + k * per - (roadOff * 0.006f) % per
                var z2 = z1 + len
                z1 = Math.max(z1, 1f)
                // the road's far end: the horizon in perspective, the top of the screen once the lines are parallel
                val zMax = 1f / sTop + ((1f + (hh + ext) / 150f) - 1f / sTop) * dpar
                if (z2 <= 1f || z1 >= zMax) continue
                z2 = Math.min(z2, zMax)
                val s1 = 1f / z1; val s2 = 1f / z2
                var y1 = hh * (s1 - smin) / (1f - smin); var y2 = hh * (s2 - smin) / (1f - smin)
                y1 += ((hh - (z1 - 1f) * 150f) - y1) * dpar; y2 += ((hh - (z2 - 1f) * 150f) - y2) * dpar
                y1 = Math.max(y1, yTop); y2 = Math.max(y2, yTop)
                if (y1 - y2 < 0.3f) continue
                val f1 = s1 + (adasW(y1) - s1) * adasE; val f2 = s2 + (adasW(y2) - s2) * adasE; val e1 = f1 + (1f - f1) * par; val e2 = f2 + (1f - f2) * par
                val w1 = 3.4f * e1; val w2 = 3.4f * e2
                val x1 = cx + side * half * e1 + sh(y1); val x2 = cx + side * half * e2 + sh(y2)
                val fade = if (yTop < 0f) 0.3f + 0.7f * (((y1 + y2) / 2f - yTop) / (-yTop)).coerceIn(0f, 1f) else 1f
                p.color = Color.argb(((0.35f + 0.4f * e1) * 255 * fade).toInt().coerceIn(0, 255), 255, 255, 255)
                path.reset()
                path.moveTo(x1 - w1 / 2, y1); path.lineTo(x1 + w1 / 2, y1); path.lineTo(x2 + w2 / 2, y2); path.lineTo(x2 - w2 / 2, y2); path.close()
                c.drawPath(path, p)
            }
        }
        if (lay >= 0) c.restoreToCount(lay)
        bendFn = { yy -> sh(yy) }
        drawLead(c, tilt, par) { yy -> sh(yy) }
        c.restore()
        overlay?.postInvalidateOnAnimation()
        animating = shown > 0f || target > 0f || curveShown != curveTarget || (tilt > 0f && tilt < 1f) || leadAnim || parAnim || adasAnimNow
        if (animating) postInvalidateDelayed(30)
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

/** When a card last swiped or changed page: the shortcut tiles ignore touches for a moment after, so a quick second touch cannot open an app by accident. */
object ShortcutGuard {
    @Volatile var lastSwipe = 0L
    const val LOCKOUT_MS = 400L
    const val MIN_TAP_MS = 50L      // a shorter touch is a brush, not a tap
    const val MAX_TAP_MS = 400L     // a longer one that stays put is a hesitation (the long press still works)
    fun mark() { lastSwipe = android.os.SystemClock.uptimeMillis() }
    fun locked() = android.os.SystemClock.uptimeMillis() - lastSwipe < LOCKOUT_MS
}

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
    // a press only counts as a tap if the finger barely moves sideways (swipes are sideways, so 4 dp), a little more up and down
    private val tapSlopX = 4f * ctx.resources.displayMetrics.density
    private val tapSlopY = 5f * ctx.resources.displayMetrics.density
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
                downTile = if (ShortcutGuard.locked()) null else tileAt(e.x, e.y)     // just after a swipe: this touch is not a tap
                if (downTile != null) postDelayed(longRun, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (downTile != null && (Math.abs(e.x - downX) > tapSlopX || Math.abs(e.y - downY) > tapSlopY)) {
                removeCallbacks(longRun); downTile = null; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                val t = downTile; downTile = null
                invalidate()
                val held = e.eventTime - e.downTime
                if (t != null && !longDone && !ShortcutGuard.locked() && held in ShortcutGuard.MIN_TAP_MS..ShortcutGuard.MAX_TAP_MS && tileAt(e.x, e.y) === t && Math.abs(e.x - downX) <= tapSlopX && Math.abs(e.y - downY) <= tapSlopY) onTile?.invoke(t)
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
        ShortcutGuard.mark()
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
            MotionEvent.ACTION_MOVE -> if (!swiping && Math.abs(e.x - downX) > slop * 1.5f && Math.abs(e.x - downX) > Math.abs(e.y - downY)) { swiping = true; ShortcutGuard.mark(); parent?.requestDisallowInterceptTouchEvent(true); return true }
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
                if (swiping) ShortcutGuard.mark()
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


/**
 * The live 3D Ocean: a transparent WebView running three.js (the model and scripts are in assets/car3d, served from a fake https host so
 * the page can load its modules). It sits exactly over the 150 x 150 unit car square of the car view, lets every touch through, and
 * reports back whether it started; if it does not, the caller falls back to the pre-rendered car images.
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
class Car3dView(ctx: Context, private val onState: (Boolean) -> Unit) : android.webkit.WebView(ctx) {
    private var ready = false
    private var failed = false
    private var lastJson = ""
    private var lastPush = 0L
    val live: Boolean get() = ready && !failed
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false      // the view reaches over the gauges beside the car, so it never takes a touch
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false; isFocusable = false; isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun ready() { main.post { ready = true; onState(true) } }
            @android.webkit.JavascriptInterface fun fail(m: String?) { main.post { failed = true; onState(false) } }
        }, "CarBridge")
        webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(view: android.webkit.WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = req.url
                if (u.host != HOST || !(u.path ?: "").startsWith("/car3d/")) return android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                val name = u.path!!.removePrefix("/car3d/").ifEmpty { "index.html" }
                return try {
                    val mime = if (name.endsWith(".html")) "text/html" else "text/javascript"
                    android.webkit.WebResourceResponse(mime, "utf-8", context.assets.open("car3d/$name"))
                } catch (e: Throwable) {
                    android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
            override fun onReceivedError(view: android.webkit.WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                if (req.isForMainFrame) main.post { failed = true; onState(false) }
            }
            override fun onRenderProcessGone(view: android.webkit.WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                main.post { failed = true; onState(false) }; return true
            }
        }
        loadUrl("https://$HOST/car3d/index.html")
        // if the page has not reported ready in 10 seconds, give up and use the car images
        main.postDelayed({ if (!ready) { failed = true; onState(false) } }, 10000)
    }

    /** Lets every touch fall through to whatever is underneath. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?) = false

    /** Sends the car's state to the page (about 7 times a second at most, sooner when something other than the speed changes). */
    private var lastSteer = 999f
    /** Front wheel angle: follows the compass bend of the road (positive = right). */
    fun setSteer(bend: Float) {
        if (!live) return
        val d = Math.round(bend * 0.8f * 2f) / 2f
        if (d == lastSteer) return
        lastSteer = d
        evaluateJavascript("window.car3d&&car3d.set({turn:$d})", null)
    }

    var red = false         // USA / Canada: red turn signals
    var follow = true       // Settings: turn the car body toward the road bend
    fun push(speedKmh: Float, head: Int, brake: Boolean, left: Boolean, right: Boolean, reverse: Boolean, paint: Int) {
        if (!live) return
        val hex = String.format("#%06x", paint and 0xFFFFFF)
        val sp = Math.round(speedKmh * 2f) / 2f
        val json = "{speed:$sp,head:$head,brake:$brake,left:$left,right:$right,rev:$reverse,follow:$follow,red:$red,paint:'$hex'}"
        if (json == lastJson) return
        val now = SystemClock.uptimeMillis()
        val onlySpeed = lastJson.substringAfter(",head:") == json.substringAfter(",head:")
        if (onlySpeed && now - lastPush < 140) return
        lastJson = json; lastPush = now
        evaluateJavascript("window.car3d&&car3d.set($json)", null)
    }

    companion object { const val HOST = "appassets.androidplatform.net" }
}


/** A transparent full-size layer above everything that draws the other objects around the car, using the road view's map. Takes no touches. */
class ObjectsView(ctx: Context, private val road: RoadView) : View(ctx) {
    init { isClickable = false; isFocusable = false; road.overlay = this }
    override fun onDraw(c: Canvas) { road.drawObjects(c) }
}
