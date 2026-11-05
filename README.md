# MudaPay Native Android — v3.11.2

## 3.11.2
- Honeycoin removed (it was not used): no more call to `/honeycoin/*` and no Honeycoin deposit tile. Mobile-money deposits go through Payfonte, Lumicash and Airtel only. The shared deposit screen is now `PayfonteDeposit.kt` (`PayfonteDepositSection`, `PayfonteHistory`).
- Version 3.11.2 (versionCode 30). Works with server v3-15.

## 3.11.1
- Register screen: the country list is now alphabetical (A-Z by name). `Util.kt` only.
- Zimbabwe (and any country without a mobile-money method) is crypto-only for deposits and withdrawals: no app change needed, the Crypto option is added automatically. Needs server v3-13.
- Version 3.11.1 (versionCode 29).

# MudaPay Native Android — v3.9.0 (profile photos)

## 3.11.0 — KYC document scan (OpenCV + ML Kit)

- **Agent desk → ID verification → "SCAN MY DOCUMENT"**: choose National ID / Passport / Driving licence → photo of the front → back (cards, licences) → check the details → selfie → send.
- **OpenCV** (`org.opencv:opencv:4.9.0`) finds the document, crops it, straightens it (perspective warp) and measures blur, brightness, glare, size and angle. Bad photos are refused with a clear reason (after two failed tries the member can still send, flagged for a person to check).
- **Google ML Kit** (`text-recognition` 16.0.1, bundled Latin model: offline, nothing goes to Google) reads the text. Passport / ID-card **MRZ** lines are checked with the ICAO check digits; printed labels (English / French) are read for name, number, birth date, expiry date, issue date, sex, nationality, place of birth, address, licence categories.
- The member **always confirms or corrects** every value and ticks a consent switch. Photos and details stay in memory (no gallery, no cache), the screen is `FLAG_SECURE`, and everything goes to MudaPay's server over HTTPS (certificate pinning applies if `Config.CERT_PINS` is set).
- **Never automatic**: the app only sends photos + suggested values + a quality report. A MudaPay admin verifies the document by eye in `admin_agents.php`. OCR can read a forged document perfectly well, so it is never treated as proof.
- Server side: see `KYC_SETUP.md` in the server patch. The old manual photo buttons are still there under "Or add the photos yourself".
- Build notes: APK is built for `arm64-v8a` + `armeabi-v7a` only (OpenCV native libs); first build downloads OpenCV from Maven Central. Not compiled here: build on GitHub / Android Studio and test on 2-3 real phones, then tune the thresholds at the top of `KycDocScanner.kt`.

## 3.9.0 — profile pictures
A member can now add a profile photo, and the people he deals with see it.
- **Add / change / remove** (Profile tab → tap the round photo, it has a small camera badge): pick a picture from the phone. The app straightens it (camera rotation), crops the centre square and shrinks it to 512 px before sending; the server makes the final 256 px JPEG. Files: `Avatars.kt` (everything new is in this one file).
- **Where the photo shows**: Home top bar, Profile, the member you are about to pay (lookup card + "Send?" confirmation + "Money sent" card), transfer lists (sent / received), contacts that use MudaPay, and — the main point — **payment requests**: the person who receives a request sees the requester's photo on the card, in the details popup and in the "Pay … ?" confirmation, so he knows exactly who is asking. The same photo is shown to the requester when he picks the member to ask.
- Without a photo nothing changes: the round initials are drawn as before.
- **How other members' photos are found**: the app asks `POST /avatars/lookup` with the phone numbers on screen (one request per list, cached). If a server response already carries `avatar` next to a member (`counterparty.avatar`, `recipient.avatar`, `member.avatar`, lookup `avatar`) the app uses it and makes no extra request.
- No new libraries and no new permission (the system picture picker is used). Photos are decoded with the existing `ImageCache`.
- Server: see `PROFILE_PHOTO.md` (new `lib_avatar.php`, 5 routes, admin "REMOVE PHOTO" button). Needs PHP GD (standard on most hosts).
- Version 3.9.0 (versionCode 26). New texts have French translations (`frenchPhoto` in `Language.kt`).

# MudaPay Native Android — v3.5.0 (new look)

## 3.5.0 — full visual redesign (same colours, same theme, same features)
Only the appearance changed: no screen, route, API call or translation key was removed.
- **One design language** (`Design.kt`): flat white cards with a hairline border (20 dp), one button radius (16 dp), lighter type weights, sentence-case labels instead of ALL CAPS (`softCase()` – display only, translation keys untouched), round icon chips, calm hairline dividers.
- **Home**: greeting + avatar bar, one balance card, five quick actions, weekly login bonus card (Mon-Sun), a tidy 2-column "Earn & explore" grid, Muda AI, referral card (copy button inside the link field), wallet ID row.
- **Menu**: 23 separate cards replaced by three grouped lists (Money / Earn & learn / Account & help).
- **Profile**: identity card, two stat tiles, two grouped lists, log out.
- **Tabs** (Videos, Tasks, Withdraw, Profile, Home) share the same large title (`TabHeader`); Tasks and Withdraw show the balance in the shared `BalanceHero` card.
- **Inner screens**: new page header (round back button, one title, hairline). All gradient balance cards now use `Modifier.heroCard()`.
- **Bottom bar**: flat bar with a pill behind the active icon.
- New French strings are in `frenchDesign` (Language.kt).

Kotlin + Jetpack Compose. The app now talks to the website through `https://<site>/api/v1`
(see `README_API.md` in the website zip). Set the site address in `app/src/main/java/io/mudapay/mobile/Config.kt`.

## 2.9.0 — Scan to pay + dot-style QR codes
- **Scan to pay** (`ScanPay.kt`): Home quick action *Scan*, Menu → *Scan to pay*, and a *SCAN QR TO PAY* button on the Send screen. Opens the camera (CameraX), reads the code with ZXing, then opens **Send** with the number (and amount / note when the code carries them) already filled in and the member looked up.
  The member still sees the **name the server returns** and must confirm (and pass the app lock if enabled); nothing is ever paid automatically. The name inside a QR code is never trusted or read.
- Also in the scanner: **pick a QR picture from the gallery**, **torch** button, *Type number* shortcut, and a clear screen when the camera permission is refused or the camera cannot start. Works without Google Play services.
- **Dot-style QR codes** (`DotQr.kt`): round dots instead of squares, rounded corner eyes and a MudaPay badge in the middle. Used by *Receive* (your payment QR), *My referrals* (invite QR) and the shareable image. Error correction level H.
  The dot size and eye shapes were tuned by decoding hundreds of rendered codes with ZXing at different sizes, rotations and blur: dots must stay at full size (0.5 module) and the inner eyes must stay rounded *squares*; round inner eyes scanned worse.
- **Receive** now shows the dot QR (`mudapay://pay?v=1&p=<phone>[&a=<amount>][&n=<note>]`), an optional **amount to request** (the QR then carries it), and **SHARE QR CODE** (a ready-to-post picture with name, number and amount). Old codes that hold only a phone number are still accepted by the scanner.
- No server change. The scanner uses the existing `POST /transfers/lookup` and `POST /transfers` (same limits, idempotency key, verified-email rule and same-country rule as typing the number).
- New permission: `CAMERA` (asked the first time the scanner opens; `uses-feature camera` is optional). New libraries: CameraX 1.3.4. ZXing: if `app/libs/core-3.5.3.jar` exists it is used (offline builds), otherwise `com.google.zxing:core:3.5.3` from Maven.
- Version 2.9.0 (versionCode 11). New texts have French translations.

## 2.8.0 — push coverage (server + app)
Server pushes (FCM, data-only) now also cover: deposit **under review / expired / failed** (`payment_deposit_review|expired|failed`, route `Deposit`), **referral reward** to the inviter (`referral`),
**share approved** and **share rejected** (`credit` / `share_rejected`), **leaderboard prize** and **admin credit** (`credit`). Credit pushes are sent at the end of the request and only if the
notification row was really committed. App side (`Push.kt`): deposit pushes open the Deposit screen and are de-duplicated per reference + status; `credit` / `referral` pushes mark the amount as
already announced so the 90 s / 15 min sync check (`NotifySync`) never shows the same alert twice. The "Rewards & activity" and "Payment updates" switches describe these alerts.

## New in 2.6.0 — Voice chat with Muda AI
- **Mic button** in the Muda AI input bar (it turns into *Send* as soon as you type). Opens a full-screen voice conversation (`VoiceChat.kt`).
- Flow: microphone → Android `SpeechRecognizer` (on the phone) → text → existing `POST /coach/messages` over HTTPS (your PHP server → OpenRouter) → reply text → Android `TextToSpeech` (on the phone) → speaker. **No server change is required**; only text travels over the network.
- The request also carries `"voice": true` and `"lang": "en"|"fr"`; the server may ignore them (see README note below for a short-answer mode).
- Speech language follows the app language (English / Français). **Auto-listen** switch: the mic reopens after each spoken answer (stops by itself on silence, so it never loops). Tap the round button to finish speaking, or to interrupt Muda AI.
- Replies are cleaned before being read aloud (no markdown, links or emoji) and cut into sentence chunks, so long answers are not refused by TextToSpeech.
- Voice questions and answers appear in the normal chat and in *History*, and count against the same daily message quota.
- **No new libraries**: `SpeechRecognizer` and `TextToSpeech` are part of Android. Added only the `RECORD_AUDIO` permission (asked when the mic is first used) and two `<queries>` entries so Android 11+ can see the speech services.
- Needs the Google app / Speech Services on the phone for recognition (online), and a text-to-speech voice for the language (Settings → Text-to-speech).
- Optional server tweak: when `voice` is true, add to the system prompt "Answer in 1–3 short spoken sentences, no markdown, no lists, no links".
- Version 2.6.0 (versionCode 8). All new texts have French translations.

## New in 2.5.0 — Share the app, Muda AI captions, new Muda AI design
- **Share MudaPay app** (Settings → top card, also Menu → *Share the app*, Home referral card): opens a custom share screen instead of Android's plain chooser.
  - Sends **the installed APK** (copied to `cache/shared`, handed over with a `FileProvider` content URI) or **only the invite link** (switch at the top).
  - **Send it with**: the member's installed apps that accept the file, with real icons; WhatsApp, WhatsApp Business, Telegram, Messenger, SMS, Gmail, Bluetooth, Xender, SHAREit and Quick Share come first.
    Below: *All apps* with search, and *More apps…* (Android's own picker) as a fallback. Tapping an app sends the APK + caption straight to it.
  - Play Store installs are split APKs and cannot be sent as one file: the screen then falls back to link-only automatically. GitHub / sideloaded builds send the APK.
  - Friends may need to allow *Install unknown apps* for the app that received the file (a tip is shown on the screen).
- **Muda AI caption (the "legend")**: on the same screen. Pick a **tone** (Friendly, Excited, Professional, Short & punchy) and a **language** (English, Français, Kiswahili), optionally type what *you* like about MudaPay.
  - *Instant* caption (offline, free): `CaptionEngine.kt`, personalised with first name, country and invite link, always ends with the link.
  - **Write it with Muda AI**: the same facts go to the existing `/coach/messages` endpoint (no server change). The reply is cleaned (no markdown) and the invite link is added if the AI forgot it. If Muda AI is offline, busy or out of messages, the instant caption stays.
  - The caption is editable, can be copied, and goes out with the APK or the link.
  - Texts describe MudaPay honestly (learn, tasks, videos, daily bonus, referrals, leaderboard, mobile-money withdrawals) and never promise income; they say rewards depend on activity.
  - Each AI caption counts as one Muda AI message of the day and creates one saved conversation (the server decides the quota).
- **Muda AI redesign** (`CoachScreen.kt`, `AiDesign.kt`): gradient header with online status and messages left, animated orb, greeting with the member's name, tip of the day card, four starter cards (Earn more, Withdrawals, Invite friends, Free lesson), a *Write my share caption* shortcut, chat bubbles with an AI avatar, bold / bullet formatting, animated typing dots, **copy** and **share** buttons under every answer, softer input bar. The Home Muda AI card got the animated orb and a *Write my share caption* button.
- **Fixed in the uploaded 2.4.0 source** (it did not parse): the `L()` translator had lost its body (the second half of the French dictionary was pasted inside it), eleven `L("…", color = …)` calls were missing a parenthesis, several `str(")key")` typos, and one literal `\n` in Settings.
- Version 2.5.0 (versionCode 7). All new texts have French translations.

## New in 2.4.0 — refresh, detailed notifications, restart-proof alerts, new look
- **Refresh**: pull down on any screen (Home, Tasks, Videos, Withdraw, History, Notifications…) to reload balance and data; the Home header also has a refresh button. A failed refresh keeps what is on screen and says "No connection".
- **Notifications that come from what you do** (real system notifications, shown even while the app is open, with sound + heads-up banner):
  withdrawal request (**"You withdrew KSh 5,000"** + method, account, new balance), task / video / like rewards, daily bonus, share approved / rejected / received,
  support ticket, password changed, Google linked / unlinked, saved offline actions sent.
- **Notifications that come from MudaPay**: `NotifySync` looks for new messages (admin credits, referral rewards…) and withdrawal status changes (approved / paid / rejected, with the amount):
  every ~90 s while the app is open, every 15 min in the background (WorkManager), right after a reboot, and right after a withdrawal.
  The server push (FCM) is now **data-only**, so the app builds the notification itself in every state; the same event is never shown twice (de-duplication).
- **After the phone restarts** (or the app is updated) `BootReceiver` restarts the checks, refreshes the push token and runs one check. Android does not let an app open its screen by itself at boot, so MudaPay starts quietly in the background.
  Settings → Push notifications has *Send a test notification* and *Keep alerts reliable* (battery setting; some brands such as Xiaomi, Tecno, Infinix also need "Autostart" switched on for MudaPay).
- Two new alert switches in Settings: *Rewards & activity* and *Account & security*.
- **Design**: soft decorative gradient header, richer cards, gradient buttons, Home quick actions (Withdraw / Tasks / Videos / Invite), time-of-day greeting, floating animated bottom bar, loading skeletons, notification icons with a NEW tag.
- Server files changed (folder `www_push_upgrade`): `push_fcm.php`, `admin.php`, `payment_actions.php`, `cron/push_daily.php`. Withdrawal paid / rejected pushes now carry the amount and method.

## Screens (all live data, no preview/mock content)
Welcome · Login · Register · Home (balance, weekly login bonus, Muda AI tip, referral card, wallet ID) ·
Tasks (open → read → submit) · Videos (official admin-uploaded videos, like/dislike, no rewards) · Withdraw (rules, referral lock, history) ·
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
- **Push notifications (FCM)**: payment approved, withdrawal updates, open-the-app reminder, weekly bonus at risk. Each kind can be switched off in Settings.
  Needs `app/google-services.json` from your Firebase project (or a `GOOGLE_SERVICES_JSON` GitHub secret). Without it the app builds and push stays off.
- **App lock**: fingerprint / face / phone PIN (Settings → App lock): on/off, lock when opening, confirm before withdrawals, auto-lock delay, hide in recent apps.
- **Delete account** inside the app (Settings), plus the web fallback `Config.DELETE_URL`.
- **Withdrawal tracking + receipt**: tap a withdrawal for the status timeline and a shareable receipt.
- **My referrals**: who joined, who made a first deposit, what each earned, QR code, WhatsApp share.
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
- First time with a Google account → "One last step": full name, **phone number**, country, optional referral code (MudaPay accounts are phone based). New accounts are active immediately; there is no activation fee.
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


## Offline mode and data saver (2.3.0)

- `Offline.kt`: connectivity tracking, encrypted on-device cache (`OfflineCache`), retry queue (`PendingQueue`), offline screen (`OfflineState`) and the bottom banner.
- `Api.call` caches successful GETs for /me, /dashboard, /tasks, /videos, /transactions (first page), /withdrawals, /notifications, /referrals, /leaderboard, /campaigns, /settings. When the network fails it returns the saved copy (`ApiResult.cached`) and screens show "showing the copy saved X ago".
- Task answers and video watch/like rewards made offline are queued (kept 72 h), sent when the connection returns, and the server stays the judge (already_completed answers are dropped silently). Withdrawals and payments are never queued.
- Screens that need the internet (Muda AI, Support, Share, Withdraw) show the offline screen and reload by themselves when the network is back.
- Settings > Data saver: low-data mode skips video thumbnails and loads other pictures only on tap.
- Release builds now use R8 minify + resource shrinking (`app/proguard-rules.pro`).


## Market: coin icons and "add my coin" (v3.12.0)
* Coin icons are loaded from the server (`SITE/crypto_icon.php?s=BTC`, see `CryptoIcons.kt`), cached on the phone for 7 days. No icon is bundled in the app.
* Markets > Crypto: search any coin (server `/market/search`), tap `+` to add it to the list (kept on the phone, max 40, sent as `/market/coins?extra=`). `＋ Add coin` chip = popular coins. Added coins can be removed from the coin page.
* Needs the server files `server_update_files_v3` (crypto_icon.php, crypto/, lib_market.php, index.php, .htaccess).

## Dark mode (v3.14.0)
* Settings > Appearance: Automatic / Light / Dark, "Pure black" (OLED), accent colour (6), reset. Kept on the phone (`mudapay_ui`), applied at once. Light = the previous theme, unchanged.
* `AppTheme.kt` (`MudaTheme`) holds the palette. `Green, Ink, Muted, Page, Soft, Line, Hairline, Dark, Danger, Blue...` in `Design.kt` are now getters that follow it: never copy them into a top-level `val`. New tokens: `Panel` (cards, sheets, dialogs; white in light), `DarkFill` (dark buttons with white text), `Amber`, `Tint(0xFF...)` (pastel backgrounds that turn into a deep tint in dark).
* Use `Panel` instead of `Color.White` for a surface. Keep `Color.White` only for text on a coloured fill, QR codes and the receipt image.
* Sign-in screens follow it too: `AuthTheme.s` is the member's auth look adapted by `AuthStyle.forDark()`; `AuthTheme.raw` is what is saved. The palette button on Welcome also has the Automatic / Light / Dark choice.
* The home-screen widget follows it (dark card in dark mode, accent on the buttons) and is redrawn when the setting changes.
* Solid fills that carry white text use `GreenFill`, `BlueFill`, `DangerFill` (PrimaryButton maps Blue / Danger / GreenDark to them); icons on a theme-tone fill use `OnTone`.

## Markets > Currencies: server flags (v3.14.1)
* The currency rows and the currency page use `Flag(cc)` (SITE/flags/xx.png, same as the register screen), no emoji. A currency without a single country shows a grey globe.
* The 8 major currencies need `flags/eu gb ae cn in ca ch jp .png` on the server (`server_update_files_v4`).
