package io.mudapay.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/** Same style as mp_fmt_money(): whole numbers without decimals. */
fun money(symbol: String, amount: Double): String {
    val whole = amount == Math.floor(amount)
    val f = if (whole) String.format(Locale.US, "%,.0f", amount) else String.format(Locale.US, "%,.2f", amount)
    return "$symbol $f"
}

val COUNTRIES = listOf(
    "ke" to "Kenya", "tz" to "Tanzania", "ug" to "Uganda", "bi" to "Burundi", "rw" to "Rwanda",
    "ng" to "Nigeria", "za" to "South Africa", "gh" to "Ghana", "cd" to "DR Congo", "zw" to "Zimbabwe",
    // French-speaking West and Central Africa
    "sn" to "Senegal", "ci" to "Ivory Coast", "cm" to "Cameroon", "ml" to "Mali",
    "bf" to "Burkina Faso", "bj" to "Benin", "tg" to "Togo", "ne" to "Niger",
    "gn" to "Guinea", "cg" to "Congo (Brazzaville)", "ga" to "Gabon", "td" to "Chad",
    // Southern and East Africa
    "zm" to "Zambia", "mz" to "Mozambique", "mg" to "Madagascar", "na" to "Namibia",
    "ls" to "Lesotho", "sz" to "Eswatini", "sc" to "Seychelles", "ss" to "South Sudan",
    // Other West Africa and Angola
    "gm" to "Gambia", "sl" to "Sierra Leone", "lr" to "Liberia", "ao" to "Angola"
)
fun countryName(code: String): String = COUNTRIES.firstOrNull { it.first == code.lowercase() }?.second ?: code.uppercase()
fun flagUrl(code: String): String = "${Config.SITE}/flags/${code.lowercase()}.png"

fun newIdemKey(): String = UUID.randomUUID().toString().replace("-", "")

fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun shareText(ctx: Context, text: String) {
    try {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        ctx.startActivity(Intent.createChooser(i, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { Ui.notify("Share", "No app is available to share this.") }
}

fun shareWhatsApp(ctx: Context, text: String) {
    try {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; setPackage("com.whatsapp"); putExtra(Intent.EXTRA_TEXT, text) }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        openUrl(ctx, "https://wa.me/?text=" + java.net.URLEncoder.encode(text, "UTF-8"))
    }
}

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { Ui.notify("Open link", "No browser is available.") }
}

/** Decode, shrink to <= 1600 px and re-encode as JPEG (keeps uploads small and accepted by the server). */
fun readImageForUpload(ctx: Context, uri: Uri): ByteArray? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        out.toByteArray()
    } catch (e: Exception) { null }
}

/** App-wide message dialog. */
object Ui {
    var title by mutableStateOf<String?>(null)
    var message by mutableStateOf("")
    /** true while a full-screen black player is shown (hides the bottom bar, light status icons). */
    var immersive by mutableStateOf(false)
    /** Goes up every time the member pulls to refresh (or something changed); every loaded screen reloads on it. */
    var refreshTick by mutableIntStateOf(0)
    /** Screen asked for by a push notification tap; consumed by the navigation host. */
    var pendingRoute by mutableStateOf<String?>(null)
    /** Withdrawal opened from the list, shown on the detail / receipt screen. */
    var selectedWithdrawal by mutableStateOf<JSONObject?>(null)
    fun notify(t: String, m: String) { title = t; message = m }
    fun clear() { title = null }
}

/**
 * Pull-to-refresh / refresh button: reloads the profile and balance, makes every open screen reload,
 * sends saved offline actions and checks for new notifications. Returns false when the server could not be reached.
 */
suspend fun refreshEverything(): Boolean {
    Offline.clearFailure()
    val me = Api.call("GET", "/me")
    val ok = me.ok && !me.cached
    if (ok) Session.updateUser(me.data)
    Ui.refreshTick++
    if (ok) {
        Push.appOrNull()?.let { NotifySync.check(it, forceWithdrawals = true) }
        if (PendingQueue.size > 0) PendingQueue.flush()
    }
    kotlinx.coroutines.delay(600)
    return ok
}
