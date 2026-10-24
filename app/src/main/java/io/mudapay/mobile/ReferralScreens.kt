package io.mudapay.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import org.json.JSONObject

class ReferralsViewModel : ViewModel() {
    sealed interface State {
        object Loading : State
        class Error(val msg: String) : State
        class Data(val d: JSONObject) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    init { load() }

    fun load() {
        state = State.Loading
        viewModelScope.launch {
            val r = Api.call("GET", "/referrals")
            state = if (r.ok) State.Data(r.data) else State.Error(r.message)
        }
    }
}

private fun qrBitmap(text: String, size: Int = 600): ImageBitmap? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val ink = 0xFF0F172A.toInt()
    val white = 0xFFFFFFFF.toInt()
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) ink else white }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
} catch (e: Exception) { null }

@Composable
fun ReferralsScreen(vm: ReferralsViewModel = viewModel()) {
    val user = Session.user ?: JSONObject()
    val symbol = user.str("symbol")
    var filter by remember { mutableStateOf("all") }
    val st = vm.state

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageIntro("My referrals", "Who joined, who activated and what each one earned you.", Icons.Default.Groups) }
        item { InviteCard(user.str("referral_link")) }
        when (st) {
            is ReferralsViewModel.State.Loading -> item { LoadingBox() }
            is ReferralsViewModel.State.Error -> item { ErrorBox(st.msg) { vm.load() } }
            is ReferralsViewModel.State.Data -> {
                val all = st.d.list("items")
                val sum = st.d.optJSONObject("summary")
                val total = if (sum != null && sum.has("total")) sum.int("total") else all.size
                val active = if (sum != null && sum.has("active")) sum.int("active") else all.count { isActive(it) }
                val earned = if (sum != null && sum.has("earned")) sum.dbl("earned") else all.sumOf { it.dbl("earned") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MiniStat("INVITED", total.toString(), Ink, Modifier.weight(1f))
                        MiniStat("ACTIVE", active.toString(), GreenDark, Modifier.weight(1f))
                        MiniStat("EARNED", money(st.d.str("symbol", symbol), earned), GreenDark, Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "All", "active" to "Active", "pending" to "Pending").forEach { (k, label) ->
                            FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.White))
                        }
                    }
                }
                val shown = all.filter { filter == "all" || (filter == "active") == isActive(it) }
                if (shown.isEmpty()) item { EmptyState("No referrals here yet", Icons.Default.Groups, "Share your invite link or QR code to get started.") }
                items(shown) { r -> ReferralRow(r, st.d.str("symbol", symbol)) }
            }
        }
    }
}

private fun isActive(r: JSONObject) = r.str("status") == "active" || r.bool("active")

@Composable
private fun MiniStat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.card(RoundedCornerShape(18.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Slate, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        Text(value, color = color, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun ReferralRow(r: JSONObject, symbol: String) {
    val active = isActive(r)
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Green.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
            Text(r.str("name", "?").trim().take(1).uppercase(), color = GreenDark, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.str("name", "Member"), color = Dark, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1)
            val sub = listOf("Joined " + r.str("joined_at").take(10), r.str("phone_masked")).filter { it.isNotBlank() && it != "Joined " }.joinToString(" • ")
            Text(sub, color = Muted, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Pill(if (active) "Active" else "Pending", if (active) GreenDark else Blue)
            if (active) Text(L("+") + money(symbol, r.dbl("earned")), color = GreenDark, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun InviteCard(link: String) {
    val ctx = LocalContext.current
    val qr = remember(link) { if (link.isBlank()) null else qrBitmap(link) }
    val message = "Join me on MudaPay and start earning: $link"
    Column(Modifier.fillMaxWidth().card().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(L("Your invite"), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        if (qr != null) {
            Box(Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(8.dp)) { Image(qr, "Invite QR code", Modifier.size(180.dp)) }
            Text(L("Friends can scan this to open your invite link."), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Text(link.ifBlank { "—" }, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
        PrimaryButton("SHARE ON WHATSAPP", { shareWhatsApp(ctx, message) }, color = Color(0xFF25D366), icon = Icons.Default.Chat, enabled = link.isNotBlank())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("COPY", { copyToClipboard(ctx, "Invite link", link); Ui.notify("Copied", "Your invite link was copied.") }, Modifier.weight(1f), color = Dark, icon = Icons.Default.ContentCopy, enabled = link.isNotBlank())
            PrimaryButton("SHARE", { shareText(ctx, message) }, Modifier.weight(1f), icon = Icons.Default.Share, enabled = link.isNotBlank())
        }
    }
}
