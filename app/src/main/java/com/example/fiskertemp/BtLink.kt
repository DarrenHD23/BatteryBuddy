package com.example.fiskertemp

// ---------------------------------------------------------------------------
// Checks, in the background, that the Bluetooth link the media card depends on ("FiskerOcean") is really connected.
// It looks at the A2DP / AVRCP / hands-free profiles of the bonded devices, writes what it finds to the log,
// and, if the FiskerOcean device is bonded but not connected, asks Bluetooth to connect it again.
// ---------------------------------------------------------------------------

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BtLink(private val ctx: Context, private val ui: Handler, private val log: (String) -> Unit) {
    private val target = "fiskerocean"
    @Volatile private var running = false
    @Volatile private var lastRun = 0L

    // headset 1, A2DP 2, A2DP sink 11, AVRCP controller 12, hands-free client 16
    private val profiles = listOf(2 to "A2DP", 11 to "A2DP sink", 12 to "AVRCP", 16 to "HFP client", 1 to "headset")

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun nm(d: BluetoothDevice): String = try { d.name ?: "" } catch (e: Throwable) { "" }

    /** Runs the check on its own thread (at most once every few seconds). [done] is called on the main thread: true connected, false not, null could not tell. */
    fun check(reason: String, repair: Boolean = true, done: ((Boolean?) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (running || now - lastRun < 5000) { if (done != null) ui.post { done(null) }; return }
        running = true; lastRun = now
        Thread {
            var ok: Boolean? = null
            try { ok = run(reason, repair) } catch (e: Throwable) { log("bluetooth: check failed: " + e.message) }
            running = false
            if (done != null) ui.post { done(ok) }
        }.start()
    }

    private fun proxy(ad: BluetoothAdapter, id: Int): BluetoothProfile? {
        val latch = CountDownLatch(1)
        var got: BluetoothProfile? = null
        val l = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) { got = p; latch.countDown() }
            override fun onServiceDisconnected(profile: Int) {}
        }
        val ok = try { ad.getProfileProxy(ctx, l, id) } catch (e: Throwable) { false }
        if (!ok) return null
        latch.await(2, TimeUnit.SECONDS)
        return got
    }

    /** Address -> the profiles that device is connected on. */
    private fun connectedNow(ad: BluetoothAdapter): Map<String, MutableList<String>> {
        val m = LinkedHashMap<String, MutableList<String>>()
        for ((id, label) in profiles) {
            val px = proxy(ad, id) ?: continue
            try { for (d in px.connectedDevices) m.getOrPut(d.address) { ArrayList() }.add(label) }
            catch (e: Throwable) { }
            finally { try { ad.closeProfileProxy(id, px) } catch (e: Throwable) { } }
        }
        return m
    }

    private fun run(reason: String, repair: Boolean): Boolean? {
        if (!allowed()) { log("bluetooth: no permission to read the connection ($reason)"); return null }
        val ad = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (ad == null || !ad.isEnabled) { log("bluetooth: off or unavailable ($reason)"); return false }
        val bonded = ad.bondedDevices.toList()
        val fisker = bonded.filter { nm(it).replace(" ", "").lowercase().contains(target) }
        var conn = connectedNow(ad)
        val names = conn.entries.joinToString(", ") { e -> (bonded.firstOrNull { it.address == e.key }?.let { nm(it) } ?: e.key) + " [" + e.value.joinToString("/") + "]" }
        val self = try { ad.name ?: "" } catch (e: Throwable) { "" }
        log("bluetooth ($reason): this device is \"$self\"; connected: " + names.ifEmpty { "nothing" } + "; bonded: " + bonded.joinToString(", ") { nm(it).ifEmpty { it.address } })
        if (fisker.isEmpty()) {
            // no bonded device carries that name here, so all that can be said is whether anything is connected
            return conn.isNotEmpty()
        }
        if (fisker.any { conn.containsKey(it.address) }) return true
        if (!repair) return false
        log("bluetooth: FiskerOcean is paired but not connected; asking Bluetooth to connect it")
        for (d in fisker) {
            for ((id, label) in profiles) {
                if (id == 1) continue
                val px = proxy(ad, id) ?: continue
                try {
                    val r = px.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(px, d)
                    log("bluetooth: connect on $label -> $r")
                } catch (e: Throwable) {
                    log("bluetooth: connect on $label refused (" + (e.cause?.message ?: e.message) + ")")
                } finally { try { ad.closeProfileProxy(id, px) } catch (e: Throwable) { } }
            }
        }
        try { Thread.sleep(3500) } catch (e: InterruptedException) { }
        conn = connectedNow(ad)
        val ok = fisker.any { conn.containsKey(it.address) }
        log("bluetooth: FiskerOcean is " + if (ok) "connected again" else "still not connected")
        return ok
    }
}
