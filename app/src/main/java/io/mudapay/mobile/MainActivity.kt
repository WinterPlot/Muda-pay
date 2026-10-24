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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
        window.statusBarColor = AColor.TRANSPARENT
        window.navigationBarColor = AColor.TRANSPARENT
        routeFrom(intent)
        setContent { MudaPayApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)
    }

    override fun onStart() { super.onStart(); AppLock.onForeground() }
    override fun onStop() { super.onStop(); AppLock.onBackground() }

    private fun routeFrom(i: Intent?) {
        i?.getStringExtra("route")?.let { Ui.pendingRoute = it }
    }
}

@Composable
fun SystemBars(darkIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = darkIcons
        c.isAppearanceLightNavigationBars = true
    }
}

@Composable
fun MudaPayApp() {
    val act = LocalContext.current.findActivity()
    SideEffect {
        if (AppLock.hideScreen) act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = Blue, background = Page, surface = Color.White, onSurface = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Page) {
            if (Session.signedIn) { if (AppLock.locked) LockScreen() else SignedIn() } else AuthFlow()

            Ui.title?.let { t ->
                AlertDialog(
                    onDismissRequest = { Ui.clear() }, containerColor = Color.White,
                    confirmButton = { TextButton(onClick = { Ui.clear() }) { Text(L("OK"), color = GreenDark, fontWeight = FontWeight.Bold) } },
                    title = { Text(t, fontWeight = FontWeight.Black) }, text = { Text(Ui.message) }
                )
            }
            Session.blockedMessage?.let { m ->
                AlertDialog(
                    onDismissRequest = { Session.blockedMessage = null }, containerColor = Color.White,
                    confirmButton = { TextButton(onClick = { Session.blockedMessage = null }) { Text(L("OK"), color = GreenDark, fontWeight = FontWeight.Bold) } },
                    title = { Text(L("Account blocked"), fontWeight = FontWeight.Black) }, text = { Text(m) }
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ signed out */
@Composable
private fun AuthFlow() {
    var screen by remember { mutableStateOf("Welcome") }
    var phone by remember { mutableStateOf("") }
    var google by remember { mutableStateOf<GoogleAuth.Outcome.NeedsProfile?>(null) }
    SystemBars(darkIcons = false)
    BackHandler(enabled = screen != "Welcome") { screen = "Welcome" }
    val toProfile: (GoogleAuth.Outcome.NeedsProfile) -> Unit = { google = it; screen = "GoogleProfile" }
    when (screen) {
        "Login" -> AuthScreen(false, phone, { screen = "Welcome" }, { screen = "Register" }, {}, toProfile)
        "Register" -> AuthScreen(true, "", { screen = "Welcome" }, { screen = "Login" }, { p -> phone = p; screen = "Login" }, toProfile)
        "GoogleProfile" -> {
            val g = google
            if (g != null) GoogleProfileScreen(g) { screen = "Login" } else LaunchedEffect(Unit) { screen = "Welcome" }
        }
        else -> WelcomeScreen({ screen = "Login" }, { screen = "Register" })
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
    LaunchedEffect(Ui.pendingRoute) {
        if (Ui.pendingRoute != null && Session.user?.bool("paid") == false) {
            val r = Api.call("GET", "/me")
            if (r.ok) Session.updateUser(r.data)
        }
    }
    val user = Session.user
    when {
        user == null -> {
            SystemBars(darkIcons = true)
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                val e = error
                if (e != null) ErrorBox(e, { error = null; tick++ }, errOffline) else LoadingBox()
            }
        }
        !user.bool("paid") -> { SystemBars(darkIcons = false); ActivationScreen() }
        else -> MainShell()
    }
}

private class Tab(val route: String, val label: String, val icon: ImageVector)
private val TABS = listOf(
    Tab("Home", "Home", Icons.Default.Home), Tab("Videos", "Videos", Icons.Default.PlayCircle), Tab("Tasks", "Tasks", Icons.Default.TaskAlt),
    Tab("Withdraw", "Withdraw", Icons.Default.AccountBalanceWallet), Tab("Profile", "Profile", Icons.Default.AccountCircle)
)
private val TAB_ROUTES = TABS.map { it.route }
private val GRADIENT_ROUTES = setOf("Home", "Tasks", "Withdraw", "Profile")
/** Only these routes may be opened (also protects against a bad route inside a push payload). */
private val KNOWN_ROUTES: Set<String> by lazy { (TAB_ROUTES + MORE_ENTRIES.map { it.route } + listOf("More", "WithdrawalDetail", "Referrals")).toSet() }

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
    val showBanner = !Ui.immersive && (Offline.offline || PendingQueue.size > 0)

    val isTab = route in TAB_ROUTES
    Column(Modifier.fillMaxSize().background(Page)) {
        if (!isTab) {
            val title = when (route) {
                "More" -> "Menu"
                "WithdrawalDetail" -> "Withdrawal"
                else -> MORE_ENTRIES.firstOrNull { it.route == route }?.title ?: route
            }
            PageHeader(title, onBack = { navController.popBackStack() }, trailing = {
                if (route != "More") IconButton(onClick = { nav("More") }) { Icon(Icons.Default.Menu, "Menu", tint = Ink) }
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
                    containerColor = Color.White, color = Green
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
                composable("Tasks") { TasksScreen() }
                composable("Withdraw") { WithdrawScreen(::nav) }
                composable("Profile") { ProfileScreen(::nav) }
                composable("More") { MoreScreen(::nav) }
                composable("Share") { NeedsInternet { ShareScreen() } }
                composable("Referrals") { ReferralsScreen() }
                composable("Leaderboard") { LeaderboardScreen() }
                composable("History") { HistoryScreen() }
                composable("Rewards") { RewardRatesScreen() }
                composable("Coach") { NeedsInternet { CoachScreen(::nav) } }
                composable("Notifications") { NotificationsScreen() }
                composable("Support") { NeedsInternet { SupportScreen() } }
                composable("Settings") { SettingsScreen(::nav) }
                composable("ShareApp") { ShareAppScreen() }
                composable("About") { AboutScreen() }
                composable("WithdrawalDetail") { WithdrawalDetailScreen() }
            }
        }
        if (showBanner) OfflineBanner(if (isTab) Modifier else Modifier.navigationBarsPadding())
        if (isTab && !Ui.immersive) BottomBar(route, ::nav)
    }
}

@Composable
private fun BottomBar(route: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Row(
        Modifier.fillMaxWidth().shadow(18.dp, shape, ambientColor = Color(0x330F172A), spotColor = Color(0x330F172A)).background(Color.White, shape)
            .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        TABS.forEach { tab ->
            val on = tab.route == route
            val tint by androidx.compose.animation.animateColorAsState(if (on) Green else Slate, tween(180), label = "tab")
            Column(
                Modifier.weight(1f).padding(horizontal = 3.dp)
                    .background(if (on) Green.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(18.dp))
                    .clickable { onSelect(tab.route) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(if (on) 26.dp else 24.dp))
                Text(tab.label, color = tint, fontSize = 10.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold)
            }
        }
    }
}
