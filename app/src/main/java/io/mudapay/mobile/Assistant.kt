package io.mudapay.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject

/*
 * Muda AI as the phone's DIGITAL ASSISTANT.
 *
 *  Settings > Apps > Default apps > Digital assistant app > MudaPay  (MudaPay shows a shortcut in its own Settings).
 *  After that, the assistant gesture (hold Home / corner swipe / power-button hold, depending on the phone) opens
 *  AssistActivity ON TOP of whatever app is open. MudaPay itself is not opened: only the glowing panel with the orb.
 *
 *  Pieces: MudaAssistService + MudaAssistSessionService + MudaAssistSession (what Android needs to list MudaPay as an
 *  assistant), MudaAssistRecognitionService (required by the assistant declaration; it simply hands the audio to the
 *  phone's normal speech recognizer), AssistActivity (the panel itself).
 */

/** Android binds this service when MudaPay is chosen as the default assistant. */
class MudaAssistService : VoiceInteractionService()

class MudaAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MudaAssistSession(this)
}

/** One "assistant gesture": opens the Muda AI panel over the current app. */
class MudaAssistSession(private val c: Context) : VoiceInteractionSession(c) {
    companion object {
        @Volatile var current: MudaAssistSession? = null
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        current = this
        try {
            startAssistantActivity(
                Intent(c, AssistActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Throwable) {
            ErrorReporter.nonFatal(e, "Assist.show")
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}

/**
 * Android requires a recognition service in an assistant declaration, and may point the phone's speech recognition at it.
 * So it must really work: it forwards every request to the phone's normal recognizer (Google's if present).
 */
class MudaAssistRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private var inner: SpeechRecognizer? = null

    private fun target(): ComponentName? {
        val found = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != packageName }
        val pick = found.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" } ?: found.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        main.post {
            val comp = target()
            if (comp == null) { listener.error(SpeechRecognizer.ERROR_CLIENT); return@post }
            try {
                inner?.destroy()
                val sr = SpeechRecognizer.createSpeechRecognizer(this, comp)
                inner = sr
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { listener.readyForSpeech(params ?: Bundle()) }
                    override fun onBeginningOfSpeech() { listener.beginningOfSpeech() }
                    override fun onRmsChanged(rmsdB: Float) { listener.rmsChanged(rmsdB) }
                    override fun onBufferReceived(buffer: ByteArray?) { if (buffer != null) listener.bufferReceived(buffer) }
                    override fun onEndOfSpeech() { listener.endOfSpeech() }
                    override fun onError(error: Int) { listener.error(error) }
                    override fun onResults(results: Bundle?) { listener.results(results ?: Bundle()) }
                    override fun onPartialResults(partialResults: Bundle?) { listener.partialResults(partialResults ?: Bundle()) }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                sr.startListening(recognizerIntent)
            } catch (e: Throwable) {
                ErrorReporter.nonFatal(e, "Assist.recognizer")
                try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Throwable) {}
            }
        }
    }

    override fun onStopListening(listener: Callback) { main.post { try { inner?.stopListening() } catch (_: Throwable) {} } }

    override fun onCancel(listener: Callback) {
        main.post { try { inner?.cancel(); inner?.destroy() } catch (_: Throwable) {}; inner = null }
    }

    override fun onDestroy() {
        main.post { try { inner?.destroy() } catch (_: Throwable) {}; inner = null }
        super.onDestroy()
    }
}

/* ------------------------------------------------------------------------------------------ the panel */

class AssistActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = mudaColorScheme()) {
                AssistOverlay(
                    onClose = { finish(); overridePendingTransition(0, 0) },
                    onOpenApp = {
                        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        MudaAssistSession.current?.let { try { it.hide() } catch (_: Throwable) {} }
        super.onDestroy()
    }
}

private val glowColors = listOf(
    Color(0xFF00BF8E), Color(0xFF38BDF8), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFE9B949), Color(0xFF00BF8E)
)

private suspend fun askAssistant(text: String, conv: IntArray): VoiceAnswer {
    val t = text.trim()
    if (t.isEmpty()) return VoiceAnswer(false, L("I didn't hear anything. Tap the mic and try again."))
    val body = JSONObject().put("message", t).put("voice", true).put("lang", AppLanguage.current)
    if (conv[0] != 0) body.put("conversation_id", conv[0])
    val r = Api.call("POST", "/coach/messages", body, timeoutMs = 140000)
    if (r.ok) {
        conv[0] = r.data.optJSONObject("conversation")?.int("id") ?: conv[0]
        return VoiceAnswer(true, r.data.optJSONObject("reply")?.str("content") ?: "")
    }
    return VoiceAnswer(false, r.message)
}

@Composable
private fun AssistOverlay(onClose: () -> Unit, onOpenApp: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conv = remember { IntArray(1) }
    val usable = Session.signedIn && !(AppLock.enabled && AppLock.locked)
    val engine = remember { VoiceEngine(ctx, scope) { t -> askAssistant(t, conv) } }

    var shown by remember { mutableStateOf(false) }
    var permBlocked by remember { mutableStateOf(false) }
    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permBlocked = !granted
        if (granted) engine.begin()
    }
    fun close() { engine.shutdown(); onClose() }

    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(usable) { if (usable) { if (hasMic()) engine.begin() else permLauncher.launch(Manifest.permission.RECORD_AUDIO) } }
    DisposableEffect(Unit) { onDispose { engine.shutdown() } }
    BackHandler { close() }

    // entrance: the dark veil fades in, the colour glow rises from the bottom edge, the panel springs up, the orb pops in
    val veil by animateFloatAsState(if (shown) 0.55f else 0f, tween(380), label = "veil")
    val glowRise by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow), label = "glowRise")
    val orbPop by animateFloatAsState(if (shown) 1f else 0.2f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow), label = "orbPop")
    val flow = rememberInfiniteTransition(label = "flow")
    val shift by flow.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "shift")
    val pulse by flow.animateFloat(0.75f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse")

    val level01 = ((engine.level - 1f) / 8f).coerceIn(0f, 1f)
    val level by animateFloatAsState(level01, tween(120), label = "level")
    val listening = engine.state == VoiceState.Listening
    val busy = engine.state == VoiceState.Thinking
    val panelShape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)

    Box(Modifier.fillMaxSize()) {
        // tap outside the panel to dismiss
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
        )

        // moving colour glow behind / around the panel (like a light coming up from the bottom of the screen)
        BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(420.dp * glowRise)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = pulse }
                    .drawBehind {
                        val w = size.width
                        drawRect(
                            Brush.horizontalGradient(glowColors, startX = shift * w, endX = shift * w + w, tileMode = TileMode.Mirror),
                            alpha = 0.75f
                        )
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), blendMode = BlendMode.DstIn)
                    }
            )
        }

        AnimatedVisibility(
            visible = shown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(250))
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(panelShape)
                    .background(Brush.verticalGradient(listOf(Color(0xE60B1730), Color(0xF20A1226))))
                    .drawBehind {
                        val w = size.width
                        drawRoundRect(
                            Brush.horizontalGradient(glowColors, startX = shift * w, endX = shift * w + w, tileMode = TileMode.Mirror),
                            cornerRadius = CornerRadius(34.dp.toPx()), style = Stroke(3.dp.toPx())
                        )
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(Gradient, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(MudaIcons.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Muda AI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f)).clickable { close() },
                        contentAlignment = Alignment.Center
                    ) { Icon(MudaIcons.Close, L("Close"), tint = Color.White, modifier = Modifier.size(18.dp)) }
                }

                Box(Modifier.graphicsLayer { scaleX = orbPop; scaleY = orbPop; alpha = orbPop.coerceIn(0f, 1f) }) {
                    CloudOrb(state = engine.state, level = level01, diameter = 170.dp, accent = Green)
                }

                if (!usable) {
                    val msg = if (!Session.signedIn) L("Sign in to MudaPay to talk to Muda AI.") else L("Unlock MudaPay first, then ask again.")
                    Text(msg, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
                    PrimaryButton("OPEN MUDAPAY", onOpenApp)
                } else {
                    Crossfade(targetState = engine.state, label = "assistStatus") { st ->
                        val status = when (st) {
                            VoiceState.Listening -> engine.partial.ifEmpty { L("Listening…") }
                            VoiceState.Thinking -> L("Muda AI is thinking…")
                            VoiceState.Speaking -> L("Speaking…")
                            VoiceState.Idle -> engine.error ?: L("Tap the mic to talk")
                        }
                        val isError = st == VoiceState.Idle && engine.error != null
                        Text(
                            status, color = if (isError) Color(0xFFFCA5A5) else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
                        )
                    }
                    if (engine.reply.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                            Text(markdownLite(engine.reply), color = Color.White.copy(alpha = .92f), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (permBlocked) {
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text(L("Open settings"), color = Gold, fontWeight = FontWeight.Bold) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onOpenApp) { Text(L("Open MudaPay"), color = Color.White.copy(alpha = .75f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                        Box(
                            Modifier.size(68.dp).clip(CircleShape)
                                .background(if (listening) Brush.linearGradient(listOf(DangerFill, DangerFill)) else if (busy) Brush.linearGradient(listOf(Slate, Slate)) else Gradient)
                                .border(1.dp, Color.White.copy(alpha = .22f), CircleShape)
                                .clickable(enabled = !busy) { if (hasMic()) engine.micTap() else permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            contentAlignment = Alignment.Center
                        ) { Icon(if (listening) MudaIcons.Stop else MudaIcons.Mic, L("Voice chat"), tint = Color.White, modifier = Modifier.size(30.dp)) }
                        Box(Modifier.width(96.dp))   // keeps the mic button centred
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------ settings card */

private fun isDefaultAssistant(ctx: Context): Boolean = try {
    (Settings.Secure.getString(ctx.contentResolver, "assistant") ?: "").startsWith(ctx.packageName + "/")
} catch (_: Throwable) { false }

/** Settings card: make Muda AI the phone's default assistant, or try the panel right now. */
@Composable
fun AssistantSettings() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = ctx.findActivity() as? LifecycleOwner
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner?.lifecycle?.addObserver(o)
        onDispose { owner?.lifecycle?.removeObserver(o) }
    }
    val isDefault = remember(tick) { isDefaultAssistant(ctx) }

    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L("Default assistant"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            L("Make Muda AI your phone's assistant. Use your phone's assistant gesture (hold Home, swipe from a corner or hold the power button) and Muda AI opens on top of any app, without opening MudaPay."),
            color = Muted, fontSize = 12.sp, lineHeight = 17.sp
        )
        Text(
            if (isDefault) L("Muda AI is your default assistant.") else L("Not set yet."),
            color = if (isDefault) GreenDark else Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp
        )
        PrimaryButton(if (isDefault) "CHANGE ASSISTANT" else "SET AS DEFAULT ASSISTANT", {
            for (a in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)) {
                try { ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break } catch (_: Throwable) { }
            }
        }, icon = MudaIcons.AutoAwesome)
        Text(L("On the next screen choose Digital assistant app, then MudaPay."), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        TextButton(onClick = { ctx.startActivity(Intent(ctx, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
            Text(L("Try it now"), color = GreenDark, fontWeight = FontWeight.Bold)
        }
    }
}
