package io.mudapay.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Account security, connected devices and KYC-based limits.
 *   GET  /account/security   GET /account/devices   POST /account/devices/revoke   POST /account/devices/revoke-others   GET /account/limits
 * Shown inside Settings > Security (see SocialScreens.kt: settings pages "devices" and "limits").
 */

private fun eventLabel(e: String): String = when (e) {
    "login" -> "Signed in"
    "new_device_login" -> "Signed in on a new device"
    "device_revoked" -> "A device was signed out"
    "devices_revoked_others" -> "Other devices were signed out"
    "logout_all" -> "Signed out of all devices"
    "password_changed" -> "Password changed"
    "password_reset" -> "Password reset"
    else -> e.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun checkLabel(k: String): String = when (k) {
    "email" -> "E-mail verified"
    "pin" -> "Transaction PIN set"
    "password" -> "Password set"
    "id" -> "Identity verified"
    else -> k
}

@Composable
private fun UsageBar(used: Double, limit: Double) {
    val f = if (limit <= 0) 0f else (used / limit).toFloat().coerceIn(0f, 1f)
    val tone = if (f >= 0.9f) Danger else Green
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Slate.copy(alpha = .22f))) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50)).background(tone))
    }
}

/** Top of Settings > Security: security check, shortcuts to devices / limits and the latest security events. */
@Composable
fun SecurityOverview() {
    Remote(call = { Api.call("GET", "/account/security") }) { d, _ ->
        val checks = d.optJSONArray("checks").objects()
        val events = d.optJSONArray("events").objects()
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Security check"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Pill("${d.int("score")}/${d.int("score_max")}", if (d.int("score") >= d.int("score_max")) Green else Amber)
            }
            checks.forEach { c ->
                val ok = c.bool("ok")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) MudaIcons.Check else MudaIcons.Info, null, tint = if (ok) Green else Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(L(checkLabel(c.str("key"))), color = if (ok) Ink else Muted, fontSize = 14.sp)
                }
            }
        }
        ContentRow(L("Connected devices"), "${d.int("devices")} " + L("signed in"), MudaIcons.Smartphone, { Ui.settingsPage = "devices" })
        ContentRow(L("Transaction limits"), L("Level") + ": " + L(d.str("kyc_level_name")), MudaIcons.Security, { Ui.settingsPage = "limits" })
        if (events.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("Recent security activity"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                events.take(6).forEach { e ->
                    Column {
                        Text(L(eventLabel(e.str("event"))), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val sub = listOf(e.str("device"), e.str("ip"), e.str("at").take(16)).filter { it.isNotEmpty() }.joinToString(" • ")
                        if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Settings > Security > Connected devices. */
@Composable
fun SettingsDevices() {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<JSONObject?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(0) }
    SettingsPane {
        Remote(refreshKey = key, call = { Api.call("GET", "/account/devices") }) { d, reload ->
            val items = d.optJSONArray("items").objects()
            Text(L("These phones are signed in to your account. If you do not recognise one, sign it out and change your password."), color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            items.forEach { it ->
                val cur = it.bool("current")
                Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(MudaIcons.Smartphone, GreenDark, 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.str("name"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            val app = it.str("app_version")
                            Text(it.str("platform").replaceFirstChar { c -> c.uppercase() } + (if (app.isNotEmpty()) " • MudaPay $app" else ""), color = Muted, fontSize = 12.sp)
                        }
                        if (cur) Pill(L("This device"), Green)
                    }
                    Text(L("Last active") + ": " + it.str("last_seen").take(16), color = Muted, fontSize = 12.sp)
                    val ip = it.str("ip")
                    if (ip.isNotEmpty()) Text("IP: $ip", color = Muted, fontSize = 12.sp)
                    Text(L("Signed in since") + ": " + it.str("first_seen").take(10), color = Muted, fontSize = 12.sp)
                    if (!cur) GhostButton(L("SIGN OUT THIS DEVICE"), { confirm = it }, icon = MudaIcons.Lock)
                }
            }
            if (items.size > 1) PrimaryButton(L("SIGN OUT ALL OTHER DEVICES"), { confirmAll = true }, color = Danger, loading = busy)
            if (confirm != null) {
                val target = confirm!!
                AlertDialog(
                    onDismissRequest = { confirm = null },
                    title = { Text(L("Sign out this device?")) },
                    text = { Text(target.str("name") + " " + L("will be signed out and will stop receiving alerts.")) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirm = null; busy = true
                            scope.launch {
                                val r = Api.call("POST", "/account/devices/revoke", JSONObject().put("session_id", target.str("session_id")))
                                busy = false
                                if (r.ok) { Ui.notify("Devices", "Device signed out."); key++; reload() } else Ui.notify("Devices", r.message)
                            }
                        }) { Text(L("SIGN OUT")) }
                    },
                    dismissButton = { TextButton(onClick = { confirm = null }) { Text(L("CANCEL")) } }
                )
            }
            if (confirmAll) AlertDialog(
                onDismissRequest = { confirmAll = false },
                title = { Text(L("Sign out all other devices?")) },
                text = { Text(L("Every phone except this one will be signed out.")) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmAll = false; busy = true
                        scope.launch {
                            val r = Api.call("POST", "/account/devices/revoke-others", JSONObject())
                            busy = false
                            if (r.ok) { Ui.notify("Devices", "Other devices signed out."); key++; reload() } else Ui.notify("Devices", r.message)
                        }
                    }) { Text(L("SIGN OUT ALL")) }
                },
                dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(L("CANCEL")) } }
            )
        }
    }
}

private fun kindTitle(k: String): String = when (k) {
    "send" -> "Sending money"
    "withdraw" -> "Withdrawals"
    "deposit" -> "Deposits"
    else -> k
}

/** Settings > Security > Transaction limits (by verification level). */
@Composable
fun SettingsLimits() {
    var kyc by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(0) }
    SettingsPane {
        Remote(refreshKey = key, call = { Api.call("GET", "/account/limits") }) { d, reload ->
            val sym = d.str("symbol").ifEmpty { d.str("currency") }
            val level = d.int("level")
            val idStatus = d.str("id_status")
            Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L("Your verification level"), color = Muted, fontSize = 12.sp)
                        Text(L(d.str("level_name")), color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Pill("${level + 1}/3", if (level >= 2) Green else Amber)
                }
                Text(
                    when (level) {
                        0 -> L("Verify your e-mail, then your identity, to raise your limits.")
                        1 -> L("Verify your identity to raise your limits.")
                        else -> L("Your identity is verified: you have the highest limits.")
                    }, color = Muted, fontSize = 13.sp, lineHeight = 18.sp
                )
                if (idStatus == "pending") InfoBanner(L("Your ID is being reviewed. Your limits rise as soon as it is approved."), Blue)
                if (idStatus == "rejected") InfoBanner(d.str("id_note").ifEmpty { L("Your last ID check was not accepted. You can try again.") }, Danger)
            }
            if (!d.bool("enabled")) InfoBanner(L("Limits are currently switched off."), Blue)
            d.optJSONArray("limits").objects().forEach { row ->
                Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L(kindTitle(row.str("kind"))), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row { Text(L("Per operation"), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(money(sym, row.dbl("per_tx")), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    Row { Text(L("Per 24 hours"), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(money(sym, row.dbl("used_day")) + " / " + money(sym, row.dbl("day")), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    UsageBar(row.dbl("used_day"), row.dbl("day"))
                    Row { Text(L("Per 30 days"), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(money(sym, row.dbl("used_month")) + " / " + money(sym, row.dbl("month")), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    UsageBar(row.dbl("used_month"), row.dbl("month"))
                    val next = row.optJSONObject("next")
                    if (next != null) Text(L("Next level") + ": " + money(sym, next.dbl("day")) + " " + L("per 24 hours"), color = GreenDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("Daily limits by level"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                d.optJSONArray("levels").objects().forEach { lv ->
                    val cur = lv.bool("current")
                    Column(Modifier.fillMaxWidth().background(if (cur) GreenFill else Color.Transparent, RoundedCornerShape(12.dp)).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(L(lv.str("name")), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (cur) Pill(L("You are here"), Green)
                        }
                        Text(L("Send") + " " + money(sym, lv.dbl("send_day")) + " • " + L("Withdraw") + " " + money(sym, lv.dbl("withdraw_day")) + " • " + L("Deposit") + " " + money(sym, lv.dbl("deposit_day")), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
            if (level < 2 && idStatus != "pending") PrimaryButton(L("VERIFY MY IDENTITY"), { kyc = true })
            if (kyc) KycFlowDialog("national_id", onClose = { kyc = false }, onSubmitted = { kyc = false; key++; reload() })
        }
    }
}
