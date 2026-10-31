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
 * Dépôt par Airtel Money (RDC, CDF). Le membre saisit son numéro Airtel + le montant ; le serveur envoie une demande
 * de paiement USSD sur son téléphone ; il valide avec son code PIN Airtel ; le serveur crédite le solde MudaPay une seule
 * fois, après confirmation d'Airtel. L'app ne décide jamais du crédit : elle affiche l'état renvoyé par le serveur.
 * Les clés Airtel ne sont PAS dans l'app (elles restent sur le serveur, db/airtel_config.php).
 */
private val AirtelRed = Color(0xFFE40000)

@Composable
fun AirtelDepositSection(onChanged: () -> Unit) {
    var key by remember { mutableIntStateOf(0) }
    Remote(refreshKey = key, call = { Api.call("GET", "/airtel/rules") }) { rules, _ ->
        if (!rules.bool("enabled")) return@Remote   // pays non supporté / service coupé : on n'affiche rien
        AirtelForm(rules, onChanged = { key++; onChanged() })
    }
}

@Composable
private fun AirtelForm(rules: JSONObject, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol", "FC")
    val min = rules.dbl("min")
    val max = rules.dbl("max")

    var phone by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var idem by remember { mutableStateOf(newIdemKey()) }
    var watching by remember { mutableStateOf<Int?>(null) }           // id du dépôt suivi
    var watchText by remember { mutableStateOf("") }

    // dépôt en cours (approbation sur le téléphone pas encore reçue) : on revérifie toutes les 4 s, jusqu'à ~8 min
    val w = watching
    if (w != null) {
        LaunchedEffect(w) {
            var n = 0
            while (n < 120) {
                val st = Api.call("GET", "/airtel/deposits/$w")
                if (st.ok) {
                    watchText = st.data.str("status_text")
                    if (st.data.bool("credited")) {
                        Session.balance = st.data.dbl("balance")
                        Ui.notify("Deposit received", "${money(symbol, st.data.dbl("amount"))} ${L("was added to your balance.")}")
                        Ui.refreshTick++
                        watching = null; onChanged(); break
                    }
                    if (st.data.str("status") == "failed") {
                        Ui.notify("Deposit", L("The Airtel Money payment did not go through. Nothing was charged to your balance."))
                        watching = null; onChanged(); break
                    }
                    if (st.data.str("status") == "review") { watchText = L("Under review") }
                }
                delay(4000); n++
            }
            if (watching == w) { watching = null; onChanged() }
        }
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AIRTEL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.airtelBg(AirtelRed).padding(horizontal = 10.dp, vertical = 5.dp))
            Text(L("Deposit with Airtel Money"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        if (watching != null) {
            InfoBanner(L("Approve the payment on your phone: enter your Airtel Money PIN when the prompt appears.") +
                if (watchText.isNotEmpty()) "  ($watchText)" else "", Blue, Icons.Default.Sync)
        }

        InputField(L("Airtel Money number"), phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, keyboard = KeyboardType.Phone)
        InputField("${L("Amount")} ($symbol)", amount, { amount = it.filter { c -> c.isDigit() }.take(9) }, keyboard = KeyboardType.Number)
        val presets = rules.doubles("presets")
        if (presets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.take(4).forEach { p ->
                    Text(
                        money("", p).trim(), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp,
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
        PrimaryButton(softCase(L("SEND PAYMENT REQUEST")), {
            error = null; busy = true
            scope.launch {
                val r = Api.call("POST", "/airtel/deposit", JSONObject().put("phone", phone.trim()).put("amount", a), idem = idem)
                busy = false
                if (r.ok) {
                    idem = newIdemKey()
                    watching = r.data.int("id"); watchText = r.data.str("status_text")
                    phone = ""; amount = ""
                    onChanged()
                } else {
                    if (r.http != 0) idem = newIdemKey()   // erreur claire : nouvelle clé ; réseau coupé (http 0) : on garde la même pour ne pas doubler la demande
                    error = r.message
                }
            }
        }, loading = busy, enabled = phone.filter { it.isDigit() }.length >= 9 && a >= min && a <= max, color = AirtelRed, contentColor = Color.White, icon = Icons.Default.PhoneAndroid)

        // dépôts déjà lancés mais pas encore confirmés (app fermée en cours de route)
        rules.list("open").filter { it.int("id") != watching }.forEach { o ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${money(symbol, o.dbl("amount"))} • ${o.str("status_text")}", color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${L("Reference")}: ${o.str("reference")}", color = Muted, fontSize = 11.sp)
                }
                Text(softCase(L("CHECK")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.clickable { watching = o.int("id") }.padding(8.dp))
            }
        }
    }
}

private fun Modifier.airtelBg(c: Color): Modifier = this.then(Modifier.background(c, RoundedCornerShape(8.dp)))

@Composable
fun AirtelHistory(refreshKey: Int, symbol: String) {
    Remote(refreshKey = refreshKey, call = { Api.call("GET", "/airtel/deposits") }) { d, _ ->
        d.list("items").forEach { t ->
            val ok = t.bool("credited")
            val st = t.str("status")
            val tone = when { ok -> GreenDark; st == "failed" -> Danger; else -> Blue }
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(money(symbol, t.dbl("amount")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${t.str("created_at").take(16)} • Airtel Money", color = Muted, fontSize = 12.sp)
                    Text("${L("Reference")}: ${t.str("reference")}", color = Muted, fontSize = 11.sp)
                }
                Pill(t.str("status_text").uppercase(), tone)
            }
        }
    }
}
