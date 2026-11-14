package io.mudapay.mobile

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.Normalizer
import java.util.Calendar
import kotlin.coroutines.resume

/*
 * On-device OCR (Google ML Kit, bundled Latin model) + parsing of identity documents.
 *
 * What is read:   full name, surname / given names, document number, date of birth, expiry date, issue date, sex, nationality,
 *                 issuing country, place of birth, address, licence categories (whatever is printed and readable).
 * Two sources:    - MRZ (the 2-3 machine-readable lines of passports and many ID cards): validated with the ICAO 9303 check digits;
 *                 - printed labels (EN / FR: "Nom", "Prénoms", "Date de naissance", "Date d'expiration", "Valable jusqu'au" ...).
 * Every value carries its source and a confidence. They are SUGGESTIONS the member must confirm or correct, and a human reviews them
 * on the server. OCR cannot tell a genuine document from a good fake: nothing here ever decides that a document is genuine.
 */

class OcrField(val value: String, val source: String, val confidence: Double)   // source: "mrz" | "label" | "heuristic"
class ParsedDoc(val fields: Map<String, OcrField>, val mrzChecksumOk: Boolean?, val charCount: Int)

object KycOcr {
    val engineName: String get() = "mlkit-latin-16.0.1"

    private suspend fun recognize(bmp: Bitmap): List<String> = suspendCancellableCoroutine { c ->
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t -> c.resume(t.textBlocks.flatMap { b -> b.lines.map { it.text } }) }
            .addOnFailureListener { c.resume(emptyList()) }
            .addOnCompleteListener { rec.close() }
    }

    private fun rotate180(b: Bitmap): Bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(180f) }, true)
    private fun chars(lines: List<String>) = lines.sumOf { l -> l.count { it.isLetterOrDigit() } }

    /** Reads the text lines of a cropped document, trying it upside-down too when the first pass finds little text. */
    suspend fun readLines(doc: Bitmap): List<String> {
        val a = recognize(doc)
        if (chars(a) >= 120 || mrzCandidates(a).size >= 2) return a
        val flipped = rotate180(doc)
        val b = recognize(flipped)
        flipped.recycle()
        return if (chars(b) > chars(a)) b else a
    }

    /** [front] and [back] are the OCR lines of each side (back may be empty, e.g. passport). */
    fun parse(docType: String, front: List<String>, back: List<String>): ParsedDoc {
        val all = front + back
        val out = LinkedHashMap<String, OcrField>()
        val mrz = parseMrz(all)
        val viz = parseLabels(all, docType)
        for ((k, v) in viz) out[k] = v
        var mrzOk: Boolean? = null
        if (mrz != null) {
            mrzOk = mrz.checksOk
            val conf = if (mrz.checksOk) 0.95 else 0.5
            for ((k, v) in mrz.fields) out[k] = OcrField(v, "mrz", conf)
        }
        // date / number fallbacks when no label was readable
        fillHeuristics(all, out)
        // full name
        val sn = out["surname"]?.value; val gn = out["given_names"]?.value
        if (!sn.isNullOrBlank() && !gn.isNullOrBlank()) {
            val conf = minOf(out["surname"]!!.confidence, out["given_names"]!!.confidence)
            out["full_name"] = OcrField("$gn $sn", out["surname"]!!.source, conf)
        }
        return ParsedDoc(out, mrzOk, chars(all))
    }

    // ------------------------------------------------------------------ MRZ (ICAO 9303)
    private class Mrz(val fields: Map<String, String>, val checksOk: Boolean)

    private fun mrzNorm(s: String): String = s.uppercase().replace('«', '<').replace(" ", "").filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }
    private fun mrzCandidates(lines: List<String>): List<String> = lines.map { mrzNorm(it) }.filter { it.length in 28..46 && it.count { c -> c == '<' } >= 2 }

    private fun toDigits(s: String) = s.map { when (it) { 'O', 'Q', 'D' -> '0'; 'I', 'L' -> '1'; 'Z' -> '2'; 'S' -> '5'; 'B' -> '8'; else -> it } }.joinToString("")
    private fun toLetters(s: String) = s.map { when (it) { '0' -> 'O'; '1' -> 'I'; '2' -> 'Z'; '5' -> 'S'; '8' -> 'B'; else -> it } }.joinToString("")

    private fun check(data: String): Int {
        val w = intArrayOf(7, 3, 1); var sum = 0
        data.forEachIndexed { i, ch ->
            val v = when (ch) { in '0'..'9' -> ch - '0'; in 'A'..'Z' -> ch - 'A' + 10; else -> 0 }
            sum += v * w[i % 3]
        }
        return sum % 10
    }
    private fun ok(data: String, digit: Char) = digit in '0'..'9' && check(data) == digit - '0'

    private fun parseMrz(lines: List<String>): Mrz? {
        val c = mrzCandidates(lines)
        // TD3 (passport): two lines of 44
        for (i in 0 until c.size - 1) {
            val a = c[i]; val b = c[i + 1]
            if (a.length in 42..46 && b.length in 42..46 && a.startsWith("P")) return td3(fit(a, 44), fit(b, 44))
        }
        // TD1 (ID card): three lines of 30
        for (i in 0 until c.size - 2) {
            val a = c[i]; val b = c[i + 1]; val d = c[i + 2]
            if (a.length in 28..32 && b.length in 28..32 && d.length in 28..32 && (a.startsWith("I") || a.startsWith("A") || a.startsWith("C"))) return td1(fit(a, 30), fit(b, 30), fit(d, 30))
        }
        return null
    }
    private fun fit(s: String, n: Int) = if (s.length >= n) s.substring(0, n) else s.padEnd(n, '<')

    private fun names(raw: String): Pair<String, String> {
        val p = raw.split("<<", limit = 2)
        fun clean(x: String) = toLetters(x).replace('<', ' ').trim().replace(Regex("\\s+"), " ")
        return clean(p.getOrElse(0) { "" }) to clean(p.getOrElse(1) { "" })
    }

    private fun td3(l1: String, l2: String): Mrz {
        val num = l2.substring(0, 9); val numC = l2[9]
        val dob = toDigits(l2.substring(13, 19)); val dobC = toDigits(l2[19].toString())[0]
        val exp = toDigits(l2.substring(21, 27)); val expC = toDigits(l2[27].toString())[0]
        val comp = l2.substring(0, 10) + dob + dobC + exp + expC + l2.substring(28, 43)
        val okAll = ok(num, numC) && ok(dob, dobC) && ok(exp, expC) && ok(comp, toDigits(l2[43].toString())[0])
        val (sn, gn) = names(l1.substring(5))
        val f = LinkedHashMap<String, String>()
        f["issuing_country"] = toLetters(l1.substring(2, 5)).replace("<", "")
        if (sn.isNotEmpty()) f["surname"] = sn
        if (gn.isNotEmpty()) f["given_names"] = gn
        f["doc_number"] = num.replace("<", "")
        f["nationality"] = toLetters(l2.substring(10, 13)).replace("<", "")
        mrzDate(dob, true)?.let { f["date_of_birth"] = it }
        mrzDate(exp, false)?.let { f["expiry_date"] = it }
        l2[20].let { if (it == 'M' || it == 'F') f["sex"] = it.toString() }
        return Mrz(f, okAll)
    }

    private fun td1(l1: String, l2: String, l3: String): Mrz {
        val num = l1.substring(5, 14); val numC = l1[14]
        val dob = toDigits(l2.substring(0, 6)); val dobC = toDigits(l2[6].toString())[0]
        val exp = toDigits(l2.substring(8, 14)); val expC = toDigits(l2[14].toString())[0]
        val comp = l1.substring(5, 30) + dob + dobC + exp + expC + l2.substring(18, 29)
        val okAll = ok(num, numC) && ok(dob, dobC) && ok(exp, expC) && ok(comp, toDigits(l2[29].toString())[0])
        val (sn, gn) = names(l3)
        val f = LinkedHashMap<String, String>()
        f["issuing_country"] = toLetters(l1.substring(2, 5)).replace("<", "")
        if (sn.isNotEmpty()) f["surname"] = sn
        if (gn.isNotEmpty()) f["given_names"] = gn
        f["doc_number"] = num.replace("<", "")
        f["nationality"] = toLetters(l2.substring(15, 18)).replace("<", "")
        mrzDate(dob, true)?.let { f["date_of_birth"] = it }
        mrzDate(exp, false)?.let { f["expiry_date"] = it }
        l2[7].let { if (it == 'M' || it == 'F') f["sex"] = it.toString() }
        return Mrz(f, okAll)
    }

    private fun mrzDate(yymmdd: String, birth: Boolean): String? {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return null
        val yy = yymmdd.substring(0, 2).toInt(); val mm = yymmdd.substring(2, 4).toInt(); val dd = yymmdd.substring(4, 6).toInt()
        val nowYy = Calendar.getInstance().get(Calendar.YEAR) % 100
        val year = if (birth) (if (yy > nowYy) 1900 + yy else 2000 + yy) else 2000 + yy
        return isoDate(year, mm, dd)
    }

    // ------------------------------------------------------------------ printed labels (English / French)
    private val MONTHS = mapOf(
        "jan" to 1, "janv" to 1, "feb" to 2, "fev" to 2, "fevr" to 2, "mar" to 3, "mars" to 3, "apr" to 4, "avr" to 4, "may" to 5, "mai" to 5,
        "jun" to 6, "juin" to 6, "jul" to 7, "juil" to 7, "aug" to 8, "aout" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    private fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /** field -> label pattern, tested on the accent-free lower-case LABEL part of a line. Order matters: first match wins. */
    private val LABELS: List<Pair<String, Regex>> = listOf(
        "expiry_date" to Regex("expir|valid(e)? (until|thru|to)|valable jusqu|fin de validite|echeance|date of exp"),
        "issue_date" to Regex("date of issue|issue date|issued|date de delivrance|delivre(e)? le|delivrance|date d.emission|emis le"),
        "place_of_birth" to Regex("place of birth|lieu de naissance|lieu naiss|born at|ne(e)?\\(?e?\\)? a\\b"),
        "date_of_birth" to Regex("date of birth|date de naissance|birth date|date naiss|\\bdob\\b|ne\\(?e?\\)? le|\\bnee? le\\b|\\bborn\\b"),
        "nationality" to Regex("nationalit"),
        "sex" to Regex("\\bsex\\b|\\bsexe\\b|\\bgender\\b|\\bgenre\\b"),
        "address" to Regex("address|adresse|domicile|residence"),
        "license_categories" to Regex("categor|\\bclass\\b|\\bcat\\b"),
        "doc_number" to Regex("document n|passport n|licen[cs]e n|permis n|id n(o|umber)|card n|carte n|numero|n°|\\bno\\.?\\b|number|\\bnum\\b"),
        "given_names" to Regex("given name|prenom|first name|forename"),
        "surname" to Regex("surname|last name|family name|nom de famille|\\bnoms?\\b"),
        "full_name" to Regex("full name|\\bnames?\\b")
    )

    private fun classify(label: String): String? { val p = plain(label); return LABELS.firstOrNull { it.second.containsMatchIn(p) }?.first }

    private fun parseLabels(lines: List<String>, docType: String): Map<String, OcrField> {
        val out = LinkedHashMap<String, OcrField>()
        val rows = lines.map { it.trim() }.filter { it.isNotEmpty() }
        var i = 0
        while (i < rows.size) {
            val line = rows[i]
            var field: String? = null; var value = ""
            val colon = line.indexOfFirst { it == ':' || it == '：' }
            if (colon in 2..45) {
                field = classify(line.substring(0, colon)); value = line.substring(colon + 1).trim()
            }
            if (field == null && line.length <= 45 && line.none { it.isDigit() }) {
                field = classify(line)                       // label alone (often "Nom / Surname"): the value is on the next line
                if (field != null) { value = ""; }
            }
            if (field != null && value.isEmpty() && i + 1 < rows.size && classify(rows[i + 1]) == null) { value = rows[i + 1]; i++ }
            if (field != null && value.isNotEmpty() && field !in out) {
                val v = cleanValue(field, value)
                if (v != null) out[field] = OcrField(v, "label", 0.7)
            }
            i++
        }
        return out
    }

    private fun cleanValue(field: String, raw: String): String? {
        val v = raw.trim()
        return when (field) {
            "date_of_birth", "expiry_date", "issue_date" -> parseDate(v)
            "doc_number" -> Regex("[A-Za-z0-9][A-Za-z0-9\\-/ ]{3,24}").find(v)?.value?.replace(" ", "")?.uppercase()?.takeIf { s -> s.any { it.isDigit() } && s.length >= 5 }
            "sex" -> when (plain(v).firstOrNull()) { 'm' -> "M"; 'f' -> "F"; else -> null }
            "surname", "given_names", "full_name", "nationality", "place_of_birth" ->
                v.filter { it.isLetter() || it == ' ' || it == '-' || it == '\'' }.trim().replace(Regex("\\s+"), " ").takeIf { s -> s.count { it.isLetter() } >= 2 }
            "license_categories" -> v.uppercase().filter { it.isLetterOrDigit() || it == ' ' || it == ',' || it == '/' }.trim().take(40).takeIf { it.isNotEmpty() }
            else -> v.take(160).takeIf { it.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------ dates
    private val DATE_NUM = Regex("(\\d{1,2})\\s*[./\\-\\s]\\s*(\\d{1,2})\\s*[./\\-\\s]\\s*(\\d{4})")
    private val DATE_ISO = Regex("(\\d{4})\\s*[./\\-]\\s*(\\d{1,2})\\s*[./\\-]\\s*(\\d{1,2})")
    private val DATE_TXT = Regex("(\\d{1,2})\\s*([A-Za-zéûÉ]{3,5})\\.?\\s*(\\d{4})")

    private fun isoDate(y: Int, m: Int, d: Int): String? {
        if (y < 1900 || y > 2100 || m !in 1..12 || d !in 1..31) return null
        val cal = Calendar.getInstance().apply { isLenient = false; clear(); try { set(y, m - 1, d); timeInMillis } catch (e: Exception) { return null } }
        return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }
    /** "03/11/1994", "3.11.1994", "1994-11-03", "3 NOV 1994", "03 NOV/NOV 1994" -> "1994-11-03" (day first, as on African and European documents). */
    fun parseDate(s: String): String? {
        DATE_ISO.find(s)?.let { m -> return isoDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        DATE_NUM.find(s)?.let { m -> return isoDate(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        DATE_TXT.find(s)?.let { m ->
            val mon = MONTHS[plain(m.groupValues[2]).take(5).trimEnd('.')] ?: MONTHS[plain(m.groupValues[2]).take(4)] ?: MONTHS[plain(m.groupValues[2]).take(3)]
            if (mon != null) return isoDate(m.groupValues[3].toInt(), mon, m.groupValues[1].toInt())
        }
        return null
    }

    // ------------------------------------------------------------------ last resort (low confidence, always shown for confirmation)
    private fun fillHeuristics(lines: List<String>, out: MutableMap<String, OcrField>) {
        val nowY = Calendar.getInstance().get(Calendar.YEAR)
        val dates = lines.flatMap { l -> (DATE_NUM.findAll(l).map { it.value } + DATE_ISO.findAll(l).map { it.value }).toList() }.mapNotNull { parseDate(it) }.distinct().sorted()
        if ("date_of_birth" !in out) dates.firstOrNull { it.take(4).toInt() <= nowY - 14 && it.take(4).toInt() >= nowY - 100 }?.let { out["date_of_birth"] = OcrField(it, "heuristic", 0.35) }
        if ("expiry_date" !in out) dates.lastOrNull { it.take(4).toInt() >= nowY - 1 && it != out["date_of_birth"]?.value }?.let { out["expiry_date"] = OcrField(it, "heuristic", 0.3) }
    }
}
