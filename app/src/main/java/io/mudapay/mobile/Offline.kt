package io.mudapay.mobile

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object Offline {
    var networkUp by mutableStateOf(true)
        private set
    private var callFailed by mutableStateOf(false)
    var lowData by mutableStateOf(false)
        private set
    val offline: Boolean get() = !networkUp || callFailed

    private var prefs: SharedPreferences? = null
    private val live = HashSet<Network>()

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences("mudapay_offline", Context.MODE_PRIVATE)
        lowData = prefs?.getBoolean("low_data", false) ?: false
        OfflineCache.init(app)
        PendingQueue.init(app)
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkUp = try {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true } ?: false
        } catch (e: Exception) { true }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(live) { live.add(network) }
                        callFailed = false
                        networkUp = true
                    }
                    override fun onLost(network: Network) {
                        synchronized(live) { live.remove(network); networkUp = live.isNotEmpty() }
                    }
                }
            )
        } catch (e: Exception) { }
    }

    fun changeLowData(v: Boolean) {
        lowData = v
        prefs?.edit()?.putBoolean("low_data", v)?.apply()
    }

    fun reportReachable(ok: Boolean) { if (callFailed == ok) callFailed = !ok }
    fun clearFailure() { callFailed = false }
}

object OfflineCache {
    private var dir: File? = null
    private val paths = setOf(
        "/me", "/dashboard", "/tasks", "/videos", "/transactions", "/withdrawals", "/notifications",
        "/referrals", "/leaderboard", "/campaigns", "/settings"
    )

    fun init(ctx: Context) { dir = File(ctx.filesDir, "offline").apply { mkdirs() } }

    fun keyFor(path: String, query: Map<String, String>): String? {
        if (path !in paths || query.containsKey("cursor")) return null
        return path + query.toSortedMap().entries.joinToString("&", "?") { it.key + "=" + it.value }
    }

    private fun file(key: String): File? {
        val d = dir ?: return null
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, "c_" + h.take(32))
    }

    suspend fun put(key: String, data: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val f = file(key) ?: return@withContext
            val tmp = File(f.path + ".tmp")
            tmp.writeText(SecureStore.encrypt(JSONObject().put("t", System.currentTimeMillis()).put("d", data).toString()))
            tmp.renameTo(f)
        } catch (e: Exception) { }
    }

    suspend fun get(key: String): Pair<JSONObject, Long>? = withContext(Dispatchers.IO) {
        try {
            val f = file(key)
            if (f == null || !f.exists()) return@withContext null
            val plain = SecureStore.decrypt(f.readText()) ?: return@withContext null
            val j = JSONObject(plain)
            Pair(j.getJSONObject("d"), j.optLong("t"))
        } catch (e: Exception) { null }
    }

    fun clear() {
        try { dir?.listFiles()?.filter { it.name.startsWith("c_") }?.forEach { it.delete() } } catch (e: Exception) { }
    }
}

class PendingItem(
    val kind: String,
    val id: Int,
    val path: String,
    val body: JSONObject?,
    val pre: String?,
    val preBody: JSONObject?,
    val waitMs: Long,
    val at: Long
) {
    fun toJson(): JSONObject = JSONObject().put("k", kind).put("i", id).put("p", path).put("b", body ?: JSONObject.NULL)
        .put("pre", pre ?: JSONObject.NULL).put("pb", preBody ?: JSONObject.NULL).put("w", waitMs).put("t", at)

    companion object {
        fun from(j: JSONObject) = PendingItem(
            j.optString("k"), j.optInt("i"), j.optString("p"), j.optJSONObject("b"),
            if (j.isNull("pre")) null else j.optString("pre"), j.optJSONObject("pb"), j.optLong("w"), j.optLong("t")
        )
    }
}

object PendingQueue {
    private var file: File? = null
    private val items = mutableListOf<PendingItem>()
    private val keys = mutableStateListOf<String>()
    private val flushLock = Mutex()
    private const val MAX_AGE_MS = 72L * 3600L * 1000L

    val size: Int get() = keys.size
    fun has(kind: String, id: Int): Boolean = keys.contains("$kind:$id")

    fun init(ctx: Context) {
        file = File(File(ctx.filesDir, "offline").apply { mkdirs() }, "queue.bin")
        try {
            val f = file
            if (f != null && f.exists()) {
                val arr = JSONArray(SecureStore.decrypt(f.readText()) ?: "[]")
                synchronized(items) {
                    items.clear(); keys.clear()
                    arr.objects().map { PendingItem.from(it) }.forEach { items.add(it); keys.add("${it.kind}:${it.id}") }
                }
            }
        } catch (e: Exception) { }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            synchronized(items) { items.forEach { arr.put(it.toJson()) } }
            file?.writeText(SecureStore.encrypt(arr.toString()))
        } catch (e: Exception) { }
    }

    fun add(item: PendingItem) {
        if (has(item.kind, item.id)) return
        synchronized(items) { items.add(item); keys.add("${item.kind}:${item.id}") }
        persist()
    }

    fun addTask(id: Int, needsStart: Boolean) = add(
        PendingItem("task", id, "/tasks/$id/complete", null,
            if (needsStart) "/tasks/$id/start" else null, null, if (needsStart) 3500L else 0L, System.currentTimeMillis())
    )

    fun addVideo(kind: String, id: Int, needsStart: Boolean) = add(
        PendingItem(kind, id, "/videos/$id/events", JSONObject().put("event", kind),
            if (needsStart) "/videos/$id/events" else null, JSONObject().put("event", "start"), if (needsStart) 10500L else 0L, System.currentTimeMillis())
    )

    private fun remove(item: PendingItem) {
        synchronized(items) { items.remove(item); keys.remove("${item.kind}:${item.id}") }
        persist()
    }

    fun clear() {
        synchronized(items) { items.clear(); keys.clear() }
        try { file?.delete() } catch (e: Exception) { }
    }

    suspend fun flush() {
        if (!flushLock.tryLock()) return
        try {
            val snapshot = synchronized(items) { items.toList() }
            var sent = 0
            var reward = 0.0
            for (item in snapshot) {
                if (System.currentTimeMillis() - item.at > MAX_AGE_MS) { remove(item); continue }
                if (item.pre != null) {
                    val pr = Api.call("POST", item.pre, item.preBody)
                    if (pr.code == "network") break
                    if (item.waitMs > 0) delay(item.waitMs)
                }
                val r = Api.call("POST", item.path, item.body)
                if (r.code == "network") break
                remove(item)
                if (r.ok) {
                    sent++
                    reward += r.data.dbl("reward")
                    if (r.data.has("balance")) Session.balance = r.data.dbl("balance")
                } else if (r.code !in setOf("already_completed", "already_rewarded", "task_unavailable")) {
                    Ui.notify("Sync", r.message)
                }
            }
            if (sent > 0) {
                val symbol = Session.user?.str("symbol") ?: ""
                Notify.backOnline(sent, reward, Session.balance)
                Ui.notify("Back online", if (reward > 0) "$sent saved action(s) sent: +${money(symbol, reward)} added to your balance." else "$sent saved action(s) sent.")
            }
        } finally {
            flushLock.unlock()
        }
    }
}

fun agoText(ts: Long): String {
    val m = ((System.currentTimeMillis() - ts) / 60000L).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 1440 -> "${m / 60} h ago"
        else -> "${m / 1440} d ago"
    }
}

@Composable
fun StaleNote(ts: Long) {
    InfoBanner("You're offline. Showing the copy saved ${agoText(ts)}.", Blue, Icons.Default.CloudOff)
}

@Composable
fun OfflineState(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).background(Page, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.WifiOff, null, tint = Slate, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(L("You're offline"), color = Dark, fontWeight = FontWeight.Black, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "This screen needs an internet connection. Turn on Wi-Fi or mobile data and it will reload by itself.",
            color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(L("Home, Tasks and History still open from saved data."), color = Slate, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("TRY AGAIN", { Offline.clearFailure(); onRetry() }, Modifier.width(200.dp), icon = Icons.Default.Refresh)
    }
}

@Composable
fun NeedsInternet(content: @Composable () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    key(tick) {
        if (Offline.offline) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OfflineState { tick++ } }
        } else content()
    }
}

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val off = Offline.offline
    val n = PendingQueue.size
    if (off || n > 0) {
        val text = when {
            off && n > 0 -> "No connection • $n saved action(s) will be sent later"
            off -> "No connection • showing saved data"
            else -> "Sending $n saved action(s)…"
        }
        Row(
            modifier.fillMaxWidth().background(if (off) Color(0xFF334155) else Blue).padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (off) Icons.Default.CloudOff else Icons.Default.Sync, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
