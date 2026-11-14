package io.mudapay.mobile

import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Crypto Wallet screen (route "Wallet"): a self-custody wallet INSIDE MudaPay for BNB Chain (BNB and USDT BEP20).
 *
 *  - The recovery phrase is created / typed on this phone only, encrypted by the Android Keystore (WalletVault) and never sent anywhere.
 *  - The server (api/v1/lib_wallet.php) only stores the two public addresses, reads balances and relays transactions that were
 *    signed here (WalletCrypto). It cannot move, freeze or recover anybody's funds.
 *  - It is separate from the MudaPay balance: money moves between them only by a normal crypto Deposit / Withdraw.
 */

private enum class WPage { Intro, Create, Import, Home, Receive, Send, Backup, History, Tx, Book }

private class WChain(val id: String, val name: String, val explorer: String, val enabled: Boolean)
private class WConfig(val enabled: Boolean, val chains: List<WChain>, val assets: List<WalletAsset>) {
    fun chain(id: String) = chains.firstOrNull { it.id == id }
    fun chainName(id: String) = chain(id)?.name ?: id
    fun chainOn(id: String) = chain(id)?.enabled == true
    fun asset(id: String) = assets.firstOrNull { it.id == id }
    fun usable() = assets.filter { chainOn(it.chain) }
}

/** The server may switch things on or off; what an asset IS (network, contract, decimals) and where the explorer is come from the app (Pinned). */
private fun parseConfig(d: JSONObject): WConfig {
    val chains = d.list("chains").filter { Pinned.chainNames.containsKey(it.str("id")) }.map {
        WChain(it.str("id"), Pinned.chainNames[it.str("id")] ?: it.str("id"), Pinned.explorers[it.str("id")] ?: "", it.bool("enabled"))
    }
    val assets = d.list("assets").mapNotNull {
        val p = Pinned.assets[it.str("id")] ?: return@mapNotNull null
        WalletAsset(it.str("id"), p.chain, p.symbol, it.str("name").ifEmpty { p.symbol }, p.decimals, p.contract, p.canSend && it.bool("can_send"))
    }
    return WConfig(d.bool("enabled"), chains, assets)
}

/** A transaction picked in the history list (shown by the transaction status screen). */
private class TxRef(val chain: String, val txid: String, val symbol: String, val amount: String, val counterparty: String, val status: String, val outgoing: Boolean)

/* ------------------------------------------------------------------ small helpers */
private fun pasteText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(ctx)?.toString()?.trim() ?: ""
}

/** "0.000000000000000123" -> "<0.000001", "12.50000" -> "12.5" (display only). */
private fun shown(amount: String): String {
    val v = try { BigDecimal(amount) } catch (e: Exception) { return amount }
    if (v.signum() == 0) return "0"
    val r = v.setScale(6, RoundingMode.DOWN).stripTrailingZeros()
    return if (r.signum() == 0) "<0.000001" else r.toPlainString()
}

private fun shortAddr(a: String) = if (a.length > 18) a.take(8) + "…" + a.takeLast(6) else a

/** While a phrase is on screen: no screenshots, no screen recording, no preview in the recent-apps list. */
@Composable
private fun SecureWindow() {
    val act = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!AppLock.hideScreen) act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun CoinBadge(symbol: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    var bmp by remember(symbol) { mutableStateOf(CryptoIcons.peek(symbol)) }
    LaunchedEffect(symbol) { if (bmp == null) bmp = CryptoIcons.load(ctx, symbol) }
    Box(Modifier.size(size).clip(CircleShape).background(Soft), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), symbol, Modifier.fillMaxSize())
        else Text(symbol.take(1), color = GreenDark, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CopyLine(label: String, value: String) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            androidx.compose.material3.IconButton(onClick = { copyToClipboard(ctx, label, value); Ui.notify(L("Copied"), L("Copied to clipboard.")) }) {
                Icon(MudaIcons.ContentCopy, L("Copy"), tint = GreenDark)
            }
        }
    }
}

@Composable
private fun WordGrid(words: List<String>) {
    Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { i, w ->
                    Row(Modifier.weight(1f).background(Soft, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${row * 2 + i + 1}", color = Muted, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                        Text(w, color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(MudaIcons.Check, null, tint = Green, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Text(text, color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/* ------------------------------------------------------------------ root */
@Composable
fun CryptoWalletScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf(if (WalletVault.hasWallet(ctx)) WPage.Home else WPage.Intro) }
    var cfg by remember { mutableStateOf<WConfig?>(null) }
    var cfgError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var addresses by remember { mutableStateOf(WalletVault.addresses(ctx)) }
    var sendAsset by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var txRef by remember { mutableStateOf<TxRef?>(null) }

    LaunchedEffect(attempt) {
        cfgError = null
        val r = Api.call("GET", "/wallet/config")
        if (r.ok) cfg = parseConfig(r.data) else cfgError = r.message.ifEmpty { L("Could not load the wallet.") }
    }

    fun home() { addresses = WalletVault.addresses(ctx); page = if (WalletVault.hasWallet(ctx)) WPage.Home else WPage.Intro }
    BackHandler(enabled = page != WPage.Home && page != WPage.Intro) { if (page == WPage.Tx) page = WPage.History else home() }

    val c = cfg
    when {
        c == null && cfgError == null -> LoadingBox()
        c == null -> ErrorBox(cfgError ?: "", onRetry = { attempt++ })
        !c.enabled -> WalletPausedPage(onRetry = { cfg = null; attempt++ })
        else -> when (page) {
            WPage.Intro -> IntroPage(onCreate = { page = WPage.Create }, onImport = { page = WPage.Import }, onExternal = { nav("ExternalWallet") })
            WPage.Create -> CreatePage(onDone = { home() })
            WPage.Import -> ImportPage(onDone = { home() })
            WPage.Home -> {
                val a = addresses
                if (a == null) { LaunchedEffect(Unit) { home() } } else HomePage(c, a, refresh,
                    onReceive = { page = WPage.Receive },
                    onSend = { id -> sendAsset = id; page = WPage.Send },
                    onHistory = { page = WPage.History },
                    onBackup = { page = WPage.Backup },
                    onBook = { page = WPage.Book },
                    onExternal = { nav("ExternalWallet") },
                    onRemoved = { home() })
            }
            WPage.Receive -> addresses?.let { ReceivePage(c, it) }
            WPage.Send -> addresses?.let { SendPage(c, it, sendAsset, onReceive = { page = WPage.Receive }, onDone = { refresh++; home() }) }
            WPage.Backup -> BackupPage(onConfirmed = { home() })
            WPage.History -> addresses?.let { HistoryPage(c) { t -> txRef = t; page = WPage.Tx } }
            WPage.Tx -> {
                val t = txRef
                if (t == null) { LaunchedEffect(Unit) { page = WPage.History } }
                else WalletTxStatus(t.chain, t.txid, t.symbol, t.amount, t.counterparty, t.status, t.outgoing, onDone = { page = WPage.History })
            }
            WPage.Book -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) { AddressBookPanel(onPick = null) }
        }
    }
}

/** Shown while the server has the wallet switched off. */
@Composable
private fun WalletPausedPage(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(84.dp).background(Gold.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(MudaIcons.HourglassTop, null, tint = Gold, modifier = Modifier.size(44.dp))
        }
        Text(L("Wallet paused"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(L("The Crypto Wallet is paused for the moment. Your funds are safe: they stay on the blockchain and in your recovery phrase."),
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Bullet(L("Nothing is lost: your money stays on the blockchain."))
            Bullet(L("Your 12 words still restore your wallet in any compatible wallet app."))
            Bullet(L("The wallet reopens here as soon as it is switched on again."))
        }
        PrimaryButton(L("CHECK AGAIN"), onRetry, color = Dark, icon = MudaIcons.Refresh)
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ intro */
@Composable
private fun IntroStep(n: String, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(Green.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
            Text(n, color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun IntroPage(onCreate: () -> Unit, onImport: () -> Unit, onExternal: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    fun guard(go: () -> Unit) { val why = WalletVault.unavailableReason(ctx); if (why != null) error = L(why) else { error = null; go() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(MudaIcons.AccountBalanceWallet, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text(L("Your own crypto wallet"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(L("Send and receive USDT (BEP20) and BNB on BNB Chain. You hold the keys, not MudaPay."), color = Color.White.copy(alpha = .92f), fontSize = 13.sp, lineHeight = 18.sp)
        }
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(L("How it works"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            IntroStep("1", L("Create or restore"), L("Make a new wallet with 12 words, or restore one you already have."))
            IntroStep("2", L("Receive"), L("Share your address to receive BNB and USDT (BEP20)."))
            IntroStep("3", L("Send and pay"), L("Send to any BNB Chain address. Each payment is confirmed with your fingerprint or phone PIN."))
        }
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Bullet(L("Your 12-word recovery phrase is created on this phone and locked by your fingerprint or phone PIN."))
            Bullet(L("MudaPay never sees your phrase or your keys. Transactions are signed on your phone."))
            Bullet(L("The wallet is separate from your MudaPay balance."))
        }
        InfoBanner(L("Nobody can recover your wallet for you, not even MudaPay. If you lose your 12 words and your phone, the money is lost for good. Never share the words with anyone."), Gold, MudaIcons.Warning)
        error?.let { InfoBanner(it, Danger, MudaIcons.Info) }
        PrimaryButton(L("CREATE A NEW WALLET"), { guard(onCreate) }, icon = MudaIcons.Add)
        PrimaryButton(L("I ALREADY HAVE A WALLET"), { guard(onImport) }, color = Dark, icon = MudaIcons.Download)
        PrimaryButton(L("CONNECT AN EXTERNAL WALLET"), onExternal, color = Soft, contentColor = GreenDark, icon = MudaIcons.Link)
        Text(L("MetaMask, Trust Wallet and other WalletConnect wallets approve each payment in their own app."), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ create */
@Composable
private fun CreatePage(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val phrase = remember { WalletCrypto.newMnemonic() }
    val words = remember(phrase) { phrase.split(" ") }
    var step by remember { mutableIntStateOf(0) }
    var ack by remember { mutableStateOf(false) }
    val quiz = remember { words.indices.shuffled().take(3).sorted() }
    val answers = remember { mutableStateListOf("", "", "") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SecureWindow()

    fun finish(verified: Boolean) {
        val act = ctx.findActivity() ?: return
        err = null
        WalletVault.authenticate(act, L("Protect your wallet"), L("Confirm with your fingerprint or phone PIN to lock the wallet to this phone"),
            onSuccess = {
                busy = true
                scope.launch {
                    val e = withContext(Dispatchers.Default) { WalletVault.save(ctx, phrase) }
                    busy = false
                    if (e == null) { if (verified) WalletVault.markBackedUp(ctx); onDone() } else err = L(e)
                }
            },
            onError = { if (it.isNotEmpty()) err = it })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (step == 0) {
            SectionTitle(L("Your recovery phrase"), L("Write these 12 words on paper, in this order."))
            InfoBanner(L("Anyone with these words can take all your money. Never type them on a website, never send them in a message, never take a screenshot. MudaPay will never ask for them."), Danger, MudaIcons.Warning)
            WordGrid(words)
            Row(Modifier.fillMaxWidth().clickable { ack = !ack }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ack, onCheckedChange = { ack = it }, colors = CheckboxDefaults.colors(checkedColor = Green))
                Text(L("I wrote the 12 words on paper and kept them safe."), color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            PrimaryButton(L("NEXT"), { step = 1 }, enabled = ack, icon = MudaIcons.ArrowForward)
        } else {
            SectionTitle(L("Check your backup"), L("Type the words at these positions."))
            quiz.forEachIndexed { i, idx ->
                InputField("${L("Word")} #${idx + 1}", answers[i], { answers[i] = it.trim().lowercase() }, keyboard = KeyboardType.Password)
            }
            val ok = quiz.indices.all { answers[it] == words[quiz[it]] }
            err?.let { InfoBanner(it, Danger, MudaIcons.Info) }
            PrimaryButton(L("CREATE MY WALLET"), { finish(true) }, enabled = ok, loading = busy, icon = MudaIcons.Lock)
            TextButton(onClick = { step = 0; err = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(L("Show the words again"), color = GreenDark, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { finish(false) }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(L("I will check my backup later"), color = Muted, fontWeight = FontWeight.SemiBold) }
            Text(L("A reminder stays on your wallet until you confirm your 12 words."), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ import */
@Composable
private fun ImportPage(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val valid = WalletCrypto.isValidMnemonic(text)
    SecureWindow()

    fun go() {
        val act = ctx.findActivity() ?: return
        err = null
        val phrase = WalletCrypto.normalize(text)
        WalletVault.authenticate(act, L("Protect your wallet"), L("Confirm with your fingerprint or phone PIN to lock the wallet to this phone"),
            onSuccess = {
                busy = true
                scope.launch {
                    val e = withContext(Dispatchers.Default) { WalletVault.save(ctx, phrase) }
                    busy = false
                    if (e == null) { WalletVault.markBackedUp(ctx); text = ""; onDone() } else err = L(e)
                }
            },
            onError = { if (it.isNotEmpty()) err = it })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(L("Restore your wallet"), L("Type your 12 or 24 recovery words, separated by spaces."))
        InfoBanner(L("Type them only here, inside MudaPay on this phone. They are never sent to MudaPay or to anyone else. Never type them on a website or in a chat."), Gold, MudaIcons.Warning)
        InputField(L("Recovery phrase"), text, { text = it }, keyboard = KeyboardType.Password, singleLine = false, minLines = 3)
        if (text.isNotBlank() && !valid) Text(L("These words are not a valid recovery phrase. Check the spelling and the order."), color = Danger, fontSize = 12.sp)
        err?.let { InfoBanner(it, Danger, MudaIcons.Info) }
        PrimaryButton(L("RESTORE WALLET"), { go() }, enabled = valid, loading = busy, icon = MudaIcons.Lock)
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ home */
private fun parsePrices(d: JSONObject): Map<String, Double> {
    val pr = d.optJSONObject("prices") ?: return emptyMap()
    return pr.keys().asSequence().associateWith { pr.optDouble(it, 0.0) }.filterValues { it > 0.0 }
}

@Composable
private fun HomePage(
    cfg: WConfig, addr: WalletAddresses, refreshKey: Int,
    onReceive: () -> Unit, onSend: (String?) -> Unit, onHistory: () -> Unit, onBackup: () -> Unit, onBook: () -> Unit,
    onExternal: () -> Unit, onRemoved: () -> Unit
) {
    val ctx = LocalContext.current
    var balances by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var askRemove by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf(WalletPrefs.hideBalance(ctx)) }
    val backedUp = remember { WalletVault.backedUp(ctx) }

    // tell the server the two PUBLIC addresses (once per account), then read the balances
    LaunchedEffect(tick, refreshKey) {
        loading = true; loadError = null
        val uid = Session.user?.str("id") ?: ""
        if (!WalletVault.isRegisteredFor(ctx, uid)) {
            val reg = Api.call("POST", "/wallet/address", JSONObject().put("bsc", addr.bsc))
            if (reg.ok) WalletVault.markRegistered(ctx, uid) else { loadError = reg.message; loading = false; return@LaunchedEffect }
        }
        val r = Api.call("GET", "/wallet/balances")
        if (r.ok) {
            balances = r.data.list("balances").associate { it.str("asset") to it.str("amount") }
            prices = parsePrices(r.data)
            val e = r.data.optJSONObject("errors")
            errors = if (e == null) emptyMap() else e.keys().asSequence().associateWith { e.optString(it) }
        } else loadError = r.message
        loading = false
    }

    fun usdOf(a: WalletAsset): BigDecimal? = walletUsdValue(balances[a.id], prices[a.symbol])
    val total: BigDecimal? = if (prices.isEmpty() || balances.isEmpty()) null
        else cfg.usable().mapNotNull { usdOf(it) }.fold(BigDecimal.ZERO) { x, y -> x.add(y) }
    val totalText = when {
        hidden -> "••••••"
        loading -> "…"
        total == null -> "–"
        else -> walletUsd(total)
    }
    val allZero = !loading && loadError == null && cfg.usable().all { (balances[it.id]?.toBigDecimalOrNull()?.signum() ?: 0) == 0 }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!backedUp) {
            Column(Modifier.fillMaxWidth().background(Gold.copy(alpha = .14f), RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(MudaIcons.Warning, null, tint = Gold, modifier = Modifier.size(22.dp))
                    Text(L("Back up your recovery phrase"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Text(L("You have not confirmed your 12 words yet. If you lose this phone without them, your money is lost for good."), color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
                PrimaryButton(L("BACK UP NOW"), onBackup, icon = MudaIcons.Security)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Total balance"), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                androidx.compose.material3.IconButton(onClick = { hidden = !hidden; WalletPrefs.setHideBalance(ctx, hidden) }) {
                    Icon(if (hidden) MudaIcons.VisibilityOff else MudaIcons.Visibility, L("Hide or show the balance"), tint = Muted, modifier = Modifier.size(19.dp))
                }
            }
            Text(totalText, color = Dark, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, maxLines = 1)
            Text(L("Your keys stay on this phone. Separate from your MudaPay balance."), color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(L("Receive"), MudaIcons.SouthWest, Modifier.weight(1f), accent = true, onClick = onReceive)
            ActionTile(L("Send"), MudaIcons.NorthEast, Modifier.weight(1f)) { onSend(null) }
        }
        loadError?.let { InfoBanner(it, Danger, MudaIcons.Info) }
        errors.forEach { (chain, msg) -> InfoBanner("${cfg.chainName(chain)}: $msg", Gold, MudaIcons.Warning) }

        Column(Modifier.fillMaxWidth().card().padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("Assets"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = { tick++ }) { Icon(MudaIcons.Refresh, L("Refresh"), tint = GreenDark) }
            }
            if (loading) LoadingBox()
            else cfg.usable().forEach { a ->
                val bal = balances[a.id]
                val usd = usdOf(a)
                Row(Modifier.fillMaxWidth().clickable(enabled = a.canSend) { onSend(a.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoinBadge(a.symbol)
                    Column(Modifier.weight(1f)) {
                        Text(a.symbol, color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(cfg.chainName(a.chain), color = Muted, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (hidden) "••••" else if (bal == null) "–" else shown(bal), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (usd != null) Text(if (hidden) "••••" else "≈ " + walletUsd(usd), color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        if (allZero) {
            Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(56.dp).background(Green.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(MudaIcons.AccountBalanceWallet, null, tint = GreenDark, modifier = Modifier.size(30.dp))
                }
                Text(L("Your wallet is ready"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(L("Receive BNB or USDT (BEP20) to get started. Keep a little BNB for network fees."), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
                PrimaryButton(L("SHOW MY ADDRESS"), onReceive, icon = MudaIcons.SouthWest)
            }
        }
        InfoBanner(L("Network fees on BNB Chain are paid in BNB. Keep a little BNB in the wallet, even when you send USDT."), Blue, MudaIcons.Info)

        Column(Modifier.fillMaxWidth().card()) {
            WalletRow(L("Transaction history"), MudaIcons.History, onHistory)
            WalletRow(L("Address book"), MudaIcons.Contacts, onBook)
            WalletRow(L("Connect external wallet"), MudaIcons.Link, onExternal)
            WalletRow(L("Back up recovery phrase"), MudaIcons.Security, onBackup)
            WalletRow(L("Remove wallet from this phone"), MudaIcons.Warning, { removeError = null; askRemove = true }, tint = Danger)
        }

        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(MudaIcons.Security, null, tint = GreenDark, modifier = Modifier.size(20.dp))
                Text(L("Security tips"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Bullet(L("Never share your 12 words. MudaPay will never ask for them."))
            Bullet(L("Never type your words on a website or in a chat."))
            Bullet(L("Check the address and the network before every payment: it cannot be undone."))
            Bullet(L("Keep a little BNB in the wallet to pay network fees."))
        }
        Text(L("Wallet addresses are public. You can safely share them to receive money."), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }

    if (askRemove) {
        AlertDialog(
            onDismissRequest = { askRemove = false },
            containerColor = Panel,
            title = { Text(L("Remove the wallet?"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L("The wallet will be deleted from this phone only. Your money stays on the blockchain, but you can reach it again ONLY with your 12 recovery words."), color = Ink, fontSize = 13.sp)
                    Text(L("If you do not have the words, do not continue."), color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    removeError?.let { Text(it, color = Danger, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val act = ctx.findActivity() ?: return@TextButton
                    WalletVault.authenticate(act, L("Remove wallet"), L("Confirm it's you to remove the wallet from this phone"),
                        onSuccess = { WalletVault.remove(ctx); askRemove = false; onRemoved() },
                        onError = { if (it.isNotEmpty()) removeError = it })
                }) { Text(L("Remove"), color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askRemove = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

/** Balance x USD price, or null when either is missing. */
private fun walletUsdValue(amount: String?, price: Double?): BigDecimal? {
    if (amount == null || price == null || price <= 0.0) return null
    val a = amount.toBigDecimalOrNull() ?: return null
    return a.multiply(BigDecimal.valueOf(price))
}

@Composable
private fun WalletRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, tint: Color = Dark) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = if (tint == Danger) Danger else GreenDark, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(MudaIcons.ChevronRight, null, tint = Muted)
    }
}

/* ------------------------------------------------------------------ receive */
@Composable
private fun ReceivePage(cfg: WConfig, addr: WalletAddresses) {
    val ctx = LocalContext.current
    val chains = cfg.chains.filter { it.enabled }
    var chain by remember { mutableStateOf(chains.firstOrNull()?.id ?: "bsc") }
    val address = addr.forChain(chain)
    val qr = remember(address) { DotQr.render(address, 900, logo = false)?.asImageBitmap() }
    val coins = cfg.assets.filter { it.chain == chain }.joinToString(", ") { it.symbol }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(L("Receive"), L("Choose the network, then share the address."))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chains.forEach { c ->
                val on = c.id == chain
                Text(c.name, color = if (on) Color.White else Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Green else Soft).clickable { chain = c.id }.padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
        InfoBanner(
            if (chain == "bsc") L("BNB Chain only: send only BNB and USDT (BEP20) to this address. Coins sent on another network are lost.")
            else "${L("Send only")} $coins ${L("on the network")} ${cfg.chainName(chain)}. ${L("Coins sent on another network are lost.")}",
            Gold, MudaIcons.Warning
        )
        Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (qr != null) Box(Modifier.background(Color.White, RoundedCornerShape(18.dp)).padding(12.dp)) {
                Image(qr, L("Wallet address QR code"), Modifier.size(260.dp))
            }
            CopyLine(L("Your address"), address)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(L("COPY"), { copyToClipboard(ctx, L("Your address"), address); Ui.notify(L("Copied"), L("Copied to clipboard.")) }, Modifier.weight(1f), icon = MudaIcons.ContentCopy)
            GhostButton(L("SHARE"), { shareText(ctx, address) }, Modifier.weight(1f), icon = MudaIcons.Share)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ send */
private class SentResult(val txid: String, val chain: String)

/** A transaction id is 32 bytes of hex. Anything else (for example odd text from a server) never reaches an explorer link. */
private val TXID_RX = Regex("^(0x)?[0-9a-fA-F]{64}$")

private const val MAX_GAS_PRICE_WEI = 30_000_000_000L   // 30 gwei: BNB Chain is normally 1-5 gwei
private const val MAX_GAS_LIMIT = 250_000L
private const val LOW_BNB = "0.0003"                      // below this the member is warned before even trying: a USDT transfer costs about 0.0001 to 0.0003 BNB

/** "Add BNB for fees": network fees on BNB Chain are always paid in BNB. */
@Composable
private fun FeeWarning(message: String, onReceive: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Gold.copy(alpha = .12f), RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(MudaIcons.Warning, null, tint = Gold, modifier = Modifier.size(20.dp))
            Text(L("Add BNB for fees"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(L(message), color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
        PrimaryButton(L("RECEIVE BNB"), onReceive, color = Dark, icon = MudaIcons.SouthWest)
    }
}

/** The address in bold, its first and last characters highlighted: the part people compare. */
private fun highlightedAddress(a: String) = buildAnnotatedString {
    val hi = SpanStyle(color = GreenDark, fontWeight = FontWeight.ExtraBold, background = Green.copy(alpha = .14f))
    if (a.length <= 12) {
        withStyle(hi) { append(a) }
    } else {
        withStyle(hi) { append(a.take(6)) }
        withStyle(SpanStyle(color = Dark, fontWeight = FontWeight.Bold)) { append(a.substring(6, a.length - 6)) }
        withStyle(hi) { append(a.takeLast(6)) }
    }
}

@Composable
private fun ConfirmRow(label: String, value: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.End)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp, textAlign = TextAlign.End)
        }
    }
}

/** What the member sees right before the fingerprint prompt: everything that will be signed, nothing else. */
@Composable
private fun ConfirmSheet(
    amountText: String, symbol: String, amountUsd: String?, network: String, from: String, to: String, contactLabel: String?,
    feeText: String, feeUsd: String?, totalText: String?, totalSub: String?, err: String?, busy: Boolean,
    onConfirm: () -> Unit, onCancel: () -> Unit
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Confirm payment"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Column(Modifier.fillMaxWidth().heroCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(softCase(L("YOU SEND")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("$amountText $symbol", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (amountUsd != null) Text("≈ $amountUsd", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
            Text("${L("on")} $network", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("To"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (contactLabel != null) Pill(contactLabel, Green) else Pill(L("New recipient"), Gold)
            }
            Text(highlightedAddress(to), fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(2.dp))
            ConfirmRow(L("From"), walletShort(from))
            ConfirmRow(L("Network"), network)
            ConfirmRow(L("Network fee"), feeText, feeUsd?.let { "≈ $it" })
            if (totalText != null) ConfirmRow(L("Total"), totalText, totalSub)
        }
        InfoBanner(L("Check the address and the network. If they are wrong, the money is lost and nobody can get it back."), Gold, MudaIcons.Warning)
        err?.let { InfoBanner(it, Danger, MudaIcons.Info) }
        PrimaryButton(L("CONFIRM AND SEND"), onConfirm, loading = busy, icon = MudaIcons.Lock)
        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(L("Cancel"), color = Muted, fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendPage(cfg: WConfig, addr: WalletAddresses, initial: String?, onReceive: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val options = cfg.usable().filter { it.canSend }
    var assetId by remember { mutableStateOf(initial?.takeIf { id -> options.any { it.id == id } } ?: options.firstOrNull()?.id ?: "") }
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var evm by remember { mutableStateOf<EvmPlan?>(null) }       // set by the review step = "the data that will be signed"
    var tron by remember { mutableStateOf<TronPlan?>(null) }
    var feeText by remember { mutableStateOf("") }
    var feeNative by remember { mutableStateOf<BigDecimal?>(null) }   // the fee in BNB (BNB Chain), for the total and its USD value
    var idem by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var needFee by remember { mutableStateOf<String?>(null) }    // "not enough BNB for the fee" message from the server
    var balances by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var sent by remember { mutableStateOf<SentResult?>(null) }
    var showScan by remember { mutableStateOf(false) }
    var showBook by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    var bookTick by remember { mutableIntStateOf(0) }
    val toClean = to.trim()
    val saved = remember(toClean, bookTick) { WalletPrefs.contacts(ctx).firstOrNull { it.address.equals(toClean, ignoreCase = true) } }
    val asset = cfg.asset(assetId)

    LaunchedEffect(Unit) {
        val r = Api.call("GET", "/wallet/balances")
        if (r.ok) {
            balances = r.data.list("balances").associate { it.str("asset") to it.str("amount") }
            prices = parsePrices(r.data)
        }
    }

    if (asset == null) { Column(Modifier.padding(18.dp)) { InfoBanner(L("Nothing can be sent right now."), Gold, MudaIcons.Info) }; return }
    val from = addr.forChain(asset.chain)
    val network = cfg.chainName(asset.chain)
    val raw = WalletCrypto.toRaw(amount, asset.decimals)
    val exact = raw?.let { WalletCrypto.fromRaw(it, asset.decimals) } ?: ""   // EXACTLY what will be signed (never rounded)
    val addressOk = WalletCrypto.isAddressFor(asset.chain, toClean)
    val reviewing = evm != null || tron != null
    val balance = balances[assetId]
    val bnbBalance = balances["bnb"]?.toBigDecimalOrNull()
    val lowBnb = asset.chain == "bsc" && balances.isNotEmpty() && (bnbBalance == null || bnbBalance < BigDecimal(LOW_BNB))

    val s = sent
    if (s != null) {
        WalletTxStatus(s.chain, s.txid, asset.symbol, exact, toClean, "pending", true, onDone = onDone)
        return
    }

    fun reset() { evm = null; tron = null; err = null; needFee = null; feeNative = null }

    fun review() {
        if (raw == null || !addressOk) return
        busy = true; err = null; needFee = null
        scope.launch {
            val r = Api.call("POST", "/wallet/prepare", JSONObject().put("asset", asset.id).put("from", from).put("to", toClean).put("amount", amount.trim().replace(',', '.')))
            busy = false
            if (!r.ok) { err = r.message; return@launch }
            val d = r.data
            if (!d.bool("fee_ok")) {
                val m = d.str("message").ifEmpty { L("Not enough balance to pay the network fee.") }
                if (m.startsWith("Not enough BNB") || m.startsWith("Not enough TRX")) needFee = m else err = m
                return@launch
            }
            try {
                // the plan is read and checked HERE, before the member confirms, so the fee shown is the fee that will be signed
                if (asset.chain == "tron") {
                    val plan = TronPlan(
                        hexToBytes(d.str("ref_block_bytes")) ?: ByteArray(0), hexToBytes(d.str("ref_block_hash")) ?: ByteArray(0),
                        d.optLong("timestamp_ms"), d.optLong("expiration_ms"), d.optLong("fee_limit_sun")
                    )
                    if (plan.refBlockBytes.size != 2 || plan.refBlockHash.size != 8 || plan.feeLimitSun !in 1_000_000L..100_000_000L) throw IllegalStateException("bad plan")
                    feeText = "${L("up to")} ${shown(WalletCrypto.fromRaw(BigInteger.valueOf(plan.feeLimitSun), 6))} TRX"
                    feeNative = null
                    tron = plan; evm = null
                } else {
                    val gasPrice = BigInteger(d.opt("gas_price").toString())
                    val gasLimit = BigInteger(d.opt("gas_limit").toString())
                    if (gasPrice.signum() <= 0 || gasPrice > BigInteger.valueOf(MAX_GAS_PRICE_WEI) ||
                        gasLimit < BigInteger.valueOf(21_000L) || gasLimit > BigInteger.valueOf(MAX_GAS_LIMIT)) {
                        err = L("The network fee is unusually high right now. Try again later."); return@launch
                    }
                    val plan = EvmPlan(d.optLong("chain_id"), BigInteger(d.opt("nonce").toString()), gasPrice, gasLimit)
                    if (plan.chainId != 56L || plan.nonce.signum() < 0) throw IllegalStateException("bad plan")
                    val feeExact = WalletCrypto.fromRaw(gasPrice.multiply(gasLimit), 18)
                    feeText = "${shown(feeExact)} BNB"
                    feeNative = BigDecimal(feeExact)
                    evm = plan; tron = null
                }
                idem = newIdemKey()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { reset(); err = L("The network data could not be read. Try again in a moment.") }
        }
    }

    fun signAndSend() {
        val evmPlan = evm; val tronPlan = tron
        val act = ctx.findActivity() ?: return
        val rawAmount = raw ?: return
        if (evmPlan == null && tronPlan == null) return
        err = null
        WalletVault.withPhrase(act, L("Confirm payment"), "${L("Send")} $exact ${asset.symbol}", onError = { if (it.isNotEmpty()) err = it }) { phrase ->
            busy = true
            scope.launch {
                val signed: SignedTx? = try {
                    withContext(Dispatchers.Default) {
                        if (asset.chain == "tron" && tronPlan != null) WalletCrypto.signTronTrc20(phrase, asset, from, toClean, rawAmount, tronPlan)
                        else if (evmPlan != null) WalletCrypto.signBsc(phrase, asset, from, toClean, rawAmount, evmPlan)
                        else throw IllegalStateException(L("The transaction could not be signed."))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { val m = e.message ?: L("The transaction could not be signed."); reset(); err = m; null }
                if (signed == null) { busy = false; return@launch }
                val body = JSONObject().put("chain", asset.chain).put("asset", asset.id).put("signed", signed.hex).put("from", from).put("to", toClean).put("amount", exact)
                val r = Api.call("POST", "/wallet/broadcast", body, idem = idem)
                busy = false
                if (r.ok) sent = SentResult(signed.txid, asset.chain)
                else err = r.message
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(L("Send"), L("Payments on the blockchain cannot be undone."))
        Dropdown(L("Asset"), assetId, options.map { it.id to "${it.symbol} · ${cfg.chainName(it.chain)}" }) { assetId = it; to = ""; amount = ""; reset() }
        Text("${L("Available")}: ${balance?.let { shown(it) } ?: "…"} ${asset.symbol}", color = Muted, fontSize = 12.sp)
        if (lowBnb) FeeWarning("Your BNB balance is very low. Network fees on BNB Chain are paid in BNB, even when you send USDT.", onReceive)

        InputField(L("Recipient address") + " ($network)", to, { to = it; err = null; needFee = null })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WalletChip(MudaIcons.ContentCopy, L("Paste")) { to = walletPaste(ctx); err = null; needFee = null }
            WalletChip(MudaIcons.QrCodeScanner, L("Scan QR")) { showScan = true }
            WalletChip(MudaIcons.Contacts, L("Address book")) { showBook = true }
        }
        if (toClean.isNotEmpty() && !addressOk) Text("${L("This is not a valid address on")} $network.", color = Danger, fontSize = 12.sp)
        if (addressOk && toClean.equals(from, ignoreCase = true)) Text(L("This is your own address."), color = Gold, fontSize = 12.sp)
        if (addressOk && saved != null) Text("${L("Saved as")}: ${saved.label}", color = GreenDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (addressOk && saved == null && asset.chain == "bsc") {
            TextButton(onClick = { askSave = true }) { Text(L("Save to address book"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }

        InputField(L("Amount") + " (${asset.symbol})", amount, { amount = it; err = null; needFee = null }, keyboard = KeyboardType.Decimal, trailing = {
            if (asset.contract != null && balance != null) TextButton(onClick = { amount = balance }) { Text(L("MAX"), color = GreenDark, fontWeight = FontWeight.Bold) }
        })
        if (amount.isNotEmpty() && raw == null) Text(L("Enter a valid amount."), color = Danger, fontSize = 12.sp)
        needFee?.let { FeeWarning(it, onReceive) }
        if (!reviewing) err?.let { InfoBanner(it, Danger, MudaIcons.Info) }
        PrimaryButton(L("REVIEW"), { review() }, enabled = raw != null && addressOk && !reviewing, loading = busy && !reviewing, icon = MudaIcons.ArrowForward)
        Spacer(Modifier.height(8.dp))
    }

    if (showScan) WalletQrScanDialog(onAddress = { to = it; err = null; needFee = null; showScan = false }, onClose = { showScan = false })

    if (showBook) {
        ModalBottomSheet(onDismissRequest = { showBook = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 22.dp)) {
                AddressBookPanel(onPick = { c -> to = c.address; err = null; needFee = null; showBook = false }, onChanged = { bookTick++ })
            }
        }
    }

    if (askSave) {
        SaveContactDialog(toClean, onSave = { name ->
            val e = WalletPrefs.saveContact(ctx, name, toClean)
            if (e == null) { bookTick++; askSave = false }
            e
        }, onDismiss = { askSave = false })
    }

    if (reviewing) {
        val fee = feeNative
        val amountBd = exact.toBigDecimalOrNull()
        val isNative = asset.contract == null
        // BNB Chain: a BNB payment costs amount + fee (both BNB); a USDT payment is the USDT amount plus a fee paid in BNB
        val totalText: String? = if (asset.chain == "bsc" && fee != null && amountBd != null) {
            if (isNative) "${shown(amountBd.add(fee).toPlainString())} BNB" else "$exact ${asset.symbol}"
        } else null
        val totalSub: String? = if (asset.chain == "bsc" && fee != null && amountBd != null) {
            if (isNative) walletUsdOf(amountBd.add(fee), prices["BNB"])?.let { "≈ $it" }
            else "+ ${shown(fee.toPlainString())} BNB"
        } else null
        ModalBottomSheet(onDismissRequest = { if (!busy) reset() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
            ConfirmSheet(
                amountText = exact, symbol = asset.symbol, amountUsd = walletUsdOf(amountBd, prices[asset.symbol]),
                network = network, from = from, to = toClean, contactLabel = saved?.label,
                feeText = feeText, feeUsd = if (fee != null) walletUsdOf(fee, prices["BNB"]) else null,
                totalText = totalText, totalSub = totalSub, err = err, busy = busy,
                onConfirm = { signAndSend() }, onCancel = { reset() }
            )
        }
    }
}

/* ------------------------------------------------------------------ backup */
@Composable
private fun BackupPage(onConfirmed: () -> Unit) {
    val ctx = LocalContext.current
    var words by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var quiz by remember { mutableStateOf<List<Int>?>(null) }
    val answers = remember { mutableStateListOf("", "", "") }
    val alreadyConfirmed = remember { WalletVault.backedUp(ctx) }
    SecureWindow()
    DisposableEffect(Unit) {
        val owner = ctx.findActivity()
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { words = null; quiz = null } }
        owner?.lifecycle?.addObserver(obs)
        onDispose { words = null; owner?.lifecycle?.removeObserver(obs) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(L("Recovery phrase"), L("The only way to restore your wallet on a new phone."))
        InfoBanner(L("Anyone with these words can take all your money. Make sure nobody is looking at your screen. MudaPay will never ask for them."), Danger, MudaIcons.Warning)
        val w = words
        val q = quiz
        if (w == null) {
            err?.let { InfoBanner(it, Danger, MudaIcons.Info) }
            PrimaryButton(L("SHOW MY 12 WORDS"), {
                val act = ctx.findActivity() ?: return@PrimaryButton
                err = null
                WalletVault.withPhrase(act, L("Recovery phrase"), L("Confirm it's you to show the recovery phrase"), onError = { if (it.isNotEmpty()) err = it }) { phrase ->
                    words = phrase.split(" ")
                }
            }, icon = MudaIcons.Visibility)
        } else if (q != null) {
            SectionTitle(L("Check your backup"), L("Type the words at these positions."))
            q.forEachIndexed { i, idx ->
                InputField("${L("Word")} #${idx + 1}", answers[i], { answers[i] = it.trim().lowercase() }, keyboard = KeyboardType.Password)
            }
            val ok = q.indices.all { answers[it] == w[q[it]] }
            PrimaryButton(L("CONFIRM MY BACKUP"), { WalletVault.markBackedUp(ctx); words = null; quiz = null; onConfirmed() }, enabled = ok, icon = MudaIcons.Check)
            TextButton(onClick = { quiz = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(L("Show the words again"), color = GreenDark, fontWeight = FontWeight.Bold) }
        } else {
            WordGrid(w)
            if (!alreadyConfirmed) {
                InfoBanner(L("Write the words on paper, then confirm your backup so this reminder goes away."), Blue, MudaIcons.Info)
                PrimaryButton(L("I WROTE THEM DOWN: CHECK MY BACKUP"), {
                    for (i in 0 until 3) answers[i] = ""
                    quiz = w.indices.shuffled().take(3).sorted()
                }, icon = MudaIcons.Check)
            }
            PrimaryButton(L("HIDE"), { words = null }, color = Dark, icon = MudaIcons.VisibilityOff)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ history */
@Composable
private fun HistoryPage(cfg: WConfig, onOpen: (TxRef) -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var incoming by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        val r = Api.call("GET", "/wallet/history", query = mapOf("limit" to "30"))
        if (r.ok) { items = r.data.list("items"); incoming = r.data.optBoolean("incoming_available", true) } else error = r.message
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(L("Transaction history"))
        val list = items
        when {
            error != null -> ErrorBox(error ?: "", onRetry = { attempt++ })
            list == null -> LoadingBox()
            list.isEmpty() -> EmptyState(L("No transactions yet"), MudaIcons.History, L("Money you send or receive with this wallet will appear here."))
            else -> Column(Modifier.fillMaxWidth().card().padding(vertical = 4.dp)) {
                list.forEach { t ->
                    val out = t.str("direction") == "out"
                    val a = cfg.asset(t.str("asset"))
                    val canOpen = TXID_RX.matches(t.str("txid"))
                    val ref = TxRef(t.str("chain"), t.str("txid"), a?.symbol ?: "", t.str("amount"), t.str("counterparty"), t.str("status"), out)
                    Row(Modifier.fillMaxWidth().clickable(enabled = canOpen) { onOpen(ref) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(38.dp).background(if (out) Soft else Green.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(if (out) MudaIcons.NorthEast else MudaIcons.SouthWest, null, tint = if (out) Dark else GreenDark, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(if (out) L("Sent") else L("Received"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${shortAddr(t.str("counterparty"))} · ${t.str("time").take(16).replace('T', ' ')}", color = Muted, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${if (out) "-" else "+"}${shown(t.str("amount"))} ${a?.symbol ?: ""}", color = if (out) Dark else GreenDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            val st = t.str("status")
                            Pill(L(st.replaceFirstChar { it.uppercase() }), when (st) { "confirmed" -> Green; "failed" -> Danger; else -> Gold })
                        }
                    }
                }
            }
        }
        if (!incoming && error == null) InfoBanner(L("Incoming BNB Chain transfers are not listed here yet. They still appear in your balance."), Blue, MudaIcons.Info)
        Spacer(Modifier.height(8.dp))
    }
}
