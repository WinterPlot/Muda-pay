package io.mudapay.mobile

import android.graphics.BitmapFactory
import android.util.LruCache
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/* ------------------------------------------------ design tokens (mudapay.css :root) */
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
val Gradient = Brush.linearGradient(listOf(Green, Blue))
val CardShape = RoundedCornerShape(22.dp)   // --r-card
val BtnShape = RoundedCornerShape(16.dp)    // --r-btn

/** White card with the site's soft shadow (--shadow-card). */
fun Modifier.card(shape: RoundedCornerShape = CardShape, color: Color = Color.White): Modifier =
    this.shadow(3.dp, shape, ambientColor = Color(0x1A000000), spotColor = Color(0x1A000000)).background(color, shape)

/* ------------------------------------------------ brand + headers */
@Composable
fun Brand(light: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            Modifier.size(38.dp).background(if (light) Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x33FFFFFF))) else Gradient, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.AccountBalanceWallet, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Column {
            Text("MudaPay", fontWeight = FontWeight.Black, fontSize = 20.sp, color = if (light) Color.White else Ink, letterSpacing = (-.5).sp)
            Text("GROW • LEARN • EARN", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (light) Color.White.copy(alpha = .8f) else Muted, letterSpacing = 1.sp)
        }
    }
}

/** Green→blue header with the bottom radius used on the site's dashboard. */
@Composable
fun GradientHeader(modifier: Modifier = Modifier, bottomPadding: Dp = 56.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Gradient, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
            .statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = bottomPadding),
        content = content
    )
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text("MudaPay", color = Muted, fontSize = 10.sp)
        }
        trailing()
    }
}

@Composable
fun PageIntro(title: String, subtitle: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(45.dp).background(Gradient, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = (-.3).sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Black)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

/* ------------------------------------------------ buttons + inputs */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
                  color: Color = Green, contentColor: Color = Color.White, icon: ImageVector? = null) {
    Button(
        onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor, disabledContainerColor = color.copy(alpha = .45f), disabledContentColor = contentColor.copy(alpha = .8f))
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = contentColor, strokeWidth = 2.5.dp)
        else {
            if (icon != null) { Icon(icon, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
    }
}

@Composable
fun InputField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text,
               password: Boolean = false, singleLine: Boolean = true, minLines: Int = 1, trailing: @Composable (() -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) }, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        singleLine = singleLine, minLines = minLines, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None, trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, focusedLabelColor = GreenDark, cursorColor = Green, unfocusedBorderColor = Line)
    )
}

@Composable
fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
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
    Row(Modifier.fillMaxWidth().background(tone.copy(alpha = .08f), RoundedCornerShape(15.dp)).padding(13.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = Ink, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
fun EmptyState(title: String, icon: ImageVector, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().card().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(Green.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = GreenDark, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(title, color = Ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun StatBox(title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.card().clickable(onClick = onClick).padding(vertical = 20.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Green, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = Dark, fontWeight = FontWeight.Black, fontSize = 15.sp, textAlign = TextAlign.Center)
        Text(subtitle.uppercase(), color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun ContentRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, tint: Color = GreenDark, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(tint.copy(alpha = .1f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (trailing != null) Text(trailing, color = GreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        else Icon(Icons.Default.ChevronRight, null, tint = Slate)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(text, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, modifier = Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/* ------------------------------------------------ loading / error / remote data */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green, strokeWidth = 3.dp) }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.CloudOff, null, tint = Slate, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp))
        Text(message, color = Ink, textAlign = TextAlign.Center, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        PrimaryButton("TRY AGAIN", onRetry, Modifier.width(200.dp), icon = Icons.Default.Refresh)
    }
}

/** Loads once (and again when [refreshKey] changes or the content asks for a reload). */
@Composable
fun Remote(refreshKey: Any? = null, call: suspend () -> ApiResult, content: @Composable (JSONObject, () -> Unit) -> Unit) {
    var result by remember { mutableStateOf<ApiResult?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey, tick) { result = call() }
    val r = result
    when {
        r == null -> LoadingBox()
        r.ok -> content(r.data) { tick++ }
        else -> ErrorBox(r.message) { result = null; tick++ }
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
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bmp by produceState<ImageBitmap?>(null, url) { value = ImageCache.load(url) }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = contentScale) else Box(modifier.background(Line))
}

@Composable
fun Flag(code: String, size: Dp = 22.dp) {
    RemoteImage(flagUrl(code), Modifier.size(size).clip(CircleShape).border(1.5.dp, Color.White, CircleShape), ContentScale.Crop)
}
