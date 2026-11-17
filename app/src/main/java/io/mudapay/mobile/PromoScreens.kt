package io.mudapay.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Promo codes and vouchers (server: promo_lib.php, api/v1/lib_promo.php).
 * A member types a code from a partner / influencer (bonus, often paid with the first deposit) or a voucher (instant money).
 */
@Composable
fun PromoScreen() {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
            Text(softCase(L("PROMO CODES & VOUCHERS")), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(L("Got a code? Get money"), color = androidx.compose.ui.graphics.Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(L("Type a promo code from a MudaPay partner, or a voucher you received. Vouchers are added to your balance right away; some promo codes pay with your first deposit."),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = .9f), fontSize = 13.sp, lineHeight = 18.sp)
        }
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InputField("Promo code or voucher", code, { code = it.uppercase().take(24) })
            PrimaryButton("REDEEM", {
                busy = true; result = null
                scope.launch {
                    val r = Api.call("POST", "/promo/redeem", JSONObject().put("code", code.trim()))
                    busy = false
                    if (r.ok) {
                        Session.balance = r.data.dbl("balance")
                        val amt = money(r.data.str("symbol"), r.data.dbl("amount"))
                        val msg = if (r.data.bool("held")) {
                            amt + " " + L("is reserved for you. It is added to your balance with your first deposit") +
                                (if (r.data.int("min_deposit") > 0) " " + L("of at least") + " " + money(r.data.str("symbol"), r.data.int("min_deposit").toDouble()) else "") +
                                " (" + r.data.int("days") + " " + L("days") + ")."
                        } else amt + " " + L("was added to your balance.")
                        result = msg; code = ""; key++
                        Ui.notify("Promo code", msg)
                    } else { result = r.message; Ui.notify("Promo code", r.message) }
                }
            }, loading = busy, enabled = code.trim().length >= 4, icon = MudaIcons.Redeem)
            result?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        Remote(refreshKey = key, call = { Api.call("GET", "/promo/history") }) { d, _ ->
            val items = d.list("items")
            if (items.isNotEmpty()) {
                Text(L("Your codes"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                items.forEach { it ->
                    Row(Modifier.fillMaxWidth().card().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(it.str("code"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            val sub = it.str("label").ifEmpty { L(if (it.str("kind") == "voucher") "Voucher" else "Promo code") }
                            Text(sub + " • " + it.str("created_at").take(10), color = Muted, fontSize = 12.sp)
                            if (it.str("status") == "held" && it.str("deadline").isNotEmpty()) Text(L("Deposit before") + " " + it.str("deadline").take(10), color = Muted, fontSize = 11.sp)
                        }
                        Text(money(it.str("symbol"), it.dbl("amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        when (it.str("status")) {
                            "released" -> Pill("PAID", GreenDark)
                            "held" -> Pill("WAITING", Blue)
                            else -> Pill("EXPIRED", Muted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------------------------------------------ French */
internal val frenchPromo: Map<String, String> = mapOf(
    "PROMO CODES & VOUCHERS" to "CODES PROMO ET BONS",
    "Got a code? Get money" to "Un code ? Recevez de l’argent",
    "Type a promo code from a MudaPay partner, or a voucher you received. Vouchers are added to your balance right away; some promo codes pay with your first deposit." to
        "Saisissez un code promo d’un partenaire MudaPay ou un bon reçu. Les bons sont ajoutés tout de suite à votre solde ; certains codes promo paient avec votre premier dépôt.",
    "Promo code or voucher" to "Code promo ou bon",
    "REDEEM" to "UTILISER",
    "Promo code" to "Code promo",
    "is reserved for you. It is added to your balance with your first deposit" to "est mis de côté pour vous. Il est ajouté à votre solde avec votre premier dépôt",
    "was added to your balance." to "a été ajouté à votre solde.",
    "Your codes" to "Vos codes",
    "Voucher" to "Bon",
    "Deposit before" to "Déposez avant le",
    "PAID" to "PAYÉ",
    "WAITING" to "EN ATTENTE",
    "EXPIRED" to "EXPIRÉ",
    "Cashback for customers (%)" to "Cashback pour les clients (%)",
    "Your customers get this share of every local payment back, paid from the amount you receive. Maximum" to "Vos clients récupèrent cette part de chaque paiement local, prélevée sur le montant que vous recevez. Maximum",
    "cashback" to "cashback",
    "Cashback" to "Cashback",
    "You get back" to "Vous récupérez",
    "Promo" to "Promo",
    "Promo code & vouchers" to "Code promo et bons",
    "Redeem a code and get money" to "Utilisez un code et recevez de l’argent"
)
