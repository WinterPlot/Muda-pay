package io.mudapay.mobile

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

private const val APK_MIME = "application/vnd.android.package-archive"

/** One app the member can send MudaPay to (an exported "share" screen of an installed app). */
class ShareTarget(val label: String, val pkg: String, val cls: String, val icon: ImageBitmap?, val rank: Int) {
    val key: String get() = "$pkg/$cls"
}

/** The installed MudaPay APK, copied where [FileProvider] may hand it to another app. [file] is null when it cannot be shared as one file. */
class ApkInfo(val file: File?, val sizeText: String)

object ShareApk {
    /** Apps shown first, in this order, when they are installed (WhatsApp, Telegram, offline senders widely used in Africa, SMS, Bluetooth...). */
    private val POPULAR = listOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca", "com.facebook.mlite",
        "com.facebook.katana", "com.instagram.android", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.google.android.gm", "com.android.bluetooth", "cn.xender", "com.lenovo.anyshare.gps",
        "com.google.android.gms", "com.twitter.android", "com.snapchat.android"
    )

    /** Copies the running APK into cache/shared. Split installs (Play Store bundles) are not one file, so they return null. */
    fun prepare(ctx: Context): ApkInfo {
        try {
            val ai = ctx.applicationInfo
            val splits = ai.splitSourceDirs
            if (splits != null && splits.isNotEmpty()) return ApkInfo(null, "")
            val src = File(ai.sourceDir)
            val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
            val dir = File(ctx.cacheDir, "shared")
            dir.mkdirs()
            val out = File(dir, if (ver.isNotEmpty()) "MudaPay-v$ver.apk" else "MudaPay.apk")
            dir.listFiles()?.forEach { if (it.name != out.name) it.delete() }
            if (!out.exists() || out.length() != src.length()) src.copyTo(out, overwrite = true)
            return ApkInfo(out, String.format(Locale.US, "%.1f MB", out.length() / 1048576.0))
        } catch (e: Exception) {
            ErrorReporter.nonFatal(e, "ShareApp.apk")
            return ApkInfo(null, "")
        }
    }

    /** Installed apps that accept what we send (an APK file, or plain text for link-only mode). */
    fun targets(ctx: Context, apkMode: Boolean): List<ShareTarget> {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_SEND).apply { type = if (apkMode) APK_MIME else "text/plain" }
        val infos = try { pm.queryIntentActivities(probe, 0) } catch (e: Exception) { emptyList() }
        val seen = HashSet<String>()
        val out = ArrayList<ShareTarget>()
        for (ri in infos) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == ctx.packageName || !ai.exported) continue
            // Google Play services hosts several share screens (Quick Share, Save to Drive...): keep them apart; every other app once.
            val dedupe = if (ai.packageName == "com.google.android.gms") ai.packageName + "/" + ai.name else ai.packageName
            if (!seen.add(dedupe)) continue
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { ai.packageName }
            val icon = try { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
            var rank = POPULAR.indexOf(ai.packageName)
            if (ai.packageName == "com.google.android.gms" && !label.contains("share", ignoreCase = true)) rank = -1
            out.add(ShareTarget(label, ai.packageName, ai.name, icon, rank))
        }
        return out.sortedWith(compareBy<ShareTarget>({ if (it.rank < 0) 999 else it.rank }, { it.label.lowercase() }))
    }

    private fun build(ctx: Context, text: String, file: File?): Intent {
        val i = Intent(Intent.ACTION_SEND)
        i.putExtra(Intent.EXTRA_TEXT, text)
        i.putExtra(Intent.EXTRA_SUBJECT, "MudaPay")
        if (file != null) {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            i.type = APK_MIME
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.clipData = ClipData.newRawUri("MudaPay", uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            i.type = "text/plain"
        }
        return i
    }

    fun sendTo(ctx: Context, t: ShareTarget, text: String, file: File?) {
        try {
            val i = build(ctx, text, file)
            i.setClassName(t.pkg, t.cls)
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            ErrorReporter.nonFatal(e, "ShareApp.sendTo")
            Ui.notify(L("Share"), L("Could not open that app. Try \"More apps…\" instead."))
        }
    }

    /** Android's own picker, as a last resort for apps that are not in our list. */
    fun systemChooser(ctx: Context, text: String, file: File?) {
        try {
            val c = Intent.createChooser(build(ctx, text, file), L("Share MudaPay"))
            ctx.startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Ui.notify(L("Share"), L("No app is available to share this."))
        }
    }
}

/* ------------------------------------------------------------------ entry card (Settings, Home) */

/** Big tappable card that opens the share screen. */
@Composable
fun ShareAppEntry(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Gradient)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = .10f), radius = size.height * .9f, center = Offset(size.width * .96f, size.height * .1f))
                drawCircle(Color.White.copy(alpha = .07f), radius = size.height * .5f, center = Offset(size.width * .05f, size.height * 1.05f))
            }
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).background(Color.White.copy(alpha = .2f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Android, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(L("Share MudaPay app"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(L("Send the app to friends with a caption written by Muda AI"), color = Color.White.copy(alpha = .88f), fontSize = 12.sp, lineHeight = 16.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color.White)
    }
}

/* ------------------------------------------------------------------ share screen */
@Composable
fun ShareAppScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Session.user ?: JSONObject()
    val link = user.str("referral_link")
    val first = user.str("full_name").trim().substringBefore(" ")
    val country = user.str("country").let { if (it.isBlank()) "" else countryName(it) }

    var sendApk by remember { mutableStateOf(true) }
    var tone by remember { mutableStateOf("friendly") }
    var lang by remember { mutableStateOf(if (AppLanguage.current == "fr") "fr" else "en") }
    var note by remember { mutableStateOf("") }
    var seed by remember { mutableIntStateOf(0) }
    var aiRound by remember { mutableIntStateOf(0) }
    var caption by remember { mutableStateOf("") }
    var fromAi by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val apkInfo by produceState<ApkInfo?>(null) { value = withContext(Dispatchers.IO) { ShareApk.prepare(ctx) } }
    val apkReady = apkInfo?.file != null
    // Split installs cannot be sent as one file: fall back to the invite link on its own.
    LaunchedEffect(apkInfo) { if (apkInfo != null && apkInfo?.file == null) sendApk = false }

    val targets by produceState<List<ShareTarget>?>(null, sendApk) { value = withContext(Dispatchers.IO) { ShareApk.targets(ctx, sendApk) } }

    // Instant caption: rebuilt whenever an option changes (the member can still edit the text afterwards).
    LaunchedEffect(tone, lang, seed, link, note) {
        delay(350)
        caption = CaptionEngine.local(CaptionEngine.Spec(tone, lang, first, country, link, note, seed))
        fromAi = false
    }

    fun askMudaAi() {
        if (aiBusy) return
        if (Offline.offline) { Ui.notify("Muda AI", L("Muda AI needs an internet connection. Your instant caption is ready to use.")); return }
        aiBusy = true
        aiRound++
        val spec = CaptionEngine.Spec(tone, lang, first, country, link, note, seed + aiRound)
        scope.launch {
            val r = Api.call("POST", "/coach/messages", JSONObject().put("message", CaptionEngine.aiPrompt(spec)), timeoutMs = 140000)
            aiBusy = false
            val text = if (r.ok) (r.data.optJSONObject("reply")?.str("content") ?: "") else ""
            if (text.isNotBlank()) {
                caption = CaptionEngine.clean(text, link, CaptionEngine.joinLine(lang))
                fromAi = true
            } else {
                Ui.notify("Muda AI", if (r.ok) L("Muda AI sent an empty answer. Your instant caption is kept.") else r.message)
            }
        }
    }

    fun currentText(): String = caption.ifBlank { CaptionEngine.local(CaptionEngine.Spec(tone, lang, first, country, link, note, seed)) }

    fun sendVia(t: ShareTarget?) {
        val file = if (sendApk) apkInfo?.file else null
        if (sendApk && apkInfo == null) { Ui.notify(L("Share"), L("Preparing the app file… try again in a second.")); return }
        if (t == null) ShareApk.systemChooser(ctx, currentText(), file) else ShareApk.sendTo(ctx, t, currentText(), file)
    }

    val list = targets
    val popular = list?.filter { it.rank >= 0 }?.take(8) ?: emptyList()
    val shown = list?.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) } ?: emptyList()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ShareHero(link, if (sendApk) apkInfo?.sizeText ?: "" else "") }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(Icons.Default.Android, L("The app"), if (apkReady) L("Send the APK file") else L("Not available on this install"),
                    selected = sendApk, enabled = apkReady, modifier = Modifier.weight(1f)) { sendApk = true }
                ModeCard(Icons.Default.Link, L("Invite link"), L("Send only your link"),
                    selected = !sendApk, enabled = true, modifier = Modifier.weight(1f)) { sendApk = false }
            }
        }

        item {
            CaptionCard(
                tone = tone, onTone = { tone = it }, lang = lang, onLang = { lang = it },
                note = note, onNote = { note = it }, caption = caption, onCaption = { caption = it; fromAi = false },
                fromAi = fromAi, aiBusy = aiBusy, onAi = { askMudaAi() }, onNewIdea = { seed++ },
                onCopy = { copyToClipboard(ctx, "MudaPay", currentText()); Ui.notify(L("Copied"), L("Your caption was copied.")) }
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(L("Send it with"), L("Tap an app. The caption goes with it."))
                when {
                    list == null -> SkeletonCards(1)
                    popular.isEmpty() -> Unit
                    else -> popular.chunked(4).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { t -> QuickTile(t, Modifier.weight(1f)) { sendVia(t) } }
                            repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (sendApk) item {
            InfoBanner(L("Your friend may need to allow \"Install unknown apps\" for the app that received the file (WhatsApp, Files…). Their phone asks once."), Blue, Icons.Default.Info)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(L("All apps"), if (list != null) L("${list.size} apps can receive this") else null)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(L("Search apps")) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, cursorColor = Green, unfocusedBorderColor = Line)
                )
            }
        }

        if (list != null && shown.isEmpty()) item { EmptyState(L("No app found"), Icons.Default.SearchOff, L("Try another name, or use \"More apps…\" below.")) }
        items(shown, key = { it.key }) { t -> AppRow(t) { sendVia(t) } }

        item {
            PrimaryButton(L("MORE APPS…"), { sendVia(null) }, color = Dark, icon = Icons.Default.MoreHoriz)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ShareHero(link: String, sizeText: String) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Gradient)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = .10f), radius = size.width * .42f, center = Offset(size.width * .98f, size.height * .02f))
                drawCircle(Color.White.copy(alpha = .07f), radius = size.width * .26f, center = Offset(size.width * .02f, size.height * 1.0f))
            }
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(Color.White.copy(alpha = .2f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Share, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(L("Share MudaPay"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-.3).sp)
                Text(L("Bring friends in and earn a commission on their first deposit."), color = Color.White.copy(alpha = .9f), fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sizeText.isNotBlank()) HeroPill(Icons.Default.Android, "APK · $sizeText")
            HeroPill(Icons.Default.Link, L("Your invite link inside"))
        }
        if (link.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(Color.White.copy(alpha = .16f), RoundedCornerShape(14.dp)).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(link, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { copyToClipboard(ctx, "Invite link", link); Ui.notify(L("Copied"), L("Your invite link was copied.")) }) {
                    Icon(Icons.Default.ContentCopy, L("Copy"), tint = Color.White, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

@Composable
private fun HeroPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.background(Color.White.copy(alpha = .18f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ModeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.card(shape).then(if (selected) Modifier.border(BorderStroke(2.dp, Green), shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background((if (selected) Green else Slate).copy(alpha = .14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (selected) GreenDark else Slate, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.weight(1f))
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(20.dp))
        }
        Text(title, color = if (enabled) Ink else Slate, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(sub, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun CaptionCard(
    tone: String, onTone: (String) -> Unit, lang: String, onLang: (String) -> Unit,
    note: String, onNote: (String) -> Unit, caption: String, onCaption: (String) -> Unit,
    fromAi: Boolean, aiBusy: Boolean, onAi: () -> Unit, onNewIdea: () -> Unit, onCopy: () -> Unit
) {
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiOrb(size = 34.dp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Muda AI caption"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(L("A ready-to-post legend that describes MudaPay well."), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Pill(if (fromAi) softCase(L("WRITTEN BY AI")) else softCase(L("INSTANT")), if (fromAi) Blue else GreenDark)
        }

        Text(L("Tone"), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionEngine.TONES.forEach { (k, label) ->
                FilterChip(selected = tone == k, onClick = { onTone(k) }, label = { Text(L(label), fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.White))
            }
        }
        Text(L("Language"), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionEngine.LANGS.forEach { (k, label) ->
                FilterChip(selected = lang == k, onClick = { onLang(k) }, label = { Text(label, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Blue, selectedLabelColor = Color.White))
            }
        }

        InputField(L("Say it in your own words (optional)"), note, { onNote(it.take(240)) })

        OutlinedTextField(
            value = caption, onValueChange = onCaption, modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 14,
            shape = RoundedCornerShape(16.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = Ink),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, cursorColor = Green, unfocusedBorderColor = Line)
        )

        PrimaryButton(if (aiBusy) L("MUDA AI IS WRITING…") else softCase(L("WRITE IT WITH MUDA AI")), onAi, loading = aiBusy, icon = Icons.Default.AutoAwesome)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(softCase(L("NEW IDEA")), onNewIdea, Modifier.weight(1f), color = Dark, icon = Icons.Default.Refresh)
            PrimaryButton(softCase(L("COPY")), onCopy, Modifier.weight(1f), color = Dark, icon = Icons.Default.ContentCopy)
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, size: androidx.compose.ui.unit.Dp) {
    if (icon != null) Image(icon, null, Modifier.size(size).clip(RoundedCornerShape(size * .24f)))
    else Box(Modifier.size(size).background(Line, RoundedCornerShape(size * .24f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Apps, null, tint = Slate, modifier = Modifier.size(size * .55f))
    }
}

@Composable
private fun QuickTile(t: ShareTarget, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(62.dp).card(RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { AppIcon(t.icon, 42.dp) }
        Spacer(Modifier.height(6.dp))
        Text(t.label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AppRow(t: ShareTarget, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(t.icon, 42.dp)
        Spacer(Modifier.width(12.dp))
        Text(t.label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Pill(softCase(L("SEND")), GreenDark)
    }
}
