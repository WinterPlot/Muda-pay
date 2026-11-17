package io.mudapay.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Coin icons come from the MudaPay SERVER (SITE/crypto_icon.php?s=BTC), never from the app package or from another site.
 * The phone keeps a copy in memory and on disk (7 days), so a list scrolls instantly and still shows icons offline.
 * The server answers 404 for a coin it has no icon for: the screen then draws its own coloured letter badge.
 */
object CryptoIcons {
    private val mem = LruCache<String, Bitmap>(96)
    private val missing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())   // 404 seen: not asked again until the app restarts
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    fun url(symbol: String): String = "${Config.SITE}/crypto_icon.php?s=${Uri.encode(symbol.uppercase())}"

    /** Already in memory (no waiting), or null. */
    fun peek(symbol: String): Bitmap? = mem.get(symbol.uppercase())

    suspend fun load(ctx: Context, symbol: String): Bitmap? = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(12)
        if (sym.isEmpty()) return@withContext null
        mem.get(sym)?.let { return@withContext it }
        if (sym in missing) return@withContext null
        val dir = File(ctx.cacheDir, "crypto_icons").apply { mkdirs() }
        val file = File(dir, "$sym.png")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        if (fresh) decode(file)?.let { mem.put(sym, it); return@withContext it }
        try {
            val req = Request.Builder().url(url(sym)).build()
            Http.client.newCall(req).execute().use { r ->
                if (r.code == 404) { missing.add(sym); file.delete(); return@withContext null }
                if (r.isSuccessful) {
                    val bytes = r.body?.bytes()
                    if (bytes != null && bytes.size > 100) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            runCatching { file.writeBytes(bytes) }
                            mem.put(sym, bmp)
                            return@withContext bmp
                        }
                    }
                }
            }
        } catch (_: Exception) { /* offline: fall through to the old copy */ }
        if (file.exists()) decode(file)?.let { mem.put(sym, it); return@withContext it }
        null
    }

    private fun decode(f: File): Bitmap? = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
}

/**
 * Currency / country flags of the Market screen. Same rules as the coin icons: the PNG comes from the MudaPay SERVER
 * (SITE/flags/xx.png), is kept in memory and on disk (30 days) so the Currencies list shows instantly and still works offline.
 * A flag the server does not have (404) is not asked again until the app restarts: the screen draws a currency-code badge instead.
 */
object FlagIcons {
    private val mem = LruCache<String, Bitmap>(64)
    private val missing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000

    fun peek(cc: String): Bitmap? = mem.get(cc.lowercase())

    suspend fun load(ctx: Context, cc: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = cc.lowercase().filter { it in 'a'..'z' }.take(2)
        if (key.length != 2) return@withContext null
        mem.get(key)?.let { return@withContext it }
        if (key in missing) return@withContext null
        val dir = File(ctx.cacheDir, "flag_icons").apply { mkdirs() }
        val file = File(dir, "$key.png")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        if (fresh) decode(file)?.let { mem.put(key, it); return@withContext it }
        try {
            val req = Request.Builder().url(flagUrl(key)).build()
            Http.client.newCall(req).execute().use { r ->
                if (r.code == 404) { missing.add(key); file.delete(); return@withContext null }
                if (r.isSuccessful) {
                    val bytes = r.body?.bytes()
                    if (bytes != null && bytes.size > 100) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            runCatching { file.writeBytes(bytes) }
                            mem.put(key, bmp)
                            return@withContext bmp
                        }
                    }
                }
            }
        } catch (_: Exception) { /* offline: fall through to the old copy */ }
        if (file.exists()) decode(file)?.let { mem.put(key, it); return@withContext it }
        null
    }

    private fun decode(f: File): Bitmap? = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
}
