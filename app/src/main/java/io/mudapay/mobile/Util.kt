package io.mudapay.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    "ng" to "Nigeria", "za" to "South Africa", "gh" to "Ghana", "cd" to "DR Congo", "zw" to "Zimbabwe"
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
    fun notify(t: String, m: String) { title = t; message = m }
    fun clear() { title = null }
}
