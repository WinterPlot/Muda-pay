package io.mudapay.mobile

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings tiles (the panel you pull down from the top of the phone): Send money, Scan to pay, Receive money and a
 * "Hide balance" switch. A tile only opens MudaPay on the right screen; when the phone is locked Android asks to unlock first,
 * and the app's own lock / PIN still applies. Nothing is paid from a tile.
 */
@RequiresApi(Build.VERSION_CODES.N)
abstract class RouteTile(private val route: String, private val labelRes: Int, private val iconRes: Int) : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.label = getString(labelRes)
        t.icon = Icon.createWithResource(this, iconRes)
        t.state = Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        unlockAndRun {
            val i = RouteIntent.make(this, route)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, route.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.N) class SendTile : RouteTile("Send", R.string.tile_send, R.drawable.ic_qs_send)
@RequiresApi(Build.VERSION_CODES.N) class ScanTile : RouteTile("Scan", R.string.tile_scan, R.drawable.ic_qs_scan)
@RequiresApi(Build.VERSION_CODES.N) class ReceiveTile : RouteTile("Receive", R.string.tile_receive, R.drawable.ic_qs_receive)

/** On = the balance is hidden (app card and widget). Same switch as the eye icon. */
@RequiresApi(Build.VERSION_CODES.N)
class PrivacyTile : TileService() {
    private fun render() {
        BalancePrivacy.load(this)
        val t = qsTile ?: return
        t.label = getString(R.string.tile_hide)
        t.icon = Icon.createWithResource(this, if (BalancePrivacy.hidden) R.drawable.ic_qs_eye_off else R.drawable.ic_qs_eye)
        t.state = if (BalancePrivacy.hidden) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = getString(if (BalancePrivacy.hidden) R.string.tile_on else R.string.tile_off)
        t.updateTile()
    }
    override fun onStartListening() = render()
    override fun onClick() { BalancePrivacy.toggle(this); render() }
}
