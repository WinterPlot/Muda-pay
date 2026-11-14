package io.mudapay.mobile

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Extras of the Crypto Wallet screens (WalletScreens.kt): address book, QR scan of an address, transaction status screen,
 * USD formatting. Nothing here touches keys or the recovery phrase. The address book holds PUBLIC addresses and names only,
 * saved on this phone (per MudaPay account) and never sent anywhere.
 */

/* ------------------------------------------------------------------ helpers */

/** "$1,234.56", "<$0.01" (display only). */
fun walletUsd(v: BigDecimal): String {
    if (v.signum() == 0) return "\$0.00"
    if (v < BigDecimal("0.01")) return "<\$0.01"
    val f = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
    f.minimumFractionDigits = 2
    f.maximumFractionDigits = 2
    return "\$" + f.format(v)
}

/** USD text for [amount] at [price], or null when there is no price (the screens then simply show no USD value). */
fun walletUsdOf(amount: BigDecimal?, price: Double?): String? =
    if (amount == null || price == null || price <= 0.0) null else walletUsd(amount.multiply(BigDecimal.valueOf(price)))

fun walletShort(a: String) = if (a.length > 18) a.take(8) + "…" + a.takeLast(6) else a

fun walletPaste(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(ctx)?.toString()?.trim() ?: ""
}

private val ADDR_RX = Regex("(?<![0-9a-fA-F])0x[0-9a-fA-F]{40}(?![0-9a-fA-F])")
private val ADDR_PARAM_RX = Regex("address=(0x[0-9a-fA-F]{40})(?![0-9a-fA-F])")
private val TX_HASH_RX = Regex("^(0x)?[0-9a-fA-F]{64}$")

/** A BNB Chain address out of a scanned QR code: a plain address, "ethereum:0x...", "bnb:0x..." or a payment link ("...?address=0x..."). */
fun addressFromQr(text: String): String? {
    val t = text.trim()
    ADDR_PARAM_RX.find(t)?.let { return it.groupValues[1] }
    return ADDR_RX.find(t)?.value
}

/** Small rounded action chip ("Paste", "Scan QR", "Address book"). */
@Composable
fun WalletChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Soft).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = GreenDark, modifier = Modifier.size(16.dp))
        Text(label, color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ address book storage */
class WalletContact(val label: String, val address: String)

object WalletPrefs {
    private const val PREFS = "mudapay_wallet_extras"
    private const val MAX_CONTACTS = 100

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun bookKey() = "book_" + (Session.user?.str("id") ?: "")

    fun hideBalance(ctx: Context): Boolean = p(ctx).getBoolean("hide_balance", false)
    fun setHideBalance(ctx: Context, hidden: Boolean) { p(ctx).edit().putBoolean("hide_balance", hidden).apply() }

    fun contacts(ctx: Context): List<WalletContact> {
        return try {
            val arr = JSONArray(p(ctx).getString(bookKey(), "[]") ?: "[]")
            (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val a = o.optString("a", "")
                if (a.isEmpty()) null else WalletContact(o.optString("l", ""), a)
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun write(ctx: Context, list: List<WalletContact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("l", it.label).put("a", it.address)) }
        p(ctx).edit().putString(bookKey(), arr.toString()).apply()
    }

    /** Saves (or renames) a recipient. Returns null on success or a message for the member. */
    fun saveContact(ctx: Context, label: String, address: String): String? {
        val name = label.trim()
        val a = address.trim()
        if (name.isEmpty()) return L("Enter a name for this recipient.")
        if (!WalletCrypto.isEvmAddress(a)) return L("This is not a valid BNB Chain address.")
        val list = contacts(ctx).toMutableList()
        val at = list.indexOfFirst { it.address.equals(a, ignoreCase = true) }
        if (at >= 0) list[at] = WalletContact(name.take(30), list[at].address)
        else {
            if (list.size >= MAX_CONTACTS) return L("The address book is full. Delete a recipient first.")
            list.add(0, WalletContact(name.take(30), a))
        }
        write(ctx, list)
        return null
    }

    fun deleteContact(ctx: Context, address: String) {
        write(ctx, contacts(ctx).filter { !it.address.equals(address, ignoreCase = true) })
    }
}

/* ------------------------------------------------------------------ scan an address (camera or picture) */
@Composable
fun WalletQrScanDialog(onAddress: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun cameraAllowed() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraAllowed()) }
    var cameraError by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }

    fun handle(raw: String) {
        if (finished) return
        val a = addressFromQr(raw)
        if (a == null) { message = L("This QR code does not contain a BNB Chain address."); return }
        finished = true
        onAddress(a)
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.Default) { try { QrDecoder.decodeUri(ctx, uri) } catch (e: Throwable) { null } }
            if (text == null) message = L("No QR code was found in this picture.") else handle(text)
        }
    }

    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(message) { if (message != null) { delay(3200); message = null } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted && !cameraError) {
                CameraScanner(Modifier.fillMaxSize(), false, { }, { cameraError = true }, ::handle)
                ScanOverlay(Modifier.fillMaxSize())
                Text(
                    L("Point the camera at the QR code of the address"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp, start = 28.dp, end = 28.dp)
                )
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp).fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(MudaIcons.QrCodeScanner, null, tint = GreenDark, modifier = Modifier.size(40.dp))
                    Text(if (cameraError) L("The camera could not start") else L("Camera needed"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        if (cameraError) L("You can still choose a picture of the QR code from your gallery.") else L("Allow the camera to scan the QR code of an address. Nothing is recorded or sent."),
                        color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp
                    )
                    if (!cameraError) PrimaryButton(L("ALLOW CAMERA"), { askCamera.launch(Manifest.permission.CAMERA) }, icon = MudaIcons.CameraAlt)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                message?.let { m ->
                    Row(Modifier.fillMaxWidth().background(DangerFill, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(MudaIcons.Info, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(m, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                PrimaryButton(L("CHOOSE FROM GALLERY"), { pick.launch("image/*") }, color = Dark, icon = MudaIcons.Image)
                PrimaryButton(L("CLOSE"), onClose, color = Soft, contentColor = Dark, icon = MudaIcons.Close)
            }
        }
    }
}

/* ------------------------------------------------------------------ address book UI */
@Composable
fun SaveContactDialog(address: String, onSave: (String) -> String?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(L("Save this recipient"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(walletShort(address), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                InputField(L("Name"), name, { name = it.take(30); err = null })
                err?.let { Text(it, color = Danger, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { err = onSave(name) }) { Text(L("Save"), color = GreenDark, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel"), color = Muted) } }
    )
}

/**
 * The address book: the list (with delete) and a small form to add a recipient (type, paste or scan).
 * [onPick] != null = "choose a recipient" mode (inside the Send screen); null = manage mode (its own page).
 * It does not scroll by itself: put it in a scrolling parent.
 */
@Composable
fun AddressBookPanel(onPick: ((WalletContact) -> Unit)?, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(WalletPrefs.contacts(ctx)) }
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var showScan by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf<WalletContact?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(L("Address book"), if (onPick != null) L("Tap a recipient to use the address.") else L("Saved BNB Chain addresses you send to often."))
        if (list.isEmpty()) {
            Text(L("No saved recipients yet."), color = Muted, fontSize = 13.sp)
        } else {
            Column(Modifier.fillMaxWidth().card().padding(vertical = 4.dp)) {
                list.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = onPick != null) { onPick?.invoke(c) }.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(38.dp).background(Green.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(MudaIcons.Person, null, tint = GreenDark, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(c.label, color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(walletShort(c.address), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        androidx.compose.material3.IconButton(onClick = { askDelete = c }) { Icon(MudaIcons.DeleteOutline, L("Delete"), tint = Danger) }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L("Add a recipient"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            InputField(L("Name"), label, { label = it.take(30); err = null })
            InputField(L("BNB Chain address"), address, { address = it.trim(); err = null })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WalletChip(MudaIcons.ContentCopy, L("Paste")) { address = walletPaste(ctx); err = null }
                WalletChip(MudaIcons.QrCodeScanner, L("Scan QR")) { showScan = true }
            }
            err?.let { InfoBanner(it, Danger, MudaIcons.Info) }
            PrimaryButton(L("SAVE RECIPIENT"), {
                val e = WalletPrefs.saveContact(ctx, label, address)
                if (e != null) err = e
                else { list = WalletPrefs.contacts(ctx); label = ""; address = ""; err = null; onChanged() }
            }, enabled = label.isNotBlank() && address.isNotBlank(), icon = MudaIcons.Add)
        }
    }

    if (showScan) WalletQrScanDialog(onAddress = { address = it; err = null; showScan = false }, onClose = { showScan = false })

    askDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            containerColor = Panel,
            title = { Text(L("Delete this recipient?"), fontWeight = FontWeight.Bold) },
            text = { Text("${c.label}\n${walletShort(c.address)}", color = Ink, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    WalletPrefs.deleteContact(ctx, c.address)
                    list = WalletPrefs.contacts(ctx)
                    askDelete = null
                    onChanged()
                }) { Text(L("Delete"), color = Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text(L("Cancel"), color = Muted) } }
        )
    }
}

/* ------------------------------------------------------------------ transaction status */
@Composable
private fun TxCopyRow(label: String, value: String) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Dark, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            androidx.compose.material3.IconButton(onClick = { copyToClipboard(ctx, label, value); Ui.notify(L("Copied"), L("Copied to clipboard.")) }) {
                Icon(MudaIcons.ContentCopy, L("Copy"), tint = GreenDark)
            }
        }
    }
}

/**
 * Status of one wallet transaction: Pending (checked every 5 seconds), Confirmed or Failed, with the transaction id and a
 * "View on BscScan" button. Used after a send, from the history list, and for payments approved in an external wallet.
 * The status comes from the server (GET /wallet/tx), which reads it from the blockchain.
 */
@Composable
fun WalletTxStatus(
    chain: String, txid: String, symbol: String, amount: String, counterparty: String,
    initialStatus: String = "pending", outgoing: Boolean = true, onDone: () -> Unit
) {
    val ctx = LocalContext.current
    var status by remember(txid) { mutableStateOf(initialStatus) }
    var confirmations by remember(txid) { mutableIntStateOf(0) }
    var round by remember(txid) { mutableIntStateOf(0) }
    var polling by remember(txid) { mutableStateOf(false) }

    LaunchedEffect(txid, round) {
        var n = 0
        polling = true
        while (status == "pending" && n < 60) {   // about 5 minutes, then the member can refresh by hand
            val r = Api.call("GET", "/wallet/tx", query = mapOf("chain" to chain, "txid" to txid))
            if (r.ok) {
                status = r.data.optString("status", status)
                confirmations = r.data.optInt("confirmations", confirmations)
            }
            n++
            if (status == "pending") delay(5000)
        }
        polling = false
    }

    val tone = when (status) { "confirmed" -> Green; "failed" -> Danger; else -> Gold }
    val title = when (status) { "confirmed" -> L("Confirmed"); "failed" -> L("Failed"); else -> L("Pending") }
    val note = when (status) {
        "confirmed" -> if (confirmations > 0) "${L("Confirmed on the network")} · $confirmations ${L("confirmations")}" else L("Confirmed on the network")
        "failed" -> L("The network did not accept this transaction. Check the details on the explorer.")
        else -> L("Waiting for the network. It usually confirms within a minute.")
    }
    val explorer = Pinned.explorers[chain] ?: ""
    val network = Pinned.chainNames[chain] ?: chain

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().card().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(72.dp).background(tone.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                when (status) {
                    "confirmed" -> Icon(MudaIcons.CheckCircle, null, tint = tone, modifier = Modifier.size(44.dp))
                    "failed" -> Icon(MudaIcons.Warning, null, tint = tone, modifier = Modifier.size(40.dp))
                    else -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(36.dp), color = tone, strokeWidth = 3.dp)
                }
            }
            Pill(title, tone)
            Text("$amount $symbol", color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 26.sp, textAlign = TextAlign.Center)
            Text(note, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
        }
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(L("Network"), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(network, color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            if (counterparty.isNotEmpty()) TxCopyRow(if (outgoing) L("To") else L("From"), counterparty)
            TxCopyRow(L("Transaction ID"), txid)
        }
        if (status == "pending" && !polling) PrimaryButton(L("REFRESH STATUS"), { round++ }, color = Dark, icon = MudaIcons.Refresh)
        if (explorer.isNotEmpty() && TX_HASH_RX.matches(txid)) {
            PrimaryButton(if (chain == "bsc") L("VIEW ON BSCSCAN") else L("VIEW ON EXPLORER"), { openUrl(ctx, explorer + txid) }, color = Dark, icon = MudaIcons.Link)
        }
        GhostButton(L("Report a problem"), {
            Support.report("wallet", txid, L("Crypto wallet"), "$amount $symbol · $network · " + txid.take(14) + "…")
            onDone()
        }, modifier = Modifier.fillMaxWidth(), icon = MudaIcons.SupportAgent)
        PrimaryButton(L("DONE"), onDone, icon = MudaIcons.Check)
        Spacer(Modifier.height(8.dp))
    }
}
