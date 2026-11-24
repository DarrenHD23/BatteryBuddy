package com.example.fiskertemp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** What the navigation notification (Google Maps or CoastPlay) says right now, for the Navigation card in Layout 2. */
object NavState {
    @Volatile var active = false
    @Volatile var distance = ""        // e.g. "200 yd"
    @Volatile var instruction = ""     // e.g. "Turn left onto High Street"
    @Volatile var eta = ""             // e.g. "12 min · 3.1 mi"
    @Volatile var arrow: Bitmap? = null
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var onDiag: ((String) -> Unit)? = null     // one line for the event log when what a navigation app posts changes shape
    val MAPS = "com.google.android.apps.maps"
    val NAV_APPS = setOf(MAPS, "com.coastplay")
    @Volatile var pkg = MAPS          // which of them the current guidance comes from (the card opens that one when tapped)
}

/** Reads the Google Maps navigation notification (needs notification access; the app grants itself that with root). */
class NavListener : NotificationListenerService() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onListenerConnected() { NavState.onDiag?.invoke("nav: listener connected"); scan() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { if (sbn?.packageName in NavState.NAV_APPS) scan() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { if (sbn?.packageName in NavState.NAV_APPS) scan() }

    private fun scan() { ui.post { try { scanNow() } catch (e: Throwable) { } } }
    private var lastDiag = ""

    /** Every text (and the first picture) inside a notification built from custom RemoteViews, which is how some apps (CoastPlay) draw their guidance. */
    private fun remoteParts(n: StatusBarNotification): Pair<List<String>, Bitmap?> {
        val texts = ArrayList<String>(); var pic: Bitmap? = null
        try {
            val nf = n.notification
            @Suppress("DEPRECATION") val rv = nf.bigContentView ?: nf.contentView ?: nf.headsUpContentView
            if (rv != null) {
                val v = rv.apply(applicationContext, android.widget.FrameLayout(this))
                fun walk(x: android.view.View) {
                    if (x is android.widget.TextView) { val t = x.text?.toString()?.trim().orEmpty(); if (t.isNotEmpty() && t !in texts) texts.add(t) }
                    else if (x is android.widget.ImageView && pic == null) {
                        val d = x.drawable
                        if (d != null) { val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 192; val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 192
                            if (w >= 24 && h >= 24) { val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); d.setBounds(0, 0, w, h); d.draw(Canvas(b)); pic = b } }
                    }
                    if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
                }
                walk(v)
            }
        } catch (e: Throwable) { }
        return Pair(texts, pic)
    }

    private fun scanNow() {
        var found: StatusBarNotification? = null
        try {
            for (n in activeNotifications ?: emptyArray()) {
                if (n.packageName !in NavState.NAV_APPS) continue
                val cat = n.notification.category
                val ongoing = (n.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
                if (cat == android.app.Notification.CATEGORY_NAVIGATION || ongoing) { found = n; break }
            }
        } catch (e: Exception) { }
        val n = found
        if (n == null) {
            // nothing matched: list what the listener can see, so the right app and notification can be picked out
            try {
                val all = activeNotifications ?: emptyArray()
                val d = "nav: no match among ${all.size} notifications: " + all.joinToString(" | ") { x -> val e = x.notification.extras; "${x.packageName} cat=${x.notification.category} flags=${x.notification.flags} title='${e.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: ""}' text='${e.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ""}'" }
                if (d != lastDiag) { lastDiag = d; NavState.onDiag?.invoke(d.take(1500)) }
            } catch (e: Throwable) { if (lastDiag != "err") { lastDiag = "err"; NavState.onDiag?.invoke("nav: cannot read notifications (${e.javaClass.simpleName}) - is notification access on?") } }
            NavState.active = false; NavState.arrow = null
        } else {
            val ex = n.notification.extras
            var title = (ex.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: ex.getCharSequence(android.app.Notification.EXTRA_TITLE_BIG))?.toString() ?: ""
            var text = (ex.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))?.toString() ?: ""
            var sub = (ex.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT))?.toString() ?: ""
            var rvPic: Bitmap? = null
            var rvTexts: List<String> = emptyList()
            if (title.isBlank() && text.isBlank()) {
                // nothing in the usual fields: read the words out of the app's own notification layout, in the order they are drawn
                val (t, pic) = remoteParts(n); rvTexts = t; rvPic = pic
                if (t.isNotEmpty()) { title = t[0]; text = t.getOrElse(1) { "" }; if (sub.isBlank() && t.size > 2) sub = t.drop(2).joinToString(" \u00B7 ") }
            }
            val dump = "nav: ${n.packageName} cat=${n.notification.category} flags=${n.notification.flags} title='$title' text='$text' sub='$sub' remoteTexts=$rvTexts extras=${ex.keySet().joinToString(",")}"
            if (dump != lastDiag) { lastDiag = dump; NavState.onDiag?.invoke(dump) }
            NavState.distance = title
            NavState.instruction = text
            NavState.eta = sub
            NavState.arrow = try {
                val ic = n.notification.getLargeIcon()
                if (ic == null && rvPic != null) rvPic else {
                val ic2 = ic ?: n.notification.smallIcon
                val dr = ic2?.loadDrawable(this)
                if (dr == null) null else {
                    val s = 192
                    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                    dr.setBounds(0, 0, s, s); dr.draw(Canvas(bmp)); bmp
                } }
            } catch (e: Exception) { null }
            NavState.pkg = n.packageName
            NavState.active = true
        }
        NavState.onChange?.invoke()
    }

    companion object {
        fun component(ctx: Context) = android.content.ComponentName(ctx, NavListener::class.java).flattenToString()
    }
}
