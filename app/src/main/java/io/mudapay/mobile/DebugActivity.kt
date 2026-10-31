package io.mudapay.mobile

import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import org.json.JSONObject

/**
 * "MudaPay Debug" — the second icon that comes with the app (it opens this screen, in its own task).
 *  This phone : the last problems the app saw (failed calls with the server's real error, caught exceptions, crashes)
 *  Server     : the newest rows of the server's error log (needs db/api_debug.flag on the server + a signed-in member)
 * Turn the icon off for a public release: Config.DEBUG_TOOLS = false.
 */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AColor.TRANSPARENT
        window.navigationBarColor = AColor.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent { DebugScreen() }
    }
}

private val DbgBg = Color(0xFF0F172A)
private val DbgCard = Color(0xFF1E293B)
private val DbgText = Color(0xFFE2E8F0)
private val DbgMuted = Color(0xFF94A3B8)
private val DbgWarn = Color(0xFFFBBF24)
private val DbgBad = Color(0xFFF87171)
private val DbgGood = Color(0xFF34D399)

private fun serverText(items: List<JSONObject>): String = items.joinToString("\n\n") { o ->
    buildString {
        append("[${o.optString("last_seen")} UTC] ${o.optString("source")}/${o.optString("level")} x${o.optInt("occurrences", 1)}  member=${o.optString("user_id")}")
        if (o.optString("location").isNotEmpty() && o.optString("location") != "null") append("\n${o.optString("location")}")
        append("\n${o.optString("message")}")
        if (o.optString("context").isNotEmpty() && o.optString("context") != "null") append("\n${o.optString("context")}")
        if (o.optString("detail").isNotEmpty() && o.optString("detail") != "null") append("\n${o.optString("detail")}")
    }
}

@Composable
private fun DebugScreen() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var tab by remember { mutableStateOf(0) }
    var nonce by remember { mutableStateOf(0) }
    var mine by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(DebugLog.all()) }
    var server by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var serverMsg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(tab, nonce, mine) {
        if (tab == 0) { phone = DebugLog.all(); return@LaunchedEffect }
        serverMsg = ""
        if (!Session.signedIn) { server = emptyList(); serverMsg = "Sign in to MudaPay first, then come back here."; return@LaunchedEffect }
        loading = true
        val q = if (mine) mapOf("limit" to "40", "mine" to "1") else mapOf("limit" to "40")
        val r = Api.call("GET", "/debug/errors", query = q)
        loading = false
        if (r.ok) {
            server = r.data.optJSONArray("items").objects()
            if (server.isEmpty()) serverMsg = "No server errors recorded."
        } else {
            server = emptyList()
            serverMsg = "HTTP ${r.http} ${r.code}\n${r.message}"
        }
    }

    val text = if (tab == 0) DebugLog.asText(phone) else serverText(server)

    Column(Modifier.fillMaxSize().background(DbgBg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(10.dp))
        Text("MudaPay Debug", color = DbgText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" } }
        Text("v$ver · real errors, not for members", color = DbgMuted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DbgChip("This phone (${phone.size})", tab == 0) { tab = 0 }
            DbgChip("Server", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DbgChip("Refresh", false) { nonce++ }
            DbgChip("Copy", false) { clip.setText(AnnotatedString(text)) }
            DbgChip("Share", false) {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                ctx.startActivity(Intent.createChooser(i, "Share debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (tab == 0) DbgChip("Clear", false) { DebugLog.clear(); nonce++ }
            else DbgChip(if (mine) "Mine only" else "All members", mine) { mine = !mine }
        }
        Spacer(Modifier.height(10.dp))

        if (loading) Text("Loading…", color = DbgMuted, fontSize = 13.sp)
        if (serverMsg.isNotEmpty() && tab == 1) Text(serverMsg, color = DbgWarn, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        if (tab == 0 && phone.isEmpty()) Text("Nothing recorded yet. Reproduce the problem (for example tap Continue on a deposit), then come back and press Refresh.", color = DbgMuted, fontSize = 13.sp)

        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (tab == 0) {
                    items(phone) { o -> PhoneEntry(o) }
                } else {
                    items(server) { o -> ServerEntry(o) }
                }
            }
        }
    }
}

@Composable
private fun DbgChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Color.White else DbgText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Green else DbgCard).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun PhoneEntry(o: JSONObject) {
    val http = o.optInt("http")
    val head = when {
        o.optString("kind") == "crash" -> DbgBad
        http >= 500 || http == 0 -> DbgBad
        else -> DbgWarn
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DbgCard).padding(12.dp)) {
        Text("${DebugLog.time(o.optLong("t"))}  ${o.optString("kind").uppercase()}" + if (http > 0) "  HTTP $http" else "", color = head, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(o.optString("title"), color = DbgText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        if (o.optString("code").isNotEmpty()) Text("code: ${o.optString("code")}", color = DbgMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (o.optString("message").isNotEmpty()) Text(o.optString("message"), color = DbgText, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (o.optString("debug").isNotEmpty()) {
            Text("REAL ERROR (from the server)", color = DbgGood, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(o.optString("debug"), color = DbgText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        } else if (o.optString("kind") == "api" && http in 500..599) {
            Text("No provider detail: create an empty file htdocs/db/api_debug.flag on the server, then try again.", color = DbgMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (o.optString("kind") != "api" && o.optString("extra").isNotEmpty())
            Text(o.optString("extra").take(1200), color = DbgMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ServerEntry(o: JSONObject) {
    val src = o.optString("source")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DbgCard).padding(12.dp)) {
        Text("${o.optString("last_seen")} UTC  ${src.uppercase()}/${o.optString("level")}  ×${o.optInt("occurrences", 1)}  member ${o.optString("user_id")}", color = if (src == "app") DbgWarn else DbgBad, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        val loc = o.optString("location")
        if (loc.isNotEmpty() && loc != "null") Text(loc, color = DbgMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(o.optString("message"), color = DbgText, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        val ctxText = o.optString("context")
        if (ctxText.isNotEmpty() && ctxText != "null") Text(ctxText, color = DbgMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        val detail = o.optString("detail")
        if (detail.isNotEmpty() && detail != "null") Text(detail.take(900), color = DbgMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
    }
}
