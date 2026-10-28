package io.mudapay.mobile

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * MudaPay agents & bureaus (server: agent_lib.php, api/v1/lib_agent.php).
 *  - AgentDeskScreen   : apply to become an agent, then the agent's own desk (code, requests to pay, commission).
 *  - AgentPickerBlock  : used by the Withdraw form when the member chooses "MudaPay Agent" / "MudaPay Bureau" (see MoneyScreens.kt).
 *  - the member's payout code + cancel button live on the withdrawal detail screen (WithdrawalScreens.kt).
 */

fun dialPhone(ctx: Context, phone: String) {
    try {
        val n = phone.filter { it.isDigit() || it == '+' }
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { Ui.notify("Call", "No phone app is available.") }
}

/** "MA7K2QX" typed by hand: letters and digits only, upper case. */
fun cleanAgentCode(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase().take(12)

/* ------------------------------------------------------------------ the Agent desk */
@Composable
fun AgentDeskScreen() {
    var reload by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Remote(refreshKey = reload, call = { Api.call("GET", "/agent/me") }) { d, _ ->
            val agent = d.optJSONObject("agent")
            if (agent == null) {
                if (!d.bool("enabled")) InfoBanner(L("MudaPay agents are not open right now. Please check again later."), Blue, Icons.Default.Info)
                else if (d.optJSONObject("id")?.str("status") != "verified") IdVerifyCard(d.optJSONObject("id")) { reload++ }
                else ApplyCard(d, null) { reload++ }
            } else when (agent.str("status")) {
                "rejected" -> if (d.optJSONObject("id")?.str("status") != "verified") IdVerifyCard(d.optJSONObject("id")) { reload++ } else ApplyCard(d, agent) { reload++ }
                "pending" -> PendingCard(agent)
                "suspended" -> InfoBanner(L("Your agent account is suspended.") + (agent.str("admin_note").takeIf { it.isNotEmpty() }?.let { " $it" } ?: "") + " " + L("Contact support."), Danger, Icons.Default.Lock)
                else -> AgentDashboard(agent, d.str("symbol")) { reload++ }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StepLine(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(Green.copy(alpha = .14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text("$n", color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Ink, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
    }
}

/** MudaPay agents must be ID-verified: ID photo + selfie holding it, reviewed by MudaPay support. */
@Composable
private fun IdVerifyCard(id: JSONObject?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = id?.str("status") ?: "none"
    var docType by remember { mutableStateOf(id?.str("doc_type")?.ifEmpty { "national_id" } ?: "national_id") }
    var hasFront by remember { mutableStateOf(id?.bool("has_front") == true) }
    var hasSelfie by remember { mutableStateOf(id?.bool("has_selfie") == true) }
    var busy by remember { mutableStateOf("") }

    fun upload(part: String, uri: Uri) {
        scope.launch {
            busy = part
            val bytes = withContext(Dispatchers.IO) { readImageForUpload(ctx, uri) }
            if (bytes == null) { busy = ""; Ui.notify("ID verification", "That photo could not be read. Choose another one."); return@launch }
            val r = Api.upload("/account/id/upload?part=$part&doc_type=$docType", Upload("file", "$part.jpg", "image/jpeg", bytes))
            busy = ""
            if (r.ok) { if (part == "front") hasFront = true else hasSelfie = true } else Ui.notify("ID verification", r.message)
        }
    }
    val pickFront = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) upload("front", uri) }
    val pickSelfie = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) upload("selfie", uri) }

    Column(Modifier.fillMaxWidth().heroCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(softCase(L("ID VERIFICATION")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
        Text(L("Verify your identity to become an agent"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
    }
    if (status == "pending") {
        InfoBanner(L("Your ID is being reviewed by MudaPay support. You will be notified of the result."), Blue, Icons.Default.HourglassTop)
        return
    }
    if (status == "rejected") InfoBanner(L("Your ID was not accepted.") + (id?.str("note")?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "") + " " + L("Please send clearer photos."), Danger, Icons.Default.Info)
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Agents handle other members' cash, so MudaPay support checks every agent's identity first. Your photos are private and only seen by MudaPay support."), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Dropdown(L("Document"), docType, listOf("national_id" to L("National ID card"), "passport" to L("Passport"), "driver_licence" to L("Driving licence"))) { docType = it }
        PrimaryButton(L(if (hasFront) "ID PHOTO ADDED ✓ (tap to change)" else "ADD A PHOTO OF YOUR ID"), { pickFront.launch("image/*") }, loading = busy == "front", color = if (hasFront) GreenDark else Dark, icon = Icons.Default.Badge)
        PrimaryButton(L(if (hasSelfie) "SELFIE ADDED ✓ (tap to change)" else "ADD A SELFIE HOLDING YOUR ID"), { pickSelfie.launch("image/*") }, loading = busy == "selfie", color = if (hasSelfie) GreenDark else Dark, icon = Icons.Default.Person)
        Text(L("Use a clear, well-lit photo. All four corners of the document and your face must be visible."), color = Muted, fontSize = 12.sp)
        var sending by remember { mutableStateOf(false) }
        PrimaryButton(softCase(L("SEND FOR REVIEW")), {
            sending = true
            scope.launch {
                val r = Api.call("POST", "/account/id/submit")
                sending = false
                if (r.ok) { Ui.notify("ID sent", "MudaPay support will review it and tell you the result."); onDone() } else Ui.notify("ID verification", r.message)
            }
        }, loading = sending, enabled = hasFront && hasSelfie, icon = Icons.Default.Check)
    }
}

@Composable
private fun ApplyCard(d: JSONObject, old: JSONObject?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val terms = d.optJSONObject("terms")
    val pct = terms?.dbl("agent_pct") ?: 0.0
    var city by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(d.str("my_phone")) }
    var hours by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pinLat by remember { mutableStateOf<Double?>(null) }
    var pinLng by remember { mutableStateOf<Double?>(null) }
    var onMap by remember { mutableStateOf(true) }
    val locate = rememberLocationRequester { loc ->
        if (loc == null) Ui.notify("Location", "We could not get your position. Turn on location and try again.")
        else { pinLat = loc.latitude; pinLng = loc.longitude }
    }

    Column(Modifier.fillMaxWidth().heroCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(softCase(L("BECOME A MUDAPAY AGENT")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
        Text(L("Pay members their cash and earn commission"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
    }
    if (old != null && old.str("status") == "rejected") {
        InfoBanner(L("Your last application was not approved.") + (old.str("admin_note").takeIf { it.isNotEmpty() }?.let { " $it" } ?: "") + " " + L("You can apply again."), Danger, Icons.Default.Info)
    }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(L("How it works"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        StepLine(1, L("A member asks for a cash withdrawal with your agent code."))
        StepLine(2, L("You hand her the cash."))
        StepLine(3, L("She gives you her 6-digit payout code. You type it in the app."))
        StepLine(4, L("MudaPay credits your account with the cash you paid out plus your commission") + if (pct > 0) " (${"%.1f".format(pct)}%)." else ".")
    }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Your details"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        InputField(L("Town / city"), city, { city = it })
        InputField(L("Where can members find you? (street, market, shop)"), address, { address = it })
        InputField(L("Phone number members can call"), phone, { phone = it }, keyboard = KeyboardType.Phone)
        InputField(L("Opening hours (optional)"), hours, { hours = it })
        InputField(L("Tell us about you (optional)"), note, { note = it }, singleLine = false, minLines = 2)
        Column(Modifier.fillMaxWidth().background(Blue.copy(alpha = .06f), RoundedCornerShape(15.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L("Be found on the map"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(L("Members near you can find you, call you and get directions. Your pin is shown about 100 m off; your address and phone are shown on your card."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            PrimaryButton(L(if (pinLat == null) "USE MY CURRENT LOCATION" else "POSITION SAVED ✓ (tap to update)"), { locate() }, color = Blue, icon = Icons.Default.MyLocation)
            if (pinLat != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Show me on the agent map"), color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = onMap, onCheckedChange = { onMap = it })
            }
        }
        Text(L("You need enough cash of your own to pay members. MudaPay reviews every application."), color = Muted, fontSize = 12.sp)
        PrimaryButton(softCase(L("SEND APPLICATION")), {
            when {
                city.trim().length < 2 -> Ui.notify("Agent", "Enter your town.")
                address.trim().length < 4 -> Ui.notify("Agent", "Enter where members can find you.")
                else -> {
                    busy = true
                    scope.launch {
                        val body = JSONObject().put("city", city.trim()).put("address", address.trim()).put("phone", phone.trim()).put("hours", hours.trim()).put("note", note.trim())
                        val la = pinLat; val ln = pinLng
                        if (la != null && ln != null) body.put("lat", la).put("lng", ln).put("listed", onMap)
                        val r = Api.call("POST", "/agent/apply", body)
                        busy = false
                        if (r.ok) { Ui.notify("Application sent", "We will review it and tell you the result here and by notification."); onDone() }
                        else Ui.notify("Agent", r.message)
                    }
                }
            }
        }, loading = busy, icon = Icons.Default.Storefront)
    }
}

@Composable
private fun PendingCard(agent: JSONObject) {
    InfoBanner(L("Your application is waiting for review. You will get a notification as soon as it is approved."), Blue, Icons.Default.HourglassTop)
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(L("Your application"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("${agent.str("city")} — ${agent.str("address")}", color = Ink, fontSize = 13.sp)
        Text(agent.str("phone"), color = Muted, fontSize = 13.sp)
        if (agent.str("hours").isNotEmpty()) Text(agent.str("hours"), color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.card(RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun AgentDashboard(agent: JSONObject, symbol: String, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = agent.str("code")
    val isBureau = agent.str("kind") == "bureau"
    var available by remember(agent.str("status")) { mutableStateOf(agent.str("status") == "active") }
    var poll by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var showHistory by remember { mutableStateOf(false) }
    var confirmFor by remember { mutableStateOf<JSONObject?>(null) }
    var declineFor by remember { mutableStateOf<JSONObject?>(null) }
    var busyId by remember { mutableIntStateOf(0) }
    // new requests arrive while the desk is open: look every 15 s (a push notification also tells the agent)
    LaunchedEffect(Unit) { while (true) { delay(15000); poll++ } }

    Column(Modifier.fillMaxWidth().heroCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(L(if (isBureau) "MUDAPAY BUREAU" else "MUDAPAY AGENT"), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
        Text(code, color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
        Text("${agent.str("name")} • ${agent.str("city")}", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        Text(L("Members enter this code in Withdraw → MudaPay ${if (isBureau) "Bureau" else "Agent"}."), color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(softCase(L("COPY")), { copyToClipboard(ctx, "Agent code", code); Ui.notify("Copied", "Agent code copied.") }, Modifier.weight(1f), color = Dark, icon = Icons.Default.ContentCopy)
            PrimaryButton(softCase(L("SHARE")), {
                shareText(ctx, "Withdraw your MudaPay money in cash with me. Agent code: $code (${agent.str("city")}). In the MudaPay app open Withdraw → MudaPay ${if (isBureau) "Bureau" else "Agent"} and enter my code.")
            }, Modifier.weight(1f), color = Dark, icon = Icons.Default.Share)
        }
    }

    Row(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(L("Accepting requests"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(L(if (available) "Members can choose you" else "Paused: members cannot choose you"), color = Muted, fontSize = 12.sp)
        }
        Switch(checked = available, onCheckedChange = { v ->
            available = v
            scope.launch {
                val r = Api.call("POST", "/agent/availability", JSONObject().put("available", v))
                if (!r.ok) { available = !v; Ui.notify("Agent", r.message) }
            }
        })
    }

    LocatorSettingsCard(agent)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(softCase(L("CASH-OUTS")), "${agent.int("jobs")}", Modifier.weight(1f))
        StatTile(softCase(L("PAID OUT")), money(symbol, agent.dbl("total_paid")), Modifier.weight(1.4f))
        StatTile(softCase(L("COMMISSION")), money(symbol, agent.dbl("total_commission")), Modifier.weight(1.2f))
    }

    Text(L("Requests to pay"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
    Remote(refreshKey = "$poll:$tick", call = { Api.call("GET", "/agent/requests", query = mapOf("scope" to "open")) }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) EmptyState(L("No request right now"), Icons.Default.Inbox, L("New requests appear here and as a notification."))
        items.forEach { r ->
            val id = r.int("id")
            RequestCard(r, symbol, busyId == id,
                onAccept = {
                    busyId = id
                    scope.launch {
                        val res = Api.call("POST", "/agent/requests/$id/accept")
                        busyId = 0
                        if (!res.ok) Ui.notify("Agent", res.message)
                        tick++
                    }
                },
                onDecline = { declineFor = r },
                onConfirm = { confirmFor = r })
        }
    }

    val cReq = confirmFor
    if (cReq != null) {
        ConfirmCodeDialog(cReq, symbol, onDismiss = { confirmFor = null }) { typed ->
            confirmFor = null
            val id = cReq.int("id")
            busyId = id
            scope.launch {
                val res = Api.call("POST", "/agent/requests/$id/confirm", JSONObject().put("code", typed), idem = newIdemKey())
                busyId = 0
                if (res.ok) {
                    Session.balance = res.data.dbl("balance")
                    Ui.notify("Cash-out settled ✅", "${money(symbol, res.data.dbl("credited"))} was added to your MudaPay account (${money(symbol, res.data.dbl("cash"))} cash-out + ${money(symbol, res.data.dbl("commission"))} commission).")
                } else Ui.notify("Confirm payout", res.message)
                tick++
                onChange()
            }
        }
    }
    val dReq = declineFor
    if (dReq != null) {
        DeclineDialog(onDismiss = { declineFor = null }) { reason ->
            declineFor = null
            val id = dReq.int("id")
            busyId = id
            scope.launch {
                val res = Api.call("POST", "/agent/requests/$id/decline", JSONObject().put("reason", reason))
                busyId = 0
                if (!res.ok) Ui.notify("Agent", res.message)
                tick++
                onChange()
            }
        }
    }

    Row(Modifier.fillMaxWidth().clickable { showHistory = !showHistory }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(L("Recent cash-outs"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Icon(if (showHistory) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Muted)
    }
    if (showHistory) {
        Remote(refreshKey = tick, call = { Api.call("GET", "/agent/requests", query = mapOf("scope" to "history")) }) { d, _ ->
            val items = d.list("items")
            if (items.isEmpty()) EmptyState(L("Nothing yet"), Icons.Default.SouthWest)
            items.forEach { r ->
                val st = r.str("status")
                Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(money(symbol, r.dbl("cash_amount")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${r.str("member_name")} • ${r.str("closed_at").ifEmpty { r.str("created_at") }.take(16)}", color = Muted, fontSize = 12.sp)
                        if (st == "completed") Text(L("Commission") + " " + money(symbol, r.dbl("commission")), color = GreenDark, fontSize = 12.sp)
                        else if (r.str("note").isNotEmpty()) Text(r.str("note"), color = Muted, fontSize = 12.sp)
                    }
                    Pill(if (st == "completed") L("Paid") else L("Closed"), if (st == "completed") GreenDark else Danger)
                }
            }
        }
    }
}

@Composable
private fun RequestCard(r: JSONObject, symbol: String, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit, onConfirm: () -> Unit) {
    val st = r.str("status")
    val cash = r.dbl("cash_amount")
    val comm = r.dbl("commission")
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.str("member_name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${r.str("member_phone")} • #${r.str("reference")} • ${r.str("created_at").take(16)}", color = Muted, fontSize = 12.sp)
            }
            Pill(if (st == "approved") L("Accepted") else L("New"), if (st == "approved") GreenDark else Blue)
        }
        Text(softCase(L("GIVE IN CASH")), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
        Text(money(symbol, cash), color = Dark, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(L("You will receive") + " ${money(symbol, cash + comm)} " + L("in your MudaPay account") + " (${money(symbol, comm)} " + L("commission") + ").", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        if (r.bool("locked")) {
            InfoBanner(L("Too many wrong codes. Contact MudaPay support to settle this request."), Danger, Icons.Default.Lock)
        } else {
            if (st == "pending") {
                if (r.bool("overdue")) InfoBanner(L("This request expired and will be refunded to the member."), Danger, Icons.Default.Schedule)
                PrimaryButton(softCase(L("ACCEPT")), onAccept, loading = busy, enabled = !r.bool("overdue"), icon = Icons.Default.Check)
            } else {
                InfoBanner(L("Hand over the cash, then ask the member for her 6-digit code. Never enter a code before she holds the cash."), Blue, Icons.Default.Info)
                PrimaryButton(softCase(L("ENTER MEMBER'S CODE")), onConfirm, loading = busy, icon = Icons.Default.Lock)
            }
        }
        PrimaryButton(softCase(L("DECLINE")), onDecline, enabled = !busy, color = Danger, icon = Icons.Default.Close)
    }
}

@Composable
private fun ConfirmCodeDialog(r: JSONObject, symbol: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(L("Member's payout code"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("Ask ${r.str("member_name")} for the 6-digit code shown on her phone, after you gave her ${money(symbol, r.dbl("cash_amount"))} in cash."), color = Ink, fontSize = 13.sp)
                InputField(L("6-digit code"), code, { code = it.filter { c -> c.isDigit() }.take(6) }, keyboard = KeyboardType.NumberPassword)
                if (r.int("tries_left") in 1..4) Text(L("Tries left") + ": ${r.int("tries_left")}", color = Danger, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = code.length == 6, onClick = { onSubmit(code) }) { Text(L("Confirm"), color = GreenDark, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel"), color = Muted) } }
    )
}

@Composable
private fun DeclineDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(L("Decline this request?"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("The member gets her money back at once. Only decline if you have NOT paid her."), color = Ink, fontSize = 13.sp)
                InputField(L("Reason (optional)"), reason, { reason = it.take(80) })
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(reason.trim()) }) { Text(L("Decline"), color = Danger, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel"), color = Muted) } }
    )
}

/* ------------------------------------------------------------------ Withdraw form: choose the agent / bureau */
/**
 * Shown by the Withdraw form for the methods "Agent" and "Bureau". Reports the chosen code through [onCode]
 * ("" = nothing valid chosen yet). [feePct] is what the member pays on top when the admin put the commission on the member.
 */
@Composable
fun AgentPickerBlock(isBureau: Boolean, feePct: Double, code: String, onKind: (String) -> Unit = {}, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(code) }
    var showMap by remember { mutableStateOf(false) }

    // Agent locator: find an agent or bureau on the map, then CHOOSE fills the code (and switches Agent / Bureau if needed).
    PrimaryButton(softCase(L("FIND AGENTS NEAR ME")), { showMap = true }, color = Blue, icon = Icons.Default.Map)
    if (showMap) {
        Dialog(onDismissRequest = { showMap = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = Page) {
                AgentLocatorScreen(onClose = { showMap = false }, onChoose = { a ->
                    showMap = false
                    val bureau = a.str("kind") == "bureau"
                    if (bureau != isBureau) onKind(if (bureau) "Bureau" else "Agent")
                    typed = a.str("code")
                    info = a
                    onCode(a.str("code"))
                })
            }
        }
    }

    if (isBureau) {
        Remote(call = { Api.call("GET", "/agents/bureaus") }) { d, _ ->
            val items = d.list("items")
            if (items.isEmpty()) {
                InfoBanner(L("No MudaPay bureau is open in your country yet."), Blue, Icons.Default.Info)
            } else {
                val chosen = items.firstOrNull { it.str("code") == code } ?: items.first()
                LaunchedEffect(chosen.str("code")) { if (code != chosen.str("code")) onCode(chosen.str("code")) }
                Dropdown(L("Choose a bureau"), chosen.str("code"), items.map { it.str("code") to "${it.str("name")} — ${it.str("city")}" }) { onCode(it) }
                AgentInfoCard(chosen, ctx, feePct)
            }
        }
    } else {
        InputField(L("Agent code (for example MA7K2QX)"), typed, { v ->
            typed = cleanAgentCode(v)
            info = null
            onCode("")
        }, trailing = {
            TextButton(enabled = typed.length >= 6 && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = Api.call("POST", "/agents/lookup", JSONObject().put("code", typed))
                    busy = false
                    when {
                        !r.ok -> Ui.notify("Agent", r.message)
                        !r.data.bool("found") -> Ui.notify("Agent", if (r.data.bool("self")) "You cannot cash out through your own agent code." else "No MudaPay agent has this code.")
                        !r.data.bool("same_country") -> Ui.notify("Agent", "This agent works in another country. Choose an agent in your country.")
                        !r.data.bool("available") -> Ui.notify("Agent", "This agent is not taking requests right now.")
                        else -> { info = r.data; onCode(typed) }
                    }
                }
            }) { Text(if (busy) "…" else softCase(L("CHECK")), color = GreenDark, fontWeight = FontWeight.Bold) }
        })
        val i = info
        if (i != null && code.isNotEmpty()) AgentInfoCard(i, ctx, feePct)
        else Text(L("Ask the agent for his code, then press CHECK to see who he is."), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun AgentInfoCard(a: JSONObject, ctx: Context, feePct: Double) {
    Column(Modifier.fillMaxWidth().background(Green.copy(alpha = .08f), RoundedCornerShape(15.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storefront, null, tint = GreenDark, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(a.str("name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(a.str("code"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text("${a.str("city")} — ${a.str("address")}", color = Ink, fontSize = 13.sp)
        if (a.str("hours").isNotEmpty()) Text(a.str("hours"), color = Muted, fontSize = 12.sp)
        if (a.str("phone").isNotEmpty()) {
            Row(Modifier.clickable { dialPhone(ctx, a.str("phone")) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Phone, null, tint = Blue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(a.str("phone"), color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        val hasPos = !a.isNull("lat") && !a.isNull("lng")
        val addrQuery = listOf(a.str("address"), a.str("city")).filter { it.isNotEmpty() }.joinToString(", ")
        if (hasPos || addrQuery.isNotEmpty()) {
            Row(
                Modifier.clickable { openDirections(ctx, if (hasPos) a.dbl("lat") else null, if (hasPos) a.dbl("lng") else null, a.str("name"), addrQuery) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Directions, null, tint = GreenDark, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(softCase(L("DIRECTIONS")), color = GreenDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            a.dbl("fee_pct").let { f -> if (f > 0) L("Fee: ${"%.1f".format(f)}% is taken from your cash.") else L("No fee for you.") },
            color = Muted, fontSize = 12.sp
        )
    }
}

/** Small card at the bottom of the Withdraw tab: become an agent, or open the agent desk. */
@Composable
fun AgentEntryCard(rules: JSONObject, nav: (String) -> Unit) {
    val ag = rules.optJSONObject("agents") ?: return
    val isAgent = ag.bool("is_agent")
    if (!ag.bool("enabled") && !isAgent) return
    val status = ag.str("agent_status")
    ContentRow(
        if (isAgent) L("Agent desk") else if (status == "pending") L("Agent application") else L("Become a MudaPay agent"),
        if (isAgent) L("Pay cash-outs and earn commission") else if (status == "pending") L("Waiting for review") else L("Pay members their cash and earn commission"),
        Icons.Default.Storefront, { nav("AgentDesk") }
    )
}
