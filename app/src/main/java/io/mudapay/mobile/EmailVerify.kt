package io.mudapay.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val AMBER = Color(0xFFD97706)

/** Settings card: "Verify your account" with an email address and a 6-digit code. */
@Composable
fun EmailVerificationSettings() {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(true) }
    var verified by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var codeSent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var resendIn by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val r = Api.call("GET", "/account/email")
        if (r.ok) {
            available = r.data.optBoolean("available", true)
            verified = r.data.bool("verified")
            saved = r.data.str("email")
            email = saved
            codeSent = r.data.bool("code_pending")
            resendIn = r.data.int("resend_in")
        }
        loaded = true
    }
    LaunchedEffect(resendIn) { if (resendIn > 0) { delay(1000); resendIn -= 1 } }

    val showForm = !verified || editing
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("Verify your account"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (loaded) Pill(if (verified) "VERIFIED" else "NOT VERIFIED", if (verified) GreenDark else AMBER)
        }
        Text(L("Add your email and confirm it with the code we send you. It protects your account and your money."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)

        if (loaded && !available) InfoBanner("Email verification is not available right now. Please try again later.", Danger, Icons.Default.Info)

        if (verified && !editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = GreenDark, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(saved, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            TextButton(onClick = { editing = true; email = ""; codeSent = false; code = "" }) { Text(L("Change email"), color = GreenDark, fontWeight = FontWeight.Bold) }
        }

        if (showForm && loaded && available) {
            InputField("Email address", email, { email = it.trim().take(190) }, keyboard = KeyboardType.Email)
            val valid = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
            PrimaryButton(
                if (resendIn > 0) "RESEND IN ${resendIn}s" else if (codeSent) "SEND A NEW CODE" else "SEND CODE",
                {
                    sending = true
                    scope.launch {
                        val r = Api.call("POST", "/account/email/send", JSONObject().put("email", email))
                        sending = false
                        if (r.ok) {
                            codeSent = true; code = ""; saved = email
                            resendIn = r.data.int("resend_in")
                            Notify.emailCodeSent(email)
                            Ui.notify("Code sent", "We sent a 6-digit code to $email. Check your inbox and spam folder.")
                        } else {
                            if (r.error?.has("resend_in") == true) resendIn = r.error.optInt("resend_in", 0)
                            Ui.notify("Verify email", r.message)
                        }
                    }
                },
                loading = sending, enabled = valid && resendIn == 0, icon = Icons.Default.Email
            )
            if (codeSent) {
                InfoBanner("Enter the 6-digit code sent to $saved. It expires in 10 minutes.", Blue, Icons.Default.Email)
                InputField("6-digit code", code, { code = it.filter { c -> c.isDigit() }.take(6) }, keyboard = KeyboardType.Number)
                PrimaryButton("VERIFY EMAIL", {
                    verifying = true
                    scope.launch {
                        val r = Api.call("POST", "/account/email/verify", JSONObject().put("code", code))
                        verifying = false
                        if (r.ok) {
                            verified = true; editing = false; codeSent = false; code = ""
                            saved = r.data.str("email", saved); email = saved
                            Notify.emailVerified(saved)
                            Api.call("GET", "/me").let { me -> if (me.ok) Session.updateUser(me.data) }
                            Ui.notify("Email verified", "Your account is verified. Thank you!")
                        } else {
                            if (r.code in setOf("code_expired", "code_locked", "no_code")) { code = ""; }
                            Ui.notify("Verify email", r.message)
                        }
                    }
                }, loading = verifying, enabled = code.length == 6, color = Dark)
            }
        }
    }
}
