package io.mudapay.mobile

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun WelcomeScreen(onLogin: () -> Unit, onRegister: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Gradient).statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(92.dp).background(Color.White.copy(alpha = .18f), RoundedCornerShape(29.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AccountBalanceWallet, null, tint = Color.White, modifier = Modifier.size(51.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(L("MudaPay"), fontSize = 37.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text(L("Grow • Learn • Earn"), color = Color.White.copy(alpha = .92f), fontSize = 15.sp)
        Spacer(Modifier.height(15.dp))
        Text(L("Your everyday space for learning, sharing and earning rewards."), color = Color.White, fontSize = 15.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(33.dp))
        PrimaryButton("CREATE ACCOUNT", onRegister, color = Color.White, contentColor = GreenDark)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
            Text(L("LOG IN"), fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun AuthScreen(register: Boolean, prefillPhone: String, onBack: () -> Unit, onSwitch: () -> Unit, onRegistered: (String) -> Unit,
               onGoogleProfile: (GoogleAuth.Outcome.NeedsProfile) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(prefillPhone) }
    var country by remember { mutableStateOf("bi") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var referral by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        error = null
        if (register) {
            if (fullName.isBlank() || phone.isBlank() || password.isEmpty()) { error = "Please fill in all the fields."; return }
            if (password.length < 6) { error = "The password must have at least 6 characters."; return }
            if (password != confirm) { error = "The two passwords do not match."; return }
        } else if (phone.isBlank() || password.isEmpty()) { error = "Please fill in all the fields."; return }
        busy = true
        scope.launch {
            if (register) {
                val body = JSONObject().put("full_name", fullName.trim()).put("phone", phone.trim()).put("password", password).put("country", country)
                val ref = referral.trim().toIntOrNull()
                if (ref != null && ref > 0) body.put("referrer", ref)
                val r = Api.call("POST", "/auth/register", body, auth = false)
                busy = false
                if (r.ok) {
                    Ui.notify("Account created", "Log in to continue. You will then be asked to pay the activation fee.")
                    onRegistered(phone.trim())
                } else error = r.message
            } else {
                val body = JSONObject().put("phone", phone.trim()).put("password", password).put("device", Build.MODEL ?: "Android")
                val r = Api.call("POST", "/auth/login", body, auth = false)
                busy = false
                if (r.ok) {
                    val tokens = r.data.optJSONObject("tokens")
                    val user = r.data.optJSONObject("user")
                    if (tokens != null && user != null) { Session.saveTokens(tokens); Session.updateUser(user) }
                    else error = "Unexpected answer from the server."
                } else {
                    error = authErrorMessage(r)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Gradient).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text(L("‹  Back"), color = Color.White, modifier = Modifier.clickable { onBack() }.padding(vertical = 10.dp))
        Spacer(Modifier.height(16.dp))
        Text(if (register) "Create your account" else "Welcome back", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Black)
        Text(if (register) "Join the MudaPay community" else "Log in to your MudaPay account", color = Color.White.copy(alpha = .88f))
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(20.dp)) {
            Text(if (register) "REGISTER" else "LOGIN", color = GreenDark, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.height(14.dp))
            if (register) { InputField("Full name", fullName, { fullName = it }); Spacer(Modifier.height(12.dp)) }
            InputField("Phone number", phone, { phone = it }, keyboard = KeyboardType.Phone)
            if (register) {
                Spacer(Modifier.height(12.dp))
                Dropdown("Country", country, COUNTRIES.map { it.first to it.second }) { country = it }
            }
            Spacer(Modifier.height(12.dp))
            InputField("Password", password, { password = it }, password = !show, trailing = {
                IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Muted) }
            })
            if (register) {
                Spacer(Modifier.height(12.dp)); InputField("Confirm password", confirm, { confirm = it }, password = !show)
                Spacer(Modifier.height(12.dp)); InputField("Referral code (optional)", referral, { referral = it }, keyboard = KeyboardType.Number)
            }
            error?.let { Spacer(Modifier.height(12.dp)); InfoBanner(it, Danger) }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (register) "CREATE ACCOUNT" else "LOG IN", { submit() }, loading = busy)
            GoogleSignInBlock(enabled = !busy, onNeedsProfile = onGoogleProfile)
            Text(if (register) "Already have an account? Log in" else "New to MudaPay? Create account", color = Blue, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable { onSwitch() }.padding(14.dp))
        }
    }
}
