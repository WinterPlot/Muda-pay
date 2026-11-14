package io.mudapay.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors

/*
 * KYC scan flow (MudaPay 3.11): document type -> photo of the front -> photo of the back (cards and licences) -> check the details read by
 * the phone -> selfie holding the document -> send for review.
 *
 * Privacy / safety rules of this file:
 *  - photos and personal details live in memory only: nothing is saved to the gallery, cache or disk, and the window is FLAG_SECURE
 *    (no screenshots, hidden in the recent-apps view);
 *  - the member always sees and can correct every extracted value, and must tick an explicit consent before anything is sent;
 *  - everything goes to MudaPay's own server over HTTPS (same Api client as the rest of the app, certificate pinning included when set);
 *  - the app NEVER decides that an ID is genuine. It sends the photos + the details + the quality report, and MudaPay support decides.
 */

private enum class Step { Intro, Front, Back, Review, Selfie, Sending }

private class Side(val bitmap: Bitmap, val jpeg: ByteArray, val quality: DocQuality, val lines: List<String>)

@Composable
fun KycFlowDialog(initialDocType: String, onClose: () -> Unit, onSubmitted: () -> Unit) {
    var askExit by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { askExit = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false, decorFitsSystemWindows = true)
    ) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        Surface(Modifier.fillMaxSize(), color = Page) {
            KycFlow(initialDocType, onExit = { askExit = true }, onSubmitted = onSubmitted)
        }
    }
    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        title = { Text(L("Stop scanning?")) },
        text = { Text(L("The photos you took are discarded. You can start again at any time.")) },
        confirmButton = { TextButton(onClick = { askExit = false; onClose() }) { Text(L("STOP")) } },
        dismissButton = { TextButton(onClick = { askExit = false }) { Text(L("KEEP GOING")) } }
    )
}

@Composable
private fun KycFlow(initialDocType: String, onExit: () -> Unit, onSubmitted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Intro) }
    var docType by remember { mutableStateOf(if (initialDocType in listOf("national_id", "passport", "driver_licence")) initialDocType else "national_id") }
    var front by remember { mutableStateOf<Side?>(null) }
    var back by remember { mutableStateOf<Side?>(null) }
    var selfie by remember { mutableStateOf<ByteArray?>(null) }
    var parsed by remember { mutableStateOf<ParsedDoc?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var consent by remember { mutableStateOf(false) }
    var sendMsg by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val needsBack = docType != "passport"

    // release every photo and detail when the screen goes away
    DisposableEffect(Unit) { onDispose { front = null; back = null; selfie = null; parsed = null; values.clear() } }

    fun goAfterFront() { step = if (needsBack) Step.Back else Step.Review }
    fun buildFields() {
        val p = KycOcr.parse(docType, front?.lines.orEmpty(), back?.lines.orEmpty())
        parsed = p
        values.clear()
        p.fields.forEach { (k, v) -> if (k != "surname" && k != "given_names") values[k] = v.value }
        if (values["full_name"].isNullOrBlank()) values["full_name"] = p.fields["full_name"]?.value ?: ""
    }

    fun send() {
        val f = front ?: return; val s = selfie ?: return
        step = Step.Sending; sendError = null
        scope.launch {
            val dt = docType
            sendMsg = L("Sending the front of your document…")
            var r = Api.upload("/account/id/upload?part=front&doc_type=$dt", Upload("file", "front.jpg", "image/jpeg", f.jpeg))
            if (!r.ok) { sendError = r.message; return@launch }
            val b = back
            if (needsBack && b != null) {
                sendMsg = L("Sending the back of your document…")
                r = Api.upload("/account/id/back?doc_type=$dt", Upload("file", "back.jpg", "image/jpeg", b.jpeg))
                if (!r.ok) { sendError = r.message; return@launch }
            }
            sendMsg = L("Sending your selfie…")
            r = Api.upload("/account/id/upload?part=selfie&doc_type=$dt", Upload("file", "selfie.jpg", "image/jpeg", s))
            if (!r.ok) { sendError = r.message; return@launch }
            sendMsg = L("Sending the details…")
            // The details are a help for the reviewer. If they cannot be saved (server not configured yet, network), the photos still go for review.
            val dr = Api.call("POST", "/account/id/details", detailsJson(ctx, dt, values, parsed, f, b, consent))
            if (!dr.ok && dr.code != "kyc_unavailable") { sendError = dr.message; return@launch }
            sendMsg = L("Sending for review…")
            r = Api.call("POST", "/account/id/submit")
            if (!r.ok) { sendError = r.message; return@launch }
            Ui.notify("ID sent", if (dr.ok) "MudaPay support will review it and tell you the result." else "MudaPay support will read your photos and tell you the result.")
            onSubmitted()
        }
    }

    Column(Modifier.fillMaxSize()) {
        KycHeader(step, onExit)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                Step.Intro -> IntroStep(docType, { docType = it }) { step = Step.Front }
                Step.Front -> DocCaptureStep(
                    title = L(if (docType == "passport") "Photo page of your passport" else "Front of your document"),
                    hint = L("Put the document on a plain dark surface. All four corners must be visible."),
                    onAccepted = { front = it; buildFields(); goAfterFront() }
                )
                Step.Back -> DocCaptureStep(
                    title = L("Back of your document"),
                    hint = L("Turn it over. Same rules: flat, all corners visible, no reflection."),
                    onAccepted = { back = it; buildFields(); step = Step.Review },
                    onSkip = { back = null; step = Step.Review }
                )
                Step.Review -> ReviewStep(docType, front, parsed, values, consent, { consent = it }) { step = Step.Selfie }
                Step.Selfie -> SelfieStep { selfie = it; send() }
                Step.Sending -> SendingStep(sendMsg, sendError, onRetry = { send() }, onBack = { step = Step.Review })
            }
        }
    }
}

@Composable
private fun KycHeader(step: Step, onExit: () -> Unit) {
    val n = when (step) { Step.Intro -> 0; Step.Front -> 1; Step.Back -> 2; Step.Review -> 3; Step.Selfie -> 4; Step.Sending -> 5 }
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(L("ID VERIFICATION"), color = GreenDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(if (n in 1..4) "${L("Step")} $n / 4" else L("Scan your document"), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(Soft).clickable(onClick = onExit), contentAlignment = Alignment.Center) {
            Icon(MudaIcons.Close, null, tint = Ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun IntroStep(docType: String, onDoc: (String) -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Dropdown(L("Document"), docType, listOf("national_id" to L("National ID card"), "passport" to L("Passport"), "driver_licence" to L("Driving licence")), onSelect = onDoc)
            Text(L("The app finds your document, straightens it, checks the photo and reads the details for you to confirm. A MudaPay agent then checks everything by eye: nothing is accepted automatically."), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("For a good photo"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("•  " + L("Use daylight or a bright room, no flash on the document."), color = Muted, fontSize = 13.sp)
            Text("•  " + L("Lay the document flat on a plain, darker surface."), color = Muted, fontSize = 13.sp)
            Text("•  " + L("Avoid reflections and shadows. Keep your fingers off the text."), color = Muted, fontSize = 13.sp)
        }
        InfoBanner(L("Your photos and details are private: they are not saved on your phone, they are sent over a secure connection to MudaPay and only MudaPay support can see them."), Blue, MudaIcons.Lock)
        PrimaryButton(L("START"), onStart, icon = MudaIcons.CameraAlt)
    }
}

/* ------------------------------------------------------------------ capture + automatic crop + quality check */
private class Pending(val scan: ScanResult)

@Composable
private fun DocCaptureStep(title: String, hint: String, onAccepted: (Side) -> Unit, onSkip: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }
    var cameraFailed by remember { mutableStateOf(false) }

    fun process(raw: Bitmap) {
        busy = true
        scope.launch {
            val res = withContext(Dispatchers.Default) { KycScanner.scan(KycScanner.scaleDown(raw, 3200)) }
            pending = Pending(res); attempts++; busy = false
        }
    }
    fun accept(p: Pending) {
        busy = true
        scope.launch {
            val lines = withContext(Dispatchers.Default) { try { KycOcr.readLines(p.scan.bitmap) } catch (t: Throwable) { ErrorReporter.nonFatal(t, "Kyc.ocr"); emptyList() } }
            val jpeg = withContext(Dispatchers.Default) { KycScanner.jpeg(p.scan.bitmap) }
            busy = false
            onAccepted(Side(p.scan.bitmap, jpeg, p.scan.quality, lines))
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { val b = withContext(Dispatchers.IO) { readBitmapForKyc(ctx, uri) }; if (b == null) Ui.notify("ID verification", "That photo could not be read. Choose another one.") else process(b) }
    }

    val p = pending
    when {
        busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SpinKit(color = Green)
                Text(L("Checking your photo…"), color = Muted, fontSize = 14.sp)
            }
        }
        p != null -> ResultCard(p, attempts, onUse = { accept(p) }, onRetake = { pending = null })
        else -> Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(hint, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (cameraFailed) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L("The camera is not available. You can choose a photo from your gallery instead."), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } else PermissionCamera(front = false, frame = true, onShot = { process(it) }, onFail = { cameraFailed = true })
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { PrimaryButton(L("CHOOSE FROM GALLERY"), { gallery.launch("image/*") }, color = Dark, icon = MudaIcons.Badge) }
                if (onSkip != null) Box(Modifier.weight(1f)) { PrimaryButton(L("NO BACK SIDE"), onSkip, color = DarkFill) }
            }
        }
    }
}

private fun issueText(code: String): String = when (code) {
    KycIssue.NOT_FOUND -> "We could not find the whole document. Lay it flat on a plain dark surface with all four corners visible."
    KycIssue.BLURRY -> "The photo is blurry. Hold the phone steady and let the camera focus before you shoot."
    KycIssue.DARK -> "The photo is too dark. Move to a brighter place."
    KycIssue.BRIGHT -> "The photo is very bright. Avoid direct light on the document."
    KycIssue.GLARE -> "There is a reflection on the document. Tilt it slightly or move away from the light."
    KycIssue.SMALL -> "The document is too small in the photo. Move closer."
    KycIssue.FAR -> "Move a little closer so the document fills more of the screen."
    KycIssue.SKEWED -> "Hold the phone parallel to the document."
    KycIssue.NO_SCANNER -> "Automatic cropping is not available on this phone: a person will check the full photo."
    else -> "The photo may not be good enough."
}

@Composable
private fun ResultCard(p: Pending, attempts: Int, onUse: () -> Unit, onRetake: () -> Unit) {
    val q = p.scan.quality
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(p.scan.bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black), contentScale = ContentScale.FillWidth)
        if (q.ok && q.warnings.isEmpty()) InfoBanner(L("The document was found and straightened. The photo looks good."), GreenDark, MudaIcons.CheckCircle)
        q.blocking.forEach { InfoBanner(L(issueText(it)), Danger, MudaIcons.Warning) }
        q.warnings.forEach { InfoBanner(L(issueText(it)), Blue, MudaIcons.Info) }
        if (q.ok) {
            PrimaryButton(L("USE THIS PHOTO"), onUse, icon = MudaIcons.Check)
            PrimaryButton(L("RETAKE"), onRetake, color = Dark, icon = MudaIcons.Refresh)
        } else {
            PrimaryButton(L("RETAKE"), onRetake, icon = MudaIcons.Refresh)
            // after two failed tries the member is never stuck: a person will look at it
            if (attempts >= 2) PrimaryButton(L("USE ANYWAY (A PERSON WILL CHECK)"), onUse, color = DarkFill)
        }
    }
}

/* ------------------------------------------------------------------ review the details read by the phone */
private val FIELDS = listOf(
    "full_name" to "Full name", "doc_number" to "Document number", "date_of_birth" to "Date of birth (DD/MM/YYYY)", "expiry_date" to "Expiry date (DD/MM/YYYY)",
    "issue_date" to "Issue date (DD/MM/YYYY)", "sex" to "Sex (M or F)", "nationality" to "Nationality", "issuing_country" to "Issuing country",
    "place_of_birth" to "Place of birth", "address" to "Address", "license_categories" to "Licence categories"
)
private val DATE_KEYS = setOf("date_of_birth", "expiry_date", "issue_date")

/** "03/11/1994", "3.11.1994" or "1994-11-03" -> "1994-11-03"; null when it is not a real date. */
private fun normDate(s: String): String? = KycOcr.parseDate(s.trim())

/** Returns the reason the form cannot continue, or null. */
private fun reviewProblem(v: Map<String, String>): String? {
    if ((v["full_name"] ?: "").trim().length < 3) return "Enter the full name as written on the document."
    if ((v["doc_number"] ?: "").filter { it.isLetterOrDigit() }.length < 4) return "Enter the document number."
    for (k in DATE_KEYS) { val x = (v[k] ?: "").trim(); if (x.isNotEmpty() && normDate(x) == null) return "Enter dates like 03/11/1994 (day/month/year)." }
    val s = (v["sex"] ?: "").trim().uppercase(); if (s.isNotEmpty() && s != "M" && s != "F") return "Sex must be M or F."
    return null
}

@Composable
private fun ReviewStep(docType: String, front: Side?, parsed: ParsedDoc?, values: MutableMap<String, String>, consent: Boolean, onConsent: (Boolean) -> Unit, onNext: () -> Unit) {
    val problem = reviewProblem(values)
    val today = remember { val c = Calendar.getInstance(); "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        front?.let { Image(it.bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 190.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentScale = ContentScale.Fit) }
        InfoBanner(L("This is what your phone read. It can make mistakes: check every line against your document and correct it."), Blue, MudaIcons.Info)
        if (parsed?.mrzChecksumOk == true) InfoBanner(L("The machine-readable lines of your document were read correctly."), GreenDark, MudaIcons.CheckCircle)
        if (parsed != null && parsed.fields.isEmpty()) InfoBanner(L("Nothing could be read automatically. Type the details from your document."), Danger, MudaIcons.Warning)
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((key, label) in FIELDS) {
                if (key == "license_categories" && docType != "driver_licence") continue
                if (key == "address" && docType == "passport") continue
                InputField(L(label), values[key] ?: "", { values[key] = if (key == "sex") it.uppercase().take(1) else it })
                val f = parsed?.fields?.get(key)
                if (f != null && f.confidence < 0.6) Text(L("Not sure about this one: please check it carefully."), color = Danger, fontSize = 11.sp)
            }
        }
        val exp = normDate(values["expiry_date"] ?: "")
        if (exp != null && exp < today) InfoBanner(L("This document looks expired. You can still send it, but it will probably be refused."), Danger, MudaIcons.Warning)
        Row(Modifier.fillMaxWidth().card().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L("I agree that MudaPay support reads my ID photos and these details to verify my identity. They are kept private and used only for this."), color = Ink, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Switch(checked = consent, onCheckedChange = onConsent)
        }
        problem?.let { Text(L(it), color = Danger, fontSize = 12.sp) }
        PrimaryButton(L("NEXT: TAKE A SELFIE"), onNext, enabled = problem == null && consent, icon = MudaIcons.Person)
    }
}

/* ------------------------------------------------------------------ selfie */
@Composable
private fun SelfieStep(onDone: (ByteArray) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    fun take(b: Bitmap) { shot = KycScanner.scaleDown(b, 1280) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { val b = withContext(Dispatchers.IO) { readBitmapForKyc(ctx, uri) }; if (b != null) take(b) else Ui.notify("ID verification", "That photo could not be read. Choose another one.") }
    }
    val s = shot
    if (s != null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(s.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
        Text(L("Your face and your document must both be clearly visible."), color = Muted, fontSize = 13.sp)
        PrimaryButton(L("SEND FOR REVIEW"), {
            busy = true
            scope.launch { val j = withContext(Dispatchers.Default) { KycScanner.jpeg(s, 85) }; busy = false; onDone(j) }
        }, loading = busy, icon = MudaIcons.Check)
        PrimaryButton(L("RETAKE"), { shot = null }, color = Dark, icon = MudaIcons.Refresh)
    } else Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(L("Selfie holding your document"), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(L("Hold the document next to your face, text facing the camera."), color = Muted, fontSize = 12.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (cameraFailed) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(L("The camera is not available. You can choose a photo from your gallery instead."), color = Muted) }
            else PermissionCamera(front = true, frame = false, onShot = { take(it) }, onFail = { cameraFailed = true })
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) { PrimaryButton(L("CHOOSE FROM GALLERY"), { gallery.launch("image/*") }, color = Dark, icon = MudaIcons.Badge) }
    }
}

@Composable
private fun SendingStep(msg: String, error: String?, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (error == null) {
            SpinKit(color = Green)
            Spacer(Modifier.height(14.dp))
            Text(msg, color = Muted, fontSize = 14.sp)
        } else {
            InfoBanner(error, Danger, MudaIcons.Warning)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(L("TRY AGAIN"), onRetry, icon = MudaIcons.Refresh)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(L("BACK TO MY DETAILS"), onBack, color = DarkFill)
        }
    }
}

/* ------------------------------------------------------------------ camera */
@Composable
private fun PermissionCamera(front: Boolean, frame: Boolean, onShot: (Bitmap) -> Unit, onFail: () -> Unit) {
    val ctx = LocalContext.current
    fun allowed() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (!ok) denied = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (granted) CameraCapture(front, frame, onShot, onFail)
    else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(L(if (denied) "The camera is switched off for MudaPay. Allow it in your phone settings, or choose a photo from your gallery." else "MudaPay needs the camera to photograph your document."), color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(L("ALLOW CAMERA"), { ask.launch(Manifest.permission.CAMERA) }, icon = MudaIcons.CameraAlt)
    }
}

@Composable
private fun CameraCapture(front: Boolean, frame: Boolean, onShot: (Bitmap) -> Unit, onFail: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx.findActivity()
    val latestShot by rememberUpdatedState(onShot)
    val latestFail by rememberUpdatedState(onFail)
    val executor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var torch by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { try { provider?.unbindAll() } catch (_: Throwable) { }; executor.shutdown() } }
    LaunchedEffect(torch, camera) { try { camera?.cameraControl?.enableTorch(torch) } catch (_: Throwable) { } }
    if (owner == null) { LaunchedEffect(Unit) { latestFail() }; return }

    fun shoot() {
        val cap = capture ?: return
        if (shooting) return
        shooting = true
        cap.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp: Bitmap? = try {
                    val b = image.toBitmap(); val rot = image.imageInfo.rotationDegrees
                    if (rot != 0) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else b
                } catch (t: Throwable) { ErrorReporter.nonFatal(t, "Kyc.capture"); null } finally { image.close() }
                Handler(Looper.getMainLooper()).post { shooting = false; if (bmp != null) latestShot(bmp) else latestFail() }
            }
            override fun onError(exception: ImageCaptureException) {
                Handler(Looper.getMainLooper()).post { shooting = false; latestFail() }
            }
        })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            val view = PreviewView(c).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                try {
                    val p = future.get(); provider = p
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                    p.unbindAll()
                    val cam = p.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
                    camera = cam; capture = cap; hasTorch = cam.cameraInfo.hasFlashUnit()
                } catch (e: Throwable) { ErrorReporter.nonFatal(e, "Kyc.camera"); latestFail() }
            }, ContextCompat.getMainExecutor(c))
            view
        })
        if (frame) Canvas(Modifier.fillMaxSize()) {
            val w = size.width * 0.9f; val h = w / 1.5f
            val left = (size.width - w) / 2f; val top = (size.height - h) / 2f
            drawRoundRect(Color.White.copy(alpha = .9f), Offset(left, top), androidx.compose.ui.geometry.Size(w, h), CornerRadius(18.dp.toPx()), Stroke(width = 2.5.dp.toPx()))
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = if (shooting) .4f else 1f)).clickable(enabled = !shooting) { shoot() }, contentAlignment = Alignment.Center) {
                Icon(MudaIcons.CameraAlt, null, tint = Dark, modifier = Modifier.size(30.dp))
            }
        }
        if (hasTorch && !front) Box(Modifier.align(Alignment.BottomEnd).padding(28.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f)).clickable { torch = !torch }, contentAlignment = Alignment.Center) {
            Icon(if (torch) MudaIcons.FlashOn else MudaIcons.FlashOff, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/* ------------------------------------------------------------------ helpers */
/** Decodes a gallery photo to at most ~3200 px and applies its EXIF rotation. Memory only. */
private fun readBitmapForKyc(ctx: Context, uri: Uri): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    var rot = 0
    if (bmp != null && Build.VERSION.SDK_INT >= 24) {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            rot = when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
        }
    }
    if (bmp != null && rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp
} catch (e: Throwable) { null }

private fun appVersion(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Throwable) { "" }

private fun qualityJson(q: DocQuality) = JSONObject().put("doc_detected", q.docDetected).put("sharpness", q.sharpness).put("brightness", q.brightness)
    .put("glare_pct", q.glarePct).put("width", q.width).put("height", q.height).put("issues", org.json.JSONArray(q.issues))

/** What goes to the server: the values the member CONFIRMED, what the phone originally READ (so a reviewer can spot edits), and the quality report. */
private fun detailsJson(ctx: Context, docType: String, values: Map<String, String>, parsed: ParsedDoc?, front: Side, back: Side?, consent: Boolean): JSONObject {
    val confirmed = JSONObject()
    for ((k, _) in FIELDS) { var v = (values[k] ?: "").trim(); if (k in DATE_KEYS) v = normDate(v) ?: ""; if (v.isNotEmpty()) confirmed.put(k, v) }
    val ocr = JSONObject()
    parsed?.fields?.forEach { (k, f) -> ocr.put(k, JSONObject().put("value", f.value).put("source", f.source).put("confidence", f.confidence)) }
    val quality = JSONObject().put("front", qualityJson(front.quality)); if (back != null) quality.put("back", qualityJson(back.quality))
    val body = JSONObject().put("doc_type", docType).put("consent", consent).put("confirmed", confirmed).put("ocr", ocr).put("quality", quality)
        .put("client", JSONObject().put("app_version", appVersion(ctx)).put("ocr_engine", KycOcr.engineName).put("cv_engine", KycScanner.engineName).put("device_sdk", Build.VERSION.SDK_INT))
    val mrz: Boolean? = parsed?.mrzChecksumOk
    body.put("mrz_checksum_ok", mrz ?: JSONObject.NULL)
    return body
}
