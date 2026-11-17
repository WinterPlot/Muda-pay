package io.mudapay.mobile

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(sub, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.White))
    }
}

@Composable
fun LowDataSettings() {
    val ctx = LocalContext.current
    var cleared by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(L("Lite mode"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("For slow or expensive connections and low-end phones. Failed requests are retried automatically and screens open from saved data."), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        ToggleRow("Lite mode (low data)", "Skips video thumbnails, loads other pictures only when you tap them, and uses smaller images", Offline.lowData) { Offline.updateLowData(it) }
        TextButton(onClick = { OfflineCache.clear(); cleared = true }) {
            Text(if (cleared) "Saved data cleared" else "Clear saved offline data", color = GreenDark, fontWeight = FontWeight.Bold)
        }
    }
}

/* ------------------------------------------------------------------ app lock */
@Composable
fun AppLockSettings() {
    val ctx = LocalContext.current
    val act = ctx.findActivity()
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(L("App lock"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("Ask for your fingerprint, face or phone PIN before MudaPay opens."), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        ToggleRow("Use app lock", "Uses the screen lock already set on your phone", AppLock.enabled) { want ->
            val code = AppLock.status(ctx)
            if (act == null) Ui.notify("App lock", "Not available right now.")
            else if (code != BiometricManager.BIOMETRIC_SUCCESS) Ui.notify("App lock", AppLock.statusMessage(code))
            else AppLock.prompt(act, if (want) "Turn on app lock" else "Turn off app lock", "Confirm it's you",
                { AppLock.changeEnabled(want) }, { if (it.isNotEmpty()) Ui.notify("App lock", it) })
        }
        ToggleRow("Confirm payments with fingerprint or face", "Ask before every transfer, withdrawal and request payment (before the transaction PIN, if you use one)", AppLock.confirmPayments) { want ->
            val code = AppLock.status(ctx)
            if (act == null) Ui.notify("Payments", "Not available right now.")
            else if (code != BiometricManager.BIOMETRIC_SUCCESS) Ui.notify("Payments", AppLock.statusMessage(code))
            else AppLock.prompt(act, if (want) "Turn on payment confirmation" else "Turn off payment confirmation", "Confirm it's you",
                { AppLock.changeConfirmPayments(want) }, { if (it.isNotEmpty()) Ui.notify("Payments", it) })
        }
        if (AppLock.enabled) {
            ToggleRow("Lock when I open the app", "Ask every time you come back after the delay below", AppLock.onLaunch) { AppLock.changeOnLaunch(it) }
            ToggleRow("Hide screen in recent apps", "Also blocks screenshots and screen recording", AppLock.hideScreen) { AppLock.changeHideScreen(it) }
            Spacer(Modifier.height(6.dp))
            Dropdown(
                "Lock after leaving the app", AppLock.timeoutSec.toString(),
                listOf("0" to "Immediately", "60" to "After 1 minute", "300" to "After 5 minutes", "900" to "After 15 minutes")
            ) { AppLock.changeTimeout(it.toIntOrNull() ?: 60) }
        }
    }
}

/* ------------------------------------------------------------------ push settings */
@Composable
fun PushSettings() {
    val ctx = LocalContext.current
    val prefs = remember { mutableStateMapOf<String, Boolean>().apply { PushPrefs.ALL_KEYS.forEach { put(it, PushPrefs.get(ctx, it)) } } }
    val allowed = Push.notificationsAllowed(ctx)
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(L("Push notifications"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("Choose which alerts MudaPay may send to this phone. They also appear while the app is open."), color = Muted, fontSize = 12.sp)
        if (!allowed) {
            Spacer(Modifier.height(6.dp))
            InfoBanner("Notifications are switched off for MudaPay in your phone settings.", Danger)
            Spacer(Modifier.height(6.dp))
            PrimaryButton("OPEN PHONE SETTINGS", {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
        Spacer(Modifier.height(4.dp))
        PushPrefs.ALL_KEYS.forEach { k ->
            val (title, sub) = PushPrefs.LABELS.getValue(k)
            ToggleRow(title, sub, prefs[k] ?: true) { v ->
                prefs[k] = v
                PushPrefs.set(ctx, k, v)
                Push.sync(ctx)
            }
        }
        Spacer(Modifier.height(6.dp))
        PrimaryButton("SEND A TEST NOTIFICATION", {
            val ok = Push.post(ctx, PushMsg("account", "MudaPay notifications are working ✅",
                "This is a test. Withdrawals, rewards and account updates will appear like this.", "Home",
                listOf("Alerts also arrive when the app is open or the phone was restarted.")))
            if (!ok) Ui.notify("Notifications", "The test could not be shown. Check that notifications are allowed for MudaPay in your phone settings.")
        }, color = Dark, icon = MudaIcons.NotificationsActive)
        TextButton(onClick = {
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text(L("Keep alerts reliable: allow background activity"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
    }
}

/* ------------------------------------------------------------------ delete account */
@Composable
fun DeleteAccountSection() {
    var show by remember { mutableStateOf(false) }
    ContentRow("Delete my account", "Permanently remove your account and data", MudaIcons.DeleteForever, { show = true }, tint = Danger)
    if (show) DeleteAccountDialog { show = false }
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var maskedEmail by remember { mutableStateOf<String?>(null) }   // null = step 1 (password), set = step 2 (code sent to this address)
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val hasPassword = Session.user?.optBoolean("has_password", true) ?: true   // false = account made with Google

    fun sendCode() {
        busy = true; problem = null
        scope.launch {
            val r = Api.call("POST", "/account/delete/code", JSONObject())
            busy = false
            if (r.ok) { maskedEmail = r.data.str("email_masked"); code = "" }
            else problem = if (r.code == "email_not_verified") L("To delete your account we send a code to your verified e-mail address. Verify your e-mail first (Settings > Security > Email), then come back.") else r.message
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Panel,
        title = { Text(L("Delete your account?"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (maskedEmail == null) {
                    Text(L("Your account is permanently erased: profile, ID photos and selfie, chats and history. Any money left in your balance is lost. Payment records that other people or our accounts still need stay, without your name, phone number or notes. This cannot be undone."), color = Ink, fontSize = 13.sp)
                    Text(L("For your safety we will send a code to your verified e-mail address."), color = Muted, fontSize = 12.sp)
                    if (hasPassword) InputField("Password", password, { password = it }, password = true)
                    InputField("Type DELETE to confirm", confirm, { confirm = it })
                    TextButton(onClick = { openUrl(ctx, Config.DELETE_URL) }) {
                        Text(L("Can't sign in? Request deletion on the website"), color = GreenDark, fontSize = 12.sp)
                    }
                } else {
                    Text(L("We sent a 6-digit code to") + " $maskedEmail. " + L("Enter it to delete your account for good."), color = Ink, fontSize = 13.sp)
                    InputField(L("Code from the e-mail"), code, { code = it.filter { c -> c.isDigit() }.take(6) }, keyboard = KeyboardType.Number)
                    TextButton(onClick = { sendCode() }, enabled = !busy) { Text(L("Send the code again"), color = GreenDark, fontSize = 12.sp) }
                }
                problem?.let { Text(it, color = Danger, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            if (maskedEmail == null) {
                TextButton(
                    enabled = !busy && (!hasPassword || password.isNotEmpty()) && confirm.trim() == "DELETE",
                    onClick = { sendCode() }
                ) { Text(if (busy) L("Sending…") else L("Send me a code"), color = Danger, fontWeight = FontWeight.Bold) }
            } else {
                TextButton(
                    enabled = !busy && code.length == 6,
                    onClick = {
                        busy = true; problem = null
                        scope.launch {
                            val body = JSONObject().put("confirm", true).put("code", code)
                            if (hasPassword) body.put("password", password)
                            val r = Api.call("POST", "/account/delete", body)
                            busy = false
                            if (r.ok) {
                                onDismiss()
                                Session.signOut()
                                Ui.notify("Account deleted", r.data.str("message", "Your account has been deleted."))
                            } else problem = r.message
                        }
                    }
                ) { Text(if (busy) L("Deleting…") else L("Delete for good"), color = Danger, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(L("Cancel"), color = Muted) } }
    )
}


@Composable
fun AppLanguageSettings() {
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L("Language"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("Choose the language used throughout the app."), color = Muted, fontSize = 12.sp)
        val choices = listOf("en" to "English", "fr" to "Français", "cs" to "Čeština", "ha" to "Hausa", "pt" to "Português", "sw" to "Kiswahili", "rw" to "Kinyarwanda")
        choices.chunked(3).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { (code, name) ->
                    FilterChip(selected = AppLanguage.current == code, onClick = { AppLanguage.set(code) }, label = { Text(name) }, colors = mudaChipColors(), border = null, shape = RoundedCornerShape(50))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ rate us */
@Composable
fun RateUsSettings() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_play_store), null, Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(L("Rate MudaPay"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Text(L("Your rating on Google Play helps other people find MudaPay."), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        ToggleRow(L("Ask me to rate the app"), L("A short reminder after a successful payment. Only a few times, never while you are busy, and never again once you have rated."), RateUs.autoPrompt) { RateUs.changeAutoPrompt(it) }
        Spacer(Modifier.height(6.dp))
        PrimaryButton(L("RATE ON GOOGLE PLAY"), { RateUs.openStorePage(ctx) })
    }
}

/* ------------------------------------------------------------------ hide balance */
@Composable
fun PrivacySettings() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { BalancePrivacy.load(ctx) }
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(L("Hide balance"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("For public places: nobody can read your balance over your shoulder."), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        ToggleRow(L("Hide my balance"), L("Same switch as the eye icon on the home card, the widget and the quick tile"), BalancePrivacy.hidden) { BalancePrivacy.set(ctx, it) }
        ToggleRow(L("Shake to hide"), L("Shake the phone quickly while MudaPay is open to hide the balance. A shake never shows it again."), BalancePrivacy.shake) { BalancePrivacy.changeShake(ctx, it) }
        ToggleRow(L("Start hidden"), L("MudaPay opens with the balance already hidden"), BalancePrivacy.startHidden) { BalancePrivacy.changeStartHidden(ctx, it) }
        TextButton(onClick = { ShakeToHide.addQuickTile(ctx) }) {
            Text(L("Add the \"Hide balance\" quick tile"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
