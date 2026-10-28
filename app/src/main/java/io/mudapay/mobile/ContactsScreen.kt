package io.mudapay.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pick contacts to send money.
 *   1. LOAD PHONE CONTACTS   reads the phone book (READ_CONTACTS) and saves it on the MudaPay server, private to this member.
 *   2. CHECK FOR FRIENDS     the server matches the saved numbers against MudaPay members.
 *   3. "On MudaPay" tab      scroll, tap SEND, type the amount, confirm with the transaction PIN. The money moves through the normal
 *                            POST /transfers, so limits, PIN, exchange rate and idempotency are exactly the same as on the Send screen.
 *   4. "Invite" tab          the rest of the phone book: INVITE opens the SMS app with the member's referral link.
 */

/* ------------------------------------------------------------------ reading the phone book */
private fun readPhoneContacts(ctx: Context): List<Pair<String, String>> {
    val out = LinkedHashMap<String, Pair<String, String>>()
    val nameCol = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
    val numberCol = ContactsContract.CommonDataKinds.Phone.NUMBER
    try {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(nameCol, numberCol), null, null, "$nameCol COLLATE NOCASE ASC"
        )?.use { c ->
            val iName = c.getColumnIndex(nameCol)
            val iNumber = c.getColumnIndex(numberCol)
            if (iName < 0 || iNumber < 0) return emptyList()
            while (c.moveToNext()) {
                val phone = (c.getString(iNumber) ?: "").trim()
                val digits = phone.filter { it.isDigit() }
                if (digits.length < 6 || digits.length > 16) continue
                val name = (c.getString(iName) ?: "").trim().ifEmpty { phone }
                if (!out.containsKey(digits)) out[digits] = name.take(100) to phone.take(30)
            }
        }
    } catch (e: SecurityException) {
        return emptyList()
    }
    return out.values.toList()
}

private fun inviteText(link: String): String =
    if (AppLanguage.current == "fr") "Salut ! J’utilise MudaPay pour envoyer et recevoir de l’argent instantanément. Rejoins-moi ici : $link"
    else "Hi! I use MudaPay to send and receive money instantly. Join me here: $link"

private fun openSmsInvite(ctx: Context, phone: String, text: String) {
    try {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone))).putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: Exception) {
        shareText(ctx, text)   // no SMS app: let the member pick any app instead
    }
}

private fun initialsOf(name: String): String =
    name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

@Composable
private fun ContactAvatar(name: String, tint: Color) {
    Box(Modifier.size(44.dp).background(tint.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
        Text(initialsOf(name), color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun SmallAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        modifier = Modifier.background(color, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

@Composable
private fun FilterTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.background(if (selected) GreenDark else Color.White, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (selected) Color.White else Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

/* ------------------------------------------------------------------ screen */
@Composable
fun ContactsScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = Session.user?.str("symbol") ?: ""

    var tab by remember { mutableStateOf("mudapay") }          // mudapay | invite
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }

    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var members by remember { mutableIntStateOf(0) }
    var others by remember { mutableIntStateOf(0) }
    var referralLink by remember { mutableStateOf(Session.user?.str("referral_link").orEmpty()) }

    var busy by remember { mutableStateOf<String?>(null) }      // text shown while loading / checking
    var busyKind by remember { mutableStateOf("") }             // "load" or "check"
    var info by remember { mutableStateOf<String?>(null) }
    var askConsent by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    var payTo by remember { mutableStateOf<JSONObject?>(null) }

    fun applyCounts(d: JSONObject) {
        total = d.int("total"); members = d.int("members"); others = d.int("others")
    }

    suspend fun loadPage(cursor: Int?) {
        val q = HashMap<String, String>()
        q["filter"] = tab
        if (applied.isNotBlank()) q["q"] = applied.trim()
        if (cursor != null) q["cursor"] = cursor.toString()
        val r = Api.call("GET", "/contacts", query = q)
        loading = false
        if (!r.ok) { loadError = r.message; return }
        loadError = null
        val page = r.data.list("items")
        items = if (cursor == null) page else items + page
        nextCursor = if (r.data.isNull("next_cursor")) null else r.data.int("next_cursor")
        r.data.optJSONObject("counts")?.let { applyCounts(it) }
        r.data.str("referral_link").takeIf { it.isNotEmpty() }?.let { referralLink = it }
    }

    LaunchedEffect(query) { delay(350); applied = query }
    LaunchedEffect(tab, applied, reloadKey, Ui.refreshTick) { loading = true; loadPage(null) }

    // ---- 1. load the phone book and save it on the server
    fun uploadContacts() {
        busy = L("Reading your contacts…"); busyKind = "load"; info = null
        scope.launch {
            val list = withContext(Dispatchers.IO) { readPhoneContacts(ctx) }
            if (list.isEmpty()) { busy = null; info = L("No contacts with a phone number were found on this phone."); return@launch }
            var saved = 0
            var skipped = 0
            var failed: String? = null
            val chunks = list.chunked(400)
            for ((n, chunk) in chunks.withIndex()) {
                busy = L("Saving contacts…") + " ${minOf((n + 1) * 400, list.size)}/${list.size}"
                val arr = JSONArray()
                chunk.forEach { arr.put(JSONObject().put("name", it.first).put("phone", it.second)) }
                val r = Api.call("POST", "/contacts/sync", JSONObject().put("contacts", arr), timeoutMs = 60000)
                if (!r.ok) { failed = r.message; break }
                saved += r.data.int("saved"); skipped += r.data.int("skipped")
                applyCounts(r.data)
            }
            busy = null
            info = failed ?: (L("Contacts saved:") + " $saved." + (if (skipped > 0) " " + L("Some were skipped (limit reached).") else "") + " " + L("Now tap CHECK FOR FRIENDS."))
            reloadKey++
        }
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) uploadContacts() else info = L("Allow MudaPay to read your contacts to find your friends. You can change this in the phone settings.")
    }
    fun startLoad() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) uploadContacts()
        else askPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    // ---- 2. check for friends
    fun checkFriends() {
        busy = L("Looking for friends on MudaPay…"); busyKind = "check"; info = null
        scope.launch {
            val r = Api.call("POST", "/contacts/check", timeoutMs = 90000)
            busy = null
            if (r.ok) {
                applyCounts(r.data)
                info = if (r.data.int("members") > 0) "${r.data.int("members")} " + L("of your contacts use MudaPay.") else L("None of your contacts use MudaPay yet. Invite them!")
                tab = if (r.data.int("members") > 0) "mudapay" else "invite"
                reloadKey++
            } else info = r.message
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().heroCard().padding(20.dp)) {
            Text(softCase(L("PICK CONTACTS TO SEND MONEY")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
            Text("$members ${L("on MudaPay")}", color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("$total ${L("contacts saved")} • ${money(symbol, Session.balance)}", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
        }

        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L("Find your friends"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                L("Load the contacts of your phone, then check which ones already use MudaPay. Send them money in a few taps, and invite the others."),
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp
            )
            PrimaryButton(softCase(L("LOAD PHONE CONTACTS")), { if (total == 0) askConsent = true else startLoad() }, loading = busy != null && busyKind == "load", enabled = busy == null, icon = Icons.Default.Contacts)
            PrimaryButton(softCase(L("CHECK FOR FRIENDS")), { checkFriends() }, loading = busy != null && busyKind == "check", enabled = busy == null && total > 0, color = Dark, icon = Icons.Default.PersonSearch)
            busy?.let { Text(it, color = Muted, fontSize = 12.sp) }
            info?.let { InfoBanner(it, Blue, Icons.Default.Info) }
            if (total > 0) {
                TextButton(onClick = { askClear = true }, enabled = busy == null) {
                    Text(L("Remove my contacts from MudaPay"), color = Danger, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        if (total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterTab("${L("On MudaPay")} ($members)", tab == "mudapay", Modifier.weight(1f)) { tab = "mudapay" }
                FilterTab("${L("Invite")} ($others)", tab == "invite", Modifier.weight(1f)) { tab = "invite" }
            }
            InputField(L("Search name or number"), query, { query = it.take(60) })

            loadError?.let { InfoBanner(it, Danger, Icons.Default.Info) }
            if (loading && items.isEmpty()) SkeletonCards(2)
            else if (items.isEmpty() && loadError == null) {
                if (tab == "mudapay") EmptyState(L("No friends found yet"), Icons.Default.Groups, L("Tap CHECK FOR FRIENDS, or invite your contacts from the Invite tab."))
                else EmptyState(L("Nobody to invite here"), Icons.Default.Groups, if (applied.isBlank()) L("All your contacts already use MudaPay.") else null)
            }

            items.forEach { c ->
                val member = c.optJSONObject("member")
                if (c.bool("on_mudapay") && member != null) {
                    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(c.str("name"), GreenDark)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.str("name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text("${c.str("phone")} • ${countryName(member.str("country"))}", color = Muted, fontSize = 12.sp, maxLines = 1)
                            Text("MudaPay: ${member.str("name")}", color = GreenDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        SmallAction(softCase(L("SEND")), GreenDark) { payTo = c }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(c.str("name"), Slate)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.str("name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text(c.str("phone"), color = Muted, fontSize = 12.sp, maxLines = 1)
                            if (c.bool("invited")) Text(L("Invitation sent"), color = Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(8.dp))
                        SmallAction(if (c.bool("invited")) softCase(L("REMIND")) else softCase(L("INVITE")), Blue) {
                            val id = c.int("id")
                            openSmsInvite(ctx, c.str("phone"), inviteText(referralLink))
                            scope.launch {
                                val r = Api.call("POST", "/contacts/invite", JSONObject().put("contact_id", id))
                                if (r.ok) items = items.map { if (it.int("id") == id) JSONObject(it.toString()).put("invited", true) else it }
                            }
                        }
                    }
                }
            }
            nextCursor?.let { cur ->
                PrimaryButton(softCase(L("LOAD MORE")), { scope.launch { loadPage(cur) } }, color = Dark, icon = Icons.Default.ExpandMore)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ---- dialogs
    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false }, containerColor = Color.White,
            title = { Text(L("Load your contacts?"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    L("MudaPay will read the names and phone numbers in your phone book and save them on its server, only to show you which of your contacts use MudaPay and to let you invite the others. They are never shown to other members. You can remove them at any time."),
                    color = Ink, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = { TextButton(onClick = { askConsent = false; startLoad() }) { Text(L("Continue"), color = GreenDark, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false }, containerColor = Color.White,
            title = { Text(L("Remove your contacts?"), fontWeight = FontWeight.Bold) },
            text = { Text(L("Every contact saved on MudaPay for your account will be deleted. Your phone book is not changed."), color = Ink, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    askClear = false
                    scope.launch {
                        val r = Api.call("POST", "/contacts/clear")
                        if (r.ok) { applyCounts(r.data); items = emptyList(); info = L("Your contacts were removed from MudaPay."); reloadKey++ } else info = r.message
                    }
                }) { Text(L("Remove"), color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
    payTo?.let { c -> ContactPayDialog(c, onClose = { payTo = null }) }
}

/* ------------------------------------------------------------------ send money to one contact */
@Composable
private fun ContactPayDialog(contact: JSONObject, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val phone = contact.str("phone")
    val contactCountry = contact.str("country")

    var rules by remember { mutableStateOf<JSONObject?>(null) }
    var found by remember { mutableStateOf<JSONObject?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<JSONObject?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<JSONObject?>(null) }
    val idem = remember { newIdemKey() }
    var idemKey by remember { mutableStateOf(idem) }

    // who owns this number, and what are the member's sending rules
    LaunchedEffect(contact.int("id")) {
        val rr = Api.call("GET", "/transfers/rules")
        if (!rr.ok) { problem = rr.message; return@LaunchedEffect }
        val lk = Api.call("POST", "/transfers/lookup", JSONObject().put("phone", phone).put("country", contactCountry.ifEmpty { rr.data.str("country") }))
        when {
            !lk.ok -> problem = lk.message
            !lk.data.bool("found") -> problem = if (lk.data.bool("self")) "That is your own number." else "This contact is no longer reachable on MudaPay. Run CHECK FOR FRIENDS again."
            !lk.data.bool("can_receive") -> problem = "This member cannot receive money right now."
            !lk.data.bool("cross_border_allowed") -> problem = "Sending to other countries is paused right now."
            else -> { rules = rr.data; found = lk.data }
        }
    }

    val r = rules
    val f = found
    val myCountry = r?.str("country").orEmpty()
    val symbol = r?.str("symbol").orEmpty()
    val cross = f != null && f.str("country") != myCountry
    val recvSymbol = f?.str("symbol").orEmpty()

    // across a border, ask the server what the receiver gets (rate + fee) whenever the amount changes
    LaunchedEffect(amount, found) {
        val ff = found
        val amt = amount.toLongOrNull() ?: 0L
        if (ff == null || ff.str("country") == myCountry || amt <= 0) { quote = null; quoting = false; return@LaunchedEffect }
        quoting = true
        delay(400)
        val q = Api.call("POST", "/transfers/quote", JSONObject().put("country", ff.str("country")).put("amount", amt))
        quoting = false
        quote = if (q.ok) q.data else null
    }

    fun submit(pin: String) {
        val ff = found ?: return
        val amt = amount.toLongOrNull() ?: return
        val q = quote
        busy = true
        scope.launch {
            val body = JSONObject().put("phone", phone).put("amount", amt).put("note", "").put("country", ff.str("country"))
            if (cross && q != null) body.put("expected_receive", q.dbl("receive_amount"))
            if (pin.isNotEmpty()) body.put("pin", pin)
            val res = Api.call("POST", "/transfers", body, idem = idemKey)
            busy = false
            if (res.ok) {
                idemKey = newIdemKey()
                Session.balance = res.data.dbl("balance")
                val who = res.data.optJSONObject("recipient")
                Notify.moneySent(res.data.dbl("amount"), who?.str("name").orEmpty(), who?.str("phone").orEmpty(), res.data.str("reference"), res.data.str("note"), Session.balance)
                Ui.refreshTick++
                done = res.data
            } else {
                if (res.http != 0) idemKey = newIdemKey()
                if (res.code == "rate_changed") res.error?.optJSONObject("quote")?.let { quote = it }   // show the new figures, let the member confirm again
                Ui.notify(L("Send money"), res.message)
            }
        }
    }

    fun validateThen(go: () -> Unit) {
        val rl = rules ?: return
        val amt = amount.toLongOrNull() ?: 0L
        val min = rl.int("min")
        val remaining = rl.dbl("remaining_today")
        when {
            rl.bool("require_verified_email") && !rl.bool("email_verified") -> Ui.notify(L("Send money"), L("Verify your email in Settings before you can send money."))
            amt < min -> Ui.notify(L("Send money"), "${L("The minimum transfer is")} ${money(symbol, min.toDouble())}.")
            amt > Session.balance -> Ui.notify(L("Send money"), L("Insufficient balance."))
            amt > remaining -> Ui.notify(L("Send money"), "${L("This is above your daily sending limit. You can still send")} ${money(symbol, remaining)}.")
            cross && quote == null -> Ui.notify(L("Send money"), L("Wait a moment while the exchange rate loads."))
            else -> go()
        }
    }

    val amt = amount.toLongOrNull() ?: 0L
    val toName = f?.str("name").orEmpty()
    val doneNow = done

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() }, containerColor = Color.White,
        title = { Text(if (doneNow != null) L("Money sent") else "${L("Send to")} ${contact.str("name")}", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    doneNow != null -> {
                        Text(money(symbol, doneNow.dbl("amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                        if (doneNow.bool("cross_border")) Text("${L("They receive")} ${money(doneNow.str("receive_symbol"), doneNow.dbl("receive_amount"))}", color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${L("To")} ${doneNow.optJSONObject("recipient")?.str("name").orEmpty()}", color = Ink, fontSize = 14.sp)
                        Text("${L("Reference")}: ${doneNow.str("reference")}", color = Slate, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${L("New balance")}: ${money(symbol, doneNow.dbl("balance"))}", color = Muted, fontSize = 12.sp)
                    }
                    problem != null -> InfoBanner(L(problem!!), Danger, Icons.Default.Info)
                    f == null || r == null -> LoadingBox()
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ContactAvatar(toName, GreenDark)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(toName, color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${f.str("phone")} • ${f.str("country_name").ifEmpty { countryName(f.str("country")) }}", color = Muted, fontSize = 12.sp)
                            }
                            Pill(if (cross) softCase(L("ABROAD")) else softCase(L("MUDAPAY MEMBER")), if (cross) Blue else GreenDark)
                        }
                        InputField("${L("Amount you send")} ($symbol)", amount, { amount = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
                        Text("${L("Minimum")}: ${money(symbol, r.int("min").toDouble())} • ${L("You can still send")} ${money(symbol, r.dbl("remaining_today"))}", color = Muted, fontSize = 12.sp)
                        if (cross) {
                            val q = quote
                            if (q != null) {
                                Column(Modifier.fillMaxWidth().background(GreenDark.copy(alpha = .08f), RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(L("They receive"), color = Muted, fontSize = 12.sp)
                                    Text(money(recvSymbol, q.dbl("receive_amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    Text("${L("Rate")}: 1 $symbol = ${fmtRate(q.dbl("rate"))} $recvSymbol • ${L("Fee")}: ${money(symbol, q.dbl("fee"))}", color = Muted, fontSize = 12.sp)
                                }
                            } else if (quoting) Text(L("Calculating…"), color = Muted, fontSize = 12.sp)
                        }
                        Text(L("Transfers are instant and cannot be undone. Make sure the person is right."), color = Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            when {
                doneNow != null -> TextButton(onClick = onClose) { Text(L("Done"), color = GreenDark, fontWeight = FontWeight.Bold) }
                f != null && r != null && problem == null ->
                    TextButton(enabled = !busy && amt > 0, onClick = {
                        validateThen {
                            // with a transaction PIN the PIN prompt is the confirmation; without one, ask once before the money leaves
                            AppLock.requireForWithdrawal(ctx.findActivity()) { if (r.bool("pin_required")) askPin = true else confirm = true }
                        }
                    }) { Text(if (busy) L("Sending…") else L("Send"), color = GreenDark, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { if (doneNow == null) TextButton(enabled = !busy, onClick = onClose) { Text(L("Cancel"), color = Muted) } }
    )

    if (askPin && f != null) {
        PinPromptDialog("${L("Send")} ${money(symbol, amt.toDouble())} ${L("to")} $toName", onDismiss = { askPin = false }) { pin -> askPin = false; submit(pin) }
    }
    if (confirm && f != null) {
        AlertDialog(
            onDismissRequest = { confirm = false }, containerColor = Color.White,
            title = { Text("${L("Send")} ${money(symbol, amt.toDouble())}?", fontWeight = FontWeight.Bold) },
            text = { Text("${L("To")}: $toName (${f.str("phone")})", color = Ink, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { confirm = false; submit("") }) { Text(L("Send"), color = GreenDark, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}
