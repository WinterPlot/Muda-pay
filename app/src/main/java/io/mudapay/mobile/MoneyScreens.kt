package io.mudapay.mobile

import androidx.compose.ui.draw.clip
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

/* ------------------------------------------------------------------ withdraw */
@Composable
fun WithdrawScreen(nav: (String) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val symbol = Session.user?.str("symbol") ?: ""
    Column(Modifier.fillMaxSize()) {
        TabHeader(L("Withdraw"), L("Request a payout to your account"))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            BalanceHero(softCase(L("AVAILABLE BALANCE")), money(symbol, Session.balance), value = Session.balance, symbol = symbol)
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
        InfoBanner("You have a pending withdrawal of ${money(symbol, pending.dbl("amount"))} via ${pending.str("method")}. You can request another one after it is processed.", Blue, MudaIcons.HourglassTop)
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
                InfoBanner(L("You get a 6-digit payout code. Give it to the agent ONLY when you hold your cash. Your money is held safely until then, and is refunded if the agent does not answer."), Blue, MudaIcons.Lock)
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
                    else -> AppLock.requireForPayment(ctx.findActivity(), "Confirm withdrawal", "Confirm it's you to request this payout") { if (pinRequired) askPin = true else submit(amt, "") }
                }
            }, loading = busy, enabled = method.isNotEmpty())
        }
    }

    Text(L("Your withdrawals"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
    Remote(refreshKey = reloadKey, call = { Api.call("GET", "/withdrawals") }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) EmptyState("No withdrawals yet", MudaIcons.SouthWest)
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
private val IN_BG: Color get() = Tint(0xFFECFDF5)       // money in: soft green
private val OUT_BG: Color get() = Soft                   // money out: neutral grey, never alarming
private val OUT_FG: Color get() = if (MudaTheme.dark) Color(0xFF9AA8BF) else Color(0xFF475569)
private val KINDS = mapOf(
    "survey" to Kind("Task reward", MudaIcons.TaskAlt, IN_BG, GreenDark),
    "referral" to Kind("Referral bonus", MudaIcons.Groups, IN_BG, GreenDark),
    "admin_credit" to Kind("Credit", MudaIcons.AddCircle, IN_BG, GreenDark),
    "withdrawal" to Kind("Withdrawal", MudaIcons.SouthWest, OUT_BG, OUT_FG),
    "transfer_out" to Kind("Money sent", MudaIcons.NorthEast, OUT_BG, OUT_FG),
    "transfer_in" to Kind("Money received", MudaIcons.SouthWest, IN_BG, GreenDark),
    "deposit" to Kind("Crypto deposit", MudaIcons.CurrencyBitcoin, IN_BG, GreenDark),
    "agent_payout" to Kind("Agent cash-out refund", MudaIcons.Storefront, IN_BG, GreenDark),
    "agent_commission" to Kind("Agent commission", MudaIcons.Storefront, IN_BG, GreenDark),
    "video" to Kind("Video reward", MudaIcons.PlayCircle, IN_BG, GreenDark),
    "share_reward" to Kind("Share reward", MudaIcons.Share, IN_BG, GreenDark),
    "leaderboard_prize" to Kind("Leaderboard prize", MudaIcons.EmojiEvents, IN_BG, GreenDark),
    "daily_bonus" to Kind("Login bonus", MudaIcons.CalendarMonth, IN_BG, GreenDark),
    "trade_buy" to Kind("Coins bought", MudaIcons.CandlestickChart, OUT_BG, OUT_FG),
    "trade_sell" to Kind("Coins sold", MudaIcons.CandlestickChart, IN_BG, GreenDark),
    "trade_hold" to Kind("Money held for order", MudaIcons.CandlestickChart, OUT_BG, OUT_FG),
    "trade_release" to Kind("Order money released", MudaIcons.CandlestickChart, IN_BG, GreenDark)
)

/** Label of a transaction type (also used by the exported statement). */
fun txTypeLabel(type: String): String = KINDS[type]?.label ?: "Transaction"

private val TYPE_ORDER = listOf("survey", "video", "referral", "share_reward", "daily_bonus", "leaderboard_prize", "admin_credit",
    "transfer_in", "transfer_out", "deposit", "withdrawal", "agent_payout", "agent_commission", "trade_buy", "trade_sell", "trade_hold", "trade_release")

private fun pickedDate(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilter(label: String, value: String, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Text(if (value.isEmpty()) L(label) else value, color = if (value.isEmpty()) Muted else Ink, fontSize = 13.sp, maxLines = 1)
        }
        if (value.isNotEmpty()) IconButton(onClick = { onValue("") }) { Icon(MudaIcons.Close, L("Clear"), tint = Muted) }
    }
    if (open) {
        val st = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { onValue(pickedDate(it)) }; open = false }) { Text(L("OK"), color = GreenDark, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(L("Cancel"), color = Muted) } }
        ) { DatePicker(state = st) }
    }
}

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var type by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var minAmt by remember { mutableStateOf("") }
    var maxAmt by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<JSONObject>() }
    var cursor by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var errOffline by remember { mutableStateOf(false) }
    var cachedAt by remember { mutableStateOf(0L) }
    var totals by remember { mutableStateOf(JSONObject()) }
    var symbol by remember { mutableStateOf(Session.user?.str("symbol") ?: "") }
    val latest = remember { intArrayOf(0) }   // answers of an older search must never replace a newer one

    val filters = TxFilters(filter, type, search, from, to, minAmt, maxAmt)
    var receipt by remember { mutableStateOf<ReceiptData?>(null) }
    receipt?.let { ReceiptDialog(it) { receipt = null } }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    selected?.let { t -> TxDetailSheet(t, symbol, onReceipt = { receipt = it }) { selected = null } }

    fun loadPage(reset: Boolean) {
        val mine = ++latest[0]
        val f = filters
        scope.launch {
            loading = true; error = null
            val q = f.toQuery()
            val c = cursor
            if (!reset && c != null) q["cursor"] = c.toString()
            val r = Api.call("GET", "/transactions", query = q)
            if (mine != latest[0]) return@launch
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
    LaunchedEffect(filter, type, search, from, to, minAmt, maxAmt, Ui.refreshTick) {
        if (search.isNotEmpty() || minAmt.isNotEmpty() || maxAmt.isNotEmpty()) kotlinx.coroutines.delay(350)   // wait while typing
        cursor = null; loadPage(true)
    }
    val up = Offline.networkUp
    LaunchedEffect(up) { if (up && (cachedAt > 0L || errOffline)) { cursor = null; loadPage(true) } }

    fun clearFilters() { type = ""; search = ""; from = ""; to = ""; minAmt = ""; maxAmt = "" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TabHeader(L("Activity"), L("Your rewards, transfers and withdrawals.")) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).card().tappable(CardShape) { filter = "earnings"; type = "" }.padding(14.dp)) { Text(softCase(L("TOTAL EARNED")), color = Slate, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(money(symbol, totals.dbl("earned")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Column(Modifier.weight(1f).card().tappable(CardShape) { filter = "withdrawals"; type = "" }.padding(14.dp)) { Text(softCase(L("WITHDRAWN")), color = Slate, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(money(symbol, totals.dbl("withdrawn")), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
        item {
            InputField(L("Search transactions"), search, { search = it }, trailing = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(MudaIcons.Close, L("Clear"), tint = Muted) }
                else Icon(MudaIcons.Search, null, tint = Muted)
            })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "earnings" to "Earnings", "transfers" to "Transfers", "withdrawals" to "Withdrawals").forEach { (k, label) ->
                    FilterChip(selected = filter == k && type.isEmpty(), onClick = { filter = k; type = "" }, label = { Text(label, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.White))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showFilters = !showFilters }, modifier = Modifier.weight(1f)) {
                    Icon(MudaIcons.FilterList, null, tint = GreenDark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(L("Filters") + if (filters.active) " •" else "", color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                OutlinedButton(onClick = { showExport = true }, enabled = !exporting, modifier = Modifier.weight(1f)) {
                    Icon(MudaIcons.IosShare, null, tint = GreenDark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (exporting) "…" else L("Export"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        if (showFilters) item {
            Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(L("Type"), type, listOf("" to L("All types")) + TYPE_ORDER.map { it to L(txTypeLabel(it)) }) { type = it; if (it.isNotEmpty()) filter = "all" }
                Text(L("Date range"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateFilter("From", from, Modifier.weight(1f)) { from = it }
                    DateFilter("To", to, Modifier.weight(1f)) { to = it }
                }
                Text(L("Amount"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputField(L("Min"), minAmt, { minAmt = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(12) }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                    InputField(L("Max"), maxAmt, { maxAmt = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(12) }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                }
                if (filters.active) TextButton(onClick = { clearFilters() }) { Text(L("Clear all filters"), color = Danger, fontWeight = FontWeight.Bold) }
            }
        }
        if (cachedAt > 0L) item { StaleNote(cachedAt) }
        error?.let { e -> item { ErrorBox(e, { loadPage(true) }, errOffline) } }
        if (items.isEmpty() && !loading && error == null) item {
            EmptyState(L(if (filters.active || filter != "all") "No transactions match these filters" else "No transactions yet"), MudaIcons.ReceiptLong)
        }
        // grouped by day, newest first (the server already sends them newest first)
        val days = items.groupBy { it.str("created_at").take(10) }
        days.forEach { (day, list) ->
            item(key = "day-$day") { TxDayHeader(day) }
            item(key = "grp-$day-${list.first().optInt("id")}") {
                Column(Modifier.fillMaxWidth().card(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))) {
                    list.forEachIndexed { i, t ->
                        TxRow(t, symbol) { selected = t }
                        if (i < list.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(1.dp).background(Hairline))
                    }
                }
            }
        }
        if (loading) item { LoadingBox() }
        else if (cursor != null) item { PrimaryButton("LOAD MORE", { loadPage(false) }, color = DarkFill) }
    }

    if (showExport) {
        fun run(pdf: Boolean) {
            showExport = false
            exporting = true
            val f = filters
            scope.launch {
                val msg = Statement.export(ctx, f, pdf)
                exporting = false
                if (msg != null) Ui.notify("Statement", msg)
            }
        }
        AlertDialog(
            onDismissRequest = { showExport = false },
            containerColor = Panel,
            title = { Text(L("Export statement"), fontWeight = FontWeight.Bold) },
            text = { Text(L(if (filters.active || filter != "all") "The statement includes only the transactions that match your current search and filters." else "The statement includes all your transactions."), color = Ink, fontSize = 13.sp) },
            confirmButton = {
                Row {
                    TextButton(onClick = { run(false) }) { Text("CSV", color = GreenDark, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { run(true) }) { Text("PDF", color = GreenDark, fontWeight = FontWeight.Bold) }
                }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

/* ------------------------------------------------------------------ activity list + detail sheet (grouped by day, icon rows, bottom sheet) */
private fun txLocale(): java.util.Locale = if (AppLanguage.current == "fr") java.util.Locale.FRENCH else java.util.Locale.ENGLISH
private fun txIsOut(type: String) = type == "withdrawal" || type == "transfer_out"
private fun txKind(type: String) = KINDS[type] ?: Kind("Transaction", MudaIcons.ReceiptLong, Page, Muted)

/** "Today", "Yesterday" or "Sep 16, 2026". */
private fun txDayLabel(day: String): String {
    return try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val d = f.parse(day) ?: return day
        val today = f.format(java.util.Date())
        val yest = f.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
        when (day) { today -> L("Today"); yest -> L("Yesterday"); else -> java.text.SimpleDateFormat("MMM d, yyyy", txLocale()).format(d) }
    } catch (e: Exception) { day }
}

/** "16 Sep 2026, 23:33" */
private fun txFullDate(createdAt: String): String {
    return try {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(createdAt.take(16)) ?: return createdAt
        java.text.SimpleDateFormat("d MMM yyyy, HH:mm", txLocale()).format(d)
    } catch (e: Exception) { createdAt }
}

@Composable
private fun TxDayHeader(day: String) {
    Text(txDayLabel(day), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun TxRow(t: JSONObject, symbol: String, onClick: () -> Unit) {
    val type = t.str("type")
    val k = txKind(type)
    val out = txIsOut(type)
    val time = t.str("created_at").let { if (it.length >= 16) it.substring(11, 16) else "" }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(k.bg, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Icon(k.icon, null, tint = k.fg, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(L(k.label), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            val sub = t.str("description")
            if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text((if (out) "-" else "+") + money(symbol, t.dbl("amount")), color = if (out) Ink else GreenDark, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            if (time.isNotEmpty()) Text(time, color = Slate, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Bottom sheet with everything about one transaction (same look as the rest of the app: white sheet, green accents). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TxDetailSheet(t: JSONObject, symbol: String, onReceipt: (ReceiptData) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun close() { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    val type = t.str("type")
    val k = txKind(type)
    val out = txIsOut(type)
    val tone = if (out) Ink else GreenDark
    val rc = ReceiptBuilder.transaction(t, symbol)
    val ref = "#" + t.optInt("id")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Panel, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Text(L(k.label), color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).size(40.dp).background(Soft, androidx.compose.foundation.shape.CircleShape).clickable { close() }, contentAlignment = Alignment.Center) {
                    Icon(MudaIcons.Close, L("Close"), tint = Ink, modifier = Modifier.size(20.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).background(k.bg, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Icon(k.icon, null, tint = k.fg, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text((if (out) "-" else "+") + money(symbol, t.dbl("amount")), color = tone, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    Pill(L("Completed"), GreenDark)
                }
            }
            Column(Modifier.fillMaxWidth()) {
                TxSheetRow(L("Date"), txFullDate(t.str("created_at")))
                TxSheetRow(L("Type"), L(k.label))
                if (t.str("description").isNotBlank()) TxSheetRow(L("Details"), t.str("description"))
                TxSheetRow(L("Reference"), ref, copy = { copyToClipboard(ctx, "Reference", ref); Ui.notify("Copied", "Reference copied.") })
            }
            if (rc != null) PrimaryButton(L("View receipt"), { onReceipt(rc); close() }, icon = MudaIcons.ReceiptLong)
        }
    }
}

@Composable
private fun TxSheetRow(label: String, value: String, copy: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(0.38f))
            Row(Modifier.weight(0.62f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
                Text(value, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f, fill = false))
                if (copy != null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(MudaIcons.ContentCopy, L("Copy"), tint = Muted, modifier = Modifier.size(18.dp).clickable { copy() })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
    }
}
