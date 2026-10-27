package io.mudapay.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Dépôt par Lumicash (Burundi, BIF). Le membre saisit son numéro + le montant, reçoit un code OTP par SMS, le saisit,
 * puis le serveur (API BitLibera) débite son compte Lumicash et crédite le solde MudaPay. L'app ne décide jamais du
 * crédit : elle affiche l'état renvoyé par le serveur. La clé BitLibera n'est PAS dans l'app (elle reste sur le serveur).
 */
private val LumiYellow = Color(0xFFF5C400)

@Composable
fun LumicashDepositSection(onChanged: () -> Unit) {
    var key by remember { mutableIntStateOf(0) }
    Remote(refreshKey = key, call = { Api.call("GET", "/lumicash/rules") }) { rules, _ ->
        if (!rules.bool("enabled")) return@Remote   // pays non supporté / service coupé : on n'affiche rien
        LumicashForm(rules, onChanged = { key++; onChanged() })
    }
}

@Composable
private fun LumicashForm(rules: JSONObject, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol", "FBu")
    val min = rules.dbl("min")
    val max = rules.dbl("max")

    var phone by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(1) }                    // 1 = numéro + montant, 2 = code OTP
    var deposit by remember { mutableStateOf<JSONObject?>(null) }    // ligne créée par /lumicash/otp
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var idem by remember { mutableStateOf(newIdemKey()) }
    var watching by remember { mutableStateOf<Int?>(null) }           // id du dépôt suivi
    var watchText by remember { mutableStateOf("") }

    // dépôt en cours (confirmation Lumicash pas encore reçue) : on revérifie toutes les 5 s, jusqu'à ~10 min
    val w = watching
    if (w != null) {
        LaunchedEffect(w) {
            var n = 0
            while (n < 120) {
                val st = Api.call("GET", "/lumicash/deposits/$w")
                if (st.ok) {
                    watchText = st.data.str("status_text")
                    if (st.data.bool("credited")) {
                        Session.balance = st.data.dbl("balance")
                        Ui.notify("Deposit received", "${money(symbol, st.data.dbl("amount"))} ${L("was added to your balance.")}")
                        Ui.refreshTick++
                        watching = null; onChanged(); break
                    }
                    if (st.data.str("status") == "failed") {
                        Ui.notify("Deposit", L("The Lumicash payment did not go through. Nothing was charged to your balance."))
                        watching = null; onChanged(); break
                    }
                    if (st.data.str("status") == "review") { watchText = L("Under review"); }
                }
                delay(5000); n++
            }
            if (watching == w) { watching = null; onChanged() }
        }
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("LUMICASH", color = Dark, fontWeight = FontWeight.Black, fontSize = 12.sp,
                modifier = Modifier.androidxBg(LumiYellow).padding(horizontal = 10.dp, vertical = 5.dp))
            Text(L("Deposit with Lumicash"), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }

        if (watching != null) {
            InfoBanner(L("Confirming your Lumicash payment…") + if (watchText.isNotEmpty()) "  ($watchText)" else "", Blue, Icons.Default.Sync)
        }

        if (step == 1) {
            InputField(L("Lumicash number"), phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, keyboard = KeyboardType.Phone)
            InputField("${L("Amount")} ($symbol)", amount, { amount = it.filter { c -> c.isDigit() }.take(9) }, keyboard = KeyboardType.Number)
            val presets = rules.doubles("presets")
            if (presets.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.take(4).forEach { p ->
                        Text(
                            money("", p).trim(), color = GreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                            modifier = Modifier.weight(1f).border(1.dp, GreenDark.copy(alpha = .4f), RoundedCornerShape(12.dp))
                                .clickable { amount = p.toLong().toString() }.padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            Text(L("Minimum: ${money(symbol, min)} • Maximum: ${money(symbol, max)} per payment"), color = Muted, fontSize = 12.sp)
            error?.let { InfoBanner(it, Danger, Icons.Default.Info) }
            val a = amount.toLongOrNull() ?: 0L
            PrimaryButton(L("SEND CODE"), {
                error = null; busy = true
                scope.launch {
                    val r = Api.call("POST", "/lumicash/otp", JSONObject().put("phone", phone.trim()).put("amount", a))
                    busy = false
                    if (r.ok) { deposit = r.data; otp = ""; idem = newIdemKey(); step = 2 } else error = r.message
                }
            }, loading = busy, enabled = phone.filter { it.isDigit() }.length >= 8 && a >= min && a <= max, color = LumiYellow, contentColor = Dark, icon = Icons.Default.Sms)
        } else {
            val d = deposit
            Text("${L("Enter the code sent to")} +${d?.str("phone")} ${L("for")} ${money(symbol, d?.dbl("amount") ?: 0.0)}", color = Ink, fontSize = 14.sp)
            InputField(L("Lumicash code (OTP)"), otp, { otp = it.filter { c -> c.isDigit() }.take(8) }, keyboard = KeyboardType.Number)
            error?.let { InfoBanner(it, Danger, Icons.Default.Info) }
            PrimaryButton(L("CONFIRM PAYMENT"), {
                val id = d?.int("id") ?: return@PrimaryButton
                error = null; busy = true
                scope.launch {
                    val r = Api.call("POST", "/lumicash/deposit", JSONObject().put("id", id).put("otp", otp), idem = idem)
                    busy = false
                    if (r.ok) {
                        idem = newIdemKey()
                        if (r.data.bool("credited")) {
                            Session.balance = r.data.dbl("balance")
                            Ui.notify("Deposit received", "${money(symbol, r.data.dbl("amount"))} ${L("was added to your balance.")}")
                            Ui.refreshTick++
                        } else if (r.data.str("status") != "failed") {
                            watching = id; watchText = r.data.str("status_text")   // réponse initiale ≠ règlement : on suit l'état
                        } else error = L("The Lumicash payment did not go through. Nothing was charged to your balance.")
                        if (error == null) { step = 1; phone = ""; amount = ""; otp = ""; deposit = null }
                        onChanged()
                    } else {
                        if (r.http != 0) idem = newIdemKey()
                        error = r.message
                        if (r.code == "otp_expired") { step = 1; deposit = null }
                    }
                }
            }, loading = busy, enabled = otp.length >= 4, color = LumiYellow, contentColor = Dark, icon = Icons.Default.CheckCircle)
            Text(L("Back"), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { step = 1; error = null }.padding(vertical = 4.dp))
        }

        // dépôts déjà lancés mais pas encore confirmés (app fermée en cours de route)
        rules.list("open").filter { it.int("id") != watching }.forEach { o ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${money(symbol, o.dbl("amount"))} • ${o.str("status_text")}", color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    Text("${L("Reference")}: ${o.str("reference")}", color = Muted, fontSize = 11.sp)
                }
                Text(L("CHECK"), color = GreenDark, fontWeight = FontWeight.Black, fontSize = 12.sp,
                    modifier = Modifier.clickable { watching = o.int("id") }.padding(8.dp))
            }
        }
    }
}

private fun Modifier.androidxBg(c: Color): Modifier = this.then(Modifier.background(c, RoundedCornerShape(8.dp)))

@Composable
fun LumicashHistory(refreshKey: Int, symbol: String) {
    Remote(refreshKey = refreshKey, call = { Api.call("GET", "/lumicash/deposits") }) { d, _ ->
        d.list("items").forEach { t ->
            val ok = t.bool("credited")
            val st = t.str("status")
            val tone = when { ok -> GreenDark; st == "failed" -> Danger; else -> Blue }
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(money(symbol, t.dbl("amount")), color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("${t.str("created_at").take(16)} • Lumicash", color = Muted, fontSize = 12.sp)
                    Text("${L("Reference")}: ${t.str("reference")}", color = Muted, fontSize = 11.sp)
                }
                Pill(t.str("status_text").uppercase(), tone)
            }
        }
    }
}
