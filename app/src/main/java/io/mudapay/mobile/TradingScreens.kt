package io.mudapay.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Invest (Alpaca Broker API, live). The app only talks to the MudaPay server (/trading/...): the Alpaca keys never leave the server.
 * The server decides everything (limits, account status, order validity). The app shows what the server answers.
 *
 *   Invest -> [Markets | Portfolio | Orders]  ->  asset detail (price, chart, buy / sell with the transaction PIN)
 */
private fun usd(v: Double): String = money("$", v)
private fun num(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e9) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%,.6f", v).trimEnd('0').trimEnd('.')
private fun pct(v: Double): String = String.format(java.util.Locale.US, "%+.2f%%", v)

@Composable
fun InvestScreen(nav: (String) -> Unit) {
    var key by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = detail != null) { detail = null }
    val d = detail
    if (d != null) {
        AssetDetail(d, onBack = { detail = null }, onChanged = { key++ })
    } else {
        Remote(refreshKey = key, call = { Api.call("GET", "/trading/status") }) { st, reload ->
            InvestHome(st, onOpenAsset = { detail = it }, reload = { key++; reload() })
        }
    }
}

@Composable
private fun InvestHome(st: JSONObject, onOpenAsset: (String) -> Unit, reload: () -> Unit) {
    val account = st.optJSONObject("account")
    val req = st.optJSONObject("requirements") ?: JSONObject()
    val limits = st.optJSONObject("limits") ?: JSONObject()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!st.bool("enabled")) {
            InfoBanner(L("Investing is not available right now."), Danger, MudaIcons.Info)
        } else if (account == null) {
            OpenAccountForm(req, st.optJSONObject("prefill") ?: JSONObject(), onDone = reload)
        } else if (!account.bool("active")) {
            Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("Your investing account"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Pill(account.str("status").replace('_', ' '), Blue)
                Text(L("Your account is being reviewed. This can take a little while. Pull down to refresh."), color = Muted, fontSize = 13.sp)
                PrimaryButton(L("Refresh status"), reload, icon = MudaIcons.Refresh)
            }
        } else {
            InvestTabs(account, limits, onOpenAsset)
        }
        Text(
            L("Investing involves risk, including the loss of the money you invest. Securities brokerage services are provided by Alpaca Securities LLC, member FINRA/SIPC. Crypto services are provided by Alpaca Crypto LLC, which is not a member of FINRA or SIPC; crypto is not FDIC or SIPC insured. MudaPay does not give investment advice."),
            color = Muted, fontSize = 11.sp, lineHeight = 15.sp
        )
    }
}

/* ------------------------------------------------------------------ account opening */
@Composable
private fun OpenAccountForm(req: JSONObject, prefill: JSONObject, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val myCountry = (Session.user?.str("country") ?: "").lowercase()
    val nameFromId = prefill.bool("name_from_id")
    val dobFromId = prefill.bool("dob_from_id")
    var given by remember { mutableStateOf(prefill.str("given_name")) }
    var family by remember { mutableStateOf(prefill.str("family_name")) }
    var dob by remember { mutableStateOf(prefill.str("date_of_birth")) }
    var street by remember { mutableStateOf(prefill.str("address")) }
    var city by remember { mutableStateOf("") }
    var postal by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(myCountry) }
    var citizen by remember { mutableStateOf(myCountry) }
    var birth by remember { mutableStateOf(myCountry) }
    var taxRes by remember { mutableStateOf(myCountry) }
    var taxId by remember { mutableStateOf("") }
    var crypto by remember { mutableStateOf(true) }
    var control by remember { mutableStateOf(false) }
    var affiliated by remember { mutableStateOf(false) }
    var pep by remember { mutableStateOf(false) }
    var famExp by remember { mutableStateOf(false) }
    var agree by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var idem by remember { mutableStateOf(newIdemKey()) }

    val idOk = req.bool("id_verified")
    val mailOk = req.bool("email_verified")

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Open your investing account"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(L("Buy and sell US stocks, ETFs and crypto. Your account is opened with our broker partner Alpaca, using your verified ID."), color = Muted, fontSize = 13.sp)
        if (!idOk) InfoBanner(L("Verify your ID first (Profile > ID verification)."), Danger, MudaIcons.Info)
        if (!mailOk) InfoBanner(L("Verify your e-mail address first (Profile)."), Danger, MudaIcons.Info)

        Text(L("Your identity"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (nameFromId) {
            Text(L("Name on your ID") + ": " + given + " " + family, color = Ink, fontSize = 13.sp)
        } else {
            InputField(L("First name(s), as on your ID"), given, { given = it.take(80) })
            InputField(L("Last name, as on your ID"), family, { family = it.take(80) })
        }
        if (dobFromId) {
            Text(L("Date of birth") + ": " + dob, color = Ink, fontSize = 13.sp)
        } else {
            InputField(L("Date of birth (YYYY-MM-DD)"), dob, { dob = it.filter { c -> c.isDigit() || c == '-' }.take(10) }, keyboard = KeyboardType.Number)
        }
        Text(L("Your address"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        InputField(L("Street address"), street, { street = it.take(120) })
        InputField(L("City"), city, { city = it.take(60) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InputField(L("Postal code"), postal, { postal = it.take(20) }, modifier = Modifier.weight(1f))
            InputField(L("State / province (optional)"), state, { state = it.take(40) }, modifier = Modifier.weight(1f))
        }
        CountryField(L("Country of residence"), country) { country = it }
        CountryField(L("Citizenship"), citizen) { citizen = it }
        CountryField(L("Country of birth"), birth) { birth = it }
        CountryField(L("Country of tax residence"), taxRes) { taxRes = it }
        InputField(L("Tax ID (optional)"), taxId, { taxId = it.take(40) })

        ToggleRow(L("Enable crypto trading"), crypto) { crypto = it }
        Text(L("Please answer honestly"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        ToggleRow(L("I am a director, officer or 10% shareholder of a listed company"), control) { control = it }
        ToggleRow(L("I work for a stock exchange or a FINRA member firm"), affiliated) { affiliated = it }
        ToggleRow(L("I hold, or held, a senior public or political position"), pep) { pep = it }
        ToggleRow(L("A close family member holds, or held, such a position"), famExp) { famExp = it }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agree, onCheckedChange = { agree = it })
            Text(L("I accept the Alpaca customer agreement, account agreement and crypto agreement, and confirm my answers are true."), color = Ink, fontSize = 12.sp, lineHeight = 16.sp)
        }
        error?.let { InfoBanner(it, Danger, MudaIcons.Info) }
        PrimaryButton(L("Open my account"), {
            error = null; busy = true
            scope.launch {
                val body = JSONObject()
                    .put("agreements_accepted", true)
                    .put("street_address", street.trim()).put("city", city.trim()).put("postal_code", postal.trim())
                    .put("country", country).put("country_of_citizenship", citizen).put("country_of_birth", birth).put("country_of_tax_residence", taxRes)
                    .put("crypto", crypto)
                    .put("given_name", given.trim()).put("family_name", family.trim()).put("date_of_birth", dob.trim())
                    .put("disclosures", JSONObject().put("is_control_person", control).put("is_affiliated_exchange_or_finra", affiliated)
                        .put("is_politically_exposed", pep).put("immediate_family_exposed", famExp))
                if (state.isNotBlank()) body.put("state", state.trim())
                if (taxId.isNotBlank()) body.put("tax_id", taxId.trim())
                val r = Api.call("POST", "/trading/account", body, idem = idem, timeoutMs = 60000)
                busy = false
                if (r.http != 0) idem = newIdemKey()
                if (r.ok) onDone() else error = r.message
            }
        }, loading = busy,
            enabled = idOk && mailOk && agree && given.isNotBlank() && family.isNotBlank() && dob.length == 10 && street.isNotBlank() && city.isNotBlank() && postal.isNotBlank() &&
                country.length >= 2 && citizen.length >= 2 && birth.length >= 2 && taxRes.length >= 2)
    }
}

/* ------------------------------------------------------------------ country picker (ISO codes the server understands) */
private val INVEST_EXTRA = listOf(
    "ae" to "United Arab Emirates", "ar" to "Argentina", "au" to "Australia", "be" to "Belgium", "bw" to "Botswana", "br" to "Brazil", "ca" to "Canada",
    "cf" to "Central African Republic", "ch" to "Switzerland", "cn" to "China", "de" to "Germany", "dj" to "Djibouti", "dz" to "Algeria", "eg" to "Egypt",
    "er" to "Eritrea", "es" to "Spain", "et" to "Ethiopia", "fr" to "France", "gb" to "United Kingdom", "in" to "India", "it" to "Italy", "ly" to "Libya",
    "ma" to "Morocco", "mr" to "Mauritania", "mu" to "Mauritius", "mw" to "Malawi", "nl" to "Netherlands", "pt" to "Portugal", "sd" to "Sudan",
    "so" to "Somalia", "tn" to "Tunisia", "us" to "United States"
)
private val INVEST_COUNTRIES: List<Pair<String, String>> =
    (COUNTRIES + INVEST_EXTRA.filter { e -> COUNTRIES.none { it.first == e.first } }).sortedBy { it.second }

@Composable
private fun CountryField(label: String, code: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = INVEST_COUNTRIES.firstOrNull { it.first == code.lowercase() }?.second ?: ""
    Column(Modifier.fillMaxWidth().border(1.dp, Muted.copy(alpha = .35f), RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).clickable { open = true }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(if (name.isEmpty()) L("Choose a country") else name, color = if (name.isEmpty()) Muted else Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = Panel,
            title = { Text(label, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    INVEST_COUNTRIES.forEach { (c, n) ->
                        Text(n, color = if (c == code.lowercase()) GreenDark else Ink, fontWeight = if (c == code.lowercase()) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(c); open = false }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/* ------------------------------------------------------------------ tabs */
@Composable
private fun InvestTabs(account: JSONObject, limits: JSONObject, onOpenAsset: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val names = listOf(L("Markets"), L("Portfolio"), L("Orders"))
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
        0 -> MarketsTab(onOpenAsset)
        1 -> PortfolioTab(account, onOpenAsset)
        else -> OrdersTab()
    }
    val maxOrder = limits.dbl("max_order_usd")
    val daily = limits.dbl("daily_usd")
    if (maxOrder > 0) Text(L("Limits") + ": " + usd(maxOrder) + " / " + L("order") + " • " + usd(daily) + " / " + L("day"), color = Muted, fontSize = 11.sp)
}

/* ------------------------------------------------------------------ markets */
@Composable
private fun MarketsTab(onOpenAsset: (String) -> Unit) {
    var cls by remember { mutableStateOf("us_equity") }
    var q by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("us_equity" to L("Stocks & ETFs"), "crypto" to L("Crypto")).forEach { (c, label) ->
            val on = cls == c
            Text(label, color = if (on) GreenDark else Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (on) GreenDark else Muted.copy(alpha = .3f), RoundedCornerShape(50))
                    .clickable { cls = c; q = ""; applied = "" }.padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
    InputField(L("Search (name or symbol)"), q, { q = it.take(40) }, trailing = {
        Text(L("Go"), color = GreenDark, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { applied = q.trim() }.padding(10.dp))
    })
    Remote(refreshKey = "$cls|$applied", call = { Api.call("GET", "/trading/assets", query = mapOf("q" to applied, "class" to cls)) }) { d, _ ->
        val items = d.list("assets")
        if (items.isEmpty()) Text(L("Nothing found."), color = Muted, fontSize = 13.sp)
        items.forEach { a ->
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onOpenAsset(a.str("symbol")) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.str("symbol"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(a.str("name"), color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                Text(">", color = Muted, fontSize = 18.sp)
            }
        }
    }
}

/* ------------------------------------------------------------------ portfolio */
@Composable
private fun PortfolioTab(account: JSONObject, onOpenAsset: (String) -> Unit) {
    Remote(call = { Api.call("GET", "/trading/account") }) { a, _ ->
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("Portfolio value"), color = Muted, fontSize = 12.sp)
            Text(usd(a.dbl("equity")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(L("Cash"), color = Muted, fontSize = 12.sp); Text(usd(a.dbl("cash")), color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(L("Buying power"), color = Muted, fontSize = 12.sp); Text(usd(a.dbl("buying_power")), color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            if (a.dbl("cash") <= 0.0) InfoBanner(L("Your investing account has no cash yet. Add funds to start trading."), Blue, MudaIcons.Info)
        }
    }
    Remote(call = { Api.call("GET", "/trading/positions") }) { p, _ ->
        val items = p.list("positions")
        SectionHeader(L("Positions"))
        if (items.isEmpty()) Text(L("You do not own any asset yet."), color = Muted, fontSize = 13.sp)
        items.forEach { x ->
            val pl = x.dbl("unrealized_pl")
            val tone = if (pl >= 0) GreenDark else Danger
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onOpenAsset(x.str("symbol")) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(x.str("symbol"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(num(x.dbl("qty")) + " • " + usd(x.dbl("current_price")), color = Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(usd(x.dbl("market_value")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(usd(pl) + " (" + pct(x.dbl("unrealized_plpc") * 100) + ")", color = tone, fontSize = 12.sp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ orders */
@Composable
private fun OrdersTab() {
    val scope = rememberCoroutineScope()
    var key by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    error?.let { InfoBanner(it, Danger, MudaIcons.Info) }
    Remote(refreshKey = key, call = { Api.call("GET", "/trading/orders") }) { d, _ ->
        val items = d.list("orders")
        if (items.isEmpty()) Text(L("No orders yet."), color = Muted, fontSize = 13.sp)
        items.forEach { o ->
            val st = o.str("status")
            val tone = when (st) { "filled" -> GreenDark; "rejected", "canceled", "expired" -> Danger; else -> Blue }
            Column(Modifier.fillMaxWidth().card(RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.str("side").uppercase() + " " + o.str("symbol"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Pill(st.replace('_', ' '), tone)
                }
                val amount = if (!o.isNull("notional")) usd(o.dbl("notional")) else num(o.dbl("qty")) + " " + L("units")
                Text(amount + " • " + o.str("type") + " • " + o.str("created_at").take(16), color = Muted, fontSize = 12.sp)
                if (!o.isNull("filled_avg_price")) Text(L("Filled") + ": " + num(o.dbl("filled_qty")) + " @ " + usd(o.dbl("filled_avg_price")), color = Ink, fontSize = 12.sp)
                if (o.str("note").isNotEmpty()) Text(o.str("note"), color = Muted, fontSize = 11.sp)
                if (o.bool("cancelable")) {
                    Text(softCase(L("CANCEL ORDER")), color = Danger, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            scope.launch {
                                val r = Api.call("POST", "/trading/orders/${o.int("id")}/cancel")
                                error = if (r.ok) null else r.message
                                key++
                            }
                        }.padding(vertical = 6.dp))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ asset detail + order ticket */
@Composable
private fun AssetDetail(symbol: String, onBack: () -> Unit, onChanged: () -> Unit) {
    var range by remember { mutableStateOf("1M") }
    var qKey by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("<  " + L("Back"), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp))
        }
        Remote(refreshKey = "q$symbol$qKey", call = { Api.call("GET", "/trading/quote", query = mapOf("symbol" to symbol)) }) { q, _ ->
            val ch = if (q.isNull("change_pct")) null else q.dbl("change_pct")
            Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(symbol, color = Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(usd(q.dbl("price")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                if (ch != null) Text(pct(ch) + " " + L("today"), color = if (ch >= 0) GreenDark else Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (q.dbl("bid") > 0 && q.dbl("ask") > 0) Text(L("Bid") + " " + usd(q.dbl("bid")) + " • " + L("Ask") + " " + usd(q.dbl("ask")), color = Muted, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1D", "1W", "1M", "3M", "1Y").forEach { r ->
                val on = range == r
                Text(r, color = if (on) Color.White else GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .then(if (on) Modifier.card(RoundedCornerShape(10.dp), Green) else Modifier.border(1.dp, GreenDark.copy(alpha = .35f), RoundedCornerShape(10.dp)))
                        .clickable { range = r }.padding(vertical = 8.dp))
            }
        }
        Remote(refreshKey = "b$symbol$range", call = { Api.call("GET", "/trading/bars", query = mapOf("symbol" to symbol, "range" to range)) }) { b, _ ->
            val closes = b.list("bars").map { it.dbl("c") }
            if (closes.size < 2) Text(L("No chart data for this period."), color = Muted, fontSize = 13.sp)
            else Column(Modifier.fillMaxWidth().card().padding(12.dp)) { LineChart(closes) }
        }
        OrderTicket(symbol, onPlaced = { qKey++; onChanged() })
    }
}

@Composable
private fun LineChart(values: List<Double>) {
    val up = values.last() >= values.first()
    val col = if (up) GreenDark else Danger
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val mn = values.minOrNull() ?: 0.0
        val mx = values.maxOrNull() ?: 1.0
        val span = if (mx - mn < 1e-9) 1.0 else mx - mn
        val w = size.width
        val h = size.height
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = w * i / (values.size - 1)
            val frac = ((v - mn) / span).toFloat()
            val y = h - (frac * (h - 8f) + 4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, col, style = Stroke(width = 3f))
    }
}

@Composable
private fun OrderTicket(symbol: String, onPlaced: () -> Unit) {
    val scope = rememberCoroutineScope()
    var side by remember { mutableStateOf("buy") }
    var type by remember { mutableStateOf("market") }
    var byQty by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    var askPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var idem by remember { mutableStateOf(newIdemKey()) }

    val useQty = type == "limit" || byQty
    val ready = (if (useQty) (qty.toDoubleOrNull() ?: 0.0) > 0.0 else (amount.toDoubleOrNull() ?: 0.0) >= 1.0) &&
        (type != "limit" || (limit.toDoubleOrNull() ?: 0.0) > 0.0)

    if (askPin) {
        PinPromptDialog("Confirm your order", onDismiss = { askPin = false }, onSubmit = { pin ->
            askPin = false; error = null; result = null; busy = true
            scope.launch {
                val body = JSONObject().put("symbol", symbol).put("side", side).put("type", type).put("pin", pin)
                if (useQty) body.put("qty", qty.trim()) else body.put("notional", amount.trim())
                if (type == "limit") body.put("limit_price", limit.trim())
                val r = Api.call("POST", "/trading/orders", body, idem = idem, timeoutMs = 45000)
                busy = false
                if (r.http != 0) idem = newIdemKey()
                if (r.ok) { result = r.data; amount = ""; qty = ""; onPlaced() } else error = r.message
            }
        })
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Trade") + " " + symbol, color = Dark, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("buy" to L("Buy"), "sell" to L("Sell")).forEach { (s, label) ->
                val on = side == s
                val c = if (s == "buy") Green else Danger
                Text(label, color = if (on) Color.White else c, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .then(if (on) Modifier.card(RoundedCornerShape(12.dp), c) else Modifier.border(1.dp, c.copy(alpha = .5f), RoundedCornerShape(12.dp)))
                        .clickable { side = s }.padding(vertical = 11.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("market" to L("Market"), "limit" to L("Limit")).forEach { (t, label) ->
                val on = type == t
                Text(label, color = if (on) GreenDark else Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (on) GreenDark else Muted.copy(alpha = .3f), RoundedCornerShape(50))
                        .clickable { type = t }.padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
        if (type == "market") {
            ToggleRow(L("Enter a quantity instead of an amount"), byQty) { byQty = it }
        }
        if (useQty) InputField(L("Quantity"), qty, { qty = it.filter { c -> c.isDigit() || c == '.' }.take(14) }, keyboard = KeyboardType.Decimal)
        else InputField(L("Amount") + " (USD)", amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, keyboard = KeyboardType.Decimal)
        if (type == "limit") InputField(L("Limit price") + " (USD)", limit, { limit = it.filter { c -> c.isDigit() || c == '.' }.take(14) }, keyboard = KeyboardType.Decimal)
        error?.let { InfoBanner(it, Danger, MudaIcons.Info) }
        result?.let { r ->
            InfoBanner(L("Order sent") + ": " + r.str("side").uppercase() + " " + r.str("symbol") + " (" + r.str("status").replace('_', ' ') + ")" +
                (if (r.str("note").isNotEmpty()) "\n" + r.str("note") else ""), GreenDark, MudaIcons.CheckCircle)
        }
        PrimaryButton(if (side == "buy") L("Review and buy") else L("Review and sell"), { askPin = true }, loading = busy, enabled = ready,
            color = if (side == "buy") Green else Danger)
        Text(L("Prices move. Your order is sent to the market after you enter your transaction PIN."), color = Muted, fontSize = 11.sp)
    }
}
