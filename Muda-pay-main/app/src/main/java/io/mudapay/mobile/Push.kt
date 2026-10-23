package io.mudapay.mobile

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import kotlin.coroutines.resume

/** Which kinds of push the member wants (chosen in Settings). All on by default. */
object PushPrefs {
    val KEYS = listOf("payment", "withdrawal", "bonus", "streak")
    val LABELS = mapOf(
        "payment" to ("Payment approved" to "When your activation payment is reviewed"),
        "withdrawal" to ("Withdrawal updates" to "When a payout is approved, paid or rejected"),
        "bonus" to ("Daily bonus ready" to "A reminder that today's bonus can be claimed"),
        "streak" to ("Streak at risk" to "A reminder before your login streak resets")
    )
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("mudapay_push", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String) = p(ctx).getBoolean(key, true)
    fun set(ctx: Context, key: String, v: Boolean) { p(ctx).edit().putBoolean(key, v).apply() }
    fun asked(ctx: Context) = p(ctx).getBoolean("permission_asked", false)
    fun markAsked(ctx: Context) { p(ctx).edit().putBoolean("permission_asked", true).apply() }
}

object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun firebaseReady(ctx: Context): Boolean = try { FirebaseApp.getApps(ctx).isNotEmpty() } catch (e: Throwable) { false }
    fun notificationsAllowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        PushPrefs.KEYS.forEach { k ->
            val (name, desc) = PushPrefs.LABELS.getValue(k)
            nm.createNotificationChannel(NotificationChannel("mp_$k", name, NotificationManager.IMPORTANCE_HIGH).apply { description = desc })
        }
        nm.createNotificationChannel(NotificationChannel("mp_general", "General", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private suspend fun token(): String? = suspendCancellableCoroutine { c ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> c.resume(if (t.isSuccessful) t.result else null) }
        } catch (e: Throwable) { c.resume(null) }
    }

    /** Sends this phone's token + chosen categories to the server (idempotent upsert). Safe to call often. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        if (!Session.signedIn || !firebaseReady(app)) return
        scope.launch {
            val t = token() ?: return@launch
            val cats = JSONObject().apply { PushPrefs.KEYS.forEach { put(it, PushPrefs.get(app, it)) } }
            val body = JSONObject().put("token", t).put("platform", "android").put("app_version", "2.1.0").put("categories", cats)
            Api.call("POST", "/devices", body)
        }
    }

    private fun keyFor(type: String) = when (type) {
        "payment_approved", "payment_rejected" -> "payment"
        "withdrawal_paid", "withdrawal_update", "withdrawal_rejected" -> "withdrawal"
        "daily_bonus" -> "bonus"
        "streak_risk" -> "streak"
        else -> null
    }

    private fun defaultRoute(type: String) = when (keyFor(type)) { "withdrawal" -> "Withdraw"; else -> "Home" }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, type: String, title: String, body: String, route: String?) {
        val key = keyFor(type)
        if (key != null && !PushPrefs.get(ctx, key)) return
        if (!notificationsAllowed(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra("route", route ?: defaultRoute(type))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, "mp_" + (key ?: "general"))
            .setSmallIcon(R.drawable.ic_stat_mudapay)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(pi).build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

class MudaFcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Push.sync(applicationContext) }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val body = d["body"] ?: message.notification?.body ?: return
        val title = d["title"] ?: message.notification?.title ?: "MudaPay"
        Push.show(applicationContext, d["type"] ?: "general", title, body, d["route"])
    }
}
