package io.mudapay.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Send money to someone who is NOT on MudaPay yet (server: invite_lib.php, api/v1/lib_invite.php).
 *   - InviteSendSection : shown by the Send screen when "Find member" finds nobody: the money is held and an invitation link is made.
 *   - InviteSentCard    : the answer: code + link, WhatsApp / SMS / share buttons.
 *   - PendingInvites    : the member's invitations (waiting / joined / refunded), share again or cancel and get the money back.
 *   - ClaimInviteCard   : an existing member types an invitation code to receive money that was sent to his number.
 *   - inviteCodeOrNull  : the register screens use it to tell an invitation code (MPK7Q2X9TH) from a plain referral number.
 */

private val INVITE_CODE = Regex("^MP[0-9A-Z]{8}$")

/** "mp k7q2 x9th" -> "MPK7Q2X9TH"; null when the text is not an invitation code (a plain number is a referral id). */
fun inviteCodeOrNull(text: String): String? {
    val c = text.uppercase().filter { it.isLetterOrDigit() }
    return if (INVITE_CODE.matches(c)) c else null
}

/** The sentence shown after registering with an invitation code (null when no code was used). */
fun inviteOutcomeText(inv: JSONObject?): String? {
    if (inv == null) return null
    if (!inv.bool("claimed")) return inv.str("message").ifEmpty { null }
    val sym = inv.str("symbol")
    val bonus = inv.dbl("bonus")
    return L("Your invitation was accepted:") + " " + money(sym, inv.dbl("amount")) + " " + L("from") + " " + inv.str("from") + " " + L("are in your account.") +
        (if (bonus > 0) " " + L("Welcome bonus:") + " " + money(inv.str("bonus_symbol"), bonus) + " " + L("is added with your first deposit") +
            (if (inv.int("bonus_min_deposit") > 0) " " + L("of at least") + " " + money(inv.str("bonus_symbol"), inv.int("bonus_min_deposit").toDouble()) else "") +
            " (" + inv.int("bonus_days") + " " + L("days") + ")." else "")
}

private fun inviteShareText(receiveAmount: String, code: String, link: String, bonusLine: String): String =
    if (AppLanguage.current == "fr")
        "Salut ! Je t’ai envoyé $receiveAmount sur MudaPay 🎁 L’argent t’attend. Installe l’application, crée ton compte avec ce numéro et entre le code $code. $bonusLine$link"
    else
        "Hi! I sent you $receiveAmount on MudaPay 🎁 The money is waiting for you. Install the app, create your account with this number and enter the code $code. $bonusLine$link"

private fun bonusLineOf(b: JSONObject?): String {
    if (b == null || !b.bool("eligible") || b.dbl("amount") <= 0) return ""
    val m = money(b.str("symbol"), b.dbl("amount"))
    return if (AppLanguage.current == "fr") "Bonus de bienvenue : $m offerts après ton premier dépôt. " else "Welcome bonus: $m free after your first deposit. "
}

private fun smsInvite(ctx: Context, phone: String, text: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone))).putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        shareText(ctx, text)   // no SMS app: let the member pick any app instead
    }
}

/* ------------------------------------------------------------------ send (number not on MudaPay) */
/** [offer] = the answer of POST /transfers/lookup when nobody uses the number (found = false, with an "invite" block). */
@Composable
fun InviteSendSection(rules: JSONObject, phone: String, offer: JSONObject, onSent: (JSONObject) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol")
    val myCountry = rules.str("country")
    val min = rules.int("min")
    val remaining = rules.dbl("remaining_today")
    val needEmail = rules.bool("require_verified_email") && !rules.bool("email_verified")
    val pinRequired = rules.bool("pin_required")
    val inv = offer.optJSONObject("invite") ?: JSONObject()
    val toCc = offer.str("country")
    val recvSymbol = offer.str("symbol")
    val bonus = inv.dbl("bonus")
    val bonusMin = inv.int("bonus_min_send")
    val ttl = inv.int("ttl_days")

    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<JSONObject?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf(newIdemKey()) }

    LaunchedEffect(amount, toCc) {
        val amt = amount.toLongOrNull() ?: 0L
        if (amt <= 0 || toCc.isEmpty()) { quote = null; quoting = false; return@LaunchedEffect }
        quoting = true
        delay(400)
        val r = Api.call("POST", "/transfers/quote", JSONObject().put("country", toCc).put("amount", amt))
        quoting = false
        quote = if (r.ok) r.data else null
    }

    fun submit(amt: Long, pin: String) {
        val q = quote
        busy = true
        scope.launch {
            val body = JSONObject().put("phone", phone.trim()).put("amount", amt).put("note", note.trim()).put("country", toCc)
            if (q != null) body.put("expected_receive", q.dbl("receive_amount"))
            if (pin.isNotEmpty()) body.put("pin", pin)
            val r = Api.call("POST", "/transfers/invite", body, idem = idem)
            busy = false
            if (r.ok) {
                idem = newIdemKey()
                Session.balance = r.data.dbl("balance")
                amount = ""; note = ""
                onSent(r.data)
            } else {
                if (r.http != 0) idem = newIdemKey()
                if (r.code == "rate_changed") r.error?.optJSONObject("quote")?.let { quote = it }
                Ui.notify("Send money", r.message)
            }
        }
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MudaIcons.Redeem, null, tint = GreenDark, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(L("Not on MudaPay yet? Send anyway"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        InfoBanner(L("Nobody uses this number on MudaPay yet. You can still send money: it is held safely and the person receives it as soon as they join with your invitation link."), Blue, MudaIcons.Info)
        if (bonus > 0) {
            InfoBanner(
                L("They also get a welcome bonus of") + " " + money(inv.str("bonus_symbol"), bonus) + " " + L("added with their first deposit") +
                    (if (bonusMin > 0) " (" + L("when you send at least") + " " + money(symbol, bonusMin.toDouble()) + ")" else "") + ".",
                GreenDark, MudaIcons.CardGiftcard
            )
        }
        Text(L("If they do not join within") + " $ttl " + L("days, the whole amount, fee included, comes back to you. You can also cancel any time."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        InputField("Amount you send ($symbol)", amount, { amount = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
        Text(L("Minimum: ${money(symbol, min.toDouble())} • You can still send ${money(symbol, remaining)} today"), color = Muted, fontSize = 12.sp)
        val q = quote
        if (q != null) QuoteBreakdown(q, symbol, recvSymbol)
        else if (quoting) Text(L("Calculating…"), color = Muted, fontSize = 12.sp)
        InputField("Note (optional)", note, { note = it.take(120) })
        PrimaryButton("SEND AND INVITE", {
            val amt = amount.toLongOrNull() ?: 0L
            when {
                amt < min -> Ui.notify("Send money", "The minimum transfer is ${money(symbol, min.toDouble())}.")
                amt > Session.balance -> Ui.notify("Send money", "Insufficient balance.")
                amt > remaining -> Ui.notify("Send money", "This is above your daily sending limit. You can still send ${money(symbol, remaining)} today.")
                quoting || quote == null -> Ui.notify("Send money", "Wait a moment while the amount is calculated.")
                else -> confirm = true
            }
        }, loading = busy, enabled = amount.isNotEmpty() && !needEmail, icon = MudaIcons.NorthEast)
    }

    if (askPin) PinPromptDialog("Confirm transfer", onDismiss = { askPin = false }) { pin -> askPin = false; submit(amount.toLongOrNull() ?: 0L, pin) }

    if (confirm) {
        val amt = amount.toLongOrNull() ?: 0L
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Panel,
            title = { Text(L("Send ${money(symbol, amt.toDouble())}?"), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${L("To")}: ${phone.trim()} (${countryName(toCc)})", color = Ink, fontSize = 14.sp)
                    quote?.let { QuoteBreakdown(it, symbol, recvSymbol, compact = true) }
                    Text(L("This person is not on MudaPay yet. The money is held until they join with your invitation link.") + " " + L("If they do not join within") + " $ttl " + L("days, the whole amount, fee included, comes back to you. You can also cancel any time."), color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    AppLock.requireForPayment(ctx.findActivity(), "Confirm transfer", "Confirm it's you to send this money") { if (pinRequired) askPin = true else submit(amt, "") }
                }) { Text(L("Send"), color = GreenDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

/* ------------------------------------------------------------------ sent: share the invitation */
@Composable
fun InviteSentCard(d: JSONObject, onAgain: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val code = d.str("code")
    val link = d.str("link")
    val phone = d.str("phone")
    val text = inviteShareText(money(d.str("receive_symbol"), d.dbl("receive_amount")), code, link, bonusLineOf(d.optJSONObject("bonus_offer")))
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).background(GreenDark.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(MudaIcons.Check, null, tint = GreenDark, modifier = Modifier.size(34.dp))
        }
        Text(L("Money on hold"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(money(d.str("symbol"), d.dbl("amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 30.sp)
        Text(L("for") + " $phone", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(
            L("Now send them the invitation. As soon as they join MudaPay with this number and enter the code, the money is theirs. Not claimed in") + " ${d.int("ttl_days")} " + L("days: you get it back."),
            color = Ink, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center
        )
        Column(Modifier.fillMaxWidth().background(GreenDark.copy(alpha = .08f), RoundedCornerShape(14.dp)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(L("Invitation code"), color = Muted, fontSize = 12.sp)
            Text(code, color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 2.sp)
        }
        PrimaryButton(softCase(L("SEND ON WHATSAPP")), { shareWhatsApp(ctx, text) }, icon = MudaIcons.Chat)
        PrimaryButton(softCase(L("SEND BY SMS")), { smsInvite(ctx, phone, text) }, color = Dark, icon = MudaIcons.Sms)
        GhostButton(softCase(L("SHARE WITH ANOTHER APP")), { shareText(ctx, text) }, icon = MudaIcons.Share)
        GhostButton(softCase(L("COPY LINK")), { copyToClipboard(ctx, "Invitation link", link); Ui.notify("Copied", "The invitation link was copied.") }, icon = MudaIcons.ContentCopy)
        PrimaryButton("SEND AGAIN", onAgain, color = Dark, icon = MudaIcons.NorthEast)
        TextButton(onClick = onDone) { Text(L("Back to home"), color = GreenDark, fontWeight = FontWeight.Bold) }
    }
}

/* ------------------------------------------------------------------ my invitations */
@Composable
fun PendingInvites(refreshKey: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableIntStateOf(0) }
    var cancelling by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }

    Remote(refreshKey = "$refreshKey-$key", call = { Api.call("GET", "/transfers/invites") }) { d, _ ->
        val items = d.list("items").take(10)
        if (items.isNotEmpty()) {
            Text(L("My invitations"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
            if (d.int("open") > 0) Text(L("Money waiting for friends to join:") + " " + money(Session.user?.str("symbol").orEmpty(), d.dbl("held")), color = Muted, fontSize = 12.sp)
        }
        items.forEach { t ->
            val status = t.str("status")
            val pending = status == "pending"
            val tone = when (status) { "pending" -> Blue; "claimed" -> GreenDark; else -> Muted }
            val label = when (status) { "pending" -> "WAITING"; "claimed" -> "JOINED"; else -> "REFUNDED" }
            Column(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.str("phone"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                        Text(
                            money(t.str("symbol"), t.dbl("amount")) + " • " + t.str("created_at").take(10) +
                                (if (pending) " • " + t.int("days_left") + " " + L("days left") else "") +
                                (if (status == "claimed" && t.str("bonus_status") == "released") " • " + L("bonus paid")
                                 else if (status == "claimed" && t.str("bonus_status") == "held") " • " + L("bonus after first deposit") else ""),
                            color = Muted, fontSize = 12.sp
                        )
                    }
                    Pill(label, tone)
                }
                if (pending) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(softCase(L("SHARE AGAIN")), {
                            val txt = inviteShareText(money(t.str("receive_symbol"), t.dbl("receive_amount")), t.str("code"), t.str("link"), "")
                            shareText(ctx, txt)   // the system chooser lists WhatsApp, SMS and the rest
                        }, Modifier.weight(1f), icon = MudaIcons.Share)
                        GhostButton(softCase(L("CANCEL")), { cancelling = t }, Modifier.weight(1f), icon = MudaIcons.Cancel)
                    }
                }
            }
        }
    }

    cancelling?.let { t ->
        AlertDialog(
            onDismissRequest = { if (!busy) cancelling = null },
            containerColor = Panel,
            title = { Text(L("Cancel this invitation?"), fontWeight = FontWeight.Bold) },
            text = { Text(L("The money held for") + " ${t.str("phone")} " + L("goes back to your balance now, fee included. The invitation link will stop working."), color = Ink, fontSize = 14.sp) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val r = Api.call("POST", "/transfers/invites/${t.int("id")}/cancel", JSONObject())
                        busy = false
                        cancelling = null
                        if (r.ok) { Session.balance = r.data.dbl("balance"); Ui.notify("Invitation cancelled", "The money is back in your balance.") }
                        else Ui.notify("Invitation", r.message)
                        key++
                    }
                }) { Text(L("Cancel and get my money back"), color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { cancelling = null }) { Text(L("Keep it"), color = Muted) } }
        )
    }
}

/* ------------------------------------------------------------------ claim (member who already has an account) */
@Composable
fun ClaimInviteCard() {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(L("Got an invitation code?"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("If someone sent you money before you had MudaPay, type the code from their invitation here. Your account must use the phone number they sent it to."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        InputField("Invitation code (MP…)", code, { code = it.take(14) })
        PrimaryButton("CLAIM MY MONEY", {
            val c = inviteCodeOrNull(code) ?: return@PrimaryButton
            busy = true
            scope.launch {
                val r = Api.call("POST", "/transfers/claim", JSONObject().put("code", c))
                busy = false
                if (r.ok) {
                    Session.balance = r.data.dbl("balance")
                    code = ""
                    Ui.notify("Money received", inviteOutcomeText(r.data) ?: "Your money was added to your balance.")
                } else Ui.notify("Invitation", r.message)
            }
        }, loading = busy, enabled = inviteCodeOrNull(code) != null, color = Dark, icon = MudaIcons.Redeem)
    }
}

/* ------------------------------------------------------------------ French */
internal val frenchInvite: Map<String, String> = mapOf(
    "Not on MudaPay yet? Send anyway" to "Pas encore sur MudaPay ? Envoyez quand même",
    "Nobody uses this number on MudaPay yet. You can still send money: it is held safely and the person receives it as soon as they join with your invitation link." to
        "Personne n’utilise ce numéro sur MudaPay pour le moment. Vous pouvez quand même envoyer de l’argent : il est mis de côté en sécurité et la personne le reçoit dès qu’elle rejoint MudaPay avec votre lien d’invitation.",
    "They also get a welcome bonus of" to "Elle reçoit aussi un bonus de bienvenue de",
    "when you send at least" to "si vous envoyez au moins",
    "If they do not join within" to "Si elle ne rejoint pas MudaPay dans les",
    "days, the whole amount, fee included, comes back to you. You can also cancel any time." to "jours, tout le montant, frais compris, vous est rendu. Vous pouvez aussi annuler à tout moment.",
    "This person is not on MudaPay yet. The money is held until they join with your invitation link." to
        "Cette personne n’est pas encore sur MudaPay. L’argent est mis de côté jusqu’à ce qu’elle rejoigne MudaPay avec votre lien d’invitation.",
    "SEND AND INVITE" to "ENVOYER ET INVITER",
    "Money on hold" to "Argent mis de côté",
    "for" to "pour",
    "Now send them the invitation. As soon as they join MudaPay with this number and enter the code, the money is theirs. Not claimed in" to
        "Envoyez-lui maintenant l’invitation. Dès qu’elle rejoint MudaPay avec ce numéro et entre le code, l’argent est à elle. Non réclamé en",
    "days: you get it back." to "jours : il vous est rendu.",
    "Invitation code" to "Code d’invitation",
    "SEND ON WHATSAPP" to "ENVOYER SUR WHATSAPP",
    "SEND BY SMS" to "ENVOYER PAR SMS",
    "SHARE WITH ANOTHER APP" to "PARTAGER AVEC UNE AUTRE APPLICATION",
    "COPY LINK" to "COPIER LE LIEN",
    "The invitation link was copied." to "Le lien d’invitation a été copié.",
    "My invitations" to "Mes invitations",
    "Money waiting for friends to join:" to "Argent en attente de vos amis :",
    "WAITING" to "EN ATTENTE",
    "JOINED" to "INSCRIT",
    "REFUNDED" to "REMBOURSÉ",
    "days left" to "jours restants",
    "bonus paid" to "bonus versé",
    "bonus after first deposit" to "bonus après le premier dépôt",
    "added with their first deposit" to "ajouté lors de son premier dépôt",
    "is added with your first deposit" to "sera ajouté avec votre premier dépôt",
    "of at least" to "d’au moins",
    "days" to "jours",
    "SHARE AGAIN" to "PARTAGER À NOUVEAU",
    "CANCEL" to "ANNULER",
    "Cancel this invitation?" to "Annuler cette invitation ?",
    "The money held for" to "L’argent mis de côté pour",
    "goes back to your balance now, fee included. The invitation link will stop working." to "retourne maintenant sur votre solde, frais compris. Le lien d’invitation ne fonctionnera plus.",
    "Cancel and get my money back" to "Annuler et récupérer mon argent",
    "Keep it" to "Garder",
    "Invitation cancelled" to "Invitation annulée",
    "The money is back in your balance." to "L’argent est revenu sur votre solde.",
    "Got an invitation code?" to "Vous avez un code d’invitation ?",
    "If someone sent you money before you had MudaPay, type the code from their invitation here. Your account must use the phone number they sent it to." to
        "Si quelqu’un vous a envoyé de l’argent avant que vous ayez MudaPay, saisissez ici le code de son invitation. Votre compte doit utiliser le numéro de téléphone auquel il l’a envoyé.",
    "Invitation code (MP…)" to "Code d’invitation (MP…)",
    "CLAIM MY MONEY" to "RÉCLAMER MON ARGENT",
    "Money received" to "Argent reçu",
    "Your money was added to your balance." to "Votre argent a été ajouté à votre solde.",
    "Your invitation was accepted:" to "Votre invitation a été acceptée :",
    "from" to "de",
    "are in your account." to "sont sur votre compte.",
    "Welcome bonus:" to "Bonus de bienvenue :",
    "Referral or invite code (optional)" to "Code de parrainage ou d’invitation (facultatif)"
)
