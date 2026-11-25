package com.example.fiskertemp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Finds the newest build on GitHub, downloads it and installs it (as root where possible, keeping the app's settings). */
object Updater {
    private const val API = "https://api.github.com/repos/DarrenHD23/BatteryBuddy/releases?per_page=100"
    const val ACTION_STATUS = "com.example.fiskertemp.INSTALL_STATUS"

    class Release(val build: Int, val title: String, val notes: String, val apkUrl: String, val size: Long, val token: String = "")

    fun installedBuild(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    private fun open(url: String, token: String = ""): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "FiskerBatteryBuddy")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    /** The newest release (by build number) that has an APK attached, or null if none / offline. Call off the main thread. */
    fun latest(beta: Boolean = false, installed: Int = 0, token: String = ""): Release? = try {
        val dev = token.isNotEmpty()    // with the owner's token GitHub also lists the draft developer builds, and the APKs download through the API
        val c = open(API, token)
        if (c.responseCode != 200) null else {
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            var best: Release? = null
            val notesBy = HashMap<Int, Pair<String, String>>()    // build -> (title, notes) for every release, tests included
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft") && !dev) continue
                val tag = r.optString("tag_name")
                val n = tag.removePrefix("build-").toIntOrNull() ?: continue
                notesBy[n] = Pair(r.optString("name", "Build $n"), cleanNotes(r.optString("body")))
                val assets = r.optJSONArray("assets") ?: continue
                var url = ""; var size = 0L
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk")) { url = if (dev) a.optString("url") else a.optString("browser_download_url"); size = a.optLong("size"); break }
                }
                if (url.isEmpty()) continue
                if (r.optBoolean("prerelease") && !beta && !dev) continue   // test builds are only offered to people who joined the beta
                if (best == null || n > best.build) best = Release(n, r.optString("name", "Build $n"), cleanNotes(r.optString("body")), url, size, if (dev) token else "")
            }
            // one list of everything that changed since the installed build, newest first (test builds' notes included)
            best?.let { b ->
                val builds = notesBy.keys.filter { it > installed && it <= b.build }.sortedDescending()
                if (builds.size > 1) {
                    val text = builds.joinToString("\n\n") { k -> "Build $k\n" + (notesBy[k]?.second ?: "") }
                    best = Release(b.build, b.title, text, b.apkUrl, b.size, b.token)
                }
            }
            best
        }
    } catch (e: Exception) { null }

    /** Every release's notes from the first build to the newest, newest first, as (title, notes). Pre-releases only if [beta]; drafts only with the owner's [token]. Null if offline. Call off the main thread. */
    fun allNotes(beta: Boolean = false, token: String = ""): List<Pair<String, String>>? = try {
        val dev = token.isNotEmpty()
        val found = HashMap<Int, Pair<String, String>>()
        var page = 1
        while (page <= 20) {
            val c = open(API + "&page=" + page, token)
            if (c.responseCode != 200) { if (page == 1) throw RuntimeException("http " + c.responseCode) else break }
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft") && !dev) continue
                if (r.optBoolean("prerelease") && !beta && !dev) continue
                val n = r.optString("tag_name").removePrefix("build-").toIntOrNull() ?: continue
                found[n] = Pair(r.optString("name", "Build $n"), cleanNotes(r.optString("body")))
            }
            if (arr.length() < 100) break
            page++
        }
        found.keys.sortedDescending().map { found[it]!! }
    } catch (e: Exception) { null }

    /** Release notes without the commit trailers. */
    fun cleanNotes(raw: String): String =
        raw.lines().filterNot { it.startsWith("Co-Authored-By", true) || it.startsWith("Claude-Session", true) }.joinToString("\n").trim()
            .ifEmpty { "No release notes." }

    /** Downloads to [dest]; reports 0..100. Throws on failure. */
    fun download(r: Release, dest: File, onProgress: (Int) -> Unit) {
        var c = open(r.apkUrl, r.token)
        c.setRequestProperty("Accept", "application/octet-stream")
        if (r.token.isNotEmpty()) {
            // GitHub answers the API asset address with a redirect to a signed address: follow it by hand, without the token
            c.instanceFollowRedirects = false
            var hops = 0
            while (c.responseCode in 301..308 && hops++ < 5) {
                val loc = c.getHeaderField("Location") ?: break
                c = open(loc); c.setRequestProperty("Accept", "application/octet-stream")
            }
        }
        if (c.responseCode != 200) throw RuntimeException("download failed (${c.responseCode})")
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
        dest.parentFile?.mkdirs()
        var done = 0L; var last = -1
        c.inputStream.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 100 / total).toInt().coerceIn(0, 100); if (p != last) { last = p; onProgress(p) } }
                }
            }
        }
        if (total > 0 && done < total) throw RuntimeException("download incomplete")
        onProgress(100)
    }

    fun rootAvailable(): Boolean = Root.exec("id").let { it.ok && it.out.contains("uid=0") }

    /**
     * Installs the APK as root. A normal "replace" install keeps every setting. Only if Android refuses it because the old
     * app was signed with a different key does the script copy the settings aside, uninstall, install, put the settings
     * back and fix their owner. It runs detached, because installing kills this app, and starts the new one when done.
     */
    /** Restarts the Pulse as root (Settings > Restart Pulse): the usual reboot commands one after another, the last one is an immediate restart. Runs detached. */
    fun rebootRoot(ctx: Context): Boolean {
        val sh = """
sync; sleep 3
reboot; sleep 12
/system/bin/reboot; sleep 12
svc power reboot; sleep 12
setprop sys.powerctl reboot; sleep 12
reboot -f; sleep 12
echo 1 > /proc/sys/kernel/sysrq; echo b > /proc/sysrq-trigger
""".trim()
        val f = File(ctx.cacheDir, "bb_reboot.sh")
        f.writeText(sh)
        f.setReadable(true, false)
        return Root.exec("nohup setsid sh ${f.absolutePath} >/dev/null 2>&1 &").ok
    }

    fun installRoot(ctx: Context, apk: File): Boolean {
        val pkg = ctx.packageName
        val sh = """
PKG=@PKG; A=/data/local/tmp/bb_update.apk; B=/data/local/tmp/bb_backup
cp @SRC §A && chmod 644 §A
sleep 1
R=§(pm install -r §A 2>&1)
case "§R" in
*Success*) ;;
*INCOMPATIBLE*)
  rm -rf §B; mkdir -p §B
  for d in shared_prefs files databases; do [ -d /data/data/§PKG/§d ] && cp -a /data/data/§PKG/§d §B/; done
  pm uninstall §PKG
  R=§(pm install §A 2>&1)
  case "§R" in
  *Success*)
    U=§(stat -c %u /data/data/§PKG)
    for d in shared_prefs files databases; do
      if [ -d §B/§d ]; then rm -rf /data/data/§PKG/§d; cp -a §B/§d /data/data/§PKG/; chown -R §U:§U /data/data/§PKG/§d; fi
    done
    restorecon -R /data/data/§PKG ;;
  esac ;;
esac
am start -n §PKG/.MainActivity
rm -f §A
""".trim().replace("§", "$").replace("@PKG", pkg).replace("@SRC", apk.absolutePath)
        val f = File(ctx.cacheDir, "bb_update.sh")
        f.writeText(sh)
        f.setReadable(true, false)
        val r = Root.exec("nohup setsid sh ${f.absolutePath} >/data/local/tmp/bb_update.log 2>&1 &")
        return r.ok
    }

    /** Without root: Android's own installer asks the user to confirm. The old app must have been signed with the same key. */
    fun installSession(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { inp -> s.openWrite("update.apk", 0, apk.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
            val intent = Intent(ACTION_STATUS).setPackage(ctx.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}
