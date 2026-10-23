package io.mudapay.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

fun Context.findActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** App lock: fingerprint / face / phone PIN. Every option is chosen by the member in Settings. */
object AppLock {
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private var prefs: SharedPreferences? = null
    private var leftAt = 0L

    var enabled by mutableStateOf(false)
        private set
    var onLaunch by mutableStateOf(true)
        private set
    var forWithdraw by mutableStateOf(true)
        private set
    var timeoutSec by mutableIntStateOf(60)
        private set
    var hideScreen by mutableStateOf(false)
        private set
    var locked by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("mudapay_lock", Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("enabled", false)
        onLaunch = p.getBoolean("on_launch", true)
        forWithdraw = p.getBoolean("for_withdraw", true)
        timeoutSec = p.getInt("timeout", 60)
        hideScreen = p.getBoolean("hide_screen", false)
        locked = enabled && onLaunch && Session.signedIn
    }

    fun changeEnabled(v: Boolean) { enabled = v; prefs?.edit()?.putBoolean("enabled", v)?.apply(); if (!v) locked = false }
    fun changeOnLaunch(v: Boolean) { onLaunch = v; prefs?.edit()?.putBoolean("on_launch", v)?.apply() }
    fun changeForWithdraw(v: Boolean) { forWithdraw = v; prefs?.edit()?.putBoolean("for_withdraw", v)?.apply() }
    fun changeTimeout(sec: Int) { timeoutSec = sec; prefs?.edit()?.putInt("timeout", sec)?.apply() }
    fun changeHideScreen(v: Boolean) { hideScreen = v; prefs?.edit()?.putBoolean("hide_screen", v)?.apply() }
    fun unlock() { locked = false }

    /** Called on sign-out: lock settings belong to the signed-in member. */
    fun reset() {
        prefs?.edit()?.clear()?.apply()
        enabled = false; onLaunch = true; forWithdraw = true; timeoutSec = 60; hideScreen = false; locked = false
    }

    fun onBackground() { leftAt = SystemClock.elapsedRealtime() }
    fun onForeground() {
        if (enabled && onLaunch && Session.signedIn && !locked && leftAt > 0 &&
            SystemClock.elapsedRealtime() - leftAt >= timeoutSec * 1000L) locked = true
        leftAt = 0
    }

    fun status(ctx: Context): Int = BiometricManager.from(ctx).canAuthenticate(AUTH)

    fun statusMessage(code: Int): String = when (code) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            "Set up a screen lock (PIN, pattern, password or fingerprint) in your phone settings, then try again."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            "This device can't verify your identity right now."
        else -> "App lock is not available on this device."
    }

    fun disableBecauseUnavailable() {
        changeEnabled(false)
        Ui.notify("App lock turned off", "Your phone no longer has a screen lock, so MudaPay's app lock was turned off.")
    }

    /** [onError] receives "" when the member simply cancelled. */
    fun prompt(activity: FragmentActivity, title: String, subtitle: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                onError(if (cancelled) "" else errString.toString())
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(AUTH).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb).authenticate(info)
    }

    /** Runs [proceed] straight away, or after the member confirms, when "ask before withdrawals" is on. */
    fun requireForWithdrawal(activity: FragmentActivity?, proceed: () -> Unit) {
        if (!enabled || !forWithdraw || activity == null) { proceed(); return }
        if (status(activity) != BiometricManager.BIOMETRIC_SUCCESS) { disableBecauseUnavailable(); proceed(); return }
        prompt(activity, "Confirm withdrawal", "Confirm it's you to request this payout", proceed) { if (it.isNotEmpty()) Ui.notify("Withdraw", it) }
    }
}

@Composable
fun LockScreen() {
    val ctx = LocalContext.current
    val act = ctx.findActivity()
    var error by remember { mutableStateOf<String?>(null) }

    fun ask() {
        if (act == null) return
        if (AppLock.status(act) != BiometricManager.BIOMETRIC_SUCCESS) { AppLock.disableBecauseUnavailable(); return }
        AppLock.prompt(act, "Unlock MudaPay", "Confirm it's you to continue", { AppLock.unlock() }, { error = it.ifEmpty { null } })
    }
    SystemBars(darkIcons = false)
    LaunchedEffect(Unit) { ask() }

    Column(
        Modifier.fillMaxSize().background(Gradient).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("MudaPay is locked", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text("Use your fingerprint, face or phone PIN to continue.", color = Color.White.copy(alpha = .9f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
        error?.let { Text(it, color = Gold, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp)) }
        PrimaryButton("UNLOCK", { error = null; ask() }, color = Dark, icon = Icons.Default.Fingerprint)
        TextButton(onClick = { Session.signOut() }) { Text("Sign out", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}
