package io.mudapay.mobile

import android.app.Activity
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
        Session.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AColor.TRANSPARENT
        window.navigationBarColor = AColor.TRANSPARENT
        setContent { MudaPayApp() }
    }
}

@Composable
private fun SystemBars(darkIcons: Boolean) {
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
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = Blue, background = Page, surface = Color.White, onSurface = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Page) {
            if (Session.signedIn) SignedIn() else AuthFlow()

            Ui.title?.let { t ->
                AlertDialog(
                    onDismissRequest = { Ui.clear() }, containerColor = Color.White,
                    confirmButton = { TextButton(onClick = { Ui.clear() }) { Text("OK", color = GreenDark, fontWeight = FontWeight.Bold) } },
                    title = { Text(t, fontWeight = FontWeight.Black) }, text = { Text(Ui.message) }
                )
            }
            Session.blockedMessage?.let { m ->
                AlertDialog(
                    onDismissRequest = { Session.blockedMessage = null }, containerColor = Color.White,
                    confirmButton = { TextButton(onClick = { Session.blockedMessage = null }) { Text("OK", color = GreenDark, fontWeight = FontWeight.Bold) } },
                    title = { Text("Account blocked", fontWeight = FontWeight.Black) }, text = { Text(m) }
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
    SystemBars(darkIcons = false)
    BackHandler(enabled = screen != "Welcome") { screen = "Welcome" }
    when (screen) {
        "Login" -> AuthScreen(false, phone, { screen = "Welcome" }, { screen = "Register" }, {})
        "Register" -> AuthScreen(true, "", { screen = "Welcome" }, { screen = "Login" }, { p -> phone = p; screen = "Login" })
        else -> WelcomeScreen({ screen = "Login" }, { screen = "Register" })
    }
}

/* ------------------------------------------------------------------ signed in */
@Composable
private fun SignedIn() {
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        if (Session.user == null) {
            val r = Api.call("GET", "/me")
            if (r.ok) Session.setUser(r.data) else error = r.message
        }
    }
    val user = Session.user
    when {
        user == null -> {
            SystemBars(darkIcons = true)
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                val e = error
                if (e != null) ErrorBox(e) { error = null; tick++ } else LoadingBox()
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

@Composable
private fun MainShell() {
    val stack = remember { mutableStateListOf("Home") }
    val route = stack.last()

    fun nav(r: String) {
        if (r == route) return
        if (r in TAB_ROUTES) { stack.clear(); stack.add("Home"); if (r != "Home") stack.add(r) } else stack.add(r)
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    SystemBars(darkIcons = !Ui.immersive && route !in GRADIENT_ROUTES)

    val isTab = route in TAB_ROUTES
    Column(Modifier.fillMaxSize().background(Page)) {
        if (!isTab) {
            val title = MORE_ENTRIES.firstOrNull { it.route == route }?.title ?: if (route == "More") "Menu" else route
            PageHeader(title, onBack = { stack.removeAt(stack.lastIndex) }, trailing = {
                if (route != "More") IconButton(onClick = { nav("More") }) { Icon(Icons.Default.Menu, "Menu", tint = Ink) }
            })
        }
        Box(Modifier.weight(1f).then(if (isTab) Modifier else Modifier.navigationBarsPadding())) {
            when (route) {
                "Home" -> DashboardScreen(::nav)
                "Videos" -> VideosScreen()
                "Tasks" -> TasksScreen()
                "Withdraw" -> WithdrawScreen()
                "Profile" -> ProfileScreen(::nav)
                "More" -> MoreScreen(::nav)
                "Share" -> ShareScreen()
                "Leaderboard" -> LeaderboardScreen()
                "History" -> HistoryScreen()
                "Rewards" -> RewardRatesScreen()
                "Coach" -> CoachScreen()
                "Notifications" -> NotificationsScreen()
                "Support" -> SupportScreen()
                "Settings" -> SettingsScreen()
                "About" -> AboutScreen()
                else -> MoreScreen(::nav)
            }
        }
        if (isTab && !Ui.immersive) BottomBar(route, ::nav)
    }
}

@Composable
private fun BottomBar(route: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp)
    Row(
        Modifier.fillMaxWidth().shadow(14.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000)).background(Color.White, shape)
            .navigationBarsPadding().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        TABS.forEach { tab ->
            val on = tab.route == route
            Column(Modifier.weight(1f).clickable { onSelect(tab.route) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(tab.icon, tab.label, tint = if (on) Green else Slate, modifier = Modifier.size(25.dp))
                Text(tab.label, color = if (on) Green else Slate, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
