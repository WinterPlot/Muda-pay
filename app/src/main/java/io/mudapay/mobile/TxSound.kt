package io.mudapay.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import org.json.JSONObject

/**
 * The "money done" sound (res/raw/tx_success.mp3): played when a transaction completes while the app is open
 * (money sent, money received, deposit credited, withdrawal paid, cash-out settled).
 * When the app is closed the same sound comes from the notification channel "Completed transactions" (see Push).
 * It follows the phone: silent in silent / vibrate mode, and it uses the notification volume.
 */
object TxSound {
    private var last = 0L

    /** [key] identifies the transaction (e.g. "dep:REF"): the same one never plays twice, whether it is seen in the app or in a push. */
    fun play(key: String? = null) {
        val ctx = Push.appOrNull() ?: return
        val now = System.currentTimeMillis()
        if (now - last < 1500L) return
        if (key != null) {
            if (Push.wasShown(ctx, "snd:$key")) return
            Push.markShown(ctx, "snd:$key")
        }
        last = now
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val mp = MediaPlayer.create(ctx, R.raw.tx_success, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.start()
        } catch (e: Throwable) { ErrorReporter.nonFatal(e, "TxSound.play") }
    }

    /** For a server answer that carries the deposit "reference". */
    fun playFor(data: JSONObject) {
        val ref = data.str("reference")
        play(if (ref.isEmpty()) null else "dep:$ref")
    }
}
