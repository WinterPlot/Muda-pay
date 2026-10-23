# MudaPay Native Android — v2.2.0 (connected)

Kotlin + Jetpack Compose. The app now talks to the website through `https://<site>/api/v1`
(see `README_API.md` in the website zip). Set the site address in `app/src/main/java/io/mudapay/mobile/Config.kt`.

## Screens (all live data, no preview/mock content)
Welcome · Login · Register · Activation payment (proof upload) · Home (balance, daily streak, Muda AI tip, referral card, wallet ID) ·
Tasks (open → read → submit) · Videos (YouTube player, watch + like rewards) · Withdraw (rules, referral lock, history) ·
Profile · History (filters, totals, paging) · Share & Earn (campaigns, screenshot review) · Leaderboard · Reward rates ·
Muda AI (chat + saved conversations) · Notifications · Support tickets · Settings (language, password, sign out everywhere) · About.

## Design rules followed (from mudapay.css)
Green #00BF8E / dark #008F6A / blue #0061FF / gold #FFD700 / ink #1E293B / muted #64748B / line #E2E8F0 / bg #F8FAFC / danger #EF4444,
135° green→blue gradient, card radius 22, button radius 16–18, soft card shadow, gradient header with 40 dp bottom radius,
5-tab bottom bar (Home, Videos, Tasks, Withdraw, Profile) with 25 dp top corners, weight-900 headings.

## Security
Tokens are encrypted with an Android Keystore AES-GCM key. Access token 1 h, refresh token rotated on use.
All money rules (rewards, balance, withdrawal limits, timing) are decided by the server; the app only displays them.
Withdrawals send an `Idempotency-Key`, so a retry never pays twice.

## New in 2.1.0
- **Push notifications (FCM)**: payment approved, withdrawal updates, daily bonus ready, streak at risk. Each kind can be switched off in Settings.
  Needs `app/google-services.json` from your Firebase project (or a `GOOGLE_SERVICES_JSON` GitHub secret). Without it the app builds and push stays off.
- **App lock**: fingerprint / face / phone PIN (Settings → App lock): on/off, lock when opening, confirm before withdrawals, auto-lock delay, hide in recent apps.
- **Delete account** inside the app (Settings), plus the web fallback `Config.DELETE_URL`.
- **Withdrawal tracking + receipt**: tap a withdrawal for the status timeline and a shareable receipt.
- **My referrals**: who joined, who activated, what each earned, QR code, WhatsApp share.
- **Architecture**: Navigation Compose (with push deep links), ViewModel for the new referral screen, OkHttp transport, optional certificate pinning.
- The server side of all this is in `BACKEND_API_CONTRACT.md` (section "Added in 2.1.0").

### Certificate pinning (off until you fill `Config.CERT_PINS`)
```
openssl s_client -connect openinvest.alwaysdata.net:443 -servername openinvest.alwaysdata.net -showcerts </dev/null
# save the ISSUER (intermediate) certificate as ca.pem, then:
openssl x509 -in ca.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
```
Use `sha256/<that value>` and add a second backup pin. A wrong pin blocks every user until they update.

## New in 2.2.0 — Continue with Google (mobile app only)
Login and Register screens get a **Continue with Google** button (Google's own button style, untouched "G"). The website has no Google button on purpose.
- First time with a Google account → "One last step": full name, **phone number**, country, optional referral code (MudaPay accounts are phone based). New accounts start unpaid and go to the activation screen, like normal sign-ups.
- Phone already has a MudaPay account → the same screen asks for **that account's password** to link Google to it (nobody can take over an account by knowing only its number).
- Returning Google member → signed in straight away. Banned accounts see the admin's reason, same as phone login.
- Accounts created with Google have no password: Settings shows **Set a password** (no "current password"), and Delete account asks only for DELETE.
- Security: the app asks the server for a one-time nonce, Google signs it into the ID token, the **server** verifies signature / audience / expiry / verified e-mail and burns the nonce (a token can never be replayed). Google accounts are matched by their permanent `sub` id, not by e-mail.
- **Everyone gets both ways to sign in.** Phone-signup members add Google in Settings → *Ways to sign in* → *Link my Google account*; Google members add a password (*Set a password*). Unlinking Google needs the password, and is blocked until a password exists, so nobody can lock themselves out.
- If `Config.GOOGLE_WEB_CLIENT_ID` is not set the buttons simply stay hidden; nothing else changes.

### Google sign-in setup (once)
1. **Google Cloud Console** (project `mudapay`, the same one Firebase uses) → *APIs & Services* → **OAuth consent screen**: fill app name, support e-mail, logo; scopes are the defaults (email, profile, openid). **Publish the app** ("In production"); in "Testing" only 100 listed test users can sign in.
2. *Credentials* → **Create credentials → OAuth client ID → Web application**. Copy its client ID.
   Put it in `Config.GOOGLE_WEB_CLIENT_ID` (app) **and** `MP_GOOGLE_WEB_CLIENT_ID` in the server file `htdocs/db/google_config.php`. Both must be identical.
3. *Credentials* → **Create credentials → OAuth client ID → Android**: package `io.mudapay.mobile` + the **SHA-1** of the key that signs the APK. Make one Android client per key: your CI/debug key, and (for the Play Store) the *App signing key* SHA-1 shown in Play Console → Setup → App integrity.
4. **Easiest (no computer needed):** GitHub → Actions → **Create signing key (run once)** → Run workflow. Open the run: the log shows the SHA-1, and the artifact *MudaPay-signing-key-PRIVATE* holds the 4 secret values to paste in Settings → Secrets → Actions. Keep the repo private, then delete the artifact. Or by hand:
   Stable signing key (important for GitHub builds — otherwise every build gets a new random debug key and Google says "no credentials available"):
```
keytool -genkeypair -v -keystore mudapay.keystore -alias mudapay -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 mudapay.keystore            # copy the output
```
   GitHub → repo → Settings → Secrets → Actions: `KEYSTORE_BASE64` (that output), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`mudapay`), `KEY_PASSWORD`. Keep the keystore file somewhere safe and never commit it.
   Each CI run prints the APK's SHA-1 in the step **Show the APK's SHA-1**; that is the value for step 3.
5. Upload the server files (see `README_API.md`) and test with a real Google account on a real phone (not an emulator without Play Services).

Troubleshooting: *"Google sign-in is not available right now"* in the app = wrong Web client ID, or the APK's SHA-1 is not registered as an Android client (check `adb logcat -s MudaPay`). *"No Google account was found"* = no Google account on the phone.

## Not built yet
Map, Explorer, Growth center and Earning potential pages (they stay on the website), Retrofit (OkHttp is used directly), ViewModels for the older screens,
Inter font (system font is used; add Inter TTFs in `res/font` and wire them in `Design.kt` if you want an exact match).

## Status
Written without an Android SDK available: the code was reviewed and bracket-checked but NOT compiled or run.
Open in Android Studio, sync Gradle, fix any compile message it reports, then test against a staging copy of the site.

## Build on GitHub
Push to `main`: the **Android build** workflow (`.github/workflows/android.yml`) builds the debug APK.
Download it from the run page → *Artifacts* → `MudaPay-debug-apk`. If the build fails, open the run log
(or the `build-reports` artifact) and send me the first `e:` error line.
Set your site address in `app/src/main/java/io/mudapay/mobile/Config.kt` before building.
