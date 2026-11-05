package io.mudapay.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Trade: buy and sell coins inside MudaPay (no broker). The app only talks to the MudaPay server (/trade/...).
 * The server decides every price, fee and limit; the app shows what the server answers and asks for the transaction PIN.
 *
 *   Trade -> [Markets | Portfolio | Orders]  ->  coin screen (bid / ask, buy or sell, market or limit)
 */
object TradeNav {
    /** Set by another screen (Markets) to open Trade directly on one coin. */
    var symbol by mutableStateOf<String?>(null)
    /** "buy" or "sell": opens the quick entry screen straight away. */
    var side by mutableStateOf<String?>(null)
}

private fun tUsd(v: Double): String = if (v >= 1.0) String.format(Locale.US, "%,.2f", v) else String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
private fun tPct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)
private fun tLocal(sym: String, v: Double): String = money(sym, v)
private fun tDisplayLocal(sym: String, v: Double): String =
    if (v >= 100.0) money(sym, Math.round(v).toDouble()) else sym + " " + String.format(Locale.US, "%,.4f", v).trimEnd('0').trimEnd('.')
private fun tPart(owned: String, percent: Int): String {
    val o = owned.toBigDecimalOrNull() ?: return ""
    if (percent >= 100) return owned
    return o.multiply(BigDecimal(percent)).divide(BigDecimal(100), 8, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
}

@Composable
fun TradeScreen(nav: (String) -> Unit) {
    var key by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf(TradeNav.symbol) }
    var entry by remember { mutableStateOf(if (TradeNav.symbol != null) TradeNav.side else null) }
    LaunchedEffect(Unit) { TradeNav.symbol = null; TradeNav.side = null }
    BackHandler(enabled = entry != null || detail != null) { if (entry != null) entry = null else detail = null }
    Remote(refreshKey = key, call = { Api.call("GET", "/trade/status") }) { st, _ ->
        val d = detail
        val e = entry
        if (d != null && e != null) TradeEntry(d, e, st, onClose = { entry = null }, onDone = { key++ })
        else if (d != null) TradeCoin(d, st, onBack = { detail = null }, onTrade = { side -> entry = side })
        else TradeHome(st, onOpen = { detail = it })
    }
}

@Composable
private fun TradeHome(st: JSONObject, onOpen: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val sym = st.str("symbol")
    val names = listOf(L("Markets"), L("Portfolio"), L("Orders"))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(L("Wallet balance"), color = Muted, fontSize = 12.sp)
            Text(tLocal(sym, st.dbl("balance")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            if (st.dbl("held_in_orders") > 0) Text(L("Held in open orders") + ": " + tLocal(sym, st.dbl("held_in_orders")), color = Muted, fontSize = 12.sp)
        }
        if (!st.bool("tradable")) InfoBanner(L(st.str("reason", "Trading is not available right now.")), Danger, MudaIcons.Info)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { i, n ->
                val on = tab == i
                Text(
                    n, color = if (on) Color.White else GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .then(if (on) Modifier.card(RoundedCornerShape(12.dp), Green) else Modifier.border(1.dp, GreenDark.copy(alpha = .4f), RoundedCornerShape(12.dp)))
                        .clickable { tab = i }.padding(vertical = 10.dp)
                )
            }
        }
        when (tab) {
            0 -> TradeMarkets(onOpen)
            1 -> TradePortfolio(onOpen)
            else -> TradeOrders()
        }
        Text(
            L("Trades are made with MudaPay at the price shown, including a small spread and fee. Prices move fast and you can lose money. MudaPay does not give investment advice or price predictions."),
            color = Muted, fontSize = 11.sp, lineHeight = 15.sp
        )
    }
}

/* ------------------------------------------------------------------ markets */
@Composable
private fun TradeMarkets(onOpen: (String) -> Unit) {
    Remote(call = { Api.call("GET", "/trade/assets") }) { d, _ ->
        val sym = d.str("symbol")
        val items = d.list("assets")
        if (items.isEmpty()) Text(L("Nothing found."), color = Muted, fontSize = 13.sp)
        items.forEach { a ->
            val ch = if (a.isNull("change")) null else a.dbl("change")
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onOpen(a.str("symbol")) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.str("symbol"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(a.str("name"), color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (a.isNull("ask_local")) "-" else tDisplayLocal(sym, a.dbl("ask_local")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (ch != null) Text(tPct(ch), color = if (ch >= 0) GreenDark else Danger, fontSize = 12.sp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ portfolio */
@Composable
private fun TradePortfolio(onOpen: (String) -> Unit) {
    Remote(call = { Api.call("GET", "/trade/portfolio") }) { p, _ ->
        val sym = p.str("symbol")
        val t = p.optJSONObject("totals") ?: JSONObject()
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("Value of your coins"), color = Muted, fontSize = 12.sp)
            Text(tLocal(sym, t.dbl("value")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            if (!t.isNull("pnl")) {
                val pnl = t.dbl("pnl")
                Text(tLocal(sym, pnl) + (if (t.isNull("pnl_pct")) "" else " (" + tPct(t.dbl("pnl_pct")) + ")"), color = if (pnl >= 0) GreenDark else Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(L("Profit already made"), color = Muted, fontSize = 12.sp); Text(tLocal(sym, t.dbl("realized")), color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        SectionHeader(L("Your coins"))
        val items = p.list("items")
        if (items.isEmpty()) Text(L("You do not own any coin yet."), color = Muted, fontSize = 13.sp)
        items.forEach { x ->
            val pnl = if (x.isNull("pnl")) null else x.dbl("pnl")
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onOpen(x.str("symbol")) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(x.str("symbol"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(x.str("qty") + (if (x.str("locked") != "0") " • " + L("locked") + " " + x.str("locked") else ""), color = Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (x.isNull("value")) "-" else tLocal(sym, x.dbl("value")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (pnl != null) Text(tLocal(sym, pnl) + (if (x.isNull("pnl_pct")) "" else " (" + tPct(x.dbl("pnl_pct")) + ")"), color = if (pnl >= 0) GreenDark else Danger, fontSize = 12.sp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ orders */
@Composable
private fun TradeOrders() {
    val scope = rememberCoroutineScope()
    var key by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    error?.let { InfoBanner(it, Danger, MudaIcons.Info) }
    Remote(refreshKey = "o$key", call = { Api.call("GET", "/trade/orders", query = mapOf("status" to "open")) }) { d, _ ->
        val items = d.list("orders")
        if (items.isNotEmpty()) SectionHeader(L("Waiting for the price"))
        items.forEach { o -> TradeOrderCard(o, onCancel = {
            scope.launch {
                val r = Api.call("POST", "/trade/orders/${o.int("id")}/cancel")
                error = if (r.ok) null else r.message
                if (r.ok) Session.balance = r.data.dbl("balance")
                key++
            }
        }) }
    }
    SectionHeader(L("History"))
    Remote(refreshKey = "h$key", call = { Api.call("GET", "/trade/orders", query = mapOf("status" to "history")) }) { d, _ ->
        val items = d.list("orders")
        if (items.isEmpty()) Text(L("No orders yet."), color = Muted, fontSize = 13.sp)
        items.forEach { o -> TradeOrderCard(o, onCancel = null) }
    }
}

@Composable
private fun TradeOrderCard(o: JSONObject, onCancel: (() -> Unit)?) {
    val st = o.str("status")
    val tone = when (st) { "filled" -> GreenDark; "canceled", "expired", "rejected" -> Danger; else -> Blue }
    val sym = o.str("symbol_local")
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (o.str("side") == "buy") L("Buy") else L("Sell")) + " " + o.str("qty") + " " + o.str("symbol"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Pill(L(st), tone)
        }
        Text((if (o.str("type") == "limit") L("Limit") else L("Market")) + " • " + o.str("created_at").take(16), color = Muted, fontSize = 12.sp)
        if (!o.isNull("limit_usd") && st == "open") Text(L("Limit price") + ": USD " + tUsd(o.dbl("limit_usd")), color = Ink, fontSize = 12.sp)
        if (!o.isNull("price_local")) Text(L("Price") + ": " + tDisplayLocal(sym, o.dbl("price_local")) + " • " + L("Fee") + ": " + tLocal(sym, o.dbl("fee")), color = Ink, fontSize = 12.sp)
        if (!o.isNull("total")) Text((if (o.str("side") == "buy") L("You paid") else L("You received")) + ": " + tLocal(sym, o.dbl("total")), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (o.dbl("reserved") > 0) Text(L("Money held") + ": " + tLocal(sym, o.dbl("reserved")), color = Muted, fontSize = 12.sp)
        if (!o.isNull("realized")) {
            val r = o.dbl("realized")
            Text(L("Profit / loss") + ": " + tLocal(sym, r), color = if (r >= 0) GreenDark else Danger, fontSize = 12.sp)
        }
        if (onCancel != null && o.bool("cancelable")) {
            Text(softCase(L("CANCEL ORDER")), color = Danger, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onCancel).padding(vertical = 6.dp))
        }
    }
}

/* ------------------------------------------------------------------ coin screen + ticket */
@Composable
private fun TradeCoin(symbol: String, st: JSONObject, onBack: () -> Unit, onTrade: (String) -> Unit) {
    var asset by remember(symbol) { mutableStateOf<JSONObject?>(null) }
    var loadErr by remember(symbol) { mutableStateOf<String?>(null) }
    LaunchedEffect(symbol) {
        while (true) {
            val r = Api.call("GET", "/trade/assets")
            if (r.ok) { asset = r.data.list("assets").firstOrNull { it.str("symbol") == symbol }; loadErr = null } else loadErr = r.message
            delay(8000)
        }
    }
    val sym = st.str("symbol")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("<  " + L("Back"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp))
        val a = asset
        if (a == null) {
            Text(loadErr ?: L("Loading..."), color = Muted, fontSize = 13.sp)
        } else {
            val ch = if (a.isNull("change")) null else a.dbl("change")
            Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.str("name") + " (" + symbol + ")", color = Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("USD " + tUsd(a.dbl("price_usd")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                if (ch != null) Text(tPct(ch) + " " + L("today"), color = if (ch >= 0) GreenDark else Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (!a.isNull("ask_local")) Text(L("Buy at") + " " + tDisplayLocal(sym, a.dbl("ask_local")) + " • " + L("Sell at") + " " + tDisplayLocal(sym, a.dbl("bid_local")), color = Muted, fontSize = 12.sp)
                Text(L("You own") + ": " + a.str("qty") + " " + symbol, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Green).clickable { onTrade("buy") }.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                    Text(L("Buy"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Danger).clickable { onTrade("sell") }.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                    Text(L("Sell"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Text(L("You see the exact price, fee and total before you confirm. Your transaction PIN is needed to place the order."), color = Muted, fontSize = 11.sp)
        }
    }
}

/* ------------------------------------------------------------------ quick entry screen (big amount + own keypad + slide to confirm) */
private val EBg = Color(0xFF0E111B)
private val EPanel = Color(0xFF1B2030)
private val EMuted = Color(0xFF8D93A6)
private val EBuy = Color(0xFF2DB872)
private val ESell = Color(0xFFE0484F)

/** One key press on the on-screen keypad: digits, ".", and "<" (backspace). */
private fun eNext(cur: String, k: String, maxDec: Int): String {
    if (k == "<") return cur.dropLast(1)
    if (k == ".") return if (maxDec == 0 || cur.contains('.')) cur else (if (cur.isEmpty()) "0." else "$cur.")
    if (cur == "0") return k
    val dot = cur.indexOf('.')
    if (dot >= 0 && cur.length - dot - 1 >= maxDec) return cur
    if (cur.length >= 14) return cur
    return cur + k
}
private fun eFloor(x: Double, scale: Int): String =
    if (x.isNaN() || x.isInfinite() || x <= 0.0) "" else BigDecimal(x).setScale(scale, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

@Composable
private fun EKeypad(onKey: (String) -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "<"))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { k ->
                    Box(Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).clickable { onKey(k) }, contentAlignment = Alignment.Center) {
                        Text(if (k == "<") "⌫" else k, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlideToConfirm(label: String, color: Color, enabled: Boolean, busy: Boolean, onConfirm: () -> Unit) {
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    val thumbPx = with(density) { 52.dp.toPx() }
    val padPx = with(density) { 4.dp.toPx() }
    val maxOff = (widthPx - thumbPx - padPx * 2).coerceAtLeast(1f)
    Box(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(EPanel).onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, busy, widthPx) {
                detectHorizontalDragGestures(
                    onDragEnd = { val ok = offset >= maxOff * 0.85f; offset = 0f; if (ok && enabled && !busy) onConfirm() },
                    onDragCancel = { offset = 0f }
                ) { change, dx ->
                    change.consume()
                    if (enabled && !busy) offset = (offset + dx).coerceIn(0f, maxOff)
                }
            }
    ) {
        Text(label, color = if (enabled) Color.White else EMuted, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        Box(
            Modifier.offset { IntOffset((offset + padPx).roundToInt(), padPx.roundToInt()) }.size(52.dp).clip(RoundedCornerShape(26.dp))
                .background(if (enabled) color else color.copy(alpha = .4f)),
            contentAlignment = Alignment.Center
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
            else Text("›", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TradeEntry(symbol: String, side: String, st: JSONObject, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sym = st.str("symbol")
    val buy = side == "buy"
    val accent = if (buy) EBuy else ESell
    val fee = st.dbl("fee_pct")
    val feeF = fee / 100.0
    val fx = st.dbl("fx_rate")
    var asset by remember(symbol) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(symbol) {
        while (true) {
            val r = Api.call("GET", "/trade/assets")
            if (r.ok) asset = r.data.list("assets").firstOrNull { it.str("symbol") == symbol }
            delay(6000)
        }
    }
    var coinUnit by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var limitMode by remember { mutableStateOf(false) }
    var limitText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf("main") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    var askPin by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var idem by remember { mutableStateOf(newIdemKey()) }

    val a = asset
    val owned = a?.str("qty") ?: "0"
    val unitPrice = if (a == null) 0.0 else if (buy) a.dbl("ask_local") else a.dbl("bid_local")     // local currency per coin
    val value = text.toDoubleOrNull() ?: 0.0
    val limitUsd = limitText.toDoubleOrNull() ?: 0.0
    val priceLocalForQty = if (limitMode && limitUsd > 0 && fx > 0) limitUsd * fx else unitPrice

    /** Coin quantity as a string when the order must be sent by quantity, null when it is a plain "spend this much" buy. */
    fun qtyOrNull(): String? {
        if (coinUnit) return text
        if (buy && !limitMode) return null
        if (priceLocalForQty <= 0.0) return ""
        val q = if (buy) value / (1.0 + feeF) / priceLocalForQty else value / priceLocalForQty
        return eFloor(q, 8)
    }
    fun query(): Map<String, String> {
        val m = HashMap<String, String>()
        m["symbol"] = symbol; m["side"] = side
        val q = qtyOrNull()
        if (q == null) m["amount"] = text else m["qty"] = q
        if (limitMode) m["limit_price"] = limitText
        return m
    }

    val eq: String = when {
        value <= 0.0 || unitPrice <= 0.0 -> if (coinUnit) tLocal(sym, 0.0) else "0 $symbol"
        coinUnit -> "≈ " + tDisplayLocal(sym, value * unitPrice)
        else -> "≈ " + (eFloor(if (buy) value / (1.0 + feeF) / unitPrice else value / unitPrice, 8).ifEmpty { "0" }) + " " + symbol
    }
    val ready = st.bool("tradable") && value > 0.0 && (!limitMode || limitUsd > 0.0) && (!buy || a?.bool("can_buy") != false) && a != null

    fun key(k: String) {
        error = null
        if (editing == "limit" && limitMode) limitText = eNext(limitText, k, 8)
        else text = eNext(text, k, if (coinUnit) 8 else 2)
    }
    fun chip(p: Int) {
        error = null; editing = "main"
        if (buy) {
            val spend = st.dbl("balance") * p / 100.0
            if (coinUnit) text = eFloor(if (unitPrice > 0) spend / (1.0 + feeF) / unitPrice else 0.0, 8)
            else text = eFloor(spend, 2)
        } else {
            coinUnit = true
            text = tPart(owned, p)
        }
    }

    if (askPin) {
        val q = pending
        PinPromptDialog("Confirm your order", onDismiss = { askPin = false; pending = null }, onSubmit = { pin ->
            askPin = false; busy = true
            scope.launch {
                val body = JSONObject().put("symbol", symbol).put("side", side).put("pin", pin)
                val qs = qtyOrNull()
                if (qs == null) body.put("amount", text) else body.put("qty", qs)
                if (limitMode) body.put("limit_price", limitText)
                if (q != null && q.bool("immediate")) body.put("price_hint", q.dbl("exec_usd"))
                val r = Api.call("POST", "/trade/orders", body, idem = idem, timeoutMs = 45000)
                busy = false; pending = null
                if (r.http != 0) idem = newIdemKey()
                if (r.ok) {
                    Session.balance = r.data.dbl("balance")
                    result = r.data
                    onDone()
                } else error = r.message
            }
        })
    }

    val done = result
    if (done != null) {
        val sl = done.str("symbol_local")
        Column(Modifier.fillMaxSize().background(EBg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("✓", color = accent, fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(if (done.str("status") == "filled") L("Order done") else L("Order placed, waiting for your price"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text((if (buy) L("Buy") else L("Sell")) + " " + done.str("qty") + " " + done.str("symbol"), color = Color.White, fontSize = 16.sp)
            if (!done.isNull("total")) Text((if (buy) L("You paid") else L("You received")) + ": " + tLocal(sl, done.dbl("total")), color = EMuted, fontSize = 14.sp)
            else if (done.dbl("reserved") > 0) Text(L("Money held") + ": " + tLocal(sl, done.dbl("reserved")), color = EMuted, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            PrimaryButton(L("Done"), onClose, color = Green)
        }
        return
    }

    Column(Modifier.fillMaxSize().background(EBg).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(EPanel).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text((if (buy) L("Buy") else L("Sell")) + " " + symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(if (a == null) "…" else "USD " + tUsd(a.dbl("price_usd")), color = EMuted, fontSize = 13.sp)
            }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(if (limitMode) accent.copy(alpha = .35f) else EPanel)
                .clickable { limitMode = !limitMode; editing = if (limitMode) "limit" else "main"; error = null }, contentAlignment = Alignment.Center) {
                Text("⚙", color = Color.White, fontSize = 18.sp)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(Modifier.clickable { editing = "main" }, verticalAlignment = Alignment.Bottom) {
                if (!coinUnit) Text(sym + " ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                Text(if (text.isEmpty()) "0" else text, color = if (text.isEmpty()) EMuted else Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (coinUnit) Text(" $symbol", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            }
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { coinUnit = !coinUnit; text = ""; editing = "main"; error = null }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(eq + "  ⇅", color = EMuted, fontSize = 16.sp)
            }
            if (limitMode) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (editing == "limit") EPanel else Color.Transparent).clickable { editing = "limit" }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Limit price") + " (USD): ", color = EMuted, fontSize = 14.sp)
                    Text(if (limitText.isEmpty()) "0" else limitText, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            error?.let { Text(it, color = ESell, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)) }
            if (!st.bool("tradable")) Text(L(st.str("reason", "Trading is not available right now.")), color = ESell, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (buy) L("Pay with") else L("Receive in"), color = EMuted, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(L("Wallet") + " (" + sym + ")", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(L("Available") + ": " + (if (buy) tLocal(sym, st.dbl("balance")) else owned + " " + symbol), color = EMuted, fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(25, 50, 100).forEach { p ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(EPanel).clickable { chip(p) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("$p%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        EKeypad(::key)
        Spacer(Modifier.height(6.dp))
        SlideToConfirm(if (buy) L("Slide to buy") else L("Slide to sell"), accent, ready, busy) {
            error = null; busy = true
            scope.launch {
                val r = Api.call("GET", "/trade/quote", query = query())
                busy = false
                if (r.ok) { pending = r.data; askPin = true } else error = r.message
            }
        }
        Text(
            L("Pricing details") + "  ·  " + L("Fee") + " " + String.format(Locale.US, "%.2f", fee).trimEnd('0').trimEnd('.') + "% + " + L("spread") + " " + String.format(Locale.US, "%.2f", st.dbl("spread_pct")).trimEnd('0').trimEnd('.') + "%",
            color = EMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
        )
    }
}
