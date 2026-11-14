package io.mudapay.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Page.toArgb()))
        window.statusBarColor = AColor.TRANSPARENT
        window.navigationBarColor = AColor.TRANSPARENT
        // not again after a rotation / process restore, nor when the app is reopened from the recent-apps list
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) routeFrom(intent)
        setContent { MudaPayApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)
    }

    override fun onStart() { super.onStart(); Push.foreground = true; AppLock.onForeground(); AppUpdate.kick(this) }   // ask the server about a new version (public call, also works signed out)
    override fun onStop() { super.onStop(); Push.foreground = false; AppLock.onBackground() }

    private fun routeFrom(i: Intent?) {
        InboxMsg.from(i)?.let { Ui.openMessage = it }
        i?.getStringExtra("route")?.let { Ui.pendingRoute = it }
    }
}

@Composable
fun SystemBars(darkIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = darkIcons && !MudaTheme.dark   // light icons on a dark page
        c.isAppearanceLightNavigationBars = darkIcons && !MudaTheme.dark
    }
}

@Composable
fun MudaPayApp() {
    val act = LocalContext.current.findActivity()
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(Session.balance, Session.user, Session.signedIn) { WidgetSync.push(appCtx) }
    SideEffect {
        if (AppLock.hideScreen) act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    SideEffect { if (MudaTheme.systemDark != sysDark) { MudaTheme.systemDark = sysDark; WidgetSync.refresh(appCtx) } }
    val pageArgb = Page.toArgb()
    SideEffect { act?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(pageArgb)) }   // no light flash behind a dark app
    MaterialTheme(colorScheme = mudaColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Page) {
            val forcedUpdate = AppUpdate.info?.takeIf { it.mode == "forced" }
            if (forcedUpdate != null) {
                // the last day of a required update has passed: nothing else can be used until the app is updated
                LaunchedEffect(Unit) { ErrorReporter.screen = "ForceUpdate" }
                ForceUpdateScreen(forcedUpdate)
            } else {
                if (Session.signedIn) {
                    if (AppLock.locked) { LaunchedEffect(Unit) { ErrorReporter.screen = "Lock" }; LockScreen() } else SignedIn()
                } else AuthFlow()
                if (!(Session.signedIn && AppLock.locked)) UpdatePromptDialog()
                if (Session.signedIn && !AppLock.locked) PinSetupHost()
            }

            Ui.title?.let { t ->
                AlertDialog(
                    onDismissRequest = { Ui.clear() }, containerColor = Panel,
                    confirmButton = { TextButton(onClick = { Ui.clear() }) { Text(L("OK"), color = GreenDark, fontWeight = FontWeight.Bold) } },
                    title = { Text(t, fontWeight = FontWeight.Bold) }, text = { Text(Ui.message) }
                )
            }
            Session.blockedMessage?.let { m ->
                AlertDialog(
                    onDismissRequest = { Session.blockedMessage = null }, containerColor = Panel,
                    confirmButton = { TextButton(onClick = { Session.blockedMessage = null }) { Text(L("OK"), color = GreenDark, fontWeight = FontWeight.Bold) } },
                    title = { Text(L("Account blocked"), fontWeight = FontWeight.Bold) }, text = { Text(m) }
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ signed out */
private fun authDepth(s: String) = when (s) { "Welcome" -> 0; "Login", "Register" -> 1; else -> 2 }

@Composable
private fun AuthFlow() {
    var screen by remember { mutableStateOf("Welcome") }
    var phone by remember { mutableStateOf("") }
    var google by remember { mutableStateOf<GoogleAuth.Outcome.NeedsProfile?>(null) }
    LaunchedEffect(screen) { ErrorReporter.screen = "Auth:$screen" }   // crash / error reports say which screen the member was on
    SystemBars(darkIcons = AuthTheme.s.heroIsLight)
    BackHandler(enabled = screen != "Welcome") { screen = if (screen == "Forgot") "Login" else "Welcome" }
    val toProfile: (GoogleAuth.Outcome.NeedsProfile) -> Unit = { google = it; screen = "GoogleProfile" }
    // the living gradient is drawn once here; screens slide over it. Login <-> Register share one key so only the form animates.
    AuthBackdrop(full = screen == "Welcome") {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.fillMaxSize(),
            contentKey = { if (it == "Login" || it == "Register") "Auth" else it },
            transitionSpec = {
                val fwd = authDepth(targetState) >= authDepth(initialState)
                val spec = tween<androidx.compose.ui.unit.IntOffset>(430, easing = FastOutSlowInEasing)
                if (fwd) (fadeIn(tween(380, delayMillis = 110)) + slideInHorizontally(spec) { it / 5 }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(spec) { -it / 8 })
                else (fadeIn(tween(380, delayMillis = 110)) + slideInHorizontally(spec) { -it / 5 }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(spec) { it / 8 })
            },
            label = "authFlow"
        ) { s ->
            when (s) {
                "Login" -> AuthScreen(false, phone, { screen = "Welcome" }, { screen = "Register" }, {}, toProfile, onForgot = { p -> phone = p; screen = "Forgot" })
                "Register" -> AuthScreen(true, "", { screen = "Welcome" }, { screen = "Login" }, { p -> phone = p; screen = "Login" }, toProfile)
                "Forgot" -> ForgotPasswordScreen(phone, { screen = "Login" }) { p -> phone = p; screen = "Login" }
                "GoogleProfile" -> {
                    val g = google
                    if (g != null) GoogleProfileScreen(g) { screen = "Login" } else LaunchedEffect(Unit) { screen = "Welcome" }
                }
                else -> WelcomeScreen({ screen = "Login" }, { screen = "Register" })
            }
        }
    }
}

/* ------------------------------------------------------------------ signed in */
@Composable
private fun SignedIn() {
    var error by remember { mutableStateOf<String?>(null) }
    var errOffline by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick, Offline.networkUp) {
        if (Session.user == null) {
            val r = Api.call("GET", "/me")
            if (r.ok) { Session.updateUser(r.data); error = null } else { error = r.message; errOffline = r.code == "network" }
        }
    }
    val user = Session.user
    LaunchedEffect(user == null) {
        if (user == null) ErrorReporter.screen = "Loading"
    }
    LaunchedEffect(user != null, Ui.refreshTick) { if (user != null) Avatars.loadMine() }   // my profile photo
    when {
        user == null -> {
            SystemBars(darkIcons = true)
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                val e = error
                if (e != null) ErrorBox(e, { error = null; tick++ }, errOffline) else LoadingBox()
            }
        }
        else -> MainShell()
    }
}

private class Tab(val route: String, val label: String, val icon: ImageVector)
private val TABS = listOf(
    Tab("Home", "Home", MudaIcons.Home), Tab("History", "Activity", MudaIcons.History), Tab("RewardsHub", "Rewards", MudaIcons.CardGiftcard),
    Tab("Withdraw", "Withdraw", MudaIcons.AccountBalanceWallet), Tab("Profile", "Profile", MudaIcons.AccountCircle)
)
// Tasks and Videos keep the tab layout (bottom bar, big title) but are opened from Rewards
private val TAB_ROUTES = TABS.map { it.route } + listOf("Tasks", "Videos")
private fun barRoute(route: String) = if (route == "Tasks" || route == "Videos") "RewardsHub" else route
private val GRADIENT_ROUTES = emptySet<String>()   // Market is a dark screen with its own top bar: light status-bar icons there, dark everywhere else
/** Only these routes may be opened (also protects against a bad route inside a push payload). */
private val KNOWN_ROUTES: Set<String> by lazy { (TAB_ROUTES + MORE_ENTRIES.map { it.route } + listOf("More", "WithdrawalDetail", "Referrals", "CryptoPay", "ShopMode", InboxMsg.ROUTE)).toSet() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    var refreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    fun doRefresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            val ok = refreshEverything()
            if (!ok) Toast.makeText(ctx, "No connection. Showing saved data.", Toast.LENGTH_SHORT).show()
            refreshing = false
        }
    }
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "Home"
    LaunchedEffect(route) { ErrorReporter.screen = route }   // crash / error reports say which screen the member was on

    fun nav(r: String) {
        if (r == route || r !in KNOWN_ROUTES) return
        navController.navigate(r) {
            if (r in TAB_ROUTES) popUpTo("Home") { inclusive = false }
            launchSingleTop = true
        }
    }

    // Push: register this phone, ask for the notification permission once (Android 13+).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Push.sync(ctx) }
    LaunchedEffect(Unit) {
        Push.sync(ctx)
        if (Build.VERSION.SDK_INT >= 33 && !PushPrefs.asked(ctx) &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            PushPrefs.markAsked(ctx)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Push tap -> open the requested screen.
    LaunchedEffect(Ui.pendingRoute) {
        Ui.pendingRoute?.let { nav(it); Ui.pendingRoute = null }
    }

    SystemBars(darkIcons = !Ui.immersive && route !in GRADIENT_ROUTES)

    // While the app is open: look for new messages / withdrawal updates every ~90 s and show them as real notifications.
    LaunchedEffect(Unit) {
        while (true) {
            if (!Offline.offline) {
                val changed = NotifySync.check(ctx)
                if (changed) {
                    val me = Api.call("GET", "/me")
                    if (me.ok && !me.cached) Session.updateUser(me.data)
                    Ui.refreshTick++
                }
            }
            delay(90_000)
        }
    }

    LaunchedEffect(Offline.networkUp) { if (Offline.networkUp) PendingQueue.flush() }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20000)
            if (PendingQueue.size > 0) PendingQueue.flush()
        }
    }
    val showBanner = !Ui.immersive && (Offline.offline || PendingQueue.size > 0 || Offline.suggestLite)

    val isTab = route in TAB_ROUTES
    Column(Modifier.fillMaxSize().background(Page)) {
        if (!isTab && route != "Market") {
            val title = when (route) {
                "Settings" -> Ui.settingsPage?.let { settingsPageTitle(it) } ?: "Settings"
                "More" -> "Menu"
                "WithdrawalDetail" -> "Withdrawal"
                "CryptoPay" -> "Crypto payment"
                "ShopMode" -> "Shop mode"
                InboxMsg.ROUTE -> "Notification"
                else -> MORE_ENTRIES.firstOrNull { it.route == route }?.title ?: route
            }
            PageHeader(L(title), onBack = { if (route == "Settings" && Ui.settingsPage != null) settingsBack() else navController.popBackStack() }, trailing = {
                if (route != "More") IconButton(onClick = { nav("More") }) { Icon(MudaIcons.Menu, "Menu", tint = Ink) }
            })
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { doRefresh() },
            state = pullState,
            modifier = Modifier.weight(1f).then(if (isTab || showBanner) Modifier else Modifier.navigationBarsPadding()),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState, isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).then(if (isTab) Modifier.statusBarsPadding() else Modifier),
                    containerColor = Panel, color = Green
                )
            }
        ) {
            NavHost(
                navController, startDestination = "Home", modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(160)) }, exitTransition = { fadeOut(tween(100)) },
                popEnterTransition = { fadeIn(tween(160)) }, popExitTransition = { fadeOut(tween(100)) }
            ) {
                composable("Home") { DashboardScreen(::nav) }
                composable("Videos") { VideosScreen() }
                composable("RewardsHub") { RewardsScreen(::nav) }
                composable("Tasks") { TasksScreen() }
                composable("Withdraw") { WithdrawScreen(::nav) }
                composable("Profile") { ProfileScreen(::nav) }
                composable("More") { MoreScreen(::nav) }
                composable("Share") { NeedsInternet { ShareScreen() } }
                composable("Referrals") { ReferralsScreen() }
                composable("Leaderboard") { LeaderboardScreen() }
                composable("History") { HistoryScreen() }
                composable("Rewards") { RewardRatesScreen(::nav) }
                composable("Coach") { NeedsInternet { CoachScreen(::nav) } }
                composable("Notifications") { NotificationsScreen(::nav) }
                composable(InboxMsg.ROUTE) { NotificationDetailScreen(::nav) }
                composable("Support") { NeedsInternet { SupportScreen(::nav) } }
                composable("Settings") { SettingsScreen(::nav) }
                composable("ShareApp") { ShareAppScreen() }
                composable("About") { AboutScreen(::nav) }
                composable("Terms") { TermsScreen(::nav) }
                composable("Privacy") { PrivacyScreen(::nav) }
                composable("WithdrawalDetail") { WithdrawalDetailScreen() }
                composable("Send") { NeedsInternet { SendMoneyScreen(::nav) } }
                composable("Receive") { NeedsInternet { ReceiveMoneyScreen(::nav) } }
                composable("Requests") { NeedsInternet { RequestsScreen(::nav) } }
                composable("Scan") { NeedsInternet { ScanPayScreen(::nav) } }
                composable("Promo") { NeedsInternet { PromoScreen() } }
                composable("Merchant") { NeedsInternet { MerchantScreen(::nav) } }
                composable("ShopMode") { NeedsInternet { ShopModeScreen(::nav) } }
                composable("Contacts") { NeedsInternet { ContactsScreen(::nav) } }
                composable("Deposit") { NeedsInternet { DepositScreen(::nav) } }
                composable("CryptoPay") { NeedsInternet { CryptoPayScreen(::nav) } }
                composable("AgentDesk") { NeedsInternet { AgentDeskScreen() } }
                composable("Market") { MarketScreen(::nav) { navController.popBackStack() } }
                composable("Wallet") { NeedsInternet { CryptoWalletScreen(::nav) } }
                composable("ExternalWallet") { NeedsInternet { ExternalWalletScreen(::nav) } }
            }
        }
        if (showBanner) OfflineBanner(if (isTab) Modifier else Modifier.navigationBarsPadding())
        if (isTab && !Ui.immersive) BottomBar(barRoute(route), ::nav)
    }
}

@Composable
private fun BottomBar(route: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Page).navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).height(64.dp).background(Panel, RoundedCornerShape(32.dp)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TABS.forEach { tab ->
                val on = tab.route == route
                val tint by androidx.compose.animation.animateColorAsState(if (on) Dark else Slate, tween(180), label = "tab")
                val pill by androidx.compose.animation.animateColorAsState(if (on) Soft else Color.Transparent, tween(180), label = "pill")
                Row(
                    Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(pill).clickable { onSelect(tab.route) }
                        .padding(horizontal = if (on) 14.dp else 11.dp).animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(tab.icon, L(tab.label), tint = if (on) Green else tint, modifier = Modifier.size(24.dp))
                    if (on) {
                        Spacer(Modifier.width(6.dp))
                        Text(L(tab.label), color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(64.dp).background(Panel, CircleShape).clip(CircleShape).clickable { onSelect("Scan") },
            contentAlignment = Alignment.Center
        ) { Icon(MudaIcons.QrCodeScanner, L("Scan"), tint = Dark, modifier = Modifier.size(26.dp)) }
    }
}
