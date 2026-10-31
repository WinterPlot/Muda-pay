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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import org.json.JSONObject

/* ------------------------------------------------------------------ small pieces */
private fun initials(name: String): String =
    name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "M" }

@Composable
private fun Avatar(name: String, tint: Color = GreenDark) {
    Box(Modifier.size(44.dp).background(tint.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
        Text(initials(name), color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
            Text(name, color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(sub, color = Muted, fontSize = 12.sp, maxLines = 2)
        }
        if (amount != null) Text(amount, color = tint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** 0.043729 -> "0.043729", 163.88888 -> "163.89": enough digits to be useful, no trailing zeros. */
@Composable
private fun QRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = if (strong) GreenDark else Ink, fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold, fontSize = if (strong) 14.sp else 12.sp)
    }
}

/** "You send / fee / converted / rate / they receive" — everything the member needs before he confirms. */
@Composable
fun QuoteBreakdown(q: JSONObject, symbol: String, recvSymbol: String, compact: Boolean = false) {
    val amount = q.dbl("amount")
    val fee = q.dbl("fee")
    val net = if (q.has("net_amount")) q.dbl("net_amount") else amount - fee
    Column(Modifier.fillMaxWidth().background(GreenDark.copy(alpha = .08f), RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        QRow(L("You send"), money(symbol, amount))
        QRow("${L("Fee")} (${fmtRate(q.dbl("fee_pct"))}%)", if (fee > 0) "− " + money(symbol, fee) else L("Free"))
        QRow(L("Amount converted"), money(symbol, net))
        QRow(L("Rate"), "1 $symbol = ${fmtRate(q.dbl("rate"))} $recvSymbol")
        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(GreenDark.copy(alpha = .18f)))
        Text(L("They receive"), color = Muted, fontSize = 12.sp)
        Text(money(recvSymbol, q.dbl("receive_amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = if (compact) 18.sp else 24.sp)
        if (!compact) Text(L("You are charged exactly the amount you send. The rate is checked again when you press Send; if it changed, you will see the new figures and confirm again."), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

fun fmtRate(r: Double): String {
    val f = when { r >= 100 -> "%.2f"; r >= 1 -> "%.4f"; else -> "%.6f" }
    return String.format(Locale.US, f, r).trimEnd('0').trimEnd('.')
}

private fun receiptText(d: JSONObject, symbol: String): String = buildString {
    appendLine("MudaPay transfer receipt")
    appendLine("Reference: ${d.str("reference")}")
    appendLine("Amount: ${money(symbol, d.dbl("amount"))}")
    if (d.bool("cross_border")) {
        appendLine("Fee: ${money(symbol, d.dbl("fee"))}")
        appendLine("Received: ${money(d.str("receive_symbol"), d.dbl("receive_amount"))}")
    }
    val rc = d.optJSONObject("recipient")?.str("country").orEmpty()
    appendLine("To: ${d.optJSONObject("recipient")?.str("name").orEmpty()}${if (rc.isNotEmpty()) " (${countryName(rc)})" else ""}")
    if (d.str("note").isNotEmpty()) appendLine("Note: ${d.str("note")}")
    appendLine("Date: ${d.str("created_at").take(16)}")
    appendLine("Status: Completed")
}.trim()

/** The member's recent transfers in one direction ("out" = sent, "in" = received). */
@Composable
private fun TransferList(direction: String, refreshKey: Int) {
    Remote(refreshKey = refreshKey, call = { Api.call("GET", "/transfers", query = mapOf("direction" to direction)) }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) {
            EmptyState(if (direction == "in") "No money received yet" else "No transfers yet", if (direction == "in") Icons.Default.QrCode2 else Icons.Default.NorthEast)
        }
        items.forEach { t ->
            val out = t.str("direction") == "out"
            val who = t.optJSONObject("counterparty")
            val note = t.str("note")
            val cross = t.bool("cross_border")
            val sub = buildString {
                append(if (out) "To " else "From ")
                append(who?.str("phone").orEmpty().ifEmpty { "MudaPay" })
                if (cross && who?.str("country").orEmpty().isNotEmpty()) append(" (").append(countryName(who!!.str("country"))).append(")")
                append(" • ").append(t.str("created_at").take(16))
                if (cross) append("\n").append(if (out) "They received " else "They sent ").append(money(t.str("counter_symbol"), t.dbl("counter_amount")))
                if (note.isNotEmpty()) append("\n").append(note)
            }
            PersonRow(who?.str("name").orEmpty().ifEmpty { "MudaPay member" }, sub, (if (out) "-" else "+") + money(t.str("symbol"), t.dbl("amount")), positive = !out)
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
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
            Text(softCase(L("AVAILABLE BALANCE")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
            Text(money(symbol, Session.balance), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(L("Send money to any MudaPay member, in your country or abroad, using their phone number."), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        val sent = done
        if (sent != null) {
            SentCard(sent, symbol, onAgain = { done = null }, onDone = { nav("Home") })
        } else {
            PrimaryButton(softCase(L("PICK FROM CONTACTS")), { nav("Contacts") }, icon = Icons.Default.Contacts)
            PrimaryButton(softCase(L("SCAN QR TO PAY")), { nav("Scan") }, color = Dark, icon = Icons.Default.QrCodeScanner)
            Remote(call = { Api.call("GET", "/transfers/rules") }) { rules, _ ->
                SendForm(rules, nav) { res -> done = res; recentKey++ }
            }
        }
        Text(L("Sent recently"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
        TransferList("out", recentKey)
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
        Text(L("Money sent"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(money(symbol, d.dbl("amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 30.sp)
        if (d.bool("cross_border")) {
            Text("${L("They receive")} ${money(d.str("receive_symbol"), d.dbl("receive_amount"))}", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("${L("Fee")}: ${money(symbol, d.dbl("fee"))} • ${L("Rate")}: 1 $symbol = ${fmtRate(d.dbl("rate"))} ${d.str("receive_symbol")}", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
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
    val myCountry = rules.str("country")
    val min = rules.int("min")
    val remaining = rules.dbl("remaining_today")
    val verified = rules.bool("email_verified")
    val needEmail = rules.bool("require_verified_email") && !verified
    val crossOn = rules.bool("cross_border")
    val feePct = rules.dbl("fee_pct")
    val countryList = rules.list("countries")
    // everyone can pick any supported country; when cross-border sending is paused only the member's own country is offered
    val countryOptions = countryList.filter { crossOn || it.str("code") == myCountry }.map { it.str("code") to it.str("name") }

    var country by remember { mutableStateOf(myCountry) }
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
    var quote by remember { mutableStateOf<JSONObject?>(null) }
    var quoting by remember { mutableStateOf(false) }
    val pinRequired = rules.bool("pin_required")
    var askPin by remember { mutableStateOf(false) }

    fun lookup(p: String, cc: String) {
        looking = true; lookupMsg = null; found = null; quote = null
        scope.launch {
            val r = Api.call("POST", "/transfers/lookup", JSONObject().put("phone", p.trim()).put("country", cc))
            looking = false
            if (!r.ok) lookupMsg = r.message
            else if (!r.data.bool("found")) lookupMsg = if (r.data.bool("self")) "That is your own number." else {
                val where = r.data.str("country_name")
                "No MudaPay account uses this number" + (if (where.isNotEmpty()) " in $where" else "") + ". Check the number and the country, then try again."
            }
            else if (!r.data.bool("can_receive")) lookupMsg = "This member cannot receive money right now."
            else if (!r.data.bool("cross_border_allowed")) lookupMsg = "Sending to other countries is paused right now."
            else { country = r.data.str("country", cc); found = r.data }
        }
    }

    val cross = found?.let { it.str("country") != myCountry } == true
    val recvSymbol = found?.str("symbol").orEmpty()

    // A QR code was scanned: fill in the number (and amount / note / country if the code carries them) and look the member up.
    val scanned = PayPrefill.pending
    LaunchedEffect(scanned) {
        if (scanned != null) {
            PayPrefill.pending = null
            phone = scanned.phone
            amount = scanned.amount?.toString().orEmpty()
            note = scanned.note
            if (scanned.country.isNotEmpty() && countryOptions.any { it.first == scanned.country }) country = scanned.country
            fromQr = true
            if (!needEmail) lookup(scanned.phone, country)
        }
    }

    // Across a border, ask the server what the receiver will get (rate + fee) whenever the amount changes.
    LaunchedEffect(amount, found) {
        val f = found
        val amt = amount.toLongOrNull() ?: 0L
        if (f == null || f.str("country") == myCountry || amt <= 0) { quote = null; quoting = false; return@LaunchedEffect }
        quoting = true
        delay(400)
        val r = Api.call("POST", "/transfers/quote", JSONObject().put("country", f.str("country")).put("amount", amt))
        quoting = false
        quote = if (r.ok) r.data else null
    }

    if (!verified) {
        InfoBanner(
            if (needEmail) "Verify your email in Settings before you can send money." else "Tip: verify your email in Settings to better protect your account.",
            if (needEmail) Danger else Blue, Icons.Default.Email
        )
        ContentRow("Verify your account", "Add your email and enter the code we send you", Icons.Default.Email, { nav("Settings") })
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Recipient"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (countryOptions.size > 1) {
            Dropdown(L("Recipient's country"), country, countryOptions) { country = it; found = null; quote = null; lookupMsg = null; fromQr = false }
        }
        val dial = countryList.firstOrNull { it.str("code") == country }?.str("dial").orEmpty()
        InputField(
            if (dial.isNotEmpty()) "Phone number (+$dial …)" else "Phone number (with country code)", phone,
            { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20); found = null; quote = null; lookupMsg = null; fromQr = false },
            keyboard = KeyboardType.Phone
        )
        PrimaryButton("FIND MEMBER", {
            lookup(phone, country)
        }, loading = looking, enabled = phone.count { it.isDigit() } >= 6 && !needEmail, color = Dark, icon = Icons.Default.Search)
        lookupMsg?.let { InfoBanner(it, Danger, Icons.Default.Info) }
    }

    val f = found
    if (f != null) {
        val q = quote
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (fromQr) InfoBanner(L("Scanned from a MudaPay QR code. Check the name below before you pay."), Blue, Icons.Default.QrCodeScanner)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(f.str("name"))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.str("name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${f.str("phone")} • ${f.str("country_name").ifEmpty { countryName(f.str("country")) }}", color = Muted, fontSize = 12.sp)
                }
                Pill(if (cross) "ABROAD" else "MUDAPAY MEMBER", if (cross) Blue else GreenDark)
            }
            InputField("Amount you send ($symbol)", amount, { amount = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
            Text(L("Minimum: ${money(symbol, min.toDouble())} • You can still send ${money(symbol, remaining)} today"), color = Muted, fontSize = 12.sp)
            if (cross) {
                if (q != null) {
                    QuoteBreakdown(q, symbol, recvSymbol)
                } else if (quoting) {
                    Text(L("Calculating…"), color = Muted, fontSize = 12.sp)
                } else {
                    Text(L("Enter an amount to see what ${f.str("name")} will receive in $recvSymbol. Fee: ${fmtRate(feePct)}%."), color = Muted, fontSize = 12.sp)
                }
            }
            InputField("Note (optional)", note, { note = it.take(120) })
            PrimaryButton("SEND MONEY", {
                val amt = amount.toLongOrNull() ?: 0L
                when {
                    amt < min -> Ui.notify("Send money", "The minimum transfer is ${money(symbol, min.toDouble())}.")
                    amt > Session.balance -> Ui.notify("Send money", "Insufficient balance.")
                    amt > remaining -> Ui.notify("Send money", "This is above your daily sending limit. You can still send ${money(symbol, remaining)} today.")
                    cross && q == null -> Ui.notify("Send money", "Wait a moment while the exchange rate loads.")
                    else -> confirm = true
                }
            }, loading = busy, enabled = amount.isNotEmpty() && !needEmail, icon = Icons.Default.NorthEast)
        }
    }

    // Sends the transfer. [pin] is "" when the member has no transaction PIN; the server enforces it either way.
    fun submit(amt: Long, pin: String) {
        val f = found ?: return
        val q = quote
        busy = true
        scope.launch {
            val body = JSONObject().put("phone", phone.trim()).put("amount", amt).put("note", note.trim()).put("country", f.str("country"))
            if (cross && q != null) body.put("expected_receive", q.dbl("receive_amount"))
            if (pin.isNotEmpty()) body.put("pin", pin)
            val r = Api.call("POST", "/transfers", body, idem = idem)
            busy = false
            if (r.ok) {
                idem = newIdemKey()
                Session.balance = r.data.dbl("balance")
                val who = r.data.optJSONObject("recipient")
                Notify.moneySent(r.data.dbl("amount"), who?.str("name").orEmpty(), who?.str("phone").orEmpty(), r.data.str("reference"), r.data.str("note"), Session.balance)
                amount = ""; note = ""; phone = ""; found = null; quote = null; fromQr = false; country = myCountry
                onSent(r.data)
            } else {
                if (r.http != 0) idem = newIdemKey()
                // the rate moved while the member was reading: show the new figures and let him confirm again
                if (r.code == "rate_changed") r.error?.optJSONObject("quote")?.let { quote = it }
                Ui.notify("Send money", r.message)
            }
        }
    }

    if (askPin) PinPromptDialog("Confirm transfer", onDismiss = { askPin = false }) { pin -> askPin = false; submit(amount.toLongOrNull() ?: 0L, pin) }

    if (confirm && f != null) {
        val amt = amount.toLongOrNull() ?: 0L
        val q = quote
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Color.White,
            title = { Text(L("Send ${money(symbol, amt.toDouble())}?"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${L("To")}: ${f.str("name")} (${f.str("phone")})", color = Ink, fontSize = 14.sp)
                    if (cross) {
                        Text("${L("Country")}: ${f.str("country_name").ifEmpty { countryName(f.str("country")) }}", color = Ink, fontSize = 14.sp)
                        if (q != null) {
                            QuoteBreakdown(q, symbol, recvSymbol, compact = true)
                        }
                    }
                    if (note.isNotBlank()) Text("${L("Note")}: ${note.trim()}", color = Ink, fontSize = 14.sp)
                    Text(L("Transfers are instant and cannot be undone. Make sure the person is right."), color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    AppLock.requireForWithdrawal(ctx.findActivity()) { if (pinRequired) askPin = true else submit(amt, "") }
                }) { Text(L("Send"), color = GreenDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

/* ------------------------------------------------------------------ receive */
@Composable
fun ReceiveMoneyScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val user = Session.user ?: JSONObject()
    val symbol = user.str("symbol")
    val phone = user.str("phone")
    val name = user.str("full_name")
    var requested by remember { mutableStateOf("") }
    val amountReq = requested.toLongOrNull()?.takeIf { it > 0 }
    val myCountry = user.str("country")
    val payload = remember(phone, amountReq, myCountry) { if (phone.isBlank()) null else PayQr.build(phone, amountReq, country = myCountry) }
    val qrBmp = remember(payload) { payload?.let { DotQr.render(it, 900) } }
    val qr = remember(qrBmp) { qrBmp?.asImageBitmap() }
    val amountLine = amountReq?.let { money(symbol, it.toDouble()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
            Text(softCase(L("RECEIVE MONEY")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
            Text(phone, color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(name, color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L("Your MudaPay QR code"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (qr != null) Box(Modifier.background(Color.White, RoundedCornerShape(20.dp)).padding(10.dp)) { Image(qr, "QR code to pay you", Modifier.size(250.dp)) }
            if (amountLine != null) Text("${L("Amount")}: $amountLine", color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                L("Ask the person to open MudaPay and tap Scan to pay. They see your name before they confirm, and the money is added to your balance instantly."),
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center
            )
            Text(
                L("Members in other supported countries can pay you too. You always receive in your own currency."),
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center
            )
            InputField(L("Amount to request (optional)"), requested, { requested = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
            PrimaryButton(softCase(L("SHARE QR CODE")), {
                val src = qrBmp
                if (src != null) {
                    val card = DotQr.shareCard(src, name, phone, amountLine)
                    val caption = "Pay me on MudaPay: $phone" + (if (amountLine != null) " ($amountLine)" else "")
                    if (!DotQr.sharePicture(ctx, card, caption)) Ui.notify("Share", "The QR code could not be shared.")
                }
            }, enabled = qrBmp != null, icon = Icons.Default.Share)
            PrimaryButton(softCase(L("REQUEST MONEY")), { nav("Requests") }, icon = Icons.Default.RequestQuote)
            PrimaryButton(softCase(L("SHARE MY NUMBER")), { shareText(ctx, "Send me money on MudaPay (${countryName(myCountry)}) with my phone number: $phone") }, color = Dark, icon = Icons.Default.Share)
            PrimaryButton(softCase(L("COPY NUMBER")), { copyToClipboard(ctx, "MudaPay number", phone); Ui.notify("Copied", "Your number was copied.") }, color = Dark, icon = Icons.Default.ContentCopy)
        }
        Text(L("Received recently"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
        TransferList("in", 0)
        Spacer(Modifier.height(8.dp))
    }
}
