package io.mudapay.mobile

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local black box for the "MudaPay Debug" icon: keeps the last [MAX] problems seen by THIS phone, unmasked,
 * so you can read the real error without waiting for the e-mail alert.
 *  - kind "api"   : a request the server answered with an error (HTTP code, error code, message, and the server's own
 *                   `debug` text = what Payfonte / NOWPayments / Airtel really replied, only while db/api_debug.flag exists)
 *  - kind "app"   : an exception the app caught, or a state it should never reach
 *  - kind "crash" : an uncaught crash
 * Stored in the app's private folder, never uploaded by this class. Passwords and tokens are never put here (only paths + answers).
 */
object DebugLog {
    private const val MAX = 80
    private var file: File? = null
    private val lock = Any()

    fun init(a: Application) {
        file = File(a.filesDir, "debug_log.jsonl")
    }

    fun api(method: String, path: String, r: ApiResult) {
        val e = r.error
        val raw = e?.toString().orEmpty()
        add("api", "$method $path", r.http, r.code, e?.optString("message").orEmpty().ifEmpty { r.message }, e?.optString("debug").orEmpty(), raw)
    }

    fun app(kind: String, where: String, message: String, stack: String = "") = add(kind, where, 0, "", message, "", stack)

    private fun add(kind: String, title: String, http: Int, code: String, message: String, debug: String, extra: String) {
        try {
            val f = file ?: return
            val o = JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("title", title).put("http", http)
                .put("code", code).put("message", message.take(1500)).put("debug", debug.take(3000)).put("extra", extra.take(4000))
            val screen = ErrorReporter.lastCall
            if (screen.isNotEmpty()) o.put("last_call", screen)
            synchronized(lock) {
                val lines = if (f.exists()) f.readLines().takeLast(MAX - 1) else emptyList()
                f.writeText((lines + o.toString()).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) { }
    }

    /** Newest first. */
    fun all(): List<JSONObject> = try {
        val f = file
        if (f == null || !f.exists()) emptyList()
        else synchronized(lock) { f.readLines().mapNotNull { try { JSONObject(it) } catch (_: Throwable) { null } }.reversed() }
    } catch (_: Throwable) { emptyList() }

    fun clear() { try { synchronized(lock) { file?.delete() } } catch (_: Throwable) { } }

    fun time(ms: Long): String = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(ms))

    fun asText(items: List<JSONObject>): String = items.joinToString("\n\n") { o ->
        buildString {
            append("[${time(o.optLong("t"))}] ${o.optString("kind").uppercase()} ${o.optString("title")}")
            if (o.optInt("http") > 0) append("  HTTP ${o.optInt("http")}")
            if (o.optString("code").isNotEmpty()) append("  code=${o.optString("code")}")
            if (o.optString("message").isNotEmpty()) append("\n${o.optString("message")}")
            if (o.optString("debug").isNotEmpty()) append("\nREAL ERROR: ${o.optString("debug")}")
            if (o.optString("extra").isNotEmpty() && o.optString("kind") != "api") append("\n${o.optString("extra")}")
        }
    }
}

