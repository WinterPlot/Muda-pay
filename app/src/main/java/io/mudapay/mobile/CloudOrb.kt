package io.mudapay.mobile

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val MAX_SCALE = 1.25f

private const val VERTEX_SRC = """
attribute vec2 a_position;
void main() {
  gl_Position = vec4(a_position, 0.0, 1.0);
}
"""

private const val FRAGMENT_SRC = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 u_resolution;
uniform float u_time;
uniform float u_activity;
uniform float u_think;
uniform float u_spin;
uniform float u_scale;
uniform float u_fade;
uniform vec3 u_deep;
uniform vec3 u_upper;
uniform vec3 u_lower;
uniform vec3 u_milk;

float hash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
    u.y
  );
}

float fbm(vec2 p) {
  float value = 0.0;
  float amplitude = 0.52;
  mat2 rotation = mat2(0.80, 0.60, -0.60, 0.80);
  for (int octave = 0; octave < 4; octave++) {
    value += amplitude * noise(p);
    p = rotation * p * 1.92 + vec2(9.7, 4.3);
    amplitude *= 0.5;
  }
  return value;
}

void main() {
  vec2 uv0 = gl_FragCoord.xy / u_resolution;
  vec2 centered = (uv0 - 0.5) * (${MAX_SCALE} / max(u_scale, 0.001));
  vec2 uv = centered + 0.5;
  float radius = length(centered);
  float edge = 1.0 - smoothstep(0.484, 0.5, radius);
  if (edge <= 0.0) discard;

  vec2 p = centered * 2.0;
  float t = u_time;

  float twist = u_think * (0.9 + 0.5 * sin(t * 1.1)) * (1.0 - smoothstep(0.0, 1.0, length(p)));
  float cs = cos(twist);
  float sn = sin(twist);
  p = mat2(cs, -sn, sn, cs) * p;

  vec2 warp = vec2(
    fbm(p * 1.02 + vec2(t * 0.34, -t * 0.24)),
    fbm(p * 1.08 + vec2(-t * 0.27, t * 0.32) + vec2(6.7, 2.9))
  );
  vec2 curl = vec2(
    sin(p.y * 2.4 + t * 0.68 + warp.y * 3.2),
    cos(p.x * 2.1 - t * 0.61 + warp.x * 3.0)
  );
  vec2 warped = p + (warp - 0.5) * (1.18 + u_activity * 0.38) + curl * (0.035 + u_activity * 0.07);
  float broad = fbm(warped * 0.92 + vec2(t * 0.14, -t * 0.18));
  float folded = fbm(warped * 1.66 + vec2(-t * 0.23, t * 0.19) + 5.2);
  float field = mix(broad, folded, 0.3 + u_activity * 0.14);

  float horizon = 0.46 + 0.08 * sin((uv.x + warp.x * 0.2) * 5.4 + t * 0.42) + 0.16 * (broad - 0.5);
  float upper = smoothstep(horizon - 0.12, horizon + 0.08, uv.y);
  float bandX = (uv.y - horizon) * (5.2 + u_activity * 0.8);
  float band = exp(-bandX * bandX);
  float cloud = smoothstep(0.24, 0.79, field);

  vec3 color = mix(u_lower, u_upper, upper);
  float upperDepth = upper * (0.14 + smoothstep(0.42, 0.78, folded) * 0.5);
  color = mix(color, u_deep, upperDepth);

  float milkAmount = clamp(band * (0.42 + cloud * 0.62 + u_think * 0.18), 0.0, 0.9);
  color = mix(color, u_milk, milkAmount);

  float lowerMist = (1.0 - upper) * smoothstep(0.58, 0.9, broad) * 0.18;
  color = mix(color, u_milk, lowerMist);

  float rim = smoothstep(0.40, 0.488, radius) * (0.10 + u_activity * 0.14);
  color = mix(color, u_milk, rim);

  float specD = length(centered - vec2(-0.14, 0.2)) * 3.2;
  float spec = exp(-specD * specD) * 0.16;
  color = mix(color, u_milk, spec);

  float ang = atan(centered.y, centered.x);
  float d1 = mod(u_spin - ang, 6.2831853);
  float d2 = mod(u_spin + 3.1415926 - ang, 6.2831853);
  float ring = smoothstep(0.30, 0.47, radius);
  float comet = (exp(-d1 * 1.7) + 0.5 * exp(-d2 * 2.4)) * ring * u_think;
  color = mix(color, vec3(1.0), clamp(comet * 0.85, 0.0, 0.95));

  float grain = (noise(gl_FragCoord.xy * 0.64) - 0.5) / 255.0;
  color += grain;

  float a = edge * u_fade;
  gl_FragColor = vec4(color * a, a);
}
"""

/**
 * Cloud orb from orb-ui (Cloud theme), ported to a native OpenGL ES 2.0 TextureView.
 * state: 0 idle, 1 listening, 2 thinking, 3 speaking. level: microphone level 0..1 (only used while listening).
 */
class CloudOrbView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    @Volatile var state: Int = 0
    @Volatile var level: Float = 0f
    @Volatile var palette: FloatArray = FloatArray(12)
    var onFailed: (() -> Unit)? = null

    private var worker: RenderThread? = null
    private val ui = Handler(Looper.getMainLooper())

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val t = RenderThread(surface, width, height)
        worker = t
        t.start()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        worker?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val t = worker
        worker = null
        if (t != null) {
            t.running = false
            try { t.join(800) } catch (_: InterruptedException) {}
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private inner class RenderThread(private val texture: SurfaceTexture, w: Int, h: Int) : Thread("MudaCloudOrb") {
        @Volatile var running = true
        @Volatile private var width = w
        @Volatile private var height = h

        fun resize(w: Int, h: Int) { width = w; height = h }

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0

        private val quad: FloatBuffer = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)); position(0)
        }

        override fun run() {
            if (!setupEgl() || !setupProgram()) {
                teardown()
                ui.post { onFailed?.invoke() }
                return
            }
            val aPos = GLES20.glGetAttribLocation(program, "a_position")
            val uRes = GLES20.glGetUniformLocation(program, "u_resolution")
            val uTime = GLES20.glGetUniformLocation(program, "u_time")
            val uAct = GLES20.glGetUniformLocation(program, "u_activity")
            val uThink = GLES20.glGetUniformLocation(program, "u_think")
            val uSpin = GLES20.glGetUniformLocation(program, "u_spin")
            val uScale = GLES20.glGetUniformLocation(program, "u_scale")
            val uFade = GLES20.glGetUniformLocation(program, "u_fade")
            val uDeep = GLES20.glGetUniformLocation(program, "u_deep")
            val uUpper = GLES20.glGetUniformLocation(program, "u_upper")
            val uLower = GLES20.glGetUniformLocation(program, "u_lower")
            val uMilk = GLES20.glGetUniformLocation(program, "u_milk")

            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)

            val begin = SystemClock.uptimeMillis()
            var prev = begin
            var volume = 0f
            var audioScale = 1f
            var think = 0f
            var speed = 0.24f
            var activity = 0.1f
            var flow = 0f
            var spin = 0f

            while (running) {
                val now = SystemClock.uptimeMillis()
                val dt = min((now - prev) / 1000f, 0.05f)
                prev = now
                val sec = (now - begin) / 1000f
                val st = this@CloudOrbView.state   // plain "state" would resolve to Thread.getState()

                val raw = when (st) {
                    1 -> level.coerceIn(0f, 1f)
                    3 -> speechEnvelope(sec)
                    else -> 0f
                }
                volume = follow(volume, raw, 210f, 380f, dt)

                val audioTarget = when (st) {
                    1 -> 1f - volume * (1f - 0.796f)
                    3 -> 1f + volume * (1.2145f - 1f)
                    2 -> 0.94f + 0.045f * sin(sec * 2.6f)
                    else -> 0.88f
                }
                audioScale = follow(audioScale, audioTarget, 250f, 250f, dt)
                think = follow(think, if (st == 2) 1f else 0f, 450f, 650f, dt)

                val speedTarget = when (st) {
                    1 -> 0.72f + volume * 0.78f
                    3 -> 1.65f + volume * 1.55f
                    2 -> 0.95f
                    else -> 0.24f
                }
                val activityTarget = when (st) {
                    1 -> 0.28f + volume * 0.32f
                    3 -> 0.66f + volume * 0.34f
                    2 -> 0.52f
                    else -> 0.1f
                }
                speed = follow(speed, speedTarget, 420f, 420f, dt)
                activity = follow(activity, activityTarget, 300f, 420f, dt)
                flow += dt * speed
                spin += dt * (0.4f + 2.6f * think)

                val elapsed = (now - begin).toFloat()
                val entrance = entranceScale(elapsed)
                val influence = clamp((elapsed - 180f - 300f) / 1350f)
                val scale = (entrance * (1f + (audioScale - 1f) * influence)).coerceIn(0.02f, MAX_SCALE)
                val fade = smooth(elapsed, 180f + 300f * 0.22f, 180f + 300f * 0.8f)

                val w = width
                val h = height
                GLES20.glViewport(0, 0, w, h)
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUniform2f(uRes, w.toFloat(), h.toFloat())
                GLES20.glUniform1f(uTime, flow)
                GLES20.glUniform1f(uAct, activity)
                GLES20.glUniform1f(uThink, think)
                GLES20.glUniform1f(uSpin, spin)
                GLES20.glUniform1f(uScale, scale)
                GLES20.glUniform1f(uFade, fade)
                val c = palette
                GLES20.glUniform3f(uDeep, c[0], c[1], c[2])
                GLES20.glUniform3f(uUpper, c[3], c[4], c[5])
                GLES20.glUniform3f(uLower, c[6], c[7], c[8])
                GLES20.glUniform3f(uMilk, c[9], c[10], c[11])
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
                if (!EGL14.eglSwapBuffers(display, surface)) break

                val spent = SystemClock.uptimeMillis() - now
                if (spent < 15) {
                    try { sleep(16 - spent) } catch (_: InterruptedException) { break }
                }
            }
            teardown()
        }

        private fun setupEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] <= 0) return false
            val config = configs[0] ?: return false
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) return false
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false
            return EGL14.eglMakeCurrent(display, surface, surface, context)
        }

        private fun setupProgram(): Boolean {
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SRC)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
            if (vs == 0 || fs == 0) return false
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return ok[0] != 0
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { GLES20.glDeleteShader(s); return 0 }
            return s
        }

        private fun teardown() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            }
            surface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
        }
    }
}

private fun clamp(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

private fun smooth(v: Float, a: Float, b: Float): Float {
    val x = clamp((v - a) / (b - a))
    return x * x * (3f - 2f * x)
}

private fun follow(current: Float, target: Float, riseMs: Float, fallMs: Float, dt: Float): Float {
    val ms = if (target > current) riseMs else fallMs
    if (ms <= 0f) return target
    val rate = 1f - 0.1f.pow(dt * 1000f / ms)
    return current + (target - current) * rate
}

private fun entranceScale(elapsed: Float): Float {
    val hold = 180f
    val grow = 300f
    val settle = 1350f
    val dot = 0.063f
    val overshoot = 1.178f
    if (elapsed <= hold) return dot
    if (elapsed <= hold + grow) {
        val x = clamp((elapsed - hold) / grow)
        val e = 1f - (1f - x) * (1f - x) * (1f - x)
        return dot + (overshoot - dot) * e
    }
    val s = elapsed - hold - grow
    if (s >= settle) return 1f
    val x = clamp(s / settle)
    return 1f + (overshoot - 1f) * (1f - x) * (1f - x)
}

private fun speechEnvelope(t: Float): Float {
    val a = 0.5f + 0.5f * sin(t * 9.0f)
    val b = 0.5f + 0.5f * sin(t * 3.7f + 1.3f)
    val c = 0.5f + 0.5f * sin(t * 15.0f + 2.0f)
    val gate = if (sin(t * 1.9f + 0.6f) > -0.55f) 1f else 0.15f
    return clamp((0.12f + 0.55f * a * b + 0.28f * c * b) * gate)
}

private fun Color.rgb(out: FloatArray, at: Int) {
    out[at] = red; out[at + 1] = green; out[at + 2] = blue
}

/** The cloud orb with a soft aura. [state] comes from VoiceEngine; [level] is the mic level 0..1. */
@Composable
fun CloudOrb(
    state: VoiceState,
    level: Float,
    diameter: Dp,
    accent: Color,
    modifier: Modifier = Modifier,
    onFailed: () -> Unit = {},
) {
    val canvas = diameter * MAX_SCALE
    val density = LocalDensity.current.density
    val k = (420f / (canvas.value * density)).coerceIn(0.35f, 1f)
    val code = when (state) {
        VoiceState.Idle -> 0
        VoiceState.Listening -> 1
        VoiceState.Thinking -> 2
        VoiceState.Speaking -> 3
    }
    val palette = remember(accent) {
        FloatArray(12).also {
            lerp(accent, Color.Black, 0.30f).rgb(it, 0)
            accent.rgb(it, 3)
            lerp(accent, Color.White, 0.55f).rgb(it, 6)
            lerp(accent, Color.White, 0.88f).rgb(it, 9)
        }
    }
    val auraTarget = when (state) {
        VoiceState.Idle -> 0.10f
        VoiceState.Listening -> 0.20f + level * 0.30f
        VoiceState.Thinking -> 0.32f
        VoiceState.Speaking -> 0.30f + level * 0.2f
    }
    val aura by animateFloatAsState(auraTarget, tween(260), label = "aura")
    var failed by remember { mutableStateOf(false) }

    Box(modifier.size(canvas), contentAlignment = Alignment.Center) {
        Canvas(Modifier.requiredSize(canvas * 1.7f)) {
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to accent.copy(alpha = aura),
                    0.55f to accent.copy(alpha = aura * 0.35f),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = size.minDimension / 2f
                )
            )
        }
        if (failed) {
            AiOrb(size = diameter * 0.5f, pulse = state != VoiceState.Idle)
        } else {
            AndroidView(
                factory = { ctx ->
                    CloudOrbView(ctx).also {
                        it.onFailed = { failed = true; onFailed() }
                    }
                },
                modifier = Modifier
                    .size(canvas * k)
                    .graphicsLayer { scaleX = 1f / k; scaleY = 1f / k },
                update = { v ->
                    v.state = code
                    v.level = level
                    v.palette = palette
                }
            )
        }
    }
}
