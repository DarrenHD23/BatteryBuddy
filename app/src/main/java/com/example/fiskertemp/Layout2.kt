package com.example.fiskertemp

// ---------------------------------------------------------------------------
// Pieces of "Layout 2": a top-down car, a compass card and a shortcuts card in the middle column,
// and the thumbnails for the layout picker in Settings.
// ---------------------------------------------------------------------------

import android.content.Context
import android.graphics.Bitmap
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

/** A simple top-down car seen from behind, with glowing tail lights. */
class CarView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    var braking = false
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // the car is 300 x 676 in its own units, scaled to fit the height
        val k = min(h / 340f, w / 190f)
        val cw = 190f * k; val ch = 340f * k
        val x0 = (w - cw) / 2f; val y0 = (h - ch) / 2f
        c.save(); c.translate(x0, y0); c.scale(k, k)
        // light cast ahead of the car
        p.style = Paint.Style.FILL
        for (sx in floatArrayOf(52f, 138f)) {
            p.shader = RadialGradient(sx, 4f, 44f, Color.argb(190, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawCircle(sx, 4f, 44f, p)
        }
        p.shader = null
        // body
        path.reset()
        path.moveTo(40f, 20f); path.cubicTo(70f, 10f, 120f, 10f, 150f, 20f)
        path.cubicTo(172f, 30f, 182f, 70f, 184f, 130f); path.lineTo(184f, 270f)
        path.cubicTo(184f, 310f, 160f, 332f, 120f, 334f); path.lineTo(70f, 334f)
        path.cubicTo(30f, 332f, 6f, 310f, 6f, 270f); path.lineTo(6f, 130f)
        path.cubicTo(8f, 70f, 18f, 30f, 40f, 20f); path.close()
        p.shader = LinearGradient(0f, 0f, 190f, 0f, intArrayOf(0xFF1A222F.toInt(), 0xFF3A4658.toInt(), 0xFF111823.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = Color.argb(130, 200, 220, 255); c.drawPath(path, p)
        // glass roof and screens
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#0B1220")
        path.reset(); path.moveTo(34f, 84f); path.cubicTo(60f, 70f, 130f, 70f, 156f, 84f); path.lineTo(150f, 112f); path.lineTo(40f, 112f); path.close(); c.drawPath(path, p)
        path.reset(); path.moveTo(42f, 128f); path.lineTo(148f, 128f); path.lineTo(146f, 236f); path.lineTo(44f, 236f); path.close()
        p.color = Color.parseColor("#141E30"); c.drawPath(path, p)
        p.color = Color.argb(40, 255, 255, 255); c.drawRect(52f, 134f, 66f, 232f, p)
        path.reset(); path.moveTo(44f, 250f); path.lineTo(146f, 250f); path.lineTo(152f, 286f); path.lineTo(38f, 286f); path.close()
        p.color = Color.parseColor("#0B1220"); c.drawPath(path, p)
        // tail lights
        val tl = if (braking) Color.parseColor("#FF2A2A") else Color.parseColor("#C8161E")
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 4f; p.color = tl
        c.drawLine(28f, 304f, 78f, 308f, p); c.drawLine(162f, 304f, 112f, 308f, p)
        p.style = Paint.Style.FILL
        for (sx in floatArrayOf(40f, 150f)) {
            p.shader = RadialGradient(sx, 306f, 26f, Color.argb(if (braking) 200 else 110, 255, 40, 40), Color.argb(0, 255, 40, 40), Shader.TileMode.CLAMP)
            c.drawCircle(sx, 306f, 26f, p)
        }
        p.shader = null; p.strokeCap = Paint.Cap.BUTT
        c.restore()
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
        val r = (min(w * 0.40f, (h - titleB - textH - 14f * d) / 2f)).coerceAtLeast(10f * d)
        val cx = w / 2f; val cy = titleB + 6f * d + r
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

class ShortTile(val label: String, val icon: Drawable?, val slot: Int)

/** Shortcuts card: app tiles, four to a page (2 x 2), swipe for more pages; the last tile adds an app. */
class ShortcutsCardView(ctx: Context) : View(ctx) {
    var onTile: ((ShortTile) -> Unit)? = null
    var onLong: ((ShortTile) -> Unit)? = null
    var day = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private var tiles: List<ShortTile> = emptyList()
    private var page = 0
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

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { tileAt(e.x, e.y)?.let { onTile?.invoke(it) }; return true }
        override fun onLongPress(e: MotionEvent) { tileAt(e.x, e.y)?.let { if (it.slot >= 0) onLong?.invoke(it) } }
        override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < abs(vy)) return false
            val pages = pageCount()
            if (vx < 0 && page < pages - 1) { page++; invalidate() } else if (vx > 0 && page > 0) { page--; invalidate() }
            return true
        }
    })

    private fun tileAt(x: Float, y: Float): ShortTile? {
        for (i in 0 until 4) if (rects[i].contains(x, y)) return shownTiles.getOrNull(i)
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gd.onTouchEvent(e)
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
        val dotsH = 16f * d
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
            c.drawRoundRect(r, 14f * d, 14f * d, p)
            val isize = min(r.height() * 0.46f, 40f * d)
            val icy = r.top + r.height() * 0.40f
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
            p.style = Paint.Style.FILL; p.color = ink; p.typeface = Typeface.DEFAULT
            p.textSize = min(13f * d, r.height() * 0.2f)
            var s = t.label
            val maxW = r.width() - 8f * d
            if (p.measureText(s) > maxW) { while (s.length > 1 && p.measureText("$s…") > maxW) s = s.dropLast(1); s = "$s…" }
            c.drawText(s, r.centerX(), r.bottom - 7f * d, p)
        }
        // page dots
        val pages = pageCount()
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
