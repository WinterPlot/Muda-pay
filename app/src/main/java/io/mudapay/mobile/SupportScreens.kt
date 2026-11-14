package io.mudapay.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Support center: tickets are conversations with the MudaPay team, optionally linked to one transaction (dispute).
 * While the team is away (or the member wants a faster answer) "Chat with Muda AI" opens the assistant, which already knows the ticket.
 * Server side: api/v1/lib_support.php.
 */
object Support {
    /** A transaction the member wants to report. Set by [report], read once by the Support screen. */
    class Draft(val kind: String, val id: String, val title: String, val summary: String)

    private var draft: Draft? = null
    /** Muda AI opened from a ticket: the assistant gets the ticket id (read once by CoachScreen) and a first sentence in the message box. */
    private var coachTicket = 0
    private var coachPrompt = ""

    /** "Report a problem" on a transaction: opens Support with the transaction already attached. kind = tx | withdrawal | transfer | wallet. */
    fun report(kind: String, id: String, title: String, summary: String) {
        draft = Draft(kind, id, title, summary)
        Ui.pendingRoute = "Support"
    }
    fun takeDraft(): Draft? { val d = draft; draft = null; return d }
    fun openAi(ticketId: Int, prompt: String) { coachTicket = ticketId; coachPrompt = prompt }
    fun takeCoachTicket(): Int { val t = coachTicket; coachTicket = 0; return t }
    fun takeCoachPrompt(): String { val p = coachPrompt; coachPrompt = ""; return p }
}

private val CATEGORIES = listOf("Payment" to "Payment issue", "Video" to "Video issue", "Account" to "Account security", "Other" to "Other")
private val REASONS = listOf(
    "not_received" to "Money not received",
    "wrong_amount" to "Wrong amount",
    "wrong_recipient" to "Sent to the wrong person",
    "duplicate" to "Charged twice",
    "unauthorized" to "I did not make this transaction",
    "pending_too_long" to "Pending for too long",
    "other" to "Something else"
)

private fun reasonLabel(key: String) = L(REASONS.firstOrNull { it.first == key }?.second ?: key)
private fun statusTone(s: String): Color = if (s.equals("Resolved", true)) GreenDark else if (s.equals("In Progress", true)) Blue else Slate
private fun statusLabel(s: String): String = when (s) { "Pending" -> "Waiting for the team"; "In Progress" -> "In progress"; "Resolved" -> "Resolved"; else -> s }

@Composable
fun SupportScreen(nav: (String) -> Unit) {
    var openId by remember { mutableIntStateOf(0) }
    BackHandler(enabled = openId != 0) { openId = 0; Ui.refreshTick++ }
    if (openId != 0) SupportThread(openId, nav) { openId = 0; Ui.refreshTick++ }
    else SupportHome(nav) { openId = it }
}

/* ------------------------------------------------------------------ "Chat with Muda AI" shortcut */
@Composable
private fun AiShortcut(staffOnline: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Green.copy(alpha = .10f)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).background(Gradient, CircleShape), contentAlignment = Alignment.Center) {
            Icon(MudaIcons.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(L("Need a faster answer?"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                L(if (staffOnline) "Chat with Muda AI. It answers right away, 24/7, while you wait for the team."
                  else "Our team is away right now. Chat with Muda AI: it answers right away, 24/7."),
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(MudaIcons.Chat, null, tint = GreenDark, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TeamStatus(cfg: JSONObject?) {
    if (cfg == null) return
    val online = cfg.bool("staff_online")
    val hours = cfg.optJSONObject("hours")
    val range = if (hours != null) "${hours.str("start")}–${hours.str("end")}" else ""
    val replyTime = cfg.str("reply_time")
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (online) GreenDark else Slate, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(L(if (online) "Our team is online" else "Our team is away"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                (if (range.isNotEmpty()) L("Support hours") + " " + range + " · " else "") + L("We usually answer") + " " + L(replyTime),
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }
}

/* ------------------------------------------------------------------ list + new request */
@Composable
private fun SupportHome(nav: (String) -> Unit, onOpen: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var cfg by remember { mutableStateOf<JSONObject?>(null) }
    var draft by remember { mutableStateOf(Support.takeDraft()) }
    var formOpen by remember { mutableStateOf(draft != null) }
    var category by remember { mutableStateOf("Payment") }
    var isDispute by remember { mutableStateOf(true) }
    var reason by remember { mutableStateOf("not_received") }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Ui.refreshTick) { val r = Api.call("GET", "/support/config"); if (r.ok) cfg = r.data }

    fun openAi() { Support.openAi(0, ""); nav("Coach") }

    fun submit() {
        val d = draft
        if (message.isBlank() || (d == null && subject.isBlank())) { Ui.notify("Support", "Please fill in all fields."); return }
        val body = JSONObject().put("message", message.trim())
        if (d != null) {
            body.put("ref_kind", d.kind).put("ref_id", d.id)
            if (isDispute) body.put("dispute_reason", reason).put("category", "Dispute") else body.put("category", "Payment")
            if (subject.isNotBlank()) body.put("subject", subject.trim())
        } else {
            body.put("category", category).put("subject", subject.trim())
        }
        busy = true
        scope.launch {
            val r = Api.call("POST", "/support/tickets", body)
            busy = false
            if (r.ok) {
                Notify.ticketSent(subject.ifBlank { d?.title ?: "" })
                subject = ""; message = ""; draft = null; formOpen = false; reload++
                onOpen(r.data.int("id"))
            } else if (r.code == "already_open") {
                val existing = r.error?.int("ticket_id") ?: 0
                draft = null; formOpen = false; reload++
                Ui.notify("Support", r.message)
                if (existing != 0) onOpen(existing)
            } else Ui.notify("Support", r.message)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro("Support center", "Ask the MudaPay team, report a problem with a transaction, or chat with Muda AI.", MudaIcons.SupportAgent)
        TeamStatus(cfg)
        AiShortcut(cfg?.bool("staff_online") ?: true) { openAi() }

        if (!formOpen) {
            PrimaryButton(L("NEW REQUEST"), { formOpen = true }, icon = MudaIcons.Send)
        } else {
            Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val d = draft
                if (d != null) {
                    // the transaction is attached: the member does not have to type amount, date or reference
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Soft).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(softCase(L("Transaction")).uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(L(d.title), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(d.summary, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        TextButton(onClick = { draft = null }, contentPadding = PaddingValues(0.dp)) { Text(L("Remove the transaction"), color = GreenDark, fontSize = 12.sp) }
                    }
                    Dropdown(L("What do you need?"), if (isDispute) "dispute" else "question",
                        listOf("dispute" to L("Report a problem (dispute)"), "question" to L("I have a question about it"))) { isDispute = it == "dispute" }
                    if (isDispute) Dropdown(L("What went wrong?"), reason, REASONS.map { it.first to L(it.second) }) { reason = it }
                    InputField(L("Subject (optional)"), subject, { subject = it })
                } else {
                    Dropdown(L("Category"), category, CATEGORIES.map { it.first to L(it.second) }) { category = it }
                    InputField(L("Subject"), subject, { subject = it })
                }
                InputField(L("Describe your issue"), message, { message = it }, singleLine = false, minLines = 4)
                if (draft != null && isDispute) InfoBanner(L("Sending this does not change your balance. The MudaPay team checks the transaction and answers you here."))
                PrimaryButton(L("SEND TO THE TEAM"), { submit() }, loading = busy, icon = MudaIcons.Send)
                TextButton(onClick = { formOpen = false; draft = null }, modifier = Modifier.fillMaxWidth()) { Text(L("Cancel"), color = Muted, fontWeight = FontWeight.SemiBold) }
            }
        }

        SectionTitle(L("Your tickets"))
        Remote(refreshKey = reload, call = { Api.call("GET", "/support/tickets") }) { d, _ ->
            val items = d.list("items")
            if (items.isEmpty()) EmptyState(L("No tickets yet"), MudaIcons.Inbox)
            items.forEach { t -> TicketCard(t) { onOpen(t.int("id")) } }
        }
    }
}

@Composable
private fun TicketCard(t: JSONObject, onClick: () -> Unit) {
    val status = t.str("status")
    val unread = t.bool("unread")
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).tappable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (unread) { Box(Modifier.size(9.dp).background(Danger, CircleShape)); Spacer(Modifier.width(8.dp)) }
            Text(t.str("subject"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
            Spacer(Modifier.width(8.dp))
            Pill(L(statusLabel(status)), statusTone(status))
        }
        if (t.str("dispute_reason").isNotEmpty()) Text(L("Dispute") + " · " + reasonLabel(t.str("dispute_reason")), color = Danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (t.str("ref_summary").isNotEmpty()) Text(t.str("ref_summary"), color = Muted, fontSize = 11.sp, maxLines = 2, lineHeight = 15.sp)
        val last = t.optJSONObject("last_message")
        val preview = if (last != null) (if (last.str("sender") == "staff") L("MudaPay Support") + ": " else L("You") + ": ") + last.str("body") else t.str("message")
        Text(preview, color = if (unread) Ink else Muted, fontSize = 12.sp, maxLines = 2, lineHeight = 17.sp, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
        Text(L(t.str("category")) + " • " + t.str("updated_at").take(16) + " • #" + t.int("id"), color = Slate, fontSize = 10.sp)
    }
}

/* ------------------------------------------------------------------ one ticket = one conversation */
@Composable
private fun SupportThread(id: Int, nav: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember(id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var input by remember(id) { mutableStateOf("") }
    var sending by remember(id) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    suspend fun load() {
        val r = Api.call("GET", "/support/tickets/$id")
        if (r.ok) { data = r.data; error = null } else if (data == null) error = r.message
    }
    // new answers from the team show up by themselves (every 12 s while this screen is open)
    LaunchedEffect(id) { load(); while (true) { delay(12000); load() } }

    val d = data
    val ticket = d?.optJSONObject("ticket")
    val msgs = d?.list("messages") ?: emptyList()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size) }

    fun send() {
        val t = input.trim()
        if (t.isEmpty() || sending) return
        sending = true
        scope.launch {
            val r = Api.call("POST", "/support/tickets/$id/messages", JSONObject().put("message", t))
            sending = false
            if (r.ok) { input = ""; load() } else Ui.notify("Support", r.message)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (ticket == null) {
            Box(Modifier.weight(1f).padding(18.dp)) {
                if (error != null) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoBanner(error ?: "", Danger)
                    PrimaryButton(L("Back"), onBack, color = Dark)
                } else SkeletonCards()
            }
        } else {
            val status = ticket.str("status")
            val staffOnline = d?.bool("staff_online") ?: true
            val canReply = d?.bool("can_reply") ?: true
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ticket.str("subject"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Pill(L(statusLabel(status)), statusTone(status))
                        }
                        Text(L(ticket.str("category")) + " • #" + ticket.int("id"), color = Slate, fontSize = 11.sp)
                        if (ticket.str("dispute_reason").isNotEmpty()) Text(L("Dispute") + " · " + reasonLabel(ticket.str("dispute_reason")), color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (ticket.str("ref_summary").isNotEmpty()) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Soft).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(softCase(L("Transaction")).uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(ticket.str("ref_summary"), color = Ink, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }
                        if (status != "Resolved") {
                            Text(
                                L(if (staffOnline) "The team will answer here and you will get a notification. We usually answer" else "Our team is away right now. We will answer here as soon as we are back. We usually answer") + " " + L(d?.str("reply_time") ?: ""),
                                color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                            )
                        }
                    }
                }
                items(msgs) { m -> SupportBubble(m) }
                if (status != "Resolved") item {
                    AiShortcut(staffOnline) {
                        Support.openAi(id, L("I need help with my support ticket: ") + ticket.str("subject"))
                        nav("Coach")
                    }
                }
                if (status != "Resolved") item {
                    TextButton(onClick = {
                        scope.launch {
                            val r = Api.call("POST", "/support/tickets/$id/close")
                            if (r.ok) load() else Ui.notify("Support", r.message)
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(L("My problem is solved: close this ticket"), color = GreenDark, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                }
            }
            if (canReply) {
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it.take(3000) }, modifier = Modifier.weight(1f),
                            placeholder = { Text(L(if (status == "Resolved") "Write to reopen this ticket" else "Write a message")) },
                            shape = RoundedCornerShape(24.dp), maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, cursorColor = Green, unfocusedBorderColor = Color.Transparent, focusedContainerColor = Soft, unfocusedContainerColor = Soft, focusedTextColor = Ink, unfocusedTextColor = Ink)
                        )
                        Spacer(Modifier.width(8.dp))
                        val canSend = input.isNotBlank() && !sending
                        Box(
                            Modifier.size(50.dp).clip(CircleShape).background(if (canSend) Green else Green.copy(alpha = .4f)).clickable(enabled = canSend) { send() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (sending) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(MudaIcons.Send, L("Send"), tint = Color.White)
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { InfoBanner(L("This ticket was resolved a while ago. Please open a new request from the Support center.")) }
            }
        }
    }
}

@Composable
private fun SupportBubble(m: JSONObject) {
    val mine = m.str("sender") == "member"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(L(if (mine) "You" else "MudaPay Support"), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .background(if (mine) Green else Panel).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.str("body"), color = if (mine) Color.White else Ink, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Start)
        }
        Text(m.str("created_at").take(16), color = Slate, fontSize = 10.sp)
    }
}
