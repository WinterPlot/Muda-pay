package io.mudapay.mobile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/* ------------------------------------------------------------------ activation payment (shown to unpaid members) */
@Composable
fun ActivationScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var cryptoLoading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploading = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readImageForUpload(ctx, uri) }
                if (bytes == null) Ui.notify("Payment proof", "That image could not be read. Choose a JPG, PNG or WEBP screenshot.")
                else {
                    val r = Api.upload("/payments/proofs", Upload("proof", "proof.jpg", "image/jpeg", bytes))
                    if (r.ok) { Notify.paymentProofReceived(); Ui.notify("Proof received", r.data.str("message", "Your proof is waiting for admin review.")); reload++ }
                    else Ui.notify("Payment proof", r.message)
                }
                uploading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(bottomPadding = 30.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Brand(light = true)
                TextButton(onClick = { scope.launch { Api.call("POST", "/auth/logout"); Session.signOut() } }) { Text(L("Log out"), color = Color.White, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(16.dp))
            Text(L("Activate your account"), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(L("Pay the one-time activation fee to unlock tasks, videos and withdrawals."), color = Color.White.copy(alpha = .9f), fontSize = 13.sp, lineHeight = 19.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Remote(refreshKey = reload, call = { Api.call("GET", "/payments/instructions") }) { d, refresh ->
                LaunchedEffect(d.bool("paid")) {
                    if (d.bool("paid")) { val r = Api.call("GET", "/me"); if (r.ok) Session.updateUser(r.data) }
                }
                val pending = d.bool("payment_pending")
                val note = d.str("rejection_note")
                if (pending) InfoBanner("Your payment proof is waiting for admin review. You will be able to use the app as soon as it is approved.", Blue, Icons.Default.HourglassTop)
                else if (note.isNotEmpty()) InfoBanner("Your last proof was not accepted: $note. Please send a new one.", Danger, Icons.Default.Info)

                Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
                    Text(softCase(L("ACTIVATION FEE")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                    Text(d.str("amount_text"), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(L("about ${d.str("amount_usd")} USD"), color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                }

                val payee = d.optJSONObject("payee") ?: JSONObject()
                Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(L("Pay with ${payee.str("method")}"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(L("Send the activation fee to:"), color = Muted, fontSize = 12.sp)
                    CopyLine("Number", payee.str("number"))
                    CopyLine("Name", payee.str("name"))
                }
                val cryptoAuto = d.optBoolean("crypto_auto", false)
                val proofAllowed = d.optBoolean("proof_allowed", true)
                if (cryptoAuto) {
                    // Automatic crypto payment (NOWPayments): the account activates by itself once the payment is confirmed.
                    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(L("Or pay with crypto"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(L("BTC, ETH, USDT, SOL and more. Your account activates automatically after the payment is confirmed — no screenshot needed."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        PrimaryButton("PAY WITH CRYPTO", {
                            scope.launch {
                                cryptoLoading = true
                                val r = Api.call("POST", "/payments/crypto")
                                cryptoLoading = false
                                val url = r.data.str("invoice_url")
                                if (r.ok && url.isNotEmpty()) {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } else Ui.notify("Crypto payment", r.message)
                            }
                        }, loading = cryptoLoading, color = Dark, icon = Icons.Default.CurrencyBitcoin)
                    }
                    // While the member is paying in the browser, check every 8 s whether the webhook has activated the account.
                    LaunchedEffect(cryptoLoading) {
                        while (true) {
                            kotlinx.coroutines.delay(8000)
                            val st = Api.call("GET", "/payments/status")
                            if (st.ok && st.data.bool("paid")) { reload++; break }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(L("Or pay with crypto"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        d.list("cryptos").forEach { c -> CopyLine(c.str("coin"), c.str("address"), mono = true) }
                    }
                }
                if (proofAllowed) {
                    InfoBanner("After paying with mobile money, upload a screenshot of your payment. Only send money to the details shown here.")
                    PrimaryButton(if (pending) "REPLACE PAYMENT PROOF" else "UPLOAD PAYMENT PROOF", { picker.launch("image/*") }, loading = uploading, icon = Icons.Default.UploadFile)
                }
                PrimaryButton("CHECK STATUS", { refresh() }, color = Dark, icon = Icons.Default.Refresh)
            }
        }
    }
}

@Composable
private fun CopyLine(label: String, value: String, mono: Boolean = false) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().background(Page, RoundedCornerShape(12.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Dark, fontWeight = FontWeight.Bold, fontSize = if (mono) 11.sp else 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        }
        IconButton(onClick = { copyToClipboard(ctx, label, value); Ui.notify("Copied", "$label copied.") }) { Icon(Icons.Default.ContentCopy, "Copy", tint = GreenDark) }
    }
}

/* ------------------------------------------------------------------ withdraw */
@Composable
fun WithdrawScreen(nav: (String) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val symbol = Session.user?.str("symbol") ?: ""
    Column(Modifier.fillMaxSize()) {
        TabHeader(L("Withdraw"), L("Request a payout to your account"))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            BalanceHero(softCase(L("AVAILABLE BALANCE")), money(symbol, Session.balance))
            Remote(refreshKey = reload, call = { Api.call("GET", "/withdrawals/rules") }) { rules, _ ->
                WithdrawForm(rules, reload, { w -> Ui.selectedWithdrawal = w; nav("WithdrawalDetail") }) { reload++ }
                AgentEntryCard(rules, nav)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WithdrawForm(rules: JSONObject, reloadKey: Int, onOpen: (JSONObject) -> Unit, onSubmitted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol")
    val min = rules.int("min")
    val crypto = rules.optJSONObject("crypto")
    val coins = crypto?.list("coins") ?: emptyList()
    val cryptoOn = crypto != null && crypto.optBoolean("enabled", false) && coins.isNotEmpty()
    val methods = rules.strings("methods") + if (cryptoOn) listOf("Crypto") else emptyList()
    val localPerUsd = crypto?.dbl("local_per_usd") ?: 0.0
    val minUsd = crypto?.dbl("min_usd") ?: 0.0
    val need = rules.int("referrals_required")
    val have = rules.int("referrals_active")
    val pending = rules.optJSONObject("pending")
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(methods.firstOrNull() ?: "") }
    var account by remember { mutableStateOf("") }
    var coin by remember { mutableStateOf(coins.firstOrNull()?.str("code") ?: "") }
    var agentCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf(newIdemKey()) }
    val pinRequired = rules.bool("pin_required")
    var askPin by remember { mutableStateOf(false) }

    // Sends the request. [pin] is "" when the member has no transaction PIN; the server enforces it either way.
    fun submit(amt: Long, pin: String) {
        busy = true
        scope.launch {
            val viaAgent = method == "Agent" || method == "Bureau"
            val body = JSONObject().put("amount", amt).put("method", method).put("account_number", if (viaAgent) agentCode else account.trim())
            if (viaAgent) body.put("agent_code", agentCode)
            if (method == "Crypto") body.put("crypto_currency", coin)
            if (pin.isNotEmpty()) body.put("pin", pin)
            val r = Api.call("POST", "/withdrawals", body, idem = idem)
            busy = false
            if (r.ok && (method == "Agent" || method == "Bureau")) {
                idem = newIdemKey()
                Session.balance = r.data.dbl("balance")
                NotifySync.markWithdrawalOpen(ctx)
                amount = ""; agentCode = ""
                onSubmitted()
                onOpen(r.data)      // the detail screen shows the member's 6-digit payout code
            } else if (r.ok) {
                idem = newIdemKey()
                Session.balance = r.data.dbl("balance")
                val sentAmount = if (r.data.has("amount")) r.data.dbl("amount") else amt.toDouble()
                Notify.withdrawalRequested(sentAmount, method, account, Session.balance, if (r.data.has("amount_usd")) r.data.dbl("amount_usd") else null, if (method == "Crypto") coin else null)
                NotifySync.markWithdrawalOpen(ctx)
                NotifySync.kick(ctx)
                Ui.notify("You withdrew ${money(symbol, sentAmount)}", "Your ${money(symbol, sentAmount)} withdrawal via ${if (method == "Crypto") "crypto" else method} is pending review. You'll get a notification as soon as it is paid.")
                amount = ""; account = ""
                onSubmitted()
            } else {
                if (r.http != 0) idem = newIdemKey()
                Ui.notify("Withdraw", r.message)
            }
        }
    }

    if (askPin) PinPromptDialog("Confirm withdrawal", onDismiss = { askPin = false }) { pin -> askPin = false; submit(amount.toLongOrNull() ?: 0L, pin) }

    if (pending != null) {
        InfoBanner("You have a pending withdrawal of ${money(symbol, pending.dbl("amount"))} via ${pending.str("method")}. You can request another one after it is processed.", Blue, Icons.Default.HourglassTop)
    } else if (have < need) {
        Column(Modifier.fillMaxWidth().card().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Lock, null, tint = Gold, modifier = Modifier.size(38.dp))
            Text(L("Withdrawals unlock with $need active referrals"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            LinearProgressIndicator(progress = { (have.toFloat() / need).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Green, trackColor = Line)
            Text(L("$have of $need active referrals"), color = Muted, fontSize = 12.sp)
            PrimaryButton("SHARE INVITE LINK", { shareText(ctx, "Join me on MudaPay: ${Session.user?.str("referral_link")}") }, icon = Icons.Default.Share)
        }
    } else {
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L("Payout details"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            InputField("Amount ($symbol)", amount, { amount = it.filter { c -> c.isDigit() } }, keyboard = KeyboardType.Number)
            Text(L("Minimum: ${money(symbol, min.toDouble())}"), color = Muted, fontSize = 12.sp)
            Dropdown("Payment method", method, methods.map { it to (when (it) { "Crypto" -> "Crypto (USDT, BTC, ETH…)"; "Agent" -> L("MudaPay Agent (cash)"); "Bureau" -> L("MudaPay Bureau (cash)"); else -> it }) }) { method = it; agentCode = "" }
            val isCrypto = method == "Crypto"
            val viaAgent = method == "Agent" || method == "Bureau"
            if (isCrypto) {
                Dropdown("Cryptocurrency", coin, coins.map { it.str("code") to "${it.str("label")} — ${it.str("network")}" }) { coin = it }
                val usd = if (localPerUsd > 0) (amount.toLongOrNull() ?: 0L) / localPerUsd else 0.0
                Text("≈ ${"%.2f".format(usd)} USD • minimum ${"%.2f".format(minUsd)} USD", color = Muted, fontSize = 12.sp)
            }
            if (viaAgent) {
                val agentsInfo = rules.optJSONObject("agents")
                AgentPickerBlock(method == "Bureau", agentsInfo?.dbl("agent_fee_pct") ?: 0.0, agentCode, onKind = { method = it }) { agentCode = it }
                InfoBanner(L("You get a 6-digit payout code. Give it to the agent ONLY when you hold your cash. Your money is held safely until then, and is refunded if the agent does not answer."), Blue, Icons.Default.Lock)
            } else if (method == "Airtel Money" && Session.user?.str("country")?.lowercase() == "cd") {
                InputField(L("Airtel Money number"), account, { account = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
            } else InputField(if (isCrypto) "Wallet address" else "Phone number / account", account, { account = it })
            PrimaryButton("REQUEST WITHDRAWAL", {
                val amt = amount.toLongOrNull() ?: 0L
                when {
                    amt < min -> Ui.notify("Withdraw", "Minimum withdrawal is ${money(symbol, min.toDouble())}.")
                    viaAgent && agentCode.length < 6 -> Ui.notify("Withdraw", if (method == "Bureau") "Please choose a bureau." else "Enter the agent code and press CHECK.")
                    !viaAgent && account.isBlank() -> Ui.notify("Withdraw", if (method == "Crypto") "Please paste your wallet address." else "Please enter your payment account or phone number.")
                    method == "Crypto" && coin.isEmpty() -> Ui.notify("Withdraw", "Please choose a cryptocurrency.")
                    else -> AppLock.requireForWithdrawal(ctx.findActivity()) { if (pinRequired) askPin = true else submit(amt, "") }
                }
            }, loading = busy, enabled = method.isNotEmpty())
        }
    }

    Text(L("Your withdrawals"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
    Remote(refreshKey = reloadKey, call = { Api.call("GET", "/withdrawals") }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) EmptyState("No withdrawals yet", Icons.Default.SouthWest)
        items.forEach { w ->
            val st = w.str("status")
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).clickable { onOpen(w) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(money(symbol, w.dbl("amount")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(L("${w.str("method")} • ${w.str("created_at").take(10)}"), color = Muted, fontSize = 12.sp)
                }
                Pill(st.replaceFirstChar { it.uppercase() }, when (st) { "approved", "paid", "completed" -> GreenDark; "rejected" -> Danger; else -> Blue })
            }
        }
    }
}

/* ------------------------------------------------------------------ transaction history */
private class Kind(val label: String, val icon: ImageVector, val bg: Color, val fg: Color)
private val IN_BG = Color(0xFFECFDF5)       // money in: soft green
private val OUT_BG = Soft                   // money out: neutral grey, never alarming
private val OUT_FG = Color(0xFF475569)
private val KINDS = mapOf(
    "survey" to Kind("Task reward", Icons.Default.TaskAlt, IN_BG, GreenDark),
    "referral" to Kind("Referral bonus", Icons.Default.Groups, IN_BG, GreenDark),
    "admin_credit" to Kind("Credit", Icons.Default.AddCircle, IN_BG, GreenDark),
    "withdrawal" to Kind("Withdrawal", Icons.Default.SouthWest, OUT_BG, OUT_FG),
    "transfer_out" to Kind("Money sent", Icons.Default.NorthEast, OUT_BG, OUT_FG),
    "transfer_in" to Kind("Money received", Icons.Default.SouthWest, IN_BG, GreenDark),
    "deposit" to Kind("Crypto deposit", Icons.Default.CurrencyBitcoin, IN_BG, GreenDark),
    "agent_payout" to Kind("Agent cash-out refund", Icons.Default.Storefront, IN_BG, GreenDark),
    "agent_commission" to Kind("Agent commission", Icons.Default.Storefront, IN_BG, GreenDark),
    "video" to Kind("Video reward", Icons.Default.PlayCircle, IN_BG, GreenDark),
    "share_reward" to Kind("Share reward", Icons.Default.Share, IN_BG, GreenDark),
    "leaderboard_prize" to Kind("Leaderboard prize", Icons.Default.EmojiEvents, IN_BG, GreenDark),
    "daily_bonus" to Kind("Login bonus", Icons.Default.CalendarMonth, IN_BG, GreenDark)
)

@Composable
fun HistoryScreen() {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    val items = remember { mutableStateListOf<JSONObject>() }
    var cursor by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var errOffline by remember { mutableStateOf(false) }
    var cachedAt by remember { mutableStateOf(0L) }
    var totals by remember { mutableStateOf(JSONObject()) }
    var symbol by remember { mutableStateOf(Session.user?.str("symbol") ?: "") }

    fun loadPage(reset: Boolean) {
        scope.launch {
            loading = true; error = null
            val q = mutableMapOf("filter" to filter)
            val c = cursor
            if (!reset && c != null) q["cursor"] = c.toString()
            val r = Api.call("GET", "/transactions", query = q)
            if (r.ok) {
                if (reset) items.clear()
                items.addAll(r.data.list("items"))
                cursor = if (r.data.isNull("next_cursor")) null else r.data.optInt("next_cursor")
                totals = r.data.optJSONObject("totals") ?: JSONObject()
                symbol = r.data.str("symbol", symbol)
                cachedAt = if (r.cached) r.cachedAt else 0L
            } else { error = r.message; errOffline = r.code == "network" }
            loading = false
        }
    }
    LaunchedEffect(filter, Ui.refreshTick) { cursor = null; loadPage(true) }
    val up = Offline.networkUp
    LaunchedEffect(up) { if (up && (cachedAt > 0L || errOffline)) { cursor = null; loadPage(true) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TabHeader(L("Activity"), L("Your rewards, transfers and withdrawals.")) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).card().padding(14.dp)) { Text(softCase(L("TOTAL EARNED")), color = Slate, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(money(symbol, totals.dbl("earned")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Column(Modifier.weight(1f).card().padding(14.dp)) { Text(softCase(L("WITHDRAWN")), color = Slate, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(money(symbol, totals.dbl("withdrawn")), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "earnings" to "Earnings", "transfers" to "Transfers", "withdrawals" to "Withdrawals").forEach { (k, label) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.White))
                }
            }
        }
        if (cachedAt > 0L) item { StaleNote(cachedAt) }
        error?.let { e -> item { ErrorBox(e, { loadPage(true) }, errOffline) } }
        if (items.isEmpty() && !loading && error == null) item { EmptyState("No transactions yet", Icons.AutoMirrored.Filled.ReceiptLong) }
        items(items) { t ->
            val k = KINDS[t.str("type")] ?: Kind("Transaction", Icons.AutoMirrored.Filled.ReceiptLong, Page, Muted)
            val isOut = t.str("type") == "withdrawal" || t.str("type") == "transfer_out"
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(k.bg, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(k.icon, null, tint = k.fg) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.str("description").ifEmpty { k.label }, color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
                    Text(L("${k.label} • ${t.str("created_at").take(16)}"), color = Slate, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text((if (isOut) "-" else "+") + money(symbol, t.dbl("amount")), color = if (isOut) Danger else GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        if (loading) item { LoadingBox() }
        else if (cursor != null) item { PrimaryButton("LOAD MORE", { loadPage(false) }, color = Dark) }
    }
}
