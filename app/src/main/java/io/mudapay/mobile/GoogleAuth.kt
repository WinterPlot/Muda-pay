package io.mudapay.mobile

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "Continue with Google" — mobile app only (the website has no Google button on purpose).
 *
 * Flow: ask the server for a one-time nonce -> Google account sheet (Credential Manager) -> send the Google ID token to
 * POST /auth/google. The SERVER verifies the token; the app never decides who a member is.
 * Answer is either signed_in (tokens) or needs_profile (first time: collect phone + country, see GoogleProfileScreen).
 */
object GoogleAuth {
    /** False until a real Web client ID is set in Config.kt: the Google buttons stay hidden and nothing else changes. */
    val available: Boolean get() = Config.GOOGLE_WEB_CLIENT_ID.endsWith(".apps.googleusercontent.com") && !Config.GOOGLE_WEB_CLIENT_ID.startsWith("PASTE_")

    sealed interface Outcome {
        /** Tokens are saved and Session.signedIn is true: the root composable switches screens by itself. */
        data object SignedIn : Outcome
        data class NeedsProfile(val profileToken: String, val name: String, val email: String) : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Debug APKs (GitHub builds) show the technical reason; release builds only show friendly messages. */
    private fun debuggable(a: Activity) = (a.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Nonce from the server -> Google account sheet -> ID token. Returns the token or the Outcome to show. */
    private suspend fun fetchIdToken(activity: Activity): Pair<String?, Outcome?> {
        val nonce = Api.call("POST", "/auth/google/nonce", JSONObject(), auth = false)
        if (!nonce.ok) return null to Outcome.Failed(nonce.message)
        try {
            val option = GetSignInWithGoogleOption.Builder(Config.GOOGLE_WEB_CLIENT_ID).setNonce(nonce.data.str("nonce")).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            return if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken to null
            } else null to Outcome.Failed("Google sign-in did not return an account. Please try again.")
        } catch (e: GetCredentialCancellationException) {
            Log.w("MudaPay", "Google sign-in cancelled/closed", e)
            // Normally the member closed the sheet: say nothing. But Google also closes it silently when the Google Cloud setup is
            // incomplete (consent screen in Testing for a non-test user, or the app's SHA-1 not registered). Debug builds say so.
            return null to (if (debuggable(activity)) Outcome.Failed("[debug] Google closed the window (${e.message ?: e.type}). If you did not cancel yourself, the Google Cloud setup is not finished: publish the consent screen (or add this account as a test user) and check the Android client's SHA-1.") else Outcome.Cancelled)
        } catch (e: NoCredentialException) {
            Log.w("MudaPay", "Google sign-in: no credential", e)
            return null to Outcome.Failed("No Google account was found on this phone. Add one in your phone's Settings, then try again." + if (debuggable(activity)) " [debug] ${e.type}" else "")
        } catch (e: GetCredentialException) {
            // A wrong Web client ID, or an app SHA-1 that is not registered in Google Cloud, ends up here (see README).
            Log.w("MudaPay", "Google sign-in failed: ${e.type}", e)
            return null to Outcome.Failed("Google sign-in is not available right now. Please try again or use your phone number." + if (debuggable(activity)) " [debug] ${e.type}: ${e.message}" else "")
        } catch (e: GoogleIdTokenParsingException) {
            Log.w("MudaPay", "Google token parse", e)
            return null to Outcome.Failed("Google sign-in did not work. Please try again.")
        }
    }

    /** Signed-in member adds Google as a second way to sign in. Returns null = linked, "" = member cancelled, else an error text. */
    suspend fun linkToAccount(activity: Activity): String? {
        val (token, early) = fetchIdToken(activity)
        if (token == null) return (early as? Outcome.Failed)?.message ?: ""
        val r = Api.call("POST", "/google/link", JSONObject().put("id_token", token))
        if (!r.ok) return r.message
        Api.call("GET", "/me").let { if (it.ok) Session.updateUser(it.data) }
        Notify.googleLinked()
        return null
    }

    suspend fun signIn(activity: Activity): Outcome {
        val (idToken, early) = fetchIdToken(activity)
        if (idToken == null) return early ?: Outcome.Failed("Google sign-in did not work. Please try again.")

        val r = Api.call("POST", "/auth/google", JSONObject().put("id_token", idToken).put("device", Build.MODEL ?: "Android"), auth = false)
        if (!r.ok) return Outcome.Failed(authErrorMessage(r) + if (debuggable(activity)) " [debug] ${r.code}" else "")
        return when (r.data.str("status")) {
            "signed_in" -> if (finishSignIn(r.data)) Outcome.SignedIn else Outcome.Failed("Unexpected answer from the server.")
            "needs_profile" -> Outcome.NeedsProfile(r.data.str("profile_token"), r.data.str("name"), r.data.str("email"))
            else -> Outcome.Failed("Unexpected answer from the server.")
        }
    }

    /** Saves tokens + member from a `signed_in` answer. User first, so the next screen never flashes a loader. */
    fun finishSignIn(d: JSONObject): Boolean {
        val tokens = d.optJSONObject("tokens")
        val user = d.optJSONObject("user")
        if (tokens == null || user == null) return false
        Session.updateUser(user)
        Session.saveTokens(tokens)
        return true
    }
}

/** Same wording as the phone login: banned / deleted accounts show the admin's reason. */
fun authErrorMessage(r: ApiResult): String {
    val reason = r.error?.str("reason") ?: ""
    return if (r.code == "account_banned" || r.code == "account_deleted") r.message + if (reason.isNotEmpty()) " Reason: $reason" else "" else r.message
}

/* ------------------------------------------------------------------ pieces */
/** Google's own button look: white, thin grey outline, the untouched "G", dark text. */
@Composable
fun GoogleButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF747775)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F), disabledContainerColor = Color.White)
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = GreenDark, strokeWidth = 2.5.dp)
        else {
            Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun OrDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Line))
        Text(L("or"), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Line))
    }
}

/** The "Continue with Google" button + its error line. Hidden when Google sign-in is not configured. */
@Composable
fun GoogleSignInBlock(enabled: Boolean, onNeedsProfile: (GoogleAuth.Outcome.NeedsProfile) -> Unit) {
    if (!GoogleAuth.available) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        OrDivider()
        GoogleButton("Continue with Google", {
            val activity = ctx.findActivity() ?: return@GoogleButton
            error = null; busy = true
            scope.launch {
                when (val o = GoogleAuth.signIn(activity)) {
                    is GoogleAuth.Outcome.NeedsProfile -> onNeedsProfile(o)
                    is GoogleAuth.Outcome.Failed -> error = o.message
                    GoogleAuth.Outcome.SignedIn, GoogleAuth.Outcome.Cancelled -> {}
                }
                busy = false
            }
        }, loading = busy, enabled = enabled)
        error?.let { Spacer(Modifier.height(12.dp)); InfoBanner(it, Danger) }
    }
}

/* ------------------------------------------------------------------ first time with Google: phone + country */
@Composable
fun GoogleProfileScreen(info: GoogleAuth.Outcome.NeedsProfile, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var fullName by remember { mutableStateOf(info.name) }
    var phone by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("bi") }
    var referral by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var canLink by remember { mutableStateOf(false) }   // the phone already has a MudaPay account: ask for its password to link
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        error = null
        if (fullName.isBlank() || phone.isBlank()) { error = "Please fill in all the fields."; return }
        if (canLink && password.isEmpty()) { error = "Enter the password of your MudaPay account to link Google to it."; return }
        busy = true
        scope.launch {
            val body = JSONObject().put("profile_token", info.profileToken).put("full_name", fullName.trim()).put("phone", phone.trim())
                .put("country", country).put("device", Build.MODEL ?: "Android")
            referral.trim().toIntOrNull()?.takeIf { it > 0 }?.let { body.put("referrer", it) }
            if (canLink) body.put("password", password)
            val r = Api.call("POST", "/auth/google/complete", body, auth = false)
            busy = false
            if (r.ok) {
                if (!GoogleAuth.finishSignIn(r.data)) error = "Unexpected answer from the server."
            } else {
                if (r.code == "phone_taken" && r.error?.bool("can_link") == true) canLink = true
                error = authErrorMessage(r)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Gradient).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text(L("‹  Back"), color = Color.White, modifier = Modifier.clickable { onBack() }.padding(vertical = 10.dp))
        Spacer(Modifier.height(16.dp))
        Text(L("One last step"), color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Black)
        Text(L("Your MudaPay account is based on your phone number."), color = Color.White.copy(alpha = .88f))
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_google_g), null, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(L("Signed in with Google"), color = GreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = .5.sp)
                    if (info.email.isNotEmpty()) Text(info.email, color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            InputField("Full name", fullName, { fullName = it })
            Spacer(Modifier.height(12.dp))
            InputField("Phone number", phone, { phone = it }, keyboard = KeyboardType.Phone)
            Spacer(Modifier.height(12.dp))
            Dropdown("Country", country, COUNTRIES.map { it.first to it.second }) { country = it }
            if (canLink) {
                Spacer(Modifier.height(12.dp))
                InputField("Password of this MudaPay account", password, { password = it }, password = !show, trailing = {
                    IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Muted) }
                })
            } else {
                Spacer(Modifier.height(12.dp))
                InputField("Referral code (optional)", referral, { referral = it }, keyboard = KeyboardType.Number)
            }
            error?.let { Spacer(Modifier.height(12.dp)); InfoBanner(it, Danger) }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (canLink) "LINK GOOGLE & CONTINUE" else "CONTINUE", { submit() }, loading = busy)
            Spacer(Modifier.height(10.dp))
            Text(L("Your phone number is used to log in, receive support and withdraw. Use a number you own."), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/* ------------------------------------------------------------------ Settings: both ways to sign in, for every member */
@Composable
fun SignInMethods() {
    if (!GoogleAuth.available) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Session.user
    val linked = user?.optBoolean("google_linked", false) ?: false
    val hasPassword = user?.optBoolean("has_password", true) ?: true
    val email = user?.str("google_email") ?: ""
    var busy by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Ways to sign in"), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text(L("Use your phone number and password, or Google. Both open the same account."), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("Phone number"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (hasPassword) "Password set" else "No password yet", color = if (hasPassword) GreenDark else Muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_google_g), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Google"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (linked && email.isNotEmpty()) Text(email, color = Muted, fontSize = 11.sp)
            }
            Text(if (linked) "Linked" else "Not linked", color = if (linked) GreenDark else Muted, fontSize = 12.sp)
        }
        if (!linked) {
            GoogleButton("Link my Google account", {
                val activity = ctx.findActivity() ?: return@GoogleButton
                busy = true
                scope.launch {
                    val err = GoogleAuth.linkToAccount(activity)
                    busy = false
                    when {
                        err == null -> Ui.notify("Google linked", "You can now sign in with Google or with your phone number.")
                        err.isNotEmpty() -> Ui.notify("Google", err)
                    }
                }
            }, loading = busy)
        } else if (hasPassword) {
            TextButton(onClick = { askPassword = true }) { Text(L("Unlink Google"), color = Danger, fontWeight = FontWeight.Bold) }
        } else {
            Text(L("Set a password below to be able to unlink Google."), color = Muted, fontSize = 11.sp)
        }
    }
    if (askPassword) {
        AlertDialog(
            onDismissRequest = { if (!busy) askPassword = false }, containerColor = Color.White,
            title = { Text(L("Unlink Google?"), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(L("You will sign in with your phone number and password only."), color = Ink, fontSize = 13.sp)
                    InputField("Password", password, { password = it }, password = true)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && password.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        val r = Api.call("POST", "/google/unlink", JSONObject().put("password", password))
                        busy = false
                        if (r.ok) {
                            password = ""; askPassword = false
                            Notify.googleUnlinked()
                            Api.call("GET", "/me").let { if (it.ok) Session.updateUser(it.data) }
                        } else Ui.notify("Google", r.message)
                    }
                }) { Text(L("Unlink"), color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }, enabled = !busy) { Text(L("Cancel"), color = Muted) } }
        )
    }
}
