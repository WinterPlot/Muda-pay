package io.mudapay.mobile

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the server (admin "App updates") says about the newest published version. */
class UpdateInfo(
    /** optional | required (last day not passed yet) | forced (last day passed: the app cannot be used) */
    val mode: String,
    val mandatory: Boolean,
    val versionName: String,
    val versionCode: Int,
    val kind: String,
    val notes: String,
    /** yyyy-MM-dd, the last day the old app may still be used (required updates only) */
    val deadline: String?,
    val daysLeft: Int,
    val url: String,
    /** true = the APK is downloaded and installed by the app; false = [url] is a web page (Play Store, …) opened in the browser */
    val isFile: Boolean,
    val size: Long,
    val sha256: String,
    val raw: String
)

/**
 * Checks for a new version and runs the download + install.
 *  - optional: a polite "Update available" box (Later = ask again in 3 days)
 *  - required, before the last day: the app works, a box with a countdown appears on every start
 *  - forced, after the last day: [forced] is true and the whole app is replaced by a full-screen update screen
 * The check is public (no sign-in needed), so a locked app is locked on the login screens too.
 * The last answer is kept on the phone: a forced update still blocks while offline, and is lifted once the new version is installed.
 */
object AppUpdate {
    private const val P = "mudapay_update"
    private const val TZ = "Africa/Bujumbura"          // same clock as the server
    private const val RECHECK_MS = 5L * 60L * 1000L     // do not ask more often than this
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var info by mutableStateOf<UpdateInfo?>(null)
        private set
    /** "Later" tapped during this run. */
    var promptDismissed by mutableStateOf(false)

    /** idle | downloading | ready | error */
    var phase by mutableStateOf("idle")
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var apk: File? = null
    private var lastCheck = 0L
    private var loaded = false

    val forced: Boolean get() = info?.mode == "forced"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun currentCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Throwable) { 0 }

    fun currentName(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Throwable) { "" }

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone(TZ) }
    private fun today(): String = fmt().format(Date())
    private fun daysUntil(deadline: String): Int = try {
        val f = fmt()
        ((f.parse(deadline)!!.time - f.parse(today())!!.time) / 86_400_000L).toInt().coerceAtLeast(0)
    } catch (e: Exception) { 0 }

    /* ------------------------------------------------------------ reading the answer */
    private fun parse(ctx: Context, d: JSONObject, fromCache: Boolean): UpdateInfo? {
        val mode = d.str("mode")
        if (mode == "none" || mode.isEmpty()) return null
        val code = d.int("version_code")
        if (code <= currentCode(ctx)) return null                // already installed (or newer): nothing to do
        val mandatory = d.bool("mandatory")
        val deadline = d.str("deadline").ifEmpty { null }
        var m = mode
        var days = if (d.isNull("days_left")) 0 else d.int("days_left")
        if (fromCache && mandatory) {                            // offline: judge by the phone's clock (set to the server's time zone)
            m = if (deadline == null || today() > deadline) "forced" else "required"
            days = if (deadline == null) 0 else daysUntil(deadline)
        }
        return UpdateInfo(
            m, mandatory, d.str("version_name"), code, d.str("kind", "update"), d.str("notes"), deadline, days,
            d.str("download_url"), d.bool("is_file"), if (d.isNull("size")) 0L else d.optLong("size"), d.str("sha256"), d.toString()
        )
    }

    /** Loads the last known answer (so a forced update blocks at once, even offline). */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val raw = prefs(ctx).getString("last", null) ?: return
            info = parse(ctx, JSONObject(raw), true)
        } catch (e: Exception) { }
    }

    /** Asks the server. Called when the app comes to the front (at most every few minutes). */
    fun kick(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        load(app)
        if (!force && System.currentTimeMillis() - lastCheck < RECHECK_MS) return
        scope.launch { check(app) }
    }

    suspend fun check(ctx: Context) {
        val app = ctx.applicationContext
        lastCheck = System.currentTimeMillis()
        val r = Api.call(
            "GET", "/app/update",
            query = mapOf("version_code" to currentCode(app).toString(), "platform" to "android", "lang" to AppLanguage.current),
            auth = false, timeoutMs = 15000
        )
        if (!r.ok) return                                        // offline or server trouble: keep what we knew
        val next = parse(app, r.data, false)
        prefs(app).edit().putString("last", if (next == null) null else next.raw).apply()
        val old = info
        if (old == null || next == null || old.versionCode != next.versionCode) {
            if (phase != "downloading") { phase = "idle"; message = null; apk = null }
            promptDismissed = false
        }
        info = next
    }

    /* ------------------------------------------------------------ "Later" */
    fun shouldPrompt(ctx: Context): Boolean {
        val i = info ?: return false
        if (i.mode == "forced" || promptDismissed) return false
        if (i.mode == "required") return true
        return System.currentTimeMillis() > prefs(ctx).getLong("snooze_${i.versionCode}", 0L)
    }

    fun dismissPrompt(ctx: Context) {
        val i = info ?: return
        promptDismissed = true
        if (i.mode == "optional") prefs(ctx).edit().putLong("snooze_${i.versionCode}", System.currentTimeMillis() + 3L * 24L * 3600L * 1000L).apply()
    }

    /* ------------------------------------------------------------ download + install */
    fun start(ctx: Context) {
        val i = info ?: return
        if (phase == "downloading") return
        if (BuildConfig.PLAY_BUILD) { RateUs.openListing(ctx); return }   // Google Play build: updates come only from Google Play
        if (!i.isFile) { openUrl(ctx, i.url); return }          // a store / web page
        if (!i.url.startsWith("https://")) { phase = "error"; message = L("The update link is not secure. Please contact support."); return }
        if (phase == "ready" && apk?.exists() == true) { install(ctx); return }
        val app = ctx.applicationContext
        phase = "downloading"; progress = 0f; message = null
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                try { ApkInstaller.fetch(app, i) { progress = it } } catch (e: Exception) {
                    message = if (e.message == "checksum") L("The downloaded file is damaged. Please try again.") else L("Download failed. Check your connection and try again.")
                    null
                }
            }
            if (f == null) { phase = "error"; return@launch }
            apk = f; phase = "ready"; message = null
            install(app)
        }
    }

    /** Opens Android's installer for the downloaded file ("direct" build only; see ApkInstaller in src/direct). */
    fun install(ctx: Context) {
        val f = apk
        if (f == null || !f.exists()) { phase = "idle"; return }
        val r = ApkInstaller.install(ctx, f)
        message = r.message
        if (r.failed) phase = "error"
    }
}

/** Result of handing the downloaded update to Android's installer. */
class InstallResult(val ok: Boolean, val message: String? = null, val failed: Boolean = false)
