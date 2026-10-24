package io.mudapay.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/* ------------------------------------------------------------------ small pieces */
private fun initials(name: String): String =
    name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "M" }

@Composable
private fun Avatar(name: String, tint: Color = GreenDark) {
    Box(Modifier.size(44.dp).background(tint.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
        Text(initials(name), color = tint, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}

@Composable
private fun PersonRow(name: String, sub: String, amount: String? = null, positive: Boolean = true, onClick: (() -> Unit)? = null) {
    val tint = if (positive) GreenDark else Danger
    Row(
        Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1)
            Text(sub, color = Muted, fontSize = 12.sp, maxLines = 2)
        }
        if (amount != null) Text(amount, color = tint, fontWeight = FontWeight.Black, fontSize = 13.sp)
    }
}

private fun receiptText(d: JSONObject, symbol: String): String = buildString {
    appendLine("MudaPay transfer receipt")
    appendLine("Reference: ${d.str("reference")}")
    appendLine("Amount: ${money(symbol, d.dbl("amount"))}")
    appendLine("To: ${d.optJSONObject("recipient")?.str("name").orEmpty()}")
    if (d.str("note").isNotEmpty()) appendLine("Note: ${d.str("note")}")
    appendLine("Date: ${d.str("created_at").take(16)}")
    appendLine("Status: Completed")
}.trim()

/** The member's recent transfers in one direction ("out" = sent, "in" = received). */
@Composable
private fun TransferList(direction: String, refreshKey: Int, symbol: String) {
    Remote(refreshKey = refreshKey, call = { Api.call("GET", "/transfers", query = mapOf("direction" to direction)) }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) {
            EmptyState(if (direction == "in") "No money received yet" else "No transfers yet", if (direction == "in") Icons.Default.QrCode2 else Icons.Default.NorthEast)
        }
        items.forEach { t ->
            val out = t.str("direction") == "out"
            val who = t.optJSONObject("counterparty")
            val note = t.str("note")
            val sub = buildString {
                append(if (out) "To " else "From ")
                append(who?.str("phone").orEmpty().ifEmpty { "MudaPay" })
                append(" • ").append(t.str("created_at").take(16))
                if (note.isNotEmpty()) append("\n").append(note)
            }
            PersonRow(who?.str("name").orEmpty().ifEmpty { "MudaPay member" }, sub, (if (out) "-" else "+") + money(symbol, t.dbl("amount")), positive = !out)
        }
    }
}

/* ------------------------------------------------------------------ send */
@Composable
fun SendMoneyScreen(nav: (String) -> Unit) {
    var done by remember { mutableStateOf<JSONObject?>(null) }
    var recentKey by remember { mutableIntStateOf(0) }
    val symbol = Session.user?.str("symbol") ?: ""
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().background(Gradient, RoundedCornerShape(22.dp)).padding(20.dp)) {
            Text(L("AVAILABLE BALANCE"), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(money(symbol, Session.balance), color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text(L("Send money to any MudaPay member using their phone number."), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        val sent = done
        if (sent != null) {
            SentCard(sent, symbol, onAgain = { done = null }, onDone = { nav("Home") })
        } else {
            PrimaryButton(L("SCAN QR TO PAY"), { nav("Scan") }, icon = Icons.Default.QrCodeScanner)
            Remote(call = { Api.call("GET", "/transfers/rules") }) { rules, _ ->
                SendForm(rules, nav) { res -> done = res; recentKey++ }
            }
        }
        Text(L("Sent recently"), color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
        TransferList("out", recentKey, symbol)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SentCard(d: JSONObject, symbol: String, onAgain: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val who = d.optJSONObject("recipient")
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).background(GreenDark.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = GreenDark, modifier = Modifier.size(34.dp))
        }
        Text(L("Money sent"), color = Dark, fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text(money(symbol, d.dbl("amount")), color = GreenDark, fontWeight = FontWeight.Black, fontSize = 30.sp)
        Text("${L("To")} ${who?.str("name").orEmpty()} (${who?.str("phone").orEmpty()})", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text("${L("Reference")}: ${d.str("reference")}", color = Slate, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        PrimaryButton("SHARE RECEIPT", { shareText(ctx, receiptText(d, symbol)) }, icon = Icons.Default.Share)
        PrimaryButton("SEND AGAIN", onAgain, color = Dark, icon = Icons.Default.NorthEast)
        TextButton(onClick = onDone) { Text(L("Back to home"), color = GreenDark, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SendForm(rules: JSONObject, nav: (String) -> Unit, onSent: (JSONObject) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol")
    val min = rules.int("min")
    val remaining = rules.dbl("remaining_today")
    val verified = rules.bool("email_verified")
    val needEmail = rules.bool("require_verified_email") && !verified

    var phone by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<JSONObject?>(null) }
    var lookupMsg by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf(newIdemKey()) }
    var fromQr by remember { mutableStateOf(false) }

    fun lookup(p: String) {
        looking = true; lookupMsg = null; found = null
        scope.launch {
            val r = Api.call("POST", "/transfers/lookup", JSONObject().put("phone", p.trim()))
            looking = false
            if (!r.ok) lookupMsg = r.message
            else if (!r.data.bool("found")) lookupMsg = if (r.data.bool("self")) "That is your own number." else "No MudaPay account uses this number. Check it and try again."
            else if (!r.data.bool("can_receive")) lookupMsg = "This member cannot receive money right now."
            else if (!r.data.bool("same_currency")) lookupMsg = "You can only send money to members in your own country."
            else found = r.data
        }
    }

    // A QR code was scanned: fill in the number (and amount / note if the code carries them) and look the member up.
    val scanned = PayPrefill.pending
    LaunchedEffect(scanned) {
        if (scanned != null) {
            PayPrefill.pending = null
            phone = scanned.phone
            amount = scanned.amount?.toString().orEmpty()
            note = scanned.note
            fromQr = true
            if (!needEmail) lookup(scanned.phone)
        }
    }

    if (!verified) {
        InfoBanner(
            if (needEmail) "Verify your email in Settings before you can send money." else "Tip: verify your email in Settings to better protect your account.",
            if (needEmail) Danger else Blue, Icons.Default.Email
        )
        ContentRow("Verify your account", "Add your email and enter the code we send you", Icons.Default.Email, { nav("Settings") })
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Recipient"), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        InputField(
            "Phone number (with country code)", phone,
            { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20); found = null; lookupMsg = null; fromQr = false },
            keyboard = KeyboardType.Phone
        )
        PrimaryButton("FIND MEMBER", {
            lookup(phone)
        }, loading = looking, enabled = phone.count { it.isDigit() } >= 6 && !needEmail, color = Dark, icon = Icons.Default.Search)
        lookupMsg?.let { InfoBanner(it, Danger, Icons.Default.Info) }
    }

    val f = found
    if (f != null) {
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (fromQr) InfoBanner(L("Scanned from a MudaPay QR code. Check the name below before you pay."), Blue, Icons.Default.QrCodeScanner)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(f.str("name"))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.str("name"), color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text(f.str("phone"), color = Muted, fontSize = 12.sp)
                }
                Pill("MUDAPAY MEMBER", GreenDark)
            }
            InputField("Amount ($symbol)", amount, { amount = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
            Text(L("Minimum: ${money(symbol, min.toDouble())} • You can still send ${money(symbol, remaining)} today"), color = Muted, fontSize = 12.sp)
            InputField("Note (optional)", note, { note = it.take(120) })
            PrimaryButton("SEND MONEY", {
                val amt = amount.toLongOrNull() ?: 0L
                when {
                    amt < min -> Ui.notify("Send money", "The minimum transfer is ${money(symbol, min.toDouble())}.")
                    amt > Session.balance -> Ui.notify("Send money", "Insufficient balance.")
                    amt > remaining -> Ui.notify("Send money", "This is above your daily sending limit. You can still send ${money(symbol, remaining)} today.")
                    else -> confirm = true
                }
            }, loading = busy, enabled = amount.isNotEmpty() && !needEmail, icon = Icons.Default.NorthEast)
        }
    }

    if (confirm && f != null) {
        val amt = amount.toLongOrNull() ?: 0L
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Color.White,
            title = { Text(L("Send ${money(symbol, amt.toDouble())}?"), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${L("To")}: ${f.str("name")} (${f.str("phone")})", color = Ink, fontSize = 14.sp)
                    if (note.isNotBlank()) Text("${L("Note")}: ${note.trim()}", color = Ink, fontSize = 14.sp)
                    Text(L("Transfers are instant and cannot be undone. Make sure the person is right."), color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    AppLock.requireForWithdrawal(ctx.findActivity()) {
                        busy = true
                        scope.launch {
                            val body = JSONObject().put("phone", phone.trim()).put("amount", amt).put("note", note.trim())
                            val r = Api.call("POST", "/transfers", body, idem = idem)
                            busy = false
                            if (r.ok) {
                                idem = newIdemKey()
                                Session.balance = r.data.dbl("balance")
                                val who = r.data.optJSONObject("recipient")
                                Notify.moneySent(r.data.dbl("amount"), who?.str("name").orEmpty(), who?.str("phone").orEmpty(), r.data.str("reference"), r.data.str("note"), Session.balance)
                                amount = ""; note = ""; phone = ""; found = null; fromQr = false
                                onSent(r.data)
                            } else {
                                if (r.http != 0) idem = newIdemKey()
                                Ui.notify("Send money", r.message)
                            }
                        }
                    }
                }) { Text(L("Send"), color = GreenDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

/* ------------------------------------------------------------------ receive */
@Composable
fun ReceiveMoneyScreen() {
    val ctx = LocalContext.current
    val user = Session.user ?: JSONObject()
    val symbol = user.str("symbol")
    val phone = user.str("phone")
    val name = user.str("full_name")
    var requested by remember { mutableStateOf("") }
    val amountReq = requested.toLongOrNull()?.takeIf { it > 0 }
    val payload = remember(phone, amountReq) { if (phone.isBlank()) null else PayQr.build(phone, amountReq) }
    val qrBmp = remember(payload) { payload?.let { DotQr.render(it, 900) } }
    val qr = remember(qrBmp) { qrBmp?.asImageBitmap() }
    val amountLine = amountReq?.let { money(symbol, it.toDouble()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().background(Gradient, RoundedCornerShape(22.dp)).padding(20.dp)) {
            Text(L("RECEIVE MONEY"), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(phone, color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(name, color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L("Your MudaPay QR code"), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            if (qr != null) Box(Modifier.background(Color.White, RoundedCornerShape(20.dp)).padding(10.dp)) { Image(qr, "QR code to pay you", Modifier.size(250.dp)) }
            if (amountLine != null) Text("${L("Amount")}: $amountLine", color = GreenDark, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(
                L("Ask the person to open MudaPay and tap Scan to pay. They see your name before they confirm, and the money is added to your balance instantly."),
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center
            )
            InputField(L("Amount to request (optional)"), requested, { requested = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
            PrimaryButton(L("SHARE QR CODE"), {
                val src = qrBmp
                if (src != null) {
                    val card = DotQr.shareCard(src, name, phone, amountLine)
                    val caption = "Pay me on MudaPay: $phone" + (if (amountLine != null) " ($amountLine)" else "")
                    if (!DotQr.sharePicture(ctx, card, caption)) Ui.notify("Share", "The QR code could not be shared.")
                }
            }, enabled = qrBmp != null, icon = Icons.Default.Share)
            PrimaryButton(L("SHARE MY NUMBER"), { shareText(ctx, "Send me money on MudaPay with my phone number: $phone") }, color = Dark, icon = Icons.Default.Share)
            PrimaryButton(L("COPY NUMBER"), { copyToClipboard(ctx, "MudaPay number", phone); Ui.notify("Copied", "Your number was copied.") }, color = Dark, icon = Icons.Default.ContentCopy)
        }
        Text(L("Received recently"), color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
        TransferList("in", 0, symbol)
        Spacer(Modifier.height(8.dp))
    }
}
