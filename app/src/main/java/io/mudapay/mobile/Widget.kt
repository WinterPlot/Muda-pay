package io.mudapay.mobile

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Intent that opens MudaPay on a screen ("Send", "Scan", "Receive"...), used by the widget, the Quick Settings tiles and the icon shortcuts. */
object RouteIntent {
    fun make(ctx: Context, route: String?): Intent = Intent(ctx, MainActivity::class.java).apply {
        action = "io.mudapay.mobile.OPEN_" + (route ?: "HOME")   // different action = different PendingIntent per button
        route?.let { putExtra("route", it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/**
 * What the widget may show: only the formatted balance text and the time it was saved, encrypted with the same Keystore key as
 * the session. No token, no phone number, no name. It is wiped at sign-out.
 */
object WidgetData {
    class Snapshot(val balanceText: String?, val savedAt: Long)
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("mudapay_widget", Context.MODE_PRIVATE)
    fun save(ctx: Context, text: String) {
        prefs(ctx).edit().putString("b", SecureStore.encrypt(text)).putLong("t", System.currentTimeMillis()).apply()
    }
    fun read(ctx: Context): Snapshot {
        val p = prefs(ctx)
        val text = try { p.getString("b", null)?.let { SecureStore.decrypt(it) } } catch (_: Throwable) { null }
        return Snapshot(text?.takeIf { it.isNotEmpty() }, p.getLong("t", 0L))
    }
    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
}

/** Keeps the widget in step with the app. Safe to call from anywhere. */
object WidgetSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Call when the balance, the user or the sign-in state changes. */
    fun push(ctx: Context) {
        val app = ctx.applicationContext
        val u = Session.user
        if (!Session.signedIn || u == null) WidgetData.clear(app)
        else WidgetData.save(app, money(u.str("symbol"), Session.balance))
        refresh(app)
    }
    fun clear(ctx: Context) { WidgetData.clear(ctx.applicationContext); refresh(ctx) }
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { try { MudaWidget().updateAll(app) } catch (_: Throwable) { } }
    }
}

private val NAVY = Color(0xFF0B1F3A)

/** Card colour: the navy of the previous theme in light mode, a dark surface in dark mode (the app's own Appearance setting). */
private fun cardColor(): Color = if (MudaTheme.dark) (if (MudaTheme.pureBlack) Color.Black else Color(0xFF0F1A2D)) else NAVY

class MudaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        BalancePrivacy.load(context)
        MudaTheme.load(context); MudaTheme.syncSystem(context)   // the widget can be redrawn without any screen open
        val snap = WidgetData.read(context)
        val hidden = BalancePrivacy.hidden
        provideContent { WidgetContent(context, snap, hidden) }
    }
}

class MudaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MudaWidget()
}

/** The eye on the widget: same switch as the eye on the app's balance card. */
class ToggleBalanceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        BalancePrivacy.toggle(context)   // saves the choice and refreshes every widget
    }
}

@Composable
private fun WidgetContent(ctx: Context, snap: WidgetData.Snapshot, hidden: Boolean) {
    val wide = LocalSize.current.width >= 250.dp
    val white = ColorProvider(Color.White)
    val soft = ColorProvider(Color.White.copy(alpha = .72f))
    val signedIn = snap.balanceText != null
    Column(
        GlanceModifier.fillMaxSize().background(ColorProvider(cardColor())).cornerRadius(24.dp).padding(14.dp)
            .clickable(actionStartActivity(RouteIntent.make(ctx, null)))
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(L("MudaPay"), style = TextStyle(color = soft, fontSize = 12.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.defaultWeight())
            if (signedIn) Image(
                ImageProvider(if (hidden) R.drawable.ic_qs_eye_off else R.drawable.ic_qs_eye),
                if (hidden) L("Show balance") else L("Hide balance"),
                modifier = GlanceModifier.size(24.dp).clickable(actionRunCallback<ToggleBalanceAction>()),
                colorFilter = ColorFilter.tint(white)
            )
        }
        if (!signedIn) {
            Spacer(GlanceModifier.height(6.dp))
            Text(L("Open MudaPay to sign in"), style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        } else {
            Text(L("Available balance"), style = TextStyle(color = soft, fontSize = 11.sp))
            Text(
                if (hidden) "\u2022\u2022\u2022\u2022\u2022\u2022" else snap.balanceText ?: "",
                style = TextStyle(color = white, fontSize = if (wide) 26.sp else 20.sp, fontWeight = FontWeight.Bold), maxLines = 1
            )
            if (!hidden && snap.savedAt > 0) {
                val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snap.savedAt))
                Text(L("Updated") + " " + t, style = TextStyle(color = soft, fontSize = 10.sp))
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill(ctx, L("Send"), "Send")
                Spacer(GlanceModifier.width(8.dp))
                Pill(ctx, L("Scan"), "Scan")
                if (wide) { Spacer(GlanceModifier.width(8.dp)); Pill(ctx, L("Receive"), "Receive") }
            }
        }
    }
}

@Composable
private fun RowScope.Pill(ctx: Context, label: String, route: String) {
    Box(
        GlanceModifier.defaultWeight().height(34.dp).background(ColorProvider(MudaTheme.green)).cornerRadius(17.dp)
            .clickable(actionStartActivity(RouteIntent.make(ctx, route))),
        contentAlignment = Alignment.Center
    ) { Text(label, style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1) }
}
