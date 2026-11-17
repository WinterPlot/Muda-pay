package io.mudapay.mobile

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import kotlin.math.sqrt

/**
 * Shake to hide: while MudaPay is open (never in the background), a quick shake of the phone hides the balance, exactly like
 * the eye icon. A shake only ever HIDES; showing the balance again is always a deliberate tap.
 * Three sharp jolts within a short time are needed, so walking or a bump in a bus does not trigger it.
 */
object ShakeToHide : SensorEventListener {
    private const val JOLT_G = 2.6f          // sharper than normal handling of the phone
    private const val WINDOW_MS = 800L       // the 3 jolts must follow each other within this time
    private const val MIN_GAP_MS = 150L      // one shake makes several readings: count a jolt once
    private const val COOLDOWN_MS = 2_000L

    private var sm: SensorManager? = null
    private var app: Context? = null
    private var inForeground = false
    private var jolts = 0
    private var lastJolt = 0L
    private var lastTrigger = 0L

    /** MainActivity: the app came to the front / went to the background. */
    fun foreground(ctx: Context, on: Boolean) {
        inForeground = on
        apply(ctx)
    }

    /** (Un)registers the sensor to match the Settings switch and the app state. */
    fun apply(ctx: Context) {
        BalancePrivacy.load(ctx)
        val want = inForeground && BalancePrivacy.shake
        if (!want) { sm?.unregisterListener(this); sm = null; return }
        if (sm != null) return
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        app = ctx.applicationContext
        sm = m
        m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        if (g < JOLT_G) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < COOLDOWN_MS) return
        if (jolts > 0 && now - lastJolt < MIN_GAP_MS) return
        if (now - lastJolt > WINDOW_MS) jolts = 0
        jolts++
        lastJolt = now
        if (jolts >= 3) { jolts = 0; lastTrigger = now; trigger() }
    }

    private fun trigger() {
        val c = app ?: return
        if (!Session.signedIn || BalancePrivacy.hidden) return
        BalancePrivacy.set(c, true)
        Toast.makeText(c, L("Balance hidden"), Toast.LENGTH_SHORT).show()
    }

    /** Android 13+: the system asks "Add tile?" right away. Older phones: show where to drag it. */
    fun addQuickTile(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val sbm = ctx.getSystemService(StatusBarManager::class.java)
                if (sbm != null) {
                    sbm.requestAddTileService(
                        ComponentName(ctx, PrivacyTile::class.java), ctx.getString(R.string.tile_hide),
                        Icon.createWithResource(ctx, R.drawable.ic_qs_eye_off), ctx.mainExecutor
                    ) { }
                    return
                }
            } catch (_: Throwable) { }
        }
        Ui.notify(L("Quick tile"), L("Swipe down twice from the top of the screen, tap the pencil (edit) icon and drag \"Hide balance\" into your tiles."))
    }
}
