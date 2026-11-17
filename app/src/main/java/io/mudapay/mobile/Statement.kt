package io.mudapay.mobile

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the member typed / picked on the Activity screen. Used for the list and for the exported statement. */
data class TxFilters(
    val filter: String = "all",   // all | earnings | transfers | withdrawals
    val type: String = "",        // exact transaction type, "" = any
    val q: String = "",
    val from: String = "",        // yyyy-MM-dd
    val to: String = "",
    val min: String = "",
    val max: String = ""
) {
    val active: Boolean get() = type.isNotEmpty() || q.isNotBlank() || from.isNotEmpty() || to.isNotEmpty() || min.isNotBlank() || max.isNotBlank()

    fun toQuery(): MutableMap<String, String> {
        val m = mutableMapOf("filter" to filter)
        if (type.isNotEmpty()) m["type"] = type
        if (q.isNotBlank()) m["q"] = q.trim().take(60)
        var f = from; var t = to
        if (f.isNotEmpty() && t.isNotEmpty() && f > t) { val x = f; f = t; t = x }   // yyyy-MM-dd sorts like dates
        if (f.isNotEmpty()) m["from"] = f
        if (t.isNotEmpty()) m["to"] = t
        min.trim().replace(',', '.').toDoubleOrNull()?.let { m["min"] = it.toString() }
        max.trim().replace(',', '.').toDoubleOrNull()?.let { m["max"] = it.toString() }
        return m
    }
}

/** Builds a CSV or PDF statement from the transactions that match [TxFilters] and opens the share sheet. */
object Statement {
    private const val MAX_ROWS = 5000

    private fun isOut(type: String) = type == "withdrawal" || type == "transfer_out"
    private fun signed(t: JSONObject): Double = if (isOut(t.str("type"))) -t.dbl("amount") else t.dbl("amount")

    private class Data(val rows: List<JSONObject>, val symbol: String, val capped: Boolean)

    private suspend fun fetchAll(f: TxFilters): Data? {
        val rows = ArrayList<JSONObject>()
        var cursor: Int? = null
        var symbol = Session.user?.str("symbol") ?: ""
        do {
            val q = f.toQuery()
            q["export"] = "1"; q["limit"] = "500"
            cursor?.let { q["cursor"] = it.toString() }
            val r = Api.call("GET", "/transactions", query = q)
            if (!r.ok || r.cached) return null
            rows.addAll(r.data.list("items"))
            symbol = r.data.str("symbol", symbol)
            cursor = if (r.data.isNull("next_cursor")) null else r.data.optInt("next_cursor")
        } while (cursor != null && rows.size < MAX_ROWS)
        return Data(rows, symbol, cursor != null)
    }

    /** Returns null when the file was made and shared, otherwise a message for the member. */
    suspend fun export(ctx: Context, f: TxFilters, pdf: Boolean): String? {
        val d = fetchAll(f) ?: return "Could not load your transactions. Check your connection and try again."
        if (d.rows.isEmpty()) return "No transactions match these filters."
        val file = withContext(Dispatchers.IO) {
            try { if (pdf) buildPdf(ctx, d, f) else buildCsv(ctx, d) } catch (e: Throwable) { ErrorReporter.nonFatal(e, "Statement.export"); null }
        } ?: return "The statement could not be created."
        return if (share(ctx, file, if (pdf) "application/pdf" else "text/csv")) null else "No app was found to open the statement."
    }

    private fun outFile(ctx: Context, ext: String): File {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("MudaPay-statement-") }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return File(dir, "MudaPay-statement-$stamp.$ext")
    }

    private fun share(ctx: Context, file: File, mime: String): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MudaPay statement")
            clipData = ClipData.newRawUri("MudaPay statement", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, L("Share statement")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { false }

    /* ------------------------------------------------------------------ CSV */
    /** Quotes a cell and neutralises spreadsheet formulas (a transfer note could start with "=" or "+"). */
    private fun cell(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private fun buildCsv(ctx: Context, d: Data): File {
        val sb = StringBuilder("\uFEFF")   // so Excel reads accents correctly
        sb.append("Date,Type,Description,Direction,Amount,Currency\r\n")
        for (t in d.rows) {
            val type = t.str("type")
            val amt = signed(t)
            sb.append(cell(t.str("created_at"))).append(',')
                .append(cell(L(txTypeLabel(type)))).append(',')
                .append(cell(t.str("description"))).append(',')
                .append(if (isOut(type)) "out" else "in").append(',')
                .append(String.format(Locale.US, "%.2f", amt)).append(',')
                .append(cell(d.symbol)).append("\r\n")
        }
        val f = outFile(ctx, "csv")
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    /* ------------------------------------------------------------------ PDF */
    private fun buildPdf(ctx: Context, d: Data, f: TxFilters): File {
        val pw = 595; val ph = 842; val m = 36f
        val green = 0xFF0B7A4B.toInt()
        val base = Paint().apply { isAntiAlias = true; textSize = 9f; color = 0xFF111827.toInt() }
        val bold = Paint(base).apply { isFakeBoldText = true }
        val muted = Paint(base).apply { color = 0xFF64748B.toInt() }
        val right = Paint(base).apply { textAlign = Paint.Align.RIGHT }
        val rightBold = Paint(right).apply { isFakeBoldText = true }
        val title = Paint(base).apply { textSize = 20f; isFakeBoldText = true; color = green }
        val line = Paint().apply { color = 0xFFE2E8F0.toInt(); strokeWidth = 0.8f }

        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var c = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create()).also { page = it }.canvas
        var pageNo = 1
        var y = 0f

        fun header() {
            c.drawText(L("Date"), m, y, bold)
            c.drawText(L("Type"), m + 100, y, bold)
            c.drawText(L("Description"), m + 205, y, bold)
            c.drawText(L("Amount"), pw - m, y, rightBold)
            y += 5f; c.drawLine(m, y, pw - m, y, line); y += 12f
        }
        fun finishPage() {
            c.drawText("MudaPay - ${L("Page")} $pageNo", pw / 2f - 30f, ph - 24f, muted)
            doc.finishPage(page!!)
        }
        fun newPage() {
            finishPage()
            pageNo++
            c = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create()).also { page = it }.canvas
            y = m + 8f
            c.drawText("MudaPay - ${L("Account statement")}", m, y, bold); y += 20f
            header()
        }

        // ---- first page: title block
        y = m + 18f
        c.drawText("MudaPay", m, y, title); y += 16f
        c.drawText(L("Account statement"), m, y, bold); y += 14f
        val name = Session.user?.str("full_name") ?: ""
        val phone = Session.user?.str("phone") ?: ""
        if (name.isNotEmpty()) { c.drawText(name + if (phone.isNotEmpty()) "  |  $phone" else "", m, y, base); y += 12f }
        val newest = d.rows.first().str("created_at").take(10)
        val oldest = d.rows.last().str("created_at").take(10)
        val from = f.from.ifEmpty { oldest }; val to = f.to.ifEmpty { newest }
        c.drawText("${L("Period")}: $from - $to", m, y, base); y += 12f
        val crit = ArrayList<String>()
        if (f.type.isNotEmpty()) crit.add(L(txTypeLabel(f.type)))
        else if (f.filter != "all") crit.add(L(f.filter.replaceFirstChar { it.uppercase() }))
        if (f.q.isNotBlank()) crit.add("\"${f.q.trim().take(30)}\"")
        if (f.min.isNotBlank() || f.max.isNotBlank()) crit.add("${L("Amount")} ${f.min.ifBlank { "0" }}–${f.max.ifBlank { "max" }}")
        if (crit.isNotEmpty()) { c.drawText("${L("Filters")}: ${crit.joinToString("  |  ")}", m, y, muted); y += 12f }
        c.drawText("${L("Generated")}: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}", m, y, muted); y += 20f
        header()

        var totalIn = 0.0; var totalOut = 0.0
        for (t in d.rows) {
            if (y > ph - 60f) newPage()
            val type = t.str("type")
            val amt = t.dbl("amount")
            if (isOut(type)) totalOut += amt else totalIn += amt
            c.drawText(t.str("created_at").take(16), m, y, base)
            val label = L(txTypeLabel(type))
            c.drawText(label.take(22), m + 100, y, base)
            val desc = t.str("description").replace('\n', ' ')
            val n = base.breakText(desc, true, pw - m - 70f - (m + 205f), null)
            c.drawText(if (n < desc.length) desc.take(maxOf(0, n - 2)) + ".." else desc, m + 205, y, base)
            c.drawText((if (isOut(type)) "-" else "+") + money(d.symbol, amt), pw - m, y, right)
            y += 14f
        }

        // ---- totals
        if (y > ph - 90f) newPage()
        y += 4f; c.drawLine(m, y, pw - m, y, line); y += 14f
        c.drawText("${L("Transactions")}: ${d.rows.size}${if (d.capped) " (${L("first")} $MAX_ROWS)" else ""}", m, y, bold); y += 13f
        c.drawText("${L("Total in")}: ${money(d.symbol, totalIn)}", m, y, base); y += 13f
        c.drawText("${L("Total out")}: ${money(d.symbol, totalOut)}", m, y, base); y += 13f
        c.drawText("${L("Net")}: ${money(d.symbol, totalIn - totalOut)}", m, y, bold)
        finishPage()

        val file = outFile(ctx, "pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
