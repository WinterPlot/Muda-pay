package io.mudapay.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Muda AI's face: a gradient orb with a soft pulse ring. [pulse] = false gives a calm, static avatar. */
@Composable
fun AiOrb(size: Dp = 42.dp, pulse: Boolean = true, tint: Color = Color.White, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    Box(modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
        if (pulse) {
            Box(
                Modifier.size(size)
                    .graphicsLayer { val s = 1f + .5f * p; scaleX = s; scaleY = s; alpha = .45f * (1f - p) }
                    .background(Green, CircleShape)
            )
        }
        Box(Modifier.size(size).background(Gradient, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoAwesome, null, tint = tint, modifier = Modifier.size(size * .52f))
        }
    }
}

/** Three bouncing dots shown while Muda AI is writing. */
@Composable
fun TypingDots(color: Color = Muted) {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val on = phase.toInt() == i
            Box(
                Modifier.size(8.dp)
                    .graphicsLayer { translationY = if (on) -4f else 0f }
                    .background(color.copy(alpha = if (on) 1f else .35f), CircleShape)
            )
        }
    }
}

/** Tiny markdown subset the assistant tends to use: **bold**, "# heading", "- bullet". Everything else stays plain text. */
fun markdownLite(src: String): AnnotatedString = buildAnnotatedString {
    val lines = src.replace("\r", "").replace("`", "").split("\n")
    lines.forEachIndexed { idx, raw ->
        var line = raw
        var heading = false
        val h = Regex("^#{1,4}\\s+").find(line)
        if (h != null) {
            line = line.substring(h.range.last + 1); heading = true
        } else if (line.startsWith("- ") || line.startsWith("* ")) {
            line = "•  " + line.substring(2)
        }
        if (heading) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        var i = 0
        while (i < line.length) {
            val s = line.indexOf("**", i)
            if (s < 0) { append(line.substring(i)); break }
            val e = line.indexOf("**", s + 2)
            if (e < 0) { append(line.substring(i)); break }
            append(line.substring(i, s))
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(line.substring(s + 2, e))
            pop()
            i = e + 2
        }
        if (heading) pop()
        if (idx < lines.lastIndex) append("\n")
    }
}

/** Same text without the markdown marks (for copy / share). */
fun markdownPlain(src: String): String = markdownLite(src).text

/** Rounded corner shapes for chat bubbles. */
val AiBubbleShape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
val MeBubbleShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
