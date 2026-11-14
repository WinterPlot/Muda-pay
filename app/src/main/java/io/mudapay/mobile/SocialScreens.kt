package io.mudapay.mobile

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
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
                        if (status == "approved") { Session.balance = r.data.dbl("balance"); Notify.shareApproved(r.data.dbl("reward"), Session.balance); Ui.notify("Approved 🎉", "+${money(symbol, r.data.dbl("reward"))} added to your balance.") }
                        else if (status == "rejected") { Notify.shareRejected(r.data.str("reason", "We could not verify your post.")); Ui.notify("Not approved", r.data.str("reason", "We could not verify your post.")) }
                        else { Notify.shareReceived(); Ui.notify("Received", "Your screenshot is being reviewed.") }
                        reload++
                    } else Ui.notify("Screenshot", r.message)
                }
                uploading = 0
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageIntro("Share & Earn", "Post a campaign, send us a screenshot and get paid after review.", MudaIcons.Share)
        Remote(refreshKey = reload, call = { Api.call("GET", "/campaigns") }) { d, _ ->
            val sym = d.str("symbol")
            InfoBanner("Reward: ${money(sym, d.dbl("reward"))} per approved campaign. Up to ${d.int("max_attempts")} attempts per campaign and ${d.int("daily_upload_limit")} uploads a day. Include your invite link in the post.")
            val items = d.list("items")
            if (items.isEmpty()) EmptyState("No campaigns right now", MudaIcons.Campaign, "Check back soon.")
            items.forEach { c ->
                val sub = c.optJSONObject("submission")
                val status = sub?.str("status") ?: ""
                val attempts = sub?.int("attempts") ?: 0
                val canUpload = status == "" || (status == "rejected" && attempts < d.int("max_attempts"))
                Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteImage(c.str("image_url"), Modifier.fillMaxWidth().height(200.dp).background(Line, RoundedCornerShape(16.dp)), ContentScale.Fit)
                    Text(c.str("title"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(c.str("caption"), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                    when (status) {
                        "approved" -> Pill("Approved ✓", GreenDark)
                        "pending" -> Pill("In review", Blue)
                        "rejected" -> { Pill("Rejected", Danger); sub?.str("reason")?.takeIf { it.isNotEmpty() }?.let { InfoBanner(it, Danger) } }
                    }
                    if (status != "approved") {
                        PrimaryButton("SHARE CAPTION + LINK", { shareText(ctx, c.str("caption") + "\n" + d.str("referral_link")) }, color = Dark, icon = MudaIcons.Share)
                        if (canUpload) PrimaryButton(if (status == "rejected") "UPLOAD A NEW SCREENSHOT" else "UPLOAD SCREENSHOT", { target = c.int("id"); picker.launch("image/*") },
                            loading = uploading == c.int("id"), enabled = uploading == 0, icon = MudaIcons.UploadFile)
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
        PageIntro("Leaderboard", "Top referrers. Rank 1 wins the monthly prize.", MudaIcons.EmojiEvents)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("month" to "This month", "all" to "All time").forEach { (k, label) ->
                FilterChip(selected = period == k, onClick = { period = k }, label = { Text(label, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(containerColor = Soft, labelColor = Ink, selectedContainerColor = Green, selectedLabelColor = Color.White), border = null, shape = RoundedCornerShape(50))
            }
        }
        Remote(refreshKey = period, call = { Api.call("GET", "/leaderboard", query = mapOf("period" to period)) }) { d, _ ->
            val symbol = Session.user?.str("symbol") ?: ""
            Column(Modifier.fillMaxWidth().heroCard().padding(18.dp)) {
                Text(softCase(L("MONTHLY PRIZE")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                Text(money(symbol, d.dbl("prize")), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(L("Reach at least ${d.int("min_referrals_to_win")} referrals to qualify."), color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
            }
            d.optJSONObject("last_winner")?.let { w ->
                InfoBanner("Last winner: ${w.str("user_name")} (${countryName(w.str("country"))}) with ${w.int("referrals")} referrals won ${money(symbol, w.dbl("prize"))}.", Blue, MudaIcons.EmojiEvents)
            }
            val items = d.list("items")
            if (items.isEmpty()) EmptyState("No ranking yet", MudaIcons.Leaderboard, "Invite friends to appear here.")
            var picked by remember { mutableStateOf<JSONObject?>(null) }
            picked?.let { r ->
                val rk = r.int("rank")
                val leader = items.firstOrNull()?.int("referrals") ?: 0
                val behind = (leader - r.int("referrals")).coerceAtLeast(0)
                DetailDialog(DetailData(
                    r.str("name") + if (r.bool("is_me")) " (you)" else "", MudaIcons.EmojiEvents, if (rk == 1) Color(0xFFB7791F) else GreenDark,
                    headline = "#$rk", subtitle = countryName(r.str("country", "bi")),
                    rows = listOf(
                        "Referrals" to "${r.int("referrals")}",
                        "Country" to countryName(r.str("country", "bi")),
                        "Behind the leader" to if (rk > 1) "$behind" else "",
                        "Monthly prize" to if (rk == 1) money(symbol, d.dbl("prize")) else ""
                    ),
                    hint = if (rk == 1) "Rank 1 wins the monthly prize." else "Invite more friends to climb the ranking. Rank 1 wins the monthly prize."
                )) { picked = null }
            }
            items.forEach { r ->
                val me = r.bool("is_me")
                val rank = r.int("rank")
                Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp), if (me) Green.copy(alpha = .1f).compositeOver(Color.White) else Color.White).tappable { picked = r }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(when (rank) { 1 -> Gold; 2 -> Color(0xFFCBD5E1); 3 -> Color(0xFFF1B27A); else -> Page }, CircleShape), contentAlignment = Alignment.Center) {
                        Text(L("$rank"), color = Dark, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Flag(r.str("country", "bi"), 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(r.str("name") + if (me) " (you)" else "", color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(L("${r.int("referrals")} refs"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
fun NotificationsScreen(nav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageIntro("Notifications", "Account updates, reward notices and announcements.", MudaIcons.Notifications)
        Remote(call = { Api.call("GET", "/notifications") }) { d, _ ->
            val symbol = Session.user?.str("symbol") ?: ""
            LaunchedEffect(Unit) {   // like the website: opening the list marks everything as read
                if (d.int("unread") > 0) { Api.call("POST", "/notifications/read-all"); Session.unread = 0 }
            }
            val items = d.list("items")
            if (items.isEmpty()) EmptyState("No notifications yet", MudaIcons.NotificationsNone)
            items.forEach { n ->
                val credit = n.str("type") == "credit"
                val tone = if (credit) GreenDark else Blue
                val open = {
                    Ui.openMessage = InboxMsg(
                        n.str("type"), n.str("title").ifEmpty { "MudaPay" }, n.str("message"),
                        listOfNotNull(if (n.isNull("amount")) null else "Added to your balance: ${money(symbol, n.dbl("amount"))}"),
                        n.str("created_at").take(16), null
                    )
                    nav(InboxMsg.ROUTE)
                }
                Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).clickable { open() }.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(42.dp).background(tone.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (credit) MudaIcons.AddCircle else MudaIcons.Notifications, null, tint = tone, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.str("title"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                            if (!n.bool("is_read")) { Spacer(Modifier.width(8.dp)); Pill("NEW", Green) }
                        }
                        Text(n.str("message"), color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(n.str("created_at").take(16), color = Slate, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (!n.isNull("amount")) Text(L("+") + money(symbol, n.dbl("amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ settings */
/** Back inside Settings: devices and limits return to Security, every other page to the Settings list. */
fun settingsBack() { Ui.settingsPage = if (Ui.settingsPage == "devices" || Ui.settingsPage == "limits") "security" else null }

fun settingsPageTitle(k: String): String = when (k) {
    "preferences" -> "Preferences"
    "security" -> "Security"
    "notifications" -> "Notifications"
    "account" -> "Account"
    "devices" -> "Connected devices"
    "limits" -> "Transaction limits"
    else -> "Settings"
}

@Composable
fun SettingsScreen(nav: (String) -> Unit) {
    val page = Ui.settingsPage
    androidx.activity.compose.BackHandler(enabled = page != null) { settingsBack() }
    DisposableEffect(Unit) { onDispose { Ui.settingsPage = null } }
    when (page) {
        "preferences" -> SettingsPreferences()
        "security" -> SettingsSecurity()
        "devices" -> SettingsDevices()
        "limits" -> SettingsLimits()
        "notifications" -> SettingsPane { PushSettings() }
        "account" -> SettingsPane { DeleteAccountSection() }
        else -> SettingsHome(nav)
    }
}

@Composable
fun SettingsPane(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun SettingsHome(nav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 28.dp)) {
        ListRow(L("Contacts"), MudaIcons.Contacts, { nav("Contacts") })
        ListRow(L("Connect external wallet"), MudaIcons.Link, { nav("ExternalWallet") })
        ListRow(L("Share the app"), MudaIcons.Share, { nav("ShareApp") })
        ListRow(L("Support center"), MudaIcons.SupportAgent, { nav("Support") })
        ListDivider()
        ListRow(L("Preferences"), MudaIcons.Settings, { Ui.settingsPage = "preferences" })
        ListRow(L("Security"), MudaIcons.Lock, { Ui.settingsPage = "security" })
        ListRow(L("Notifications"), MudaIcons.Notifications, { Ui.settingsPage = "notifications" })
        ListRow(L("Account"), MudaIcons.Badge, { Ui.settingsPage = "account" })
        ListDivider()
        ListRow(L("About MudaPay"), MudaIcons.Info, { nav("About") })
    }
}

@Composable
private fun SettingsPreferences() {
    val scope = rememberCoroutineScope()
    SettingsPane {
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
        AppearanceSettings()
        AppLanguageSettings()
        AssistantSettings()
        LowDataSettings()
    }
}

@Composable
private fun SettingsSecurity() {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val hasPassword = Session.user?.optBoolean("has_password", true) ?: true
    SettingsPane {
        SecurityOverview()   // security check, connected devices, transaction limits (KYC), recent security activity
        EmailVerificationSettings()
        AppLockSettings()
        TransactionPinSettings()
        SignInMethods()
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (hasPassword) "Change password" else "Set a password", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (!hasPassword) Text(L("You signed up with Google. A password lets you also log in with your phone number."), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            if (hasPassword) InputField("Current password", current, { current = it }, password = true)
            InputField("New password (min. 10 characters)", newPw, { newPw = it }, password = true)
            PrimaryButton(if (hasPassword) "UPDATE PASSWORD" else "SET PASSWORD", {
                if (newPw.length < 10) Ui.notify("Password", "The new password must have at least 10 characters.")
                else {
                    busy = true
                    scope.launch {
                        val body = JSONObject().put("new_password", newPw)
                        if (hasPassword) body.put("current_password", current)
                        val r = Api.call("POST", "/settings/password", body)
                        busy = false
                        if (r.ok) {
                            current = ""; newPw = ""
                            if (!hasPassword) Api.call("GET", "/me").let { me -> if (me.ok) Session.updateUser(me.data) }
                            Notify.passwordChanged(!hasPassword)
                            Ui.notify(if (hasPassword) "Password updated" else "Password set", "Your other devices were signed out.")
                        } else Ui.notify("Password", r.message)
                    }
                }
            }, loading = busy, enabled = !hasPassword || current.isNotEmpty())
        }
        ContentRow("Sign out of all devices", "Ends every session, including this one", MudaIcons.Security, {
            scope.launch { Api.call("POST", "/auth/logout", JSONObject().put("all", true)); Session.signOut() }
        }, tint = Danger)
    }
}
