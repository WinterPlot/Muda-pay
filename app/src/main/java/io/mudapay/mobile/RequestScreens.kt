package io.mudapay.mobile

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * Payment requests: "please pay me X".
 *   Received  – requests other members sent me: PAY (one tap + the usual app lock / transaction PIN) or DECLINE.
 *   Sent      – requests I made: CANCEL while they are open.
 *   New       – ask a member (same country or another supported country) for an amount in MY currency.
 * The server does all the maths (the payer sees what he pays and what the requester gets); the app only shows it.
 */
@Composable
fun RequestsScreen(nav: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
            Text(softCase(L("PAYMENT REQUESTS")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
            Text(money(Session.user?.str("symbol") ?: "", Session.balance), color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(L("Ask a member to pay you, or pay the requests you received."), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip(L("Received"), tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabChip(L("Sent"), tab == 1, Modifier.weight(1f)) { tab = 1 }
            TabChip(L("New request"), tab == 2, Modifier.weight(1f)) { tab = 2 }
        }
        when (tab) {
            0 -> RequestList("in", refresh) { refresh++ }
            1 -> RequestList("out", refresh) { refresh++ }
            else -> Remote(call = { Api.call("GET", "/transfers/rules") }) { rules, _ ->
                NewRequestForm(rules) { refresh++; tab = 1 }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.background(if (selected) GreenDark else Color.White, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (selected) Color.White else Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun RequestList(box: String, refreshKey: Int, onChanged: () -> Unit) {
    Remote(refreshKey = refreshKey, call = { Api.call("GET", "/requests", query = mapOf("box" to box)) }) { d, _ ->
        val items = d.list("items")
        val pin = d.bool("pin_required")
        if (items.isEmpty()) {
            EmptyState(if (box == "in") "No payment requests received" else "You have not asked anyone for money yet", if (box == "in") Icons.Default.Inbox else Icons.Default.NorthEast)
        }
        items.forEach { RequestCard(it, pin, onChanged) }
    }
}

@Composable
private fun RequestCard(r: JSONObject, pinRequired: Boolean, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val incoming = r.str("direction") == "in"
    val status = r.str("status")
    val who = r.optJSONObject("counterparty")
    val pay = r.optJSONObject("pay")
    val name = who?.str("name").orEmpty().ifEmpty { "MudaPay member" }
    val symbol = r.str("symbol")
    var confirm by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf(newIdemKey()) }

    fun doPay(pin: String) {
        val p = pay ?: return
        busy = true
        scope.launch {
            val body = JSONObject().put("expected_pay", p.dbl("amount"))
            if (pin.isNotEmpty()) body.put("pin", pin)
            val res = Api.call("POST", "/requests/${r.int("id")}/pay", body, idem = idem)
            busy = false
            if (res.ok) {
                idem = newIdemKey()
                Session.balance = res.data.dbl("balance")
                val to = res.data.optJSONObject("recipient")
                Notify.moneySent(res.data.dbl("amount"), to?.str("name").orEmpty(), to?.str("phone").orEmpty(), res.data.str("reference"), res.data.str("note"), Session.balance)
                Ui.notify("Request paid", "You paid ${money(p.str("symbol"), res.data.dbl("amount"))} to $name.")
                onChanged()
            } else {
                if (res.http != 0) idem = newIdemKey()
                Ui.notify("Payment request", res.message)
                if (res.code == "rate_changed" || res.code == "request_closed") onChanged()   // reload so the new amount / state is shown
            }
        }
    }
    fun close(path: String, done: String) {
        busy = true
        scope.launch {
            val res = Api.call("POST", "/requests/${r.int("id")}/$path")
            busy = false
            if (res.ok) Ui.notify("Payment request", done) else Ui.notify("Payment request", res.message)
            onChanged()
        }
    }

    val tone = when (status) { "paid" -> GreenDark; "pending" -> Blue; "declined" -> Danger; else -> Muted }
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(tone.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                Text(name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "M" },
                    color = tone, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                val cc = who?.str("country").orEmpty()
                Text(
                    (if (incoming) "Asks you for" else "You asked") + " • " + who?.str("phone").orEmpty() + (if (cc.isNotEmpty()) " (${countryName(cc)})" else "") + " • " + r.str("created_at").take(16),
                    color = Muted, fontSize = 12.sp, maxLines = 2
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(symbol, r.dbl("amount")), color = tone, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Pill(L(status.uppercase()), tone)
            }
        }
        if (r.str("note").isNotEmpty()) Text("“${r.str("note")}”", color = Slate, fontSize = 12.sp)
        if (status == "paid" && r.has("paid_amount")) {
            Text("${L(if (incoming) "You paid" else "Paid with")}: ${money(r.str("paid_symbol"), r.dbl("paid_amount"))}", color = Muted, fontSize = 12.sp)
        }
        if (incoming && status == "pending") {
            if (pay != null) {
                if (pay.bool("cross_border")) {
                    Text("${L("They receive")} ${money(symbol, pay.dbl("receive_amount"))} • ${L("Fee")}: ${money(pay.str("symbol"), pay.dbl("fee"))}", color = Muted, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("PAY ${money(pay.str("symbol"), pay.dbl("amount"))}", { confirm = true }, Modifier.weight(1f), loading = busy, icon = Icons.Default.NorthEast)
                    PrimaryButton("DECLINE", { close("decline", "Request declined.") }, Modifier.weight(0.6f), enabled = !busy, color = Dark)
                }
            } else {
                PrimaryButton("DECLINE", { close("decline", "Request declined.") }, enabled = !busy, color = Dark)
            }
        }
        if (!incoming && status == "pending") {
            TextButton(onClick = { close("cancel", "Request cancelled.") }, enabled = !busy) { Text(L("Cancel request"), color = Danger, fontWeight = FontWeight.Bold) }
        }
    }

    if (askPin) PinPromptDialog("Confirm payment", onDismiss = { askPin = false }) { pin -> askPin = false; doPay(pin) }
    if (confirm && pay != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Color.White,
            title = { Text(L("Pay ${money(pay.str("symbol"), pay.dbl("amount"))}?"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${L("To")}: $name", color = Ink, fontSize = 14.sp)
                    Text("${L("They receive")}: ${money(symbol, pay.dbl("receive_amount"))}", color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (pay.bool("cross_border")) Text("${L("Rate")}: 1 ${pay.str("symbol")} = ${fmtRate(pay.dbl("rate"))} $symbol • ${L("Fee")}: ${money(pay.str("symbol"), pay.dbl("fee"))}", color = Muted, fontSize = 12.sp)
                    if (r.str("note").isNotEmpty()) Text("${L("Note")}: ${r.str("note")}", color = Ink, fontSize = 14.sp)
                    Text(L("Transfers are instant and cannot be undone. Make sure the person is right."), color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    AppLock.requireForWithdrawal(ctx.findActivity()) { if (pinRequired) askPin = true else doPay("") }
                }) { Text(L("Pay"), color = GreenDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

@Composable
private fun NewRequestForm(rules: JSONObject, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol")
    val myCountry = rules.str("country")
    val min = rules.int("min")
    val countryList = rules.list("countries")
    val countryOptions = countryList.filter { rules.bool("cross_border") || it.str("code") == myCountry }.map { it.str("code") to it.str("name") }
    var country by remember { mutableStateOf(myCountry) }
    var phone by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<JSONObject?>(null) }
    var lookupMsg by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf(newIdemKey()) }

    fun lookup() {
        looking = true; lookupMsg = null; found = null
        scope.launch {
            val r = Api.call("POST", "/transfers/lookup", JSONObject().put("phone", phone.trim()).put("country", country))
            looking = false
            if (!r.ok) lookupMsg = r.message
            else if (!r.data.bool("found")) lookupMsg = if (r.data.bool("self")) "That is your own number." else "No MudaPay account uses this number. Check the number and the country, then try again."
            else if (!r.data.bool("can_receive")) lookupMsg = "This member cannot receive requests right now."
            else if (!r.data.bool("cross_border_allowed")) lookupMsg = "Requests to other countries are paused right now."
            else { country = r.data.str("country", country); found = r.data }
        }
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Who should pay you?"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (countryOptions.size > 1) Dropdown(L("Member's country"), country, countryOptions) { country = it; found = null; lookupMsg = null }
        val dial = countryList.firstOrNull { it.str("code") == country }?.str("dial").orEmpty()
        InputField(if (dial.isNotEmpty()) "Phone number (+$dial …)" else "Phone number (with country code)", phone,
            { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20); found = null; lookupMsg = null }, keyboard = KeyboardType.Phone)
        PrimaryButton("FIND MEMBER", { lookup() }, loading = looking, enabled = phone.count { it.isDigit() } >= 6, color = Dark, icon = Icons.Default.Search)
        lookupMsg?.let { InfoBanner(it, Danger, Icons.Default.Info) }
    }
    val f = found
    if (f != null) {
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.str("name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${f.str("phone")} • ${f.str("country_name").ifEmpty { countryName(f.str("country")) }}", color = Muted, fontSize = 12.sp)
                }
                Pill("MUDAPAY MEMBER", GreenDark)
            }
            InputField("Amount you want ($symbol)", amount, { amount = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
            Text(L("Minimum: ${money(symbol, min.toDouble())} • You receive exactly this amount in your own currency."), color = Muted, fontSize = 12.sp)
            InputField("Note (optional)", note, { note = it.take(120) })
            PrimaryButton("SEND REQUEST", {
                val amt = amount.toLongOrNull() ?: 0L
                if (amt < min) { Ui.notify("Payment request", "The minimum request is ${money(symbol, min.toDouble())}."); return@PrimaryButton }
                busy = true
                scope.launch {
                    val body = JSONObject().put("phone", phone.trim()).put("country", f.str("country")).put("amount", amt).put("note", note.trim())
                    val r = Api.call("POST", "/requests", body, idem = idem)
                    busy = false
                    if (r.ok) {
                        idem = newIdemKey()
                        Ui.notify("Request sent", "${f.str("name")} will get a notification and can pay you with one tap.")
                        amount = ""; note = ""; phone = ""; found = null; country = myCountry
                        onSent()
                    } else {
                        if (r.http != 0) idem = newIdemKey()
                        Ui.notify("Payment request", r.message)
                    }
                }
            }, loading = busy, enabled = amount.isNotEmpty(), icon = Icons.Default.NorthEast)
        }
    }
}
