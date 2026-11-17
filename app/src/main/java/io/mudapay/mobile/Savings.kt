package io.mudapay.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/*
 * Savings pots: named pots ("School fees", "Phone") with an optional goal and an optional lock date.
 * Money in a pot leaves the available balance and comes back when the member takes it out; while a pot is locked it can
 * receive money but not give it back (the server enforces this, the app only explains it). API: lib_savings.php.
 */

private val POT_ICONS = linkedMapOf(
    "savings" to "🐷", "school" to "🎓", "phone" to "📱", "home" to "🏠", "car" to "🚗", "travel" to "✈️",
    "health" to "🩺", "gift" to "🎁", "business" to "🏪", "family" to "👨‍👩‍👧", "event" to "🎉", "other" to "⭐"
)

private fun potEmoji(key: String) = POT_ICONS[key] ?: "🐷"

/** Date [months] from today, "yyyy-MM-dd" (null = no lock). */
private fun lockDate(months: Int): String? {
    if (months <= 0) return null
    val c = Calendar.getInstance().apply { add(Calendar.MONTH, months) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
}

private fun monthsLabel(m: Int) = when (m) {
    0 -> L("No lock")
    1 -> "1 ${L("month")}"
    12 -> "1 ${L("year")}"
    else -> "$m ${L("months")}"
}

private val LOCK_CHOICES = listOf(0, 1, 3, 6, 12)

/* ------------------------------------------------------------------ list screen */
@Composable
fun SavingsScreen(nav: (String) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Remote(refreshKey = refresh, call = { Api.call("GET", "/savings") }) { d, _ ->
            val symbol = d.str("symbol")
            val pots = d.list("pots")
            val max = d.int("max_pots").takeIf { it > 0 } ?: 10
            Column(Modifier.fillMaxWidth().heroCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(softCase(L("SAVED IN YOUR POTS")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                Text(money(symbol, d.dbl("total_saved")), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("${L("Available balance")}: ${money(symbol, d.dbl("balance"))}", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
            }
            if (pots.isEmpty()) {
                EmptyState(L("No savings pots yet"), MudaIcons.Savings, L("Create a pot for school fees, a phone or anything you save for. You can lock it until a date you choose."))
            }
            pots.forEach { p -> PotCard(p, symbol) { open = p.int("id") } }
            PrimaryButton(softCase(L("NEW SAVINGS POT")), { showNew = true }, enabled = pots.size < max, icon = MudaIcons.Add)
            if (pots.size >= max) Text(L("You reached the maximum number of pots. Close one to create another."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            InfoBanner(L("Money in a pot is set aside: it leaves your available balance and is still yours. A locked pot cannot be emptied before its unlock date."), Blue, MudaIcons.Lock)

            if (showNew) NewPotDialog(symbol, onClose = { showNew = false }, onDone = { showNew = false; refresh++ })
            open?.let { id -> PotDialog(id, onClose = { open = null }, onChanged = { refresh++ }) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PotCard(p: JSONObject, symbol: String, onClick: () -> Unit) {
    val locked = p.bool("locked")
    val hasTarget = !p.isNull("target")
    Column(
        Modifier.fillMaxWidth().card().clip(CardShape).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(Soft, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Text(potEmoji(p.str("icon")), fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.str("name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (locked) { Icon(MudaIcons.Lock, null, tint = Muted, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(
                        if (locked) "${L("Locked until")} ${p.str("lock_until")}" else L("Not locked"),
                        color = Muted, fontSize = 12.sp, maxLines = 1
                    )
                }
            }
            Text(money(symbol, p.dbl("balance")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        if (hasTarget) ProgressLine(p, symbol)
    }
}

@Composable
private fun ProgressLine(p: JSONObject, symbol: String) {
    val frac = p.dbl("progress").toFloat().coerceIn(0f, 1f)
    val reached = p.bool("goal_reached")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Soft)) {
            if (frac > 0f) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (reached) GreenFill else Green, RoundedCornerShape(50)))
        }
        Text(
            if (reached) "${L("Goal reached")} · ${money(symbol, p.dbl("target"))}"
            else "${(frac * 100).toInt()}% ${L("of")} ${money(symbol, p.dbl("target"))}",
            color = if (reached) GreenDark else Muted, fontSize = 12.sp, fontWeight = if (reached) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/* ------------------------------------------------------------------ dialogs */
@Composable
private fun SheetDialog(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 680.dp).clip(RoundedCornerShape(28.dp)).background(Panel)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}

@Composable
private fun SheetTitle(text: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(36.dp).clip(CircleShape).background(Soft).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(MudaIcons.Close, L("Close"), tint = Ink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ErrorLine(text: String?) {
    if (!text.isNullOrBlank()) Row(Modifier.fillMaxWidth().background(DangerFill, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MudaIcons.Info, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NewPotDialog(symbol: String, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("savings") }
    var target by remember { mutableStateOf("") }
    var months by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val date = lockDate(months)

    SheetDialog(onClose) {
        SheetTitle(L("New savings pot"), onClose)
        InputField(L("Name (for example School fees)"), name, { name = it.take(40) })
        Text(L("Icon"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            POT_ICONS.forEach { (k, e) ->
                val on = k == icon
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Green.copy(alpha = .22f) else Soft).clickable { icon = k },
                    contentAlignment = Alignment.Center
                ) { Text(e, fontSize = 22.sp) }
            }
        }
        InputField("${L("Goal amount (optional)")} · $symbol", target, { target = it.filter { c -> c.isDigit() }.take(10) }, keyboard = KeyboardType.Number)
        Text(L("Lock this pot"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LOCK_CHOICES.forEach { m ->
                TabChip(monthsLabel(m), months == m, Modifier.widthIn(min = 84.dp)) { months = m }
            }
        }
        if (date != null) InfoBanner("${L("You will not be able to take money out before")} $date. ${L("You can still add money.")}", Gold, MudaIcons.Lock)
        ErrorLine(err)
        PrimaryButton(softCase(L("CREATE POT")), {
            busy = true; err = null
            scope.launch {
                val body = JSONObject().put("name", name.trim()).put("icon", icon)
                if (target.isNotEmpty()) body.put("target", target.toLong())
                if (date != null) body.put("lock_until", date)
                val r = Api.call("POST", "/savings", body)
                busy = false
                if (r.ok) { Ui.notify(L("Savings"), L("Your savings pot was created.")); onDone() } else err = r.message
            }
        }, enabled = name.trim().length >= 2, loading = busy, icon = MudaIcons.Check)
    }
}

@Composable
private fun PotDialog(id: Int, onClose: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var idem by remember { mutableStateOf(newIdemKey()) }
    var editing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf("") }
    var lockAsk by remember { mutableStateOf<Int?>(null) }
    var pinAsk by remember { mutableStateOf<Int?>(null) }   // months of a lock waiting for the transaction PIN
    var closeAsk by remember { mutableStateOf(false) }

    LaunchedEffect(id, tick) {
        val r = Api.call("GET", "/savings/$id")
        if (r.ok) { data = r.data; loadError = null } else if (data == null) loadError = r.message
    }

    SheetDialog(onClose) {
        val d = data
        val pot = d?.optJSONObject("pot")
        if (d == null || pot == null) {
            SheetTitle(L("Savings pot"), onClose)
            if (loadError != null) ErrorLine(loadError) else Text(L("Loading…"), color = Muted, fontSize = 13.sp)
            return@SheetDialog
        }
        val symbol = d.str("symbol")
        val locked = pot.bool("locked")
        val potBalance = pot.dbl("balance")
        val amt = amount.toLongOrNull() ?: 0L

        fun move(kind: String) {
            busy = true; err = null
            scope.launch {
                val r = Api.call("POST", "/savings/$id/$kind", JSONObject().put("amount", amt), idem = idem)
                busy = false
                if (r.ok) {
                    idem = newIdemKey()
                    Session.balance = r.data.dbl("balance")
                    amount = ""
                    tick++; onChanged()
                } else {
                    err = r.message
                    if (r.http != 0) idem = newIdemKey()
                }
            }
        }

        SheetTitle("${potEmoji(pot.str("icon"))}  ${pot.str("name")}", onClose)
        Text(money(symbol, potBalance), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 30.sp)
        if (!pot.isNull("target")) ProgressLine(pot, symbol)
        if (locked) InfoBanner("${L("Locked until")} ${pot.str("lock_until")} (${pot.int("days_left")} ${L("days")}). ${L("You can add money, but not take it out before then.")}", Gold, MudaIcons.Lock)

        InputField("${L("Amount")} · $symbol", amount, { amount = it.filter { c -> c.isDigit() }.take(10) }, keyboard = KeyboardType.Number)
        Text("${L("Available balance")}: ${money(symbol, d.dbl("balance"))}", color = Muted, fontSize = 12.sp)
        ErrorLine(err)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(softCase(L("ADD MONEY")), { move("deposit") }, Modifier.weight(1f), enabled = amt > 0 && !busy, loading = busy, icon = MudaIcons.Add)
            PrimaryButton(softCase(L("TAKE OUT")), { move("withdraw") }, Modifier.weight(1f), enabled = amt > 0 && !locked && !busy, color = Dark, icon = MudaIcons.SouthWest)
        }

        // lock: add one, or extend the current one (never shorter)
        Text(if (locked) L("Extend the lock") else L("Lock this pot"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LOCK_CHOICES.filter { it > 0 }.forEach { m ->
                val newDate = lockDate(m)
                val current = pot.str("lock_until")
                if (newDate != null && (!locked || newDate > current)) TabChip(monthsLabel(m), false, Modifier.widthIn(min = 84.dp)) { lockAsk = m }
            }
        }

        // history
        val moves = d.list("moves")
        if (moves.isNotEmpty()) {
            Text(L("History"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            moves.take(12).forEach { m ->
                val dep = m.str("kind") == "deposit"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (dep) L("Added") else L("Taken out"), color = Dark, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(m.str("created_at").take(16), color = Muted, fontSize = 11.sp)
                    }
                    Text((if (dep) "+" else "−") + money(symbol, m.dbl("amount")), color = if (dep) GreenDark else Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // edit + close
        if (editing) {
            InputField(L("Name"), editName, { editName = it.take(40) })
            InputField("${L("Goal amount (optional)")} · $symbol", editTarget, { editTarget = it.filter { c -> c.isDigit() }.take(10) }, keyboard = KeyboardType.Number)
            PrimaryButton(softCase(L("SAVE")), {
                busy = true; err = null
                scope.launch {
                    val r = Api.call("POST", "/savings/$id/update", JSONObject().put("name", editName.trim()).put("target", editTarget.toLongOrNull() ?: 0L))
                    busy = false
                    if (r.ok) { editing = false; tick++; onChanged() } else err = r.message
                }
            }, enabled = editName.trim().length >= 2 && !busy, loading = busy, icon = MudaIcons.Check)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                GhostButton(L("Edit"), {
                    editName = pot.str("name"); editTarget = if (pot.isNull("target")) "" else pot.dbl("target").toLong().toString(); editing = true
                }, Modifier.weight(1f), icon = MudaIcons.Edit)
                if (!locked) GhostButton(L("Close pot"), { closeAsk = true }, Modifier.weight(1f), icon = MudaIcons.DeleteOutline)
            }
        }

        lockAsk?.let { m ->
            val newDate = lockDate(m).orEmpty()
            AlertDialog(
                onDismissRequest = { lockAsk = null }, containerColor = Panel,
                title = { Text(L("Lock this pot?"), fontWeight = FontWeight.Bold) },
                text = { Text("${L("You will not be able to take money out before")} $newDate. ${L("This cannot be undone.")}", color = Ink, fontSize = 14.sp) },
                confirmButton = {
                    TextButton(onClick = { lockAsk = null; pinAsk = m }) { Text(L("Lock"), color = GreenDark, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { lockAsk = null }) { Text(L("Cancel"), color = Muted) } }
            )
        }
        // a lock cannot be undone by the member: it needs the transaction PIN, like sending money
        pinAsk?.let { m ->
            val newDate = lockDate(m).orEmpty()
            PinPromptDialog("Confirm lock", onDismiss = { pinAsk = null }) { pin ->
                pinAsk = null; busy = true; err = null
                scope.launch {
                    val r = Api.call("POST", "/savings/$id/lock", JSONObject().put("lock_until", newDate).put("pin", pin))
                    busy = false
                    if (r.ok) { tick++; onChanged() } else err = r.message
                }
            }
        }
        if (closeAsk) {
            AlertDialog(
                onDismissRequest = { closeAsk = false }, containerColor = Panel,
                title = { Text(L("Close this pot?"), fontWeight = FontWeight.Bold) },
                text = { Text(if (potBalance > 0) "${money(symbol, potBalance)} ${L("goes back to your available balance.")}" else L("This pot is empty."), color = Ink, fontSize = 14.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        closeAsk = false; busy = true; err = null
                        scope.launch {
                            val r = Api.call("POST", "/savings/$id/close")
                            busy = false
                            if (r.ok) { Session.balance = r.data.dbl("balance"); Ui.notify(L("Savings"), L("The pot was closed.")); onChanged(); onClose() } else err = r.message
                        }
                    }) { Text(L("Close pot"), color = Danger, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { closeAsk = false }) { Text(L("Cancel"), color = Muted) } }
            )
        }
    }
}
