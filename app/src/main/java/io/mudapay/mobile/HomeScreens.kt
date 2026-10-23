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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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
    MoreEntry("Leaderboard", "Leaderboard", "Top referrers", Icons.Default.EmojiEvents),
    MoreEntry("History", "Transaction history", "Rewards and withdrawals", Icons.AutoMirrored.Filled.ReceiptLong),
    MoreEntry("Withdraw", "Withdraw", "Request a payout", Icons.Default.SouthWest),
    MoreEntry("Rewards", "Reward rates", "What each activity pays", Icons.Default.Percent),
    MoreEntry("Coach", "Muda AI", "Your assistant and free lessons", Icons.Default.AutoAwesome),
    MoreEntry("Notifications", "Notifications", "Account and reward updates", Icons.Default.Notifications),
    MoreEntry("Support", "Support center", "Help and tickets", Icons.Default.SupportAgent),
    MoreEntry("Profile", "Profile", "Your account details", Icons.Default.Badge),
    MoreEntry("Settings", "Settings & security", "Language and password", Icons.Default.Settings),
    MoreEntry("About", "About MudaPay", "About, terms and privacy", Icons.Default.Info)
)

@Composable
fun MoreScreen(nav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MORE_ENTRIES.forEach { ContentRow(it.title, it.subtitle, it.icon, { nav(it.route) }) }
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ dashboard */
@Composable
fun DashboardScreen(nav: (String) -> Unit) {
    var claimMsg by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val r = Api.call("POST", "/daily-bonus/claim")
        if (r.ok && r.data.bool("claimed")) {
            Session.balance = r.data.dbl("balance")
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GradientHeader(bottomPadding = 62.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Brand(light = true)
                Row {
                    IconButton(onClick = { nav("Notifications") }) {
                        BadgedBox(badge = { if (Session.unread > 0) Badge(containerColor = Gold, contentColor = Dark) { Text(Session.unread.toString()) } }) {
                            Icon(Icons.Default.Notifications, null, tint = Color.White)
                        }
                    }
                    IconButton(onClick = { nav("More") }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("TOTAL EARNINGS (${user.str("country").uppercase()})", color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(money(symbol, Session.balance), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
            Text("Hello, $first! 👋", color = Color.White.copy(alpha = .95f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        Column(Modifier.offset(y = (-40).dp).padding(horizontal = 15.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StreakCard(daily, symbol, claimMsg)
            AiCard(nav)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatBox("${tasks.int("done_today")}/${tasks.int("per_day")}", "Tasks today", Icons.Default.TaskAlt, Modifier.weight(1f)) { nav("Tasks") }
                StatBox("${user.int("referrals_active")}", "Active referrals", Icons.Default.Groups, Modifier.weight(1f)) { nav("Referrals") }
            }
            ReferralCard(user, rewards.dbl("referral"), symbol, nav)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatBox("Videos", "Watch & earn", Icons.Default.PlayCircle, Modifier.weight(1f)) { nav("Videos") }
                StatBox("History", "Transactions", Icons.AutoMirrored.Filled.ReceiptLong, Modifier.weight(1f)) { nav("History") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatBox("Leaderboard", "Top referrers", Icons.Default.EmojiEvents, Modifier.weight(1f)) { nav("Leaderboard") }
                StatBox("Share & Earn", "Post & get paid", Icons.Default.Share, Modifier.weight(1f)) { nav("Share") }
            }
            Column(Modifier.fillMaxWidth().card(RoundedCornerShape(24.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MUDAPAY DIGITAL WALLET ID", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text(user.str("wallet_address", "—"), color = Green, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
                PrimaryButton("COPY WALLET ID", { copyToClipboard(ctx, "Wallet ID", user.str("wallet_address")); Ui.notify("Copied", "Wallet ID copied.") }, color = Dark, enabled = user.str("wallet_address").isNotEmpty())
            }
        }
    }
}

@Composable
private fun StreakCard(daily: JSONObject, symbol: String, claimMsg: String?) {
    val streak = daily.int("streak")
    val claimed = daily.bool("claimed_today")
    val schedule = daily.doubles("schedule")
    val cycle = if (streak == 0) 0 else ((streak - 1) % 7) + 1
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(24.dp)).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🔥 Daily login streak", color = Dark, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(if (streak > 0) "Day $streak · keep it going!" else "Log in every day to earn more", color = Muted, fontSize = 12.sp)
            }
            Pill(if (claimed) "✓ Claimed" else "Today", if (claimed) GreenDark else Blue)
        }
        if (claimMsg != null) { Spacer(Modifier.height(10.dp)); InfoBanner(claimMsg, GreenDark, Icons.Default.CheckCircle) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 1..7) {
                val done = i <= cycle
                val next = !done && i == cycle + 1
                val amount = schedule.getOrNull(i - 1) ?: 0.0
                Column(
                    Modifier.weight(1f).background(if (done) Green.copy(alpha = .12f) else Page, RoundedCornerShape(12.dp))
                        .border(1.5.dp, if (next) Gold else Color.Transparent, RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("D$i", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(if (done) "✓" else money("", amount).trim(), color = if (done) GreenDark else Dark, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val nextReward = daily.dbl("next_reward")
        Text(
            if (claimed) "Come back tomorrow for ${money(symbol, nextReward)}. Miss a day and the streak restarts." else "Today's bonus: ${money(symbol, nextReward)}",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
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
    Column(Modifier.fillMaxWidth().background(Dark, RoundedCornerShape(24.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Gradient, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Muda AI", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text("Your MudaPay assistant + free lessons", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .08f), RoundedCornerShape(14.dp)).padding(12.dp)) {
            Text("Tip of the day", color = Gold, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
            Text(t, color = Color(0xFFE2E8F0), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("ASK MUDA AI", { nav("Coach") }, icon = Icons.Default.Chat)
    }
}

@Composable
private fun ReferralCard(user: JSONObject, amount: Double, symbol: String, nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val link = user.str("referral_link")
    Column(Modifier.fillMaxWidth().background(Dark, RoundedCornerShape(24.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Referral Commission", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(money(symbol, amount), color = Gold, fontWeight = FontWeight.Black, fontSize = 29.sp, modifier = Modifier.padding(vertical = 6.dp))
        Text("Per active referral in ${countryName(user.str("country"))}", color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
        Text(link, color = Color(0xFFCBD5E1), fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth().background(Color.White.copy(alpha = .08f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = .3f), RoundedCornerShape(12.dp)).padding(14.dp))
        PrimaryButton("COPY INVITE LINK", { copyToClipboard(ctx, "Invite link", link); Ui.notify("Copied", "Your invite link was copied.") })
        Spacer(Modifier.height(8.dp))
        PrimaryButton("SHARE INVITE LINK", { shareText(ctx, "Join me on MudaPay and start earning: $link") }, color = Color.White.copy(alpha = .12f), icon = Icons.Default.Share)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("MY REFERRALS & QR CODE", { nav("Referrals") }, color = Color.White.copy(alpha = .12f), icon = Icons.Default.QrCode2)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("🏆 LEADERBOARD · TOP 1 WINS BIG", { nav("Leaderboard") }, color = Gold, contentColor = Dark)
    }
}

/* ------------------------------------------------------------------ profile */
@Composable
fun ProfileScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u = Session.user ?: JSONObject()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GradientHeader(bottomPadding = 60.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Brand(light = true)
                IconButton(onClick = { nav("More") }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(66.dp).background(Color.White.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(u.str("full_name", "?").trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(u.str("full_name"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
                    Text(u.str("phone"), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Flag(u.str("country", "bi"), 18.dp); Spacer(Modifier.width(6.dp))
                        Text(countryName(u.str("country")), color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
                    }
                }
            }
        }
        Column(Modifier.offset(y = (-36).dp).padding(horizontal = 15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatBox(money(u.str("symbol"), Session.balance), "Balance", Icons.Default.AccountBalanceWallet, Modifier.weight(1f)) { nav("History") }
                StatBox("${u.int("referrals_active")}", "Active referrals", Icons.Default.Groups, Modifier.weight(1f)) { nav("Referrals") }
            }
            ContentRow("My referrals", "Who joined and what they earned", Icons.Default.Groups, { nav("Referrals") })
            ContentRow("Transaction history", "Rewards and withdrawals", Icons.AutoMirrored.Filled.ReceiptLong, { nav("History") })
            ContentRow("Notifications", "Account updates", Icons.Default.Notifications, { nav("Notifications") })
            ContentRow("Reward rates", "What each activity pays", Icons.Default.Percent, { nav("Rewards") })
            ContentRow("Support center", "Help and tickets", Icons.Default.SupportAgent, { nav("Support") })
            ContentRow("Settings & security", "Language and password", Icons.Default.Settings, { nav("Settings") })
            ContentRow("About MudaPay", "About, terms and privacy", Icons.Default.Info, { nav("About") })
            ContentRow("Invite friends", "Share your invite link", Icons.Default.Share, { shareText(ctx, "Join me on MudaPay: ${u.str("referral_link")}") })
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { busy = true; scope.launch { Api.call("POST", "/auth/logout"); busy = false; Session.signOut() } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = BtnShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Danger), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
            ) { Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Log out", fontWeight = FontWeight.Bold) }
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
                    Text("Daily login bonus", color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    sched.forEachIndexed { i, a -> Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Day ${i + 1}", color = Muted, fontSize = 13.sp); Text(money(symbol, a), color = GreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) } }
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
            Text("MudaPay brings learning activities, community participation and account tools into one place.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text("App version 2.1.0", color = Slate, fontSize = 11.sp)
        }
        ContentRow("Terms of Service", "Opens the MudaPay website", Icons.Default.Description, { openUrl(ctx, "${Config.SITE}/term.php") })
        ContentRow("Privacy Policy", "Opens the MudaPay website", Icons.Default.PrivacyTip, { openUrl(ctx, "${Config.SITE}/privacy.php") })
        ContentRow("MudaPay website", Config.SITE.removePrefix("https://"), Icons.Default.Public, { openUrl(ctx, Config.SITE) })
    }
}
