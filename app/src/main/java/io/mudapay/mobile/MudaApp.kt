package io.mudapay.mobile

import android.app.Application

class MudaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        AppLock.init(this)
        Push.createChannels(this)
    }
}
