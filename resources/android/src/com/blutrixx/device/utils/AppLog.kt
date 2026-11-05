package com.blutrixx.device.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's own log: a plain text file in private storage, written here (native) so it works when the web view or PHP is dead, and so a crash
 * can be recorded. Three files of up to 400 KB are kept (app.log, app.log.1, app.log.2). One entry per line:
 *   2026-11-05 15:30:12.345 I tag message
 * Levels D, I, W, E and F (F = a crash). A message with several lines continues on lines that start with four spaces.
 * Never put personal content in a message (note text, amounts, names, coordinates): the person exports this file and shares it.
 *
 * AppLogInit calls init() when the process starts. Other plugins may write with AppLog.write(...) (a static method), by reflection if they
 * do not want a dependency on this one.
 */
object AppLog {
    private const val MAX_BYTES = 400_000L
    private const val KEEP = 3
    private const val MAX_ENTRY = 4000
    private val lock = Any()
    @Volatile private var dir: File? = null
    @Volatile private var ctx: Context? = null

    private fun stamp(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(t))

    @JvmStatic
    fun init(context: Context) {
        val first: Boolean
        synchronized(lock) {
            first = dir == null
            if (first) {
                ctx = context.applicationContext
                dir = File(context.applicationContext.filesDir, "applog").apply { mkdirs() }
                installCrashHandler()
            }
        }
        if (first) {
            val c = context.applicationContext
            checkPreviousRun(c)
            val crashed = File(dir, "crash.marker").exists()
            write('I', "app", describe(c) + if (crashed) "; the last run ended in a crash" else "")
            watchVisibility(c)
        }
    }

    /**
     * A crash in Java code is caught by the crash handler. A death outside it (a native crash, the web view's renderer, an ANR kill) leaves nothing,
     * so a marker file says "the app is on screen": it is made when the first screen starts and removed when the last one stops. A marker still there
     * at the next start means the app died while it was open (unless an update replaced it meanwhile).
     */
    private fun checkPreviousRun(c: Context) {
        val m = File(dir, "session.marker")
        if (!m.exists()) return
        val at = m.lastModified()
        m.delete()
        val updated = try { c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime } catch (e: Exception) { 0L }
        val report = File(dir, "crash.marker")
        if (updated > at) write('I', "app", "the previous run was ended by an update")
        else if (!report.exists()) {
            write('E', "crash", "the previous run ended while the app was open and left no crash report (Android killed it, a crash outside the app's own code, or a force stop)")
            report.writeText("${System.currentTimeMillis()}|NoReport")
        }
    }

    private fun watchVisibility(c: Context) {
        val app = c as? Application ?: return
        var started = 0
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { if (started++ == 0) try { File(dir, "session.marker").writeText(System.currentTimeMillis().toString()) } catch (e: Exception) { } }
            override fun onActivityStopped(a: Activity) { if (--started <= 0) { started = 0; try { File(dir, "session.marker").delete() } catch (e: Exception) { } } }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun describe(c: Context): String {
        val pi = try { c.packageManager.getPackageInfo(c.packageName, 0) } catch (e: Exception) { null }
        val code = if (pi == null) 0L else if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        return "start ${pi?.versionName ?: "?"} (build $code), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "${Locale.getDefault().toLanguageTag()}, ${TimeZone.getDefault().id}"
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val trace = sw.toString().lines().take(30).joinToString("\n")
                write('F', "crash", "Uncaught ${e.javaClass.name} on thread ${t.name}: ${(e.message ?: "").take(300)}\n$trace")
                File(dir, "crash.marker").writeText("${System.currentTimeMillis()}|${e.javaClass.simpleName}")
            } catch (x: Throwable) { /* the app is going down anyway */ }
            previous?.uncaughtException(t, e)
        }
    }

    @JvmStatic
    fun write(level: Char, tag: String, message: String) {
        val d = dir ?: return
        val l = if (level in "DIWEF") level else 'I'
        val t = tag.replace(Regex("[^A-Za-z0-9_.-]"), "").take(24).ifBlank { "app" }
        var m = message.replace("\r", "")
        if (m.length > MAX_ENTRY) m = m.substring(0, MAX_ENTRY) + "..."
        val lines = m.split("\n")
        val sb = StringBuilder()
        sb.append(stamp(System.currentTimeMillis())).append(' ').append(l).append(' ').append(t).append(' ').append(lines[0]).append('\n')
        for (i in 1 until lines.size) sb.append("    ").append(lines[i]).append('\n')
        synchronized(lock) {
            try {
                val f = File(d, "app.log")
                if (f.length() > MAX_BYTES) rotate(d)
                f.appendText(sb.toString())
            } catch (e: Exception) { /* a full disk must not take the app down */ }
        }
    }

    private fun rotate(d: File) {
        File(d, "app.log.${KEEP - 1}").delete()
        for (i in KEEP - 2 downTo 1) File(d, "app.log.$i").renameTo(File(d, "app.log.${i + 1}"))
        File(d, "app.log").renameTo(File(d, "app.log.1"))
    }

    private fun levelRank(c: Char) = "DIWEF".indexOf(c)

    /** Entries (a multi-line entry is one element), oldest first, only from `minLevel` up, the last `limit`. */
    @JvmStatic
    fun read(limit: Int, minLevel: Char = 'D'): List<String> {
        val d = dir ?: return emptyList()
        val entries = ArrayList<String>()
        synchronized(lock) {
            for (i in KEEP - 1 downTo 0) {
                val f = File(d, if (i == 0) "app.log" else "app.log.$i")
                if (!f.exists()) continue
                var cur: StringBuilder? = null
                for (line in f.readLines()) {
                    if (line.startsWith("    ") && cur != null) cur.append('\n').append(line)
                    else { cur?.let { entries.add(it.toString()) }; cur = StringBuilder(line) }
                }
                cur?.let { entries.add(it.toString()) }
            }
        }
        val min = levelRank(minLevel).coerceAtLeast(0)
        val kept = entries.filter { e -> e.length > 24 && levelRank(e[24]) >= min }
        return if (kept.size > limit) kept.subList(kept.size - limit, kept.size) else kept
    }

    @JvmStatic
    fun count(minLevel: Char): Int = read(Int.MAX_VALUE, minLevel).size

    fun clear() {
        val d = dir ?: return
        synchronized(lock) { for (i in 0 until KEEP) File(d, if (i == 0) "app.log" else "app.log.$i").delete() }
        write('I', "app", "log cleared by the person")
    }

    /** {at, what} of the crash that ended the last run, once; null when there was none. */
    fun takeCrash(): Pair<Long, String>? {
        val d = dir ?: return null
        val f = File(d, "crash.marker")
        if (!f.exists()) return null
        val raw = try { f.readText() } catch (e: Exception) { "" }
        f.delete()
        val at = raw.substringBefore('|').toLongOrNull() ?: 0L
        return Pair(at, raw.substringAfter('|', "crash"))
    }

    private const val PREFS = "blutrixx_applog"

    /** Detailed place logging: the plugin that records locations adds one line per fix (time, provider, accuracy; never coordinates). */
    @JvmStatic
    fun detail(): Boolean = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean("detail", false) ?: false

    fun setDetail(on: Boolean) {
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("detail", on)?.apply()
        write('I', "app", "detailed place logging " + if (on) "on" else "off")
    }

    /** The whole log with a header, in a file the export can copy. */
    fun exportFile(c: Context): File {
        val all = read(Int.MAX_VALUE)
        val errors = all.count { it.length > 24 && levelRank(it[24]) >= levelRank('E') }
        val out = File(File(c.filesDir, "applog").apply { mkdirs() }, "export.txt")
        val head = StringBuilder()
        head.append("Log of ${c.packageName}\n")
        head.append("Exported ${stamp(System.currentTimeMillis())} (${TimeZone.getDefault().id})\n")
        head.append(describe(c).replaceFirst("start ", "App ")).append('\n')
        head.append("Entries: ${all.size}, errors: $errors\n")
        head.append("Personal content (notes, amounts, names, coordinates) is never written to this log.\n")
        head.append("----\n")
        out.writeText(head.toString() + all.joinToString("\n") + "\n")
        return out
    }
}
