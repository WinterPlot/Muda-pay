package io.mudapay.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

private class DepMethod(val id: String, val label: String, val sub: String, val icon: ImageVector)

private class DepLoad(val methods: List<DepMethod>, val message: String)

private suspend fun loadDepositMethods(country: String): DepLoad? = coroutineScope {
    val lumi = if (country == "bi") async { Api.call("GET", "/lumicash/rules") } else null
    val airtel = if (country == "cd") async { Api.call("GET", "/airtel/rules") } else null
    val fonte = async { Api.call("GET", "/payfonte/rules") }
    val card = async { Api.call("GET", "/payfonte/card/rules") }
    val crypto = async { Api.call("GET", "/deposits/rules") }
    val all = listOfNotNull(lumi, airtel, fonte, card, crypto).map { it.await() }
    if (all.none { it.ok }) return@coroutineScope null
    val out = mutableListOf<DepMethod>()
    if (lumi?.await()?.let { it.ok && it.data.bool("enabled") } == true) out += DepMethod("lumicash", "Lumicash", L("Mobile money"), MudaIcons.PhoneAndroid)
    if (airtel?.await()?.let { it.ok && it.data.bool("enabled") } == true) out += DepMethod("airtel", "Airtel Money", L("Mobile money"), MudaIcons.PhoneAndroid)
    val f = fonte.await().let { it.ok && it.data.bool("enabled") }
    if (f) out += DepMethod("payfonte", L("Mobile money"), L("Pay from your phone"), MudaIcons.AccountBalanceWallet)
    if (card.await().let { it.ok && it.data.bool("enabled") && !it.data.bool("paused") }) out += DepMethod("card", L("Card"), "Visa • Mastercard", MudaIcons.CreditCard)
    val c = crypto.await()
    if (c.ok && c.data.bool("enabled")) out += DepMethod("crypto", "Crypto", "USDT • BTC • ETH", MudaIcons.CurrencyBitcoin)
    DepLoad(out, if (c.ok) c.data.str("message", "Deposits are not available right now.") else "Deposits are not available right now.")
}

@Composable
private fun MethodTile(m: DepMethod, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.width(124.dp)
            .background(if (selected) Green.copy(alpha = .09f) else Color.White, shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Green else Hairline, shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconChip(m.icon, if (selected) Green else GreenDark, 36.dp)
        Column {
            Text(m.label, color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(m.sub, color = Muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
fun DepositScreen(nav: (String) -> Unit) {
    var listKey by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<DepLoad?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("") }
    val symbol = Session.user?.str("symbol") ?: ""
    val country = Session.user?.str("country")?.lowercase() ?: ""

    LaunchedEffect(reload, Ui.refreshTick) {
        val r = loadDepositMethods(country)
        if (r == null) { failed = loaded == null; return@LaunchedEffect }
        failed = false
        loaded = r
        if (r.methods.none { it.id == selected }) selected = r.methods.firstOrNull()?.id ?: ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(softCase(L("AVAILABLE BALANCE")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
            Text(money(symbol, Session.balance), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(L("Funds are added in your own currency."), color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
        }

        val data = loaded
        when {
            data == null && failed -> ErrorBox(L("Could not load deposit methods."), { failed = false; reload++ })
            data == null -> SkeletonCards()
            data.methods.isEmpty() -> InfoBanner(data.message, Danger, MudaIcons.Info)
            else -> {
                if (data.methods.size > 1) {
                    SectionHeader(L("Choose a method"))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        data.methods.forEach { m -> MethodTile(m, m.id == selected) { selected = m.id } }
                    }
                }
                when (selected) {
                    "lumicash" -> LumicashDepositSection { listKey++ }
                    "airtel" -> AirtelDepositSection { listKey++ }
                    "payfonte" -> PayfonteDepositSection({ listKey++ }, "/payfonte")
                    "card" -> Remote(refreshKey = listKey, call = { Api.call("GET", "/payfonte/card/rules") }) { rules, _ ->
                        DepositForm(rules, { listKey++ }, nav, card = true)
                    }
                    "crypto" -> Remote(refreshKey = listKey, call = { Api.call("GET", "/deposits/rules") }) { rules, _ ->
                        if (rules.bool("paused")) {
                            InfoBanner(rules.str("message"), Blue, MudaIcons.Info)
                            OpenDeposits(rules.list("open"), rules.str("symbol"), nav)
                        } else DepositForm(rules, { listKey++ }, nav)
                    }
                }
                SectionHeader(L("Recent deposits"))
                when (selected) {
                    "lumicash" -> LumicashHistory(listKey, symbol)
                    "airtel" -> AirtelHistory(listKey, symbol)
                    "payfonte" -> PayfonteHistory(listKey, symbol, "/payfonte")
                    "card" -> DepositHistory(listKey, symbol, card = true)
                    "crypto" -> DepositHistory(listKey, symbol)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OpenDeposits(open: List<JSONObject>, symbol: String, nav: (String) -> Unit) {
    val ctx = LocalContext.current
    open.forEach { d ->
        val url = d.str("invoice_url")
        Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${money(symbol, d.dbl("amount_local"))} • ${d.str("status_text")}", color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("${L("Reference")}: ${d.str("reference")}", color = Muted, fontSize = 12.sp)
            if (d.str("pay_address").isNotEmpty()) PrimaryButton("SHOW PAYMENT DETAILS", { Ui.selectedCryptoDeposit = d; nav("CryptoPay") }, color = Dark, icon = MudaIcons.Payments)
            else if (url.isNotEmpty()) PrimaryButton("CONTINUE PAYMENT", { openInvoice(ctx, url) }, color = Dark, icon = MudaIcons.Payments)
        }
    }
}

@Composable
private fun DepositForm(rules: JSONObject, onChanged: () -> Unit, nav: (String) -> Unit, card: Boolean = false) {
    val quotePath = if (card) "/payfonte/card/quote" else "/deposits/quote"
    val createPath = if (card) "/payfonte/card/deposit" else "/deposits"
    val listPath = if (card) "/payfonte/card/deposits" else "/deposits"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol")
    val currency = rules.str("currency")
    val min = rules.dbl("min")
    val max = rules.dbl("max")
    val coins = if (card) emptyList() else rules.list("coins")
    val inApp = coins.isNotEmpty()
    var coin by remember { mutableStateOf(coins.firstOrNull()?.str("code") ?: "") }
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
        val r = Api.call("POST", quotePath, JSONObject().put("amount", a))
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
                val st = Api.call("GET", "$listPath/${w.int("id")}")
                if (!st.ok) continue
                if (st.data.bool("credited")) {
                    Session.balance = st.data.dbl("balance")
                    TxSound.playFor(st.data)
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

        if (inApp) {
            Text(L("Pay with"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                coins.forEach { c ->
                    val on = c.str("code") == coin
                    Column(
                        Modifier.background(if (on) Green.copy(alpha = .09f) else Color.White, RoundedCornerShape(14.dp))
                            .border(if (on) 1.5.dp else 1.dp, if (on) Green else Hairline, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp)).clickable { coin = c.str("code") }.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(c.str("label"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                        Text(c.str("network"), color = Muted, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        val q = quote
        if (quoting && q == null) Text(L("Getting the live rate…"), color = Muted, fontSize = 12.sp)
        quoteMsg?.let { InfoBanner(it, Danger, MudaIcons.Info) }
        if (q != null) {
            Column(Modifier.fillMaxWidth().background(GreenDark.copy(alpha = .07f), RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                QuoteLine(L("You receive"), money(symbol, q.dbl("amount")), strong = true)
                QuoteLine(L(if (card) "You pay by card" else "You pay in crypto"), "≈ ${usdText(q.dbl("usd"))}", strong = true)
                QuoteLine(L("Rate"), "1 USD = ${String.format(Locale.US, "%,.2f", q.dbl("rate"))} $currency")
                if (q.dbl("fee_pct") > 0) QuoteLine(L("Service fee"), "${q.dbl("fee_pct")}%")
                Text(L(if (card) "Your bank may add its own fees. The rate is locked for ${rules.int("quote_valid_minutes") / 60} hours after you start." else "Network fees are added on the payment page. The rate is locked for ${rules.int("quote_valid_minutes") / 60} hours after you start."), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        PrimaryButton("CONTINUE", { confirm = true }, loading = busy, enabled = q != null && !quoting && (!inApp || coin.isNotEmpty()), color = Dark, icon = if (card) MudaIcons.CreditCard else MudaIcons.CurrencyBitcoin)
    }

    if (confirm && quote != null) {
        val q = quote!!
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Color.White,
            title = { Text(L("Add ${money(symbol, q.dbl("amount"))}?"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${L("You will pay")}: ≈ ${usdText(q.dbl("usd"))} ${L(if (card) "by card" else "in crypto")}", color = Ink, fontSize = 14.sp)
                    Text(L(if (card) "You will open the secure payment page and pay with your Visa or Mastercard. Your card details are never shared with MudaPay. Your balance updates automatically after confirmation." else if (inApp) "Next you get a wallet address and the exact amount to send, right here in the app. Your balance updates automatically after confirmation." else "You will open the secure payment page, choose your coin (USDT, BTC, ETH…) and send the exact amount shown. Your balance updates automatically after confirmation."), color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false; busy = true
                    scope.launch {
                        val body = JSONObject().put("amount", q.dbl("amount").toLong())
                        if (inApp) body.put("pay_currency", coin)
                        val r = Api.call("POST", createPath, body, idem = idem)
                        busy = false
                        if (r.ok) {
                            idem = newIdemKey()
                            val url = r.data.str("invoice_url")
                            if (r.data.str("pay_address").isNotEmpty()) { Ui.selectedCryptoDeposit = r.data; amount = ""; onChanged(); nav("CryptoPay") }
                            else {
                                if (url.isNotEmpty()) { watching = r.data; openInvoice(ctx, url) }
                                amount = ""; onChanged()
                            }
                        } else {
                            if (r.http != 0) idem = newIdemKey()
                            Ui.notify("Deposit", r.message)
                        }
                    }
                }) { Text(L(if (card) "Pay by card" else if (inApp) "Continue" else "Pay with crypto"), color = GreenDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }

    OpenDeposits(rules.list("open"), symbol, nav)
}

@Composable
private fun QuoteLine(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = if (strong) GreenDark else Ink, fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold, fontSize = if (strong) 14.sp else 12.sp)
    }
}

@Composable
private fun DepositHistory(refreshKey: Int, symbol: String, card: Boolean = false) {
    var receipt by remember { mutableStateOf<ReceiptData?>(null) }
    receipt?.let { ReceiptDialog(it) { receipt = null } }
    Remote(refreshKey = refreshKey, call = { Api.call("GET", if (card) "/payfonte/card/deposits" else "/deposits") }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) EmptyState(L("No deposits yet"), MudaIcons.Receipt)
        items.forEach { t ->
            val ok = t.bool("credited")
            val st = t.str("status")
            val tone = when { ok -> GreenDark; st in setOf("failed", "expired", "refunded") -> Danger; else -> Blue }
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).then(if (ok) Modifier.clickable { receipt = ReceiptBuilder.deposit(t, symbol, null) } else Modifier).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
