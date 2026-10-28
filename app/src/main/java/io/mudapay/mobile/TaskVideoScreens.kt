package io.mudapay.mobile

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
@Composable
fun VideosScreen() {
    var playing by remember { mutableStateOf<JSONObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(JSONObject()) }
    val p = playing
    if (p != null) {
        VideoPlayer(p, info, onClose = { playing = null; reload++ })
    } else {
        Column(Modifier.fillMaxSize()) {
            TabHeader(L("Videos"), L("Watch and like videos to earn"))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Remote(refreshKey = reload, call = { Api.call("GET", "/videos") }) { d, _ ->
                    val symbol = d.str("symbol")
                    val rw = d.optJSONObject("rewards") ?: JSONObject()
                    InfoBanner("Watch a video for ${money(symbol, rw.dbl("watch"))} and like it for ${money(symbol, rw.dbl("like"))}. Each video pays once.")
                    val items = d.list("items")
                    if (items.isEmpty()) EmptyState("No videos yet", Icons.Default.PlayCircle, "New videos appear here.")
                    items.forEach { v ->
                        Column(Modifier.fillMaxWidth().card().clickable { info = d; playing = v }.padding(12.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Dark, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                RemoteImage("https://img.youtube.com/vi/${v.str("youtube_id")}/hqdefault.jpg", Modifier.matchParentSize().background(Dark, RoundedCornerShape(16.dp)), ContentScale.Crop, tapToLoad = false)
                                Box(Modifier.size(54.dp).background(Color.Black.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(v.str("title"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill(if (v.bool("watched")) "Watched ✓" else "Watch +" + money(symbol, rw.dbl("watch")), if (v.bool("watched")) Slate else GreenDark)
                                Pill(if (v.bool("liked")) "Liked ✓" else "Like +" + money(symbol, rw.dbl("like")), if (v.bool("liked")) Slate else Color(0xFFDB2777))
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoPlayer(v: JSONObject, info: JSONObject, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = v.int("id")
    val symbol = info.str("symbol")
    val rw = info.optJSONObject("rewards") ?: JSONObject()
    val need = 10
    var watched by remember { mutableStateOf(v.bool("watched")) }
    var liked by remember { mutableStateOf(v.bool("liked")) }
    var started by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var likeBusy by remember { mutableStateOf(false) }
    var offlineStart by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    DisposableEffect(Unit) { Ui.immersive = true; onDispose { Ui.immersive = false } }

    LaunchedEffect(id) {
        if (watched) started = true
        else {
            val sr = Api.call("POST", "/videos/$id/events", JSONObject().put("event", "start"))
            started = sr.ok || sr.code == "network"
            offlineStart = sr.code == "network"
        }
    }
    LaunchedEffect(started, watched) {
        if (started && !watched) {
            while (seconds < need) { delay(1000); seconds++ }
            if (offlineStart) {
                val sr = Api.call("POST", "/videos/$id/events", JSONObject().put("event", "start"))
                if (sr.ok) { offlineStart = false; delay(10500) }
            }
            val r = Api.call("POST", "/videos/$id/events", JSONObject().put("event", "watched"))
            if (r.ok) {
                watched = true
                Session.balance = r.data.dbl("balance")
                Notify.videoWatched(r.data.dbl("reward"), Session.balance)
                Ui.notify("Reward earned 🎉", "+${money(symbol, r.data.dbl("reward"))} for watching this video.")
            } else if (r.code == "already_rewarded") watched = true
            else if (r.code == "network") {
                PendingQueue.addVideo("watched", id, offlineStart)
                watched = true
                Ui.notify("Saved offline", "Your watch reward will be added when you're back online.")
            }
            else Ui.notify("Video", r.message)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            Text(v.str("title"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webChromeClient = WebChromeClient()
                    // a video that cannot load is reported with the screen it happened on (offline phones are not a bug, so network codes are skipped)
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            val c = error.errorCode
                            if (c == WebViewClient.ERROR_HOST_LOOKUP || c == WebViewClient.ERROR_CONNECT || c == WebViewClient.ERROR_IO || c == WebViewClient.ERROR_TIMEOUT) return
                            val host = try { request.url.host ?: "" } catch (_: Throwable) { "" }
                            ErrorReporter.report("WebView video", "Video page failed to load (code $c ${error.description}) host=$host main=${request.isForMainFrame}")
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            if (errorResponse.statusCode < 500 && !request.isForMainFrame) return
                            val host = try { request.url.host ?: "" } catch (_: Throwable) { "" }
                            ErrorReporter.report("WebView video", "Video page answered HTTP ${errorResponse.statusCode} host=$host main=${request.isForMainFrame}")
                        }
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)
                    val html = "<html><body style='margin:0;background:#000'><iframe width='100%' height='100%' " +
                        "src='https://www.youtube.com/embed/${v.str("youtube_id")}?playsinline=1&rel=0&modestbranding=1' frameborder='0' " +
                        "allow='accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture' allowfullscreen></iframe></body></html>"
                    loadDataWithBaseURL(Config.SITE, html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (watched) {
                Text(L("✓ Watch reward collected"), color = Green, fontWeight = FontWeight.Bold)
            } else {
                Text(L("Keep watching to earn ${money(symbol, rw.dbl("watch"))}"), color = Color.White, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { seconds.toFloat() / need }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Green, trackColor = Color.White.copy(alpha = .15f))
                Text(L("${minOf(seconds, need)} / $need s"), color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
            }
            PrimaryButton(
                if (liked) "LIKED ✓" else "LIKE  •  +" + money(symbol, rw.dbl("like")),
                {
                    likeBusy = true
                    scope.launch {
                        val r = Api.call("POST", "/videos/$id/events", JSONObject().put("event", "like"))
                        likeBusy = false
                        if (r.ok) { liked = true; Session.balance = r.data.dbl("balance"); Notify.videoLiked(r.data.dbl("reward"), Session.balance); Ui.notify("Reward earned 🎉", "+${money(symbol, r.data.dbl("reward"))} for liking this video.") }
                        else if (r.code == "already_rewarded") liked = true
                        else if (r.code == "network") {
                            PendingQueue.addVideo("like", id, false)
                            liked = true
                            Ui.notify("Saved offline", "Your like reward will be added when you're back online.")
                        }
                        else Ui.notify("Video", r.message)
                    }
                },
                enabled = !liked, loading = likeBusy, color = Color(0xFFDB2777), icon = Icons.Default.Favorite
            )
        }
    }
}
