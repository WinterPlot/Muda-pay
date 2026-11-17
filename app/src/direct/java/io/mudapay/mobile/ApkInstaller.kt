package io.mudapay.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * "direct" build only (website download page / sideload): downloads the update APK from MudaPay's own server and hands it to
 * Android's installer. The Google Play build has an empty version of this object (src/play) and no install permission.
 */
object ApkInstaller {
    fun fetch(app: Context, i: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "MudaPay-v" + i.versionName.filter { it.isLetterOrDigit() || it == '.' || it == '-' } + ".apk")
        val client = Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(i.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty")
            val total = if (body.contentLength() > 0) body.contentLength() else i.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != shown) { shown = pct; onProgress(pct / 100f) } }
                    }
                }
            }
            if (i.sha256.isNotEmpty() && md.digest().joinToString("") { "%02x".format(it) } != i.sha256.lowercase()) {
                out.delete(); throw IOException("checksum")
            }
        }
        return out
    }

    /** Opens Android's installer for the downloaded file. The system itself checks that it is signed with the same key as this app. */
    fun install(ctx: Context, f: File): InstallResult {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return InstallResult(false, L("Allow MudaPay to install updates in the screen that opens, then come back and tap Install."))
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return InstallResult(true)
        } catch (e: Exception) {
            ErrorReporter.nonFatal(e, "AppUpdate.install")
            return InstallResult(false, L("The update could not be installed. Please try again."), failed = true)
        }
    }
}
