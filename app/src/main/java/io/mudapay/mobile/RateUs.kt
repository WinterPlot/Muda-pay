package io.mudapay.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.launch

/**
 * "Rate MudaPay on Google Play".
 *
 * The small Google Play rating sheet (Play In-App Review API) is offered right after something good happened
 * (money sent, task completed, share approved), and ONLY when it will not annoy the member:
 *   - the member can switch it off in Settings > Preferences (or tap "Don't ask again" in the box itself);
 *   - never in the first days after install, never in the first minute of a session, never on top of another dialog;
 *   - at least 3 good moments since the last time, at least 45 days between two requests, 3 requests at most in total;
 *   - once the member has rated (or chose "Don't ask again"), it is never shown again.
 * Google also limits how often the Play sheet can appear; when it cannot be shown, the Play Store page opens instead.
 */
object RateUs {
    private const val PREFS = "mudapay_rate"
    private const val MIN_DAYS_SINCE_INSTALL = 3
    private const val MIN_GOOD_MOMENTS = 3
    private const val MIN_DAYS_BETWEEN = 45
    private const val MAX_REQUESTS = 3
    private const val MIN_SESSION_MS = 60_000L
    private const val SHOW_DELAY_MS = 2_500L
    private const val DAY_MS = 24L * 3600L * 1000L

    private var prefs: SharedPreferences? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val sessionStart = SystemClock.elapsedRealtime()
    private var scheduled = false

    /** Settings switch: "Ask me to rate the app". */
    var autoPrompt by mutableStateOf(true)
        private set
    /** The member already rated (or went to the Play page from our box). */
    var rated by mutableStateOf(false)
        private set
    /** The box is on screen. */
    var visible by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        val c = ctx.applicationContext
        app = c
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        autoPrompt = p.getBoolean("auto", true)
        rated = p.getBoolean("rated", false)
    }

    fun changeAutoPrompt(v: Boolean) { autoPrompt = v; prefs?.edit()?.putBoolean("auto", v)?.apply(); if (!v) visible = false }

    /** Call when something good just happened for the member (a successful payment, a reward). Never blocks, never throws. */
    fun positive() {
        try {
            val p = prefs ?: return
            p.edit().putInt("good", p.getInt("good", 0) + 1).apply()
            if (!eligible() || scheduled) return
            scheduled = true
            // a short pause: the member sees the success first, the question comes after
            main.postDelayed({
                scheduled = false
                if (eligible() && screenIsFree()) {
                    prefs?.edit()?.putInt("shown", (prefs?.getInt("shown", 0) ?: 0) + 1)?.putLong("last", System.currentTimeMillis())?.putInt("good", 0)?.apply()
                    visible = true
                }
            }, SHOW_DELAY_MS)
        } catch (_: Throwable) { }
    }

    private fun eligible(): Boolean {
        val p = prefs ?: return false
        val c = app ?: return false
        if (!autoPrompt || rated) return false
        if (!Session.signedIn || visible) return false
        if (SystemClock.elapsedRealtime() - sessionStart < MIN_SESSION_MS) return false
        if (p.getInt("good", 0) < MIN_GOOD_MOMENTS) return false
        if (p.getInt("shown", 0) >= MAX_REQUESTS) return false
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0L) < MIN_DAYS_BETWEEN * DAY_MS) return false
        val installed = try { c.packageManager.getPackageInfo(c.packageName, 0).firstInstallTime } catch (_: Throwable) { now }
        return now - installed >= MIN_DAYS_SINCE_INSTALL * DAY_MS
    }

    /** Not on top of a lock screen, another message, a forced update, a blocked account, a video, or while the connection is failing. */
    private fun screenIsFree(): Boolean {
        val c = app ?: return false
        return !AppLock.locked && Ui.title == null && !Ui.immersive && Session.blockedMessage == null &&
            !Offline.offline && AppUpdate.info?.let { AppUpdate.shouldPrompt(c) || it.mode == "forced" } != true
    }

    /** "Maybe later": asked again only after 3 more good moments and 45 days. */
    fun later() { visible = false }

    /** "Don't ask again": switches the Settings option off too. */
    fun never() { visible = false; changeAutoPrompt(false) }

    private fun markRated() { rated = true; visible = false; prefs?.edit()?.putBoolean("rated", true)?.apply() }

    /** The small Google Play rating sheet; if Google cannot show it (limit reached, no Play Store...), the Play Store page opens. */
    suspend fun rateInApp(act: Activity) {
        markRated()
        val t0 = SystemClock.elapsedRealtime()
        val shown = try {
            val manager = ReviewManagerFactory.create(act)
            val info = manager.requestReview()
            manager.launchReview(act, info)
            true
        } catch (_: Throwable) { false }
        // the sheet stays open while the member rates; coming back at once means Google showed nothing
        if (!shown || SystemClock.elapsedRealtime() - t0 < 1_200L) openStorePage(act)
    }

    /** Opens the MudaPay page of the Play Store (also used by the Google Play build to update the app). */
    fun openListing(ctx: Context) {
        val id = ctx.packageName
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            openUrl(ctx, "https://play.google.com/store/apps/details?id=$id")
        }
    }

    /** Settings button "Rate on Google Play": always works, no Google limit. */
    fun openStorePage(ctx: Context) { openListing(ctx); markRated() }
}

/** The "Rate us" box. Placed once, inside the signed-in app (see MudaPayApp). */
@Composable
fun RateUsHost() {
    if (!RateUs.visible) return
    val act = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { RateUs.later() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(painterResource(R.drawable.ic_play_store), null, Modifier.size(52.dp))
            Spacer(Modifier.height(12.dp))
            Text(L("Enjoying MudaPay?"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 19.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                L("A quick rating on Google Play helps us a lot and takes only a few seconds."),
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { Image(painterResource(R.drawable.ic_rate_star), null, Modifier.size(30.dp)) }
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(L("RATE ON GOOGLE PLAY"), { if (act != null) scope.launch { RateUs.rateInApp(act) } else RateUs.later() })
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { RateUs.later() }) { Text(L("Maybe later"), color = Muted, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = { RateUs.never() }) { Text(L("Don't ask again"), color = Muted, fontSize = 12.sp) }
        }
    }
}
