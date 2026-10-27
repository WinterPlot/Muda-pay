package io.mudapay.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Optional Transaction PIN (4–6 digits). The PIN lives on the server (table user_pins, stored hashed); the app never keeps it.
 * While it is on, the server refuses to send money or withdraw unless the right PIN comes with the request.
 *   - PinPromptDialog: asked right before a transfer / withdrawal is sent.
 *   - TransactionPinSettings: the card in Settings to turn it on, change it, or turn it off.
 */
private fun digitsOnly(s: String) = s.filter { it.isDigit() }.take(6)

/** Asks for the PIN and hands it back. The server decides whether it is right (and counts wrong tries). */
@Composable
fun PinPromptDialog(title: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(L(title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("Enter your transaction PIN to continue."), color = Ink, fontSize = 13.sp)
                InputField("Transaction PIN", pin, { pin = digitsOnly(it) }, keyboard = KeyboardType.NumberPassword, password = true)
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = { onSubmit(pin) }) { Text(L("Confirm"), color = GreenDark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel"), color = Muted) } }
    )
}

private enum class PinMode { Enable, Change, Disable }

@Composable
private fun PinManageDialog(mode: PinMode, canUsePassword: Boolean, onClose: (changed: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val needsProof = mode != PinMode.Enable
    val needsNew = mode != PinMode.Disable
    val proofOk = !needsProof || (if (usePassword) password.isNotEmpty() else current.length >= 4)
    val newOk = !needsNew || (pin.length in 4..6 && pin == again)
    val title = when (mode) { PinMode.Enable -> "Set a transaction PIN"; PinMode.Change -> "Change your PIN"; PinMode.Disable -> "Turn off transaction PIN" }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose(false) },
        containerColor = Color.White,
        title = { Text(L(title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode == PinMode.Enable) Text(L("Choose 4 to 6 digits. You will need it every time you send money or withdraw. Do not share it with anyone."), color = Ink, fontSize = 13.sp)
                if (needsProof) {
                    if (usePassword) InputField("Account password", password, { password = it; error = null }, password = true)
                    else InputField("Current PIN", current, { current = digitsOnly(it); error = null }, keyboard = KeyboardType.NumberPassword, password = true)
                    if (canUsePassword) TextButton(onClick = { usePassword = !usePassword; error = null }) {
                        Text(L(if (usePassword) "Use my PIN instead" else "Forgot my PIN: use my password"), color = GreenDark, fontSize = 12.sp)
                    }
                }
                if (needsNew) {
                    InputField(if (mode == PinMode.Change) "New PIN (4–6 digits)" else "PIN (4–6 digits)", pin, { pin = digitsOnly(it); error = null }, keyboard = KeyboardType.NumberPassword, password = true)
                    InputField("Repeat the PIN", again, { again = digitsOnly(it); error = null }, keyboard = KeyboardType.NumberPassword, password = true)
                    if (again.isNotEmpty() && pin != again) Text(L("The two PINs are not the same."), color = Danger, fontSize = 12.sp)
                }
                error?.let { Text(it, color = Danger, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && proofOk && newOk, onClick = {
                busy = true; error = null
                scope.launch {
                    val body = JSONObject()
                    if (needsProof) { if (usePassword) body.put("password", password) else body.put("current_pin", current) }
                    if (needsNew) body.put("pin", pin)
                    val path = when (mode) { PinMode.Enable -> "/account/pin"; PinMode.Change -> "/account/pin/change"; PinMode.Disable -> "/account/pin/disable" }
                    val r = Api.call("POST", path, body)
                    busy = false
                    if (r.ok) {
                        Ui.notify("Transaction PIN", when (mode) { PinMode.Enable -> "Transaction PIN is on."; PinMode.Change -> "Your PIN was changed."; PinMode.Disable -> "Transaction PIN is off." })
                        onClose(true)
                    } else error = r.message
                }
            }) { Text(if (busy) "…" else L("Save"), color = if (mode == PinMode.Disable) Danger else GreenDark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { onClose(false) }, enabled = !busy) { Text(L("Cancel"), color = Muted) } }
    )
}

@Composable
fun TransactionPinSettings() {
    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var mode by remember { mutableStateOf<PinMode?>(null) }
    LaunchedEffect(tick) { val r = Api.call("GET", "/account/pin"); if (r.ok) status = r.data }
    val s = status
    val enabled = s?.bool("enabled") == true

    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(L("Transaction PIN"), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text(L("Ask for a 4–6 digit PIN before every money transfer and withdrawal."), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(L("Use transaction PIN"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(L(if (enabled) "On: nobody can send or withdraw your money without it." else "Off: money can be sent or withdrawn without a PIN."), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Switch(
                checked = enabled, enabled = s != null,
                onCheckedChange = { want -> mode = if (want) PinMode.Enable else PinMode.Disable },
                colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.White)
            )
        }
        if (enabled) {
            TextButton(onClick = { mode = PinMode.Change }) { Text(L("Change PIN"), color = GreenDark, fontWeight = FontWeight.Bold) }
            if ((s?.int("locked_for") ?: 0) > 0) InfoBanner("Too many wrong PINs. Sending and withdrawing are paused for a few minutes.", Danger, Icons.Default.Lock)
        }
    }
    mode?.let { m ->
        PinManageDialog(m, s?.bool("can_use_password") == true) { changed -> mode = null; if (changed) tick++ }
    }
}
