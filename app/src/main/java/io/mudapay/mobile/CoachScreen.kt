package io.mudapay.mobile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private class Msg(val role: String, val text: String, val chips: List<String> = emptyList())

private class Starter(val icon: ImageVector, val title: String, val prompt: String)

private val STARTERS = listOf(
    Starter(Icons.Default.Bolt, "Earn more", "How can I earn more on MudaPay every day?"),
    Starter(Icons.Default.AccountBalanceWallet, "Withdrawals", "How do withdrawals work and how long do they take?"),
    Starter(Icons.Default.Groups, "Invite friends", "What is the best way to invite friends and get referral rewards?"),
    Starter(Icons.Default.School, "Free lesson", "Teach me a short, practical lesson about saving money.")
)

private fun JSONArray?.chipTexts(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>()
    for (i in 0 until length()) {
        val o = opt(i)
        val s = when (o) { is String -> o; is JSONObject -> o.optString("text", o.optString("label", "")); else -> "" }
        if (s.isNotBlank()) out.add(s)
    }
    return out
}

@Composable
fun CoachScreen(nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<Msg>() }
    var convId by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(-1) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var tip by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var disabled by remember { mutableStateOf<String?>(null) }
    var voiceOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val first = (Session.user?.str("full_name") ?: "").trim().substringBefore(" ")

    LaunchedEffect(Unit) {
        val r = Api.call("GET", "/coach/context")
        if (r.ok) {
            tip = r.data.str("tip_of_day"); remaining = r.data.int("remaining_today")
            suggestions = r.data.optJSONArray("suggestions").chipTexts()
        } else disabled = r.message
    }
    LaunchedEffect(msgs.size, sending) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1 + (if (sending) 1 else 0))
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || sending) return
        msgs.add(Msg("user", t)); input = ""; sending = true
        scope.launch {
            val body = JSONObject().put("message", t)
            if (convId != 0) body.put("conversation_id", convId)
            val r = Api.call("POST", "/coach/messages", body, timeoutMs = 140000)
            sending = false
            if (r.ok) {
                convId = r.data.optJSONObject("conversation")?.int("id") ?: convId
                val reply = r.data.optJSONObject("reply")
                msgs.add(Msg("assistant", reply?.str("content") ?: "", reply?.optJSONArray("chips").chipTexts()))
                remaining = r.data.int("remaining_today")
            } else {
                msgs.removeAt(msgs.lastIndex); input = t
                Ui.notify("Muda AI", r.message)
            }
        }
    }

    /** Same round trip as [send], used by the voice screen: the text came from speech recognition, the reply is read aloud. */
    suspend fun askVoice(text: String): VoiceAnswer {
        val t = text.trim()
        if (t.isEmpty()) return VoiceAnswer(false, L("I didn't hear anything. Tap the mic and try again."))
        msgs.add(Msg("user", t)); sending = true
        try {
            val body = JSONObject().put("message", t).put("voice", true).put("lang", AppLanguage.current)
            if (convId != 0) body.put("conversation_id", convId)
            val r = Api.call("POST", "/coach/messages", body, timeoutMs = 140000)
            if (r.ok) {
                convId = r.data.optJSONObject("conversation")?.int("id") ?: convId
                val reply = r.data.optJSONObject("reply")
                val content = reply?.str("content") ?: ""
                msgs.add(Msg("assistant", content, reply?.optJSONArray("chips").chipTexts()))
                remaining = r.data.int("remaining_today")
                return VoiceAnswer(true, content)
            }
            msgs.removeAt(msgs.lastIndex)
            return VoiceAnswer(false, r.message)
        } finally { sending = false }
    }

    val off = disabled
    Column(Modifier.fillMaxSize().imePadding()) {
        /* ---- pinned header */
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(24.dp)).background(Gradient)
                .drawBehind {
                    drawCircle(Color.White.copy(alpha = .10f), radius = size.height * 1.1f, center = Offset(size.width * .94f, size.height * .1f))
                    drawCircle(Color.White.copy(alpha = .07f), radius = size.height * .6f, center = Offset(size.width * .30f, size.height * 1.2f))
                }
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).background(Color.White.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Muda AI"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 19.sp, letterSpacing = (-.2).sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (off == null) Color(0xFF86EFAC) else Color(0xFFFCA5A5), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (off != null) L("Unavailable") else if (remaining >= 0) L("Online") + " · " + L("$remaining messages left today") else L("Online"),
                        color = Color.White.copy(alpha = .9f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Default.History, L("History"), tint = Color.White) }
            IconButton(onClick = { msgs.clear(); convId = 0 }) { Icon(Icons.Default.AddComment, L("New chat"), tint = Color.White) }
        }

        /* ---- conversation */
        if (off != null) { Box(Modifier.weight(1f).padding(18.dp)) { InfoBanner(off, Danger) } }
        else LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (msgs.isEmpty()) {
                item { Welcome(first, tip) }
                items(STARTERS.chunked(2)) { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { s -> StarterCard(s, Modifier.weight(1f)) { send(s.prompt) } }
                    }
                }
                item { CaptionShortcut { nav("ShareApp") } }
                if (suggestions.isNotEmpty()) {
                    item { Text(L("Or try one of these"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
                    items(suggestions) { s -> ChipLine(s) { send(s) } }
                }
            }
            items(msgs) { m ->
                if (m.role == "user") UserBubble(m.text)
                else AssistantBubble(m, isLast = m === msgs.last(), onChip = { send(it) }, onCopy = {
                    copyToClipboard(ctx, "Muda AI", markdownPlain(m.text)); Toast.makeText(ctx, L("Copied"), Toast.LENGTH_SHORT).show()
                }, onShare = { shareText(ctx, markdownPlain(m.text)) })
            }
            if (sending) item {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar()
                    Box(Modifier.card(AiBubbleShape).padding(horizontal = 16.dp, vertical = 14.dp)) { TypingDots() }
                }
            }
        }

        /* ---- input */
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text(L("Ask a question")) },
                    shape = RoundedCornerShape(24.dp), maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, cursorColor = Green, unfocusedBorderColor = Line, focusedContainerColor = Page, unfocusedContainerColor = Page)
                )
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank() && !sending && off == null
                if (input.isBlank() && off == null) {
                    // Empty box: the round button becomes the microphone (voice chat); as soon as you type it turns into Send.
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).background(if (sending) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Line, Line)) else Gradient)
                            .clickable(enabled = !sending) { voiceOpen = true },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Mic, L("Voice chat"), tint = Color.White) }
                } else {
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).background(if (canSend) Gradient else androidx.compose.ui.graphics.Brush.linearGradient(listOf(Line, Line)))
                            .clickable(enabled = canSend) { send(input) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.AutoMirrored.Filled.Send, L("Send"), tint = Color.White) }
                }
            }
            Text(L("Muda AI can make mistakes. Check important details before you act."), color = Slate, fontSize = 10.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }

    if (voiceOpen) VoiceChatDialog(
        onClose = { voiceOpen = false },
        // async in the screen's scope: closing the voice screen never throws away an answer the server is still writing
        ask = { t -> scope.async { askVoice(t) }.await() }
    )

    if (showHistory) HistoryDialog(
        onClose = { showHistory = false },
        onOpen = { id ->
            showHistory = false
            scope.launch {
                val r = Api.call("GET", "/coach/conversations/$id")
                if (r.ok) {
                    msgs.clear(); convId = id
                    r.data.list("messages").forEach { m -> msgs.add(Msg(m.str("role"), m.str("content"), m.optJSONArray("chips").chipTexts())) }
                } else Ui.notify("Muda AI", r.message)
            }
        }
    )
}

@Composable
private fun Avatar() {
    Box(Modifier.size(30.dp).background(Gradient, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Welcome(first: String, tip: String) {
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(26.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AiOrb(size = 54.dp)
        Text(
            if (first.isNotEmpty()) L("Hi") + " $first!" else L("Hi!"),
            color = Ink, fontWeight = FontWeight.Black, fontSize = 22.sp
        )
        Text(
            L("Hello! Ask me about MudaPay, earning, saving or any lesson."),
            color = Muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
        )
        if (tip.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().background(Gold.copy(alpha = .16f), RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Lightbulb, null, tint = Color(0xFFB45309), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(L("Tip of the day"), color = Color(0xFFB45309), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    Text(tip, color = Ink, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun StarterCard(s: Starter, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.card(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).background(Green.copy(alpha = .12f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(s.icon, null, tint = GreenDark, modifier = Modifier.size(22.dp))
        }
        Text(L(s.title), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        Text(L(s.prompt), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
    }
}

@Composable
private fun CaptionShortcut(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dark).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).background(Gradient, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(L("Write my share caption"), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Text(L("Muda AI describes MudaPay for your friends, then you send the app."), color = Color(0xFFCBD5E1), fontSize = 11.sp, lineHeight = 15.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Gold)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.widthIn(max = 300.dp).background(Gradient, MeBubbleShape).padding(horizontal = 15.dp, vertical = 11.dp)) {
            Text(text, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun AssistantBubble(m: Msg, isLast: Boolean, onChip: (String) -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Avatar()
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.widthIn(max = 310.dp).card(AiBubbleShape).padding(horizontal = 15.dp, vertical = 12.dp)) {
                Text(markdownLite(m.text), color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, L("Copy"), tint = Slate, modifier = Modifier.size(17.dp)) }
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Share, L("Share"), tint = Slate, modifier = Modifier.size(17.dp)) }
            }
            if (isLast) m.chips.forEach { c -> ChipLine(c) { onChip(c) } }
        }
    }
}

@Composable
private fun ChipLine(text: String, onClick: () -> Unit) {
    Text(text, color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.background(Green.copy(alpha = .1f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun HistoryDialog(onClose: () -> Unit, onOpen: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, containerColor = Color.White,
        confirmButton = { TextButton(onClick = onClose) { Text(L("Close"), color = GreenDark) } },
        title = { Text(L("Conversations"), fontWeight = FontWeight.Black) },
        text = {
            Remote(call = { Api.call("GET", "/coach/conversations") }) { d, _ ->
                val items = d.list("items")
                if (items.isEmpty()) Text(L("No saved conversations yet."), color = Muted)
                else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(items) { c ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(c.int("id")) }.padding(vertical = 10.dp)) {
                            Text(c.str("title").ifEmpty { "Conversation" }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text(c.str("preview"), color = Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        HorizontalDivider(color = Line)
                    }
                }
            }
        }
    )
}
