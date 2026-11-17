package io.mudapay.mobile

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the recovery phrase lives on the phone.
 *
 *  - The phrase is encrypted with an AES-256-GCM key that is created INSIDE the Android Keystore (hardware-backed, StrongBox when the phone has it).
 *    The key can never be read out. On Android 11+ (API 30+) it is bound to the phone's screen lock: every use needs a strong fingerprint / face or
 *    the phone PIN / pattern within the last 30 seconds, enforced by the secure hardware, not only by this app.
 *    On Android 6-10 the key is NOT auth-bound: there the fingerprint / PIN prompt of this app is the only gate (a software check, weaker against
 *    malware or a rooted phone running inside this app's process).
 *  - Only the encrypted blob and the two PUBLIC addresses are saved (SharedPreferences, not backed up: allowBackup is off).
 *  - The phrase is never sent anywhere, never logged, never copied to the clipboard by the app.
 *  - If the phone is wiped or the app is uninstalled, the key is gone (on Android 11+ also when the screen lock is removed): only the written phrase can restore the wallet.
 */
object WalletVault {
    private const val ALIAS = "mudapay_wallet_key"
    private const val PREFS = "mudapay_wallet"
    private const val AUTH_SECONDS = 30

    private fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /* ------------------------------------------------------------ state */
    fun hasWallet(ctx: Context): Boolean = prefs(ctx).contains("blob")

    fun addresses(ctx: Context): WalletAddresses? {
        val p = prefs(ctx)
        val bsc = p.getString("bsc", null) ?: return null
        val tron = p.getString("tron", null) ?: return null
        return WalletAddresses(bsc, tron)
    }

    /** Which signed-in MudaPay account the public addresses were last registered for (so a different account on the same phone registers again). */
    fun isRegisteredFor(ctx: Context, userId: String): Boolean = prefs(ctx).getString("registered_for", null) == userId + ":" + (addresses(ctx)?.bsc ?: "")
    fun markRegistered(ctx: Context, userId: String) { prefs(ctx).edit().putString("registered_for", userId + ":" + (addresses(ctx)?.bsc ?: "")).apply() }

    fun backedUp(ctx: Context): Boolean = prefs(ctx).getBoolean("backed_up", false)
    fun markBackedUp(ctx: Context) { prefs(ctx).edit().putBoolean("backed_up", true).apply() }

    /** null when the phone can protect the wallet, otherwise a message for the member. */
    fun unavailableReason(ctx: Context): String? {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null || !km.isDeviceSecure) return "Set a screen lock (PIN, pattern, fingerprint or face) in your phone settings first. The wallet is protected by it."
        return when (BiometricManager.from(ctx).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set a screen lock (PIN, pattern, fingerprint or face) in your phone settings first. The wallet is protected by it."
            else -> "This phone cannot protect the wallet with its screen lock."
        }
    }

    /* ------------------------------------------------------------ authentication */
    /** Fingerprint / face / phone PIN. [onError] receives "" when the member simply cancelled. */
    fun authenticate(activity: FragmentActivity, title: String, subtitle: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                onError(if (cancelled) "" else errString.toString())
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(authenticators).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb).authenticate(info)
    }

    /* ------------------------------------------------------------ keystore */
    private fun newKey(strongBox: Boolean): SecretKey {
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        // Android 11+: the secure hardware itself demands a strong biometric or the phone PIN before the key works (30 s window).
        // Android 6-10: a phone whose only biometric is "weak" (some face unlock) can pass the prompt but never gets a Keystore auth token,
        // which would lock the member out. So there the key is not auth-bound and the fingerprint / PIN prompt of this app is the gate.
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationRequired(true)
            b.setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
            b.setInvalidatedByBiometricEnrollment(false)
        }
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(b.build())
        return gen.generateKey()
    }

    private fun createKey(): SecretKey {
        // generating with an existing alias replaces the old key, so nothing is deleted before the new key exists
        return try { newKey(true) } catch (e: Exception) { newKey(false) }   // no StrongBox on this phone: normal hardware-backed Keystore
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    /* ------------------------------------------------------------ save / open / remove */
    /**
     * Encrypts and saves the phrase. Call it right after [authenticate] succeeded (the key needs a fresh authentication).
     * Returns null on success or a message for the member.
     */
    fun save(ctx: Context, mnemonic: String): String? {
        return try {
            val addr = WalletCrypto.addressesOf(mnemonic)
            val key = createKey()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            val ct = c.doFinal(WalletCrypto.normalize(mnemonic).toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
            val saved = prefs(ctx).edit().clear().putString("blob", blob).putString("bsc", addr.bsc).putString("tron", addr.tron).commit()   // on disk before the screen says done
            if (saved) null else "The wallet could not be saved on this phone. Check that it has free storage and try again."

        } catch (e: Exception) {
            "The wallet could not be saved securely on this phone. Try again after unlocking your phone."
        }
    }

    /** Decrypts the phrase. Call it right after [authenticate] succeeded. Pair: (phrase, null) or (null, message). */
    fun open(ctx: Context): Pair<String?, String?> {
        return try {
            val blob = prefs(ctx).getString("blob", null) ?: return Pair(null, "No wallet on this phone.")
            val key = existingKey() ?: return Pair(null, "The wallet key of this phone is gone (the phone was reset or its screen lock changed). Restore the wallet with your recovery phrase.")
            val all = Base64.decode(blob, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all.copyOfRange(0, 12)))
            Pair(String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8), null)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            Pair(null, "The wallet key of this phone was invalidated. Restore the wallet with your recovery phrase.")
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            Pair(null, "Please confirm with your fingerprint or phone PIN again.")
        } catch (e: Exception) {
            Pair(null, "The wallet could not be opened. Confirm with your fingerprint or phone PIN and try again.")
        }
    }

    /** Authenticates, then hands the phrase to [use] (and nothing else keeps it). */
    fun withPhrase(activity: FragmentActivity, title: String, subtitle: String, onError: (String) -> Unit, use: (String) -> Unit) {
        authenticate(activity, title, subtitle, onSuccess = {
            val (phrase, err) = open(activity)
            if (phrase == null) onError(err ?: "The wallet could not be opened.") else use(phrase)
        }, onError = onError)
    }

    /** Removes the wallet from THIS phone (key + encrypted phrase + addresses). The funds stay on the blockchain: the phrase restores them. */
    fun remove(ctx: Context) {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        } catch (e: Exception) { /* nothing to delete */ }
        prefs(ctx).edit().clear().apply()
    }
}
