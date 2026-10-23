package io.mudapay.mobile

object Config {
    /** Website root. The API lives at SITE/api/v1 (see README_API.md in the website zip). */
    const val SITE = "https://openinvest.alwaysdata.net"
    const val API_BASE = "$SITE/api/v1"

    /** Public page where a member can request account deletion without the app (also needed for the Play Store listing). */
    const val DELETE_URL = "$SITE/delete-account.php"

    /**
     * Sign in with Google (mobile app only). Paste the OAuth client ID of type "Web application" from Google Cloud Console
     * (the SAME value as MP_GOOGLE_WEB_CLIENT_ID in the server's db/google_config.php). It is a public identifier, not a secret.
     * Left as is, the Google buttons stay hidden and the app works with phone + password only. See README, "Google sign-in".
     */
    const val GOOGLE_WEB_CLIENT_ID = "935330728556-esavap4i0q33lof2ljegips1ntb9oj0h.apps.googleusercontent.com"

    /**
     * Certificate pinning. Put SHA-256 public-key pins here, e.g. "sha256/AAAA...=".
     * Rules: pin the key of the CA that issues your certificate (not the short-lived leaf, which rotates every
     * 60-90 days with Let's Encrypt) and ALWAYS add at least one backup pin. A wrong pin locks every user out
     * until they update the app. Empty list = pinning is OFF. See README for the openssl command.
     */
    val CERT_PINS: List<String> = emptyList()
}
