package io.mudapay.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Agent locator (server: GET /agents/nearby, POST /agent/location).
 *  - AgentLocatorScreen       : map + list of the agents and bureaus members can cash out through; each one has CALL and DIRECTIONS
 *                               (and CHOOSE when opened from the Withdraw form, see AgentPickerBlock in AgentScreens.kt).
 *  - LocatorSettingsCard      : the agent's own switch "show me on the agent map" + position (Agent desk).
 *  - rememberLocationRequester / currentLocation / openDirections : small helpers shared with the agent screens.
 * Map: OpenStreetMap tiles through osmdroid (no API key). For heavy production traffic point TILE_SOURCE at a paid tile provider.
 * Messages in this file use Toast, not Ui.notify: the screen is shown in its own dialog window, which would hide the global dialog.
 */

/* ---------------------------------------------------------------- location helpers */

fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** One position fix: a recent last-known position if there is one, else a fresh fix (12 s max). null = no permission, location off, or no signal. */
@SuppressLint("MissingPermission")
suspend fun currentLocation(ctx: Context): Location? = withContext(Dispatchers.Main) {
    if (!hasLocationPermission(ctx)) return@withContext null
    val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
    val providers = try { lm.getProviders(true) } catch (e: Exception) { emptyList<String>() }
    val usable = providers.filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
    if (usable.isEmpty()) return@withContext null

    var best: Location? = null
    for (p in usable) {
        val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
        if (l != null && System.currentTimeMillis() - l.time < 10 * 60 * 1000L && (best == null || l.accuracy < best.accuracy)) best = l
    }
    if (best != null) return@withContext best

    val provider = if (usable.contains(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
    var listener: LocationListener? = null
    try {
        withTimeoutOrNull(12000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val l = object : LocationListener {
                    override fun onLocationChanged(location: Location) { if (cont.isActive) cont.resume(location) }
                    @Deprecated("Needed below Android 11")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                listener = l
                try { lm.requestLocationUpdates(provider, 0L, 0f, l, Looper.getMainLooper()) } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
            }
        }
    } finally {
        listener?.let { try { lm.removeUpdates(it) } catch (e: Exception) { } }
    }
}

/** Returns a function that asks for the location permission if needed, then reports one fix (null if it could not be had). */
@Composable
fun rememberLocationRequester(onResult: (Location?) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) scope.launch { latest(currentLocation(ctx)) } else latest(null)
    }
    return {
        if (hasLocationPermission(ctx)) scope.launch { latest(currentLocation(ctx)) }
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

private fun toast(ctx: Context, text: String) { try { Toast.makeText(ctx, L(text), Toast.LENGTH_SHORT).show() } catch (e: Exception) { } }

private fun locatorDial(ctx: Context, phone: String) {
    try {
        val n = phone.filter { it.isDigit() || it == '+' }
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { toast(ctx, "No phone app is available.") }
}

/**
 * DIRECTIONS: turn-by-turn in Google Maps if it is installed, else any maps app, else the browser.
 * Without coordinates (an agent who is not on the map) it searches the written address instead.
 */
fun openDirections(ctx: Context, lat: Double?, lng: Double?, label: String, addressQuery: String = "") {
    fun go(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    val ok = if (lat != null && lng != null) {
        go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng")).setPackage("com.google.android.apps.maps")) ||
            go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"))) ||
            go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")))
    } else if (addressQuery.isNotBlank()) {
        go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(addressQuery)}"))) ||
            go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(addressQuery)}")))
    } else false
    if (!ok) toast(ctx, "No maps app is available.")
}

private fun distanceKm(me: Location, a: JSONObject): Double {
    val r = FloatArray(1)
    Location.distanceBetween(me.latitude, me.longitude, a.dbl("lat"), a.dbl("lng"), r)
    return r[0] / 1000.0
}

private fun kmText(km: Double): String =
    if (km < 1.0) "${max(50, (km * 20).roundToInt() * 50)} m" else String.format(Locale.US, "%.1f km", km)

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/* ---------------------------------------------------------------- map */

private object LocatorMapConfig {
    private var done = false
    fun init(ctx: Context) {
        if (done) return
        done = true
        val c = Configuration.getInstance()
        c.userAgentValue = ctx.packageName              // OpenStreetMap's tile servers require an identifying user agent
        val base = File(ctx.cacheDir, "osmdroid").also { it.mkdirs() }
        c.osmdroidBasePath = base                       // inside the app cache: no storage permission needed
        c.osmdroidTileCache = File(base, "tiles").also { it.mkdirs() }
        c.tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
        c.tileFileSystemCacheTrimBytes = 40L * 1024 * 1024
    }
}

private fun pinDrawable(ctx: Context, color: Int, bureau: Boolean, selected: Boolean): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val size = ((if (selected) 46 else 34) * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = 0x33000000; c.drawCircle(r, r + d, r - 2 * d, p)                       // soft shadow
    p.color = AColor.WHITE; c.drawCircle(r, r, r - 2 * d, p)                         // white ring
    p.color = if (selected) 0xFF0F172A.toInt() else color; c.drawCircle(r, r, r - 5 * d, p)
    p.color = AColor.WHITE
    if (bureau) c.drawRoundRect(RectF(r - 5 * d, r - 5 * d, r + 5 * d, r + 5 * d), 2 * d, 2 * d, p) else c.drawCircle(r, r, 4 * d, p)
    return BitmapDrawable(ctx.resources, bmp)
}

private fun mePinDrawable(ctx: Context): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val size = (26 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = 0x330061FF; c.drawCircle(r, r, r, p)
    p.color = AColor.WHITE; c.drawCircle(r, r, r - 5 * d, p)
    p.color = 0xFF0061FF.toInt(); c.drawCircle(r, r, r - 7 * d, p)
    return BitmapDrawable(ctx.resources, bmp)
}

@Composable
private fun AgentMapView(items: List<JSONObject>, me: GeoPoint?, selected: String, onPick: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        LocatorMapConfig.init(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(4.0)
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(2.0, 20.0))    // central Africa until the first fit
        }
    }
    DisposableEffect(map) {
        val owner = ctx.findLifecycleOwner()
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> map.onResume(); Lifecycle.Event.ON_PAUSE -> map.onPause(); else -> {} }
        }
        owner?.lifecycle?.addObserver(obs)
        if (owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) map.onResume()
        onDispose { owner?.lifecycle?.removeObserver(obs); map.onPause(); map.onDetach() }
    }

    // frame the nearest few agents (and the member) whenever the list changes
    val sig = items.joinToString(",") { it.str("code") } + (if (me != null) "|me" else "")
    LaunchedEffect(sig) {
        val pts = ArrayList<GeoPoint>()
        if (me != null) pts.add(me)
        (if (me != null) items.take(5) else items).forEach { pts.add(GeoPoint(it.dbl("lat"), it.dbl("lng"))) }
        when {
            pts.isEmpty() -> {}
            pts.size == 1 -> { map.controller.setZoom(15.0); map.controller.setCenter(pts[0]) }
            else -> {
                val box = BoundingBox.fromGeoPoints(pts)
                map.post {
                    map.zoomToBoundingBox(box, false, (48 * ctx.resources.displayMetrics.density).toInt())
                    if (map.zoomLevelDouble > 17.0) map.controller.setZoom(17.0)
                }
            }
        }
    }
    LaunchedEffect(selected) {
        items.firstOrNull { it.str("code") == selected }?.let { map.controller.animateTo(GeoPoint(it.dbl("lat"), it.dbl("lng"))) }
    }

    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        m.overlays.add(CopyrightOverlay(ctx))             // "© OpenStreetMap contributors" is required by the tile licence
        for (a in items) {
            val bureau = a.str("kind") == "bureau"
            val code = a.str("code")
            val mk = Marker(m)
            mk.position = GeoPoint(a.dbl("lat"), a.dbl("lng"))
            mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            mk.icon = pinDrawable(ctx, if (bureau) 0xFF0061FF.toInt() else 0xFF00BF8E.toInt(), bureau, code == selected)
            mk.title = a.str("name")
            mk.setOnMarkerClickListener { _, _ -> onPick(code); true }
            m.overlays.add(mk)
        }
        if (me != null) {
            val mm = Marker(m)
            mm.position = me
            mm.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            mm.icon = mePinDrawable(ctx)
            mm.setOnMarkerClickListener { _, _ -> true }
            m.overlays.add(mm)
        }
        m.invalidate()
    })
}

/* ---------------------------------------------------------------- screen */

@Composable
private fun LocatorChip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(if (on) Green else Color.White).border(1.dp, if (on) Green else Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, color = if (on) Color.White else Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun MiniButton(text: String, icon: ImageVector, tone: Color, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.height(40.dp).clip(shape).background(if (filled) tone else tone.copy(alpha = .10f)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (filled) Color.White else tone, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = if (filled) Color.White else tone, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LocatorCard(a: JSONObject, km: Double?, selected: Boolean, onSelect: () -> Unit, onChoose: (() -> Unit)?) {
    val ctx = LocalContext.current
    val bureau = a.str("kind") == "bureau"
    val tone = if (bureau) Blue else Green
    val shape = RoundedCornerShape(18.dp)
    val place = listOf(a.str("city"), a.str("address")).filter { it.isNotEmpty() }.joinToString(" — ")
    Column(
        Modifier.fillMaxWidth().card(shape).then(if (selected) Modifier.border(2.dp, tone, shape) else Modifier).clickable(onClick = onSelect).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(tone.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (bureau) Icons.Default.Business else Icons.Default.Storefront, null, tint = tone, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.str("name"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(L(if (bureau) "BUREAU" else "AGENT"), color = tone, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.sp)
            }
            if (km != null) Text(kmText(km), color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.LocationOn, null, tint = Muted, modifier = Modifier.size(15.dp).padding(top = 1.dp))
            Spacer(Modifier.width(6.dp))
            Text(place, color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (a.str("hours").isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AccessTime, null, tint = Muted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(a.str("hours"), color = Muted, fontSize = 12.sp)
        }
        val fee = a.dbl("fee_pct")
        if (fee > 0) Text("${L("Fee")} ${String.format(Locale.US, "%.1f", fee)}%", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.str("phone").isNotEmpty()) MiniButton(softCase(L("CALL")), Icons.Default.Phone, Blue, true, Modifier.weight(1f)) { locatorDial(ctx, a.str("phone")) }
            MiniButton(softCase(L("DIRECTIONS")), Icons.Default.Directions, GreenDark, false, Modifier.weight(1.25f)) {
                openDirections(ctx, a.dbl("lat"), a.dbl("lng"), a.str("name"), place)
            }
            if (onChoose != null) MiniButton(softCase(L("CHOOSE")), Icons.Default.Check, Green, true, Modifier.weight(1f), onChoose)
        }
    }
}

/**
 * Full-screen agent locator. onChoose != null (opened from the Withdraw form) adds a CHOOSE button to every agent.
 * Shown inside a Dialog by AgentPickerBlock; onClose closes it.
 */
@Composable
fun AgentLocatorScreen(onClose: () -> Unit, onChoose: ((JSONObject) -> Unit)? = null) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var me by remember { mutableStateOf<Location?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    val locate = rememberLocationRequester { loc ->
        locating = false
        if (loc == null) locFailed = true else { locFailed = false; me = loc }
    }
    LaunchedEffect(Unit) {
        if (hasLocationPermission(ctx)) {
            locating = true
            val l = currentLocation(ctx)
            locating = false
            if (l != null) me = l else locFailed = true
        }
    }

    // The server only gets the position rounded to ~1 km, for this request only (it is never stored). Exact distances are worked out here.
    val qLat = me?.let { String.format(Locale.US, "%.2f", it.latitude) }
    val qLng = me?.let { String.format(Locale.US, "%.2f", it.longitude) }
    LaunchedEffect(qLat, qLng, kind, reload) {
        loading = true
        error = ""
        val q = HashMap<String, String>()
        if (qLat != null && qLng != null) { q["lat"] = qLat; q["lng"] = qLng }
        if (kind.isNotEmpty()) q["kind"] = kind
        val r = Api.call("GET", "/agents/nearby", query = q)
        loading = false
        if (r.ok) items = r.data.list("items") else { items = emptyList(); error = r.message.ifEmpty { "Could not load agents." } }
    }

    val here = me
    val shown = remember(items, query, here) {
        val f = items.filter { a ->
            query.isBlank() || listOf(a.str("name"), a.str("city"), a.str("address")).any { it.contains(query.trim(), ignoreCase = true) }
        }
        if (here != null) f.sortedBy { distanceKm(here, it) } else f
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val i = shown.indexOfFirst { it.str("code") == selected }
        if (i >= 0) listState.animateScrollToItem(i)
    }

    Column(Modifier.fillMaxSize().background(Page)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, null, tint = Ink)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(L("Find an agent"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(if (loading) "…" else "${shown.size} • " + L(if (here != null) "near you" else "in your country"), color = Muted, fontSize = 12.sp)
            }
            if (here != null) Box(Modifier.size(40.dp).clip(CircleShape).clickable { locating = true; locate() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MyLocation, null, tint = Blue)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocatorChip(L("All"), kind == "") { kind = "" }
            LocatorChip(L("Agents"), kind == "agent") { kind = "agent" }
            LocatorChip(L("Bureaus"), kind == "bureau") { kind = "bureau" }
        }
        if (here == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).background(Blue.copy(alpha = .08f), RoundedCornerShape(15.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MyLocation, null, tint = Blue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Text(
                    L(if (locFailed) "We could not get your position. Agents are listed by town." else "Allow your location to see the closest agents first."),
                    color = Ink, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (locating) CircularProgressIndicator(Modifier.size(20.dp), color = Blue, strokeWidth = 2.dp)
                else Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Blue).clickable { locating = true; locate() }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(softCase(L("USE MY LOCATION")), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) { InputField(L("Search town or name"), query, { query = it }) }
        }

        AgentMapView(
            shown, here?.let { GeoPoint(it.latitude, it.longitude) }, selected, { selected = it },
            Modifier.fillMaxWidth().weight(0.9f)
        )

        Box(Modifier.fillMaxWidth().weight(1.1f), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator(color = Green)
                error.isNotEmpty() -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(L(error), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Green).clickable { reload++ }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text(softCase(L("TRY AGAIN")), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                shown.isEmpty() -> Box(Modifier.padding(16.dp)) {
                    EmptyState(L("No agent listed nearby yet"), Icons.Default.LocationOn, L("Ask an agent for his code, or try again later."))
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(shown, key = { _, a -> a.str("code") }) { _, a ->
                        LocatorCard(
                            a, here?.let { distanceKm(it, a) } ?: if (a.has("distance_km")) a.dbl("distance_km") else null,
                            a.str("code") == selected, { selected = a.str("code") },
                            if (onChoose != null) ({ onChoose(a) }) else null
                        )
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- agent desk: "show me on the map" */

/** Agent Desk card: the agent decides whether the map shows him, and where. Position comes from the phone's location. */
@Composable
fun LocatorSettingsCard(agent: JSONObject) {
    val scope = rememberCoroutineScope()
    var lat by remember { mutableStateOf(if (agent.isNull("lat")) null else agent.dbl("lat")) }
    var lng by remember { mutableStateOf(if (agent.isNull("lng")) null else agent.dbl("lng")) }
    var listed by remember { mutableStateOf(agent.bool("listed")) }
    var busy by remember { mutableStateOf(false) }

    fun save(newLat: Double?, newLng: Double?, newListed: Boolean) {
        busy = true
        scope.launch {
            val body = JSONObject().put("listed", newListed)
            if (newLat != null && newLng != null) body.put("lat", newLat).put("lng", newLng)
            val r = Api.call("POST", "/agent/location", body)
            busy = false
            if (r.ok) { lat = newLat; lng = newLng; listed = r.data.bool("listed") } else Ui.notify("Agent map", r.message)
        }
    }
    val locate = rememberLocationRequester { loc ->
        if (loc == null) Ui.notify("Location", "We could not get your position. Turn on location and try again.")
        else save(loc.latitude, loc.longitude, true)
    }

    Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(L("Agent map"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(L(if (listed) "Members can see you on the map" else "Hidden from the map"), color = Muted, fontSize = 12.sp)
            }
            Switch(checked = listed, enabled = !busy, onCheckedChange = { v ->
                val la = lat; val ln = lng
                if (!v) save(la, ln, false)
                else if (la != null && ln != null) save(la, ln, true)
                else locate()
            })
        }
        Text(L("Members near you can find you, call you and get directions. Your pin is shown about 100 m off; your address and phone are shown on your card."), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        if (listed) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Blue.copy(alpha = .10f)).clickable(enabled = !busy) { locate() }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MyLocation, null, tint = Blue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(softCase(L("UPDATE MY POSITION")), color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
