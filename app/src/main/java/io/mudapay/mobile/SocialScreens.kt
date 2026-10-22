package io.mudapay.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/* ------------------------------------------------------------------ Share & Earn */
@Composable
fun ShareScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var target by remember { mutableIntStateOf(0) }
    var uploading by remember { mutableIntStateOf(0) }
    val symbol = Session.user?.str("symbol") ?: ""

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cid = target
        if (uri != null && cid != 0) {
            uploading = cid
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readImageForUpload(ctx, uri) }
                if (bytes == null) Ui.notify("Screenshot", "That image could not be read. Choose a JPG, PNG or WEBP screenshot.")
                else {
                    val r = Api.upload("/campaigns/$cid/proofs", Upload("shot", "shot.jpg", "image/jpeg", bytes))
                    if (r.ok) {
                        val status = r.data.str("status")
                        if (status == "approved") { Session.balance = r.data.dbl("balance"); Ui.notify("Approved 🎉", "+${money(symbol, r.data.dbl("reward"))} added to your balance.") }
                        else if (status == "rejected") Ui.notify("Not approved", r.data.str("reason", "We could not verify your post."))
                        else Ui.notify("Received", "Your screenshot is being reviewed.")
                        reload++
                    } else Ui.notify("Screenshot", r.message)
                }
                uploading = 0
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageIntro("Share & Earn", "Post a campaign, send us a screenshot and get paid after review.", Icons.Default.Share)
        Remote(refreshKey = reload, call = { Api.call("GET", "/campaigns") }) { d, _ ->
            val sym = d.str("symbol")
            InfoBanner("Reward: ${money(sym, d.dbl("reward"))} per approved campaign. Up to ${d.int("max_attempts")} attempts per campaign and ${d.int("daily_upload_limit")} uploads a day. Include your invite link in the post.")
            val items = d.list("items")
            if (items.isEmpty()) EmptyState("No campaigns right now", Icons.Default.Campaign, "Check back soon.")
            items.forEach { c ->
                val sub = c.optJSONObject("submission")
                val status = sub?.str("status") ?: ""
                val attempts = sub?.int("attempts") ?: 0
                val canUpload = status == "" || (status == "rejected" && attempts < d.int("max_attempts"))
                Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteImage(c.str("image_url"), Modifier.fillMaxWidth().height(200.dp).background(Line, RoundedCornerShape(16.dp)), ContentScale.Fit)
                    Text(c.str("title"), color = Dark, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(c.str("caption"), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                    when (status) {
                        "approved" -> Pill("Approved ✓", GreenDark)
                        "pending" -> Pill("In review", Blue)
                        "rejected" -> { Pill("Rejected", Danger); sub?.str("reason")?.takeIf { it.isNotEmpty() }?.let { InfoBanner(it, Danger) } }
                    }
                    if (status != "approved") {
                        PrimaryButton("SHARE CAPTION + LINK", { shareText(ctx, c.str("caption") + "\n" + d.str("referral_link")) }, color = Dark, icon = Icons.Default.Share)
                        if (canUpload) PrimaryButton(if (status == "rejected") "UPLOAD A NEW SCREENSHOT" else "UPLOAD SCREENSHOT", { target = c.int("id"); picker.launch("image/*") },
                            loading = uploading == c.int("id"), enabled = uploading == 0, icon = Icons.Default.UploadFile)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ leaderboard */
@Composable
fun LeaderboardScreen() {
    var period by remember { mutableStateOf("month") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro("Leaderboard", "Top referrers. Rank 1 wins the monthly prize.", Icons.Default.EmojiEvents)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("month" to "This month", "all" to "All time").forEach { (k, label) ->
                FilterChip(selected = period == k, onClick = { period = k }, label = { Text(label, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.White))
            }
        }
        Remote(refreshKey = period, call = { Api.call("GET", "/leaderboard", query = mapOf("period" to period)) }) { d, _ ->
            val symbol = Session.user?.str("symbol") ?: ""
            Column(Modifier.fillMaxWidth().background(Gradient, RoundedCornerShape(22.dp)).padding(18.dp)) {
                Text("MONTHLY PRIZE", color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(money(symbol, d.dbl("prize")), color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Reach at least ${d.int("min_referrals_to_win")} referrals to qualify.", color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
            }
            d.optJSONObject("last_winner")?.let { w ->
                InfoBanner("Last winner: ${w.str("user_name")} (${countryName(w.str("country"))}) with ${w.int("referrals")} referrals won ${money(symbol, w.dbl("prize"))}.", Color(0xFFD97706), Icons.Default.EmojiEvents)
            }
            val items = d.list("items")
            if (items.isEmpty()) EmptyState("No ranking yet", Icons.Default.Leaderboard, "Invite friends to appear here.")
            items.forEach { r ->
                val me = r.bool("is_me")
                val rank = r.int("rank")
                Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp), if (me) Green.copy(alpha = .1f).compositeOver(Color.White) else Color.White).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(when (rank) { 1 -> Gold; 2 -> Color(0xFFCBD5E1); 3 -> Color(0xFFF1B27A); else -> Page }, CircleShape), contentAlignment = Alignment.Center) {
                        Text("$rank", color = Dark, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Flag(r.str("country", "bi"), 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(r.str("name") + if (me) " (you)" else "", color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${r.int("referrals")} refs", color = GreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
            }
            d.optJSONObject("me")?.let { m ->
                if (items.none { it.bool("is_me") } && !m.isNull("rank")) InfoBanner("Your position: #${m.int("rank")} with ${m.int("refs")} referrals.")
            }
        }
    }
}

/* ------------------------------------------------------------------ notifications */
@Composable
fun NotificationsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageIntro("Notifications", "Account updates, reward notices and announcements.", Icons.Default.Notifications)
        Remote(call = { Api.call("GET", "/notifications") }) { d, _ ->
            val symbol = Session.user?.str("symbol") ?: ""
            LaunchedEffect(Unit) {   // like the website: opening the list marks everything as read
                if (d.int("unread") > 0) { Api.call("POST", "/notifications/read-all"); Session.unread = 0 }
            }
            val items = d.list("items")
            if (items.isEmpty()) EmptyState("No notifications yet", Icons.Default.NotificationsNone)
            items.forEach { n ->
                Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(10.dp).padding(top = 5.dp).background(if (!n.bool("is_read")) Green else Color.Transparent, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.str("title"), color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        Text(n.str("message"), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        Text(n.str("created_at").take(16), color = Slate, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (!n.isNull("amount")) Text("+" + money(symbol, n.dbl("amount")), color = GreenDark, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ support */
@Composable
fun SupportScreen() {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf("Payment") }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro("Support center", "Tell us what happened and we will get back to you.", Icons.Default.SupportAgent)
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Dropdown("Category", category, listOf("Payment" to "Payment issue", "Video" to "Video issue", "Account" to "Account security", "Other" to "Other")) { category = it }
            InputField("Subject", subject, { subject = it })
            InputField("Describe your issue", message, { message = it }, singleLine = false, minLines = 4)
            PrimaryButton("SEND TICKET", {
                if (subject.isBlank() || message.isBlank()) Ui.notify("Support", "Please fill in all fields.")
                else {
                    busy = true
                    scope.launch {
                        val r = Api.call("POST", "/support/tickets", JSONObject().put("category", category).put("subject", subject.trim()).put("message", message.trim()))
                        busy = false
                        if (r.ok) { subject = ""; message = ""; Ui.notify("Ticket sent", "We received your request."); reload++ } else Ui.notify("Support", r.message)
                    }
                }
            }, loading = busy, icon = Icons.Default.Send)
        }
        SectionTitle("Your tickets")
        Remote(refreshKey = reload, call = { Api.call("GET", "/support/tickets") }) { d, _ ->
            val items = d.list("items")
            if (items.isEmpty()) EmptyState("No tickets yet", Icons.Default.Inbox)
            items.forEach { t ->
                Column(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.str("subject"), color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Pill(t.str("status"), if (t.str("status").equals("Pending", true)) Blue else GreenDark)
                    }
                    Text(t.str("message"), color = Muted, fontSize = 12.sp, maxLines = 3, lineHeight = 17.sp)
                    Text("${t.str("category")} • ${t.str("created_at").take(16)}", color = Slate, fontSize = 10.sp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ settings */
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro("Settings & security", "Language and account protection.", Icons.Default.Settings)
        Remote(call = { Api.call("GET", "/settings") }) { d, _ ->
            var lang by remember { mutableStateOf(d.str("language", "en")) }
            Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
                Dropdown("Language (Muda AI and notices)", lang, listOf("en" to "English", "fr" to "Français")) { v ->
                    lang = v
                    scope.launch {
                        val r = Api.call("POST", "/settings/language", JSONObject().put("language", v))
                        if (r.ok) Ui.notify("Saved", "Language updated.") else Ui.notify("Settings", r.message)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Change password", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            InputField("Current password", current, { current = it }, password = true)
            InputField("New password (min. 6 characters)", newPw, { newPw = it }, password = true)
            PrimaryButton("UPDATE PASSWORD", {
                if (newPw.length < 6) Ui.notify("Password", "The new password must have at least 6 characters.")
                else {
                    busy = true
                    scope.launch {
                        val r = Api.call("POST", "/settings/password", JSONObject().put("current_password", current).put("new_password", newPw))
                        busy = false
                        if (r.ok) { current = ""; newPw = ""; Ui.notify("Password updated", "Your other devices were signed out.") } else Ui.notify("Password", r.message)
                    }
                }
            }, loading = busy, enabled = current.isNotEmpty())
        }
        ContentRow("Sign out of all devices", "Ends every session, including this one", Icons.Default.Security, {
            scope.launch { Api.call("POST", "/auth/logout", JSONObject().put("all", true)); Session.signOut() }
        }, tint = Danger)
        ContentRow("Delete my account", "Opens the website settings", Icons.Default.DeleteForever, { openUrl(ctx, "${Config.SITE}/settings.php") }, tint = Danger)
    }
}
