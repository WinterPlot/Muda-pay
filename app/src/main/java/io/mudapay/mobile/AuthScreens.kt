package io.mudapay.mobile

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/* ====================================================================================================
 *  Shared building blocks for the signed-out experience (Welcome, Login, Register, Forgot, Google profile).
 *  Colours, shapes and sizes all come from AuthTheme.s (AuthTheme.kt): nothing is hard-coded here.
 * ==================================================================================================== */

/** Header gradient + page surface. The header covers the whole screen on Welcome and the top half elsewhere (it animates between the two). */
@Composable
fun AuthBackdrop(full: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val st = AuthTheme.s
    val target = if (full || st.layout == AuthLayout.Card) 1f else .52f
    val frac by animateFloatAsState(target, tween(520, easing = FastOutSlowInEasing), label = "heroFrac")
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(st.sheet)
            val w = size.width
            val h = size.height * frac
            drawRect(
                Brush.linearGradient(listOf(st.heroTop, st.heroBottom), start = Offset(0f, 0f), end = Offset(w * .6f, h)),
                size = Size(w, h)
            )
            val d = if (st.heroIsLight) st.accent else Color.White
            when (st.decor) {
                HeroDecor.Circles -> {
                    drawCircle(d.copy(alpha = .07f), radius = w * .62f, center = Offset(w, h * .06f))
                    drawCircle(d.copy(alpha = .06f), radius = w * .5f, center = Offset(w * .02f, h * .92f))
                }
                HeroDecor.Rings -> {
                    val sw = 1.5.dp.toPx()
                    drawCircle(d.copy(alpha = .22f), radius = w * .22f, center = Offset(w * .92f, h * .1f), style = Stroke(sw))
                    drawCircle(d.copy(alpha = .16f), radius = w * .38f, center = Offset(w * .92f, h * .1f), style = Stroke(sw))
                    drawCircle(d.copy(alpha = .10f), radius = w * .56f, center = Offset(w * .92f, h * .1f), style = Stroke(sw))
                    drawCircle(d.copy(alpha = .14f), radius = w * .26f, center = Offset(w * .04f, h * .9f), style = Stroke(sw))
                }
                HeroDecor.None -> {}
            }
        },
        content = content
    )
}

/** Fades + lifts its content into place after [delayMs]. Used for the staggered entrance of every screen. */
@Composable
fun Reveal(delayMs: Int, modifier: Modifier = Modifier, dy: Dp = 26.dp, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(520, easing = FastOutSlowInEasing), label = "revealAlpha")
    val k by animateFloatAsState(if (shown) 0f else 1f, tween(600, easing = FastOutSlowInEasing), label = "revealShift")
    Box(modifier.graphicsLayer { alpha = a; translationY = k * dy.toPx() }) { content() }
}

/** Logo that springs in and sends out a soft pulse ring. */
@Composable
fun LogoMark(markSize: Dp = 92.dp) {
    val st = AuthTheme.s
    val t = rememberInfiniteTransition(label = "logo")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse")
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val pop by animateFloatAsState(if (shown) 1f else .55f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "logoPop")
    val tile = if (st.logoCircle) CircleShape else RoundedCornerShape(markSize * .31f)
    Box(Modifier.size(markSize * 1.8f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = markSize.toPx() / 2f
            val reach = this.size.minDimension / 2f
            drawCircle(st.onHero.copy(alpha = .12f * (1f - pulse)), radius = base + (reach - base) * pulse)
            val p2 = (pulse + .5f) % 1f
            drawCircle(st.onHero.copy(alpha = .07f * (1f - p2)), radius = base + (reach - base) * p2)
        }
        Box(
            Modifier.size(markSize).graphicsLayer { scaleX = pop; scaleY = pop }
                .shadow(10.dp, tile, ambientColor = Color(0x33000000), spotColor = Color(0x55000000))
                .clip(tile).background(st.logoBg),
            contentAlignment = Alignment.Center
        ) { Icon(MudaIcons.AccountBalanceWallet, null, tint = st.logoFg, modifier = Modifier.size(markSize * .52f)) }
    }
}

/** Small brand block for the top bar. */
@Composable
fun AuthBrand() {
    val st = AuthTheme.s
    val tile = if (st.logoCircle) CircleShape else RoundedCornerShape(11.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).clip(tile).background(st.logoBg), contentAlignment = Alignment.Center) {
            Icon(MudaIcons.AccountBalanceWallet, null, tint = st.logoFg, modifier = Modifier.size(19.dp))
        }
        Column {
            Text(L("MudaPay"), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = st.onHero, letterSpacing = (-.4).sp)
            if (st.showTagline) Text(L("Simple, secure payments"), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = st.onHero.copy(alpha = .8f))
        }
    }
}

enum class AuthBtn { Brand, White, Ghost }

/** Button with a press-spring, a loading cross-fade and a soft coloured shadow. Brand = accent, White = button for the header area, Ghost = outline on the header. */
@Composable
fun AuthButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: AuthBtn = AuthBtn.Brand,
    loading: Boolean = false, enabled: Boolean = true, arrow: Boolean = false
) {
    val st = AuthTheme.s
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled && !loading) .965f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "btnScale"
    )
    val shape = RoundedCornerShape(st.buttonRadius.dp)
    val fg = when (kind) { AuthBtn.Brand -> st.onAccent; AuthBtn.White -> st.heroBtnFg; AuthBtn.Ghost -> st.onHero }
    val base = modifier.fillMaxWidth().height(st.buttonHeight.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .5f }
    val styled = when (kind) {
        AuthBtn.Brand -> base
            .shadow(if (enabled) 8.dp else 0.dp, shape, ambientColor = st.accent.copy(alpha = .25f), spotColor = st.accent.copy(alpha = .45f))
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(st.accent, if (st.gradientButtons) st.accentDark else st.accent)))
        AuthBtn.White -> base.clip(shape).background(st.heroBtnBg)
        AuthBtn.Ghost -> base.clip(shape).border(1.5.dp, st.onHero.copy(alpha = .85f), shape)
    }
    Box(styled.clickable(interactionSource = src, indication = null, enabled = enabled && !loading, onClick = onClick), contentAlignment = Alignment.Center) {
        Crossfade(loading, label = "btnLoading") { busy ->
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = fg, strokeWidth = 2.5.dp)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(softCase(text), color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (arrow) { Spacer(Modifier.width(8.dp)); Icon(MudaIcons.ArrowForward, null, tint = fg, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

/** Text field (outlined, filled or underlined, as chosen in the theme) with a leading icon that lights up on focus. */
@Composable
fun AuthField(
    label: String, value: String, onValue: (String) -> Unit, icon: ImageVector, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false, imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null
) {
    val st = AuthTheme.s
    var focused by remember { mutableStateOf(false) }
    val tint by animateColorAsState(if (focused) st.accentDark else Slate, tween(220), label = "fieldTint")
    val shape = RoundedCornerShape(st.fieldRadius.dp)
    val kb = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction)
    val ka = KeyboardActions(onDone = { onDone?.invoke() })
    val vt = if (password) PasswordVisualTransformation() else VisualTransformation.None
    val mod = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
    when (st.field) {
        FieldKind.Outlined -> OutlinedTextField(
            value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true,
            leadingIcon = { Icon(icon, null, tint = tint) }, trailingIcon = trailing, shape = shape,
            keyboardOptions = kb, keyboardActions = ka, visualTransformation = vt, modifier = mod,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = st.accent, focusedLabelColor = st.accentDark, unfocusedLabelColor = st.muted, cursorColor = st.accent,
                unfocusedBorderColor = st.line, focusedContainerColor = st.accent.copy(alpha = .04f), unfocusedContainerColor = Color.Transparent,
                focusedTextColor = st.ink, unfocusedTextColor = st.ink
            )
        )
        else -> {
            val under = st.field == FieldKind.Underline
            val ring by animateColorAsState(if (focused && !under) st.accent else Color.Transparent, tween(200), label = "fieldRing")
            TextField(
                value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true,
                leadingIcon = { Icon(icon, null, tint = tint) }, trailingIcon = trailing,
                shape = if (under) RoundedCornerShape(0.dp) else shape,
                keyboardOptions = kb, keyboardActions = ka, visualTransformation = vt,
                modifier = if (under) mod else mod.border(1.5.dp, ring, shape),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = if (under) Color.Transparent else st.fieldFill,
                    unfocusedContainerColor = if (under) Color.Transparent else st.fieldFill,
                    focusedIndicatorColor = if (under) st.accent else Color.Transparent,
                    unfocusedIndicatorColor = if (under) st.line else Color.Transparent,
                    disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                    focusedLabelColor = st.accentDark, unfocusedLabelColor = st.muted, cursorColor = st.accent,
                    focusedTextColor = st.ink, unfocusedTextColor = st.ink
                )
            )
        }
    }
}

@Composable
fun PasswordToggle(show: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Crossfade(show, label = "eye") { s -> Icon(if (s) MudaIcons.VisibilityOff else MudaIcons.Visibility, null, tint = Muted) }
    }
}

/** Country selector that matches [AuthField] (flag + name). */
@Composable
fun AuthCountryField(value: String, onSelect: (String) -> Unit) {
    val st = AuthTheme.s
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(st.fieldRadius.dp)
    val frame = when (st.field) {
        FieldKind.Outlined -> Modifier.clip(shape).border(1.dp, st.line, shape)
        FieldKind.Filled -> Modifier.clip(shape).background(st.fieldFill)
        FieldKind.Underline -> Modifier.drawBehind { drawLine(st.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
    }
    Box {
        Row(
            Modifier.fillMaxWidth().height(58.dp).then(frame).clickable { open = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Flag(value, 26.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Country"), color = st.muted, fontSize = 11.sp)
                Text(countryName(value), color = st.ink, fontSize = 15.sp)
            }
            Icon(MudaIcons.ExpandMore, null, tint = st.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Panel)) {
            COUNTRIES.forEach { (code, name) ->
                DropdownMenuItem(text = { Text(name, color = Ink) }, leadingIcon = { Flag(code, 22.dp) }, onClick = { onSelect(code); open = false })
            }
        }
    }
}

/** Frosted circular back button + brand. */
@Composable
fun AuthTopBar(onBack: () -> Unit) {
    val st = AuthTheme.s
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(st.onHero.copy(alpha = .16f)).border(1.dp, st.onHero.copy(alpha = .25f), CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) { Icon(MudaIcons.ArrowBack, L("Back"), tint = st.onHero, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.weight(1f))
        AuthBrand()
    }
}

/** The form surface: a panel rising from the bottom (Sheet) or a floating card (Card), as chosen in the theme. */
@Composable
fun AuthCard(modifier: Modifier = Modifier, minHeight: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    val st = AuthTheme.s
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); shown = true }
    val rise by animateFloatAsState(if (shown) 0f else 1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "cardRise")
    val sheetMode = st.layout == AuthLayout.Sheet
    val r = st.sheetRadius.dp
    val shape = if (sheetMode) RoundedCornerShape(topStart = r, topEnd = r) else RoundedCornerShape(r)
    Column(
        modifier.then(if (sheetMode) Modifier else Modifier.padding(horizontal = 22.dp)).fillMaxWidth()
            .graphicsLayer { translationY = rise * 64.dp.toPx(); alpha = (1f - rise).coerceIn(0f, 1f) }
            .heightIn(min = minHeight)
            .shadow(if (sheetMode) 12.dp else 20.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x44000000))
            .clip(shape).background(st.sheet)
            .then(if (sheetMode) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = if (sheetMode) 24.dp else 20.dp, vertical = if (sheetMode) 26.dp else 20.dp),
        content = content
    )
}

/** One signed-out page: top bar, animated title, then the form surface. Used by Login, Register, Forgot password and the Google profile. */
@Composable
fun AuthPage(title: String, subtitle: String, onBack: () -> Unit, shake: () -> Float = { 0f }, content: @Composable ColumnScope.() -> Unit) {
    val st = AuthTheme.s
    val density = LocalDensity.current
    var headerPx by remember { mutableIntStateOf(0) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetMode = st.layout == AuthLayout.Sheet
        val minH = if (sheetMode) (maxHeight - statusTop - with(density) { headerPx.toDp() }).coerceAtLeast(260.dp) else 0.dp
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 14.dp, bottom = 24.dp).onSizeChanged { headerPx = it.height }) {
                AuthTopBar(onBack)
                Spacer(Modifier.height(22.dp))
                AnimatedContent(
                    targetState = title to subtitle,
                    transitionSpec = { (fadeIn(tween(320, delayMillis = 90)) + slideInVertically(tween(320)) { it / 4 }) togetherWith fadeOut(tween(120)) },
                    label = "authHeader"
                ) { (t, s) ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(t, color = st.onHero, fontSize = st.titleSize.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
                        Spacer(Modifier.height(4.dp))
                        Text(s, color = st.onHero.copy(alpha = .9f), fontSize = 15.sp, lineHeight = 22.sp)
                    }
                }
            }
            AuthCard(Modifier.graphicsLayer { translationX = shake().dp.toPx() }, minHeight = minH, content = content)
            if (!sheetMode) Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

/* ====================================================================================================
 *  Welcome
 * ==================================================================================================== */

@Composable
private fun FeatureChip(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    val st = AuthTheme.s
    val shape = RoundedCornerShape(st.fieldRadius.dp.coerceAtLeast(10.dp))
    val tone = st.onHero
    Column(
        modifier.clip(shape).background(tone.copy(alpha = .10f)).border(1.dp, tone.copy(alpha = .18f), shape).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = tone, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun WelcomeScreen(onLogin: () -> Unit, onRegister: () -> Unit) {
    val st = AuthTheme.s
    var customizing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(st.onHero.copy(alpha = .16f)).border(1.dp, st.onHero.copy(alpha = .25f), CircleShape)
                    .clickable { customizing = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Palette, L("Customize"), tint = st.onHero, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.weight(1f))
        LogoMark()
        Spacer(Modifier.height(4.dp))
        Reveal(250) { Text(L("MudaPay"), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = st.onHero, letterSpacing = (-1).sp) }
        if (st.showTagline) {
            Reveal(350) { Text(L("Simple, secure payments"), color = st.onHero.copy(alpha = .85f), fontSize = 15.sp, letterSpacing = 0.sp) }
            Spacer(Modifier.height(14.dp))
            Reveal(450) {
                Text(
                    L("Send, receive and manage your money in one trusted place."), color = st.onHero.copy(alpha = .8f),
                    fontSize = 15.sp, lineHeight = 23.sp, textAlign = TextAlign.Center
                )
            }
        }
        if (st.showChips) {
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Reveal(560, Modifier.weight(1f)) { FeatureChip(MudaIcons.NorthEast, L("Send"), Modifier.fillMaxWidth()) }
                Reveal(640, Modifier.weight(1f)) { FeatureChip(MudaIcons.QrCode2, L("Receive"), Modifier.fillMaxWidth()) }
                Reveal(720, Modifier.weight(1f)) { FeatureChip(MudaIcons.AddCard, L("Deposit"), Modifier.fillMaxWidth()) }
            }
        }
        Spacer(Modifier.weight(1f))
        Reveal(820, Modifier.fillMaxWidth(), dy = 40.dp) {
            Column {
                AuthButton(softCase(L("CREATE ACCOUNT")), onRegister, kind = AuthBtn.White, arrow = true)
                Spacer(Modifier.height(12.dp))
                AuthButton(softCase(L("LOG IN")), onLogin, kind = AuthBtn.Ghost)
            }
        }
        Spacer(Modifier.height(22.dp))
    }
    if (customizing) AuthCustomizeDialog { customizing = false }
}

/* ====================================================================================================
 *  Login + Register (one screen, animated tabs, register is split in two short steps)
 * ==================================================================================================== */

private fun passwordLevel(p: String): Int {
    if (p.isEmpty()) return 0
    if (p.length < 10) return 1
    var s = 2
    if (p.any { it.isDigit() } && p.any { it.isLetter() }) s++
    if (p.length >= 12 && (p.any { !it.isLetterOrDigit() } || (p.any { it.isUpperCase() } && p.any { it.isLowerCase() }))) s++
    return s.coerceAtMost(4)
}

@Composable
fun StrengthMeter(password: String) {
    val st = AuthTheme.s
    val level = passwordLevel(password)
    val tone by animateColorAsState(
        when (level) { 0, 1 -> Danger; 2 -> Color(0xFFF59E0B); 3 -> st.accent; else -> st.accentDark }, tween(250), label = "strengthTone"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i ->
                val c by animateColorAsState(if (i < level) tone else st.line, tween(260), label = "seg$i")
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(c))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            when (level) { 0, 1 -> L("Weak"); 2 -> L("Fair"); 3 -> L("Good"); else -> L("Strong") },
            color = tone, fontSize = 11.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AuthTabs(register: Boolean, onSelect: (Boolean) -> Unit) {
    val st = AuthTheme.s
    val bias by animateFloatAsState(if (register) 1f else -1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium), label = "tabBias")
    val outer = RoundedCornerShape(st.buttonRadius.dp)
    val inner = RoundedCornerShape((st.buttonRadius - 4).coerceAtLeast(0).dp)
    Box(Modifier.fillMaxWidth().height(48.dp).clip(outer).background(st.fieldFill).padding(4.dp)) {
        Box(
            Modifier.fillMaxWidth(.5f).fillMaxHeight().align(BiasAlignment(bias, 0f))
                .shadow(3.dp, inner).background(Panel, inner)
        )
        Row(Modifier.fillMaxSize()) {
            listOf(false to L("Log in"), true to L("Sign up")).forEach { (isRegister, label) ->
                val c by animateColorAsState(if (register == isRegister) st.accentDark else st.muted, tween(220), label = "tabColor")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(inner)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(isRegister) },
                    contentAlignment = Alignment.Center
                ) { Text(label, color = c, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun StepHeader(step: Int) {
    val st = AuthTheme.s
    val p by animateFloatAsState(if (step == 0) .5f else 1f, tween(450, easing = FastOutSlowInEasing), label = "stepProgress")
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(step, transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) }, label = "stepTitle") { s ->
                Text(if (s == 0) L("About you") else L("Secure your account"), color = st.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("${step + 1}/2", color = st.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(st.line)) {
            Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(CircleShape).background(st.accent))
        }
    }
}

@Composable
fun AuthScreen(register: Boolean, prefillPhone: String, onBack: () -> Unit, onSwitch: () -> Unit, onRegistered: (String) -> Unit,
               onGoogleProfile: (GoogleAuth.Outcome.NeedsProfile) -> Unit = {}, onForgot: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(prefillPhone) }
    var country by remember { mutableStateOf("bi") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var referral by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf("") }
    var errorTick by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }

    // switching tab always restarts the form at its first step
    LaunchedEffect(register) { step = 0; error = null }
    LaunchedEffect(prefillPhone) { if (prefillPhone.isNotEmpty()) phone = prefillPhone }
    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            for (v in listOf(-14f, 11f, -8f, 5f, -2f, 0f)) shake.animateTo(v, tween(55))
        }
    }
    BackHandler(enabled = register && step == 1) { step = 0 }

    fun fail(msg: String) { lastError = msg; error = msg; errorTick++ }

    fun next() {
        error = null
        if (fullName.isBlank() || phone.isBlank()) { fail(L("Please fill in all the fields.")); return }
        step = 1
    }

    fun submit() {
        error = null
        if (register) {
            if (fullName.isBlank() || phone.isBlank() || password.isEmpty()) { fail(L("Please fill in all the fields.")); return }
            if (password.length < 10) { fail(L("The password must have at least 10 characters.")); return }
            if (password != confirm) { fail(L("The two passwords do not match.")); return }
        } else if (phone.isBlank() || password.isEmpty()) { fail(L("Please fill in all the fields.")); return }
        busy = true
        scope.launch {
            if (register) {
                val body = JSONObject().put("full_name", fullName.trim()).put("phone", phone.trim()).put("password", password).put("country", country)
                val inviteCode = inviteCodeOrNull(referral)      // money someone sent to this number before it joined
                if (inviteCode != null) body.put("invite", inviteCode)
                else { val ref = referral.trim().toIntOrNull(); if (ref != null && ref > 0) body.put("referrer", ref) }
                val r = Api.call("POST", "/auth/register", body, auth = false)
                busy = false
                if (r.ok) {
                    val created = phone.trim()
                    val inviteMsg = inviteOutcomeText(r.data.optJSONObject("invite"))
                    password = ""; confirm = ""; fullName = ""; referral = ""; step = 0
                    Ui.notify("Account created", (inviteMsg?.let { it + " " } ?: "") + "Log in to continue.")
                    onRegistered(created)
                } else fail(r.message)
            } else {
                val body = JSONObject().put("phone", phone.trim()).put("password", password).put("device", Build.MODEL ?: "Android")
                val r = Api.call("POST", "/auth/login", body, auth = false)
                busy = false
                if (r.ok) {
                    val tokens = r.data.optJSONObject("tokens")
                    val user = r.data.optJSONObject("user")
                    if (tokens != null && user != null) { Session.saveTokens(tokens); Session.updateUser(user) }
                    else fail(L("Unexpected answer from the server."))
                } else {
                    fail(authErrorMessage(r))
                }
            }
        }
    }

    // 0 = login, 1 = register step 1, 2 = register step 2
    val formKey = if (!register) 0 else 1 + step
    val st = AuthTheme.s

    AuthPage(
        title = if (register) L("Create your account") else L("Welcome back"),
        subtitle = if (register) L("Join the MudaPay community") else L("Log in to your MudaPay account"),
        onBack = onBack, shake = { shake.value }
    ) {
        AuthTabs(register) { if (it != register) onSwitch() }
        Spacer(Modifier.height(18.dp))
        AnimatedVisibility(visible = register, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column { StepHeader(step); Spacer(Modifier.height(16.dp)) }
        }
        AnimatedContent(
            targetState = formKey,
            transitionSpec = {
                val fwd = targetState > initialState
                ((fadeIn(tween(280, delayMillis = 70)) + slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { if (fwd) it / 8 else -it / 8 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(260)) { if (fwd) -it / 8 else it / 8 })).using(SizeTransform(clip = false))
            },
            label = "authForm"
        ) { k ->
            Column(Modifier.fillMaxWidth()) {
                when (k) {
                    0 -> {
                        Reveal(0, dy = 12.dp) { AuthField(L("Phone number"), phone, { phone = it }, MudaIcons.Phone, keyboard = KeyboardType.Phone) }
                        Spacer(Modifier.height(12.dp))
                        Reveal(60, dy = 12.dp) {
                            AuthField(L("Password"), password, { password = it }, MudaIcons.Lock, password = !show, keyboard = KeyboardType.Password,
                                imeAction = ImeAction.Done, onDone = { submit() }, trailing = { PasswordToggle(show) { show = !show } })
                        }
                        Text(
                            L("Forgot password?"), color = st.accentDark, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.End).clickable { onForgot(phone.trim()) }.padding(top = 12.dp, bottom = 2.dp, start = 6.dp)
                        )
                    }
                    1 -> {
                        Reveal(0, dy = 12.dp) { AuthField(L("Full name"), fullName, { fullName = it }, MudaIcons.Person) }
                        Spacer(Modifier.height(12.dp))
                        Reveal(60, dy = 12.dp) { AuthField(L("Phone number"), phone, { phone = it }, MudaIcons.Phone, keyboard = KeyboardType.Phone) }
                        Spacer(Modifier.height(12.dp))
                        Reveal(120, dy = 12.dp) { AuthCountryField(country) { country = it } }
                    }
                    else -> {
                        Reveal(0, dy = 12.dp) {
                            AuthField(L("Password"), password, { password = it }, MudaIcons.Lock, password = !show, keyboard = KeyboardType.Password,
                                trailing = { PasswordToggle(show) { show = !show } })
                        }
                        AnimatedVisibility(visible = password.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Column { Spacer(Modifier.height(8.dp)); StrengthMeter(password) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Reveal(60, dy = 12.dp) {
                            val match = confirm.isNotEmpty() && confirm == password
                            AuthField(L("Confirm password"), confirm, { confirm = it }, MudaIcons.Lock, password = !show, keyboard = KeyboardType.Password,
                                trailing = { AnimatedVisibility(visible = match, enter = fadeIn() + scaleIn(), exit = fadeOut()) { Icon(MudaIcons.CheckCircle, null, tint = st.accent) } })
                        }
                        Spacer(Modifier.height(12.dp))
                        Reveal(120, dy = 12.dp) {
                            AuthField(L("Referral or invite code (optional)"), referral, { referral = it.take(14) }, MudaIcons.Redeem, keyboard = KeyboardType.Text,
                                imeAction = ImeAction.Done, onDone = { submit() })
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column { Spacer(Modifier.height(14.dp)); InfoBanner(lastError, Danger, MudaIcons.ErrorOutline) }
        }
        Spacer(Modifier.height(18.dp))
        AuthButton(
            when (formKey) { 0 -> softCase(L("LOG IN")); 1 -> softCase(L("CONTINUE")); else -> softCase(L("CREATE ACCOUNT")) },
            { if (formKey == 1) next() else submit() }, loading = busy, arrow = formKey != 2
        )
        AnimatedVisibility(visible = formKey == 2, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            TextButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth()) { Text(L("‹  Back"), color = st.muted, fontWeight = FontWeight.Bold) }
        }
        AnimatedVisibility(visible = formKey != 2, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                GoogleSignInBlock(enabled = !busy, onNeedsProfile = onGoogleProfile)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (register) L("Already a member?") else L("New to MudaPay?"), color = st.muted, fontSize = 13.sp)
                    TextButton(onClick = onSwitch) {
                        Text(if (register) L("Log in") else L("Create an account"), color = st.accentDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
