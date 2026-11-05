package io.mudapay.mobile

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils

enum class ThemeMode { System, Light, Dark }

/**
 * Light / dark theme of the signed-in app, chosen in Settings > Appearance (kept on the phone).
 * The colour names used everywhere (Page, Ink, Muted, Soft, Green...) are getters that read this object,
 * so one switch recolours every screen at once. The light colours are exactly the previous theme.
 * The sign-in screens follow it too: AuthTheme.s adapts the member's auth look (hero colours, accent) to dark.
 */
object MudaTheme {
    const val DEFAULT_ACCENT = 0xFF0B8F6AL
    val ACCENTS = listOf(0xFF0B8F6AL, 0xFF2563EBL, 0xFF7C3AEDL, 0xFFEA580CL, 0xFFE11D48L, 0xFF0891B2L)

    var mode by mutableStateOf(ThemeMode.System)
    var pureBlack by mutableStateOf(false)
    var accent by mutableStateOf(DEFAULT_ACCENT)
    var systemDark by mutableStateOf(false)   // the phone's own setting, refreshed by MudaPayApp
    private var loaded = false

    val dark: Boolean
        get() = when (mode) {
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
            ThemeMode.System -> systemDark
        }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("mudapay_ui", Context.MODE_PRIVATE)

    /** The phone's own dark setting (the widget and the first frame read it before any screen exists). */
    fun syncSystem(ctx: Context) {
        systemDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        syncSystem(ctx)
        val p = prefs(ctx)
        mode = try { ThemeMode.valueOf(p.getString("theme_mode", "System") ?: "System") } catch (_: Exception) { ThemeMode.System }
        pureBlack = p.getBoolean("theme_black", false)
        val a = p.getLong("theme_accent", DEFAULT_ACCENT)
        accent = if (a in ACCENTS) a else DEFAULT_ACCENT
    }
    fun setMode(ctx: Context, m: ThemeMode) { mode = m; prefs(ctx).edit().putString("theme_mode", m.name).apply(); WidgetSync.refresh(ctx) }
    fun setBlack(ctx: Context, v: Boolean) { pureBlack = v; prefs(ctx).edit().putBoolean("theme_black", v).apply(); WidgetSync.refresh(ctx) }
    fun setAccent(ctx: Context, c: Long) { accent = c; prefs(ctx).edit().putLong("theme_accent", c).apply(); WidgetSync.refresh(ctx) }
    fun reset(ctx: Context) { setMode(ctx, ThemeMode.System); setBlack(ctx, false); setAccent(ctx, DEFAULT_ACCENT) }

    private fun c(light: Long, dark: Long, black: Long = dark): Color =
        Color(if (!this.dark) light else if (pureBlack) black else dark)

    private fun hsl(argb: Int): FloatArray { val h = FloatArray(3); ColorUtils.colorToHSL(argb, h); return h }

    // ---- palette (light = the previous theme, unchanged)
    val page: Color get() = c(0xFFF8FAFC, 0xFF0B1220, 0xFF000000)
    val panel: Color get() = c(0xFFFFFFFF, 0xFF121B2E, 0xFF0C0C0E)
    val soft: Color get() = c(0xFFF1F5F9, 0xFF1B2740, 0xFF1A1A1D)
    val hairline: Color get() = c(0xFFE8EDF3, 0xFF223049, 0xFF252528)
    val line: Color get() = c(0xFFE2E8F0, 0xFF2D3D5A, 0xFF35353A)
    val ink: Color get() = c(0xFF1E293B, 0xFFE6ECF5)
    val title: Color get() = c(0xFF0B1F3A, 0xFFF3F6FB)
    val muted: Color get() = c(0xFF64748B, 0xFF9AA8BF)
    val slate: Color get() = c(0xFF94A3B8, 0xFF7D8CA6)
    val blue: Color get() = c(0xFF2563EB, 0xFF6B9BFF)
    val danger: Color get() = c(0xFFEF4444, 0xFFF87171)
    val amber: Color get() = c(0xFFB45309, 0xFFFBBF24)
    val darkFill: Color get() = c(0xFF0B1F3A, 0xFF2A3A56, 0xFF2A2A2E)   // dark buttons that keep white text

    val green: Color get() = Color(accent)
    /** Deep accent for SOLID fills that carry white text (same in light and dark). */
    val greenFill: Color
        get() {
            if (accent == DEFAULT_ACCENT) return Color(0xFF066B50)
            val h = hsl(accent.toInt()); h[2] = h[2] * 0.74f
            return Color(ColorUtils.HSLToColor(h))
        }
    /** Accent for text and icons on the surface: deep in light mode, light in dark mode. */
    val greenDark: Color
        get() {
            if (!dark) return greenFill
            val h = hsl(accent.toInt()); h[1] = h[1] * 0.85f; h[2] = maxOf(h[2], 0.62f)   // readable on a dark surface
            return Color(ColorUtils.HSLToColor(h))
        }
    val blueFill: Color get() = Color(0xFF2563EB)                              // solid fills with white text
    val dangerFill: Color get() = if (dark) Color(0xFFDC2626) else Color(0xFFEF4444)
    val onTone: Color get() = if (dark) Color(0xFF08101D) else Color.White      // icon on a solid fill made with a theme tone (lighter in dark)

    /** A light tinted background of the old light-only design (pastel red, mint...) becomes a deep tint of the same hue in dark mode. */
    fun tint(argb: Long): Color {
        if (!dark) return Color(argb)
        val h = hsl(argb.toInt())
        h[1] = minOf(1f, maxOf(h[1], 0.35f) * 0.9f)
        h[2] = if (pureBlack) 0.11f else 0.17f
        return Color(ColorUtils.HSLToColor(h))
    }
}

/** Same as [MudaTheme.tint], short to write in a screen. */
fun Tint(argb: Long): Color = MudaTheme.tint(argb)

@Composable
fun mudaColorScheme(): ColorScheme =
    if (MudaTheme.dark) darkColorScheme(
        primary = Green, secondary = Blue, background = Page, surface = Panel, onSurface = Ink, onBackground = Ink,
        surfaceVariant = Soft, onSurfaceVariant = Muted, outline = Line,
        secondaryContainer = Green.copy(alpha = 0.25f).compositeOver(Panel), onSecondaryContainer = Ink
    ) else lightColorScheme(primary = Green, secondary = Blue, background = Page, surface = Panel, onSurface = Ink)

/* ------------------------------------------------------------------ Settings > Appearance */
@Composable
fun AppearanceSettings() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Appearance"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("Choose how MudaPay looks. Changes apply at once."), color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice(L("Automatic"), ThemeMode.System, Modifier.weight(1f))
            ThemeChoice(L("Light theme"), ThemeMode.Light, Modifier.weight(1f))
            ThemeChoice(L("Dark theme"), ThemeMode.Dark, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(L("Pure black"), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(L("Deepest dark, saves battery on OLED screens."), color = Muted, fontSize = 12.sp)
            }
            Switch(
                checked = MudaTheme.pureBlack, onCheckedChange = { MudaTheme.setBlack(ctx, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.White)
            )
        }
        Text(L("Accent color"), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MudaTheme.ACCENTS.forEach { a ->
                val on = MudaTheme.accent == a
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(a))
                        .border(if (on) 3.dp else 0.dp, Panel, CircleShape).clickable { MudaTheme.setAccent(ctx, a) },
                    contentAlignment = Alignment.Center
                ) { if (on) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
        }
        TextButton(onClick = { MudaTheme.reset(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text(L("Reset appearance"), color = GreenDark, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ThemeChoice(label: String, m: ThemeMode, modifier: Modifier) {
    val ctx = LocalContext.current
    val on = MudaTheme.mode == m
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).border(if (on) 2.dp else 1.dp, if (on) Green else Hairline, shape)
            .clickable { MudaTheme.setMode(ctx, m) }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp))) {
            when (m) {
                ThemeMode.Light -> PreviewHalf(Color(0xFFF8FAFC), Color.White, Modifier.fillMaxSize())
                ThemeMode.Dark -> PreviewHalf(Color(0xFF0B1220), Color(0xFF121B2E), Modifier.fillMaxSize())
                ThemeMode.System -> Row(Modifier.fillMaxSize()) {
                    PreviewHalf(Color(0xFFF8FAFC), Color.White, Modifier.weight(1f).fillMaxHeight())
                    PreviewHalf(Color(0xFF0B1220), Color(0xFF121B2E), Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        Text(label, color = if (on) GreenDark else Ink, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun PreviewHalf(bg: Color, card: Color, modifier: Modifier) {
    Box(modifier.background(bg).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().height(18.dp).align(Alignment.BottomCenter).background(card, RoundedCornerShape(5.dp)))
        Box(Modifier.size(10.dp).background(Green, CircleShape))
    }
}
