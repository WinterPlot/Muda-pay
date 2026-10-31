package io.mudapay.mobile

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatic bug recording. Nothing for the member to do:
 *  - crashes (uncaught exceptions) are written to disk the instant they happen, then uploaded at the next start;
 *  - ANRs and native crashes (Android 11+) are picked up from the system's exit history;
 *  - unexpected server answers (non-JSON, HTTP 5xx the server could not log itself, TLS failures, an unknown route) are reported by [Api];
 *  - problems that a screen catches itself go through [nonFatal] / [report], and WebView load failures are reported too;
 *  - every report carries the screen the member was on ([screen], set by the navigation), so it is clear where to look.
 * Reports go to POST /client/errors; the server stores them and e-mails the developer (see db/alert_config.php).
 * Passwords, tokens, e-mails and phone numbers are masked here before anything is saved; offline phones keep up to
 * 20 reports and send them later. Failure-safe: reporting can never crash the app or block a screen.
 */
object ErrorReporter {
    private var app: Application? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = HashMap<String, Long>()
    private var hourStart = 0L
    private var hourCount = 0
    private var lastFlush = 0L
    private val flushing = AtomicBoolean(false)

    @Volatile var pending = false
    @Volatile var lastCall = ""
    /** Name of the screen on display (route name). Set by the navigation; saved with every report. */
    @Volatile var screen = ""

    private val masks = listOf(
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{16,}") to "Bearer [hidden]",
        Regex("(?i)\\b(authorization|token|password|passwd|secret|api[_-]?key|refresh[_-]?token|access[_-]?token|pin)\\b([\"']?\\s*[=:]\\s*[\"']?)[^\\s,;&\"'}\\)]+") to "\$1\$2[hidden]",
        Regex("[A-Fa-f0-9]{32,}") to "[hash]",
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}") to "[email]",
        Regex("\\+?\\d[\\d \\-()]{8,}\\d") to "[number]"
    )

    fun init(a: Application) {
        app = a
        DebugLog.init(a)
        try { pending = dir()?.listFiles()?.isNotEmpty() == true } catch (_: Throwable) { }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { DebugLog.app("crash", "thread=${thread.name}", "${error.javaClass.name}: ${error.message}", error.stackTraceToString()) } catch (_: Throwable) { }
            try { save("crash", "${error.javaClass.name}: ${error.message}", error.stackTraceToString(), "thread=${thread.name}") } catch (_: Throwable) { }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10) }
        }
        scope.launch {
            try { collectExitReasons(a) } catch (_: Throwable) { }
            delay(4000)
            flush()
        }
    }

    /** Call from a try/catch that swallows an exception you still want to hear about. */
    fun nonFatal(e: Throwable, where: String) {
        try { DebugLog.app("app", where, "${e.javaClass.name}: ${e.message}", e.stackTraceToString()) } catch (_: Throwable) { }
        try {
            if (!allow("e|$where|${e.javaClass.name}")) return
            save("error", "${e.javaClass.name}: ${e.message}", e.stackTraceToString(), where)
            flushSoon()
        } catch (_: Throwable) { }
    }

    /** A problem that is not an exception (a WebView that could not load, a screen in a state it should never reach). */
    fun report(where: String, message: String) {
        try { DebugLog.app("app", where, message) } catch (_: Throwable) { }
        try {
            if (!allow("r|$where|${message.take(60)}")) return
            save("error", message, "", where)
            flushSoon()
        } catch (_: Throwable) { }
    }

    /** Called by [Api] after every request. */
    fun api(method: String, path: String, r: ApiResult) {
        try {
            if (path.startsWith("/client/errors")) return
            if (!r.ok && !path.startsWith("/debug/")) DebugLog.api(method, path, r)   // local black box for the MudaPay Debug icon
            // server_error = the server already recorded and e-mailed it; network = the phone is just offline
            // unknown route / method_not_allowed / invalid_json = the app asked for something the server does not have: a real bug, not a member mistake
            val reportable = r.code == "bad_response" || r.code == "tls" || (r.code == "not_found" && r.message == "Unknown route.") || r.code == "method_not_allowed" || r.code == "invalid_json" ||
                (r.http >= 500 && r.code != "server_error")
            if (reportable) {
                if (allow("api|$method $path|${r.code}|${r.http}")) {
                    save("api", "HTTP ${r.http} ${r.code} on $method $path: ${r.message}", "", "")
                    flushSoon()
                }
            } else if (r.ok && pending) flushSoon()
        } catch (_: Throwable) { }
    }

    fun flushSoon() {
        val now = System.currentTimeMillis()
        if (now - lastFlush < 60_000) return
        lastFlush = now
        scope.launch { flush() }
    }

    /* ------------------------------------------------------------------ internals */

    private fun dir(): File? {
        val a = app ?: return null
        return File(a.filesDir, "error_reports").also { if (!it.exists()) it.mkdirs() }
    }

    private fun scrub(s: String, max: Int): String {
        var t = s
        for ((rx, to) in masks) t = rx.replace(t, to)
        return t.take(max)
    }

    private fun allow(key: String): Boolean {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - hourStart > 3_600_000) { hourStart = now; hourCount = 0 }
            val last = seen[key]
            if ((last != null && now - last < 900_000) || hourCount >= 10) return false
            seen[key] = now
            hourCount++
            return true
        }
    }

    private fun versionName(): String = try {
        app?.packageManager?.getPackageInfo(app!!.packageName, 0)?.versionName ?: "?"
    } catch (_: Throwable) { "?" }

    private fun uid(): Int = try { Session.user?.optInt("id", 0) ?: 0 } catch (_: Throwable) { 0 }

    /** Same crash twice = same file (its counter goes up), so a crash loop cannot fill the phone. */
    private fun save(type: String, message: String, stack: String, extra: String) {
        val d = dir() ?: return
        val m = scrub(message, 600)
        val s = scrub(stack, 6000)
        val key = Integer.toHexString((type + m.take(200) + s.take(400)).hashCode())
        val f = File(d, "$type-$key.json")
        var count = 1
        if (f.exists()) try { count = JSONObject(f.readText()).optInt("count", 1) + 1 } catch (_: Throwable) { }
        val j = JSONObject()
            .put("type", type).put("message", m).put("stack", s).put("extra", scrub(extra, 120))
            .put("count", count).put("at", System.currentTimeMillis())
            .put("app_version", versionName()).put("sdk", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("call", lastCall).put("screen", screen).put("uid", uid())
        f.writeText(j.toString())
        val all = d.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        if (all.size > 20) all.take(all.size - 20).forEach { it.delete() }
        pending = true
    }

    private suspend fun flush() {
        if (!flushing.compareAndSet(false, true)) return
        try {
            val d = dir() ?: return
            val files = d.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.lastModified() }?.take(5) ?: emptyList()
            if (files.isEmpty()) { pending = false; return }
            val arr = JSONArray()
            val sent = ArrayList<File>()
            for (f in files) {
                try { arr.put(JSONObject(f.readText())); sent.add(f) } catch (_: Throwable) { f.delete() }
            }
            if (arr.length() == 0) return
            val r = Api.call("POST", "/client/errors", JSONObject().put("reports", arr), auth = false, timeoutMs = 15000)
            if (r.ok) {
                sent.forEach { it.delete() }
                pending = d.listFiles()?.isNotEmpty() == true
            }
        } catch (_: Throwable) {
        } finally {
            flushing.set(false)
        }
    }

    /** ANRs and native crashes kill the process before any Kotlin handler runs: Android 11+ remembers them for us. */
    private fun collectExitReasons(a: Application) {
        if (Build.VERSION.SDK_INT < 30) return
        val p = a.getSharedPreferences("mudapay_errors", Context.MODE_PRIVATE)
        val last = p.getLong("exit_seen", 0L)
        var newest = last
        val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (i in am.getHistoricalProcessExitReasons(null, 0, 6)) {
            if (i.timestamp <= last) continue
            if (i.timestamp > newest) newest = i.timestamp
            val type = when (i.reason) {
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "crash"
                else -> continue
            }
            var trace = ""
            try { i.traceInputStream?.bufferedReader()?.use { trace = it.readText().take(6000) } } catch (_: Throwable) { }
            val label = if (type == "anr") "App not responding" else if (type == "native") "Native crash" else "App failed to start"
            save(type, "$label: ${i.description ?: ""}".trim(), trace, "importance=${i.importance}")
        }
        p.edit().putLong("exit_seen", newest).apply()
    }
}
