package io.mudapay.mobile

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/* ------------------------------------------------------------------ daily tasks */
@Composable
fun TasksScreen() {
    var open by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val symbol = Session.user?.str("symbol") ?: ""
    val t = open
    if (t != null) {
        BackHandler { open = null }
        TaskDetail(t, symbol, onDone = { open = null; reload++ }, onClose = { open = null })
    } else {
        Column(Modifier.fillMaxSize()) {
            TabHeader(softCase(L("DAILY TASKS")), L("Complete your tasks to earn every day"))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BalanceHero(softCase(L("AVAILABLE BALANCE")), money(symbol, Session.balance))
                Remote(refreshKey = reload, call = { Api.call("GET", "/tasks") }) { d, _ ->
                    val items = d.list("items")
                    val done = items.count { it.bool("completed") }
                    SectionTitle("Today's tasks", "$done of ${items.size} completed")
                    if (items.isEmpty()) EmptyState("No tasks right now", Icons.Default.TaskAlt, "New tasks appear every day.")
                    items.forEach { task ->
                        val queued = PendingQueue.has("task", task.int("id"))
                        val completed = task.bool("completed") || queued
                        Row(
                            Modifier.fillMaxWidth().card().clickable(enabled = !completed) { open = task }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(46.dp).background(if (completed) Green.copy(alpha = .15f) else Page, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                                Icon(if (completed) Icons.Default.CheckCircle else Icons.Default.TaskAlt, null, tint = if (completed) GreenDark else Green, modifier = Modifier.size(26.dp))
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(task.str("title"), color = if (completed) Muted else Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(task.str("question"), color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Pill(if (queued) "Will sync" else if (completed) "Done" else "+" + money(symbol, task.dbl("reward")), if (completed) Slate else GreenDark)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TaskDetail(task: JSONObject, symbol: String, onDone: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = task.int("id")
    val options = task.strings("options")
    var ready by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    var offlineStart by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }

    // Opening the task tells the server when you started; it only accepts the answer a few seconds later.
    LaunchedEffect(id) {
        val r = Api.call("POST", "/tasks/$id/start")
        if (r.ok) { delay(3300); ready = true }
        else if (r.code == "network") { offlineStart = true; delay(3300); ready = true }
        else startError = r.message
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Task", onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(task.str("title"), color = Dark, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.fillMaxWidth().card().padding(18.dp)) {
                Text(task.str("question"), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
            }
            options.forEachIndexed { i, text ->
                val on = selected == i
                Row(
                    Modifier.fillMaxWidth().background(if (on) Green.copy(alpha = .08f) else Color.White, RoundedCornerShape(18.dp))
                        .border(2.dp, if (on) Green else Line, RoundedCornerShape(18.dp)).clickable { selected = i }.padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(30.dp).background(if (on) Green else Page, CircleShape), contentAlignment = Alignment.Center) {
                        Text(('A' + i).toString(), color = if (on) Color.White else Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(text, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
            if (offlineStart) InfoBanner("You're offline. Your answer will be saved and sent when the connection returns.", Blue, Icons.Default.CloudOff)
            startError?.let { InfoBanner(it, Danger) }
            if (startError == null && !ready) InfoBanner("Take a moment to read the task…")
        }
        Column(Modifier.background(Color.White).navigationBarsPadding().padding(16.dp)) {
            PrimaryButton(
                "SUBMIT ANSWER  •  +" + money(symbol, task.dbl("reward")),
                {
                    busy = true
                    scope.launch {
                        val r = Api.call("POST", "/tasks/$id/complete")
                        busy = false
                        if (r.ok) {
                            Session.balance = r.data.dbl("balance")
                            Notify.taskCompleted(r.data.dbl("reward"), Session.balance)
                            Ui.notify("Task completed 🎉", "+${money(symbol, r.data.dbl("reward"))} added to your balance.")
                            onDone()
                        } else if (r.code == "network") {
                            PendingQueue.addTask(id, offlineStart)
                            Ui.notify("Saved offline", "Your answer is saved and will be sent automatically when you're back online.")
                            onDone()
                        } else {
                            Ui.notify("Task", r.message)
                            if (r.code == "already_completed" || r.code == "task_unavailable") onDone()
                        }
                    }
                },
                enabled = ready && selected >= 0, loading = busy
            )
        }
    }
}

/* ------------------------------------------------------------------ videos */
private fun categoryLabel(c: String) = when (c) {
    "tutorial" -> "Tutorial"
    "announcement" -> "Announcement"
    else -> "Update"
}

@Composable
fun VideosScreen() {
    var playing by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val p = playing
    if (p != null) {
        VideoPlayer(p, onClose = { playing = null; reload++ })
    } else {
        Column(Modifier.fillMaxSize()) {
            TabHeader(L("Videos"), L("Updates, tutorials and announcements"))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Remote(refreshKey = reload, call = { Api.call("GET", "/videos") }) { d, _ ->
                    val items = d.list("items")
                    if (items.isEmpty()) EmptyState("No videos yet", Icons.Default.PlayCircle, "Official updates and tutorials appear here.")
                    items.forEach { v ->
                        Column(Modifier.fillMaxWidth().card().clickable { playing = v }.padding(12.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Dark, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(54.dp).background(Color.Black.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                                Box(Modifier.align(Alignment.TopStart).padding(10.dp)) { Pill(L(categoryLabel(v.str("category"))), GreenDark) }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(v.str("title"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val desc = v.str("description")
                            if (desc.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(desc, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                val r = v.str("reaction")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ThumbUp, null, tint = if (r == "like") Green else Slate, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${v.int("likes")}", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ThumbDown, null, tint = if (r == "dislike") Danger else Slate, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${v.int("dislikes")}", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(v: JSONObject, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = v.int("id")
    var reaction by remember { mutableStateOf(v.str("reaction", "none")) }
    var likes by remember { mutableIntStateOf(v.int("likes")) }
    var dislikes by remember { mutableIntStateOf(v.int("dislikes")) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    DisposableEffect(Unit) { Ui.immersive = true; onDispose { Ui.immersive = false } }

    fun react(kind: String) {
        if (busy) return
        val next = if (reaction == kind) "none" else kind
        busy = true
        scope.launch {
            val r = Api.call("POST", "/videos/$id/reaction", JSONObject().put("reaction", next))
            busy = false
            if (r.ok) {
                reaction = r.data.str("reaction", next)
                likes = r.data.int("likes")
                dislikes = r.data.int("dislikes")
            } else Ui.notify("Video", r.message)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            Text(v.str("title"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val mc = MediaController(ctx)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setOnPreparedListener { loading = false; start() }
                        setOnErrorListener { _, _, _ -> loading = false; failed = true; true }
                        setVideoURI(Uri.parse(v.str("video_url")))
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxSize()
            )
            if (loading && !failed) CircularProgressIndicator(color = Color.White)
            if (failed) Text(L("This video could not be played. Check your connection and try again."), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(L(categoryLabel(v.str("category"))), GreenDark)
            Text(v.str("title"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp)
            val desc = v.str("description")
            if (desc.isNotBlank()) Text(desc, color = Color.White.copy(alpha = .8f), fontSize = 14.sp, lineHeight = 21.sp)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val likeOn = reaction == "like"
            val dislikeOn = reaction == "dislike"
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (likeOn) Green else Color.White.copy(alpha = .12f)).clickable(enabled = !busy) { react("like") }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ThumbUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("$likes", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (dislikeOn) Danger else Color.White.copy(alpha = .12f)).clickable(enabled = !busy) { react("dislike") }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ThumbDown, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("$dislikes", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
