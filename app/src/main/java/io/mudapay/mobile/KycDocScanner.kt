package io.mudapay.mobile

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * KYC document scanner (OpenCV). Finds the ID card / passport page / licence in a photo, crops it, straightens (perspective warp)
 * and measures photo quality. Everything runs on the phone; images stay in memory (nothing is written to storage).
 *
 * The quality numbers are ADVISORY thresholds (see the constants): tune them with real photos from your members' phones.
 * A good quality score never means "genuine document": the document is always checked by a human on the server side.
 */

/** Issue codes (also sent to the server, so the reviewer can see what the phone saw). */
object KycIssue {
    const val NOT_FOUND = "document_not_found"
    const val BLURRY = "blurry"
    const val DARK = "too_dark"
    const val BRIGHT = "too_bright"
    const val GLARE = "glare"
    const val SMALL = "low_resolution"
    const val FAR = "too_far"
    const val SKEWED = "strong_angle"
    const val NO_SCANNER = "scanner_unavailable"
}

class DocQuality(
    val docDetected: Boolean, val sharpness: Double, val brightness: Double, val glarePct: Double,
    val width: Int, val height: Int, val blocking: List<String>, val warnings: List<String>
) {
    val ok: Boolean get() = blocking.isEmpty()
    val issues: List<String> get() = blocking + warnings
}

class ScanResult(val bitmap: Bitmap, val quality: DocQuality)

object KycScanner {
    // ---- tunable thresholds (measured on the document after it was cropped and scaled to 1000 px wide)
    private const val BLUR_BLOCK = 45.0       // variance of the Laplacian: below = text is not readable
    private const val BLUR_WARN = 100.0
    private const val DARK_BLOCK = 55.0       // mean gray level 0..255
    private const val BRIGHT_WARN = 215.0
    private const val GLARE_WARN = 2.5        // % of the document covered by blown-out white
    private const val GLARE_BLOCK = 9.0
    private const val MIN_SIDE_BLOCK = 800    // long side of the document, in REAL camera pixels
    private const val MIN_SIDE_WARN = 1100
    private const val MIN_AREA_RATIO = 0.18   // document must fill at least this part of the frame to be accepted
    private const val FAR_WARN_RATIO = 0.30
    private const val MAX_OUT_SIDE = 1800     // long side of the cropped image that is kept / uploaded

    @Volatile private var loaded: Boolean? = null
    val engineName: String get() = "opencv-4.9"

    fun available(): Boolean {
        loaded?.let { return it }
        val ok = try { OpenCVLoader.initLocal() } catch (t: Throwable) { false }
        loaded = ok
        return ok
    }

    /** Detects + crops + straightens + measures. Never throws: when OpenCV is missing the photo is kept as it is and flagged. */
    fun scan(src: Bitmap): ScanResult {
        if (!available()) return fallback(src, KycIssue.NO_SCANNER)
        val mats = ArrayList<Mat>()
        fun <T : Mat> T.track(): T { mats.add(this); return this }
        try {
            val rgba = Mat().track()
            Utils.bitmapToMat(src, rgba)
            val frameArea = rgba.width().toDouble() * rgba.height()
            val quad = findQuad(rgba, mats)
            var warped: Mat
            var detected = false
            var ratioOfFrame = 1.0
            var skew = 0.0
            var realLong = max(rgba.width(), rgba.height())
            if (quad != null) {
                detected = true
                val (tl, tr, br, bl) = orderCorners(quad)
                val wTop = dist(tl, tr); val wBot = dist(bl, br); val hL = dist(tl, bl); val hR = dist(tr, br)
                val w = max(wTop, wBot).toInt().coerceAtLeast(2)
                val h = max(hL, hR).toInt().coerceAtLeast(2)
                ratioOfFrame = polyArea(listOf(tl, tr, br, bl)) / frameArea
                skew = max(abs(wTop - wBot) / max(wTop, wBot), abs(hL - hR) / max(hL, hR))
                realLong = max(w, h)
                val from = MatOfPoint2f(tl, tr, br, bl).track()
                val to = MatOfPoint2f(Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0)).track()
                val m = Imgproc.getPerspectiveTransform(from, to).track()
                warped = Mat().track()
                Imgproc.warpPerspective(rgba, warped, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC)
                if (warped.height() > warped.width()) { val r = Mat().track(); Core.rotate(warped, r, Core.ROTATE_90_CLOCKWISE); warped = r }
            } else {
                warped = rgba
            }
            val longSide = max(warped.width(), warped.height())
            if (longSide > MAX_OUT_SIDE) {
                val s = MAX_OUT_SIDE.toDouble() / longSide
                val r = Mat().track()
                Imgproc.resize(warped, r, Size(warped.width() * s, warped.height() * s), 0.0, 0.0, Imgproc.INTER_AREA)
                warped = r
            }
            val q = measure(warped, mats, detected, ratioOfFrame, skew, realLong)
            val out = Bitmap.createBitmap(warped.width(), warped.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, out)
            return ScanResult(out, q)
        } catch (t: Throwable) {
            ErrorReporter.nonFatal(t, "KycScanner.scan")
            return fallback(src, KycIssue.NO_SCANNER)
        } finally {
            mats.forEach { try { it.release() } catch (_: Throwable) { } }
        }
    }

    private fun fallback(src: Bitmap, issue: String): ScanResult {
        val s = scaleDown(src, MAX_OUT_SIDE)
        return ScanResult(s, DocQuality(false, 0.0, 0.0, 0.0, s.width, s.height, emptyList(), listOf(issue)))
    }

    /** Largest convex 4-corner shape with a card-like aspect ratio; falls back to the minimum-area rectangle of the biggest blob. */
    private fun findQuad(rgba: Mat, mats: MutableList<Mat>): List<Point>? {
        val scale = min(1.0, 900.0 / max(rgba.width(), rgba.height()))
        val small = Mat().also { mats.add(it) }
        Imgproc.resize(rgba, small, Size(rgba.width() * scale, rgba.height() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat().also { mats.add(it) }
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val tmp = Mat().also { mats.add(it) }
        val otsu = Imgproc.threshold(gray, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val edges = Mat().also { mats.add(it) }
        Imgproc.Canny(gray, edges, max(10.0, otsu * 0.4), max(30.0, otsu * 0.9))
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).also { mats.add(it) }
        Imgproc.dilate(edges, edges, k)
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, k)
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat().also { mats.add(it) }
        Imgproc.findContours(edges, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val frame = small.width().toDouble() * small.height()
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)
        var best: List<Point>? = null
        for (c in sorted) {
            val area = Imgproc.contourArea(c)
            if (area < frame * MIN_AREA_RATIO) break
            val c2f = MatOfPoint2f(*c.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true)
            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray())) && cardLike(approx.toList())) { best = approx.toList(); break }
        }
        if (best == null && sorted.isNotEmpty()) {
            val c = sorted[0]
            if (Imgproc.contourArea(c) >= frame * 0.25) {
                val r: RotatedRect = Imgproc.minAreaRect(MatOfPoint2f(*c.toArray()))
                val pts = Array(4) { Point() }
                r.points(pts)
                val list = pts.toList()
                if (list.size == 4 && cardLike(list)) best = list
            }
        }
        contours.forEach { it.release() }
        return best?.map { Point(it.x / scale, it.y / scale) }
    }

    /** ID-1 cards are 1.586, passport pages 1.42, some licences 1.5..1.7: accept 1.25..1.95 (long side / short side). */
    private fun cardLike(p: List<Point>): Boolean {
        val (tl, tr, br, bl) = orderCorners(p)
        val a = max(dist(tl, tr), dist(bl, br)); val b = max(dist(tl, bl), dist(tr, br))
        if (min(a, b) < 10) return false
        val r = max(a, b) / min(a, b)
        return r in 1.25..1.95
    }

    private fun measure(doc: Mat, mats: MutableList<Mat>, detected: Boolean, ratioOfFrame: Double, skew: Double, realLong: Int): DocQuality {
        val s = 1000.0 / doc.width()
        val norm = Mat().also { mats.add(it) }
        Imgproc.resize(doc, norm, Size(1000.0, doc.height() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat().also { mats.add(it) }
        Imgproc.cvtColor(norm, gray, Imgproc.COLOR_RGBA2GRAY)
        val lap = Mat().also { mats.add(it) }
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble().also { mats.add(it) }; val std = MatOfDouble().also { mats.add(it) }
        Core.meanStdDev(lap, mean, std)
        val sharp = std.toArray()[0] * std.toArray()[0]
        val bright = Core.mean(gray).`val`[0]
        // glare = nearly white AND not coloured (paper white is fine on a plain card; a blob of it is not): count only the large blown-out areas
        val rgb = Mat().also { mats.add(it) }; Imgproc.cvtColor(norm, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat().also { mats.add(it) }; Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat().also { mats.add(it) }
        Core.inRange(hsv, Scalar(0.0, 0.0, 247.0), Scalar(180.0, 35.0, 255.0), mask)
        val ker = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0)).also { mats.add(it) }
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, ker)
        val glare = Core.countNonZero(mask) * 100.0 / (mask.width().toDouble() * mask.height())

        val block = ArrayList<String>(); val warn = ArrayList<String>()
        if (!detected) block.add(KycIssue.NOT_FOUND)
        else {
            if (ratioOfFrame < FAR_WARN_RATIO) warn.add(KycIssue.FAR)
            if (skew > 0.22) warn.add(KycIssue.SKEWED)
            if (realLong < MIN_SIDE_BLOCK) block.add(KycIssue.SMALL) else if (realLong < MIN_SIDE_WARN) warn.add(KycIssue.SMALL)
        }
        if (sharp < BLUR_BLOCK) block.add(KycIssue.BLURRY) else if (sharp < BLUR_WARN) warn.add(KycIssue.BLURRY)
        if (bright < DARK_BLOCK) block.add(KycIssue.DARK) else if (bright > BRIGHT_WARN) warn.add(KycIssue.BRIGHT)
        if (glare > GLARE_BLOCK) block.add(KycIssue.GLARE) else if (glare > GLARE_WARN) warn.add(KycIssue.GLARE)
        return DocQuality(detected, sharp, bright, glare, doc.width(), doc.height(), block, warn)
    }

    // ---- geometry helpers
    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    /** Returns [top-left, top-right, bottom-right, bottom-left]. */
    private fun orderCorners(p: List<Point>): List<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!; val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!; val bl = p.maxByOrNull { it.y - it.x }!!
        return listOf(tl, tr, br, bl)
    }
    private fun polyArea(p: List<Point>): Double {
        var a = 0.0
        for (i in p.indices) { val j = (i + 1) % p.size; a += p[i].x * p[j].y - p[j].x * p[i].y }
        return abs(a) / 2.0
    }
    fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val l = max(b.width, b.height)
        if (l <= maxSide) return b
        val s = maxSide.toDouble() / l
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }
    /** JPEG without EXIF/location (a Bitmap carries none). */
    fun jpeg(b: Bitmap, quality: Int = 88): ByteArray { val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, quality, o); return o.toByteArray() }
}
