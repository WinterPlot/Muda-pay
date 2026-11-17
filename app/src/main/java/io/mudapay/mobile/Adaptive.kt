package io.mudapay.mobile

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One app for every screen: small phones (320 dp wide), big phones, tablets, foldables (folded and unfolded), split-screen and desktop windows.
 *
 *  - Orientation: nothing is locked in the manifest (Android 16 ignores a lock on screens of 600 dp and up, and Google Play flags it).
 *    Phones and a folded foldable (smallest width under 600 dp) stay in portrait, which is what the screens are designed for; a tablet or an
 *    unfolded foldable (600 dp and up) rotates freely. MainActivity handles the size changes itself (configChanges), so folding or rotating
 *    never restarts the app or loses what the member was typing.
 *  - Width: on a wide window the app is a centered column (640 dp, 760 dp on very wide windows) instead of stretching forms and
 *    buttons across a whole tablet.
 */
object Adaptive {
    const val LARGE_SMALLEST_WIDTH_DP = 600

    /** Widest the app content gets, for a window [widthDp] wide. Phones are narrower than this, so nothing changes for them. */
    fun maxContentWidth(widthDp: Int): Dp = if (widthDp >= 900) 760.dp else 640.dp

    /** Portrait on compact screens, free on large ones. Called when the activity starts and on every size / fold change. */
    fun applyOrientationPolicy(a: Activity) {
        val want = if (a.resources.configuration.smallestScreenWidthDp >= LARGE_SMALLEST_WIDTH_DP) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                   else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (a.requestedOrientation != want) a.requestedOrientation = want
    }

    @Suppress("DEPRECATION")
    fun displayRotation(ctx: Context): Int {
        val act = ctx.findActivity()
        return if (act != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) act.display?.rotation ?: 0
        else (act?.windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
}

/** The app's content, centered and capped in width on wide windows (tablet, unfolded foldable, desktop mode). */
@Composable
fun AdaptiveRoot(content: @Composable BoxScope.() -> Unit) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = Adaptive.maxContentWidth(widthDp)).fillMaxWidth(), content = content)
    }
}

/** Width the app content really has, in dp (the window width, capped like [AdaptiveRoot]). */
@Composable
fun contentWidthDp(): Int {
    val w = LocalConfiguration.current.screenWidthDp
    return minOf(w, Adaptive.maxContentWidth(w).value.toInt())
}

/** The display rotation (Surface.ROTATION_*), updated when the tablet or foldable is turned, including the upside-down turn that sends no configuration change. */
@Composable
fun rememberDisplayRotation(): Int {
    val ctx = LocalContext.current
    var rotation by remember { mutableIntStateOf(Adaptive.displayRotation(ctx)) }
    DisposableEffect(ctx) {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) { rotation = Adaptive.displayRotation(ctx) }
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    return rotation
}
