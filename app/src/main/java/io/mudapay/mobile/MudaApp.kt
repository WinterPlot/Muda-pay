package io.mudapay.mobile

import android.app.Application

class MudaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorReporter.init(this)   // first: a crash while the rest starts is still recorded and sent
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
