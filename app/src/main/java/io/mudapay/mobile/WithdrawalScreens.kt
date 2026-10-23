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

private fun buildSteps(w: JSONObject): List<Step> {
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
        if (acct.isNotEmpty()) appendLine("Account: •••• ${acct.takeLast(4)}")
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (w == null) {
            EmptyState("Nothing to show", Icons.Default.SouthWest, "Open a withdrawal from the Withdraw tab.")
            return@Column
        }
        val st = w.str("status").lowercase()
        val tone = when (st) { "paid", "completed" -> GreenDark; "rejected", "failed", "cancelled" -> Danger; else -> Blue }
        Column(Modifier.fillMaxWidth().background(Gradient, RoundedCornerShape(22.dp)).padding(20.dp)) {
            Text("WITHDRAWAL", color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(money(symbol, w.dbl("amount")), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("${w.str("method")} • ${w.str("created_at").take(10)}", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth().card().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Status", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Pill(st.replaceFirstChar { it.uppercase() }, tone)
            }
            Spacer(Modifier.height(14.dp))
            val steps = buildSteps(w)
            steps.forEachIndexed { i, s -> TimelineRow(s, last = i == steps.lastIndex) }
        }
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Receipt", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
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
            Text(s.title, color = if (s.state == StepState.Todo) Slate else Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            if (s.sub.isNotEmpty()) Text(s.sub, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}
