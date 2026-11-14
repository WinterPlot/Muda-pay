package io.mudapay.mobile

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A tappable card: rounded ripple that stays inside the card's corners. Put it right after .card(shape). */
fun Modifier.tappable(shape: RoundedCornerShape = RoundedCornerShape(18.dp), onClick: () -> Unit): Modifier =
    this.clip(shape).clickable(onClick = onClick)

/** Everything one "details" popup shows. Texts are English keys: they go through L() when drawn (values are shown as they are). */
class DetailData(
    val title: String,
    val icon: ImageVector,
    val tone: Color,
    /** Big line under the title: usually the amount. */
    val headline: String? = null,
    val status: String? = null,
    val statusTone: Color = tone,
    /** Small grey line under the title (a date, a phone number…). */
    val subtitle: String? = null,
    val rows: List<Pair<String, String>> = emptyList(),
    /** A longer text (a message, a note), selectable. */
    val bodyTitle: String? = null,
    val body: String? = null,
    val hint: String? = null,
    /** When set, the round picture at the top is this member's photo (or initials) instead of the icon. */
    val avatarName: String? = null,
    val avatarPhone: String = "",
    val avatarUrl: String? = null
)

/**
 * The "open this card" popup used across the app: icon, title, amount, status, every detail as a clean row,
 * and optional buttons ([actions]) such as "Open" or "Share receipt".
 */
@Composable
fun DetailDialog(d: DetailData, actions: @Composable () -> Unit = {}, onDismiss: () -> Unit) {
    var shown by remember(d) { mutableStateOf(false) }
    LaunchedEffect(d) { shown = true }
    val pop by animateFloatAsState(if (shown) 1f else 0.4f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "detailPop")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(26.dp)).background(Panel).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (d.avatarName != null) {
                        Box(Modifier.graphicsLayer { scaleX = pop; scaleY = pop }) { MemberAvatar(d.avatarName, d.avatarPhone, 76.dp, d.tone, d.avatarUrl) }
                    } else {
                        Box(Modifier.size(68.dp).graphicsLayer { scaleX = pop; scaleY = pop }.background(d.tone.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(d.icon, null, tint = d.tone, modifier = Modifier.size(34.dp))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(d.title, color = Dark, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center, lineHeight = 26.sp)
                    d.headline?.let { Text(it, color = d.tone, fontWeight = FontWeight.Bold, fontSize = 30.sp, textAlign = TextAlign.Center) }
                    d.status?.let { Pill(L(it), d.statusTone) }
                    d.subtitle?.let { Text(it, color = Slate, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center) }
                }
                if (!d.body.isNullOrBlank()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Soft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        d.bodyTitle?.let { Text(softCase(L(it)).uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        SelectionContainer { Text(d.body, color = Ink, fontSize = 15.sp, lineHeight = 22.sp) }
                    }
                }
                val rows = d.rows.filter { it.second.isNotBlank() }
                if (rows.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        rows.forEachIndexed { i, (k, v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                Text(L(k), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
                                Text(v, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.58f))
                            }
                            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                        }
                    }
                }
                d.hint?.let { Text(L(it), color = Muted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
            actions()
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(L("Close"), color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
}
