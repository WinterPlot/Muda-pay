package io.mudapay.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size as CamSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.concurrent.Executors

/* ------------------------------------------------------------------ what a MudaPay payment QR contains */

/**
 * Payload of a MudaPay payment QR code:  mudapay://pay?v=1&p=%2B25779123456[&a=5000][&n=Rent]
 *   p = the receiver's phone number, a = amount to pay (optional), n = short note (optional).
 * Older codes that hold only a phone number are accepted too. The receiver's NAME is never read from the code:
 * the Send screen asks the server who owns the number, so a forged code cannot show a fake name.
 */
object PayQr {
    class Parsed(val phone: String, val amount: Long?, val note: String, val country: String = "")

    private fun cleanPhone(raw: String): String {
        val s = raw.filter { it.isDigit() || it == '+' }
        return (if (s.startsWith("+")) "+" else "") + s.filter { it.isDigit() }
    }

    fun build(phone: String, amount: Long? = null, note: String = "", country: String = ""): String {
        val sb = StringBuilder("mudapay://pay?v=1&p=").append(Uri.encode(cleanPhone(phone)))
        // the receiver's country lets a member in ANOTHER country resolve a national number (e.g. 712345678) correctly
        if (country.length in 2..3) sb.append("&c=").append(Uri.encode(country.lowercase()))
        if (amount != null && amount > 0) sb.append("&a=").append(amount)
        val n = note.trim().take(60)
        if (n.isNotEmpty()) sb.append("&n=").append(Uri.encode(n))
        return sb.toString()
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    private fun finish(phone: String, amount: Long?, note: String, country: String = ""): Parsed? {
        val p = cleanPhone(phone)
        val digits = p.count { it.isDigit() }
        if (digits < 6 || digits > 15) return null
        val cc = country.lowercase().filter { it in 'a'..'z' }.take(3)
        return Parsed(p, amount, note.filter { !it.isISOControl() }.trim().take(120), cc)
    }

    /** Returns null when [raw] is not a MudaPay payment code (or a plain phone number). */
    fun parse(raw: String): Parsed? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 600) return null
        val lower = t.lowercase()
        if (lower.startsWith("mudapay:")) {
            val q = t.substringAfter('?', "").substringBefore('#')
            if (q.isEmpty()) return null
            var phone = ""
            var amount: Long? = null
            var note = ""
            var country = ""
            for (part in q.split('&')) {
                val key = part.substringBefore('=').lowercase()
                val value = part.substringAfter('=', "")
                when (key) {
                    "p" -> phone = decode(value.replace("+", "%2B"))
                    "a" -> amount = value.filter { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= 12 }?.toLongOrNull()?.takeIf { it > 0 }
                    "n" -> note = decode(value)
                    "c" -> country = decode(value)
                }
            }
            return finish(phone, amount, note, country)
        }
        if (lower.startsWith("tel:")) return finish(t.substring(4), null, "")
        if (Regex("^\\+?[0-9][0-9 .()\\-]{4,24}$").matches(t)) return finish(t, null, "")
        return null
    }
}

/** Set by the scanner and consumed once by the Send screen (phone, amount and note found in the QR code). */
object PayPrefill {
    var pending by mutableStateOf<PayQr.Parsed?>(null)
}

/* ------------------------------------------------------------------ reading QR codes (ZXing) */
object QrDecoder {
    private val hints = mapOf<DecodeHintType, Any>(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    private fun attempt(source: LuminanceSource, inverted: Boolean): String? {
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(if (inverted) source.invert() else source)), hints).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /** One camera frame: [data] is the Y (brightness) plane, [rowStride] its row length. */
    fun decodeLuma(data: ByteArray, rowStride: Int, width: Int, height: Int, alsoInverted: Boolean): String? {
        val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        return attempt(source, false) ?: if (alsoInverted) attempt(source, true) else null
    }

    /** A picture from the gallery: tries the full size first, then smaller copies (very large photos can hide small codes). */
    fun decodeBitmap(src: Bitmap): String? {
        var previous: Bitmap? = null
        for (maxSide in intArrayOf(2400, 1400, 900, 600)) {
            val longest = maxOf(src.width, src.height)
            val b = if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
            } else src
            if (b === previous) continue
            previous = b
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val source = RGBLuminanceSource(b.width, b.height, px)
            attempt(source, false)?.let { return it }
            attempt(source, true)?.let { return it }
        }
        return null
    }

    fun decodeUri(ctx: Context, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return decodeBitmap(bmp)
    }
}

/** Runs on its own thread; the newest frame wins, older ones are dropped while a decode is running. */
private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var lastFound = 0L
    private var frame = 0
    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFound < 1200) return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            frame++
            val text = QrDecoder.decodeLuma(data, plane.rowStride, image.width, image.height, alsoInverted = frame % 3 == 0)
            if (text != null) {
                lastFound = now
                onFound(text)
            }
        } catch (e: Throwable) {
            // a bad frame is simply skipped
        } finally {
            image.close()
        }
    }
}

/* ------------------------------------------------------------------ screen */
@Composable
fun ScanPayScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun cameraAllowed() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraAllowed()) }
    var asked by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; asked = true }

    fun handle(raw: String) {
        if (finished) return
        val parsed = PayQr.parse(raw)
        if (parsed == null) {
            message = "This is not a MudaPay payment QR code."
            return
        }
        finished = true
        PayPrefill.pending = parsed
        nav("Send")
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.Default) { try { QrDecoder.decodeUri(ctx, uri) } catch (e: Throwable) { null } }
            if (text == null) message = "No QR code was found in this picture." else handle(text)
        }
    }

    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(message) { if (message != null) { delay(3200); message = null } }
    // Coming back from the phone's settings after allowing the camera there.
    val activity = ctx.findActivity()
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = cameraAllowed() }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && !cameraError) {
            CameraScanner(Modifier.fillMaxSize(), torch, { hasTorch = it }, { cameraError = true }, ::handle)
            ScanOverlay(Modifier.fillMaxSize())
            Text(
                L("Point the camera at a MudaPay QR code"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp, start = 28.dp, end = 28.dp)
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp).fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(64.dp).background(Green.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.QrCodeScanner, null, tint = GreenDark, modifier = Modifier.size(34.dp))
                }
                Text(L(if (cameraError) "The camera could not start" else "Camera needed"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    L(if (cameraError) "You can still choose a picture of the QR code from your gallery." else "Allow the camera to scan a MudaPay QR code. Nothing is recorded or sent."),
                    color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp
                )
                if (!cameraError) {
                    PrimaryButton(softCase(L("ALLOW CAMERA")), { askCamera.launch(Manifest.permission.CAMERA) }, icon = Icons.Default.CameraAlt)
                    if (asked) PrimaryButton(softCase(L("OPEN PHONE SETTINGS")), { openAppSettings(ctx) }, color = Dark, icon = Icons.Default.Settings)
                }
                PrimaryButton(softCase(L("CHOOSE FROM GALLERY")), { pick.launch("image/*") }, color = if (cameraError) Green else Dark, icon = Icons.Default.Image)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            message?.let { m ->
                Row(Modifier.fillMaxWidth().background(Danger, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(L(m), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (granted && !cameraError) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RoundAction(Icons.Default.Image, "Gallery") { pick.launch("image/*") }
                    if (hasTorch) RoundAction(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Light") { torch = !torch }
                    RoundAction(Icons.Default.Keyboard, "Type number") { nav("Send") }
                }
            } else {
                RoundAction(Icons.Default.Keyboard, "Type number") { nav("Send") }
            }
        }
    }
}

private fun openAppSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
    } catch (e: Exception) {
        Ui.notify("Camera", "Open your phone Settings → Apps → MudaPay → Permissions and allow the camera.")
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(52.dp).background(Color(0x55FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(L(label), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Dimmed camera with a clear square, green corners and a moving line. */
@Composable
private fun ScanOverlay(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Reverse), label = "line")
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * 0.68f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f - size.height * 0.04f
        val right = left + side
        val bottom = top + side
        val dim = Color(0x99000000)
        drawRect(dim, Offset(0f, 0f), Size(size.width, top))
        drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(dim, Offset(0f, top), Size(left, side))
        drawRect(dim, Offset(right, top), Size(size.width - right, side))
        val len = side * 0.16f
        val sw = 5.dp.toPx()
        val c = Green
        // four green corners
        drawLine(c, Offset(left, top), Offset(left + len, top), sw, StrokeCap.Round)
        drawLine(c, Offset(left, top), Offset(left, top + len), sw, StrokeCap.Round)
        drawLine(c, Offset(right, top), Offset(right - len, top), sw, StrokeCap.Round)
        drawLine(c, Offset(right, top), Offset(right, top + len), sw, StrokeCap.Round)
        drawLine(c, Offset(left, bottom), Offset(left + len, bottom), sw, StrokeCap.Round)
        drawLine(c, Offset(left, bottom), Offset(left, bottom - len), sw, StrokeCap.Round)
        drawLine(c, Offset(right, bottom), Offset(right - len, bottom), sw, StrokeCap.Round)
        drawLine(c, Offset(right, bottom), Offset(right, bottom - len), sw, StrokeCap.Round)
        // moving line
        val y = top + side * 0.06f + side * 0.88f * t
        drawLine(c.copy(alpha = .85f), Offset(left + 14.dp.toPx(), y), Offset(right - 14.dp.toPx(), y), 3.dp.toPx(), StrokeCap.Round)
    }
}

/** CameraX preview + analysis. Every frame is checked for a QR code with ZXing (no Google services needed). */
@Composable
private fun CameraScanner(
    modifier: Modifier,
    torch: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    onError: () -> Unit,
    onResult: (String) -> Unit
) {
    val ctx = LocalContext.current
    val owner = ctx.findActivity()
    val latestResult by rememberUpdatedState(onResult)
    val latestTorch by rememberUpdatedState(onTorchAvailable)
    val latestError by rememberUpdatedState(onError)
    val executor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { provider?.unbindAll() } catch (e: Throwable) { }
            executor.shutdown()
        }
    }
    LaunchedEffect(torch, camera) {
        try { camera?.cameraControl?.enableTorch(torch) } catch (e: Throwable) { }
    }
    if (owner == null) {
        LaunchedEffect(Unit) { latestError() }
        return
    }
    AndroidView(modifier = modifier, factory = { c ->
        val view = PreviewView(c).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(CamSize(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrAnalyzer { text -> view.post { latestResult(text) } })
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                latestTorch(cam.cameraInfo.hasFlashUnit())
            } catch (e: Throwable) {
                ErrorReporter.nonFatal(e, "ScanPay.camera")
                latestError()
            }
        }, ContextCompat.getMainExecutor(c))
        view
    })
}
