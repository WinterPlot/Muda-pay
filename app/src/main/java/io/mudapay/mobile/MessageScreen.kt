package io.mudapay.mobile

import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * One notification, as the member reads it on the "Notification" screen.
 * It travels inside the tap intent of the system notification (so it also works when the app was closed)
 * and is also built from the rows of the in-app Notifications list.
 */
class InboxMsg(
    val type: String,
    val title: String,
    val body: String,
    val details: List<String>,
    /** "yyyy-MM-dd HH:mm" */
    val time: String,
    /** The screen the notification is about (Withdraw, Send, Tasks…). Null = none (list rows). */
    val route: String?
) {
    /** "Key: value" detail lines. A line is a pair only when the part before ": " is short, like a label. */
    fun keyValues(): List<Pair<String, String>> = details.mapNotNull { line ->
        val i = line.indexOf(": ")
        if (i in 1..28 && i + 2 < line.length) line.substring(0, i).trim() to line.substring(i + 2).trim() else null
    }

    /** Detail lines that are sentences (tips, warnings), not "Key: value". */
    fun notes(): List<String> = details.filter { line ->
        val i = line.indexOf(": ")
        !(i in 1..28 && i + 2 < line.length)
    }

    /** Puts this message into the intent behind a notification tap. [Push] sets the route to open it. */
    fun putInto(i: Intent): Intent = i
        .putExtra("route", ROUTE)
        .putExtra("msg_type", type).putExtra("msg_title", title).putExtra("msg_body", body)
        .putExtra("msg_details", details.joinToString("\n")).putExtra("msg_time", time)
        .putExtra("msg_target", route ?: "")

    companion object {
        const val ROUTE = "NotificationDetail"

        fun from(i: Intent?): InboxMsg? {
            if (i == null || i.getStringExtra("route") != ROUTE) return null
            val title = i.getStringExtra("msg_title") ?: return null
            return InboxMsg(
                i.getStringExtra("msg_type") ?: "info", title, i.getStringExtra("msg_body") ?: "",
                (i.getStringExtra("msg_details") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() },
                i.getStringExtra("msg_time") ?: "", i.getStringExtra("msg_target")?.takeIf { it.isNotEmpty() }
            )
        }
    }
}

private class Look(val icon: ImageVector, val tone: Color, val label: String, val good: Boolean)

/** Icon, colour and small caption for each kind of notification. Green = good news, red = something did not work, blue = information. */
private fun lookOf(type: String): Look = when {
    type == "withdrawal_paid" -> Look(MudaIcons.CheckCircle, GreenDark, "Withdrawal paid", true)
    type == "withdrawal_rejected" -> Look(MudaIcons.Cancel, Danger, "Withdrawal not approved", false)
    type.startsWith("withdrawal") -> Look(MudaIcons.HourglassTop, Blue, "Withdrawal", false)
    type == "payment_transfer_out" -> Look(MudaIcons.NorthEast, Blue, "Money sent", false)
    type == "payment_transfer_in" -> Look(MudaIcons.SouthWest, GreenDark, "Money received", true)
    type == "payment_deposit" -> Look(MudaIcons.AddCircle, GreenDark, "Deposit", true)
    type.startsWith("payment_deposit_") -> Look(MudaIcons.Receipt, Blue, "Deposit", false)
    type.startsWith("payment") -> Look(MudaIcons.Receipt, Blue, "Payment", false)
    type == "share_rejected" -> Look(MudaIcons.Cancel, Danger, "Share", false)
    type in setOf("task_reward", "daily_bonus", "daily_bonus_claimed", "share_reward", "video_reward", "like_reward", "credit", "sync_done") ->
        Look(MudaIcons.AddCircle, GreenDark, "Reward", true)
    type == "referral" -> Look(MudaIcons.Groups, GreenDark, "Referral", true)
    type == "email_verified" || type == "google_linked" -> Look(MudaIcons.CheckCircle, GreenDark, "Account", true)
    type == "password_changed" || type == "google_unlinked" -> Look(MudaIcons.Lock, Blue, "Security", false)
    type == "email_code_sent" -> Look(MudaIcons.Email, Blue, "Security", false)
    type == "ticket_sent" || type == "support_reply" -> Look(MudaIcons.SupportAgent, Blue, "Support", false)
    else -> Look(if (type == "account") MudaIcons.Info else MudaIcons.Notifications, Blue, "Notification", false)
}

private fun targetLabel(route: String): String = when (route) {
    "Withdraw" -> "Open withdrawals"
    "Send" -> "Open Send money"
    "Receive" -> "Open Receive money"
    "Requests" -> "View requests"
    "Deposit" -> "Open deposits"
    "Tasks" -> "Go to Tasks"
    "Videos" -> "Watch videos"
    "Share" -> "Open Share & earn"
    "Referrals" -> "View referrals"
    "Support" -> "Open Support"
    "Settings" -> "Open Settings"
    "Home" -> "Go to Home"
    else -> "Open"
}

private val HERO_KEYS = listOf("amount", "reward", "bonus", "added to your balance")

/**
 * What opens when the member taps a notification: the whole message, readable at once —
 * a big icon, the title, the amount, the full text, every detail line, and one button to the related screen.
 */
@Composable
fun NotificationDetailScreen(nav: (String) -> Unit) {
    val m = Ui.openMessage
    if (m == null) {
        Column(Modifier.fillMaxSize().padding(18.dp)) { EmptyState(L("Nothing to show"), MudaIcons.Notifications, L("Open a notification to read it here.")) }
        return
    }
    val look = lookOf(m.type)
    val symbolRows = m.keyValues()
    val hero = symbolRows.firstOrNull { it.first.lowercase() in HERO_KEYS }
    val rows = symbolRows.filter { it != hero }
    val notes = m.notes()
    val receipt = remember(m) { ReceiptBuilder.fromMessage(m) }
    var showReceipt by remember(m) { mutableStateOf(false) }
    val target = m.route?.takeIf { it != "Notifications" && it != InboxMsg.ROUTE }

    // a small, calm entrance: the icon pops in, the rest fades up; good news also gives a light tap
    var shown by remember(m) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(m) {
        shown = false
        delay(40)
        shown = true
        if (look.good) try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (e: Throwable) { }
    }
    val pop by animateFloatAsState(if (shown) 1f else 0.3f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "pop")
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(380), label = "fade")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp).graphicsLayer { alpha = fade },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- hero: icon, title, amount, time
        Column(
            Modifier.fillMaxWidth().card(RoundedCornerShape(24.dp)).background(look.tone.copy(alpha = .06f), RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(84.dp).graphicsLayer { scaleX = pop; scaleY = pop }.background(look.tone.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(62.dp).background(look.tone, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(look.icon, null, tint = OnTone, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(softCase(L(look.label)).uppercase(), color = look.tone, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
            Text(m.title, color = Dark, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp, textAlign = TextAlign.Center)
            if (hero != null) {
                val plus = look.good && look.icon != MudaIcons.CheckCircle
                Text((if (plus) "+" else "") + hero.second, color = look.tone, fontWeight = FontWeight.Bold, fontSize = 34.sp, textAlign = TextAlign.Center)
            }
            if (m.time.isNotEmpty()) Text(m.time, color = Slate, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        // ---- the message itself, big and selectable
        if (m.body.isNotBlank()) {
            Column(Modifier.fillMaxWidth().card(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(softCase(L("MESSAGE")), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                SelectionContainer { Text(m.body, color = Ink, fontSize = 16.sp, lineHeight = 24.sp) }
            }
        }

        // ---- every detail line
        if (rows.isNotEmpty() || notes.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().card(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 8.dp)) {
                rows.forEachIndexed { i, (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Text(L(k), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
                        Text(v, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.58f))
                    }
                    if (i < rows.lastIndex || notes.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                }
                notes.forEachIndexed { i, n ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
                        Icon(MudaIcons.Info, null, tint = Blue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(n, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    if (i < notes.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                }
            }
        }

        // ---- receipt (money notifications only)
        if (receipt != null) {
            Column(Modifier.fillMaxWidth().card(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L("Receipt"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(L("A branded receipt you can send on WhatsApp."), color = Muted, fontSize = 13.sp)
                GhostButton(L("View receipt"), { showReceipt = true }, Modifier.fillMaxWidth(), MudaIcons.Receipt)
                ReceiptActions(receipt)
            }
            if (showReceipt) ReceiptDialog(receipt) { showReceipt = false }
        }

        // ---- where to go next
        if (target != null) PrimaryButton(L(targetLabel(target)), { nav(target) })
        GhostButton(L("All notifications"), { nav("Notifications") }, Modifier.fillMaxWidth(), MudaIcons.Notifications)
        Spacer(Modifier.height(8.dp))
    }
}
