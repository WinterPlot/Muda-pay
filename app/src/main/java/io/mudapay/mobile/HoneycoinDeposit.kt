package io.mudapay.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Dépôt par mobile money via Honeycoin (base = "/honeycoin") ou Payfonte (base = "/payfonte"): même écran, mêmes champs renvoyés par le serveur.
 * Dépôt par mobile money, pour tous les pays pris en charge (le serveur décide : GET /honeycoin/rules).
 * Le membre choisit son réseau, saisit son numéro et le montant ; le serveur envoie la demande de paiement ; selon le réseau
 * le membre valide sur son téléphone, saisit un code (OTP), compose un code USSD ou continue sur une page. Le serveur crédite
 * le solde une seule fois, après confirmation de Honeycoin. L'app ne décide jamais du crédit : elle affiche l'état du serveur.
 * Les clés Honeycoin ne sont PAS dans l'app (elles restent sur le serveur, db/honeycoin_config.php).
 */
@Composable
fun HoneycoinDepositSection(onChanged: () -> Unit, base: String = "/honeycoin") {
    var key by remember { mutableIntStateOf(0) }
    Remote(refreshKey = key, call = { Api.call("GET", "$base/rules") }) { rules, _ ->
        if (!rules.bool("enabled")) return@Remote   // pays non supporté / service coupé : on n'affiche rien
        HoneycoinForm(rules, base, onChanged = { key++; onChanged() })
    }
}

@Composable
private fun HoneycoinForm(rules: JSONObject, base: String, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val symbol = rules.str("symbol")
    val min = rules.dbl("min")
    val max = rules.dbl("max")
    val operators = rules.list("operators")
    val opRequired = rules.bool("operator_required")

    var operator by remember { mutableStateOf(operators.firstOrNull()?.str("id") ?: "") }
    var phone by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var idem by remember { mutableStateOf(newIdemKey()) }
    var watching by remember { mutableStateOf<Int?>(null) }          // id du dépôt suivi
    var live by remember { mutableStateOf<JSONObject?>(null) }       // dernier état renvoyé par le serveur
    var otp by remember { mutableStateOf("") }
    var otpBusy by remember { mutableStateOf(false) }
    var otpError by remember { mutableStateOf<String?>(null) }

    // dépôt en cours : on revérifie toutes les 4 s, jusqu'à ~10 min
    val w = watching
    if (w != null) {
        LaunchedEffect(w) {
            var n = 0
            while (n < 150) {
                val st = Api.call("GET", "$base/deposits/$w")
                if (st.ok) {
                    live = st.data
                    if (st.data.bool("credited")) {
                        Session.balance = st.data.dbl("balance")
                        Ui.notify("Deposit received", "${money(symbol, st.data.dbl("amount"))} ${L("was added to your balance.")}")
                        Ui.refreshTick++
                        watching = null; live = null; onChanged(); break
                    }
                    if (st.data.str("status") == "failed") {
                        Ui.notify("Deposit", L("The mobile money payment did not go through. Nothing was charged to your balance."))
                        watching = null; live = null; onChanged(); break
                    }
                }
                delay(4000); n++
            }
            if (watching == w) { watching = null; live = null; onChanged() }
        }
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconChip(Icons.Default.PhoneAndroid, GreenDark, 40.dp)
            Text(L("Deposit with mobile money"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        val d = live
        if (watching != null) {
            val step = d?.str("step") ?: ""
            when (step) {
                "otp" -> {
                    InfoBanner(L("Enter the code you received to confirm the payment."), Blue, Icons.Default.Sms)
                    InputField(L("Confirmation code"), otp, { otp = it.filter { c -> c.isLetterOrDigit() }.take(d?.int("otp_digits")?.takeIf { n -> n > 0 } ?: 12) }, keyboard = KeyboardType.Number)
                    otpError?.let { InfoBanner(it, Danger, Icons.Default.Info) }
                    PrimaryButton(softCase(L("CONFIRM")), {
                        otpError = null; otpBusy = true
                        scope.launch {
                            val r = Api.call("POST", "$base/deposits/$w/otp", JSONObject().put("otp", otp))
                            otpBusy = false
                            if (r.ok) { otp = ""; live = r.data } else otpError = r.message
                        }
                    }, loading = otpBusy, enabled = otp.length >= 3)
                }
                "ussd" -> {
                    val code = d?.str("ussd_code") ?: ""
                    InfoBanner(d?.str("ussd_description").orEmpty().ifEmpty { L("Dial this code on your phone and follow the prompts to complete the payment.") }, Blue, Icons.Default.Dialpad)
                    if (code.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(code, color = Dark, fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            IconButton(onClick = { copyToClipboard(ctx, "USSD", code); Ui.notify("Copied", "Code copied.") }) {
                                Icon(Icons.Default.ContentCopy, L("Copy"), tint = GreenDark)
                            }
                        }
                    }
                }
                "redirect" -> {
                    InfoBanner(L("Continue on your mobile money page to approve the payment."), Blue, Icons.Default.Public)
                    val url = d?.str("redirect_url") ?: ""
                    if (url.startsWith("https://")) PrimaryButton(softCase(L("CONTINUE")), { openUrl(ctx, url) }, icon = Icons.Default.Public)
                }
                else -> InfoBanner(L("Approve the payment on your phone: enter your PIN when the prompt appears.") +
                        (d?.str("status_text")?.takeIf { it.isNotEmpty() }?.let { "  ($it)" } ?: ""), Blue, Icons.Default.Sync)
            }
        }

        if (operators.size > 1) {
            Text(L("Mobile money network"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            operators.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { o ->
                        val on = operator == o.str("id")
                        Text(
                            o.str("name"), color = if (on) Color.White else Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f).background(if (on) Green else Color.White, RoundedCornerShape(12.dp))
                                .border(1.dp, if (on) Green else Line, RoundedCornerShape(12.dp))
                                .clickable { operator = o.str("id") }.padding(vertical = 11.dp)
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        InputField(L("Mobile money number"), phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(18) }, keyboard = KeyboardType.Phone)
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
                val body = JSONObject().put("phone", phone.trim()).put("amount", a)
                if (operator.isNotEmpty()) body.put("operator", operator)
                val r = Api.call("POST", "$base/deposit", body, idem = idem)
                busy = false
                if (r.ok) {
                    idem = newIdemKey()
                    live = r.data; watching = r.data.int("id")
                    phone = ""; amount = ""
                    onChanged()
                } else {
                    if (r.http != 0) idem = newIdemKey()   // erreur claire : nouvelle clé ; réseau coupé (http 0) : on garde la même pour ne pas doubler la demande
                    error = r.message
                }
            }
        }, loading = busy, enabled = phone.filter { it.isDigit() }.length >= 8 && a >= min && a <= max && (operator.isNotEmpty() || !opRequired), icon = Icons.Default.PhoneAndroid)

        // dépôts déjà lancés mais pas encore confirmés (app fermée en cours de route)
        rules.list("open").filter { it.int("id") != watching }.forEach { o ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${money(symbol, o.dbl("amount"))} • ${o.str("status_text")}", color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${L("Reference")}: ${o.str("reference")}", color = Muted, fontSize = 11.sp)
                }
                Text(softCase(L("CHECK")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.clickable { live = o; watching = o.int("id") }.padding(8.dp))
            }
        }
    }
}

@Composable
fun HoneycoinHistory(refreshKey: Int, symbol: String, base: String = "/honeycoin") {
    Remote(refreshKey = refreshKey, call = { Api.call("GET", "$base/deposits") }) { d, _ ->
        d.list("items").forEach { t ->
            val ok = t.bool("credited")
            val st = t.str("status")
            val tone = when { ok -> GreenDark; st == "failed" -> Danger; else -> Blue }
            Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(money(symbol, t.dbl("amount")), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${t.str("created_at").take(16)} • ${t.str("operator_name").ifEmpty { "Mobile money" }}", color = Muted, fontSize = 12.sp)
                    Text("${L("Reference")}: ${t.str("reference")}", color = Muted, fontSize = 11.sp)
                }
                Pill(t.str("status_text"), tone)
            }
        }
    }
}
