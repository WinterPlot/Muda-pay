package io.mudapay.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

private val CLOSED = setOf("failed", "expired", "refunded")

private fun clock(sec: Int): String = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

@Composable
private fun CopyRow(label: String, value: String, mono: Boolean = true, big: Boolean = false) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                value, color = Dark, fontWeight = FontWeight.Bold, fontSize = if (big) 18.sp else 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
            IconButton(onClick = { copyToClipboard(ctx, label, value); Ui.notify(L("Copied"), L("Copied to clipboard.")) }) {
                Icon(MudaIcons.ContentCopy, L("Copy"), tint = GreenDark)
            }
        }
    }
}

@Composable
private fun Steps(index: Int) {
    val names = listOf(L("Waiting for payment"), L("Confirming on the network"), L("Completed"))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        names.forEachIndexed { i, n ->
            val on = i <= index
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(26.dp).background(if (on) Green else Soft, CircleShape), contentAlignment = Alignment.Center) {
                    if (i < index) Icon(MudaIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", color = if (on) Color.White else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(n, color = if (on) Dark else Muted, fontWeight = if (i == index) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun CryptoPayScreen(nav: (String) -> Unit) {
    val first = Ui.selectedCryptoDeposit
    if (first == null) {
        LaunchedEffect(Unit) { nav("Deposit") }
        return
    }
    val id = first.int("id")
    val symbol = Session.user?.str("symbol") ?: ""
    var dep by remember(id) { mutableStateOf<JSONObject>(first) }
    var left by remember(id) { mutableIntStateOf(if (first.isNull("expires_in")) -1 else first.optInt("expires_in", -1)) }

    LaunchedEffect(id) {
        while (true) {
            delay(6000)
            val r = Api.call("GET", "/deposits/$id")
            if (!r.ok) continue
            dep = r.data
            if (!r.data.isNull("expires_in")) left = r.data.optInt("expires_in", -1)
            if (r.data.bool("credited")) {
                Session.balance = r.data.dbl("balance")
                Ui.refreshTick++
                TxSound.playFor(r.data)
                Ui.notify(L("Deposit received"), "${money(symbol, r.data.dbl("credited_amount"))} ${L("was added to your balance.")}")
                break
            }
            if (r.data.str("status") in CLOSED) break
        }
    }
    LaunchedEffect(id) {
        while (true) { delay(1000); if (left > 0) left-- }
    }

    val done = dep.bool("credited")
    val status = dep.str("status")
        val address = dep.str("pay_address")
    val memo = dep.str("pay_extra_id")
    val coin = dep.str("pay_label")
    val network = dep.str("pay_network")
    val amountText = dep.str("pay_amount")
    val qrBmp = remember(address) { if (address.isEmpty()) null else DotQr.render(address, 900, logo = false) }
    val qr = remember(qrBmp) { qrBmp?.asImageBitmap() }
    val step = when {
        done -> 2
        status in setOf("confirming", "confirmed", "sending") -> 1
        else -> 0
    }
    val closed = status in CLOSED || (left == 0 && step == 0 && !done)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            done -> {
                Column(Modifier.fillMaxWidth().card().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(72.dp).background(Green.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(MudaIcons.CheckCircle, null, tint = Green, modifier = Modifier.size(44.dp))
                    }
                    Text(L("Deposit completed"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(money(symbol, dep.dbl("credited_amount")), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                    Text(L("was added to your balance."), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text("${L("Reference")}: ${dep.str("reference")}", color = Muted, fontSize = 12.sp)
                }
                ReceiptActions(ReceiptBuilder.deposit(dep, symbol))
                PrimaryButton("DONE", { Ui.selectedCryptoDeposit = null; nav("Home") }, icon = MudaIcons.Check)
            }
            closed -> {
                InfoBanner(
                    if (status == "expired" || left == 0) L("This payment expired. Nothing was charged. Start a new deposit if you still want to add money.") else dep.str("status_text"),
                    Danger, MudaIcons.Info
                )
                PrimaryButton("NEW DEPOSIT", { Ui.selectedCryptoDeposit = null; nav("Deposit") }, icon = MudaIcons.Add)
            }
            else -> {
                Column(Modifier.fillMaxWidth().heroCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(softCase(L("SEND EXACTLY")), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                    Text("$amountText $coin", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("${L("You receive")} ${money(symbol, dep.dbl("amount_local"))}", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
                    if (left > 0) Text("${L("Expires in")} ${clock(left)}", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                InfoBanner(
                    "${L("Send only")} $coin ${L("on the network")} $network. ${L("Coins sent on another network are lost.")}",
                    Gold, MudaIcons.Warning
                )
                Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (qr != null) Image(qr, L("Wallet address QR code"), Modifier.size(210.dp))
                    CopyRow(L("Wallet address"), address)
                    CopyRow(L("Amount to send"), amountText, big = true)
                    if (memo.isNotEmpty()) {
                        CopyRow(L("Memo / Tag (required)"), memo)
                        InfoBanner(L("Enter this memo / tag when you send, or the payment will not be matched."), Danger, MudaIcons.Info)
                    }
                }
                Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(L("Payment status"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Steps(step)
                    if (status == "partially_paid") InfoBanner(L("Only part of the amount was received. Our team will review it. Keep your reference."), Gold, MudaIcons.Info)
                    if (status == "review") InfoBanner(L("Your payment is under review by our team."), Blue, MudaIcons.Info)
                    Text(L("This page updates by itself. You can leave it: your balance is credited automatically once the payment is confirmed."), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                    Text("${L("Reference")}: ${dep.str("reference")}", color = Muted, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
