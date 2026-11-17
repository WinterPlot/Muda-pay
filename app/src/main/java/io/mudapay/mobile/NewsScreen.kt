package io.mudapay.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Updates & tutorials": news written by MudaPay in the admin (text + pictures).
 * The list shows every post; tapping one (or tapping the push notification) opens the full post.
 */
private fun kindLabel(k: String) = when (k) {
    "tutorial" -> "Tutorial"
    "announcement" -> "Announcement"
    else -> "Update"
}

private fun newsDate(ts: Long): String =
    if (ts <= 0L) "" else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts * 1000L))

private fun JSONObject.imageUrls(): List<Pair<String, String>> =
    list("images").map { it.str("url") to it.str("caption") }.filter { it.first.isNotBlank() }

@Composable
fun NewsScreen() {
    var open by remember { mutableStateOf<JSONObject?>(null) }
    val p = open
    if (p != null) BackHandler { open = null }
    Remote(call = { Api.call("GET", "/news") }) { d, _ ->
        val items = d.list("items")
        // a notification tap asks for one post
        LaunchedEffect(Ui.pendingNewsId, items.size) {
            val want = Ui.pendingNewsId
            if (want > 0) {
                items.firstOrNull { it.int("id") == want }?.let { open = it }
                Ui.pendingNewsId = 0
            }
        }
        val cur = p?.let { sel -> items.firstOrNull { it.int("id") == sel.int("id") } ?: sel }
        if (cur != null) NewsDetail(cur) else NewsList(items) { open = it }
    }
}

@Composable
private fun NewsList(items: List<JSONObject>, onOpen: (JSONObject) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (items.isEmpty()) EmptyState(L("No updates yet"), MudaIcons.Newspaper, L("News and tutorials from MudaPay appear here."))
        items.forEach { n ->
            val imgs = n.imageUrls()
            Column(Modifier.fillMaxWidth().card().clickable { onOpen(n) }.padding(12.dp)) {
                if (imgs.isNotEmpty()) {
                    RemoteImage(imgs[0].first, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)), ContentScale.Crop)
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(L(kindLabel(n.str("kind"))), GreenDark)
                    Text(newsDate(n.optLong("ts")), color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(n.str("title"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sum = n.str("summary")
                if (sum.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(sum, color = Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun NewsDetail(n: JSONObject) {
    val imgs = n.imageUrls()
    val placed = remember(n) { HashSet<Int>() }
    placed.clear()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(L(kindLabel(n.str("kind"))), GreenDark)
            Text(newsDate(n.optLong("ts")), color = Muted, fontSize = 12.sp)
        }
        Text(n.str("title"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
        val sum = n.str("summary")
        if (sum.isNotBlank()) Text(sum, color = Muted, fontSize = 15.sp, lineHeight = 21.sp)

        val body = n.str("body")
        val hasMarkers = Regex("""\[image:\d+]""").containsMatchIn(body)
        // no [image:N] in the text: the first picture goes on top, the others under the text
        if (!hasMarkers && imgs.isNotEmpty()) { NewsPicture(imgs[0]); placed.add(1) }

        body.split("\n").forEach { raw ->
            val line = raw.trim()
            val m = Regex("""^\[image:(\d+)]$""").find(line)
            when {
                line.isEmpty() -> {}
                m != null -> {
                    val i = m.groupValues[1].toInt()
                    if (i in 1..imgs.size) { NewsPicture(imgs[i - 1]); placed.add(i) }
                }
                line.startsWith("#") -> Text(line.trimStart('#').trim(), color = Dark, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
                line.startsWith("- ") || line.startsWith("• ") -> Row(Modifier.padding(start = 4.dp)) {
                    Text("•", color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(line.substring(2).trim(), color = Dark, fontSize = 15.sp, lineHeight = 22.sp)
                }
                else -> Text(line, color = Dark, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
        imgs.forEachIndexed { idx, img -> if ((idx + 1) !in placed) NewsPicture(img) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NewsPicture(img: Pair<String, String>) {
    Column(Modifier.fillMaxWidth()) {
        RemoteImage(img.first, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 460.dp).clip(RoundedCornerShape(16.dp)).background(Line), ContentScale.FillWidth, essential = true)
        if (img.second.isNotBlank()) Text(img.second, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, start = 4.dp))
    }
}
