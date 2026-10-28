package io.mudapay.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

class MoreEntry(val route: String, val title: String, val subtitle: String, val icon: ImageVector)

val MORE_ENTRIES = listOf(
    MoreEntry("Home", "Dashboard", "Your overview", Icons.Default.Home),
    MoreEntry("Tasks", "Daily tasks", "Learn and complete activities", Icons.Default.TaskAlt),
    MoreEntry("Videos", "Videos", "Watch and earn", Icons.Default.PlayCircle),
    MoreEntry("Referrals", "My referrals", "Who joined, QR code and earnings", Icons.Default.Groups),
    MoreEntry("Share", "Share & Earn", "Post a campaign and get paid", Icons.Default.Share),
    MoreEntry("ShareApp", "Share the app", "Send MudaPay to friends", Icons.Default.Android),
    MoreEntry("Leaderboard", "Leaderboard", "Top referrers", Icons.Default.EmojiEvents),
    MoreEntry("History", "Transaction history", "Rewards and withdrawals", Icons.AutoMirrored.Filled.ReceiptLong),
    MoreEntry("Send", "Send money", "Pay a member with their phone number", Icons.Default.NorthEast),
    MoreEntry("Contacts", "Contacts", "Send money to friends from your phone book", Icons.Default.Contacts),
    MoreEntry("Scan", "Scan to pay", "Scan a MudaPay QR code to pay", Icons.Default.QrCodeScanner),
    MoreEntry("Receive", "Receive money", "Your QR code and money received", Icons.Default.QrCode2),
    MoreEntry("Requests", "Payment requests", "Ask a member to pay you, or pay a request", Icons.Default.RequestQuote),
    MoreEntry("Deposit", "Deposit", "Add money with Lumicash or crypto", Icons.Default.AddCard),
    MoreEntry("Withdraw", "Withdraw", "Request a payout", Icons.Default.SouthWest),
    MoreEntry("AgentDesk", "Agent desk", "Pay cash-outs and earn commission", Icons.Default.Storefront),
    MoreEntry("Rewards", "Reward rates", "What each activity pays", Icons.Default.Percent),
    MoreEntry("Coach", "Muda AI", "Your assistant and free lessons", Icons.Default.AutoAwesome),
    MoreEntry("Notifications", "Notifications", "Account and reward updates", Icons.Default.Notifications),
    MoreEntry("Support", "Support center", "Help and tickets", Icons.Default.SupportAgent),
    MoreEntry("Profile", "Profile", "Your account details", Icons.Default.Badge),
    MoreEntry("Settings", "Settings & security", "Language and password", Icons.Default.Settings),
    MoreEntry("About", "About MudaPay", "About, terms and privacy", Icons.Default.Info)
)

private val MORE_GROUPS = listOf(
    "Money" to listOf("Send", "Contacts", "Scan", "Receive", "Requests", "Deposit", "Withdraw", "AgentDesk"),
    "Earn & learn" to listOf("Home", "Tasks", "Videos", "Referrals", "Share", "ShareApp", "Leaderboard", "Rewards", "History"),
    "Account & help" to listOf("Coach", "Notifications", "Support", "Profile", "Settings", "About")
)

@Composable
fun MoreScreen(nav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        MORE_GROUPS.forEach { (group, routes) ->
            val entries = routes.mapNotNull { r -> MORE_ENTRIES.firstOrNull { it.route == r } }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(L(group))
                MenuCard {
                    entries.forEachIndexed { i, e -> MenuRow(L(e.title), L(e.subtitle), e.icon, { nav(e.route) }, last = i == entries.lastIndex) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ dashboard */
@Composable
fun DashboardScreen(nav: (String) -> Unit) {
    var claimMsg by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Ui.refreshTick) {
        val r = Api.call("POST", "/daily-bonus/claim")
        if (r.ok && r.data.bool("claimed")) {
            Session.balance = r.data.dbl("balance")
            Notify.dailyBonus(r.data.dbl("amount"), Session.balance, if (r.data.has("streak")) r.data.int("streak") else null)
            claimMsg = "+" + r.data.dbl("amount").let { money(Session.user?.str("symbol") ?: "", it) } + " daily bonus added to your balance!"
            reloadKey++
        }
    }
    Remote(refreshKey = reloadKey, call = { Api.call("GET", "/dashboard") }) { d, _ ->
        LaunchedEffect(d) {
            d.optJSONObject("user")?.let { Session.updateUser(it) }
            Session.unread = d.int("unread_notifications")
        }
        DashboardContent(d, claimMsg, nav)
    }
}

@Composable
private fun DashboardContent(d: JSONObject, claimMsg: String?, nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val user = d.optJSONObject("user") ?: JSONObject()
    val symbol = user.str("symbol")
    val rewards = d.optJSONObject("rewards") ?: JSONObject()
    val daily = d.optJSONObject("daily_bonus") ?: JSONObject()
    val tasks = d.optJSONObject("tasks") ?: JSONObject()
    val first = user.str("full_name").trim().substringBefore(" ")
    val scope = rememberCoroutineScope()
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        // top bar: who is here, and the three header actions
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Gradient, CircleShape), contentAlignment = Alignment.Center) {
                Text(first.take(1).uppercase().ifEmpty { "M" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(L(greeting), color = Muted, fontSize = 13.sp)
                Text(first.ifEmpty { "MudaPay" }, color = Dark, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HeaderIcon(Icons.Default.Refresh, "Refresh", { scope.launch { refreshEverything() } })
            Spacer(Modifier.width(8.dp))
            HeaderIcon(Icons.Default.Notifications, "Notifications", { nav("Notifications") }, badge = Session.unread)
            Spacer(Modifier.width(8.dp))
            HeaderIcon(Icons.Default.Menu, "Menu", { nav("More") })
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            BalanceHero(
                label = L("Total earnings") + " (" + user.str("country").uppercase() + ")",
                amount = money(symbol, Session.balance)
            )
            QuickActions(nav)
            StreakCard(daily, symbol, claimMsg)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(L("Earn & explore"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatBox("${tasks.int("done_today")}/${tasks.int("per_day")}", "Tasks today", Icons.Default.TaskAlt, Modifier.weight(1f)) { nav("Tasks") }
                    StatBox("${user.int("referrals_active")}", "Active referrals", Icons.Default.Groups, Modifier.weight(1f)) { nav("Referrals") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatBox("Videos", "Watch & earn", Icons.Default.PlayCircle, Modifier.weight(1f)) { nav("Videos") }
                    StatBox("Share & Earn", "Post & get paid", Icons.Default.Share, Modifier.weight(1f)) { nav("Share") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatBox("Leaderboard", "Top referrers", Icons.Default.EmojiEvents, Modifier.weight(1f)) { nav("Leaderboard") }
                    StatBox("History", "Transactions", Icons.AutoMirrored.Filled.ReceiptLong, Modifier.weight(1f)) { nav("History") }
                }
            }
            AiCard(nav)
            ReferralCard(user, rewards.dbl("referral"), symbol, nav)
            WalletIdRow(user.str("wallet_address"))
        }
    }
}

@Composable
private fun WalletIdRow(wallet: String) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().card().padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(softCase(L("MUDAPAY DIGITAL WALLET ID")), color = Muted, fontSize = 12.sp)
            Text(wallet.ifEmpty { "—" }, color = GreenDark, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { copyToClipboard(ctx, "Wallet ID", wallet); Ui.notify("Copied", "Wallet ID copied.") }, enabled = wallet.isNotEmpty()) {
            Icon(Icons.Default.ContentCopy, L("Copy"), tint = GreenDark)
        }
    }
}

@Composable
private fun QuickActions(nav: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        QuickAction("Send", Icons.Default.NorthEast, GreenDark, Modifier.weight(1f)) { nav("Send") }
        QuickAction("Receive", Icons.Default.QrCode2, Blue, Modifier.weight(1f)) { nav("Receive") }
        QuickAction("Scan", Icons.Default.QrCodeScanner, Color(0xFF0891B2), Modifier.weight(1f)) { nav("Scan") }
        QuickAction("Deposit", Icons.Default.AddCard, Color(0xFF7C3AED), Modifier.weight(1f)) { nav("Deposit") }
        QuickAction("Invite", Icons.Default.Groups, Color(0xFFD97706), Modifier.weight(1f)) { nav("Referrals") }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).card(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(L(label), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun StreakCard(daily: JSONObject, symbol: String, claimMsg: String?) {
    val streak = daily.int("streak")
    val claimed = daily.bool("claimed_today")
    val schedule = daily.doubles("schedule")
    val cycle = if (streak == 0) 0 else ((streak - 1) % 7) + 1
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Default.LocalFireDepartment, Color(0xFFD97706), 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(L("🔥 Daily login streak").removePrefix("🔥").trim(), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(if (streak > 0) "Day $streak · keep it going!" else "Log in every day to earn more", color = Muted, fontSize = 12.sp)
            }
            Pill(if (claimed) "✓ Claimed" else "Today", if (claimed) GreenDark else Blue)
        }
        if (claimMsg != null) { Spacer(Modifier.height(12.dp)); InfoBanner(claimMsg, GreenDark, Icons.Default.CheckCircle) }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..7) {
                val done = i <= cycle
                val next = !done && i == cycle + 1
                val amount = schedule.getOrNull(i - 1) ?: 0.0
                Column(
                    Modifier.weight(1f).background(if (done) Green.copy(alpha = .12f) else Soft, RoundedCornerShape(12.dp))
                        .border(1.5.dp, if (next) Gold else Color.Transparent, RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(L("D$i"), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Text(if (done) "✓" else money("", amount).trim(), color = if (done) GreenDark else Dark, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val nextReward = daily.dbl("next_reward")
        Text(
            if (claimed) "Come back tomorrow for ${money(symbol, nextReward)}. Miss a day and the streak restarts." else "Today's bonus: ${money(symbol, nextReward)}",
            color = Muted, fontSize = 12.sp, lineHeight = 17.sp
        )
    }
}

@Composable
private fun AiCard(nav: (String) -> Unit) {
    val tip by produceState<String?>(null) {
        val r = Api.call("GET", "/coach/context")
        value = if (r.ok) r.data.str("tip_of_day") else null
    }
    val t = tip ?: return
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().background(Dark, shape).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiOrb(size = 36.dp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(L("Muda AI"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(L("Your MudaPay assistant + free lessons"), color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .07f), RoundedCornerShape(16.dp)).padding(14.dp)) {
            Text(L("Tip of the day"), color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(t, color = Color(0xFFE2E8F0), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("ASK MUDA AI", { nav("Coach") }, icon = Icons.Default.Chat)
        Spacer(Modifier.height(8.dp))
        GhostButton(softCase(L("WRITE MY SHARE CAPTION")), { nav("ShareApp") }, Modifier.fillMaxWidth(), icon = Icons.Default.Edit, onDark = true)
    }
}

@Composable
private fun ReferralCard(user: JSONObject, amount: Double, symbol: String, nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val link = user.str("referral_link")
    Column(Modifier.fillMaxWidth().background(Dark, RoundedCornerShape(24.dp)).padding(18.dp)) {
        Text(L("Referral Commission"), color = Color.White.copy(alpha = .75f), fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Text(money(symbol, amount), color = Gold, fontWeight = FontWeight.Bold, fontSize = 30.sp, modifier = Modifier.padding(top = 2.dp))
        Text(L("Per active referral in ${countryName(user.str("country"))}"), color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
        Row(
            Modifier.padding(top = 16.dp).fillMaxWidth().background(Color.White.copy(alpha = .08f), RoundedCornerShape(14.dp)).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(link, color = Color(0xFFCBD5E1), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { copyToClipboard(ctx, "Invite link", link); Ui.notify("Copied", "Your invite link was copied.") }) {
                Icon(Icons.Default.ContentCopy, L("Copy"), tint = Color.White)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("SHARE LINK", { shareText(ctx, "Join me on MudaPay and start earning: $link") }, Modifier.weight(1f), icon = Icons.Default.Share, onDark = true)
            GhostButton(L("Share the app"), { nav("ShareApp") }, Modifier.weight(1f), icon = Icons.Default.Android, onDark = true)
        }
        Spacer(Modifier.height(10.dp))
        GhostButton("MY REFERRALS & QR CODE", { nav("Referrals") }, Modifier.fillMaxWidth(), icon = Icons.Default.QrCode2, onDark = true)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Gold.copy(alpha = .16f)).clickable { nav("Leaderboard") }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.EmojiEvents, null, tint = Gold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(L("Leaderboard · top 1 wins big"), color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = Gold, modifier = Modifier.size(20.dp))
        }
    }
}

/* ------------------------------------------------------------------ profile */
@Composable
fun ProfileScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u = Session.user ?: JSONObject()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        TabHeader(L("Profile")) { HeaderIcon(Icons.Default.Menu, "Menu", { nav("More") }) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().card().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).background(Gradient, CircleShape), contentAlignment = Alignment.Center) {
                    Text(u.str("full_name", "?").trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(u.str("full_name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(u.str("phone"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 1.dp))
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Flag(u.str("country", "bi"), 18.dp); Spacer(Modifier.width(6.dp))
                        Text(countryName(u.str("country")), color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatBox(money(u.str("symbol"), Session.balance), "Balance", Icons.Default.AccountBalanceWallet, Modifier.weight(1f)) { nav("History") }
                StatBox("${u.int("referrals_active")}", "Active referrals", Icons.Default.Groups, Modifier.weight(1f)) { nav("Referrals") }
            }
            MenuCard {
                MenuRow("My referrals", "Who joined and what they earned", Icons.Default.Groups, { nav("Referrals") })
                MenuRow("Transaction history", "Rewards and withdrawals", Icons.AutoMirrored.Filled.ReceiptLong, { nav("History") })
                MenuRow("Notifications", "Account updates", Icons.Default.Notifications, { nav("Notifications") })
                MenuRow("Reward rates", "What each activity pays", Icons.Default.Percent, { nav("Rewards") }, last = true)
            }
            MenuCard {
                MenuRow("Support center", "Help and tickets", Icons.Default.SupportAgent, { nav("Support") })
                MenuRow("Settings & security", "Language and password", Icons.Default.Settings, { nav("Settings") })
                MenuRow("About MudaPay", "About, terms and privacy", Icons.Default.Info, { nav("About") })
                MenuRow("Invite friends", "Share your invite link", Icons.Default.Share, { shareText(ctx, "Join me on MudaPay: ${u.str("referral_link")}") }, last = true)
            }
            OutlinedButton(
                onClick = { busy = true; scope.launch { Api.call("POST", "/auth/logout"); busy = false; Session.signOut() } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = BtnShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Danger.copy(alpha = .6f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
            ) { Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text(L("Log out"), fontWeight = FontWeight.SemiBold) }
        }
    }
}

/* ------------------------------------------------------------------ reward rates + about */
@Composable
fun RewardRatesScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro("Reward rates", "What each activity pays in your country.", Icons.Default.Percent)
        Remote(call = { Api.call("GET", "/dashboard") }) { d, _ ->
            val symbol = d.optJSONObject("user")?.str("symbol") ?: ""
            val rw = d.optJSONObject("rewards") ?: JSONObject()
            val sched = (d.optJSONObject("daily_bonus") ?: JSONObject()).doubles("schedule")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ContentRow("Daily task", "Per completed task, up to ${(d.optJSONObject("tasks") ?: JSONObject()).int("per_day")} a day", Icons.Default.TaskAlt, {}, trailing = money(symbol, rw.dbl("survey")))
                ContentRow("Watch a video", "Once per video", Icons.Default.PlayCircle, {}, Blue, money(symbol, rw.dbl("video")))
                ContentRow("Like a video", "Once per video", Icons.Default.Favorite, {}, Color(0xFFDB2777), money(symbol, rw.dbl("like")))
                ContentRow("Share campaign", "After review and approval", Icons.Default.Share, {}, GreenDark, money(symbol, rw.dbl("share")))
                ContentRow("Active referral", "For each friend who activates", Icons.Default.Groups, {}, Color(0xFFD97706), money(symbol, rw.dbl("referral")))
                Column(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp)) {
                    Text(L("Daily login bonus"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    sched.forEachIndexed { i, a -> Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(L("Day ${i + 1}"), color = Muted, fontSize = 13.sp); Text(money(symbol, a), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp) } }
                }
                InfoBanner("Rates are set by MudaPay for each country and can change. These values come live from the server.")
            }
        }
    }
}

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro("About MudaPay", "Grow • Learn • Earn", Icons.Default.Info)
        Column(Modifier.fillMaxWidth().card().padding(18.dp)) {
            Brand()
            Spacer(Modifier.height(14.dp))
            Text(L("MudaPay brings learning activities, community participation and account tools into one place."), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text(L("App version 2.5.0"), color = Slate, fontSize = 11.sp)
        }
        ContentRow("Terms of Service", "Opens the MudaPay website", Icons.Default.Description, { openUrl(ctx, "${Config.SITE}/term.php") })
        ContentRow("Privacy Policy", "Opens the MudaPay website", Icons.Default.PrivacyTip, { openUrl(ctx, "${Config.SITE}/privacy.php") })
        ContentRow("MudaPay website", Config.SITE.removePrefix("https://"), Icons.Default.Public, { openUrl(ctx, Config.SITE) })
    }
}
