package io.mudapay.mobile

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Which kinds of alert the member wants (chosen in Settings). All on by default. */
object PushPrefs {
    /** Categories the server knows about (sent to /devices so it can skip the ones switched off). */
    val KEYS = listOf("payment", "withdrawal", "bonus", "streak")
    /** Categories created on the phone itself (rewards, account activity). */
    val LOCAL_KEYS = listOf("activity", "account")
    val ALL_KEYS = KEYS + LOCAL_KEYS
    val LABELS = mapOf(
        "payment" to ("Payment updates" to "Deposits (credited, under review, expired), and when you send or receive money"),
        "withdrawal" to ("Withdrawals" to "\"You withdrew …\" confirmation, then approved, paid or rejected"),
        "bonus" to ("Weekly bonus" to "A reminder to open MudaPay each day, and a receipt when the weekly bonus is added"),
        "streak" to ("Weekly bonus at risk" to "A reminder before a missed day cancels this week's bonus"),
        "activity" to ("Rewards & activity" to "Tasks, share results, referral rewards, leaderboard prizes and funds added by MudaPay as they happen"),
        "account" to ("Account & security" to "Password changes, sign-in methods, support tickets and messages from MudaPay")
    )
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("mudapay_push", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String) = p(ctx).getBoolean(key, true)
    fun set(ctx: Context, key: String, v: Boolean) { p(ctx).edit().putBoolean(key, v).apply() }
    fun asked(ctx: Context) = p(ctx).getBoolean("permission_asked", false)
    fun markAsked(ctx: Context) { p(ctx).edit().putBoolean("permission_asked", true).apply() }
}

/** One notification to show. [details] become extra lines when the notification is expanded. */
class PushMsg(
    val type: String,
    val title: String,
    val body: String,
    val route: String? = null,
    val details: List<String> = emptyList(),
    /** Same key seen within [Push.DEDUP_MS] = shown only once (server push + local check, retries, …). */
    val dedup: String? = null,
    val subText: String? = null
)

object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appCtx: Context? = null
    private val ids = AtomicInteger((System.currentTimeMillis() % 100_000_000L).toInt())
    private val recentLock = Any()
    const val DEDUP_MS = 6L * 3600L * 1000L
    private const val SUMMARY_ID = 1
    /** Completed money events: they get the "money done" sound (in the app: TxSound, closed app: the notification channel). */
    val TX_DONE = setOf("payment_transfer_out", "payment_transfer_in", "payment_deposit", "withdrawal_paid")
    private const val TX_CHANNEL = "mp_txdone"
    /** true while a screen of the app is visible (set by MainActivity). */
    @Volatile var foreground = false
    private fun txSoundUri(ctx: Context) = android.net.Uri.parse("android.resource://${ctx.packageName}/${R.raw.tx_success}")
    private const val GROUP = "mudapay_updates"

    fun init(ctx: Context) { appCtx = ctx.applicationContext }
    fun appOrNull(): Context? = appCtx

    fun firebaseReady(ctx: Context): Boolean = try { FirebaseApp.getApps(ctx).isNotEmpty() } catch (e: Throwable) { false }
    fun notificationsAllowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /* ------------------------------------------------------------ channels */
    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        PushPrefs.ALL_KEYS.forEach { k ->
            val (name, desc) = PushPrefs.LABELS.getValue(k)
            nm.createNotificationChannel(NotificationChannel("mp_$k", name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                setSound(sound, attrs)
            })
        }
        // completed transactions: their own channel, because a channel's sound cannot be changed after it was created
        nm.createNotificationChannel(NotificationChannel(TX_CHANNEL, "Completed transactions", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Money sent or received, deposits credited and withdrawals paid"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            setSound(txSoundUri(ctx), attrs)
        })
        nm.createNotificationChannel(NotificationChannel("mp_general", "General", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Other MudaPay messages"
            setSound(sound, attrs)
        })
    }

    /* ------------------------------------------------------------ server registration */
    private suspend fun token(): String? = suspendCancellableCoroutine { c ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> c.resume(if (t.isSuccessful) t.result else null) }
        } catch (e: Throwable) { c.resume(null) }
    }

    private fun versionName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Throwable) { "?" }

    /** Sends this phone's token + chosen categories to the server (idempotent upsert). Safe to call often. */
    suspend fun syncNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!Session.signedIn || !firebaseReady(app)) return
        val t = token() ?: return
        val cats = JSONObject().apply { PushPrefs.KEYS.forEach { put(it, PushPrefs.get(app, it)) } }
        val body = JSONObject().put("token", t).put("platform", "android").put("app_version", versionName(app)).put("categories", cats)
        Api.call("POST", "/devices", body)
    }

    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { syncNow(app) }
    }

    /* ------------------------------------------------------------ de-duplication */
    private fun loadRecent(ctx: Context): MutableList<Pair<String, Long>> {
        val raw = ctx.getSharedPreferences("mudapay_push", Context.MODE_PRIVATE).getString("recent", "") ?: ""
        val out = ArrayList<Pair<String, Long>>()
        raw.split('\n').forEach { line ->
            val i = line.lastIndexOf('\t')
            if (i > 0) line.substring(i + 1).toLongOrNull()?.let { out.add(line.substring(0, i) to it) }
        }
        return out
    }

    fun markShown(ctx: Context, key: String) {
        val app = ctx.applicationContext
        synchronized(recentLock) {
            val now = System.currentTimeMillis()
            val list = loadRecent(app).filter { now - it.second < DEDUP_MS }.toMutableList()
            list.add(key.replace('\n', ' ').replace('\t', ' ') to now)
            val text = list.takeLast(100).joinToString("\n") { it.first + "\t" + it.second }
            app.getSharedPreferences("mudapay_push", Context.MODE_PRIVATE).edit().putString("recent", text).apply()
        }
    }

    fun wasShown(ctx: Context, key: String, withinMs: Long = DEDUP_MS): Boolean {
        val now = System.currentTimeMillis()
        val k = key.replace('\n', ' ').replace('\t', ' ')
        return synchronized(recentLock) { loadRecent(ctx.applicationContext).any { it.first == k && now - it.second < withinMs } }
    }

    /* ------------------------------------------------------------ routing */
    fun keyFor(type: String): String? = when {
        type.startsWith("payment") -> "payment"
        type.startsWith("withdrawal") -> "withdrawal"
        type == "daily_bonus" || type == "daily_bonus_claimed" -> "bonus"
        type == "streak_risk" -> "streak"
        type in ACTIVITY_TYPES -> "activity"
        type in ACCOUNT_TYPES -> "account"
        else -> null
    }
    private val ACTIVITY_TYPES = setOf("task_reward", "video_reward", "like_reward", "share_reward", "share_pending", "share_rejected", "credit", "referral", "sync_done")
    private val ACCOUNT_TYPES = setOf("account", "info", "password_changed", "google_linked", "google_unlinked", "ticket_sent", "email_code_sent", "email_verified")

    fun defaultRoute(type: String) = when {
        type.startsWith("withdrawal") -> "Withdraw"
        type == "payment_transfer_in" -> "Receive"
        type == "payment_request_in" -> "Requests"
        type.startsWith("payment_deposit") -> "Deposit"
        type == "payment_transfer_out" -> "Send"
        type == "email_code_sent" || type == "email_verified" -> "Settings"
        type == "task_reward" -> "Tasks"
        type == "video_reward" || type == "like_reward" -> "Videos"
        type == "share_reward" || type == "share_pending" || type == "share_rejected" -> "Share"
        type == "credit" || type == "account" || type == "info" -> "Notifications"
        type == "referral" -> "Referrals"
        type == "ticket_sent" -> "Support"
        type == "password_changed" || type == "google_linked" || type == "google_unlinked" -> "Settings"
        else -> "Home"
    }

    private fun categoryFor(type: String) = when {
        type == "daily_bonus" || type == "streak_risk" -> NotificationCompat.CATEGORY_REMINDER
        type in ACCOUNT_TYPES -> NotificationCompat.CATEGORY_MESSAGE
        else -> NotificationCompat.CATEGORY_STATUS
    }

    /* ------------------------------------------------------------ showing */
    /** Shows the notification (also while the app is open: a heads-up banner + sound). Returns true when it was posted. */
    @SuppressLint("MissingPermission")
    fun post(ctx: Context, m: PushMsg): Boolean {
        val app = ctx.applicationContext
        try {
            val key = keyFor(m.type)
            val txDone = m.type in TX_DONE
            // the app is open: play the sound ourselves (even when alerts are switched off, it is feedback for what the member just did)
            if (txDone && foreground) TxSound.play(m.dedup)
            if (key != null && !PushPrefs.get(app, key)) return false
            if (m.dedup != null) {
                if (wasShown(app, m.dedup)) return false
                markShown(app, m.dedup)
            }
            if (!notificationsAllowed(app)) return false
            createChannels(app)

            val route = m.route ?: defaultRoute(m.type)
            val id = ids.incrementAndGet() + 10
            // Tapping the notification opens a screen that shows this very message in full (the related screen is one button away).
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            val open = InboxMsg(m.type, m.title, m.body, m.details, stamp, route).putInto(Intent(app, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(app, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // the grouped "New updates" row opens the list of notifications
            val listIntent = Intent(app, MainActivity::class.java).putExtra("route", "Notifications")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val listPi = PendingIntent.getActivity(app, SUMMARY_ID, listIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val channel = if (txDone) TX_CHANNEL else "mp_" + (key ?: "general")
            val oldPhone = Build.VERSION.SDK_INT < 26
            val color = ContextCompat.getColor(app, R.color.mudapay_green)
            val expanded = if (m.details.isEmpty()) m.body else m.body + "\n\n" + m.details.joinToString("\n")

            // What shows on a locked screen when the member hides sensitive content (no amounts).
            val publicVersion = NotificationCompat.Builder(app, channel)
                .setSmallIcon(R.drawable.ic_stat_mudapay).setColor(color)
                .setContentTitle("MudaPay").setContentText(L("You have a new update")).build()

            val b = NotificationCompat.Builder(app, channel)
                .setSmallIcon(R.drawable.ic_stat_mudapay)
                .setColor(color)
                .setContentTitle(m.title)
                .setContentText(m.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expanded).setSummaryText(m.subText ?: "MudaPay"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(categoryFor(m.type))
                .setDefaults(if (txDone && oldPhone) NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS else NotificationCompat.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis()).setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setGroup(GROUP)
                .addAction(0, "Open", pi)
            if (txDone && foreground) b.setSilent(true)                    // TxSound already played
            else if (txDone && oldPhone) b.setSound(txSoundUri(app))        // Android 7 and older: no channels
            try {
                BitmapFactory.decodeResource(app.resources, R.mipmap.ic_launcher)?.let { b.setLargeIcon(it) }
            } catch (e: Throwable) { }

            val nm = NotificationManagerCompat.from(app)
            nm.notify(id, b.build())

            val summary = NotificationCompat.Builder(app, channel)
                .setSmallIcon(R.drawable.ic_stat_mudapay).setColor(color)
                .setContentTitle("MudaPay").setContentText(L("New updates"))
                .setGroup(GROUP).setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true).setContentIntent(listPi).build()
            nm.notify(SUMMARY_ID, summary)
            return true
        } catch (e: Throwable) {
            ErrorReporter.nonFatal(e, "Push.post")
            return false
        }
    }

    private fun statusOf(type: String) = when (type) {
        "withdrawal_paid" -> "paid"
        "withdrawal_rejected" -> "rejected"
        "withdrawal_update", "withdrawal_approved" -> "approved"
        else -> type
    }

    /** Entry point for pushes sent by the server (FCM data message). */
    fun fromServer(ctx: Context, data: Map<String, String>, fallbackTitle: String?, fallbackBody: String?) {
        val type = data["type"] ?: "general"
        val title = data["title"] ?: fallbackTitle ?: "MudaPay"
        val body = data["body"] ?: fallbackBody ?: return
        val details = data["details"]?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val wd = data["withdrawal_id"]
        val ref = data["reference"]
        // the same message can arrive twice (FCM + "missed" list after sign-in): the outbox id tells them apart
        val ob = data["ob_id"]?.takeIf { it.isNotEmpty() && it != "0" }
        if (ob != null && wasShown(ctx, "ob:$ob")) return
        val nid = data["notif_id"]?.takeIf { it.isNotEmpty() && it != "0" }
        val dedup = when {
            nid != null -> "notif:$nid"
            !wd.isNullOrEmpty() -> "wd:$wd:${statusOf(type)}"
            type == "payment_transfer_in" && !ref.isNullOrEmpty() -> "tr:$ref"
            type == "payment_request_in" && !ref.isNullOrEmpty() -> "rq:$ref"
            type == "payment_deposit" && !ref.isNullOrEmpty() -> "dep:$ref"
            // deposit under review / expired / failed: one alert per reference and status
            type.startsWith("payment_deposit_") && !ref.isNullOrEmpty() -> "dep:$ref:$type"
            else -> null
        }
        // the money also exists as a "credit" row in Notifications: tell the sync check it was already announced
        if (type == "payment_transfer_in" || type == "payment_deposit" || type == "credit" || type == "referral") data["amount"]?.toDoubleOrNull()?.let { markShown(ctx, "credit_amt:${it.toLong()}") }
        post(ctx, PushMsg(type, title, body, data["route"], details, dedup))
        if (ob != null) markShown(ctx, "ob:$ob")   // also when a switch is off: it must not come back as "missed"
    }

    /**
     * Everything that was sent to this member while no phone was signed in (or that FCM could not deliver).
     * Called right after sign-in, when the app opens, and on every background check. Each item is shown as a
     * normal notification; more than [MISSED_MAX] are shown as the latest ones + one summary.
     * Returns how many notifications were shown.
     */
    private const val MISSED_MAX = 8
    suspend fun fetchMissed(ctx: Context): Int {
        if (!Session.signedIn) return 0
        val app = ctx.applicationContext
        return try {
            val r = Api.call("GET", "/push/missed")
            if (!r.ok || r.cached) return 0
            val items = r.data.list("items")
            if (items.isEmpty()) return 0
            val older = items.dropLast(MISSED_MAX)
            var shown = 0
            if (older.isNotEmpty() && post(app, PushMsg("info", "MudaPay", "You missed ${older.size} more updates while you were signed out.", "Notifications"))) shown++
            for (item in items.takeLast(MISSED_MAX)) {
                val map = HashMap<String, String>()
                item.optJSONObject("data")?.let { d -> d.keys().forEach { k -> map[k] = d.optString(k) } }
                map["type"] = item.str("type")
                map["title"] = item.str("title")
                map["body"] = item.str("body")
                map["route"] = item.str("route")
                map["ob_id"] = item.int("id").toString()
                fromServer(app, map, null, null)
                shown++
            }
            shown
        } catch (e: Throwable) {
            ErrorReporter.nonFatal(e, "Push.fetchMissed")
            0
        }
    }

    /** Backwards-compatible helper. */
    fun show(ctx: Context, type: String, title: String, body: String, route: String?) =
        post(ctx, PushMsg(type, title, body, route))

    fun cancelAll(ctx: Context) {
        try { NotificationManagerCompat.from(ctx.applicationContext).cancelAll() } catch (e: Throwable) { }
    }
}

class MudaFcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Push.sync(applicationContext) }

    override fun onMessageReceived(message: RemoteMessage) {
        // Data messages arrive here whether the app is open, in the background or closed.
        Push.fromServer(applicationContext, message.data, message.notification?.title, message.notification?.body)
    }
}
