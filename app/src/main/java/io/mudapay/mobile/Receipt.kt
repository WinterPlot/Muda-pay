package io.mudapay.mobile

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ====================================================================================================
 * Shareable receipts: a branded PNG image or PDF of one transfer, deposit or withdrawal, ready for WhatsApp.
 * Everything is drawn on the phone (no server call), from data the app already holds. Account numbers are masked.
 * ==================================================================================================== */

/** What a receipt says. [kind] and the row labels are English keys: they go through L() when drawn. */
class ReceiptData(
    val kind: String,
    val amount: String,
    /** "+", "-" or "" in front of the amount. */
    val sign: String,
    val status: String,
    /** true = done (green), false = failed (red), null = in progress (blue). */
    val ok: Boolean?,
    val rows: List<Pair<String, String>>,
    val reference: String,
    val date: String,
    /** Header line under the brand name (English key, goes through L()). */
    val title: String = "Payment receipt"
) {
    val caption: String get() = "MudaPay – ${L(kind)} • $sign$amount" + if (reference.isNotEmpty()) " • $reference" else ""
}

/** Turns the JSON the app already has into a [ReceiptData]. Every field is optional: a missing one is simply left out. */
object ReceiptBuilder {
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    private fun sym() = Session.user?.str("symbol") ?: ""
    private fun me() = Session.user?.str("full_name") ?: ""
    private fun mask(a: String, crypto: Boolean): String {
        val t = a.trim()
        if (t.isEmpty()) return ""
        return if (crypto && t.length > 12) t.take(6) + "…" + t.takeLast(4) else "••••" + t.takeLast(4)
    }
    private fun rows(vararg p: Pair<String, String?>): List<Pair<String, String>> =
        p.mapNotNull { (k, v) -> if (v.isNullOrBlank()) null else k to v }

    /** Result of POST /transfers (shown right after sending). */
    fun transferSent(d: JSONObject, symbol: String = sym()): ReceiptData {
        val who = d.optJSONObject("recipient")
        val cross = d.bool("cross_border")
        val rc = who?.str("country").orEmpty()
        return ReceiptData(
            "Money sent", money(symbol, d.dbl("amount")), "-", "Completed", true,
            rows(
                "From" to me().ifEmpty { null },
                "Business" to d.str("business"),
                "To" to who?.str("name"),
                "Phone" to who?.str("phone"),
                "Country" to if (cross && rc.isNotEmpty()) countryName(rc) else null,
                "They received" to if (cross) money(d.str("receive_symbol"), d.dbl("receive_amount")) else null,
                "Fee" to if (cross || d.dbl("fee") > 0) money(symbol, d.dbl("fee")) else null,
                "Rate" to if (cross && d.dbl("rate") > 0) "1 $symbol = ${fmtRate(d.dbl("rate"))} ${d.str("receive_symbol")}" else null,
                "Note" to d.str("note")
            ),
            d.str("reference"), d.str("created_at").take(16).ifEmpty { now() }
        )
    }

    /** One transfer from GET /transfers or one "transfer_in" / "transfer_out" row of the history. */
    fun transfer(t: JSONObject, out: Boolean, symbol: String = sym()): ReceiptData {
        val who = t.optJSONObject("counterparty")
        val cross = t.bool("cross_border")
        val sy = t.str("symbol").ifEmpty { symbol }
        val rc = who?.str("country").orEmpty()
        val name = who?.str("name").orEmpty()
        val phone = who?.str("phone").orEmpty()
        return ReceiptData(
            if (out) "Money sent" else "Money received", money(sy, t.dbl("amount")), if (out) "-" else "+", "Completed", true,
            rows(
                (if (out) "From" else "To") to me().ifEmpty { null },
                (if (out) "To" else "From") to name.ifEmpty { "MudaPay" },
                "Business" to (if (out) who?.str("business") else null),
                "Received by" to (if (!out) Merchant.name.ifEmpty { null } else null),
                "Phone" to phone,
                "Country" to if (cross && rc.isNotEmpty()) countryName(rc) else null,
                (if (out) "They received" else "They sent") to if (cross && t.str("counter_symbol").isNotEmpty()) money(t.str("counter_symbol"), t.dbl("counter_amount")) else null,
                "Fee" to if (out && t.dbl("fee") > 0) money(sy, t.dbl("fee")) else null,
                "Merchant fee" to if (!out && t.dbl("merchant_fee") > 0) money(sy, t.dbl("merchant_fee")) else null,
                "Note" to t.str("note")
            ),
            t.str("reference"), t.str("created_at").take(16).ifEmpty { now() }
        )
    }

    fun withdrawal(w: JSONObject, symbol: String = sym()): ReceiptData {
        val st = w.str("status").lowercase()
        val paid = st in setOf("paid", "completed")
        val failed = st in setOf("rejected", "failed", "cancelled")
        val crypto = w.str("method") == "Crypto"
        val agent = w.str("agent_code").isNotEmpty()
        val acct = w.str("account_number")
        val ref = w.str("reference").ifEmpty { w.str("id") }
        return ReceiptData(
            "Withdrawal", money(symbol, w.dbl("amount")), "-", st.replaceFirstChar { it.uppercase() }.ifEmpty { "Pending" },
            if (paid) true else if (failed) false else null,
            rows(
                "Account holder" to me().ifEmpty { null },
                "Method" to if (crypto) "Crypto ${w.str("crypto_currency").uppercase()}".trim() else w.str("method"),
                (if (w.str("method") == "Bureau") "Bureau" else "Agent") to if (agent) "${w.str("agent_name")} (${w.str("agent_code")})" else null,
                "Cash" to if (agent && !w.isNull("cash_amount")) money(symbol, w.dbl("cash_amount")) else null,
                "To account" to if (!agent) mask(acct, crypto) else null,
                "Value" to if (crypto && w.dbl("amount_usd") > 0) String.format(Locale.US, "%.2f USD", w.dbl("amount_usd")) else null,
                "Requested" to w.str("created_at").take(16),
                "Paid" to if (paid) w.str("paid_at").take(16) else null,
                "Reason" to if (failed) w.str("note") else null
            ),
            if (ref.isNotEmpty() && w.str("reference").isEmpty()) "#$ref" else ref,
            (if (paid && w.str("paid_at").isNotEmpty()) w.str("paid_at") else w.str("created_at")).take(16).ifEmpty { now() }
        )
    }

    /** One filled trade from GET /trade/orders: a buy, a sell, or one side of a coin-to-coin conversion. */
    fun trade(o: JSONObject): ReceiptData {
        val sy = o.str("symbol_local").ifEmpty { sym() }
        val coin = o.str("symbol")
        val buy = o.str("side") == "buy"
        val conv = o.str("type") == "convert"
        val total = if (o.isNull("total")) 0.0 else o.dbl("total")
        val pnl = if (o.isNull("realized")) null else o.dbl("realized")
        val kind = if (conv) (if (buy) "Coin received" else "Coin conversion") else if (buy) "Coin bought" else "Coin sold"
        val amount = if (conv) o.str("qty") + " " + coin else money(sy, total)
        val sign = if (conv) (if (buy) "+" else "-") else if (buy) "-" else "+"
        val done = o.str("status") == "filled"
        return ReceiptData(
            kind, amount, sign, if (done) "Completed" else o.str("status").replaceFirstChar { it.uppercase() }, if (done) true else if (o.str("status") in setOf("canceled", "expired", "rejected")) false else null,
            rows(
                "Account holder" to me().ifEmpty { null },
                "Coin" to coin,
                "Quantity" to o.str("qty") + " " + coin,
                (if (buy) "Converted from" else "Converted to") to (if (conv) o.str("note") else null),
                "Price per coin" to if (!conv && !o.isNull("price_local")) money(sy, o.dbl("price_local")) else null,
                "Value" to if (!o.isNull("gross")) money(sy, o.dbl("gross")) else null,
                "Fee" to if (!o.isNull("fee") && o.dbl("fee") > 0) money(sy, o.dbl("fee")) else null,
                (if (buy) "You paid" else "You received") to if (!conv && !o.isNull("total")) money(sy, total) else null,
                "Profit / loss" to if (pnl != null) (if (pnl >= 0) "+" else "-") + money(sy, Math.abs(pnl)) else null,
                "Order" to if (o.str("type") == "limit") "Limit" else if (conv) "Convert" else "Market"
            ),
            o.str("reference").ifEmpty { "TR-" + o.str("id") }, o.str("filled_at").ifEmpty { o.str("created_at") }.take(16).ifEmpty { now() },
            "Trade receipt"
        )
    }

    /** One row of the deposit list (GET /deposits). */
    fun deposit(t: JSONObject, symbol: String = sym(), method: String? = null): ReceiptData {
        val credited = t.bool("credited")
        val st = t.str("status").lowercase()
        val failed = st in setOf("failed", "expired", "refunded")
        val amount = listOf(if (credited) t.dbl("credited_amount") else 0.0, t.dbl("amount_local"), t.dbl("amount")).firstOrNull { it > 0 } ?: 0.0
        return ReceiptData(
            "Deposit", money(symbol, amount), if (credited) "+" else "", t.str("status_text").ifEmpty { if (credited) "Credited" else "Pending" },
            if (credited) true else if (failed) false else null,
            rows(
                "Account holder" to me().ifEmpty { null },
                "Method" to (method ?: t.str("method").ifEmpty { t.str("coin").uppercase() }).ifEmpty { null },
                "Value" to if (t.dbl("price_usd") > 0) String.format(Locale.US, "%,.2f USD", t.dbl("price_usd")) else null
            ),
            t.str("reference"), t.str("created_at").take(16).ifEmpty { now() }
        )
    }

    /** One row of the history screen (GET /transactions). Returns null for types that have no receipt (rewards, bonuses…). */
    fun transaction(t: JSONObject, symbol: String = sym()): ReceiptData? {
        val type = t.str("type")
        return when (type) {
            "transfer_out" -> transfer(t, true, symbol)
            "transfer_in" -> transfer(t, false, symbol)
            "withdrawal", "deposit" -> {
                val out = type == "withdrawal"
                val sy = t.str("symbol").ifEmpty { symbol }
                ReceiptData(
                    if (out) "Withdrawal" else "Deposit", money(sy, t.dbl("amount")), if (out) "-" else "+", "Completed", true,
                    rows("Account holder" to me().ifEmpty { null }, "Details" to t.str("description")),
                    t.str("reference"), t.str("created_at").take(16).ifEmpty { now() }
                )
            }
            else -> null
        }
    }

    /**
     * Built from what a notification says ("Amount: …", "To: …", "Reference: …"), so a tap on "You sent …" can offer the receipt.
     * Returns null when the message has no amount.
     */
    fun fromMessage(m: InboxMsg): ReceiptData? {
        val kv = m.keyValues()
        val amount = kv.firstOrNull { it.first.equals("Amount", true) }?.second ?: return null
        val t = m.type
        val (kind, sign) = when {
            t == "payment_transfer_out" -> "Money sent" to "-"
            t == "payment_transfer_in" -> "Money received" to "+"
            t == "payment_deposit" -> "Deposit" to "+"
            t.startsWith("withdrawal") -> "Withdrawal" to "-"
            else -> return null
        }
        val status = kv.firstOrNull { it.first.equals("Status", true) }?.second
            ?: if (t == "withdrawal_paid") "Paid" else if (t == "withdrawal_rejected") "Rejected" else "Completed"
        val ok: Boolean? = when {
            status.contains("reject", true) || status.contains("fail", true) || t == "withdrawal_rejected" -> false
            status.contains("pending", true) || status.contains("approved", true) || status.contains("review", true) -> null
            else -> true
        }
        val skip = setOf("amount", "status", "reference", "new balance")
        val extra = kv.filter { it.first.lowercase() !in skip }
        val ref = kv.firstOrNull { it.first.equals("Reference", true) }?.second.orEmpty()
        return ReceiptData(
            kind, amount, sign, status, ok,
            (listOfNotNull(me().ifEmpty { null }?.let { (if (kind == "Money received") "To" else if (kind == "Money sent") "From" else "Account holder") to it }) + extra),
            ref, m.time.ifEmpty { now() }
        )
    }
}

/* ------------------------------------------------------------------ drawing */
object Receipt {
    private const val W = 360f                 // logical width; the PNG is drawn at 3x (1080 px), the PDF at 1x (5 in)
    private const val PNG_SCALE = 3f
    private val NAVY = 0xFF0B1F3A.toInt()
    private val GREEN = 0xFF0B8F6A.toInt()
    private val GREEN_DARK = 0xFF066B50.toInt()
    private val RED = 0xFFEF4444.toInt()
    private val BLUE = 0xFF2563EB.toInt()
    private val INK = 0xFF1E293B.toInt()
    private val MUTED = 0xFF64748B.toInt()
    private val LINE = 0xFFE2E8F0.toInt()
    private val PAGE = 0xFFF1F5F9.toInt()

    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; textAlign = align
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    /** Word-wraps [s] to [maxW]; a single word longer than the line is cut. */
    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                var w = word
                while (p.measureText(w) > maxW) {   // very long token (account, address): hard cut
                    val n = p.breakText(w, true, maxW, null).coerceAtLeast(1)
                    if (line.isNotEmpty()) { out.add(line); line = "" }
                    out.add(w.substring(0, n)); w = w.substring(n)
                }
                val tryLine = if (line.isEmpty()) w else "$line $w"
                if (p.measureText(tryLine) <= maxW) line = tryLine else { out.add(line); line = w }
            }
            out.add(line)
        }
        return out.ifEmpty { listOf("") }
    }

    /**
     * Lays the receipt out and, when [c] is not null, draws it. Returns the total height.
     * Two passes: the first (c = null) measures, the second draws the card background at the right height.
     */
    private fun layout(c: Canvas?, d: ReceiptData, totalH: Float): Float {
        val m = 16f
        val inner = 24f
        val cardL = m; val cardR = W - m
        val tone = when (d.ok) { true -> GREEN; false -> RED; null -> BLUE }
        val toneDark = when (d.ok) { true -> GREEN_DARK; false -> RED; null -> BLUE }

        if (c != null) {
            c.drawColor(PAGE)
            val card = RectF(cardL, m, cardR, totalH - m)
            c.drawRoundRect(card, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        }

        // ---- header band
        val top = m
        val bandH = 92f
        if (c != null) {
            val clip = Path().apply { addRoundRect(RectF(cardL, top, cardR, totalH - m), 22f, 22f, Path.Direction.CW) }
            c.save()
            c.clipPath(clip)
            val band = Paint().apply { shader = LinearGradient(cardL, top, cardR, top + bandH, NAVY, 0xFF14365C.toInt(), Shader.TileMode.CLAMP) }
            c.drawRect(cardL, top, cardR, top + bandH, band)
            c.restore()
            c.drawText("MudaPay", cardL + inner, top + 44f, paint(24f, 0xFFFFFFFF.toInt(), true))
            c.drawText(L(d.title), cardL + inner, top + 64f, paint(12f, 0xCCFFFFFF.toInt()))
            val dt = paint(11f, 0xCCFFFFFF.toInt(), false, Paint.Align.RIGHT)
            c.drawText(d.date, cardR - inner, top + 44f, dt)
        }

        // ---- status badge sitting on the edge of the band
        val cx = W / 2f
        val cy = top + bandH
        val r = 28f
        if (c != null) {
            c.drawCircle(cx, cy, r + 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tone })
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4.2f
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            when (d.ok) {
                true -> c.drawPath(Path().apply { moveTo(cx - 11f, cy + 1f); lineTo(cx - 3f, cy + 9f); lineTo(cx + 12f, cy - 8f) }, stroke)
                false -> {
                    c.drawLine(cx - 9f, cy - 9f, cx + 9f, cy + 9f, stroke)
                    c.drawLine(cx + 9f, cy - 9f, cx - 9f, cy + 9f, stroke)
                }
                null -> {   // clock
                    c.drawCircle(cx, cy, 13f, stroke)
                    c.drawLine(cx, cy, cx, cy - 8f, stroke)
                    c.drawLine(cx, cy, cx + 6f, cy + 3f, stroke)
                }
            }
        }

        var y = cy + r + 30f
        // ---- kind, amount, status
        c?.drawText(softCase(L(d.kind)).uppercase(Locale.ROOT), cx, y, paint(11.5f, MUTED, true, Paint.Align.CENTER).apply { letterSpacing = 0.12f })
        y += 38f
        val amountPaint = paint(34f, INK, true, Paint.Align.CENTER)
        // shrink very long amounts so they always fit
        val fullAmount = d.sign + d.amount
        while (amountPaint.measureText(fullAmount) > cardR - cardL - 2 * inner && amountPaint.textSize > 18f) amountPaint.textSize -= 1f
        c?.drawText(fullAmount, cx, y, amountPaint)
        y += 26f
        val pillText = L(d.status)
        val pillP = paint(12f, toneDark, true, Paint.Align.CENTER)
        val pillW = pillP.measureText(pillText) + 28f
        if (c != null) {
            c.drawRoundRect(RectF(cx - pillW / 2, y - 15f, cx + pillW / 2, y + 7f), 11f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tone; alpha = 36 })
            c.drawText(pillText, cx, y, pillP)
        }
        y += 30f

        fun dashed() {
            if (c != null) c.drawLine(cardL + inner, y, cardR - inner, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = LINE; strokeWidth = 1.4f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
            })
        }
        dashed(); y += 24f

        // ---- rows: label on the left, value on the right (wrapped, right-aligned)
        val labelP = paint(12f, MUTED)
        val valueP = paint(13f, INK, true, Paint.Align.RIGHT)
        val valueMaxW = (cardR - cardL - 2 * inner) * 0.62f
        for ((k, v) in d.rows) {
            val lines = wrap(v, valueP, valueMaxW)
            c?.drawText(L(k), cardL + inner, y, labelP)
            lines.forEachIndexed { i, ln -> c?.drawText(ln, cardR - inner, y + i * 17f, valueP) }
            y += lines.size * 17f + 11f
        }
        if (d.rows.isNotEmpty()) { y += 2f; dashed(); y += 24f }

        // ---- reference
        if (d.reference.isNotEmpty()) {
            c?.drawText(L("Reference"), cardL + inner, y, labelP)
            val refLines = wrap(d.reference, paint(13f, INK, true, Paint.Align.RIGHT).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }, valueMaxW)
            val refP = paint(12.5f, INK, true, Paint.Align.RIGHT).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
            refLines.forEachIndexed { i, ln -> c?.drawText(ln, cardR - inner, y + i * 16f, refP) }
            y += refLines.size * 16f + 11f
        }
        c?.drawText(L("Date"), cardL + inner, y, labelP)
        c?.drawText(d.date, cardR - inner, y, valueP)
        y += 30f

        // ---- footer
        if (c != null) {
            c.drawText(L("Thank you for using MudaPay"), cx, y, paint(12f, GREEN_DARK, true, Paint.Align.CENTER))
        }
        y += 17f
        c?.drawText(L("Keep this reference if you need help from Support."), cx, y, paint(10f, MUTED, false, Paint.Align.CENTER))
        y += 26f   // bottom padding inside the card
        return y + m
    }

    fun render(d: ReceiptData, scale: Float = PNG_SCALE): Bitmap {
        val h = layout(null, d, 0f)
        val bmp = Bitmap.createBitmap((W * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        layout(c, d, h)
        return bmp
    }

    private fun renderPdf(d: ReceiptData, file: File) {
        val h = layout(null, d, 0f)
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), Math.ceil(h.toDouble()).toInt(), 1).create())
            layout(page.canvas, d, h)
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    /* -------------------------------------------------------------- sharing */
    enum class Target { CHOOSER, WHATSAPP }

    private fun outFile(ctx: Context, d: ReceiptData, ext: String): File {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L
        dir.listFiles()?.filter { it.name.startsWith("MudaPay-receipt-") && it.lastModified() < cutoff }?.forEach { it.delete() }
        val tag = d.reference.filter { it.isLetterOrDigit() || it == '-' }.take(24).ifEmpty { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) }
        return File(dir, "MudaPay-receipt-$tag.$ext")
    }

    /** Makes the image (or PDF) and opens the share sheet, or WhatsApp directly. Returns null on success, otherwise a message for the member. */
    suspend fun share(ctx: Context, d: ReceiptData, pdf: Boolean, target: Target): String? {
        val file = withContext(Dispatchers.Default) {
            try {
                val f = outFile(ctx, d, if (pdf) "pdf" else "png")
                if (pdf) renderPdf(d, f)
                else {
                    val bmp = render(d)
                    try { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bmp.recycle() }
                }
                f
            } catch (e: Throwable) { ErrorReporter.nonFatal(e, "Receipt.share"); null }
        } ?: return L("The receipt could not be created.")
        val mime = if (pdf) "application/pdf" else "image/png"
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            fun intent(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, d.caption)
                putExtra(Intent.EXTRA_SUBJECT, "MudaPay receipt")
                clipData = ClipData.newRawUri("MudaPay receipt", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg != null) setPackage(pkg)
            }
            var done = false
            if (target == Target.WHATSAPP) {
                for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                    try { ctx.startActivity(intent(pkg)); done = true; break } catch (e: Exception) { }
                }
            }
            if (!done) ctx.startActivity(Intent.createChooser(intent(null), L("Share receipt")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: Exception) { L("No app was found to share the receipt.") }
    }
}

/* ------------------------------------------------------------------ UI */
/** Three buttons: share the image (any app), send it straight to WhatsApp, or share it as a PDF. */
@Composable
fun ReceiptActions(d: ReceiptData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun go(pdf: Boolean, target: Receipt.Target) {
        if (busy) return
        busy = true
        scope.launch {
            val msg = Receipt.share(ctx, d, pdf, target)
            busy = false
            if (msg != null) Ui.notify(L("Receipt"), msg)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(L("Send on WhatsApp"), { go(false, Receipt.Target.WHATSAPP) }, loading = busy, icon = MudaIcons.Share)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(L("Share image"), { go(false, Receipt.Target.CHOOSER) }, Modifier.weight(1f), MudaIcons.Share)
            GhostButton(L("Share PDF"), { go(true, Receipt.Target.CHOOSER) }, Modifier.weight(1f), MudaIcons.PictureAsPdf)
        }
    }
}

/** A full preview of the receipt (exactly what will be shared) with the share buttons under it. */
@Composable
fun ReceiptDialog(d: ReceiptData, onDismiss: () -> Unit) {
    val bmp = remember(d) { Receipt.render(d, 2f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(26.dp)).background(Color.White).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Image(bmp.asImageBitmap(), L("Receipt"), Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.FillWidth)
            }
            ReceiptActions(d)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(L("Close"), color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
}
