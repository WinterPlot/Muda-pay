package io.mudapay.mobile

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/* ---------------------------------------------------------------- JSON helpers */
fun JSONObject.str(key: String, def: String = ""): String = if (isNull(key)) def else optString(key, def)
fun JSONObject.dbl(key: String): Double = if (isNull(key)) 0.0 else optDouble(key, 0.0)
fun JSONObject.int(key: String): Int = if (isNull(key)) 0 else optInt(key, 0)
fun JSONObject.bool(key: String): Boolean = optBoolean(key, false)
fun JSONObject.list(key: String): List<JSONObject> = optJSONArray(key).objects()
fun JSONObject.strings(key: String): List<String> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).map { a.optString(it) }
}
fun JSONObject.doubles(key: String): List<Double> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).map { a.optDouble(it, 0.0) }
}
fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>()
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

/* ---------------------------------------------------------------- result */
class ApiResult(
    val http: Int,
    val ok: Boolean,
    val data: JSONObject,
    val code: String,
    val message: String,
    val error: JSONObject? = null,
    val cached: Boolean = false,
    val cachedAt: Long = 0L
)

class Upload(val field: String, val filename: String, val mime: String, val bytes: ByteArray)

/* ---------------------------------------------------------------- token encryption (Android Keystore, AES-GCM) */
object SecureStore {
    private const val ALIAS = "mudapay_session_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? {
        return try {
            val all = Base64.decode(stored, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

/* ---------------------------------------------------------------- session */
object Session {
    private var prefs: SharedPreferences? = null
    var accessToken: String? = null
        private set
    var refreshToken: String? = null
        private set

    var signedIn by mutableStateOf(false)
    var user by mutableStateOf<JSONObject?>(null)
    var balance by mutableDoubleStateOf(0.0)
    var unread by mutableStateOf(0)
    var blockedMessage by mutableStateOf<String?>(null)

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("mudapay_session", Context.MODE_PRIVATE)
        prefs = p
        accessToken = p.getString("a", null)?.let { SecureStore.decrypt(it) }
        refreshToken = p.getString("r", null)?.let { SecureStore.decrypt(it) }
        signedIn = refreshToken != null
    }

    fun saveTokens(t: JSONObject) {
        val wasSignedIn = signedIn
        accessToken = t.str("access_token")
        refreshToken = t.str("refresh_token")
        prefs?.edit()
            ?.putString("a", SecureStore.encrypt(accessToken ?: ""))
            ?.putString("r", SecureStore.encrypt(refreshToken ?: ""))
            ?.apply()
        signedIn = true
        if (!wasSignedIn) Push.appOrNull()?.let { NotifyScheduler.schedule(it); NotifyScheduler.syncSoon(it) }
    }

    fun updateUser(u: JSONObject) {
        user = u
        balance = u.dbl("balance")
    }

    fun signOut() {
        accessToken = null
        refreshToken = null
        prefs?.edit()?.clear()?.apply()
        OfflineCache.clear()
        PendingQueue.clear()
        user = null
        balance = 0.0
        unread = 0
        signedIn = false
        AppLock.reset()
        Push.appOrNull()?.let { NotifyScheduler.cancel(it); NotifySync.reset(it); Push.cancelAll(it) }
    }
}

/* ---------------------------------------------------------------- shared OkHttp client */
object Http {
    val client: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // money calls must never be silently replayed
        if (Config.CERT_PINS.isNotEmpty()) {
            val host = URL(Config.SITE).host
            val pinner = CertificatePinner.Builder().apply { Config.CERT_PINS.forEach { add(host, it) } }.build()
            b.certificatePinner(pinner)
        }
        b.build()
    }
}

/* ---------------------------------------------------------------- HTTP client */
object Api {
    private val refreshLock = Mutex()

    suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
        auth: Boolean = true,
        idem: String? = null,
        timeoutMs: Int = 30000
    ): ApiResult {
        val cacheKey = if (method == "GET" && auth) OfflineCache.keyFor(path, query) else null
        val used = Session.accessToken
        var r = request(method, path, body, query, auth, idem, timeoutMs, null)
        if (auth && r.http == 401 && Session.refreshToken != null) {
            if (refresh(used)) r = request(method, path, body, query, auth, idem, timeoutMs, null)
        }
        afterCall(r, auth)
        if (cacheKey != null) {
            if (r.ok) OfflineCache.put(cacheKey, r.data)
            else if (r.code == "network") {
                val hit = OfflineCache.get(cacheKey)
                if (hit != null) return ApiResult(200, true, hit.first, "", "", null, true, hit.second)
            }
        }
        return r
    }

    suspend fun upload(path: String, file: Upload, timeoutMs: Int = 150000): ApiResult {
        val used = Session.accessToken
        var r = request("POST", path, null, emptyMap(), true, null, timeoutMs, file)
        if (r.http == 401 && Session.refreshToken != null) {
            if (refresh(used)) r = request("POST", path, null, emptyMap(), true, null, timeoutMs, file)
        }
        afterCall(r, true)
        return r
    }

    private fun afterCall(r: ApiResult, auth: Boolean) {
        if (!auth) return
        if (r.code == "account_blocked") {
            Session.blockedMessage = "This account has been blocked. Contact support through the website."
            Session.signOut()
        } else if (r.http == 401 && Session.refreshToken == null) {
            Session.signOut()
        }
    }

    /** One refresh at a time; if another call already refreshed, just retry with the new token. */
    private suspend fun refresh(used: String?): Boolean = refreshLock.withLock {
        val current = Session.accessToken
        if (current != null && current != used) return@withLock true
        val rt = Session.refreshToken ?: return@withLock false
        val r = request("POST", "/auth/refresh", JSONObject().put("refresh_token", rt), emptyMap(), false, null, 30000, null)
        if (r.ok) {
            val tokens = r.data.optJSONObject("tokens")
            if (tokens != null) {
                Session.saveTokens(tokens)
                return@withLock true
            }
        }
        if (r.http in 400..499) Session.signOut()
        false
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject?,
        query: Map<String, String>,
        auth: Boolean,
        idem: String?,
        timeoutMs: Int,
        file: Upload?
    ): ApiResult = withContext(Dispatchers.IO) {
        try {
            val url = (Config.API_BASE + path).toHttpUrl().newBuilder().apply {
                addQueryParameter("i", "1") // free hosts such as InfinityFree let API clients through with ?i=1
                for ((k, v) in query) addQueryParameter(k, v)
            }.build()

            val reqBody: RequestBody? = when {
                file != null -> MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(file.field, file.filename, file.bytes.toRequestBody(file.mime.toMediaType()))
                    .build()
                method == "GET" || method == "HEAD" -> null
                else -> (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            }

            val rb = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "MudaPayAndroid/2.1")
            if (auth) Session.accessToken?.let { rb.header("Authorization", "Bearer $it") }
            if (idem != null) rb.header("Idempotency-Key", idem)
            rb.method(method, reqBody)

            val client = Http.client.newBuilder().readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
            client.newCall(rb.build()).execute().use { resp ->
                val res = parse(resp.code, resp.body?.string() ?: "")
                Offline.reportReachable(true)
                res
            }
        } catch (e: SSLPeerUnverifiedException) {
            ApiResult(0, false, JSONObject(), "tls", "A secure connection to MudaPay could not be verified. Update the app or try another network.")
        } catch (e: Exception) {
            Offline.reportReachable(false)
            ApiResult(0, false, JSONObject(), "network", "Can't reach MudaPay. Check your connection and try again.")
        }
    }

    private fun parse(code: Int, text: String): ApiResult {
        val j = try { JSONObject(text) } catch (e: Exception) { null }
        if (j == null) {
            val msg = if (code in 200..299) "Unexpected answer from the server." else "The server returned an unexpected answer (HTTP $code)."
            return ApiResult(code, false, JSONObject(), "bad_response", msg)
        }
        if (j.optBoolean("ok")) return ApiResult(code, true, j.optJSONObject("data") ?: JSONObject(), "", "")
        val e = j.optJSONObject("error")
        return ApiResult(code, false, JSONObject(), e?.optString("code") ?: "error", e?.optString("message") ?: "Something went wrong.", e)
    }
}
