package io.mudapay.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Group requests (split payments), shown inside the Requests screen (RequestScreens.kt).
 *   Groups tab – every group I started: who paid, who has not, REMIND the people who have not paid, CANCEL what is still open.
 *   New tab    – "Split with a group": name, total, people (found by phone) and an equal or a custom split.
 * Every person gets an ordinary payment request for his share: he pays or declines it in his Received tab, exactly like any request.
 */

private fun statusTone(status: String): Color = when (status) {
    "paid", "complete" -> GreenDark
    "pending", "open" -> Blue
    "declined" -> Danger
    else -> Muted
}

@Composable
fun GroupList(refreshKey: Int, onChanged: () -> Unit) {
    Remote(refreshKey = refreshKey, call = { Api.call("GET", "/groups") }) { d, _ ->
        val items = d.list("items")
        if (items.isEmpty()) {
            EmptyState(L("No group requests yet"), MudaIcons.Groups, L("Split a bill: ask several people for their share and see who paid."))
        }
        items.forEach { GroupCard(it, onChanged) }
    }
}

@Composable
private fun GroupCard(g: JSONObject, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status = g.str("status")
    val symbol = g.str("symbol")
    val counts = g.optJSONObject("counts") ?: JSONObject()
    val requested = g.dbl("requested")
    val collected = g.dbl("collected")
    val progress = if (requested > 0) (collected / requested).toFloat().coerceIn(0f, 1f) else 0f
    val tone = statusTone(status)
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var askCancel by remember { mutableStateOf(false) }

    fun remind() {
        busy = true
        scope.launch {
            val r = Api.call("POST", "/groups/${g.int("id")}/remind", idem = newIdemKey())
            busy = false
            if (r.ok) Ui.notify(L("Reminder sent"), "${r.data.int("reminded")} ${L("people were reminded.")}") else Ui.notify(L("Group request"), r.message)
            onChanged()
        }
    }
    fun cancel() {
        busy = true
        scope.launch {
            val r = Api.call("POST", "/groups/${g.int("id")}/cancel")
            busy = false
            if (r.ok) Ui.notify(L("Group request"), L("Group cancelled. Shares already paid stay paid.")) else Ui.notify(L("Group request"), r.message)
            onChanged()
        }
    }

    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            IconChip(MudaIcons.Groups, tone, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.str("title"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                Text("${counts.int("paid")} ${L("of")} ${counts.int("total")} ${L("paid")} • ${g.str("created_at").take(16)}", color = Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(symbol, g.dbl("total")), color = tone, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Pill(L(status.uppercase()), tone)
            }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = Green, trackColor = Soft)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${L("Collected")}: ${money(symbol, collected)}", color = GreenDark, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            if (g.dbl("waiting") > 0) Text("${L("Waiting")}: ${money(symbol, g.dbl("waiting"))}", color = Muted, fontSize = 12.sp)
        }
        if (open) {
            if (g.str("note").isNotEmpty()) Text("“${g.str("note")}”", color = Slate, fontSize = 12.sp)
            g.list("members").forEach { m ->
                val ms = m.str("status")
                val mt = statusTone(ms)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MemberAvatar(m.str("name"), m.str("phone"), 36.dp, mt, m.str("avatar").ifEmpty { null })
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.str("name"), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                        Text(if (ms == "paid" && m.str("paid_at").isNotEmpty()) m.str("paid_at").take(16) else m.str("phone"), color = Muted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(symbol, m.dbl("amount")), color = mt, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Pill(L(ms.uppercase()), mt)
                    }
                }
            }
            if (g.dbl("my_share") > 0) Text("${L("Your own share")}: ${money(symbol, g.dbl("my_share"))}", color = Muted, fontSize = 12.sp)
        }
        if (status == "open") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("REMIND UNPAID", { remind() }, Modifier.weight(1f), loading = busy, icon = MudaIcons.Notifications)
                PrimaryButton("CANCEL", { askCancel = true }, Modifier.weight(0.6f), enabled = !busy, color = Dark)
            }
        }
    }
    if (askCancel) {
        AlertDialog(
            onDismissRequest = { askCancel = false }, containerColor = Color.White,
            title = { Text(L("Cancel this group?"), fontWeight = FontWeight.Bold) },
            text = { Text(L("Everyone who has not paid yet will no longer be able to pay. Shares already paid stay paid."), color = Muted, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { askCancel = false; cancel() }) { Text(L("Cancel group"), color = Danger, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { askCancel = false }) { Text(L("Keep"), color = Muted) } }
        )
    }
}

/* ------------------------------------------------------------------ new group */
private class GroupPerson(val name: String, val phone: String, val country: String) {
    var amount by mutableStateOf("")
}

/** Same maths as the server (lib_group.php): everybody pays floor(total / people); the odd remainder is mine, or goes +1 to the first people when I am not in. */
private fun equalShares(total: Long, people: Int, includeMe: Boolean): Pair<List<Long>, Long> {
    val heads = people + if (includeMe) 1 else 0
    if (people == 0 || heads == 0 || total <= 0) return emptyList<Long>() to 0L
    val base = total / heads
    val rem = total - base * heads
    val shares = List(people) { i -> base + if (!includeMe && i < rem) 1L else 0L }
    return shares to (if (includeMe) base + rem else 0L)
}

@Composable
fun NewGroupForm(rules: JSONObject, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val symbol = rules.str("symbol")
    val myCountry = rules.str("country")
    val min = rules.int("min")
    val countryList = rules.list("countries")
    val countryOptions = countryList.filter { rules.bool("cross_border") || it.str("code") == myCountry }.map { it.str("code") to it.str("name") }
    val maxPeople = 10

    var title by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    var includeMe by remember { mutableStateOf(true) }
    var total by remember { mutableStateOf("") }
    var myShare by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val people = remember { mutableStateListOf<GroupPerson>() }

    var country by remember { mutableStateOf(myCountry) }
    var phone by remember { mutableStateOf("") }
    var lookupMsg by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf(newIdemKey()) }

    fun lookup() {
        looking = true; lookupMsg = null
        scope.launch {
            val r = Api.call("POST", "/transfers/lookup", JSONObject().put("phone", phone.trim()).put("country", country))
            looking = false
            val digits = phone.filter { it.isDigit() }
            if (!r.ok) lookupMsg = r.message
            else if (!r.data.bool("found")) lookupMsg = if (r.data.bool("self")) L("You are added automatically. Do not add your own number.") else L("No MudaPay account uses this number. Check the number and the country, then try again.")
            else if (!r.data.bool("can_receive")) lookupMsg = L("This member cannot receive requests right now.")
            else if (!r.data.bool("cross_border_allowed")) lookupMsg = L("Requests to other countries are paused right now.")
            else if (people.any { it.phone.filter { c -> c.isDigit() } == digits }) lookupMsg = L("This person is already in the list.")
            else if (people.size >= maxPeople) lookupMsg = "${L("A group can have up to")} $maxPeople ${L("people.")}"
            else {
                people.add(GroupPerson(r.data.str("name"), phone.trim(), r.data.str("country", country)))
                phone = ""
            }
        }
    }

    val totalNum = total.toLongOrNull() ?: 0L
    val equal = equalShares(totalNum, people.size, includeMe)
    val customSum = people.sumOf { it.amount.toLongOrNull() ?: 0L }
    val customTotal = customSum + (myShare.toLongOrNull() ?: 0L)
    val shareList: List<Long> = if (custom) people.map { it.amount.toLongOrNull() ?: 0L } else equal.first
    val allShares = shareList.isNotEmpty() && shareList.size == people.size && shareList.all { it >= min }
    val ready = title.isNotBlank() && people.isNotEmpty() && allShares && (custom || totalNum > 0)

    // 1. what is it for
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L("Split a bill"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        InputField(L("Name (for example Dinner Friday)"), title, { title = it.take(80) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip(L("Split equally"), !custom, Modifier.weight(1f)) { custom = false }
            TabChip(L("Custom amounts"), custom, Modifier.weight(1f)) { custom = true }
        }
        if (!custom) {
            InputField("${L("Total to collect")} ($symbol)", total, { total = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(L("I am part of the split"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(L("You pay your own share, the others pay theirs."), color = Muted, fontSize = 12.sp)
                }
                Switch(checked = includeMe, onCheckedChange = { includeMe = it }, colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.White))
            }
        } else {
            InputField("${L("My own share, if any")} ($symbol)", myShare, { myShare = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
        }
        InputField(L("Note (optional)"), note, { note = it.take(120) })
    }

    // 2. who
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${L("Who should pay")} (${people.size}/$maxPeople)", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        people.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(p.name, p.phone, 40.dp, GreenDark, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                    if (custom) {
                        InputField("${L("Amount")} ($symbol)", p.amount, { p.amount = it.filter { c -> c.isDigit() }.take(12) }, keyboard = KeyboardType.Number)
                    } else {
                        val s = equal.first.getOrNull(i) ?: 0L
                        Text(if (s > 0) "${L("Pays")} ${money(symbol, s.toDouble())}" else p.phone, color = Muted, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { people.removeAt(i) }) { Icon(MudaIcons.Close, L("Remove"), tint = Danger, modifier = Modifier.size(18.dp)) }
            }
        }
        if (people.size < maxPeople) {
            if (countryOptions.size > 1) Dropdown(L("Member's country"), country, countryOptions) { country = it; lookupMsg = null }
            val dial = countryList.firstOrNull { it.str("code") == country }?.str("dial").orEmpty()
            InputField(if (dial.isNotEmpty()) "Phone number (+$dial …)" else "Phone number (with country code)", phone,
                { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20); lookupMsg = null }, keyboard = KeyboardType.Phone)
            PrimaryButton("ADD PERSON", { lookup() }, loading = looking, enabled = phone.count { it.isDigit() } >= 6, color = Dark, icon = MudaIcons.Add)
        }
        lookupMsg?.let { InfoBanner(it, Danger, MudaIcons.Info) }
    }

    // 3. summary + send
    if (people.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val shown = if (custom) customTotal else totalNum
            Text("${L("Total")}: ${money(symbol, shown.toDouble())}", color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (!custom && equal.first.isNotEmpty()) {
                Text("${L("Each person pays")}: ${money(symbol, equal.first.first().toDouble())}" + if (includeMe) " • ${L("Your share")}: ${money(symbol, equal.second.toDouble())}" else "", color = Muted, fontSize = 12.sp)
            }
            if (shareList.isNotEmpty() && !allShares) {
                InfoBanner("${L("Each share must be at least")} ${money(symbol, min.toDouble())}. ${L("Lower the number of people or raise the total.")}", Danger, MudaIcons.Info)
            }
            Text(L("Everyone gets a payment request for their share. You receive the money in your own currency as each person pays."), color = Muted, fontSize = 12.sp)
            PrimaryButton("SEND TO ${people.size} ${if (people.size == 1) "PERSON" else "PEOPLE"}", {
                busy = true
                scope.launch {
                    val members = JSONArray()
                    people.forEach { p ->
                        val o = JSONObject().put("phone", p.phone).put("country", p.country)
                        if (custom) o.put("amount", p.amount.toLongOrNull() ?: 0L)
                        members.put(o)
                    }
                    val body = JSONObject().put("title", title.trim()).put("note", note.trim()).put("split", if (custom) "custom" else "equal")
                        .put("include_me", includeMe).put("members", members)
                    if (custom) body.put("total", customTotal) else body.put("total", totalNum)
                    val r = Api.call("POST", "/groups", body, idem = idem)
                    busy = false
                    if (r.ok) {
                        idem = newIdemKey()
                        Ui.notify(L("Group request sent"), "${people.size} ${L("people will get a notification and can pay you their share with one tap.")}")
                        title = ""; total = ""; myShare = ""; note = ""; phone = ""; people.clear(); country = myCountry
                        onCreated()
                    } else {
                        if (r.http != 0) idem = newIdemKey()
                        Ui.notify(L("Group request"), r.message)
                    }
                }
            }, loading = busy, enabled = ready, icon = MudaIcons.NorthEast)
        }
    }
}
