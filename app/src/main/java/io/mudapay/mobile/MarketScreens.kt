package io.mudapay.mobile

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/* ------------------------------------------------------------------ Market (information only: no advice, no predictions)
 * Data comes ONLY from the MudaPay server (/market/...), which asks Binance, OKX, Bybit, Coinbase, CoinGecko, Frankfurter ...
 * (no API key) and caches the answers. The phone never talks to those providers directly. */

private val MkBg: Color get() = Page
private val MkPanel: Color get() = Panel
private val MkPanel2: Color get() = Soft
private val MkLine: Color get() = Line
private val MkText: Color get() = Dark
private val MkMuted: Color get() = Muted
private val MkUp = Color(0xFF0ECB81)
private val MkDown = Color(0xFFF6465D)
private val MkGold = Color(0xFFF0B90B)
private val MkPink = Color(0xFFEB40B5)

private class MkCoin(
    val symbol: String, val name: String, val price: Double, val change: Double?,
    val high: Double, val low: Double, val volume: Double, val spark: List<Double>
)
private class MkCandle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)
private class MkFxItem(val code: String, val name: String, val cc: String, val rate: Double, val conf: String, val updated: Long)

private fun mkCoin(o: JSONObject) = MkCoin(
    o.str("symbol"), o.str("name"), o.dbl("price"), if (o.isNull("change")) null else o.dbl("change"),
    o.dbl("high"), o.dbl("low"), o.dbl("volume"), o.doubles("spark")
)

private val MK_COLORS = mapOf(
    "BTC" to 0xFFF7931A, "ETH" to 0xFF627EEA, "BNB" to 0xFFF0B90B, "SOL" to 0xFF9945FF, "XRP" to 0xFF346AA9, "DOGE" to 0xFFC2A633,
    "TRX" to 0xFFEB0029, "ADA" to 0xFF0033AD, "TON" to 0xFF0098EA, "LTC" to 0xFF345D9D, "AVAX" to 0xFFE84142, "LINK" to 0xFF2A5ADA,
    "DOT" to 0xFFE6007A, "BCH" to 0xFF8DC351, "XLM" to 0xFF14B6E7, "SHIB" to 0xFFFFA409
)
private val MK_GLYPH = mapOf("BTC" to "₿", "ETH" to "Ξ", "LTC" to "Ł", "DOGE" to "Ð")

/* ------------------------------------------------------------------ formatting */

private fun mkPrice(p: Double): String {
    val a = abs(p)
    val d = when { a >= 1.0 -> 2; a >= 0.1 -> 4; a >= 0.01 -> 5; a >= 0.0001 -> 6; else -> 8 }
    return String.format(Locale.US, "%,." + d + "f", p)
}
private fun mkCompact(v: Double): String = when {
    v >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
    v >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
    v >= 1e3 -> String.format(Locale.US, "%.2fK", v / 1e3)
    else -> String.format(Locale.US, "%.2f", v)
}
private fun mkPct(c: Double?): String = if (c == null) "—" else String.format(Locale.US, "%+.2f%%", c)
private fun mkRate(r: Double): String = when {
    r >= 1000 -> String.format(Locale.US, "%,.2f", r)
    r >= 1 -> String.format(Locale.US, "%,.4f", r)
    else -> String.format(Locale.US, "%.6f", r)
}
private fun mkLocal(v: Double): String = when {
    v >= 100 -> String.format(Locale.US, "%,.0f", v)
    v >= 1 -> String.format(Locale.US, "%,.2f", v)
    else -> String.format(Locale.US, "%,.4f", v)
}
private fun mkColor(ch: Double?): Color = if (ch == null) MkMuted else if (ch >= 0) MkUp else MkDown
private fun mkTime(ts: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts * 1000))
/** Country flag of a currency, loaded from the MudaPay server (SITE/flags/xx.png) and cached on the phone.
 *  While it loads, or when the server has no flag for it, a clean badge with the currency code is drawn (a globe when there is no code). */
@Composable
private fun MkFlagView(cc: String, size: Dp, code: String = "") {
    val app = LocalContext.current.applicationContext
    val key = cc.lowercase()
    val valid = key.length == 2 && key.all { it in 'a'..'z' }
    var bmp by remember(key) { mutableStateOf(if (valid) FlagIcons.peek(key) else null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { if (valid && bmp == null) { bmp = FlagIcons.load(app, key); failed = bmp == null } }
    val shown by animateFloatAsState(if (bmp != null) 1f else 0f, tween(200), label = "mkflag")
    val b = bmp
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(MkPanel2, MkLine)))
            .border(1.dp, MkLine, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = shown }, contentScale = ContentScale.Crop)
        else if (!valid || failed) {
            if (code.length in 2..4) Text(code.take(3), color = MkText, fontSize = (size.value * 0.32f).sp, fontWeight = FontWeight.Bold)
            else Icon(Icons.Filled.Public, null, tint = MkMuted, modifier = Modifier.size(size * 0.6f))
        }
    }
}
private fun mkSource(s: String): String = when (s) {
    "binance" -> "Binance"; "okx" -> "OKX"; "bybit" -> "Bybit"; "coinbase" -> "Coinbase"; "coingecko" -> "CoinGecko"
    "frankfurter" -> "Frankfurter"; "currency-api" -> "currency-api"; else -> s
}
private fun mkConf(c: String): Pair<String, Color> = when (c) {
    "confirmed" -> Pair("Confirmed", MkUp)
    "single" -> Pair("1 source", MkGold)
    "major" -> Pair("", MkMuted)
    else -> Pair("Estimate", MkGold)
}

/** Favourite coins (star), kept on the phone. */
private object MkFav {
    var items by mutableStateOf<Set<String>>(emptySet())
    private var loaded = false
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        items = ctx.getSharedPreferences("mk_prefs", Context.MODE_PRIVATE).getStringSet("fav", emptySet())?.toSet() ?: emptySet()
    }
    fun toggle(ctx: Context, s: String) {
        items = if (s in items) items - s else items + s
        ctx.getSharedPreferences("mk_prefs", Context.MODE_PRIVATE).edit().putStringSet("fav", HashSet(items)).apply()
    }
}

/** Coins the member added to his own list with the search (kept on the phone, sent to the server as /market/coins?extra=). */
private object MkMine {
    const val MAX = 40
    var items by mutableStateOf<List<String>>(emptyList())
    val seed = HashMap<String, MkCoin>()   // row of a coin just added, shown at once until the server list answers
    private var loaded = false
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mk_prefs", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val ok = Regex("[A-Z0-9]{1,12}")
        items = (prefs(ctx).getString("mine", "") ?: "").split(",").filter { ok.matches(it) }.distinct().take(MAX)
    }
    /** false when the list is full. */
    fun add(ctx: Context, c: MkCoin): Boolean {
        if (c.symbol in items) return true
        if (items.size >= MAX) return false
        seed[c.symbol] = c
        items = items + c.symbol
        prefs(ctx).edit().putString("mine", items.joinToString(",")).apply()
        return true
    }
    fun remove(ctx: Context, symbol: String) {
        items = items - symbol
        seed.remove(symbol)
        prefs(ctx).edit().putString("mine", items.joinToString(",")).apply()
    }
}

/* ------------------------------------------------------------------ small pieces */

@Composable
private fun MkTopBar(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("Back"), tint = MkText) }
        Text(title, color = MkText, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun MkLiveBadge(text: String) {
    val t = rememberInfiniteTransition(label = "mklive")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "mkalpha")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MkUp.copy(alpha = a)))
        Spacer(Modifier.width(6.dp))
        Text(text, color = MkMuted, fontSize = 12.sp)
    }
}

@Composable
private fun MkChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) MkPanel2 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (on) MkGold else MkMuted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun MkCoinIcon(sym: String, size: Dp) {
    val app = LocalContext.current.applicationContext
    var bmp by remember(sym) { mutableStateOf(CryptoIcons.peek(sym)) }
    LaunchedEffect(sym) { if (bmp == null) bmp = CryptoIcons.load(app, sym) }   // from the MudaPay server, cached on the phone
    val col = MK_COLORS[sym]?.let { Color(it) } ?: Color.hsv((abs(sym.hashCode()) % 360).toFloat(), 0.55f, 0.78f)
    val shown by animateFloatAsState(if (bmp != null) 1f else 0f, tween(220), label = "mkicon")
    val b = bmp
    Box(
        Modifier.size(size).drawBehind {   // soft glow in the coin's own colour
            val r = this.size.minDimension * 0.85f
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.30f), Color.Transparent), center = center, radius = r), radius = r)
        },
        contentAlignment = Alignment.Center
    ) {
        if (shown < 1f) {
            Box(Modifier.size(size).clip(CircleShape).background(col).graphicsLayer { alpha = 1f - shown }, contentAlignment = Alignment.Center) {
                Text(MK_GLYPH[sym] ?: sym.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
            }
        }
        if (b != null) Image(b.asImageBitmap(), sym, Modifier.size(size).clip(CircleShape).graphicsLayer { alpha = shown })
    }
}

@Composable
private fun MkSpark(v: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (v.size < 2) return@Canvas
        val lo = v.minOrNull() ?: 0.0
        val hi = v.maxOrNull() ?: 1.0
        val rng = if (hi - lo <= 0.0) 1.0 else hi - lo
        val path = Path()
        v.forEachIndexed { i, p ->
            val x = size.width * i / (v.size - 1)
            val y = size.height * (1f - ((p - lo) / rng).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun MkSearch(value: String, placeholder: String, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MkPanel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = MkMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = MkMuted, fontSize = 14.sp)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = MkText, fontSize = 14.sp), cursorBrush = SolidColor(MkGold), modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Filled.Close, L("Clear"), tint = MkMuted, modifier = Modifier.size(18.dp).clickable { onChange("") })
        }
    }
}

@Composable
private fun MkTabs(tab: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf(L("Crypto"), L("Currencies")).forEachIndexed { i, label ->
            Column(Modifier.clickable { onTab(i) }.padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = if (tab == i) MkText else MkMuted, fontSize = 17.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(26.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (tab == i) MkGold else Color.Transparent))
            }
        }
    }
}

@Composable
private fun MkStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, color = MkMuted, fontSize = 11.sp)
        Text(value, color = MkText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MkNote(source: String, ts: Long, delayed: Boolean, extra: String? = null) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (source.isNotEmpty()) {
            Text(L("Source") + ": " + mkSource(source) + (if (ts > 0) " · " + mkTime(ts) else ""), color = MkMuted, fontSize = 11.sp)
        }
        if (delayed) Text(L("These prices come from a daily feed and may be delayed."), color = MkGold, fontSize = 11.sp)
        if (extra != null) Text(extra, color = MkMuted, fontSize = 11.sp)
        Text(L("Market prices are shown for information only. MudaPay does not give investment advice or price predictions."), color = MkMuted, fontSize = 11.sp)
    }
}

/* ------------------------------------------------------------------ the chart (candles + volume, MA, crosshair) */

@Composable
private fun MkChart(
    candles: List<MkCandle>, line: Boolean, ma: Boolean, hasVolume: Boolean, range: String,
    cursor: Int?, onCursor: (Int?) -> Unit, modifier: Modifier = Modifier, fmt: (Double) -> String = ::mkPrice
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axisPx = with(density) { 62.dp.toPx() }
    var wPx by remember { mutableFloatStateOf(0f) }
    val n = candles.size
    val cursorNow = rememberUpdatedState(cursor)
    val onCur = rememberUpdatedState(onCursor)
    val axisStyle = remember { TextStyle(color = MkMuted, fontSize = 10.sp) }
    val tickFmt = remember(range) {
        SimpleDateFormat(
            when (range) { "1D" -> "HH:mm"; "1W" -> "EEE d"; "1Y" -> "MMM yy"; else -> "d MMM" }, Locale.getDefault()
        )
    }
    val fullFmt = remember(range) {
        SimpleDateFormat(if (range == "1D" || range == "1W" || range == "1M") "d MMM HH:mm" else "d MMM yyyy", Locale.getDefault())
    }
    val idxAt = { x: Float ->
        val pw = (wPx - axisPx).coerceAtLeast(1f)
        ((x / pw) * n).toInt().coerceIn(0, max(0, n - 1))
    }

    Canvas(
        modifier.fillMaxSize().onSizeChanged { wPx = it.width.toFloat() }
            .pointerInput(n) {
                detectTapGestures(onTap = { o ->
                    if (n > 0) { val i = idxAt(o.x); onCur.value(if (cursorNow.value == i) null else i) }
                })
            }
            .pointerInput(n) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> if (n > 0) onCur.value(idxAt(o.x)) },
                    onDrag = { ch, _ -> ch.consume(); if (n > 0) onCur.value(idxAt(ch.position.x)) },
                    onDragEnd = { onCur.value(null) },
                    onDragCancel = { onCur.value(null) }
                )
            }
    ) {
        if (n < 2) return@Canvas
        val topPad = 8.dp.toPx()
        val timeH = 20.dp.toPx()
        val plotW = size.width - axisPx
        val usableH = size.height - timeH - topPad
        val gap = if (hasVolume) 8.dp.toPx() else 0f
        val volH = if (hasVolume) usableH * 0.2f else 0f
        val priceH = usableH - volH - gap
        val slot = plotW / n
        val bw = max(1f, slot * 0.72f)
        val hair = max(1f, density.density * 0.8f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (c in candles) {
            if (line) { lo = min(lo, c.c); hi = max(hi, c.c) } else { lo = min(lo, c.l); hi = max(hi, c.h) }
        }
        if (hi <= lo) hi = lo * 1.001 + 1e-9
        val padv = (hi - lo) * 0.08
        lo -= padv; hi += padv
        fun y(p: Double): Float = topPad + ((hi - p) / (hi - lo)).toFloat() * priceH
        fun x(i: Int): Float = (i + 0.5f) * slot

        // grid + price axis
        for (k in 0..4) {
            val p = hi - (hi - lo) * k / 4.0
            val yy = y(p)
            drawLine(MkLine.copy(alpha = 0.55f), Offset(0f, yy), Offset(plotW, yy), strokeWidth = 1f)
            val tl = measurer.measure(fmt(p), axisStyle)
            drawText(tl, topLeft = Offset(plotW + 6.dp.toPx(), yy - tl.size.height / 2f))
        }
        // time axis
        for (k in 0..3) {
            val i = (((n - 1) * (k + 0.5f)) / 4f).toInt().coerceIn(0, n - 1)
            val tl = measurer.measure(tickFmt.format(Date(candles[i].t)), axisStyle)
            val left = (x(i) - tl.size.width / 2f).coerceIn(0f, max(0f, plotW - tl.size.width))
            drawText(tl, topLeft = Offset(left, size.height - timeH + 4.dp.toPx()))
        }

        val lastUp = candles.last().c >= candles.last().o
        val trendUp = candles.last().c >= candles.first().c
        if (line) {
            val lc = if (trendUp) MkUp else MkDown
            val path = Path()
            candles.forEachIndexed { i, c -> if (i == 0) path.moveTo(x(i), y(c.c)) else path.lineTo(x(i), y(c.c)) }
            val fill = Path().apply {
                addPath(path)
                lineTo(x(n - 1), topPad + priceH)
                lineTo(x(0), topPad + priceH)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(lc.copy(alpha = 0.28f), Color.Transparent), startY = topPad, endY = topPad + priceH))
            drawPath(path, lc, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            for (i in 0 until n) {
                val c = candles[i]
                val col = if (c.c >= c.o) MkUp else MkDown
                val xx = x(i)
                drawLine(col, Offset(xx, y(c.h)), Offset(xx, y(c.l)), strokeWidth = hair)
                val top = y(max(c.o, c.c))
                val bot = y(min(c.o, c.c))
                drawRect(col, Offset(xx - bw / 2f, top), Size(bw, max(1f, bot - top)))
            }
            if (ma) {
                fun drawMa(period: Int, col: Color) {
                    if (n < period) return
                    val path = Path()
                    var started = false
                    var sum = 0.0
                    for (i in 0 until n) {
                        sum += candles[i].c
                        if (i >= period) sum -= candles[i - period].c
                        if (i >= period - 1) {
                            val yy = y(sum / period)
                            if (!started) { path.moveTo(x(i), yy); started = true } else path.lineTo(x(i), yy)
                        }
                    }
                    drawPath(path, col, style = Stroke(width = 1.dp.toPx()))
                }
                drawMa(7, MkGold)
                drawMa(25, MkPink)
            }
        }
        if (hasVolume) {
            var maxV = 0.0
            for (c in candles) maxV = max(maxV, c.v)
            if (maxV > 0) {
                val vTop = topPad + priceH + gap
                for (i in 0 until n) {
                    val c = candles[i]
                    val h = (c.v / maxV).toFloat() * volH
                    drawRect((if (c.c >= c.o) MkUp else MkDown).copy(alpha = 0.5f), Offset(x(i) - bw / 2f, vTop + volH - h), Size(bw, max(1f, h)))
                }
            }
        }

        // last price: dashed line + tag on the axis
        val lastP = candles.last().c
        val ly = y(lastP)
        val lcol = if (line) (if (trendUp) MkUp else MkDown) else (if (lastUp) MkUp else MkDown)
        drawLine(lcol.copy(alpha = 0.85f), Offset(0f, ly), Offset(plotW, ly), strokeWidth = 1f, pathEffect = dash)
        val ltl = measurer.measure(fmt(lastP), TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold))
        val lh = ltl.size.height + 6.dp.toPx()
        drawRoundRect(lcol, Offset(plotW + 2.dp.toPx(), ly - lh / 2f), Size(axisPx - 2.dp.toPx(), lh), CornerRadius(4.dp.toPx()))
        drawText(ltl, topLeft = Offset(plotW + 6.dp.toPx(), ly - ltl.size.height / 2f))

        // crosshair
        val ci = cursor?.coerceIn(0, n - 1)
        if (ci != null) {
            val c = candles[ci]
            val cx = x(ci)
            val cy = y(c.c)
            drawLine(MkMuted, Offset(cx, topPad), Offset(cx, size.height - timeH), strokeWidth = 1f, pathEffect = dash)
            drawLine(MkMuted, Offset(0f, cy), Offset(plotW, cy), strokeWidth = 1f, pathEffect = dash)
            drawCircle(MkText, 3.dp.toPx(), Offset(cx, cy))
            val ptl = measurer.measure(fmt(c.c), TextStyle(color = MkText, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            val ph = ptl.size.height + 6.dp.toPx()
            drawRoundRect(MkPanel2, Offset(plotW + 2.dp.toPx(), cy - ph / 2f), Size(axisPx - 2.dp.toPx(), ph), CornerRadius(4.dp.toPx()))
            drawText(ptl, topLeft = Offset(plotW + 6.dp.toPx(), cy - ptl.size.height / 2f))
            val ttl = measurer.measure(fullFmt.format(Date(c.t)), TextStyle(color = MkText, fontSize = 10.sp))
            val tw = ttl.size.width + 12.dp.toPx()
            val tx = (cx - tw / 2f).coerceIn(0f, max(0f, plotW - tw))
            drawRoundRect(MkPanel2, Offset(tx, size.height - timeH + 1.dp.toPx()), Size(tw, timeH - 2.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawText(ttl, topLeft = Offset(tx + 6.dp.toPx(), size.height - timeH + 4.dp.toPx()))
        }
    }
}

/* ------------------------------------------------------------------ the screen */

@Composable
fun MarketScreen(nav: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MkFav.load(ctx); MkMine.load(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<MkCoin?>(null) }
    var openCoin by remember { mutableStateOf<String?>(null) }
    var openFx by remember { mutableStateOf<String?>(null) }
    var coins by remember { mutableStateOf<List<MkCoin>>(emptyList()) }
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fx by remember { mutableStateOf<JSONObject?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    // coin list: the server caches it for 15 s, so asking every 15 s is cheap
    val mine = MkMine.items
    val mineKey = mine.joinToString(",")
    LaunchedEffect(Ui.refreshTick, retry, mineKey) {
        while (true) {
            val r = if (mineKey.isEmpty()) Api.call("GET", "/market/coins") else Api.call("GET", "/market/coins", query = mapOf("extra" to mineKey))
            if (r.ok) { coins = r.data.list("coins").map { mkCoin(it) }; meta = r.data; error = null }
            else if (coins.isEmpty()) error = r.message
            delay(15000)
        }
    }
    LaunchedEffect(Ui.refreshTick, retry) {
        while (true) {
            val r = Api.call("GET", "/market/fx")
            if (r.ok) fx = r.data
            delay(180000)
        }
    }
    BackHandler(enabled = openCoin != null || openFx != null) { openCoin = null; openFx = null }

    // the server list + the coins just added (shown at once, before the next answer)
    val shown = coins + mine.mapNotNull { s -> if (coins.none { it.symbol == s }) MkMine.seed[s] else null }
    val toggleCoin: (MkCoin) -> Unit = { c ->
        if (c.symbol in MkMine.items) MkMine.remove(ctx, c.symbol)
        else if (!MkMine.add(ctx, c)) Ui.notify(L("Markets"), L("You can add up to 40 coins."))
    }
    val oc = openCoin
    val of = openFx
    Column(Modifier.fillMaxSize().background(MkBg).statusBarsPadding()) {
        when {
            oc != null -> {
                val c = shown.firstOrNull { it.symbol == oc } ?: preview?.takeIf { it.symbol == oc }
                if (c != null) MkCoinDetail(
                    c, meta, inList = shown.any { it.symbol == oc }, mine = oc in mine,
                    onToggleList = { val wasMine = oc in MkMine.items; toggleCoin(c); if (wasMine) openCoin = null },
                    onBack = { openCoin = null }
                )
                else { MkTopBar(oc + "/USDT", { openCoin = null }); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MkGold) } }
            }
            of != null -> {
                val item = mkFxItems(fx).firstOrNull { it.code == of }
                if (item != null) MkFxDetail(item, { openFx = null })
                else { MkTopBar("USD/" + of, { openFx = null }); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MkGold) } }
            }
            else -> MkList(
                tab, { tab = it }, shown, meta, error, fx, { openCoin = it }, { openFx = it }, onBack, { error = null; retry++ },
                mine = mine, onPreview = { preview = it; openCoin = it.symbol }, onToggle = toggleCoin
            )
        }
    }
}

private fun mkFxItems(fx: JSONObject?): List<MkFxItem> {
    if (fx == null) return emptyList()
    val mine = fx.str("mine")
    val own = fx.list("items").map { o ->
        val names = o.strings("countries")
        val ccs = o.strings("cc")
        MkFxItem(o.str("code"), names.take(3).joinToString(", ") + (if (names.size > 3) " +" + (names.size - 3) else ""), ccs.firstOrNull() ?: "", o.dbl("rate"), o.str("confidence"), o.optLong("updated", 0L))
    }.sortedWith(compareBy<MkFxItem>({ it.code != mine }, { it.code }))
    val majors = fx.list("majors").map { o -> MkFxItem(o.str("code"), o.str("name"), o.str("cc"), o.dbl("rate"), "major", 0L) }
    return own + majors
}

@Composable
private fun MkList(
    tab: Int, onTab: (Int) -> Unit, coins: List<MkCoin>, meta: JSONObject?, error: String?, fx: JSONObject?,
    onCoin: (String) -> Unit, onFx: (String) -> Unit, onBack: () -> Unit, onRetry: () -> Unit,
    mine: List<String> = emptyList(), onPreview: (MkCoin) -> Unit = {}, onToggle: (MkCoin) -> Unit = {}
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    val fxo = meta?.optJSONObject("fx")
    val fxCode = fxo?.str("code") ?: ""
    val fxRate = fxo?.dbl("rate") ?: 0.0
    val q = query.trim().lowercase(Locale.getDefault())

    // search on the server: any coin of the catalogue, not only the ones already in the list
    var discover by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<List<MkCoin>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var remoteErr by remember { mutableStateOf(false) }
    val wantRemote = tab == 0 && (q.length >= 2 || (discover && q.isEmpty()))
    LaunchedEffect(q, discover, tab) {
        if (!wantRemote) { remote = emptyList(); searching = false; remoteErr = false; return@LaunchedEffect }
        searching = true; remoteErr = false
        if (q.isNotEmpty()) delay(350)   // wait for the member to stop typing
        val r = Api.call("GET", "/market/search", query = mapOf("q" to q))
        if (r.ok) remote = r.data.list("coins").map { mkCoin(it) } else { remote = emptyList(); remoteErr = true }
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        MkTopBar(L("Markets"), onBack) { MkLiveBadge(L("Live")) }
        MkTabs(tab, onTab)
        MkSearch(query, if (tab == 0) L("Search any coin, e.g. PEPE") else L("Search currency")) { query = it }

        if (tab == 0) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                MkChip("＋ " + L("Add coin"), discover) { discover = !discover; if (discover) { sort = 0; query = "" } }
                MkChip(L("Volume"), sort == 0 && !discover) { sort = 0; discover = false }
                MkChip("★ " + L("Favorites"), sort == 1 && !discover) { sort = 1; discover = false }
                MkChip(L("Gainers"), sort == 2 && !discover) { sort = 2; discover = false }
                MkChip(L("Losers"), sort == 3 && !discover) { sort = 3; discover = false }
            }
            val showDiscover = discover && q.isEmpty()
            var rows = coins.filter { q.isEmpty() || it.symbol.lowercase(Locale.US).contains(q) || it.name.lowercase(Locale.getDefault()).contains(q) }
            rows = when (sort) {
                1 -> rows.filter { it.symbol in MkFav.items }
                2 -> rows.sortedByDescending { it.change ?: -999.0 }
                3 -> rows.sortedBy { it.change ?: 999.0 }
                else -> rows.sortedByDescending { it.volume }
            }
            val extraRows = remote.filter { r -> coins.none { it.symbol == r.symbol } }   // search: coins not already in the list above
            if (!showDiscover) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(L("Name"), color = MkMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(L("Last price"), color = MkMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text(L("24h change"), color = MkMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
                }
            }
            if (coins.isEmpty() && !showDiscover) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (error != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Text(error, color = MkMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(14.dp))
                            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(MkGold).clickable(onClick = onRetry).padding(horizontal = 22.dp, vertical = 10.dp)) {
                                Text(L("Try again"), color = Color.Black, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else CircularProgressIndicator(color = MkGold)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (showDiscover) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(L("Popular coins"), color = MkText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(L("Tap + to add a coin to your list. Search above to find any other coin."), color = MkMuted, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                        items(remote, key = { "d" + it.symbol }) { c -> MkAddRow(c, c.symbol in mine, { onToggle(c) }, { onPreview(c) }) }
                        if (searching) item { MkBusy() }
                        else if (remote.isEmpty()) item {
                            Text(if (remoteErr) L("Search failed. Check your connection and try again.") else L("No coin found."), color = MkMuted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                        }
                    } else {
                        items(rows, key = { it.symbol }) { c ->
                            MkCoinRow(c, fxCode, fxRate, { onCoin(c.symbol) })
                        }
                        if (q.length >= 2) {
                            if (extraRows.isNotEmpty()) {
                                item { Text(L("More coins"), color = MkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) }
                                items(extraRows, key = { "s" + it.symbol }) { c -> MkAddRow(c, c.symbol in mine, { onToggle(c) }, { onPreview(c) }) }
                            }
                            if (searching) item { MkBusy() }
                        }
                        if (rows.isEmpty() && extraRows.isEmpty() && !searching) item {
                            Text(
                                when {
                                    remoteErr && q.length >= 2 -> L("Search failed. Check your connection and try again.")
                                    sort == 1 && q.isEmpty() -> L("Tap the star on a coin to add it here.")
                                    else -> L("No coin found.")
                                },
                                color = MkMuted, fontSize = 14.sp, modifier = Modifier.padding(24.dp)
                            )
                        }
                        item { MkNote(meta?.str("source") ?: "", meta?.optLong("ts", 0L) ?: 0L, meta?.bool("delayed") ?: false) }
                    }
                }
            }
        } else {
            val fxRows = mkFxItems(fx).filter { q.isEmpty() || it.code.lowercase(Locale.US).contains(q) || it.name.lowercase(Locale.getDefault()).contains(q) }
            if (fx == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MkGold) }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    item {
                        Text(L("1 USD in each currency. Daily mid-market rates."), color = MkMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    items(fxRows, key = { it.code }) { f -> MkFxRow(f, { onFx(f.code) }) }
                    item {
                        MkNote("", 0L, false, L("Exchange rates are updated once a day by free providers, so these charts show daily points plus the latest rate."))
                    }
                }
            }
        }
    }
}

@Composable
private fun MkCoinRow(c: MkCoin, fxCode: String, fxRate: Double, onClick: () -> Unit) {
    val col = mkColor(c.change)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            MkCoinIcon(c.symbol, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(c.symbol, color = MkText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text("/USDT", color = MkMuted, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 2.dp, bottom = 1.dp))
                }
                Text(c.name + "  ·  " + mkCompact(c.volume), color = MkMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MkSpark(c.spark, col, Modifier.width(44.dp).height(24.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            Text(mkPrice(c.price), color = MkText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (fxCode.isNotEmpty() && fxRate > 0) Text(fxCode + " " + mkLocal(c.price * fxRate), color = MkMuted, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(76.dp).height(32.dp).clip(RoundedCornerShape(6.dp)).background(if (c.change == null) MkPanel2 else col), contentAlignment = Alignment.Center) {
            Text(mkPct(c.change), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MkBusy() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MkGold, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

/** A coin found by the search: tap the round button to add it to (or take it out of) the list, tap the row to look at it first. */
@Composable
private fun MkAddRow(c: MkCoin, added: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MkCoinIcon(c.symbol, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.symbol, color = MkText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(c.name, color = MkMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(mkPrice(c.price), color = MkText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(mkPct(c.change), color = mkColor(c.change), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (added) MkUp.copy(alpha = 0.18f) else MkGold).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (added) Icons.Filled.Check else Icons.Filled.Add, L(if (added) "Remove from my list" else "Add to my list"), tint = if (added) MkUp else Color.Black, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MkFxRow(f: MkFxItem, onClick: () -> Unit) {
    val conf = mkConf(f.conf)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MkFlagView(f.cc, 40.dp, f.code)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.code, color = MkText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(f.name, color = MkMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(mkRate(f.rate), color = MkText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (conf.first.isNotEmpty()) Text(L(conf.first), color = conf.second, fontSize = 11.sp)
        }
    }
}

/* ------------------------------------------------------------------ coin detail */

@Composable
private fun MkCoinDetail(base: MkCoin, meta: JSONObject?, inList: Boolean, mine: Boolean, onToggleList: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var live by remember(base.symbol) { mutableStateOf<MkCoin?>(null) }
    val cur = live ?: base
    var range by remember { mutableStateOf("1D") }
    var candles by remember(base.symbol) { mutableStateOf<List<MkCandle>>(emptyList()) }
    var hasVol by remember { mutableStateOf(true) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var loadedKey by remember(base.symbol) { mutableStateOf("") }
    var src by remember { mutableStateOf("") }
    var ts by remember { mutableLongStateOf(0L) }
    var retry by remember { mutableIntStateOf(0) }
    var line by remember { mutableStateOf(false) }
    var ma by remember { mutableStateOf(true) }
    var cursor by remember { mutableStateOf<Int?>(null) }
    val fav = base.symbol in MkFav.items

    // live price every 5 s (the server caches it for 5 s: many members, one provider call)
    LaunchedEffect(base.symbol) {
        while (true) {
            delay(5000)
            val r = Api.call("GET", "/market/ticker", query = mapOf("symbol" to base.symbol))
            if (r.ok) r.data.optJSONObject("coin")?.let { live = mkCoin(it) }
        }
    }
    LaunchedEffect(base.symbol, range, retry) {
        val key = base.symbol + "|" + range
        cursor = null
        if (loadedKey != key) { candles = emptyList(); loadErr = null }
        while (true) {
            val r = Api.call("GET", "/market/candles", query = mapOf("symbol" to base.symbol, "range" to range))
            if (r.ok) {
                val arr = r.data.optJSONArray("candles")
                val list = ArrayList<MkCandle>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val a = arr.optJSONArray(i) ?: continue
                    list.add(MkCandle(a.optLong(0), a.optDouble(1), a.optDouble(2), a.optDouble(3), a.optDouble(4), a.optDouble(5)))
                }
                candles = list; hasVol = r.data.optBoolean("has_volume", true)
                src = r.data.str("source"); ts = r.data.optLong("ts", 0L); loadedKey = key; loadErr = null
            } else if (loadedKey != key) loadErr = r.message
            delay(if (range == "1D") 20000L else 60000L)
        }
    }

    val shown = remember(candles, cur.price) {
        if (candles.isEmpty()) candles else {
            val l = candles.last()
            candles.dropLast(1) + MkCandle(l.t, l.o, max(l.h, cur.price), min(l.l, cur.price), cur.price, l.v)
        }
    }
    val col = mkColor(cur.change)
    val fxo = meta?.optJSONObject("fx")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        MkTopBar(base.symbol + "/USDT", onBack) {
            if (!inList) IconButton(onClick = onToggleList) { Icon(Icons.Filled.AddCircleOutline, L("Add to my list"), tint = MkGold) }
            else if (mine) IconButton(onClick = onToggleList) { Icon(Icons.Filled.RemoveCircleOutline, L("Remove from my list"), tint = MkMuted) }
            IconButton(onClick = { MkFav.toggle(ctx, base.symbol) }) {
                Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, L("Favorite"), tint = if (fav) MkGold else MkMuted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MkCoinIcon(base.symbol, 30.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(base.name, color = MkMuted, fontSize = 13.sp)
                }
                Text(mkPrice(cur.price), color = col, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (fxo != null && fxo.dbl("rate") > 0) {
                    Text("≈ " + fxo.str("code") + " " + mkLocal(cur.price * fxo.dbl("rate")), color = MkMuted, fontSize = 13.sp)
                }
                Text(mkPct(cur.change), color = col, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                MkStat(L("24h high"), mkPrice(cur.high))
                MkStat(L("24h low"), mkPrice(cur.low))
                MkStat(L("24h volume (USDT)"), mkCompact(cur.volume))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("1D", "1W", "1M", "3M", "1Y").forEach { r -> MkChip(r, range == r) { range = r } }
            Spacer(Modifier.weight(1f))
            MkChip(L("Candles"), !line) { line = false }
            MkChip(L("Line"), line) { line = true }
        }
        // O / H / L / C of the candle under the finger (or the latest one)
        val k = shown.getOrNull(cursor ?: (shown.size - 1))
        if (k != null) {
            val kc = if (k.c >= k.o) MkUp else MkDown
            val chg = if (k.o > 0) (k.c - k.o) / k.o * 100 else 0.0
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MkMuted)) { append("O ") }; withStyle(SpanStyle(color = kc)) { append(mkPrice(k.o) + "  ") }
                    withStyle(SpanStyle(color = MkMuted)) { append("H ") }; withStyle(SpanStyle(color = kc)) { append(mkPrice(k.h) + "  ") }
                    withStyle(SpanStyle(color = MkMuted)) { append("L ") }; withStyle(SpanStyle(color = kc)) { append(mkPrice(k.l) + "  ") }
                    withStyle(SpanStyle(color = MkMuted)) { append("C ") }; withStyle(SpanStyle(color = kc)) { append(mkPrice(k.c) + "  ") }
                    withStyle(SpanStyle(color = kc)) { append(mkPct(chg)) }
                },
                fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 2
            )
            if (hasVol && !line) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = MkMuted)) { append("Vol ") }
                    withStyle(SpanStyle(color = MkText)) { append(mkCompact(k.v) + "  ") }
                    if (ma) {
                        withStyle(SpanStyle(color = MkGold)) { append("MA7  ") }
                        withStyle(SpanStyle(color = MkPink)) { append("MA25") }
                    }
                }, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(340.dp).padding(top = 4.dp), contentAlignment = Alignment.Center) {
            when {
                shown.size >= 2 && loadedKey == base.symbol + "|" + range ->
                    MkChart(shown, line, ma && !line, hasVol && !line, range, cursor, { cursor = it }, Modifier.padding(start = 6.dp))
                loadErr != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(loadErr ?: "", color = MkMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(MkGold).clickable { loadErr = null; retry++ }.padding(horizontal = 22.dp, vertical = 10.dp)) {
                        Text(L("Try again"), color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> CircularProgressIndicator(color = MkGold)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!line) MkChip("MA", ma) { ma = !ma }
            Spacer(Modifier.weight(1f))
            Text(L("Press and hold the chart to read prices"), color = MkMuted, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
        }
        MkNote(src, ts, meta?.bool("delayed") ?: false)
        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------------------------------------------ currency detail */

@Composable
private fun MkFxDetail(item: MkFxItem, onBack: () -> Unit) {
    var range by remember { mutableStateOf("1M") }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var points by remember { mutableStateOf<List<MkCandle>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf<Int?>(null) }
    var loadedRange by remember { mutableStateOf("") }

    LaunchedEffect(item.code, range, retry) {
        cursor = null
        if (loadedRange != range) { points = emptyList(); err = null }
        val r = Api.call("GET", "/market/fx/history", query = mapOf("quote" to item.code, "range" to range))
        if (r.ok) {
            val arr = r.data.optJSONArray("points")
            val list = ArrayList<MkCandle>()
            if (arr != null) for (i in 0 until arr.length()) {
                val a = arr.optJSONArray(i) ?: continue
                val v = a.optDouble(1)
                list.add(MkCandle(a.optLong(0), v, v, v, v, 0.0))
            }
            points = list; data = r.data; err = null; loadedRange = range
        } else err = r.message
    }

    val d = data
    val change = if (d != null && !d.isNull("change")) d.dbl("change") else null
    val col = mkColor(change)
    val k = points.getOrNull(cursor ?: (points.size - 1))
    val shownRate = k?.c ?: item.rate

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        MkTopBar("USD/" + item.code, onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MkFlagView(item.cc, 42.dp, item.code)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("1 USD = " + mkRate(shownRate) + " " + item.code, color = MkText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row {
                    Text(mkPct(change), color = col, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("  " + range, color = MkMuted, fontSize = 14.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            listOf("1W", "1M", "3M", "1Y").forEach { r -> MkChip(r, range == r) { range = r } }
        }
        Box(Modifier.fillMaxWidth().height(280.dp).padding(top = 4.dp), contentAlignment = Alignment.Center) {
            when {
                points.size >= 2 && loadedRange == range ->
                    MkChart(points, true, false, false, if (range == "1Y") "1Y" else "3M", cursor, { cursor = it }, Modifier.padding(start = 6.dp), ::mkRate)
                err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(err ?: "", color = MkMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(MkGold).clickable { err = null; retry++ }.padding(horizontal = 22.dp, vertical = 10.dp)) {
                        Text(L("Try again"), color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> CircularProgressIndicator(color = MkGold)
            }
        }
        if (d != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(L("High"), color = MkMuted, fontSize = 11.sp); Text(mkRate(d.dbl("high")), color = MkText, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(L("Low"), color = MkMuted, fontSize = 11.sp); Text(mkRate(d.dbl("low")), color = MkText, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Column(horizontalAlignment = Alignment.End) { Text(L("Rate"), color = MkMuted, fontSize = 11.sp); Text(mkConf(item.conf).first.ifEmpty { L("Daily") }.let { L(it) }, color = mkConf(item.conf).second.let { if (it == MkMuted) MkText else it }, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        MkConverter(item.code, item.rate)
        MkNote(d?.str("source") ?: "", d?.optLong("ts", 0L) ?: 0L, false,
            L("Daily mid-market rate. Free providers update once a day, so the chart shows daily points plus the latest rate."))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MkConverter(code: String, rate: Double) {
    var usd by remember { mutableStateOf(true) }
    var txt by remember { mutableStateOf("100") }
    val amt = txt.replace(",", ".").toDoubleOrNull() ?: 0.0
    val out = if (usd) amt * rate else if (rate > 0) amt / rate else 0.0
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MkPanel).padding(14.dp)) {
        Text(L("Converter"), color = MkMuted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (usd) "USD" else code, color = MkGold, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
            BasicTextField(
                value = txt, onValueChange = { v -> if (v.length <= 14 && v.all { it.isDigit() || it == '.' || it == ',' }) txt = v },
                singleLine = true, textStyle = TextStyle(color = MkText, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                cursorBrush = SolidColor(MkGold), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { usd = !usd }) { Icon(Icons.Filled.SwapVert, L("Swap"), tint = MkText) }
        }
        Spacer(Modifier.height(6.dp))
        Text("= " + mkLocal(out) + " " + (if (usd) code else "USD"), color = MkText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ------------------------------------------------------------------ Home card */

@Composable
fun MarketHomeCard(nav: (String) -> Unit) {
    var coins by remember { mutableStateOf<List<MkCoin>>(emptyList()) }
    LaunchedEffect(Ui.refreshTick) {
        while (true) {
            val r = Api.call("GET", "/market/coins")
            if (r.ok) coins = r.data.list("coins").map { mkCoin(it) }
            delay(30000)
        }
    }
    val top = listOf("BTC", "ETH", "BNB").map { s -> coins.firstOrNull { it.symbol == s } }
    Column(Modifier.fillMaxWidth().card().clip(CardShape).clickable { nav("Market") }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MudaIcons.CandlestickChart, null, tint = MkGold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(L("Markets"), color = MkText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            MkLiveBadge(L("Live"))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("BTC", "ETH", "BNB").forEachIndexed { i, s ->
                val c = top[i]
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Soft).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MkCoinIcon(s, 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(s, color = MkText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(if (c != null) mkPrice(c.price) else "—", color = MkText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(mkPct(c?.change), color = mkColor(c?.change), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (c != null) MkSpark(c.spark, mkColor(c.change), Modifier.fillMaxWidth().height(18.dp).padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(L("Live crypto charts and exchange rates") + "  ›", color = MkMuted, fontSize = 12.sp)
    }
}
