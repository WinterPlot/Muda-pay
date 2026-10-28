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
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
 *  Shared building blocks for the signed-out experience (Welcome, Login, Register, Google profile)
 * ==================================================================================================== */

/** Slow, living gradient behind every signed-out screen. Drawn once in AuthFlow so it never restarts between screens. */
@Composable
fun AuthBackdrop(content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "authBackdrop")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Reverse), label = "orbA")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(21000, easing = LinearEasing), RepeatMode.Reverse), label = "orbB")
    val s by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sweep")
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.linearGradient(
                    listOf(GreenDark, Green, Blue),
                    start = Offset(size.width * .25f * s, 0f),
                    end = Offset(size.width * (1f - .25f * s), size.height)
                )
            )
            drawCircle(Color.White.copy(alpha = .10f), radius = size.width * .55f, center = Offset(size.width * (.10f + .75f * a), size.height * (.10f + .10f * b)))
            drawCircle(Color.White.copy(alpha = .07f), radius = size.width * .40f, center = Offset(size.width * (.95f - .65f * b), size.height * (.55f + .20f * a)))
            drawCircle(Color.White.copy(alpha = .05f), radius = size.width * .26f, center = Offset(size.width * (.15f + .35f * a), size.height * (.92f - .10f * b)))
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

/** Logo tile that springs in, floats gently and sends out a soft pulse ring. */
@Composable
fun LogoMark(markSize: Dp = 92.dp) {
    val t = rememberInfiniteTransition(label = "logo")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse")
    val bob by t.animateFloat(-4f, 4f, infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val pop by animateFloatAsState(if (shown) 1f else .55f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "logoPop")
    val tile = RoundedCornerShape(markSize * .31f)
    Box(Modifier.size(markSize * 1.8f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = markSize.toPx() / 2f
            val reach = this.size.minDimension / 2f
            val r = base + (reach - base) * pulse
            drawCircle(Color.White.copy(alpha = .30f * (1f - pulse)), radius = r)
            val p2 = (pulse + .5f) % 1f
            drawCircle(Color.White.copy(alpha = .18f * (1f - p2)), radius = base + (reach - base) * p2)
        }
        Box(
            Modifier.size(markSize)
                .graphicsLayer { scaleX = pop; scaleY = pop; translationY = bob.dp.toPx() }
                .shadow(14.dp, tile, ambientColor = Color(0x33000000), spotColor = Color(0x55000000))
                .clip(tile).background(Color.White.copy(alpha = .20f)).border(1.dp, Color.White.copy(alpha = .38f), tile),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.AccountBalanceWallet, null, tint = Color.White, modifier = Modifier.size(markSize * .55f)) }
    }
}

enum class AuthBtn { Brand, White, Ghost }

/** Button with a press-spring, a loading cross-fade and a soft coloured shadow. */
@Composable
fun AuthButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: AuthBtn = AuthBtn.Brand,
    loading: Boolean = false, enabled: Boolean = true, arrow: Boolean = false
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled && !loading) .965f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "btnScale"
    )
    val shape = RoundedCornerShape(18.dp)
    val fg = if (kind == AuthBtn.White) GreenDark else Color.White
    val base = modifier.fillMaxWidth().height(54.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .5f }
    val styled = when (kind) {
        AuthBtn.Brand -> base.shadow(9.dp, shape, ambientColor = Green.copy(alpha = .45f), spotColor = Blue.copy(alpha = .55f)).clip(shape).background(Gradient)
        AuthBtn.White -> base.shadow(9.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x44000000)).clip(shape).background(Color.White)
        AuthBtn.Ghost -> base.clip(shape).border(1.5.dp, Color.White.copy(alpha = .9f), shape)
    }
    Box(styled.clickable(interactionSource = src, indication = null, enabled = enabled && !loading, onClick = onClick), contentAlignment = Alignment.Center) {
        Crossfade(loading, label = "btnLoading") { busy ->
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = fg, strokeWidth = 2.5.dp)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(softCase(text), color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (arrow) { Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = fg, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

/** Text field with a leading icon that lights up on focus. */
@Composable
fun AuthField(
    label: String, value: String, onValue: (String) -> Unit, icon: ImageVector, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false, imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val tint by animateColorAsState(if (focused) GreenDark else Slate, tween(220), label = "fieldTint")
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true,
        leadingIcon = { Icon(icon, null, tint = tint) }, trailingIcon = trailing,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Green, focusedLabelColor = GreenDark, cursorColor = Green, unfocusedBorderColor = Line,
            focusedContainerColor = Green.copy(alpha = .04f), unfocusedContainerColor = Page,
            focusedTextColor = Ink, unfocusedTextColor = Ink
        )
    )
}

@Composable
fun PasswordToggle(show: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Crossfade(show, label = "eye") { s -> Icon(if (s) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Muted) }
    }
}

/** Country selector that matches [AuthField] (flag + name). */
@Composable
fun AuthCountryField(value: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box {
        Row(
            Modifier.fillMaxWidth().height(58.dp).clip(shape).background(Page).border(1.dp, Line, shape)
                .clickable { open = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Flag(value, 26.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Country"), color = Muted, fontSize = 11.sp)
                Text(countryName(value), color = Ink, fontSize = 15.sp)
            }
            Icon(Icons.Default.ExpandMore, null, tint = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Color.White)) {
            COUNTRIES.forEach { (code, name) ->
                DropdownMenuItem(text = { Text(name, color = Ink) }, leadingIcon = { Flag(code, 22.dp) }, onClick = { onSelect(code); open = false })
            }
        }
    }
}

/** Frosted circular back button + brand. */
@Composable
fun AuthTopBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).border(1.dp, Color.White.copy(alpha = .3f), CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("Back"), tint = Color.White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.weight(1f))
        Brand(light = true)
    }
}

/** White card that rises from the bottom with a soft spring. */
@Composable
fun AuthCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); shown = true }
    val rise by animateFloatAsState(if (shown) 0f else 1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "cardRise")
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth()
            .graphicsLayer { translationY = rise * 64.dp.toPx(); alpha = (1f - rise).coerceIn(0f, 1f) }
            .shadow(20.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x44000000))
            .clip(shape).background(Color.White).padding(20.dp),
        content = content
    )
}

/* ====================================================================================================
 *  Welcome
 * ==================================================================================================== */

@Composable
private fun FeatureChip(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape).background(Color.White.copy(alpha = .14f)).border(1.dp, Color.White.copy(alpha = .24f), shape).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun WelcomeScreen(onLogin: () -> Unit, onRegister: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        LogoMark()
        Spacer(Modifier.height(4.dp))
        Reveal(250) { Text(L("MudaPay"), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = (-1).sp) }
        Reveal(350) { Text(L("Grow • Learn • Earn"), color = Color.White.copy(alpha = .92f), fontSize = 15.sp, letterSpacing = 0.sp) }
        Spacer(Modifier.height(14.dp))
        Reveal(450) {
            Text(
                L("Your everyday space for learning, sharing and earning rewards."), color = Color.White.copy(alpha = .95f),
                fontSize = 15.sp, lineHeight = 23.sp, textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Reveal(560, Modifier.weight(1f)) { FeatureChip(Icons.Default.School, L("Learn"), Modifier.fillMaxWidth()) }
            Reveal(640, Modifier.weight(1f)) { FeatureChip(Icons.Default.Share, L("Share"), Modifier.fillMaxWidth()) }
            Reveal(720, Modifier.weight(1f)) { FeatureChip(Icons.Default.Paid, L("Earn"), Modifier.fillMaxWidth()) }
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
}

/* ====================================================================================================
 *  Login + Register (one screen, animated tabs, register is split in two short steps)
 * ==================================================================================================== */

private fun passwordLevel(p: String): Int {
    if (p.isEmpty()) return 0
    if (p.length < 6) return 1
    var s = 2
    if (p.any { it.isDigit() } && p.any { it.isLetter() }) s++
    if (p.length >= 10 && (p.any { !it.isLetterOrDigit() } || (p.any { it.isUpperCase() } && p.any { it.isLowerCase() }))) s++
    return s.coerceAtMost(4)
}

@Composable
fun StrengthMeter(password: String) {
    val level = passwordLevel(password)
    val tone by animateColorAsState(
        when (level) { 0, 1 -> Danger; 2 -> Color(0xFFF59E0B); 3 -> Green; else -> GreenDark }, tween(250), label = "strengthTone"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i ->
                val c by animateColorAsState(if (i < level) tone else Line, tween(260), label = "seg$i")
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
    val bias by animateFloatAsState(if (register) 1f else -1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium), label = "tabBias")
    val outer = RoundedCornerShape(16.dp)
    val inner = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().height(48.dp).clip(outer).background(Page).border(1.dp, Line, outer).padding(4.dp)) {
        Box(
            Modifier.fillMaxWidth(.5f).fillMaxHeight().align(BiasAlignment(bias, 0f))
                .shadow(3.dp, inner).background(Color.White, inner)
        )
        Row(Modifier.fillMaxSize()) {
            listOf(false to L("Log in"), true to L("Sign up")).forEach { (isRegister, label) ->
                val c by animateColorAsState(if (register == isRegister) GreenDark else Muted, tween(220), label = "tabColor")
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
    val p by animateFloatAsState(if (step == 0) .5f else 1f, tween(450, easing = FastOutSlowInEasing), label = "stepProgress")
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(step, transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) }, label = "stepTitle") { s ->
                Text(if (s == 0) L("About you") else L("Secure your account"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("${step + 1}/2", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Line)) {
            Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(CircleShape).background(Gradient))
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
            if (password.length < 6) { fail(L("The password must have at least 6 characters.")); return }
            if (password != confirm) { fail(L("The two passwords do not match.")); return }
        } else if (phone.isBlank() || password.isEmpty()) { fail(L("Please fill in all the fields.")); return }
        busy = true
        scope.launch {
            if (register) {
                val body = JSONObject().put("full_name", fullName.trim()).put("phone", phone.trim()).put("password", password).put("country", country)
                val ref = referral.trim().toIntOrNull()
                if (ref != null && ref > 0) body.put("referrer", ref)
                val r = Api.call("POST", "/auth/register", body, auth = false)
                busy = false
                if (r.ok) {
                    val created = phone.trim()
                    password = ""; confirm = ""; fullName = ""; referral = ""; step = 0
                    Ui.notify("Account created", "Log in to continue. You will then be asked to pay the activation fee.")
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

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        AuthTopBar(onBack)
        Spacer(Modifier.height(22.dp))
        AnimatedContent(
            targetState = register,
            transitionSpec = { (fadeIn(tween(320, delayMillis = 90)) + slideInVertically(tween(320)) { it / 4 }) togetherWith fadeOut(tween(120)) },
            label = "authHeader"
        ) { r ->
            Column(Modifier.fillMaxWidth()) {
                Text(if (r) L("Create your account") else L("Welcome back"), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
                Spacer(Modifier.height(4.dp))
                Text(if (r) L("Join the MudaPay community") else L("Log in to your MudaPay account"), color = Color.White.copy(alpha = .9f), fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(22.dp))

        AuthCard(Modifier.graphicsLayer { translationX = shake.value.dp.toPx() }) {
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
                            Reveal(0, dy = 12.dp) { AuthField(L("Phone number"), phone, { phone = it }, Icons.Default.Phone, keyboard = KeyboardType.Phone) }
                            Spacer(Modifier.height(12.dp))
                            Reveal(60, dy = 12.dp) {
                                AuthField(L("Password"), password, { password = it }, Icons.Default.Lock, password = !show, keyboard = KeyboardType.Password,
                                    imeAction = ImeAction.Done, onDone = { submit() }, trailing = { PasswordToggle(show) { show = !show } })
                            }
                            Text(
                                L("Forgot password?"), color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.End).clickable { onForgot(phone.trim()) }.padding(top = 12.dp, bottom = 2.dp, start = 6.dp)
                            )
                        }
                        1 -> {
                            Reveal(0, dy = 12.dp) { AuthField(L("Full name"), fullName, { fullName = it }, Icons.Default.Person) }
                            Spacer(Modifier.height(12.dp))
                            Reveal(60, dy = 12.dp) { AuthField(L("Phone number"), phone, { phone = it }, Icons.Default.Phone, keyboard = KeyboardType.Phone) }
                            Spacer(Modifier.height(12.dp))
                            Reveal(120, dy = 12.dp) { AuthCountryField(country) { country = it } }
                        }
                        else -> {
                            Reveal(0, dy = 12.dp) {
                                AuthField(L("Password"), password, { password = it }, Icons.Default.Lock, password = !show, keyboard = KeyboardType.Password,
                                    trailing = { PasswordToggle(show) { show = !show } })
                            }
                            AnimatedVisibility(visible = password.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                Column { Spacer(Modifier.height(8.dp)); StrengthMeter(password) }
                            }
                            Spacer(Modifier.height(12.dp))
                            Reveal(60, dy = 12.dp) {
                                val match = confirm.isNotEmpty() && confirm == password
                                AuthField(L("Confirm password"), confirm, { confirm = it }, Icons.Default.Lock, password = !show, keyboard = KeyboardType.Password,
                                    trailing = { AnimatedVisibility(visible = match, enter = fadeIn() + scaleIn(), exit = fadeOut()) { Icon(Icons.Default.CheckCircle, null, tint = Green) } })
                            }
                            Spacer(Modifier.height(12.dp))
                            Reveal(120, dy = 12.dp) {
                                AuthField(L("Referral code (optional)"), referral, { referral = it }, Icons.Default.Redeem, keyboard = KeyboardType.Number,
                                    imeAction = ImeAction.Done, onDone = { submit() })
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column { Spacer(Modifier.height(14.dp)); InfoBanner(lastError, Danger, Icons.Default.ErrorOutline) }
            }
            Spacer(Modifier.height(18.dp))
            AuthButton(
                when (formKey) { 0 -> softCase(L("LOG IN")); 1 -> softCase(L("CONTINUE")); else -> softCase(L("CREATE ACCOUNT")) },
                { if (formKey == 1) next() else submit() }, loading = busy, arrow = formKey != 2
            )
            AnimatedVisibility(visible = formKey == 2, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                TextButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth()) { Text(L("‹  Back"), color = Muted, fontWeight = FontWeight.Bold) }
            }
            AnimatedVisibility(visible = formKey != 2, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                GoogleSignInBlock(enabled = !busy, onNeedsProfile = onGoogleProfile)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
