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
    MoreEntry("Home", "Dashboard", "Your overview", MudaIcons.Home),
    MoreEntry("Tasks", "Daily tasks", "Learn and complete activities", MudaIcons.TaskAlt),
    MoreEntry("Videos", "Videos", "Updates and tutorials", MudaIcons.PlayCircle),
    MoreEntry("Referrals", "My referrals", "Who joined, QR code and earnings", MudaIcons.Groups),
    MoreEntry("Share", "Share & Earn", "Post a campaign and get paid", MudaIcons.Share),
    MoreEntry("ShareApp", "Share the app", "Send MudaPay to friends", MudaIcons.Android),
    MoreEntry("Leaderboard", "Leaderboard", "Top referrers", MudaIcons.EmojiEvents),
    MoreEntry("History", "Transaction history", "Rewards and withdrawals", MudaIcons.ReceiptLong),
    MoreEntry("Market", "Markets", "Live crypto charts and exchange rates", MudaIcons.CandlestickChart),
    MoreEntry("Trade", "Buy & Sell", "Buy and sell crypto with your wallet", MudaIcons.CandlestickChart),
    MoreEntry("Invest", "Invest", "US stocks, ETFs and crypto", MudaIcons.CandlestickChart),
    MoreEntry("Send", "Send money", "Pay a member with their phone number", MudaIcons.NorthEast),
    MoreEntry("Contacts", "Contacts", "Send money to friends from your phone book", MudaIcons.Contacts),
    MoreEntry("Scan", "Scan to pay", "Scan a MudaPay QR code to pay", MudaIcons.QrCodeScanner),
    MoreEntry("Receive", "Receive money", "Your QR code and money received", MudaIcons.QrCode2),
    MoreEntry("Merchant", "Merchant payments", "Business profile, shop QR code, daily sales and payout", MudaIcons.Storefront),
    MoreEntry("Requests", "Payment requests", "Ask a member to pay you, or pay a request", MudaIcons.RequestQuote),
    MoreEntry("Deposit", "Deposit", "Add money with mobile money or crypto", MudaIcons.AddCard),
    MoreEntry("Withdraw", "Withdraw", "Request a payout", MudaIcons.SouthWest),
    MoreEntry("AgentDesk", "Agent desk", "Pay cash-outs and earn commission", MudaIcons.Storefront),
    MoreEntry("Rewards", "Reward rates", "What each activity pays", MudaIcons.Percent),
    MoreEntry("Coach", "Muda AI", "Your assistant and free lessons", MudaIcons.AutoAwesome),
    MoreEntry("Notifications", "Notifications", "Account and reward updates", MudaIcons.Notifications),
    MoreEntry("Support", "Support center", "Help and tickets", MudaIcons.SupportAgent),
    MoreEntry("Profile", "Profile", "Your account details", MudaIcons.Badge),
    MoreEntry("Settings", "Settings & security", "Language and password", MudaIcons.Settings),
    MoreEntry("About", "About MudaPay", "About, terms and privacy", MudaIcons.Info)
)

private val MORE_GROUPS = listOf(
    "Money" to listOf("Market", "Trade", "Invest", "Send", "Contacts", "Scan", "Receive", "Merchant", "Requests", "Deposit", "Withdraw", "AgentDesk"),
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
        // Records today's visit. The WEEKLY bonus is only paid on Sunday, after 7 days in a row ("claimed" = just paid).
        val r = Api.call("POST", "/daily-bonus/claim")
        if (r.ok && r.data.bool("claimed")) {
            Session.balance = r.data.dbl("balance")
            Notify.dailyBonus(r.data.dbl("amount"), Session.balance, null)
            claimMsg = "+" + r.data.dbl("amount").let { money(Session.user?.str("symbol") ?: "", it) } + " " + L("weekly bonus added to your balance!")
            reloadKey++
        } else if (r.ok && r.data.bool("checked_in")) {
            reloadKey++   // today's tick must show up on the card
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
            MemberAvatar(first.ifEmpty { "M" }, size = 44.dp, url = Avatars.mine, filled = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(L(greeting), color = Muted, fontSize = 13.sp)
                Text(first.ifEmpty { "MudaPay" }, color = Dark, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HeaderIcon(MudaIcons.Refresh, "Refresh", { scope.launch { refreshEverything() } })
            Spacer(Modifier.width(8.dp))
            HeaderIcon(MudaIcons.Notifications, "Notifications", { nav("Notifications") }, badge = Session.unread)
            Spacer(Modifier.width(8.dp))
            HeaderIcon(MudaIcons.Menu, "Menu", { nav("More") })
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            BalanceHero(
                label = L("Available balance") + " · " + user.str("country").uppercase(),
                amount = money(symbol, Session.balance),
                value = Session.balance, symbol = symbol,
                onClick = { nav("History") }
            )
            if (claimMsg != null) InfoBanner(claimMsg, GreenDark, MudaIcons.CheckCircle)
            QuickActions(nav)
            MarketHomeCard(nav)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(L("Shortcuts"))
                MenuCard {
                    MenuRow(L("Transaction history"), L("Rewards and withdrawals"), MudaIcons.ReceiptLong, { nav("History") })
                    MenuRow(L("Payment requests"), L("Ask a member to pay you, or pay a request"), MudaIcons.RequestQuote, { nav("Requests") })
                    MenuRow(L("Rewards"), L("Daily tasks, referrals and bonuses"), MudaIcons.CardGiftcard, { nav("RewardsHub") }, trailing = "${tasks.int("done_today")}/${tasks.int("per_day")}", last = true)
                }
            }
            WalletIdRow(user.str("wallet_address"))
        }
    }
}

/* ------------------------------------------------------------------ rewards hub (everything that is not a payment) */
@Composable
fun RewardsScreen(nav: (String) -> Unit) {
    var claimMsg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        TabHeader(L("Rewards"), L("Earn extra with tasks, friends and daily visits"))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Remote(call = { Api.call("GET", "/dashboard") }) { d, _ ->
                val user = d.optJSONObject("user") ?: JSONObject()
                val rewards = d.optJSONObject("rewards") ?: JSONObject()
                val daily = d.optJSONObject("daily_bonus") ?: JSONObject()
                val tasks = d.optJSONObject("tasks") ?: JSONObject()
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    StreakCard(daily, user.str("symbol"), claimMsg)
                    MenuCard {
                        MenuRow(L("Daily tasks"), L("Learn and complete activities"), MudaIcons.TaskAlt, { nav("Tasks") }, trailing = "${tasks.int("done_today")}/${tasks.int("per_day")}")
                        MenuRow(L("Videos"), L("Updates and tutorials"), MudaIcons.PlayCircle, { nav("Videos") })
                        MenuRow(L("Share & Earn"), L("Post a campaign and get paid"), MudaIcons.Share, { nav("Share") })
                        MenuRow(L("My referrals"), L("Who joined, QR code and earnings"), MudaIcons.Groups, { nav("Referrals") }, trailing = "${user.int("referrals_active")}")
                        MenuRow(L("Leaderboard"), L("Top referrers"), MudaIcons.EmojiEvents, { nav("Leaderboard") })
                        MenuRow(L("Reward rates"), L("What each activity pays"), MudaIcons.Percent, { nav("Rewards") }, last = true)
                    }
                    ReferralCard(user, rewards.dbl("referral_percent"), nav)
                    AiCard(nav)
                }
            }
        }
    }
}

@Composable
private fun WalletIdRow(wallet: String) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().card().padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(L("Wallet ID"), color = Muted, fontSize = 12.sp)
            Text(wallet.ifEmpty { "—" }, color = GreenDark, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { copyToClipboard(ctx, "Wallet ID", wallet); Ui.notify("Copied", "Wallet ID copied.") }, enabled = wallet.isNotEmpty()) {
            Icon(MudaIcons.ContentCopy, L("Copy"), tint = GreenDark)
        }
    }
}

@Composable
private fun QuickActions(nav: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().card().padding(horizontal = 6.dp, vertical = 14.dp)) {
        QuickAction("Send", MudaIcons.NorthEast, Modifier.weight(1f)) { nav("Send") }
        QuickAction("Receive", MudaIcons.SouthWest, Modifier.weight(1f)) { nav("Receive") }
        QuickAction("Scan", MudaIcons.QrCodeScanner, Modifier.weight(1f)) { nav("Scan") }
        QuickAction("Deposit", MudaIcons.AddCard, Modifier.weight(1f)) { nav("Deposit") }
        QuickAction("Withdraw", MudaIcons.AccountBalanceWallet, Modifier.weight(1f)) { nav("Withdraw") }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(Green.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = GreenDark, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(L(label), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun StreakCard(daily: JSONObject, symbol: String, claimMsg: String?) {
    val fr = AppLanguage.current == "fr"
    val arr = daily.optJSONArray("week_days")
    val states = List(7) { i -> arr?.optString(i, "upcoming") ?: "upcoming" }
    val paid = daily.bool("paid")
    val onTrack = if (daily.has("on_track")) daily.optBoolean("on_track", true) else true
    val claimed = daily.bool("claimed_today")
    val weekDay = daily.int("week_day").coerceIn(1, 7)
    val reward = daily.dbl("reward")
    val amount = money(symbol, reward)
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconChip(MudaIcons.CalendarMonth, GreenDark, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Weekly login bonus"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    when {
                        paid -> L("Bonus paid this week")
                        !onTrack -> L("A day was missed · no bonus this week")
                        else -> (if (fr) "Jour $weekDay sur 7 · ouvrez MudaPay chaque jour" else "Day $weekDay of 7 · open MudaPay every day")
                    },
                    color = Muted, fontSize = 12.sp
                )
            }
            Pill(
                when { paid -> L("✓ Paid"); !onTrack -> L("Missed"); claimed -> L("✓ Today"); else -> L("Today") },
                when { paid -> GreenDark; !onTrack -> Color(0xFFDC2626); claimed -> GreenDark; else -> Blue }
            )
        }
        if (claimMsg != null) { Spacer(Modifier.height(12.dp)); InfoBanner(claimMsg, GreenDark, MudaIcons.CheckCircle) }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..6) {
                val st = states[i]
                val done = st == "done"
                val missed = st == "missed"
                val isToday = st == "today"
                Column(
                    Modifier.weight(1f)
                        .background(if (done) Green.copy(alpha = .12f) else if (missed) Tint(0xFFFEE2E2) else Soft, RoundedCornerShape(12.dp))
                        .border(1.5.dp, if (isToday) Green else Color.Transparent, RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(L(dayNames[i]), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            done -> "✓"
                            missed -> "✕"
                            i == 6 && onTrack -> money("", reward).trim()
                            else -> "·"
                        },
                        color = if (done) GreenDark else if (missed) Color(0xFFDC2626) else Dark,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                paid -> if (fr) "Votre bonus de $amount a été ajouté à votre solde. Une nouvelle semaine commence lundi." else "Your $amount bonus was added to your balance. A new week starts on Monday."
                !onTrack -> if (fr) "Vous avez manqué un jour, donc pas de bonus cette semaine. Une nouvelle semaine commence lundi : ouvrez MudaPay chaque jour pour gagner $amount." else "You missed a day, so there is no bonus this week. A new week starts on Monday: open MudaPay every day to earn $amount."
                else -> if (fr) "Ouvrez MudaPay chaque jour, du lundi au dimanche, et recevez $amount le dimanche." else "Open MudaPay every day from Monday to Sunday and get $amount on Sunday."
            },
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
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiOrb(size = 36.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Muda AI"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(L("Tip of the day"), color = Muted, fontSize = 12.sp)
            }
        }
        Text(t, color = Ink, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
        PrimaryButton(L("Ask Muda AI"), { nav("Coach") }, icon = MudaIcons.Chat)
        Spacer(Modifier.height(8.dp))
        GhostButton(softCase(L("WRITE MY SHARE CAPTION")), { nav("ShareApp") }, Modifier.fillMaxWidth(), icon = MudaIcons.Edit)
    }
}

@Composable
private fun ReferralCard(user: JSONObject, percent: Double, nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val link = user.str("referral_link")
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(MudaIcons.Groups, GreenDark, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Invite & earn"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(L("Of each friend's first deposit"), color = Muted, fontSize = 12.sp)
            }
            Text(percentText(percent), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Row(
            Modifier.padding(top = 14.dp).fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(link, color = Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { copyToClipboard(ctx, "Invite link", link); Ui.notify("Copied", "Your invite link was copied.") }) {
                Icon(MudaIcons.ContentCopy, L("Copy"), tint = GreenDark)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(L("Share link"), { shareText(ctx, "Join me on MudaPay and start earning: $link") }, Modifier.weight(1f), icon = MudaIcons.Share)
            GhostButton(L("QR code"), { nav("Referrals") }, Modifier.weight(1f), icon = MudaIcons.QrCode2)
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
    var photoDialog by remember { mutableStateOf(false) }
    if (photoDialog) ProfilePhotoDialog { photoDialog = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        TabHeader(L("Profile")) { HeaderIcon(MudaIcons.Menu, "Menu", { nav("More") }) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().card().tappable(CardShape) { nav("Settings") }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                MyAvatarWithBadge(u.str("full_name", "?")) { photoDialog = true }
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
                StatBox(money(u.str("symbol"), Session.balance), "Balance", MudaIcons.AccountBalanceWallet, Modifier.weight(1f)) { nav("History") }
                StatBox("${u.int("referrals_active")}", "Active referrals", MudaIcons.Groups, Modifier.weight(1f)) { nav("Referrals") }
            }
            MenuCard {
                MenuRow("My referrals", "Who joined and what they earned", MudaIcons.Groups, { nav("Referrals") })
                MenuRow("Transaction history", "Rewards and withdrawals", MudaIcons.ReceiptLong, { nav("History") })
                MenuRow("Notifications", "Account updates", MudaIcons.Notifications, { nav("Notifications") })
                MenuRow("Reward rates", "What each activity pays", MudaIcons.Percent, { nav("Rewards") }, last = true)
            }
            MenuCard {
                MenuRow("Support center", "Help and tickets", MudaIcons.SupportAgent, { nav("Support") })
                MenuRow("Settings & security", "Language and password", MudaIcons.Settings, { nav("Settings") })
                MenuRow("About MudaPay", "About, terms and privacy", MudaIcons.Info, { nav("About") })
                MenuRow("Invite friends", "Share your invite link", MudaIcons.Share, { shareText(ctx, "Join me on MudaPay: ${u.str("referral_link")}") }, last = true)
            }
            OutlinedButton(
                onClick = { busy = true; scope.launch { Api.call("POST", "/auth/logout"); busy = false; Session.signOut() } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = BtnShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Danger.copy(alpha = .6f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
            ) { Icon(MudaIcons.Logout, null); Spacer(Modifier.width(8.dp)); Text(L("Log out"), fontWeight = FontWeight.SemiBold) }
        }
    }
}

/* ------------------------------------------------------------------ reward rates + about */
@Composable
fun RewardRatesScreen(nav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro("Reward rates", "What each activity pays in your country.", MudaIcons.Percent)
        Remote(call = { Api.call("GET", "/dashboard") }) { d, _ ->
            val symbol = d.optJSONObject("user")?.str("symbol") ?: ""
            val rw = d.optJSONObject("rewards") ?: JSONObject()
            val sched = (d.optJSONObject("daily_bonus") ?: JSONObject()).doubles("schedule")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ContentRow("Daily task", "Per completed task, up to ${(d.optJSONObject("tasks") ?: JSONObject()).int("per_day")} a day", MudaIcons.TaskAlt, { nav("Tasks") }, trailing = money(symbol, rw.dbl("survey")))
                ContentRow("Share campaign", "After review and approval", MudaIcons.Share, { nav("Share") }, GreenDark, money(symbol, rw.dbl("share")))
                ContentRow("Referral commission", "Of each friend's first deposit", MudaIcons.Groups, { nav("Referrals") }, GreenDark, percentText(rw.dbl("referral_percent")))
                Column(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).tappable { nav("RewardsHub") }.padding(14.dp)) {
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
fun AboutScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro("About MudaPay", "Grow • Learn • Earn", MudaIcons.Info)
        Column(Modifier.fillMaxWidth().card().padding(18.dp)) {
            Brand()
            Spacer(Modifier.height(14.dp))
            Text(L("MudaPay brings learning activities, community participation and account tools into one place."), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text(L("App version 2.5.0"), color = Slate, fontSize = 11.sp)
        }
        ContentRow("Terms of Service", "Read the terms of using MudaPay", MudaIcons.Description, { nav("Terms") })
        ContentRow("Privacy Policy", "How MudaPay handles your data", MudaIcons.PrivacyTip, { nav("Privacy") })
        ContentRow("MudaPay website", Config.SITE.removePrefix("https://"), MudaIcons.Public, { openUrl(ctx, Config.SITE) })
    }
}

/** 5.0 -> "5%", 2.5 -> "2.5%". */
private fun percentText(p: Double): String = if (p == p.toLong().toDouble()) "${p.toLong()}%" else "$p%"
