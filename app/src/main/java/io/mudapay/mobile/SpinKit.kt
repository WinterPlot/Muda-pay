package io.mudapay.mobile

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compose port of the SpinKit loaders (github.com/ybq/Android-SpinKit, Apache-2.0): same keyframes, delays and easing,
 * drawn on a Canvas so no View / extra dependency is needed. Change [SpinKitDefault] to switch the whole app.
 */
enum class SpinStyle { Circle, ThreeBounce, Wave, PulseRing, DoubleBounce }

val SpinKitDefault = SpinStyle.Circle

private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EasePulse = CubicBezierEasing(0.21f, 0.53f, 0.56f, 0.8f)

private fun keyframe(p: Float, fr: FloatArray, v: FloatArray, ease: Easing): Float {
    for (i in 0 until fr.size - 1) {
        if (p >= fr[i] && p <= fr[i + 1]) {
            val local = (p - fr[i]) / (fr[i + 1] - fr[i])
            return v[i] + (v[i + 1] - v[i]) * ease.transform(local)
        }
    }
    return v[v.size - 1]
}

/** Animation progress 0..1 of one sprite; a positive [delay] makes the sprite start later (SpinKit's setAnimationDelay). */
private fun phase(ms: Long, duration: Int, delay: Int): Float {
    val x = (((ms - delay) % duration) + duration) % duration
    return x.toFloat() / duration
}

@Composable
fun SpinKit(
    modifier: Modifier = Modifier,
    style: SpinStyle = SpinKitDefault,
    color: Color = Green,
    size: Dp = 48.dp,
) {
    var ms by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) androidx.compose.runtime.withFrameMillis { ms = it - start }
    }
    Canvas(modifier.size(size)) {
        val t = ms          // read here so only the draw phase is invalidated each frame
        when (style) {
            SpinStyle.Circle -> drawCircle12(t, color)
            SpinStyle.ThreeBounce -> drawThreeBounce(t, color)
            SpinStyle.Wave -> drawWave(t, color)
            SpinStyle.PulseRing -> drawPulseRing(t, color)
            SpinStyle.DoubleBounce -> drawDoubleBounce(t, color)
        }
    }
}

private val F3 = floatArrayOf(0f, .5f, 1f)

private fun DrawScope.drawCircle12(t: Long, color: Color) {
    val s = this.size.minDimension
    val rad = s * Math.PI.toFloat() / 3.6f / 12f
    for (i in 0 until 12) {
        val sc = keyframe(phase(t, 1200, 100 * i), F3, floatArrayOf(0f, 1f, 0f), EaseInOut)
        if (sc <= 0f) continue
        rotate(i * 30f, pivot = Offset(this.size.width / 2f, this.size.height / 2f)) {
            drawCircle(color, radius = rad * sc, center = Offset(this.size.width / 2f, rad))
        }
    }
}

private fun DrawScope.drawThreeBounce(t: Long, color: Color) {
    val s = this.size.minDimension
    val rad = s / 8f
    val shift = (s - (s * 2f / 3f + rad * 2f)) / 2f
    val fr = floatArrayOf(0f, .4f, .8f, 1f)
    for (i in 0 until 3) {
        val sc = keyframe(phase(t, 1400, 160 * i), fr, floatArrayOf(0f, 1f, 0f, 0f), EaseInOut)
        if (sc <= 0f) continue
        drawCircle(color, radius = rad * sc, center = Offset(shift + s * i / 3f + rad, this.size.height / 2f))
    }
}

private fun DrawScope.drawWave(t: Long, color: Color) {
    val s = this.size.minDimension
    val rw = s / 5f
    val w = s / 5f * 3f / 5f
    val fr = floatArrayOf(0f, .2f, .4f, 1f)
    for (i in 0 until 5) {
        val sy = keyframe(phase(t, 1200, 600 + i * 100), fr, floatArrayOf(.4f, 1f, .4f, .4f), EaseInOut)
        val h = s * sy
        drawRect(color, topLeft = Offset(i * rw + rw / 5f, (this.size.height - h) / 2f), size = Size(w, h))
    }
}

private fun DrawScope.drawPulseRing(t: Long, color: Color) {
    val s = this.size.minDimension
    val p = phase(t, 1000, 0)
    val fr = floatArrayOf(0f, .7f, 1f)
    val sc = keyframe(p, fr, floatArrayOf(0f, 1f, 1f), EasePulse)
    val a = keyframe(p, fr, floatArrayOf(1f, .7f, 0f), EasePulse)
    if (sc <= 0f) return
    drawCircle(color.copy(alpha = color.alpha * a), radius = s / 2f * sc, center = center, style = Stroke(width = s / 24f * sc))
}

private fun DrawScope.drawDoubleBounce(t: Long, color: Color) {
    val s = this.size.minDimension
    for (i in 0 until 2) {
        val sc = keyframe(phase(t, 2000, 1000 * i), F3, floatArrayOf(0f, 1f, 0f), EaseInOut)
        if (sc <= 0f) continue
        drawCircle(color.copy(alpha = color.alpha * .6f), radius = s / 2f * sc, center = center)
    }
}
