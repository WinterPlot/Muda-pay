package io.mudapay.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Asks the server what is new and turns it into notifications:
 *  - messages MudaPay wrote for the member (admin credits, referral rewards, account notices),
 *  - withdrawal status changes (approved / paid / rejected), with the amount.
 * It runs while the app is open (every ~90 s), every 15 minutes in the background, after a reboot,
 * and right after the member does something that can change the answer.
 * The first run only remembers the current state (so a new install never floods the phone).
 */
object NotifySync {
    private val lock = Mutex()
    private const val P = "mudapay_sync"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun reset(ctx: Context) { prefs(ctx).edit().clear().apply() }

    /** Runs a check in the background (used right after a withdrawal request, etc.). */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { check(app) }
    }

    private fun norm(s: String): String = when (s.lowercase()) {
        "paid", "completed" -> "paid"
        "rejected", "failed", "cancelled" -> "rejected"
        "approved", "processing" -> "approved"
        else -> "pending"
    }

    /** Returns true when something new was announced (so the open screens should reload). */
    suspend fun check(ctx: Context, forceWithdrawals: Boolean = false): Boolean {
        if (!Session.signedIn) return false
        if (!lock.tryLock()) return false
        try {
            val app = ctx.applicationContext
            val p = prefs(app)
            var changed = false
            val symbol = Session.user?.str("symbol") ?: ""

            /* 1) messages written for the member */
            val nr = Api.call("GET", "/notifications")
            if (nr.ok && !nr.cached) {
                val items = nr.data.list("items")
                val maxId = items.maxOfOrNull { it.int("id") } ?: 0
                val last = p.getInt("last_notif", -1)
                if (last >= 0 && maxId > last) {
                    val fresh = items.filter { it.int("id") > last }.sortedBy { it.int("id") }
                    for (n in fresh) {
                        val amt: Double? = if (n.isNull("amount")) null else n.dbl("amount")
                        // already announced by the action that caused it (daily bonus, share reward)
                        if (amt != null && Push.wasShown(app, "credit_amt:${amt.toLong()}", 30L * 60L * 1000L)) { changed = true; continue }
                        val type = if (n.str("type") == "credit") "credit" else "account"
                        val title = n.str("title").ifEmpty { "MudaPay" }
                        val body = n.str("message").ifEmpty { title }
                        val details = listOfNotNull(amt?.let { "Added to your balance: ${money(symbol, it)}" })
                        if (Push.post(app, PushMsg(type, title, body, "Notifications", details, dedup = "notif:${n.int("id")}"))) changed = true
                    }
                }
                if (maxId > last) p.edit().putInt("last_notif", maxId).apply()
                Session.unread = nr.data.int("unread")
            }

            /* 2) withdrawals (only while one is still open, or on the background runs) */
            val open = p.getBoolean("open_wd", true)
            if (open || forceWithdrawals || !p.contains("wd")) {
                val wr = Api.call("GET", "/withdrawals")
                if (wr.ok && !wr.cached) {
                    val known = try { JSONObject(p.getString("wd", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
                    val baseline = p.contains("wd")
                    var stillOpen = false
                    for (w in wr.data.list("items")) {
                        val id = w.int("id").toString()
                        val st = norm(w.str("status"))
                        if (st == "pending" || st == "approved") stillOpen = true
                        val prev: String? = if (known.has(id)) known.optString(id) else null
                        val isNewPending = prev == null && st == "pending"
                        if (baseline && prev != st && !isNewPending) { if (announceWithdrawal(app, w, id, st, symbol)) changed = true }
                        known.put(id, st)
                    }
                    p.edit().putString("wd", known.toString()).putBoolean("open_wd", stillOpen).apply()
                }
            }
            return changed
        } catch (e: Exception) {
            return false
        } finally {
            lock.unlock()
        }
    }

    /** After the member sends a withdrawal the next check must look at withdrawals again. */
    fun markWithdrawalOpen(ctx: Context) { prefs(ctx).edit().putBoolean("open_wd", true).apply() }

    private fun announceWithdrawal(app: Context, w: JSONObject, id: String, st: String, symbol: String): Boolean {
        val amt = w.dbl("amount")
        val method = w.str("method")
        val crypto = method == "Crypto"
        val acct = w.str("account_number").trim()
        val dest = if (crypto) w.str("crypto_currency").uppercase().ifEmpty { "crypto" } else method
        val to = if (acct.isEmpty()) null else "To: " + (if (crypto && acct.length > 12) acct.take(6) + "…" + acct.takeLast(4) else "••••" + acct.takeLast(4))
        val ref = w.str("reference").ifEmpty { id }
        val key = "wd:$id:$st"
        return when (st) {
            "paid" -> Push.post(app, PushMsg(
                "withdrawal_paid", "Withdrawal paid 🎉",
                "Your ${money(symbol, amt)} withdrawal ${if (crypto) "in $dest" else "via $dest"} was paid. Please check your account.",
                "Withdraw", listOfNotNull("Amount: ${money(symbol, amt)}", "Method: $dest", to, "Reference: #$ref", "Status: Paid"), dedup = key, subText = "Withdrawal"
            ))
            "rejected" -> Push.post(app, PushMsg(
                "withdrawal_rejected", "Withdrawal not approved",
                "Your ${money(symbol, amt)} withdrawal was not approved. The amount was returned to your balance.",
                "Withdraw", listOfNotNull("Amount: ${money(symbol, amt)}", "Method: $dest", to, "Reference: #$ref", w.str("note").takeIf { it.isNotEmpty() }?.let { "Reason: $it" }, "You can send a new request any time."), dedup = key, subText = "Withdrawal"
            ))
            "approved" -> Push.post(app, PushMsg(
                "withdrawal_update", "Withdrawal approved ✅",
                "Your ${money(symbol, amt)} withdrawal was approved. Your payout is being sent.",
                "Withdraw", listOfNotNull("Amount: ${money(symbol, amt)}", "Method: $dest", to, "Reference: #$ref", "Status: Approved"), dedup = key, subText = "Withdrawal"
            ))
            else -> false
        }
    }
}

/* ------------------------------------------------------------------ background work */
class NotifyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            NotifySync.check(applicationContext, forceWithdrawals = true)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object NotifyScheduler {
    private const val NAME = "mp_notify_sync"
    private const val NOW = "mp_notify_sync_now"
    private fun net() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Every 15 minutes (the shortest period Android allows). Survives app restarts and reboots. */
    fun schedule(ctx: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES).setConstraints(net()).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Throwable) { }
    }

    fun syncSoon(ctx: Context) {
        try {
            val req = OneTimeWorkRequestBuilder<NotifyWorker>().setConstraints(net()).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(NOW, androidx.work.ExistingWorkPolicy.REPLACE, req)
        } catch (e: Throwable) { }
    }

    fun cancel(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            wm.cancelUniqueWork(NAME)
            wm.cancelUniqueWork(NOW)
        } catch (e: Throwable) { }
    }
}

/**
 * Starts MudaPay's notification machinery after the phone restarts (and after an app update):
 * channels, the 15-minute background check, a first check right away and the push token refresh.
 * Android does not allow an app to open its screen by itself at boot, so MudaPay starts quietly in the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        Push.init(app)
        Push.createChannels(app)
        if (!Session.signedIn) return
        NotifyScheduler.schedule(app)
        NotifyScheduler.syncSoon(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Push.syncNow(app)
                NotifySync.check(app, forceWithdrawals = true)
            } catch (e: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}
