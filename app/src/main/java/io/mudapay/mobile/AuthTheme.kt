package io.mudapay.mobile

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

/* ====================================================================================================
 *  Look of the signed-out screens (Welcome, Login, Register, Forgot password, Google profile).
 *
 *  EVERYTHING that defines that look lives in [AuthStyle] below. To change the default look of the app,
 *  edit the first entry of [AUTH_PRESETS] (or add a preset). Members can also change it live from the
 *  palette button on the Welcome screen: it opens [AuthCustomizeDialog] and the choice is remembered.
 * ==================================================================================================== */

enum class AuthLayout { Sheet, Card }              // Sheet = white panel rising from the bottom; Card = floating card
enum class FieldKind { Outlined, Filled, Underline }
enum class HeroDecor { Circles, Rings, None }

data class AuthStyle(
    val id: String = "emerald",
    // header (the coloured area behind the logo and the titles)
    val heroTop: Color, val heroBottom: Color, val onHero: Color = Color.White,
    // buttons, links, focus colour
    val accent: Color, val accentDark: Color, val onAccent: Color = Color.White, val gradientButtons: Boolean = false,
    // structure
    val layout: AuthLayout = AuthLayout.Sheet, val field: FieldKind = FieldKind.Filled, val decor: HeroDecor = HeroDecor.Circles,
    // sizes (dp)
    val fieldRadius: Int = 16, val buttonRadius: Int = 16, val sheetRadius: Int = 32, val buttonHeight: Int = 54, val titleSize: Int = 30,
    // details
    val logoCircle: Boolean = false, val showChips: Boolean = true, val showTagline: Boolean = true
) {
    // form surface: live theme colours (white / dark panel), never stored
    val sheet: Color get() = Panel
    val ink: Color get() = Ink
    val muted: Color get() = Muted
    val line: Color get() = Line
    val fieldFill: Color get() = Soft

    val heroIsLight: Boolean get() = heroTop.luminance() > .6f
    val heroBtnBg: Color get() = if (heroIsLight) accent else Color.White
    val heroBtnFg: Color get() = if (heroIsLight) onAccent else heroBottom
    val logoBg: Color get() = if (heroIsLight) accent else Color.White
    val logoFg: Color get() = if (heroIsLight) onAccent else heroBottom

    /** The same look in dark mode: a light header becomes a deep tint of the accent, bright headers get deeper, links get lighter. */
    fun forDark(): AuthStyle {
        val page = Page
        val lightHero = heroIsLight
        val h = FloatArray(3)
        ColorUtils.colorToHSL(accentDark.toArgb(), h); h[1] = h[1] * 0.85f; h[2] = maxOf(h[2], 0.62f)
        return copy(
            heroTop = if (lightHero) lerp(accent, page, .82f) else lerp(heroTop, page, .30f),
            heroBottom = if (lightHero) page else lerp(heroBottom, page, .30f),
            onHero = if (lightHero) Color.White else onHero,
            accentDark = Color(ColorUtils.HSLToColor(h))
        )
    }

    /** New header colour: derives the second gradient stop and the text colour on top of it. */
    fun withHero(c: Color): AuthStyle {
        val light = c.luminance() > .6f
        return copy(
            id = "custom", heroTop = c,
            heroBottom = if (light) lerp(c, Color.White, .6f) else lerp(c, Color.Black, .35f),
            onHero = if (light) Ink else Color.White
        )
    }

    /** New button / link colour: derives the darker tone and the text colour on top of it. */
    fun withAccent(c: Color): AuthStyle =
        copy(id = "custom", accent = c, accentDark = lerp(c, Color.Black, .3f), onAccent = if (c.luminance() > .6f) Ink else Color.White)
}

/** The first preset is the default look. */
val AUTH_PRESETS: List<AuthStyle> = listOf(
    AuthStyle(
        id = "midnight", heroTop = Navy, heroBottom = NavySoft, accent = Color(0xFF0B8F6A), accentDark = Color(0xFF066B50),
        layout = AuthLayout.Card, field = FieldKind.Outlined, sheetRadius = 28
    ),
    AuthStyle(id = "emerald", heroTop = Color(0xFF0FA67C), heroBottom = Color(0xFF056047), accent = Color(0xFF0B8F6A), accentDark = Color(0xFF066B50)),
    AuthStyle(id = "ocean", heroTop = Color(0xFF2F6FF0), heroBottom = Color(0xFF1B3A8C), accent = Color(0xFF2563EB), accentDark = Color(0xFF1D4ED8), gradientButtons = true),
    AuthStyle(id = "violet", heroTop = Color(0xFF8B5CF6), heroBottom = Color(0xFF4C1D95), accent = Color(0xFF7C3AED), accentDark = Color(0xFF5B21B6), gradientButtons = true, logoCircle = true),
    AuthStyle(id = "sunset", heroTop = Color(0xFFFB923C), heroBottom = Color(0xFFBE3A12), accent = Color(0xFFEA580C), accentDark = Color(0xFFC2410C), field = FieldKind.Underline, decor = HeroDecor.Rings),
    AuthStyle(
        id = "light", heroTop = Color(0xFFE7F6F0), heroBottom = Color(0xFFF8FAFC), onHero = Color(0xFF1E293B), accent = Color(0xFF0B8F6A), accentDark = Color(0xFF066B50),
        field = FieldKind.Outlined, decor = HeroDecor.Rings
    )
)

object AuthTheme {
    private const val PREF = "mudapay_auth_theme"
    /** What the member chose (saved as is). */
    var raw by mutableStateOf(AUTH_PRESETS[0])
        private set
    /** What the screens draw: the chosen look, adapted when the app is in dark mode. */
    val s: AuthStyle get() = if (MudaTheme.dark) raw.forDark() else raw
    private var ctx: Context? = null
    private var loaded = false

    fun load(c: Context) {
        ctx = c.applicationContext
        if (loaded) return
        loaded = true
        val txt = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("style", null) ?: return
        raw = runCatching { styleFromJson(JSONObject(txt), AUTH_PRESETS[0]) }.getOrDefault(AUTH_PRESETS[0])
    }

    fun set(n: AuthStyle) {
        raw = n
        ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putString("style", styleToJson(n).toString())?.apply()
    }

    fun update(f: (AuthStyle) -> AuthStyle) = set(f(raw))   // always from what the member chose, never from the dark-adapted view
    fun reset() = set(AUTH_PRESETS[0])
}

private fun styleToJson(a: AuthStyle): JSONObject = JSONObject().apply {
    put("id", a.id)
    put("heroTop", a.heroTop.toArgb()); put("heroBottom", a.heroBottom.toArgb()); put("onHero", a.onHero.toArgb())
    put("accent", a.accent.toArgb()); put("accentDark", a.accentDark.toArgb()); put("onAccent", a.onAccent.toArgb())
    put("gradientButtons", a.gradientButtons)
    put("layout", a.layout.name); put("field", a.field.name); put("decor", a.decor.name)
    put("fieldRadius", a.fieldRadius); put("buttonRadius", a.buttonRadius); put("sheetRadius", a.sheetRadius)
    put("buttonHeight", a.buttonHeight); put("titleSize", a.titleSize)
    put("logoCircle", a.logoCircle); put("showChips", a.showChips); put("showTagline", a.showTagline)
}

private inline fun <reified E : Enum<E>> JSONObject.enumOr(key: String, default: E): E =
    runCatching { enumValueOf<E>(optString(key, default.name)) }.getOrDefault(default)

private fun styleFromJson(j: JSONObject, b: AuthStyle): AuthStyle = b.copy(
    id = j.optString("id", b.id),
    heroTop = Color(j.optInt("heroTop", b.heroTop.toArgb())), heroBottom = Color(j.optInt("heroBottom", b.heroBottom.toArgb())),
    onHero = Color(j.optInt("onHero", b.onHero.toArgb())),
    accent = Color(j.optInt("accent", b.accent.toArgb())), accentDark = Color(j.optInt("accentDark", b.accentDark.toArgb())),
    onAccent = Color(j.optInt("onAccent", b.onAccent.toArgb())),
    gradientButtons = j.optBoolean("gradientButtons", b.gradientButtons),
    layout = j.enumOr("layout", b.layout), field = j.enumOr("field", b.field), decor = j.enumOr("decor", b.decor),
    fieldRadius = j.optInt("fieldRadius", b.fieldRadius), buttonRadius = j.optInt("buttonRadius", b.buttonRadius),
    sheetRadius = j.optInt("sheetRadius", b.sheetRadius), buttonHeight = j.optInt("buttonHeight", b.buttonHeight),
    titleSize = j.optInt("titleSize", b.titleSize),
    logoCircle = j.optBoolean("logoCircle", b.logoCircle), showChips = j.optBoolean("showChips", b.showChips),
    showTagline = j.optBoolean("showTagline", b.showTagline)
)

/* ====================================================================================================
 *  Customizer dialog (opened from the palette button on the Welcome screen). Every change applies live.
 * ==================================================================================================== */

private val HERO_SWATCHES = listOf(0xFF0FA67C, 0xFF0B1F3A, 0xFF2F6FF0, 0xFF8B5CF6, 0xFFFB923C, 0xFFE11D48, 0xFF0F172A, 0xFFE7F6F0).map { Color(it) }
private val ACCENT_SWATCHES = listOf(0xFF0B8F6A, 0xFF2563EB, 0xFF7C3AED, 0xFFEA580C, 0xFFE11D48, 0xFF0F172A, 0xFFD4A017, 0xFF0891B2).map { Color(it) }

@Composable
private fun CustomLabel(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
}

@Composable
private fun Swatch(fill: Brush, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(fill)
            .border(if (selected) 3.dp else 1.dp, if (selected) GreenDark else Hairline, CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { if (selected) Icon(MudaIcons.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(12.dp)).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Panel else Color.Transparent)
                    .clickable { onPick(value) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (on) GreenDark else Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$value", color = Muted, fontSize = 12.sp)
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Line)
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Green))
    }
}

@Composable
fun AuthCustomizeDialog(onClose: () -> Unit) {
    val st = AuthTheme.s
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 680.dp).clip(RoundedCornerShape(24.dp)).background(Panel)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Customize the look"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(L("Done"), color = GreenDark, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CustomLabel(L("Appearance"))
                ChoiceRow(
                    listOf(ThemeMode.System to L("Automatic"), ThemeMode.Light to L("Light theme"), ThemeMode.Dark to L("Dark theme")),
                    MudaTheme.mode
                ) { m -> MudaTheme.setMode(ctx, m) }
                CustomLabel(L("Themes"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AUTH_PRESETS.forEach { p ->
                        Swatch(Brush.linearGradient(listOf(p.heroTop, p.heroBottom)), st.id == p.id) { AuthTheme.set(p) }
                    }
                }
                CustomLabel(L("Header color"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HERO_SWATCHES.forEach { c -> Swatch(Brush.linearGradient(listOf(c, c)), st.heroTop == c) { AuthTheme.update { it.withHero(c) } } }
                }
                CustomLabel(L("Button color"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ACCENT_SWATCHES.forEach { c -> Swatch(Brush.linearGradient(listOf(c, c)), st.accent == c) { AuthTheme.update { it.withAccent(c) } } }
                }
                CustomLabel(L("Layout"))
                ChoiceRow(listOf(AuthLayout.Sheet to L("Sheet"), AuthLayout.Card to L("Card")), st.layout) { v -> AuthTheme.update { it.copy(layout = v) } }
                CustomLabel(L("Fields"))
                ChoiceRow(
                    listOf(FieldKind.Outlined to L("Outlined"), FieldKind.Filled to L("Filled"), FieldKind.Underline to L("Underline")), st.field
                ) { v -> AuthTheme.update { it.copy(field = v) } }
                CustomLabel(L("Decoration"))
                ChoiceRow(
                    listOf(HeroDecor.Circles to L("Circles"), HeroDecor.Rings to L("Rings"), HeroDecor.None to L("None")), st.decor
                ) { v -> AuthTheme.update { it.copy(decor = v) } }
                SliderRow(L("Corner roundness"), st.buttonRadius, 0..28) { v -> AuthTheme.update { it.copy(buttonRadius = v, fieldRadius = v) } }
                SliderRow(L("Panel roundness"), st.sheetRadius, 0..44) { v -> AuthTheme.update { it.copy(sheetRadius = v) } }
                SliderRow(L("Button height"), st.buttonHeight, 44..64) { v -> AuthTheme.update { it.copy(buttonHeight = v) } }
                SliderRow(L("Title size"), st.titleSize, 24..38) { v -> AuthTheme.update { it.copy(titleSize = v) } }
                SwitchRow(L("Gradient buttons"), st.gradientButtons) { v -> AuthTheme.update { it.copy(gradientButtons = v) } }
                SwitchRow(L("Round logo"), st.logoCircle) { v -> AuthTheme.update { it.copy(logoCircle = v) } }
                SwitchRow(L("Show quick actions"), st.showChips) { v -> AuthTheme.update { it.copy(showChips = v) } }
                SwitchRow(L("Show tagline"), st.showTagline) { v -> AuthTheme.update { it.copy(showTagline = v) } }
                TextButton(onClick = { AuthTheme.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(L("Reset to default"), color = Danger, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** French wording of the strings above and of the new auth screens (chained in L()). */
val frenchAuthTheme = mapOf(
    "Customize the look" to "Personnaliser l’apparence",
    "Customize" to "Personnaliser",
    "Done" to "Terminé",
    "Themes" to "Thèmes",
    "Header color" to "Couleur de l’en-tête",
    "Button color" to "Couleur des boutons",
    "Layout" to "Disposition",
    "Sheet" to "Panneau",
    "Card" to "Carte",
    "Fields" to "Champs",
    "Outlined" to "Contour",
    "Filled" to "Rempli",
    "Underline" to "Souligné",
    "Decoration" to "Décoration",
    "Circles" to "Cercles",
    "Rings" to "Anneaux",
    "None" to "Aucune",
    "Corner roundness" to "Arrondi des coins",
    "Panel roundness" to "Arrondi du panneau",
    "Button height" to "Hauteur des boutons",
    "Title size" to "Taille des titres",
    "Gradient buttons" to "Boutons en dégradé",
    "Round logo" to "Logo rond",
    "Show quick actions" to "Afficher les actions rapides",
    "Show tagline" to "Afficher le slogan",
    "Reset to default" to "Rétablir par défaut",
    "New to MudaPay?" to "Nouveau sur MudaPay ?",
    "Already a member?" to "Déjà membre ?",
    "Create an account" to "Créer un compte"
)
