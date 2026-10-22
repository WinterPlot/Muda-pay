# MudaPay Native Android — v2.0.0 (connected)

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

## Not built yet
Map, Explorer, Growth center and Earning potential pages (they stay on the website), account deletion in-app,
Inter font (system font is used; add Inter TTFs in `res/font` and wire them in `Design.kt` if you want an exact match).

## Status
Written without an Android SDK available: the code was reviewed and bracket-checked but NOT compiled or run.
Open in Android Studio, sync Gradle, fix any compile message it reports, then test against a staging copy of the site.

## Build on GitHub
Push to `main`: the **Android build** workflow (`.github/workflows/android.yml`) builds the debug APK.
Download it from the run page → *Artifacts* → `MudaPay-debug-apk`. If the build fails, open the run log
(or the `build-reports` artifact) and send me the first `e:` error line.
Set your site address in `app/src/main/java/io/mudapay/mobile/Config.kt` before building.
