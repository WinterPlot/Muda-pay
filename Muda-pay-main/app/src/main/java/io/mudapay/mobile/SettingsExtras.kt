package io.mudapay.mobile

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
        Text("Data saver", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text("Useful on slow or expensive connections.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        ToggleRow("Low-data mode", "Skips video thumbnails and loads other pictures only when you tap them", Offline.lowData) { Offline.setLowData(it) }
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
        Text("App lock", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text("Ask for your fingerprint, face or phone PIN before MudaPay opens.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        ToggleRow("Use app lock", "Uses the screen lock already set on your phone", AppLock.enabled) { want ->
            val code = AppLock.status(ctx)
            if (act == null) Ui.notify("App lock", "Not available right now.")
            else if (code != BiometricManager.BIOMETRIC_SUCCESS) Ui.notify("App lock", AppLock.statusMessage(code))
            else AppLock.prompt(act, if (want) "Turn on app lock" else "Turn off app lock", "Confirm it's you",
                { AppLock.changeEnabled(want) }, { if (it.isNotEmpty()) Ui.notify("App lock", it) })
        }
        if (AppLock.enabled) {
            ToggleRow("Lock when I open the app", "Ask every time you come back after the delay below", AppLock.onLaunch) { AppLock.changeOnLaunch(it) }
            ToggleRow("Confirm before withdrawals", "Ask again before a payout request is sent", AppLock.forWithdraw) { AppLock.changeForWithdraw(it) }
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
    val prefs = remember { mutableStateMapOf<String, Boolean>().apply { PushPrefs.KEYS.forEach { put(it, PushPrefs.get(ctx, it)) } } }
    val allowed = Push.notificationsAllowed(ctx)
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Push notifications", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text("Choose which alerts MudaPay may send to this phone.", color = Muted, fontSize = 12.sp)
        if (!Push.firebaseReady(ctx)) {
            Spacer(Modifier.height(6.dp))
            InfoBanner("Push alerts are not set up in this build yet.")
        } else if (!allowed) {
            Spacer(Modifier.height(6.dp))
            InfoBanner("Notifications are switched off for MudaPay in your phone settings.", Danger)
            Spacer(Modifier.height(6.dp))
            PrimaryButton("OPEN PHONE SETTINGS", {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
        Spacer(Modifier.height(4.dp))
        PushPrefs.KEYS.forEach { k ->
            val (title, sub) = PushPrefs.LABELS.getValue(k)
            ToggleRow(title, sub, prefs[k] ?: true) { v ->
                prefs[k] = v
                PushPrefs.set(ctx, k, v)
                Push.sync(ctx)
            }
        }
    }
}

/* ------------------------------------------------------------------ delete account */
@Composable
fun DeleteAccountSection() {
    var show by remember { mutableStateOf(false) }
    ContentRow("Delete my account", "Permanently remove your account and data", Icons.Default.DeleteForever, { show = true }, tint = Danger)
    if (show) DeleteAccountDialog { show = false }
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val hasPassword = Session.user?.optBoolean("has_password", true) ?: true   // false = account made with Google
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color.White,
        title = { Text("Delete your account?", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your balance, history and referrals will be permanently removed. This cannot be undone.", color = Ink, fontSize = 13.sp)
                if (hasPassword) InputField("Password", password, { password = it }, password = true)
                InputField("Type DELETE to confirm", confirm, { confirm = it })
                TextButton(onClick = { openUrl(ctx, Config.DELETE_URL) }) {
                    Text("Can't sign in? Request deletion on the website", color = GreenDark, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (!hasPassword || password.isNotEmpty()) && confirm.trim() == "DELETE",
                onClick = {
                    busy = true
                    scope.launch {
                        val body = JSONObject().put("confirm", true)
                        if (hasPassword) body.put("password", password)
                        val r = Api.call("POST", "/account/delete", body)
                        busy = false
                        if (r.ok) {
                            onDismiss()
                            Session.signOut()
                            Ui.notify("Account deleted", r.data.str("message", "Your account has been deleted."))
                        } else Ui.notify("Delete account", r.message)
                    }
                }
            ) { Text(if (busy) "Deleting…" else "Delete", color = Danger, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel", color = Muted) } }
    )
}
