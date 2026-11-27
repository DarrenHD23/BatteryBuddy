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
    class Info(val box: RectF, val grip: RectF, val active: Boolean)
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    init { isClickable = false; isFocusable = false; setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        for (g in provider()) {
            if (g.active) {
                p.style = Paint.Style.FILL; p.color = Color.argb(28, 120, 190, 255); c.drawRoundRect(g.box, 14 * d, 14 * d, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = Color.argb(230, 120, 190, 255); p.pathEffect = DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
                c.drawRoundRect(g.box, 14 * d, 14 * d, p); p.pathEffect = null
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
