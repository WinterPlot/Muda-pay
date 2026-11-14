package io.mudapay.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/** Result of one question sent to Muda AI from the voice screen. */
class VoiceAnswer(val ok: Boolean, val text: String)

enum class VoiceState { Idle, Listening, Thinking, Speaking }

/**
 * One voice conversation: microphone -> Android SpeechRecognizer -> text -> [ask] (the existing /coach/messages
 * endpoint on your PHP server, which calls OpenRouter) -> reply text -> Android TextToSpeech -> speaker.
 * Both speech engines run on the phone; only text goes over HTTPS.
 */
class VoiceEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val ask: suspend (String) -> VoiceAnswer,
) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    var state by mutableStateOf(VoiceState.Idle)
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var heard by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
    /** When on, the mic opens again by itself after Muda AI finishes speaking. */
    var handsFree by mutableStateOf(true)

    private var active = true
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsLangOk = true
    private var lastUtterance = ""
    private var pendingSpeech: String? = null
    private var onSpeechDone: (() -> Unit)? = null

    private fun locale(): Locale = when (AppLanguage.current) {
        "fr" -> Locale.FRANCE
        "cs" -> Locale("cs", "CZ")
        "ha" -> Locale("ha", "NG")
        "pt" -> Locale("pt", "BR")
        "sw" -> Locale("sw", "TZ")
        "rw" -> Locale("rw", "RW")
        else -> Locale.US
    }

    /* ------------------------------------------------------------ public controls */

    /** Warm up text-to-speech and open the mic. */
    fun begin() {
        initTts()
        startListening()
    }

    /** The big round button. */
    fun micTap() {
        when (state) {
            VoiceState.Listening -> recognizer?.stopListening()
            VoiceState.Speaking -> { stopSpeakingOnly(); startListening() }
            VoiceState.Idle -> startListening()
            VoiceState.Thinking -> Unit
        }
    }

    fun shutdown() {
        active = false
        destroyRecognizer()
        onSpeechDone = null
        pendingSpeech = null
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        state = VoiceState.Idle
        level = 0f
    }

    /* ------------------------------------------------------------ listening */

    private fun startListening() {
        if (!active) return
        error = null
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            error = L("Speech recognition isn't available on this phone. Install or enable the Google app.")
            state = VoiceState.Idle
            return
        }
        stopSpeakingOnly()
        destroyRecognizer()
        partial = ""; level = 0f
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(listenerFor(r))
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            }
            state = VoiceState.Listening
            r.startListening(i)
        } catch (e: Exception) {
            ErrorReporter.nonFatal(e, "VoiceChat.listen")
            destroyRecognizer()
            state = VoiceState.Idle
            error = L("Voice recognition failed. Try again.")
        }
    }

    private fun destroyRecognizer() {
        val r = recognizer
        recognizer = null // callbacks from the old recognizer are ignored from here on
        if (r != null) {
            try { r.cancel() } catch (_: Exception) {}
            try { r.destroy() } catch (_: Exception) {}
        }
    }

    private fun listenerFor(owner: SpeechRecognizer) = object : RecognitionListener {
        private fun mine() = owner === recognizer
        override fun onReadyForSpeech(params: Bundle?) { if (mine()) state = VoiceState.Listening }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (mine()) level = rmsdB }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (mine()) level = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            if (!mine()) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) partial = t
        }
        override fun onResults(results: Bundle?) {
            if (!mine()) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            destroyRecognizer(); level = 0f; partial = ""
            if (text.isEmpty()) {
                state = VoiceState.Idle
                error = L("I didn't hear anything. Tap the mic and try again.")
            } else process(text)
        }
        override fun onError(error: Int) {
            if (!mine()) return
            destroyRecognizer(); level = 0f; partial = ""
            state = VoiceState.Idle
            this@VoiceEngine.error = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> L("I didn't hear anything. Tap the mic and try again.")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> L("Microphone permission is needed for voice chat.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> L("Speech recognition needs an internet connection.")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> L("Speech recognition is busy. Try again in a moment.")
                else -> L("Voice recognition failed. Try again.")
            }
        }
    }

    /* ------------------------------------------------------------ server round trip */

    private fun process(text: String) {
        heard = text; reply = ""; state = VoiceState.Thinking
        scope.launch {
            val a = ask(text)
            if (!active) return@launch
            if (!a.ok) {
                error = a.text.ifBlank { L("Voice recognition failed. Try again.") }
                state = VoiceState.Idle
                return@launch
            }
            reply = a.text
            speak(a.text) {
                if (active && handsFree) main.postDelayed({ if (active && handsFree && state == VoiceState.Idle) startListening() }, 350)
            }
        }
    }

    /* ------------------------------------------------------------ speaking */

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            main.post {
                if (!active) return@post
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { if (utteranceId == lastUtterance) main.post { finishSpeaking() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { if (utteranceId == lastUtterance) main.post { finishSpeaking() } }
                        override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == lastUtterance) main.post { finishSpeaking() } }
                    })
                    pendingSpeech?.let { pendingSpeech = null; play(it) }
                } else {
                    error = L("Text-to-speech isn't available on this phone.")
                    pendingSpeech = null
                    if (state == VoiceState.Speaking) state = VoiceState.Idle
                }
            }
        }
    }

    private fun speak(text: String, done: () -> Unit) {
        val clean = speakable(text)
        if (clean.isBlank()) { done(); return }
        onSpeechDone = done
        state = VoiceState.Speaking
        if (!ttsReady) { pendingSpeech = clean; initTts(); return }
        play(clean)
    }

    private fun play(clean: String) {
        val t = tts ?: return
        val r = t.setLanguage(locale())
        ttsLangOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsLangOk) {
            error = L("Voice for this language isn't installed. Open phone Settings › Text-to-speech to add it.")
            onSpeechDone = null
            state = VoiceState.Idle
            return
        }
        val parts = chunk(clean, 3000)
        val base = "muda_" + System.nanoTime()
        lastUtterance = base + "_" + (parts.size - 1)
        parts.forEachIndexed { i, c ->
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, base + "_" + i)
        }
    }

    private fun finishSpeaking() {
        if (state != VoiceState.Speaking) return
        state = VoiceState.Idle
        val cb = onSpeechDone
        onSpeechDone = null
        cb?.invoke()
    }

    private fun stopSpeakingOnly() {
        onSpeechDone = null
        pendingSpeech = null
        try { tts?.stop() } catch (_: Exception) {}
        if (state == VoiceState.Speaking) state = VoiceState.Idle
    }

    /** Markdown, links, emoji and symbols sound silly when read aloud, so they are removed first. */
    private fun speakable(src: String): String {
        val plain = markdownPlain(src)
            .replace(Regex("https?://\\S+"), "")
            .replace("•", "")
            .replace(Regex("[*#_~`]"), "")
            .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]"), "")
        return plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n") { if (it.last() in ".!?:;…") it else "$it." }
    }

    /** TextToSpeech refuses very long input (about 4000 characters), so long answers are cut at sentence ends. */
    private fun chunk(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in text.split(Regex("(?<=[.!?\\n])\\s+"))) {
            var piece = s
            while (piece.length > max) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                out.add(piece.substring(0, max)); piece = piece.substring(max)
            }
            if (cur.length + piece.length + 1 > max && cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(piece)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}

/** Full-screen voice conversation with Muda AI. [ask] sends the recognised text to the server and returns the reply. */
@Composable
fun VoiceChatDialog(onClose: () -> Unit, ask: suspend (String) -> VoiceAnswer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { VoiceEngine(ctx, scope, ask) }
    var permBlocked by remember { mutableStateOf(false) }

    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permBlocked = !granted
        if (granted) engine.begin()
    }

    LaunchedEffect(Unit) { if (hasMic()) engine.begin() else permLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(Unit) { onDispose { engine.shutdown() } }

    Dialog(
        onDismissRequest = { engine.shutdown(); onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        val view = LocalView.current
        DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

        val level01 = ((engine.level - 1f) / 8f).coerceIn(0f, 1f)
        val level by animateFloatAsState(level01, tween(120), label = "level")
        val listening = engine.state == VoiceState.Listening
        val busy = engine.state == VoiceState.Thinking
        val glass = Color.White.copy(alpha = .07f)
        val glassLine = Color.White.copy(alpha = .12f)

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy, Color(0xFF0B2A33), Color(0xFF0B3B33))))) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val orbDiameter = (maxHeight * 0.27f).coerceIn(140.dp, 210.dp)
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(L("Voice chat"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(glass).border(1.dp, glassLine, RoundedCornerShape(50)).padding(start = 14.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(L("Auto-listen"), color = Color.White.copy(alpha = .85f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = engine.handsFree, onCheckedChange = { engine.handsFree = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = .15f), uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White.copy(alpha = .8f))
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(glass).border(1.dp, glassLine, CircleShape)
                                .clickable { engine.shutdown(); onClose() },
                            contentAlignment = Alignment.Center
                        ) { Icon(MudaIcons.Close, L("Close"), tint = Color.White, modifier = Modifier.size(20.dp)) }
                    }

                    Spacer(Modifier.height(6.dp))
                    CloudOrb(state = engine.state, level = level01, diameter = orbDiameter, accent = Green)

                    Crossfade(targetState = engine.state, label = "status") { st ->
                        val status = when (st) {
                            VoiceState.Listening -> engine.partial.ifEmpty { L("Listening…") }
                            VoiceState.Thinking -> L("Muda AI is thinking…")
                            VoiceState.Speaking -> L("Speaking…")
                            VoiceState.Idle -> engine.error ?: L("Tap the mic to talk")
                        }
                        val isError = st == VoiceState.Idle && engine.error != null
                        Text(
                            status, color = if (isError) Color(0xFFFCA5A5) else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        )
                    }
                    Box(Modifier.height(22.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        if (busy) TypingDots(color = Color.White.copy(alpha = .8f))
                    }
                    if (permBlocked) {
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text(L("Open settings"), color = Gold, fontWeight = FontWeight.Bold) }
                    }

                    val hasText = engine.heard.isNotEmpty() || engine.reply.isNotEmpty()
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp, bottom = 14.dp)) {
                        if (hasText) {
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(glass).border(1.dp, glassLine, RoundedCornerShape(24.dp))
                                    .verticalScroll(rememberScrollState()).padding(18.dp)
                            ) {
                                if (engine.heard.isNotEmpty()) {
                                    Text(engine.heard, color = Color.White.copy(alpha = .6f), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                }
                                if (engine.reply.isNotEmpty()) {
                                    Spacer(Modifier.height(14.dp))
                                    Text(markdownLite(engine.reply), color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }

                    val ring = rememberInfiniteTransition(label = "ring")
                    val ringPhase by ring.animateFloat(0f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Restart), label = "ringPhase")
                    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                        if (listening) {
                            Box(
                                Modifier.size(78.dp)
                                    .graphicsLayer { val s = 1f + .55f * ringPhase + level * .25f; scaleX = s; scaleY = s; alpha = .35f * (1f - ringPhase) }
                                    .background(DangerFill, CircleShape)
                            )
                            Box(
                                Modifier.size(78.dp)
                                    .graphicsLayer { val s = 1.14f + level * .3f; scaleX = s; scaleY = s; alpha = .18f }
                                    .background(DangerFill, CircleShape)
                            )
                        }
                        Box(
                            Modifier.size(78.dp).clip(CircleShape)
                                .background(if (listening) Brush.linearGradient(listOf(DangerFill, DangerFill)) else if (busy) Brush.linearGradient(listOf(Slate, Slate)) else Gradient)
                                .border(1.dp, Color.White.copy(alpha = .22f), CircleShape)
                                .clickable(enabled = !busy) { if (hasMic()) engine.micTap() else permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (listening) MudaIcons.Stop else MudaIcons.Mic, L("Voice chat"), tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                    Text(
                        when (engine.state) {
                            VoiceState.Listening -> L("Tap to finish")
                            VoiceState.Speaking -> L("Tap to interrupt")
                            else -> ""
                        },
                        color = Color.White.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.height(16.dp)
                    )
                }
            }
        }
    }
}
