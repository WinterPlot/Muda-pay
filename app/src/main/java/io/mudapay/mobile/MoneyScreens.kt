package io.mudapay.mobile

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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploading = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readImageForUpload(ctx, uri) }
                if (bytes == null) Ui.notify("Payment proof", "That image could not be read. Choose a JPG, PNG or WEBP screenshot.")
                else {
                    val r = Api.upload("/payments/proofs", Upload("proof", "proof.jpg", "image/jpeg", bytes))
                    if (r.ok) { Ui.notify("Proof received", r.data.str("message", "Your proof is waiting for admin review.")); reload++ }
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
                TextButton(onClick = { scope.launch { Api.call("POST", "/auth/logout"); Session.signOut() } }) { Text("Log out", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(16.dp))
            Text("Activate your account", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("Pay the one-time activation fee to unlock tasks, videos and withdrawals.", color = Color.White.copy(alpha = .9f), fontSize = 13.sp, lineHeight = 19.sp)
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

                Column(Modifier.fillMaxWidth().background(Gradient, RoundedCornerShape(22.dp)).padding(20.dp)) {
                    Text("ACTIVATION FEE", color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(d.str("amount_text"), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text("about ${d.str("amount_usd")} USD", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                }

                val payee = d.optJSONObject("payee") ?: JSONObject()
                Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pay with ${payee.str("method")}", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text("Send the activation fee to:", color = Muted, fontSize = 12.sp)
                    CopyLine("Number", payee.str("number"))
                    CopyLine("Name", payee.str("name"))
                }
                Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Or pay with crypto", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    d.list("cryptos").forEach { c -> CopyLine(c.str("coin"), c.str("address"), mono = true) }
                }
                InfoBanner("After paying, upload a screenshot of your payment. Only send money to the details shown here.")
                PrimaryButton(if (pending) "REPLACE PAYMENT PROOF" else "UPLOAD PAYMENT PROOF", { picker.launch("image/*") }, loading = uploading, icon = Icons.Default.UploadFile)
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
            Text(value, color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = if (mono) 11.sp else 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
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
        GradientHeader(bottomPadding = 30.dp) {
            Text("AVAILABLE BALANCE", color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(money(symbol, Session.balance), color = Gold, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text("Request a payout to your account", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Remote(refreshKey = reload, call = { Api.call("GET", "/withdrawals/rules") }) { rules, _ ->
                WithdrawForm(rules, reload, { w -> Ui.selectedWithdrawal = w; nav("WithdrawalDetail") }) { reload++ }
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
    val methods = rules.strings("methods")
    val need = rules.int("referrals_required")
    val have = rules.int("referrals_active")
    val pending = rules.optJSONObject("pending")
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(methods.firstOrNull() ?: "") }
    var account by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf(newIdemKey()) }

    if (pending != null) {
        InfoBanner("You have a pending withdrawal of ${money(symbol, pending.dbl("amount"))} via ${pending.str("method")}. You can request another one after it is processed.", Blue, Icons.Default.HourglassTop)
    } else if (have < need) {
        Column(Modifier.fillMaxWidth().card().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Lock, null, tint = Gold, modifier = Modifier.size(38.dp))
            Text("Withdrawals unlock with $need active referrals", color = Dark, fontWeight = FontWeight.Black, fontSize = 16.sp)
            LinearProgressIndicator(progress = { (have.toFloat() / need).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Green, trackColor = Line)
            Text("$have of $need active referrals", color = Muted, fontSize = 12.sp)
            PrimaryButton("SHARE INVITE LINK", { shareText(ctx, "Join me on MudaPay: ${Session.user?.str("referral_link")}") }, icon = Icons.Default.Share)
        }
    } else {
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Payout details", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            InputField("Amount ($symbol)", amount, { amount = it.filter { c -> c.isDigit() } }, keyboard = KeyboardType.Number)
            Text("Minimum: ${money(symbol, min.toDouble())}", color = Muted, fontSize = 12.sp)
            Dropdown("Payment method", method, methods.map { it to it }) { method = it }
            InputField("Phone number / account", account, { account = it })
            PrimaryButton("REQUEST WITHDRAWAL", {
                val amt = amount.toLongOrNull() ?: 0L
                when {
                    amt < min -> Ui.notify("Withdraw", "Minimum withdrawal is ${money(symbol, min.toDouble())}.")
                    account.isBlank() -> Ui.notify("Withdraw", "Please enter your payment account or phone number.")
                    else -> AppLock.requireForWithdrawal(ctx.findActivity()) {
                        busy = true
                        scope.launch {
                            val body = JSONObject().put("amount", amt).put("method", method).put("account_number", account.trim())
                            val r = Api.call("POST", "/withdrawals", body, idem = idem)
                            busy = false
                            if (r.ok) {
                                idem = newIdemKey()
                                Session.balance = r.data.dbl("balance")
                                Ui.notify("Withdrawal requested", "Your request for ${money(symbol, r.data.dbl("amount"))} is pending review.")
                                amount = ""; account = ""
                                onSubmitted()
                            } else {
                                if (r.http != 0) idem = newIdemKey()
                                Ui.notify("Withdraw", r.message)
                            }
                        }
                    }
                }
            }, loading = busy, enabled = method.isNotEmpty())
        }
    }

    Text("Your withdrawals", color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
    Remote(refreshKey = reloadKey, call = { Api.call("GET", "/withdrawals") }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) EmptyState("No withdrawals yet", Icons.Default.SouthWest)
        items.forEach { w ->
            val st = w.str("status")
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).clickable { onOpen(w) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(money(symbol, w.dbl("amount")), color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("${w.str("method")} • ${w.str("created_at").take(10)}", color = Muted, fontSize = 12.sp)
                }
                Pill(st.replaceFirstChar { it.uppercase() }, when (st) { "approved", "paid", "completed" -> GreenDark; "rejected" -> Danger; else -> Blue })
            }
        }
    }
}

/* ------------------------------------------------------------------ transaction history */
private class Kind(val label: String, val icon: ImageVector, val bg: Color, val fg: Color)
private val KINDS = mapOf(
    "survey" to Kind("Task reward", Icons.Default.TaskAlt, Color(0xFFF0FFFB), Green),
    "referral" to Kind("Referral bonus", Icons.Default.Groups, Color(0xFFFFFBEB), Color(0xFFF59E0B)),
    "admin_credit" to Kind("Credit", Icons.Default.AddCircle, Color(0xFFEFF6FF), Color(0xFF2563EB)),
    "withdrawal" to Kind("Withdrawal", Icons.Default.SouthWest, Color(0xFFFEF2F2), Danger),
    "video" to Kind("Video reward", Icons.Default.PlayCircle, Color(0xFFF5F3FF), Color(0xFF7C3AED)),
    "share_reward" to Kind("Share reward", Icons.Default.Share, Color(0xFFFDF2F8), Color(0xFFDB2777)),
    "leaderboard_prize" to Kind("Leaderboard prize", Icons.Default.EmojiEvents, Color(0xFFFFFBEB), Color(0xFFD97706)),
    "daily_bonus" to Kind("Daily bonus", Icons.Default.LocalFireDepartment, Color(0xFFECFEFF), Color(0xFF0891B2))
)

@Composable
fun HistoryScreen() {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    val items = remember { mutableStateListOf<JSONObject>() }
    var cursor by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
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
            } else error = r.message
            loading = false
        }
    }
    LaunchedEffect(filter) { cursor = null; loadPage(true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageIntro("Transaction history", "Your rewards and withdrawals.", Icons.AutoMirrored.Filled.ReceiptLong) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).card().padding(14.dp)) { Text("TOTAL EARNED", color = Slate, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Text(money(symbol, totals.dbl("earned")), color = GreenDark, fontWeight = FontWeight.Black, fontSize = 16.sp) }
                Column(Modifier.weight(1f).card().padding(14.dp)) { Text("WITHDRAWN", color = Slate, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Text(money(symbol, totals.dbl("withdrawn")), color = Danger, fontWeight = FontWeight.Black, fontSize = 16.sp) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "earnings" to "Earnings", "withdrawals" to "Withdrawals").forEach { (k, label) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.White))
                }
            }
        }
        error?.let { e -> item { ErrorBox(e) { loadPage(true) } } }
        if (items.isEmpty() && !loading && error == null) item { EmptyState("No transactions yet", Icons.AutoMirrored.Filled.ReceiptLong) }
        items(items) { t ->
            val k = KINDS[t.str("type")] ?: Kind("Transaction", Icons.AutoMirrored.Filled.ReceiptLong, Page, Muted)
            val isOut = t.str("type") == "withdrawal"
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(k.bg, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(k.icon, null, tint = k.fg) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.str("description").ifEmpty { k.label }, color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 2)
                    Text("${k.label} • ${t.str("created_at").take(16)}", color = Slate, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text((if (isOut) "-" else "+") + money(symbol, t.dbl("amount")), color = if (isOut) Danger else GreenDark, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }
        if (loading) item { LoadingBox() }
        else if (cursor != null) item { PrimaryButton("LOAD MORE", { loadPage(false) }, color = Dark) }
    }
}
