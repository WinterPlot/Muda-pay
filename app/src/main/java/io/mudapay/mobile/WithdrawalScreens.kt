package io.mudapay.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private enum class StepState { Done, Current, Todo, Failed }
private class Step(val title: String, val sub: String, val state: StepState)

private fun isAgentWd(w: JSONObject) = w.str("agent_code").isNotEmpty()

private fun buildAgentSteps(w: JSONObject): List<Step> {
    val st = w.str("status").lowercase()
    val steps = mutableListOf(Step("Requested", w.str("created_at").take(16), StepState.Done))
    when (st) {
        "completed", "paid" -> {
            steps += Step("Agent accepted", w.str("reviewed_at").take(16).ifEmpty { "Accepted" }, StepState.Done)
            steps += Step("Cash received", w.str("paid_at").take(16), StepState.Done)
        }
        "rejected" -> steps += Step("Closed — money refunded", w.str("note").ifEmpty { "This request was not completed." }, StepState.Failed)
        "approved" -> {
            steps += Step("Agent accepted", w.str("reviewed_at").take(16), StepState.Done)
            steps += Step("Cash received", "Collect your cash, then give the agent your payout code", StepState.Current)
        }
        else -> {
            steps += Step("Agent accepted", "Waiting for the agent to answer", StepState.Current)
            steps += Step("Cash received", "Give the agent your payout code when you hold the cash", StepState.Todo)
        }
    }
    return steps
}

private fun buildSteps(w: JSONObject): List<Step> {
    if (isAgentWd(w)) return buildAgentSteps(w)
    val st = w.str("status").lowercase()
    val created = w.str("created_at").take(16)
    val reviewed = w.str("reviewed_at").take(16)
    val paid = w.str("paid_at").take(16)
    val steps = mutableListOf(Step("Requested", created.ifEmpty { "Your request was received" }, StepState.Done))
    when {
        st in setOf("rejected", "failed", "cancelled") -> {
            steps += Step("Reviewed", reviewed, StepState.Done)
            steps += Step("Not approved", w.str("note").ifEmpty { "This request was not approved." }, StepState.Failed)
        }
        st in setOf("paid", "completed") -> {
            steps += Step("Reviewed", reviewed.ifEmpty { "Approved" }, StepState.Done)
            steps += Step("Paid", paid.ifEmpty { "Your payout was sent" }, StepState.Done)
        }
        st in setOf("approved", "processing") -> {
            steps += Step("Reviewed", reviewed.ifEmpty { "Approved" }, StepState.Done)
            steps += Step("Paid", "Approved — your payout is being sent", StepState.Current)
        }
        else -> {
            steps += Step("Reviewed", "Our team is checking your request", StepState.Current)
            steps += Step("Paid", "You'll get a notification when it is paid", StepState.Todo)
        }
    }
    return steps
}

private fun receiptText(w: JSONObject, symbol: String): String {
    val paid = w.str("status").lowercase() in setOf("paid", "completed")
    val ref = w.str("reference").ifEmpty { w.str("id") }
    val acct = w.str("account_number")
    return buildString {
        appendLine(if (paid) "MudaPay withdrawal receipt" else "MudaPay withdrawal summary")
        if (ref.isNotEmpty()) appendLine("Reference: $ref")
        appendLine("Amount: ${money(symbol, w.dbl("amount"))}")
        appendLine("Method: ${w.str("method")}")
        if (isAgentWd(w)) {
            appendLine("Agent: ${w.str("agent_name")} (${w.str("agent_code")})")
            if (w.has("cash_amount") && !w.isNull("cash_amount")) appendLine("Cash: ${money(symbol, w.dbl("cash_amount"))}")
        }
        if (acct.isNotEmpty() && !isAgentWd(w)) appendLine("Account: •••• ${acct.takeLast(4)}")
        if (w.str("crypto_currency").isNotEmpty()) appendLine("Value: ${"%.2f".format(w.dbl("amount_usd"))} USD in ${w.str("crypto_currency").uppercase()}")
        appendLine("Status: ${w.str("status").replaceFirstChar { it.uppercase() }}")
        appendLine("Requested: ${w.str("created_at").take(16)}")
        if (paid && w.str("paid_at").isNotEmpty()) appendLine("Paid: ${w.str("paid_at").take(16)}")
    }.trim()
}

@Composable
fun WithdrawalDetailScreen() {
    val ctx = LocalContext.current
    val w = Ui.selectedWithdrawal
    val symbol = Session.user?.str("symbol") ?: ""
    val scope = rememberCoroutineScope()
    var askCancel by remember { mutableStateOf(false) }
    var cancelBusy by remember { mutableStateOf(false) }
    // an open cash-out through an agent changes on the agent's side: look for news every 8 s until it is closed
    val wid = w?.str("id") ?: ""
    LaunchedEffect(wid) {
        while (wid.isNotEmpty()) {
            val cur = Ui.selectedWithdrawal ?: break
            if (!isAgentWd(cur) || cur.str("status") !in setOf("pending", "approved")) break
            delay(8000)
            val r = Api.call("GET", "/withdrawals")
            if (r.ok) {
                val fresh = r.data.list("items").firstOrNull { it.str("id") == wid } ?: continue
                val was = Ui.selectedWithdrawal?.str("status")
                Ui.selectedWithdrawal = fresh
                if (fresh.str("status") != was && fresh.str("status") !in setOf("pending", "approved")) {
                    val me = Api.call("GET", "/me")
                    if (me.ok && !me.cached) Session.updateUser(me.data)
                    Ui.refreshTick++
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (w == null) {
            EmptyState("Nothing to show", Icons.Default.SouthWest, "Open a withdrawal from the Withdraw tab.")
            return@Column
        }
        val st = w.str("status").lowercase()
        val tone = when (st) { "paid", "completed" -> GreenDark; "rejected", "failed", "cancelled" -> Danger; else -> Blue }
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
            Text(softCase(L("WITHDRAWAL")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
            Text(money(symbol, w.dbl("amount")), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(L("${w.str("method")}${if (isAgentWd(w)) " ${w.str("agent_code")}" else ""} • ${w.str("created_at").take(10)}"), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        val open = st == "pending" || st == "approved"
        if (isAgentWd(w) && open && w.str("confirm_code").isNotEmpty()) {
            Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(softCase(L("YOUR PAYOUT CODE")), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                Text(w.str("confirm_code").chunked(3).joinToString(" "), color = Dark, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
                if (!w.isNull("cash_amount")) Text(L("You should receive") + " " + money(symbol, w.dbl("cash_amount")) + " " + L("in cash"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(L("Give this code to the agent ONLY after he has handed you the cash. Never share it before, and never send it by message."), color = Muted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        if (isAgentWd(w)) {
            Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(L(if (w.str("method") == "Bureau") "Bureau" else "Agent"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${w.str("agent_name")} • ${w.str("agent_code")}", color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val place = listOf(w.str("agent_city"), w.str("agent_address")).filter { it.isNotEmpty() }.joinToString(" — ")
                if (place.isNotEmpty()) Text(place, color = Muted, fontSize = 13.sp)
                if (w.str("agent_hours").isNotEmpty()) Text(w.str("agent_hours"), color = Muted, fontSize = 12.sp)
                if (open && w.str("agent_phone").isNotEmpty()) {
                    PrimaryButton(softCase(L("CALL")) + " " + w.str("agent_phone"), { dialPhone(ctx, w.str("agent_phone")) }, color = Blue, icon = Icons.Default.Phone)
                }
                if (st == "pending") {
                    PrimaryButton(softCase(L("CANCEL REQUEST")), { askCancel = true }, color = Danger, loading = cancelBusy, icon = Icons.Default.Close)
                } else if (st == "approved") {
                    Text(L("The agent accepted. To stop this request ask him to decline it, or contact support."), color = Muted, fontSize = 12.sp)
                }
            }
        }
        if (askCancel) AlertDialog(
            onDismissRequest = { askCancel = false }, containerColor = Color.White,
            title = { Text(L("Cancel this request?"), fontWeight = FontWeight.Bold) },
            text = { Text(L("Your money goes back to your balance."), color = Ink, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = {
                askCancel = false; cancelBusy = true
                scope.launch {
                    val r = Api.call("POST", "/withdrawals/${w.str("id")}/cancel")
                    cancelBusy = false
                    if (r.ok) {
                        r.data.optJSONObject("withdrawal")?.let { Ui.selectedWithdrawal = it }
                        Session.balance = r.data.dbl("balance")
                        Ui.refreshTick++
                    } else Ui.notify("Cancel", r.message)
                }
            }) { Text(L("Cancel request"), color = Danger, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { askCancel = false }) { Text(L("Keep"), color = Muted) } }
        )
        Column(Modifier.fillMaxWidth().card().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(L("Status"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Pill(st.replaceFirstChar { it.uppercase() }, tone)
            }
            Spacer(Modifier.height(14.dp))
            val steps = buildSteps(w)
            steps.forEachIndexed { i, s -> TimelineRow(s, last = i == steps.lastIndex) }
        }
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L("Receipt"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(receiptText(w, symbol), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            PrimaryButton("SHARE RECEIPT", { shareText(ctx, receiptText(w, symbol)) }, icon = Icons.Default.Share)
            PrimaryButton("COPY", { copyToClipboard(ctx, "Receipt", receiptText(w, symbol)); Ui.notify("Copied", "Receipt copied.") }, color = Dark, icon = Icons.Default.ContentCopy)
        }
    }
}

@Composable
private fun TimelineRow(s: Step, last: Boolean) {
    val color = when (s.state) { StepState.Done -> GreenDark; StepState.Current -> Blue; StepState.Failed -> Danger; StepState.Todo -> Slate }
    val icon = when (s.state) { StepState.Done -> Icons.Default.Check; StepState.Current -> Icons.Default.HourglassTop; StepState.Failed -> Icons.Default.Close; StepState.Todo -> Icons.Default.MoreHoriz }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).background(color.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(Line))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(bottom = if (last) 0.dp else 16.dp)) {
            Text(s.title, color = if (s.state == StepState.Todo) Slate else Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (s.sub.isNotEmpty()) Text(s.sub, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}
