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
    val MAPS = "com.google.android.apps.maps"
    val NAV_APPS = setOf(MAPS, "com.coastplay")
    @Volatile var pkg = MAPS          // which of them the current guidance comes from (the card opens that one when tapped)
}

/** Reads the Google Maps navigation notification (needs notification access; the app grants itself that with root). */
class NavListener : NotificationListenerService() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onListenerConnected() { scan() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { if (sbn?.packageName in NavState.NAV_APPS) scan() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { if (sbn?.packageName in NavState.NAV_APPS) scan() }

    private fun scan() {
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
            NavState.active = false; NavState.arrow = null
        } else {
            val ex = n.notification.extras
            val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val sub = ex.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            NavState.distance = title
            NavState.instruction = text
            NavState.eta = sub
            NavState.arrow = try {
                val ic = n.notification.getLargeIcon() ?: n.notification.smallIcon
                val dr = ic?.loadDrawable(this)
                if (dr == null) null else {
                    val s = 192
                    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                    dr.setBounds(0, 0, s, s); dr.draw(Canvas(bmp)); bmp
                }
            } catch (e: Exception) { null }
            NavState.pkg = n.packageName
            NavState.active = true
        }
        ui.post { NavState.onChange?.invoke() }
    }

    companion object {
        fun component(ctx: Context) = android.content.ComponentName(ctx, NavListener::class.java).flattenToString()
    }
}
