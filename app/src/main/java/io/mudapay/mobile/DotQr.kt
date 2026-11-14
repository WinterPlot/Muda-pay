package io.mudapay.mobile

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot

/**
 * Dot-style QR code: every data module is a round dot, the three corner "eyes" are rounded.
 * Built on ZXing's encoder (error correction level H) and drawn by hand, so it needs no extra library.
 *
 * The geometry (dot size, eye shapes, clear zone) was checked by rendering many codes and decoding them with
 * ZXing at several sizes, rotations and blur levels; round inner eyes and smaller dots scanned noticeably worse,
 * so these values are deliberately conservative. Do not shrink the dots or round the inner eyes into circles.
 */
object DotQr {
    private const val QUIET = 3          // white border, in modules
    private const val DOT = 0.50f        // dot radius in modules (0.5 = neighbouring dots just touch)
    private const val RING_ARC = 3.8f    // corner diameter of the outer eye ring, in modules
    private const val INNER_ARC = 1.2f   // corner diameter of the inner eye square, in modules
    private const val LOGO_SHARE = 0.20f // width of the centre badge compared with the code

    private val INK = 0xFF0F172A.toInt()
    private val BADGE = 0xFF00BF8E.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    private fun inEye(x: Int, y: Int, n: Int): Boolean = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)

    /** Square bitmap, [size] px wide, or null when the text is empty or cannot be encoded. */
    fun render(text: String, size: Int = 720, logo: Boolean = true, ink: Int = INK, badge: Int = BADGE): Bitmap? {
        if (text.isEmpty() || size < 64) return null
        return try {
            val m = Encoder.encode(text, ErrorCorrectionLevel.H, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
            val n = m.width
            val unit = size.toFloat() / (n + 2 * QUIET)
            val off = QUIET * unit
            val center = size / 2f
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(WHITE)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.FILL }
            val showLogo = logo && n >= 25
            val logoR = if (showLogo) n * unit * LOGO_SHARE / 2f else 0f

            // data modules -> dots (skipping the eyes and the clear zone under the badge)
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (m.get(x, y).toInt() != 1) continue
                    if (inEye(x, y, n)) continue
                    val cx = off + (x + 0.5f) * unit
                    val cy = off + (y + 0.5f) * unit
                    if (showLogo && hypot(cx - center, cy - center) < logoR + unit * 0.6f) continue
                    c.drawCircle(cx, cy, unit * DOT, fill)
                }
            }

            // the three eyes: rounded ring + rounded inner square
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = unit }
            for ((ex, ey) in listOf(0 to 0, (n - 7) to 0, 0 to (n - 7))) {
                val x0 = off + ex * unit
                val y0 = off + ey * unit
                c.drawRoundRect(RectF(x0 + unit * 0.5f, y0 + unit * 0.5f, x0 + unit * 6.5f, y0 + unit * 6.5f), unit * RING_ARC / 2f, unit * RING_ARC / 2f, ring)
                c.drawRoundRect(RectF(x0 + unit * 2f, y0 + unit * 2f, x0 + unit * 5f, y0 + unit * 5f), unit * INNER_ARC / 2f, unit * INNER_ARC / 2f, fill)
            }

            // MudaPay badge in the middle (inside the cleared zone; level H error correction covers it)
            if (showLogo) {
                val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
                c.drawCircle(center, center, logoR, whitePaint)
                val br = logoR * 0.82f
                val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badge }
                c.drawCircle(center, center, br, green)
                val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = br * 1.15f
                }
                val fm = letter.fontMetrics
                c.drawText("M", center, center - (fm.ascent + fm.descent) / 2f, letter)
            }
            bmp
        } catch (e: Throwable) {
            ErrorReporter.nonFatal(e, "DotQr.render")
            null
        }
    }

    fun image(text: String, size: Int = 720): ImageBitmap? = render(text, size)?.asImageBitmap()

    /** A ready-to-post picture: the member's QR code with name, number and (optionally) the requested amount. */
    fun shareCard(qr: Bitmap, name: String, phone: String, amountLine: String?): Bitmap {
        val w = 1080
        val h = if (amountLine != null) 1560 else 1480
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFF1F5F9.toInt())
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
        c.drawRoundRect(RectF(50f, 50f, w - 50f, h - 50f), 56f, 56f, card)
        fun text(s: String, y: Float, sizePx: Float, col: Int, bold: Boolean) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col; textAlign = Paint.Align.CENTER; textSize = sizePx
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            c.drawText(s, w / 2f, y, p)
        }
        text("MudaPay", 190f, 78f, BADGE, true)
        text(L("Scan to pay"), 262f, 44f, 0xFF64748B.toInt(), false)
        val frame = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(qr, null, RectF(90f, 320f, 990f, 1220f), frame)
        text(name.take(26), 1310f, 58f, INK, true)
        text(phone, 1378f, 46f, 0xFF64748B.toInt(), false)
        if (amountLine != null) text(amountLine, 1460f, 54f, BADGE, true)
        return bmp
    }

    /** Saves [bitmap] in cache/shared (already covered by the FileProvider) and opens the share sheet. */
    fun sharePicture(ctx: Context, bitmap: Bitmap, caption: String): Boolean = try {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "mudapay_qr.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri("MudaPay QR", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "MudaPay"))
        true
    } catch (e: Throwable) {
        ErrorReporter.nonFatal(e, "DotQr.share")
        false
    }
}
