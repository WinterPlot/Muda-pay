package io.mudapay.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Deposit money with crypto. The member types an amount in HIS OWN currency; the server converts it with a cross-checked
 * live rate, opens a NOWPayments invoice, and credits exactly that amount when the payment is confirmed (webhook).
 * The app never decides what is credited — it only shows the server's quote and polls the deposit state.
 */
private fun openInvoice(ctx: android.content.Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { Ui.notify("Deposit", "Could not open the payment page.") }
}

private fun usdText(v: Double) = String.format(Locale.US, "%,.2f USD", v)

@Composable
fun DepositScreen(nav: (String) -> Unit) {
    var listKey by remember { mutableIntStateOf(0) }
    val symbol = Session.user?.str("symbol") ?: ""
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
            Text(softCase(L("AVAILABLE BALANCE")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
            Text(money(symbol, Session.balance), color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(if (Session.user?.str("country")?.lowercase() == "cd") L("Add money with Airtel Money or crypto. You get it in your own currency.") else L("Add money with Lumicash or crypto. You get it in your own currency."), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        if (Session.user?.str("country")?.lowercase() == "bi") {
            LumicashDepositSection { listKey++ }
        }
        if (Session.user?.str("country")?.lowercase() == "cd") {
            AirtelDepositSection { listKey++ }
        }
        Remote(refreshKey = listKey, call = { Api.call("GET", "/deposits/rules") }) { rules, _ ->
            if (!rules.bool("enabled")) {
                InfoBanner(rules.str("message", "Deposits are not available right now."), Danger, Icons.Default.Info)
            } else if (rules.bool("paused")) {
                InfoBanner(rules.str("message"), Blue, Icons.Default.Info)
                OpenDeposits(rules.list("open"), rules.str("symbol"))
            } else {
                DepositForm(rules) { listKey++ }
            }
        }
        Text(L("Deposit history"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
        if (Session.user?.str("country")?.lowercase() == "bi") LumicashHistory(listKey, symbol)
        if (Session.user?.str("country")?.lowercase() == "cd") AirtelHistory(listKey, symbol)
        DepositHistory(listKey, symbol)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OpenDeposits(open: List<JSONObject>, symbol: String) {
    val ctx = LocalContext.current
    open.forEach { d ->
        val url = d.str("invoice_url")
        Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${money(symbol, d.dbl("amount_local"))} • ${d.str("status_text")}", color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("${L("Reference")}: ${d.str("reference")}", color = Muted, fontSize = 12.sp)
            if (url.isNotEmpty()) PrimaryButton("CONTINUE PAYMENT", { openInvoice(ctx, url) }, color = Dark, icon = Icons.Default.Payments)
        }
    }
}

@Composable
private fun DepositForm(rules: JSONObject, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol")
    val currency = rules.str("currency")
    val min = rules.dbl("min")
    val max = rules.dbl("max")
    var amount by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<JSONObject?>(null) }
    var quoteMsg by remember { mutableStateOf<String?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf(newIdemKey()) }
    var watching by remember { mutableStateOf<JSONObject?>(null) }

    // ask the server for the price 0.5 s after the member stops typing (the server is the only one that converts)
    LaunchedEffect(amount) {
        quote = null; quoteMsg = null
        val a = amount.toLongOrNull() ?: return@LaunchedEffect
        if (a <= 0) return@LaunchedEffect
        quoting = true
        delay(500)
        val r = Api.call("POST", "/deposits/quote", JSONObject().put("amount", a))
        quoting = false
        if (r.ok) quote = r.data else quoteMsg = r.message
    }

    // while a payment page is open, check the deposit every 6 s; stop when it is credited / failed / expired
    val w = watching
    if (w != null) {
        LaunchedEffect(w.int("id")) {
            var n = 0
            while (n < 300) {
                delay(6000); n++
                val st = Api.call("GET", "/deposits/${w.int("id")}")
                if (!st.ok) continue
                if (st.data.bool("credited")) {
                    Session.balance = st.data.dbl("balance")
                    Ui.notify("Deposit received", "${money(symbol, st.data.dbl("credited_amount"))} was added to your balance.")
                    Ui.refreshTick++
                    watching = null; onChanged(); break
                }
                if (st.data.str("status") in setOf("failed", "expired", "refunded")) { watching = null; onChanged(); break }
            }
        }
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("How much do you want to add?"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        InputField("Amount ($symbol)", amount, { amount = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
        val presets = rules.doubles("presets")
        if (presets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.take(4).forEach { p ->
                    Text(
                        money("", p).trim(), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.weight(1f).border(1.dp, GreenDark.copy(alpha = .4f), RoundedCornerShape(12.dp))
                            .clickable { amount = p.toLong().toString() }.padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        Text(L("Minimum: ${money(symbol, min)} • Maximum: ${money(symbol, max)} per payment"), color = Muted, fontSize = 12.sp)

        val q = quote
        if (quoting && q == null) Text(L("Getting the live rate…"), color = Muted, fontSize = 12.sp)
        quoteMsg?.let { InfoBanner(it, Danger, Icons.Default.Info) }
        if (q != null) {
            Column(Modifier.fillMaxWidth().background(GreenDark.copy(alpha = .07f), RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                QuoteLine(L("You receive"), money(symbol, q.dbl("amount")), strong = true)
                QuoteLine(L("You pay in crypto"), "≈ ${usdText(q.dbl("usd"))}", strong = true)
                QuoteLine(L("Rate"), "1 USD = ${String.format(Locale.US, "%,.2f", q.dbl("rate"))} $currency")
                if (q.dbl("fee_pct") > 0) QuoteLine(L("Service fee"), "${q.dbl("fee_pct")}%")
                Text(L("Network fees are added on the payment page. The rate is locked for ${rules.int("quote_valid_minutes") / 60} hours after you start."), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        PrimaryButton("CONTINUE", { confirm = true }, loading = busy, enabled = q != null && !quoting, color = Dark, icon = Icons.Default.CurrencyBitcoin)
    }

    if (confirm && quote != null) {
        val q = quote!!
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Color.White,
            title = { Text(L("Add ${money(symbol, q.dbl("amount"))}?"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${L("You will pay")}: ≈ ${usdText(q.dbl("usd"))} ${L("in crypto")}", color = Ink, fontSize = 14.sp)
                    Text(L("You will open the secure payment page, choose your coin (USDT, BTC, ETH…) and send the exact amount shown. Your balance updates automatically after confirmation."), color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false; busy = true
                    scope.launch {
                        val r = Api.call("POST", "/deposits", JSONObject().put("amount", q.dbl("amount").toLong()), idem = idem)
                        busy = false
                        if (r.ok) {
                            idem = newIdemKey()
                            val url = r.data.str("invoice_url")
                            if (url.isNotEmpty()) { watching = r.data; openInvoice(ctx, url) }
                            amount = ""; onChanged()
                        } else {
                            if (r.http != 0) idem = newIdemKey()
                            Ui.notify("Deposit", r.message)
                        }
                    }
                }) { Text(L("Pay with crypto"), color = GreenDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }

    OpenDeposits(rules.list("open"), symbol)
}

@Composable
private fun QuoteLine(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = if (strong) GreenDark else Ink, fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold, fontSize = if (strong) 14.sp else 12.sp)
    }
}

@Composable
private fun DepositHistory(refreshKey: Int, symbol: String) {
    Remote(refreshKey = refreshKey, call = { Api.call("GET", "/deposits") }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) EmptyState(L("No deposits yet"), Icons.Default.CurrencyBitcoin)
        items.forEach { t ->
            val ok = t.bool("credited")
            val st = t.str("status")
            val tone = when { ok -> GreenDark; st in setOf("failed", "expired", "refunded") -> Danger; else -> Blue }
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(money(symbol, if (ok) t.dbl("credited_amount") else t.dbl("amount_local")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${t.str("created_at").take(16)} • ${usdText(t.dbl("price_usd"))}", color = Muted, fontSize = 12.sp)
                    Text("${L("Reference")}: ${t.str("reference")}", color = Muted, fontSize = 11.sp)
                }
                Pill(t.str("status_text").uppercase(), tone)
            }
        }
    }
}
