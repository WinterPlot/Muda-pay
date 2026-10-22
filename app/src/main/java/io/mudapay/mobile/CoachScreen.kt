package io.mudapay.mobile

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private class Msg(val role: String, val text: String, val chips: List<String> = emptyList())

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
fun CoachScreen() {
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
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val r = Api.call("GET", "/coach/context")
        if (r.ok) {
            tip = r.data.str("tip_of_day"); remaining = r.data.int("remaining_today")
            suggestions = r.data.optJSONArray("suggestions").chipTexts()
        } else disabled = r.message
    }
    LaunchedEffect(msgs.size, sending) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size) }

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

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Muda AI", color = Dark, fontWeight = FontWeight.Black, fontSize = 18.sp)
                if (remaining >= 0) Text("$remaining messages left today", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Default.History, "History", tint = Ink) }
            IconButton(onClick = { msgs.clear(); convId = 0 }) { Icon(Icons.Default.AddComment, "New chat", tint = Ink) }
        }
        if (disabled != null) { Box(Modifier.weight(1f).padding(18.dp)) { InfoBanner(disabled ?: "", Danger) } }
        else LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (msgs.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).background(Gradient, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White) }
                            Spacer(Modifier.width(10.dp))
                            Text("Hello! Ask me about MudaPay, earning, saving or any lesson.", color = Ink, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                        }
                        if (tip.isNotEmpty()) { Spacer(Modifier.height(10.dp)); InfoBanner("Tip of the day: $tip", GreenDark, Icons.Default.Lightbulb) }
                    }
                }
                items(suggestions) { s -> ChipLine(s) { send(s) } }
            }
            items(msgs) { m ->
                val mine = m.role == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.widthIn(max = 300.dp)
                            .then(if (mine) Modifier.background(Gradient, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)) else Modifier.card(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)))
                            .padding(13.dp)
                    ) { Text(m.text, color = if (mine) Color.White else Ink, fontSize = 14.sp, lineHeight = 20.sp) }
                    if (!mine && m === msgs.last()) m.chips.forEach { c -> ChipLine(c) { send(c) } }
                }
            }
            if (sending) item { Text("Muda AI is thinking…", color = Muted, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask a question") }, shape = RoundedCornerShape(22.dp), maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, cursorColor = Green, unfocusedBorderColor = Line)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !sending && disabled == null,
                modifier = Modifier.size(48.dp).background(if (input.isNotBlank() && !sending) Green else Line, CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
            }
        }
    }

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
private fun ChipLine(text: String, onClick: () -> Unit) {
    Text(text, color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.background(Green.copy(alpha = .1f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun HistoryDialog(onClose: () -> Unit, onOpen: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, containerColor = Color.White,
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = GreenDark) } },
        title = { Text("Conversations", fontWeight = FontWeight.Black) },
        text = {
            Remote(call = { Api.call("GET", "/coach/conversations") }) { d, _ ->
                val items = d.list("items")
                if (items.isEmpty()) Text("No saved conversations yet.", color = Muted)
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
