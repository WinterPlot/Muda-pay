package io.mudapay.mobile

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/* ------------------------------------------------ design tokens (same palette as mudapay.css :root) */
val Green = Color(0xFF00BF8E)
val GreenDark = Color(0xFF008F6A)
val Blue = Color(0xFF0061FF)
val Gold = Color(0xFFFFD700)
val Dark = Color(0xFF0F172A)
val Ink = Color(0xFF1E293B)
val Muted = Color(0xFF64748B)
val Line = Color(0xFFE2E8F0)
val Page = Color(0xFFF8FAFC)
val Danger = Color(0xFFEF4444)
val Slate = Color(0xFF94A3B8)
val Soft = Color(0xFFF1F5F9)       // quiet fill for chips, inputs and secondary buttons
val Hairline = Color(0xFFE8EDF3)   // 1 dp borders and dividers
val Gradient = Brush.linearGradient(listOf(Green, Blue))
val CardShape = RoundedCornerShape(20.dp)   // one radius for every card
val BtnShape = RoundedCornerShape(16.dp)    // one radius for every button and field

/* ------------------------------------------------ text helper */
private val KEEP_UPPER = setOf("QR", "ID", "AI", "APK", "PIN", "SMS", "OTP", "USD", "KYC", "FX", "BTC", "ETH", "USDT", "SOL", "URL", "FAQ", "VIP", "UI", "FCM")
private val BRAND_WORDS = mapOf("MUDAPAY" to "MudaPay", "MUDA" to "Muda", "WHATSAPP" to "WhatsApp", "LUMICASH" to "Lumicash", "AIRTEL" to "Airtel", "GOOGLE" to "Google", "TELEGRAM" to "Telegram")

/** Turns an ALL-CAPS label into a calm sentence-case one ("COPY WALLET ID" -> "Copy wallet ID"). Mixed-case text and text with numbers is returned unchanged. */
fun softCase(s: String): String {
    if (s.length < 3 || s.any { it.isDigit() }) return s
    val letters = s.filter { it.isLetter() }
    if (letters.length < 3 || letters.any { it.isLowerCase() }) return s
    var first = true
    return s.split(" ").joinToString(" ") { w ->
        val core = w.filter { it.isLetter() }
        if (core.isEmpty()) return@joinToString w
        var out = when {
            BRAND_WORDS.containsKey(core) -> w.replace(core, BRAND_WORDS[core] ?: core)
            core in KEEP_UPPER -> w
            else -> w.lowercase()
        }
        if (first) {
            val i = out.indexOfFirst { it.isLetter() }
            if (i >= 0) out = out.substring(0, i) + out[i].uppercaseChar() + out.substring(i + 1)
            first = false
        }
        out
    }
}

/* ------------------------------------------------ surfaces */
/** White card: hairline border and a barely-there shadow. Flat on purpose, so a screen full of cards stays calm. */
fun Modifier.card(shape: RoundedCornerShape = CardShape, color: Color = Color.White): Modifier =
    this.shadow(2.dp, shape, ambientColor = Color(0x0D0F172A), spotColor = Color(0x140F172A))
        .background(color, shape)
        .border(1.dp, Hairline, shape)

/** The green-to-blue hero surface used for balances. Pair it with .padding(…). */
fun Modifier.heroCard(shape: RoundedCornerShape = RoundedCornerShape(26.dp)): Modifier =
    this.shadow(8.dp, shape, ambientColor = Green.copy(alpha = .22f), spotColor = Blue.copy(alpha = .30f))
        .clip(shape)
        .background(Gradient)
        .drawBehind {
            drawCircle(Color.White.copy(alpha = .08f), radius = size.minDimension * .75f, center = Offset(size.width * .95f, 0f))
            drawCircle(Color.White.copy(alpha = .05f), radius = size.minDimension * .5f, center = Offset(size.width * .04f, size.height * 1.05f))
        }

/** Rounded square holding an icon, tinted with [tint]. The one icon treatment used everywhere. */
@Composable
fun IconChip(icon: ImageVector, tint: Color = GreenDark, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(tint.copy(alpha = .12f), RoundedCornerShape(size * .32f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * .5f))
    }
}

/* ------------------------------------------------ brand + headers */
@Composable
fun Brand(light: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(36.dp).background(if (light) Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x33FFFFFF))) else Gradient, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.AccountBalanceWallet, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Column {
            Text(L("MudaPay"), fontWeight = FontWeight.Bold, fontSize = 19.sp, color = if (light) Color.White else Ink, letterSpacing = (-.4).sp)
            Text(L("Grow • Learn • Earn"), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = if (light) Color.White.copy(alpha = .8f) else Muted)
        }
    }
}

/** Full-width green-to-blue banner. Kept for the sign-up activation screen; tabs use [TabHeader] + [BalanceHero]. */
@Composable
fun GradientHeader(modifier: Modifier = Modifier, bottomPadding: Dp = 56.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Gradient)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = .08f), radius = size.width * .42f, center = Offset(size.width * .98f, size.height * .05f))
                drawCircle(Color.White.copy(alpha = .05f), radius = size.width * .28f, center = Offset(size.width * .02f, size.height * .98f))
            }
            .statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = bottomPadding),
        content = content
    )
}

/** Large title at the top of a main tab (Home, Videos, Tasks, Withdraw, Profile). */
@Composable
fun TabHeader(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Dark, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

/** Round icon button used in headers. [badge] shows a small gold counter. */
@Composable
fun HeaderIcon(icon: ImageVector, description: String, onClick: () -> Unit, badge: Int = 0) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Soft).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        BadgedBox(badge = { if (badge > 0) Badge(containerColor = Gold, contentColor = Dark) { Text(if (badge > 99) "99+" else badge.toString()) } }) {
            Icon(icon, description, tint = Ink, modifier = Modifier.size(21.dp))
        }
    }
}

/** Balance card: label, big amount, optional caption and extra content. */
@Composable
fun BalanceHero(label: String, amount: String, caption: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().heroCard().padding(horizontal = 22.dp, vertical = 20.dp)) {
        Text(label, color = Color.White.copy(alpha = .85f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(amount, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 2.dp))
        if (caption != null) Text(caption, color = Color.White.copy(alpha = .85f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        content()
    }
}

/** Header of every inner screen: round back button, one title, hairline underneath. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Soft).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
    }
}

@Composable
fun PageIntro(title: String, subtitle: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, GreenDark, 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, color = Dark, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, color = Dark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
    }
}

/** Section label with an optional action on the right ("See all"). */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Dark, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = GreenDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

/* ------------------------------------------------ buttons + inputs */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
                  color: Color = Green, contentColor: Color = Color.White, icon: ImageVector? = null) {
    val label = softCase(text)
    if (color == Green) {
        val active = enabled && !loading
        Box(
            modifier.fillMaxWidth().height(54.dp).clip(BtnShape)
                .background(if (enabled) Gradient else Brush.linearGradient(listOf(Green.copy(alpha = .45f), Blue.copy(alpha = .45f))))
                .clickable(enabled = active, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = contentColor, strokeWidth = 2.5.dp)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = contentColor, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
                Text(label, color = contentColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
        return
    }
    Button(
        onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().height(54.dp), shape = BtnShape,
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor, disabledContainerColor = color.copy(alpha = .45f), disabledContentColor = contentColor.copy(alpha = .8f))
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = contentColor, strokeWidth = 2.5.dp)
        else {
            if (icon != null) { Icon(icon, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

/** Compact secondary button. Use two side by side with Modifier.weight(1f). [onDark] for buttons that sit on a dark card. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, onDark: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    val fg = if (onDark) Color.White else Ink
    val bg = if (onDark) Color.White.copy(alpha = .12f) else Soft
    Row(
        modifier.height(46.dp).clip(shape).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)) }
        Text(softCase(text), color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun InputField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text,
               password: Boolean = false, singleLine: Boolean = true, minLines: Int = 1, trailing: @Composable (() -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) }, modifier = modifier.fillMaxWidth(), shape = BtnShape,
        singleLine = singleLine, minLines = minLines, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None, trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Green, focusedLabelColor = GreenDark, cursorColor = Green, unfocusedBorderColor = Line,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, unfocusedLabelColor = Muted
        )
    )
}

@Composable
fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(
                onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = BtnShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)
            ) {
                Text(options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "Select" }, color = Ink, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Default.ExpandMore, null, tint = Muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Color.White)) {
                options.forEach { (k, v) -> DropdownMenuItem(text = { Text(v, color = Ink) }, onClick = { onSelect(k); open = false }) }
            }
        }
    }
}

/* ------------------------------------------------ cards + rows */
@Composable
fun InfoBanner(text: String, tone: Color = Blue, icon: ImageVector = Icons.Default.Info) {
    Row(Modifier.fillMaxWidth().background(tone.copy(alpha = .08f), RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
fun EmptyState(title: String, icon: ImageVector, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().card().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconChip(icon, GreenDark, 56.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Tile with an icon, a headline value and a caption. Two per row, with Modifier.weight(1f). */
@Composable
fun StatBox(title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.card().clip(CardShape).clickable(onClick = onClick).padding(16.dp)) {
        IconChip(icon, GreenDark, 38.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(softCase(subtitle), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
fun ContentRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, tint: Color = GreenDark, trailing: String? = null) {
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().card(shape).clip(shape).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconChip(icon, tint, 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 1.dp))
        }
        if (trailing != null) Text(trailing, color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        else Icon(Icons.Default.ChevronRight, null, tint = Slate)
    }
}

/** One white card that holds several [MenuRow]s separated by hairlines. Much calmer than a stack of separate cards. */
@Composable
fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().card().clip(CardShape), content = content)
}

@Composable
fun MenuRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, tint: Color = GreenDark, last: Boolean = false, trailing: String? = null) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, tint, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Text(trailing, color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            else Icon(Icons.Default.ChevronRight, null, tint = Slate, modifier = Modifier.size(20.dp))
        }
        if (!last) Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(1.dp).background(Hairline))
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(softCase(text), color = color, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/* ------------------------------------------------ loading / error / remote data */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green, strokeWidth = 3.dp) }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, offline: Boolean = false) {
    if (offline) { OfflineState(onRetry); return }
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.CloudOff, null, tint = Slate, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp))
        Text(message, color = Ink, textAlign = TextAlign.Center, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        PrimaryButton("TRY AGAIN", onRetry, Modifier.width(200.dp), icon = Icons.Default.Refresh)
    }
}

/** Pulsing placeholder cards shown while a screen loads for the first time. */
@Composable
fun SkeletonCards(count: Int = 3) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(.35f, .85f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(.45f).height(14.dp).background(Line.copy(alpha = a), RoundedCornerShape(7.dp)))
                Box(Modifier.fillMaxWidth().height(12.dp).background(Line.copy(alpha = a), RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(.7f).height(12.dp).background(Line.copy(alpha = a), RoundedCornerShape(6.dp)))
            }
        }
    }
}

/** Loads once (and again when [refreshKey] changes, on pull-to-refresh, or when the content asks for a reload). */
@Composable
fun Remote(refreshKey: Any? = null, call: suspend () -> ApiResult, content: @Composable (JSONObject, () -> Unit) -> Unit) {
    var result by remember { mutableStateOf<ApiResult?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey, tick, Ui.refreshTick) {
        val fresh = call()
        val old = result
        // a failed refresh keeps what is already on screen instead of replacing it with an error
        result = if (!fresh.ok && old != null && old.ok) old else fresh
    }
    val up = Offline.networkUp
    LaunchedEffect(up) {
        val c = result
        if (up && c != null && (c.code == "network" || c.cached)) tick++
    }
    val r = result
    when {
        r == null -> SkeletonCards()
        r.ok -> {
            if (r.cached) StaleNote(r.cachedAt)
            content(r.data) { tick++ }
        }
        else -> ErrorBox(r.message, { result = null; tick++ }, offline = r.code == "network")
    }
}

/* ------------------------------------------------ images */
object ImageCache {
    private val cache = LruCache<String, ImageBitmap>(40)
    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            try {
                val u = if (url.startsWith(Config.SITE)) url + (if (url.contains("?")) "&i=1" else "?i=1") else url
                URL(u).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            } catch (e: Exception) { null }
        }
        if (bmp != null) cache.put(url, bmp)
        return bmp
    }
}

@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, tapToLoad: Boolean = true, essential: Boolean = false) {
    var forced by remember(url) { mutableStateOf(false) }
    if (Offline.lowData && !essential && !forced) {
        Box(
            modifier.background(Line).then(if (tapToLoad) Modifier.clickable { forced = true } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (tapToLoad) Icon(Icons.Default.Image, null, tint = Slate)
        }
    } else {
        val bmp by produceState<ImageBitmap?>(null, url) { value = ImageCache.load(url) }
        val b = bmp
        if (b != null) Image(b, null, modifier, contentScale = contentScale) else Box(modifier.background(Line))
    }
}

@Composable
fun Flag(code: String, size: Dp = 22.dp) {
    RemoteImage(flagUrl(code), Modifier.size(size).clip(CircleShape).border(1.5.dp, Color.White, CircleShape), ContentScale.Crop, essential = true)
}
