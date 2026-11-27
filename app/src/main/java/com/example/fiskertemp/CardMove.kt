package com.example.fiskertemp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The little resize grip in the bottom-right corner of every card that can be moved, and an outline around the card being dragged or resized.
 * It only draws: the touches are handled by the activity (a long press moves a card, the grip resizes it).
 */
class CardGripOverlay(ctx: Context, private val provider: () -> List<Info>) : View(ctx) {
    /** [box] and [grip] are in this view's own coordinates. */
    class Info(val box: RectF, val grip: RectF, val active: Boolean, val cols: Int = 0, val rows: Int = 0)
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    /** The area to draw the faint snap grid over while a card is held (null when nothing is held). */
    var gridProvider: (() -> RectF?)? = null
    var gridStep = 0f
    init { isClickable = false; isFocusable = false; setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        val gb = gridProvider?.invoke()
        if (gb != null && gridStep > 4f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.pathEffect = null; p.color = Color.argb(46, 255, 255, 255)
            var x = gb.left; while (x <= gb.right + 1f) { c.drawLine(x, gb.top, x, gb.bottom, p); x += gridStep }
            var y = gb.top; while (y <= gb.bottom + 1f) { c.drawLine(gb.left, y, gb.right, y, p); y += gridStep }
            p.color = Color.argb(70, 255, 255, 255)
            var i = 0; x = gb.left; while (x <= gb.right + 1f) { var j = 0; y = gb.top; while (y <= gb.bottom + 1f) { if (i % 4 == 0 && j % 4 == 0) c.drawCircle(x, y, 2.2f * d, p.apply { style = Paint.Style.FILL }); y += gridStep; j++ }; x += gridStep; i++ }
        }
        for (g in provider()) {
            if (g.active) {
                p.style = Paint.Style.FILL; p.color = Color.argb(28, 120, 190, 255); c.drawRoundRect(g.box, 14 * d, 14 * d, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = Color.argb(230, 120, 190, 255); p.pathEffect = DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
                c.drawRoundRect(g.box, 14 * d, 14 * d, p); p.pathEffect = null
            }
            if (g.cols > 0 && g.rows > 0) {
                // while resizing: a little picture of the grid squares the card covers, with the count (columns x rows), in the middle of the card
                val cell = 4f * d; val gap = 1f * d
                val shownC = minOf(g.cols, 16); val shownR = minOf(g.rows, 16)
                val gw = shownC * (cell + gap) - gap; val gh = shownR * (cell + gap) - gap
                val txt = "${g.cols} \u00D7 ${g.rows}"
                p.style = Paint.Style.FILL; p.textSize = 18f * d; p.typeface = android.graphics.Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.LEFT
                val tw = p.measureText(txt)
                val pw = maxOf(gw, tw) + 24 * d; val ph = gh + 18f * d + 30 * d
                val cx = g.box.centerX(); val cy = g.box.centerY()
                val pr = RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
                p.color = Color.argb(225, 16, 22, 36); c.drawRoundRect(pr, 12 * d, 12 * d, p)
                p.color = Color.WHITE; c.drawText(txt, cx - tw / 2, pr.top + 12 * d + 16 * d, p)
                val x0 = cx - gw / 2; val y0 = pr.top + 12 * d + 24 * d
                p.color = Color.argb(235, 120, 190, 255)
                for (ri in 0 until shownR) for (ci in 0 until shownC) c.drawRect(x0 + ci * (cell + gap), y0 + ri * (cell + gap), x0 + ci * (cell + gap) + cell, y0 + ri * (cell + gap) + cell, p)
            }
            // the grip: a small dark chip with three diagonal strokes
            val r = RectF(g.grip.right - 24 * d, g.grip.bottom - 24 * d, g.grip.right - 4 * d, g.grip.bottom - 4 * d)
            p.style = Paint.Style.FILL; p.color = Color.argb(if (g.active) 210 else 120, 20, 28, 44); c.drawRoundRect(r, 7 * d, 7 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.strokeCap = Paint.Cap.ROUND; p.color = Color.argb(if (g.active) 255 else 200, 255, 255, 255)
            for (i in 0..2) {
                val o = (4 + i * 4.5f) * d
                path.reset(); path.moveTo(r.right - 4 * d, r.bottom - 4 * d - o); path.lineTo(r.right - 4 * d - o, r.bottom - 4 * d)
                c.drawPath(path, p)
            }
        }
    }
}
