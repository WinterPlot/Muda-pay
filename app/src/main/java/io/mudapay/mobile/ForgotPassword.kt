package io.mudapay.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "Forgot password" — step 1: phone number or verified email -> the server e-mails a 6-digit code to the account's VERIFIED email.
 * Step 2: code + new password. The server never says whether an account exists, so the screen words it the same way.
 * [onDone] gets the phone number to pre-fill the login (empty when an email was used).
 */
@Composable
fun ForgotPasswordScreen(prefill: String, onBack: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var step by remember { mutableIntStateOf(0) }
    var identifier by remember { mutableStateOf(prefill) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var resendIn by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf("") }
    var errorTick by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }

    LaunchedEffect(resendIn) { if (resendIn > 0) { delay(1000); resendIn -= 1 } }
    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            for (v in listOf(-14f, 11f, -8f, 5f, -2f, 0f)) shake.animateTo(v, tween(55))
        }
    }
    BackHandler(enabled = step == 1) { step = 0; error = null }

    fun fail(msg: String) { lastError = msg; error = msg; errorTick++ }

    fun sendCode() {
        error = null
        val id = identifier.trim()
        if (id.isEmpty()) { fail(L("Enter your phone number or your verified email.")); return }
        busy = true
        scope.launch {
            val r = Api.call("POST", "/auth/password/forgot", JSONObject().put("identifier", id), auth = false)
            busy = false
            if (r.ok) { resendIn = r.data.int("resend_in"); code = ""; step = 1 } else fail(r.message)
        }
    }

    fun reset() {
        error = null
        if (code.length != 6) { fail(L("Enter the 6-digit code from the email.")); return }
        if (password.length < 6) { fail(L("The password must have at least 6 characters.")); return }
        if (password != confirm) { fail(L("The two passwords do not match.")); return }
        busy = true
        scope.launch {
            val id = identifier.trim()
            val r = Api.call("POST", "/auth/password/reset", JSONObject().put("identifier", id).put("code", code).put("new_password", password), auth = false)
            busy = false
            if (r.ok) {
                Ui.notify(L("Password changed"), L("Log in with your new password."))
                onDone(if (id.contains("@")) "" else id)
            } else fail(r.message)
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        AuthTopBar(if (step == 1) ({ step = 0; error = null }) else onBack)
        Spacer(Modifier.height(22.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = { (fadeIn(tween(320, delayMillis = 90)) + slideInVertically(tween(320)) { it / 4 }) togetherWith fadeOut(tween(120)) },
            label = "forgotHeader"
        ) { s ->
            Column(Modifier.fillMaxWidth()) {
                Text(if (s == 0) L("Forgot password?") else L("Choose a new password"), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-.5).sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (s == 0) L("We will email you a code so you can choose a new one.") else L("Enter the code we emailed you and choose a new password."),
                    color = Color.White.copy(alpha = .9f), fontSize = 15.sp, lineHeight = 22.sp
                )
            }
        }
        Spacer(Modifier.height(22.dp))

        AuthCard(Modifier.graphicsLayer { translationX = shake.value.dp.toPx() }) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val fwd = targetState > initialState
                    ((fadeIn(tween(280, delayMillis = 70)) + slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { if (fwd) it / 8 else -it / 8 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(260)) { if (fwd) -it / 8 else it / 8 })).using(SizeTransform(clip = false))
                },
                label = "forgotForm"
            ) { s ->
                Column(Modifier.fillMaxWidth()) {
                    if (s == 0) {
                        Reveal(0, dy = 12.dp) {
                            AuthField(L("Phone number or email"), identifier, { identifier = it.take(190) }, Icons.Default.Person,
                                imeAction = ImeAction.Done, onDone = { sendCode() })
                        }
                        Spacer(Modifier.height(14.dp))
                        Reveal(60, dy = 12.dp) {
                            InfoBanner(L("We send a 6-digit code to the verified email of your account. No verified email on your account? Contact MudaPay support."), Blue, Icons.Default.Email)
                        }
                    } else {
                        Reveal(0, dy = 12.dp) {
                            InfoBanner(L("If an account matches, a 6-digit code was sent to its verified email. It expires in 10 minutes. Check your spam folder."), Blue, Icons.Default.Email)
                        }
                        Spacer(Modifier.height(14.dp))
                        Reveal(60, dy = 12.dp) {
                            AuthField(L("6-digit code"), code, { code = it.filter { c -> c.isDigit() }.take(6) }, Icons.Default.Email, keyboard = KeyboardType.Number)
                        }
                        Spacer(Modifier.height(12.dp))
                        Reveal(120, dy = 12.dp) {
                            AuthField(L("New password"), password, { password = it }, Icons.Default.Lock, password = !show, keyboard = KeyboardType.Password,
                                trailing = { PasswordToggle(show) { show = !show } })
                        }
                        AnimatedVisibility(visible = password.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Column { Spacer(Modifier.height(8.dp)); StrengthMeter(password) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Reveal(180, dy = 12.dp) {
                            val match = confirm.isNotEmpty() && confirm == password
                            AuthField(L("Confirm password"), confirm, { confirm = it }, Icons.Default.Lock, password = !show, keyboard = KeyboardType.Password,
                                imeAction = ImeAction.Done, onDone = { reset() },
                                trailing = { AnimatedVisibility(visible = match, enter = fadeIn(), exit = fadeOut()) { androidx.compose.material3.Icon(Icons.Default.CheckCircle, null, tint = Green) } })
                        }
                    }
                }
            }
            AnimatedVisibility(visible = error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column { Spacer(Modifier.height(14.dp)); InfoBanner(lastError, Danger, Icons.Default.ErrorOutline) }
            }
            Spacer(Modifier.height(18.dp))
            AuthButton(
                if (step == 0) L("SEND CODE") else L("RESET PASSWORD"),
                { if (step == 0) sendCode() else reset() }, loading = busy, arrow = step == 0
            )
            AnimatedVisibility(visible = step == 1, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                TextButton(onClick = { sendCode() }, enabled = resendIn == 0 && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (resendIn > 0) "${L("RESEND IN")} ${resendIn}s" else L("SEND A NEW CODE"),
                        color = if (resendIn > 0) Muted else GreenDark, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
