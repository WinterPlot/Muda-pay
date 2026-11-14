# MudaPay for Android

Native Android wallet app (Kotlin + Jetpack Compose) for members in 34 African countries.
Package `io.mudapay.mobile` · version **3.14.1** (versionCode 34) · minSdk 23 · targetSdk 35.

The app talks only to the MudaPay server over HTTPS (`<site>/api/v1`). Every money rule (balances, fees, limits,
rewards, rates) is decided by the server; the app displays what the server returns. Server docs live in the server
package (`README_SERVER.md`, `README_API.md`). The route list used by the app is in `BACKEND_API_CONTRACT.md`.

## Features

**Money**
- Wallet in the member's own currency, history with filters, receipts (image / PDF / WhatsApp), statements.
- Send money (same country and cross-border with a live quote), contacts picker, payment requests, group / split requests.
- Scan to pay and Receive with dot-style QR codes (`mudapay://pay?...`), merchant shop mode with verified business name.
- Deposits: mobile money (Payfonte, Lumicash, Airtel Money), card (Visa / Mastercard via hosted checkout), crypto (NOWPayments).
- Withdrawals: mobile money, crypto, or cash through MudaPay agents and bureaus (with an agent locator map).
- Mandatory 4 to 6 digit transaction PIN for transfers, requests and withdrawals.

**Markets and trading**
- Markets: crypto prices and charts, currency rates with flags and history, "add my coin" search.
- Trade: buy / sell / convert coins in the wallet (market and limit orders, receipts, PDF statements).
- Invest: US stocks and crypto through the Alpaca Broker API (when enabled on the server).

**Earn and learn**
- Weekly login bonus, tasks, official videos, share campaigns, referrals (counted, bonus switched off for now), leaderboard.
- Muda AI coach with text and voice chat, and AI-written share captions.

**Account and security**
- Phone + password and Continue with Google (both can be linked to one account).
- App lock (fingerprint / face / PIN), encrypted token storage, optional certificate pinning, `FLAG_SECURE` on sensitive screens.
- KYC document scan (OpenCV + ML Kit, on device). The member confirms every value; a MudaPay admin verifies the document by eye. Nothing is ever verified automatically.
- Profile photos, e-mail verification, password reset by e-mail code, delete account in the app.

**Experience**
- English and French. Dark by default (midnight blue); light and pure-black themes and accent colours in Settings → Preferences → Appearance.
- Design v6: flat tonal cards, large balance on the page, square action tiles, floating bottom bar with a Scan button, list-style Menu, Profile and Settings (Preferences, Security, Notifications, Account).
- Offline cache and retry queue, Lite / data-saver mode, pull to refresh, home-screen widget.
- Push notifications (FCM, data-only) with per-category switches and background sync after reboot.
- In-app update prompts and share-the-app screen (APK or invite link).

## Crypto Wallet (self-custody, BNB Chain)
Menu > Crypto Wallet. A 12-word BIP-39 phrase is created (or restored) on the phone, encrypted with an Android Keystore key and unlocked with fingerprint / face / phone PIN. Address: BNB Chain `m/44'/60'/0'/0/0` (the same as MetaMask / Trust Wallet for the same phrase). Assets: BNB and USDT (BEP20). Tron is switched off (its code is dormant; see `Pinned` in `WalletCrypto.kt` to switch it on again). The server only sees public addresses and already-signed transactions (`/wallet/*`, see `BACKEND_API_CONTRACT.md`). Code: `WalletCrypto.kt` (keys + signing), `WalletVault.kt` (Keystore), `WalletScreens.kt` (screens). Needs the `org.web3j:crypto:4.8.7-android` dependency (see `app/build.gradle.kts`) and the R8 rules in `proguard-rules.pro`. Test on a small amount first. On Android 6-10 the key is not hardware auth-bound (the in-app fingerprint / PIN prompt is the gate); on Android 11+ the secure hardware enforces it.

## Project layout

```
app/src/main/java/io/mudapay/mobile/   all screens and logic (one file per feature)
  Config.kt                             site address, Google client ID, certificate pins, debug tools switch
  Language.kt                           English / French strings
  Design.kt, AppTheme.kt                design tokens and theme
  Net.kt, Offline.kt                    API client, cache, retry queue
app/google-services.json                Firebase config (push)
app/build.gradle.kts                    versions, signing, dependencies
.github/workflows/                      android.yml (build) and create-keystore.yml (one-time signing key)
```

## Configuration (`Config.kt`)

| Value | Purpose |
|---|---|
| `SITE`, `API_BASE`, `DELETE_URL` | Server address and public delete-account page |
| `GOOGLE_WEB_CLIENT_ID` | Google OAuth Web client ID. Must be identical to `MP_GOOGLE_WEB_CLIENT_ID` on the server. Empty hides the Google buttons |
| `CERT_PINS` | Optional TLS public-key pins. Empty = pinning off |
| `DEBUG_TOOLS` | Shows the second launcher icon "MudaPay Debug". Keep `false` in releases |

### Certificate pinning (optional)
```
openssl s_client -connect <site-host>:443 -servername <site-host> -showcerts </dev/null
# save the ISSUER (intermediate) certificate as ca.pem, then:
openssl x509 -in ca.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
```
Use `sha256/<value>` and always add a second backup pin. A wrong pin blocks every user until they update.

## Build

**Android Studio:** open the project, sync Gradle, run `app`.

**GitHub Actions:** push to `main`. The **Android build** workflow builds the APK and uploads it as the `MudaPay-debug-apk` artifact; the `build-reports` artifact is uploaded on failure.
Add these repository secrets so every build is signed with the same key and push works:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `GOOGLE_SERVICES_JSON`.
No keystore yet? Run **Create signing key (run once)** from the Actions tab, copy the values it prints into the secrets, then delete the artifact.

**Release build:** `gradle :app:assembleRelease` (R8 minify and resource shrinking are on, signed with the `mudapay` key).
The APK targets `arm64-v8a` and `armeabi-v7a` only (OpenCV native libraries). Keep the same keystore forever: Android refuses updates signed with a different key.

## Google sign-in setup

1. Google Cloud Console → **OAuth consent screen**: fill app name, support e-mail and logo, then **publish** the app (in "Testing", only listed test users can sign in).
2. Credentials → **OAuth client ID → Web application**. Put its ID in `Config.GOOGLE_WEB_CLIENT_ID` and in `db/google_config.php` on the server.
3. Credentials → **OAuth client ID → Android**: package `io.mudapay.mobile` and the SHA-1 of the signing key. Create one per key (your CI key, and the Play App Signing key from Play Console → App integrity).
   The CI log prints the APK's SHA-1 in the step **Show the APK's SHA-1**.
4. Test with a real Google account on a real phone with Google Play services.

Troubleshooting: "Google sign-in is not available right now" means a wrong Web client ID or an unregistered SHA-1 (`adb logcat -s MudaPay`). "No Google account was found" means the phone has no Google account.

## Push notifications

FCM data-only messages. The app builds each notification itself, so category switches work and the same event is never shown twice.
Needs `app/google-services.json` (or the `GOOGLE_SERVICES_JSON` secret in CI). Without it the app builds and push stays off.
After a reboot or app update, `BootReceiver` restarts the background checks. On some brands (Xiaomi, Tecno, Infinix) the member must also enable "Autostart" for MudaPay; Settings → Push notifications has a test button and a battery-setting shortcut.

## Offline behaviour

Successful reads (profile, dashboard, tasks, videos, history, withdrawals, notifications, referrals, transfers, deposits) are cached encrypted on the phone and shown when the network fails.
Task answers and video rewards made offline are queued for 72 hours; the server stays the judge. Transfers, requests, deposits and withdrawals are never queued: they need a live rate, PIN and balance check.
Money calls carry an `Idempotency-Key` and are retried only when the request never reached the server, so nothing is executed twice.

## Security notes

- Access token 1 h, refresh token 30 days and rotated on every use; tokens are stored with an Android Keystore AES-GCM key.
- The transaction PIN is never stored on the phone; the server keeps only a hash and locks after 5 wrong tries.
- QR codes only carry a phone number (optional amount / note). The server's name for that number is what the payer confirms; the QR text is never trusted.
- KYC photos and details stay in memory on the phone and go only to the MudaPay server.
- Credentials (keystore, `google-services.json`, signing values, `Config.kt` values) must never be removed or replaced: see `README_AI_NEVER_REMOVE_CREDENTIALS.md`.

## Release checklist

- [ ] `versionCode` is higher than the published build and the APK is signed with the production keystore.
- [ ] Release APK built with `assembleRelease` (the CI workflow produces a debug build, which Google Play refuses).
- [ ] `Config.DEBUG_TOOLS = false`.
- [ ] `SITE` points to the production server; `GOOGLE_WEB_CLIENT_ID` matches the server; the Google OAuth app is published and the production SHA-1 is registered.
- [ ] Server side go-live items done (see `README_SERVER.md`, "Go-live checklist").
- [ ] Upload the APK in the server admin (App updates) and check the download page and the in-app update prompt.

## Release notes

**3.14.x** Dark mode and appearance settings; currency flags served by the server.
**3.12 – 3.13** Markets (crypto charts, currency rates, add-your-own coin), Trade (buy / sell / convert, receipts, statements).
**3.11** KYC document scan; Honeycoin removed; alphabetical country list; crypto-only countries handled automatically.
**3.9 – 3.10** Profile photos, merchant payments and shop mode.
**3.5** Full visual redesign.
**3.0 – 3.4** Cross-border transfers, mandatory transaction PIN, payment requests, group requests, agents and bureaus, phone contacts.
**2.x** Scan to pay and dot QR codes, share-the-app and Muda AI captions, voice chat with Muda AI, Continue with Google, push notifications, app lock, offline mode, send / receive money, crypto deposits.

### Connect external wallet (WalletConnect / Reown AppKit)
Crypto Wallet > Connect external wallet. The member connects MetaMask, Trust Wallet, Rainbow ... and approves BNB / USDT (BEP20) payments inside that wallet app. BNB Chain only (AppKit for Android supports EVM chains only). MudaPay sees only the public address while connected. Code: `ExternalWallet.kt` (self-contained; starts only when the screen opens). Needs the Reown lines in `app/build.gradle.kts`, the JitPack repository in `settings.gradle.kts`, the `mudapay-wc://request` link and wallet `<queries>` in `AndroidManifest.xml`, and the Reown R8 rules. The Reown Project ID is the constant `ExternalWallet.PROJECT_ID` (public id from dashboard.reown.com: replace it when you regenerate it). UNVERIFIED until the first compile: the SDK calls were written from the Reown docs without a compiler.
