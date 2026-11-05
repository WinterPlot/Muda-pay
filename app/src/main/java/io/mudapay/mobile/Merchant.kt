package io.mudapay.mobile

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Merchant payments (v3.10).
 *
 * A shop or market seller is a normal member with a business profile (name, category, town). Payments still go through the
 * normal transfer code, so they are free for the shop and instant. What the profile adds:
 *   - a FIXED QR code (the member's phone + the shop code; it never changes, print it once and stick it on the counter);
 *   - a verified business name that the payer sees before confirming (POST /merchant/lookup; the name is never read from the QR);
 *   - SHOP MODE: while it is open the phone polls the received transfers, plays the "received" sound and shows a big
 *     "RECEIVED +amount" card for every new sale, so the seller does not have to look at the screen;
 *   - a daily sales summary (today + last 7 days) computed on the phone from GET /transfers?direction=in;
 *   - PAYOUT: the sales sit in the normal balance; the Withdraw screen pays them out.
 */
object Merchant {
    /** The signed-in member's business profile, or null (not a merchant / not loaded yet). */
    var mine by mutableStateOf<JSONObject?>(null)
        private set

    fun set(p: JSONObject?) { mine = p }
    fun reset() { mine = null }
    val name: String get() = mine?.str("business_name").orEmpty()
}

/** One day of sales. */
class SalesDay(val date: String, val total: Double, val count: Int)

object Sales {
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = fmt().format(Date())

    /** Stable id of a transfer: reference first, then id, then a fingerprint. Used to play the sound once per sale. */
    fun key(t: JSONObject): String {
        val ref = t.str("reference").ifEmpty { t.str("id") }
        return if (ref.isNotEmpty()) ref else t.str("created_at") + "|" + t.dbl("amount") + "|" + t.optJSONObject("counterparty")?.str("phone").orEmpty()
    }

    /** The last [n] days (oldest first, today last) from the received transfers. */
    fun days(items: List<JSONObject>, n: Int = 7): List<SalesDay> {
        val cal = Calendar.getInstance()
        val dates = ArrayList<String>()
        for (i in 0 until n) { dates.add(0, fmt().format(cal.time)); cal.add(Calendar.DAY_OF_YEAR, -1) }
        return dates.map { d ->
            val day = items.filter { it.str("created_at").take(10) == d }
            SalesDay(d, day.sumOf { it.dbl("amount") }, day.size)
        }
    }

    fun forDate(items: List<JSONObject>, date: String): List<JSONObject> = items.filter { it.str("created_at").take(10) == date }
}

private fun vibrateOnce(ctx: Context) {
    try {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(350)
    } catch (e: Throwable) { ErrorReporter.nonFatal(e, "Merchant.vibrate") }
}

private fun soundOn(ctx: Context): Boolean = try {
    (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode == AudioManager.RINGER_MODE_NORMAL
} catch (e: Throwable) { true }

private fun shortTime(createdAt: String) = createdAt.drop(11).take(5)

/* ------------------------------------------------------------------ screen: business profile + dashboard */
@Composable
fun MerchantScreen(nav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Remote(call = { Api.call("GET", "/merchant/me") }) { d, reload ->
            val profile = d.optJSONObject("profile")
            val categories = d.strings("categories").ifEmpty { listOf("Shop", "Market stall", "Food & drinks", "Other") }
            LaunchedEffect(profile?.toString()) { Merchant.set(profile) }
            var editing by remember { mutableStateOf(false) }
            if (profile == null) {
                Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
                    Text(softCase(L("MERCHANT PAYMENTS")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                    Text(L("Accept money from customers, free"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(L("Open a business profile: one fixed QR code for your counter, a sound every time you are paid, a daily sales summary and a payout to your account."), color = Color.White.copy(alpha = .9f), fontSize = 13.sp, lineHeight = 18.sp)
                }
                ProfileForm(null, categories) { editing = false; reload() }
            } else {
                MerchantDashboard(profile, nav, editing, { editing = it }, categories, reload)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProfileForm(existing: JSONObject?, categories: List<String>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.str("business_name").orEmpty()) }
    var category by remember { mutableStateOf(existing?.str("category").orEmpty().ifEmpty { categories.first() }) }
    var city by remember { mutableStateOf(existing?.str("city").orEmpty()) }
    var address by remember { mutableStateOf(existing?.str("address").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L(if (existing == null) "Your business" else "Edit your business"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        InputField("Business name", name, { name = it.take(40) })
        Dropdown(L("Category"), category, categories.map { it to L(it) }) { category = it }
        InputField("Town or market (optional)", city, { city = it.take(40) })
        InputField("Address or stall number (optional)", address, { address = it.take(80) })
        Text(L("Customers see this name, checked by MudaPay, before they pay. The money goes to your normal MudaPay balance."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        PrimaryButton(if (existing == null) "OPEN MY BUSINESS PROFILE" else "SAVE", {
            busy = true
            scope.launch {
                val r = Api.call("POST", "/merchant/me", JSONObject().put("business_name", name.trim()).put("category", category).put("city", city.trim()).put("address", address.trim()))
                busy = false
                if (r.ok) { Merchant.set(r.data.optJSONObject("profile")); Ui.notify("Business", "Your business profile was saved."); onDone() }
                else Ui.notify("Business", r.message)
            }
        }, loading = busy, enabled = name.trim().length >= 2, icon = MudaIcons.Storefront)
        if (existing != null) TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(L("Cancel"), color = Muted) }
    }
}

@Composable
private fun MerchantDashboard(profile: JSONObject, nav: (String) -> Unit, editing: Boolean, setEditing: (Boolean) -> Unit, categories: List<String>, reload: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Session.user ?: JSONObject()
    val symbol = user.str("symbol")
    val phone = user.str("phone")
    val myCountry = user.str("country")
    val shop = profile.str("business_name")
    val payload = remember(phone, myCountry, profile.str("code")) { if (phone.isBlank()) null else PayQr.build(phone, null, country = myCountry, merchant = profile.str("code")) }
    val qrBmp = remember(payload) { payload?.let { DotQr.render(it, 900) } }
    val qr = remember(qrBmp) { qrBmp?.asImageBitmap() }
    var removeAsk by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<ReceiptData?>(null) }
    receipt?.let { ReceiptDialog(it) { receipt = null } }

    // hero
    Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(softCase(L("BUSINESS")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp, modifier = Modifier.weight(1f))
            Pill(profile.str("code"), Gold)
        }
        Text(shop, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(listOf(L(profile.str("category")), profile.str("city")).filter { it.isNotBlank() }.joinToString(" • "), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
    }

    if (editing) { ProfileForm(profile, categories) { setEditing(false); reload() }; return }

    // sales
    Remote(call = { Api.call("GET", "/transfers", query = mapOf("direction" to "in", "limit" to "300")) }) { d, _ ->
        val items = d.list("items")
        val days = Sales.days(items)
        val todayDate = Sales.today()
        val today = days.last()
        val todays = Sales.forDate(items, todayDate)
        val weekTotal = days.sumOf { it.total }
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L("Sales today"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(money(symbol, today.total), color = GreenDark, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("${today.count} ${L(if (today.count == 1) "payment" else "payments")}", color = Ink, fontSize = 13.sp)
            val peak = (days.maxOfOrNull { it.total } ?: 0.0).coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                days.forEach { dd ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        val h = (56 * (dd.total / peak)).toInt().coerceAtLeast(if (dd.total > 0) 4 else 2)
                        Box(Modifier.fillMaxWidth().height(h.dp).clip(RoundedCornerShape(5.dp)).background(if (dd.date == todayDate) Green else Green.copy(alpha = .35f)))
                        Text(dd.date.takeLast(2), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            Text("${L("Last 7 days")}: ${money(symbol, weekTotal)}", color = Muted, fontSize = 12.sp)
            PrimaryButton(softCase(L("SHARE TODAY'S SUMMARY")), {
                shareText(ctx, "$shop — ${L("Sales today")} ($todayDate)\n${money(symbol, today.total)} • ${today.count} ${L(if (today.count == 1) "payment" else "payments")}\nMudaPay")
            }, color = Dark, icon = MudaIcons.Share)
        }
        if (todays.isNotEmpty()) {
            Text(L("Today's payments"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            todays.take(8).forEach { t -> SaleRow(t, symbol) { receipt = ReceiptBuilder.transfer(t, false) } }
        }
    }

    PrimaryButton(softCase(L("OPEN SHOP MODE")), { nav("ShopMode") }, icon = MudaIcons.NotificationsActive)
    Text(L("Shop mode keeps the screen on and plays a sound with a big card every time a customer pays you."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)

    // fixed QR
    Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(L("Your shop QR code"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (qr != null) Box(Modifier.background(Color.White, RoundedCornerShape(20.dp)).padding(10.dp)) { Image(qr, "Shop QR code", Modifier.size(240.dp)) }
        Text(shop, color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("This code never changes. Print it once and stick it on your counter. The customer scans it, types the amount and pays; you pay no fee."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
        PrimaryButton(softCase(L("SHARE OR PRINT QR CODE")), {
            val src = qrBmp
            if (src != null) {
                val card = DotQr.shareCard(src, shop, phone, null)
                if (!DotQr.sharePicture(ctx, card, "$shop — pay with MudaPay: $phone")) Ui.notify("Share", "The QR code could not be shared.")
            }
        }, enabled = qrBmp != null, icon = MudaIcons.Share)
    }

    // payout
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L("Payout"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(money(symbol, Session.balance), color = GreenDark, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(L("Your sales are added to your balance. Pay them out to mobile money, a bank, crypto or a cash agent whenever you want."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        PrimaryButton(softCase(L("PAY OUT MY SALES")), { nav("Withdraw") }, color = Dark, icon = MudaIcons.AccountBalanceWallet)
    }

    ContentRow("Edit business profile", "Name, category, town", MudaIcons.Edit, { setEditing(true) })
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = { removeAsk = true }) { Text(L("Close business profile"), color = Danger, fontWeight = FontWeight.SemiBold) }
    }
    if (removeAsk) {
        AlertDialog(
            onDismissRequest = { removeAsk = false }, containerColor = Panel,
            title = { Text(L("Close business profile?"), fontWeight = FontWeight.Bold) },
            text = { Text(L("Customers will no longer see your business name. Your account, balance and personal QR code stay as they are."), color = Ink, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    removeAsk = false
                    scope.launch { val r = Api.call("POST", "/merchant/remove"); if (r.ok) { Merchant.set(null); reload() } else Ui.notify("Business", r.message) }
                }) { Text(L("Close"), color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { removeAsk = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

@Composable
private fun SaleRow(t: JSONObject, symbol: String, onClick: () -> Unit) {
    val who = t.optJSONObject("counterparty")
    val name = who?.str("name").orEmpty().ifEmpty { "MudaPay" }
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        MemberAvatar(name, who?.str("phone").orEmpty(), 40.dp, GreenDark, who?.str("avatar"))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(shortTime(t.str("created_at")) + t.str("note").let { if (it.isNotEmpty()) " • $it" else "" }, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        Text("+" + money(t.str("symbol").ifEmpty { symbol }, t.dbl("amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/* ------------------------------------------------------------------ screen: shop mode (live "received" sound) */
@Composable
fun ShopModeScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val symbol = Session.user?.str("symbol").orEmpty()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var flash by remember { mutableStateOf<JSONObject?>(null) }
    var flashTick by remember { mutableIntStateOf(0) }
    var lastOk by remember { mutableStateOf(true) }
    var silent by remember { mutableStateOf(!soundOn(ctx)) }
    var receipt by remember { mutableStateOf<ReceiptData?>(null) }
    receipt?.let { ReceiptDialog(it) { receipt = null } }

    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    // Every 4 s: ask for the received transfers. The first answer only sets the starting point; anything new after it is a sale.
    LaunchedEffect(Unit) {
        val seen = HashSet<String>()
        var first = true
        while (true) {
            val r = Api.call("GET", "/transfers", query = mapOf("direction" to "in", "limit" to "100"))
            lastOk = r.ok
            if (r.ok) {
                val list = r.data.list("items")
                items = list
                if (first) { list.forEach { seen.add(Sales.key(it)) }; first = false }
                else {
                    val fresh = list.filter { seen.add(Sales.key(it)) }.reversed()
                    for (t in fresh) {
                        flash = t; flashTick++
                        TxSound.play("tr:" + Sales.key(t))
                        vibrateOnce(ctx)
                        Session.balance = Session.balance + t.dbl("amount")
                    }
                    if (fresh.isNotEmpty()) { Ui.refreshTick++ }
                }
            }
            silent = !soundOn(ctx)
            delay(4000)
        }
    }
    LaunchedEffect(flashTick) { if (flash != null) { delay(14000); flash = null } }

    val todayItems = Sales.forDate(items, Sales.today())
    val total = todayItems.sumOf { it.dbl("amount") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val f = flash
        if (f != null) {
            val who = f.optJSONObject("counterparty")
            Column(
                Modifier.fillMaxWidth().background(GreenFill, RoundedCornerShape(24.dp)).clickable { flash = null }.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(64.dp).background(Color.White.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) { Icon(MudaIcons.Check, null, tint = Color.White, modifier = Modifier.size(38.dp)) }
                Text(softCase(L("PAYMENT RECEIVED")), color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                Text("+" + money(f.str("symbol").ifEmpty { symbol }, f.dbl("amount")), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(who?.str("name").orEmpty().ifEmpty { "MudaPay" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (f.str("note").isNotEmpty()) Text(f.str("note"), color = Color.White.copy(alpha = .85f), fontSize = 13.sp, textAlign = TextAlign.Center)
                Text(L("Tap to close"), color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
            }
        } else {
            Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
                Text(softCase(L("SHOP MODE")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                Text(Merchant.name.ifEmpty { L("Waiting for payments…") }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(L("Waiting for payments…"), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
            }
        }
        if (silent) InfoBanner(L("Your phone is on silent or vibrate: you will not hear the payment sound. Turn the ringer on."), Danger, MudaIcons.NotificationsOff)
        if (!lastOk) InfoBanner(L("No connection. New payments will appear as soon as the network is back."), Danger, MudaIcons.Info)

        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(L("Sales today"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(money(symbol, total), color = GreenDark, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("${todayItems.size} ${L(if (todayItems.size == 1) "payment" else "payments")}", color = Ink, fontSize = 13.sp)
        }
        PrimaryButton(softCase(L("TEST THE SOUND")), { TxSound.play() }, color = Dark, icon = MudaIcons.NotificationsActive)
        PrimaryButton(softCase(L("SHOW MY SHOP QR CODE")), { nav("Merchant") }, color = Dark, icon = MudaIcons.QrCode2)
        if (todayItems.isNotEmpty()) {
            Text(L("Today's payments"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            todayItems.take(30).forEach { t -> SaleRow(t, symbol) { receipt = ReceiptBuilder.transfer(t, false) } }
        }
        Text(L("Keep this screen open while you sell. The sound plays only while Shop mode is open; when the app is closed you still get the normal payment notification."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
    }
}
