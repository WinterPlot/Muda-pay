package io.mudapay.mobile

import android.app.Application

class MudaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorReporter.init(this)   // first: a crash while the rest starts is still recorded and sent
        try {   // shows / hides the second launcher icon "MudaPay Debug" (Config.DEBUG_TOOLS)
            packageManager.setComponentEnabledSetting(
                android.content.ComponentName(this, "io.mudapay.mobile.DebugLauncher"),
                if (Config.DEBUG_TOOLS) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (_: Throwable) { }
        AppLanguage.init(this)
        Push.init(this)
        Session.init(this)
        Offline.init(this)
        AppLock.init(this)
        Push.createChannels(this)
        // The process can be started by a reboot, a push or the background check: keep the checks scheduled.
        if (Session.signedIn) NotifyScheduler.schedule(this)
    }
}
