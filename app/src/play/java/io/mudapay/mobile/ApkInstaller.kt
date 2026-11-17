package io.mudapay.mobile

import android.content.Context
import java.io.File

/**
 * Google Play build: the app never downloads or installs an APK by itself (Play policy). AppUpdate opens the Play Store page instead,
 * so these two functions are never called. The real installer lives in src/direct.
 */
object ApkInstaller {
    @Suppress("UNUSED_PARAMETER")
    fun fetch(app: Context, i: UpdateInfo, onProgress: (Float) -> Unit): File = throw UnsupportedOperationException("Updates come from Google Play")

    @Suppress("UNUSED_PARAMETER")
    fun install(ctx: Context, f: File): InstallResult = InstallResult(false)
}
