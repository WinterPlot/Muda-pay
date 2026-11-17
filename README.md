# MudaPay


> **Before publishing on Google Play:** get the D-U-N-S number for Userlab Ltd first. Checklist: `README_DUNS.md`. Full publishing guide: `PLAY_PUBLISHING.md`.

**A mobile wallet and payments platform for Africa and beyond.** Native Android app (Kotlin + Jetpack Compose) backed by a PHP / MySQL server. Members hold a balance in their own currency, send money across 60 countries, deposit and withdraw through mobile money, cards, PayPal, crypto and cash agents, pay merchants by QR code, and keep a self-custody crypto wallet.

| | |
|---|---|
| **Android package** | `io.mudapay.mobile` |
| **App version** | 3.20.8 (`versionCode` 52) · minSdk 23 · targetSdk / compileSdk 36 |
| **Server** | PHP + MySQL 5.7+ / MariaDB 10.3+ (InnoDB, `utf8mb4`) · API at `<site>/api/v1` |
| **Database** | 65 tables, schema only, in `db.sql` |
| **Countries** | 60 (all 54 African countries + United Kingdom, United States, France, Bulgaria, Andorra, Czechia) |
| **App languages** | English, French, Swahili, Kinyarwanda, Hausa, Portuguese, Czech |
| **Operator** | UserLab Ltd |

> **This document is the single source of documentation** for the Android app, the server, the API and the database. It replaces the previous collection of separate README, setup and status files (listed in [Appendix A](#appendix-a-documents-merged-into-this-readme)).

---

## 16 KB page size (Google Play requirement, since 3.20.1)

Google Play requires 64-bit native libraries to support 16 KB memory pages (apps targeting Android 15+). Fixed in 3.20.1: OpenCV 4.12.0, Reown BOM 1.4.8 (16 KB Yttrium), JNA 5.17.0 (forced, the old `libjnidispatch.so` was not aligned), CameraX 1.4.2.
`tools/check_16kb.py` checks an APK/AAB (`python3 tools/check_16kb.py app.apk bundle.aab`) and the GitHub workflow runs it on every build: if it prints `FAIL`, the library name tells which SDK still needs an update. Final proof: upload the AAB to an internal testing track and look at "Memory page size" in Play Console > App bundle explorer.

## Two builds: Google Play and direct (since 3.20.0)

| Build | For | Updates | Permission |
|---|---|---|---|
| `play` | Google Play (upload the **AAB**: `gradle :app:bundlePlayRelease`) | Only through Google Play. The update box says "Update on Google Play" and opens the store page. | No `REQUEST_INSTALL_PACKAGES`, no APK installer code |
| `direct` | Website download page / sideload (`gradle :app:assembleDirectDebug`) | In-app download + install of the APK from your server, as before | Adds `REQUEST_INSTALL_PACKAGES` (from `app/src/direct`) |

Same package name and same signing key for both. `BuildConfig.PLAY_BUILD` tells them apart; the installer is `ApkInstaller` in `src/direct` (real) and `src/play` (empty).
In the admin "App updates", publish the new version on Google Play **before** you announce it (a required or forced update sends Play users to the store page).
GitHub Actions builds both debug APKs and, when the signing secrets exist, the Play AAB.

## Table of contents

1. [Overview](#1-overview)
2. [Repository layout](#2-repository-layout)
3. [Features](#3-features)
4. [Countries and payment rails](#4-countries-and-payment-rails)
5. [Quick start](#5-quick-start)
6. [Server](#6-server)
7. [Android app](#7-android-app)
8. [Database](#8-database)
9. [API reference](#9-api-reference)
10. [Money rules: fees, limits, rates, rewards](#10-money-rules-fees-limits-rates-rewards)
11. [Payment provider integrations](#11-payment-provider-integrations)
12. [Crypto Wallet (self-custody)](#12-crypto-wallet-self-custody)
13. [KYC and identity verification](#13-kyc-and-identity-verification)
14. [Security model](#14-security-model)
15. [Operations](#15-operations)
16. [Go-live checklist](#16-go-live-checklist)
17. [Troubleshooting](#17-troubleshooting)
18. [Credentials and AI-assistant rules](#18-credentials-and-ai-assistant-rules)
19. [Release notes](#19-release-notes)
20. [Known limits and open items](#20-known-limits-and-open-items)
- [Appendix A: documents merged into this README](#appendix-a-documents-merged-into-this-readme)

---

## 1. Overview

MudaPay has two deployable parts that share one contract:

```
 ┌────────────────────────┐   HTTPS / JSON    ┌──────────────────────────────────────────┐
 │  Android app           │ ────────────────► │  PHP server  (<site>)                    │
 │  Kotlin + Compose      │   /api/v1         │                                          │
 │  io.mudapay.mobile     │ ◄──────────────── │  api/v1/     app API (front controller)  │
 └────────────────────────┘                   │  admin*.php  admin panel                 │
        ▲        │                            │  webhooks    Payfonte, NOWPayments,      │
        │ FCM    │ signs crypto               │              Airtel, PayPal              │
        │ push   ▼ transactions locally       │  cron/       scheduled jobs              │
 ┌──────┴─────────────┐                       │  index.php   public download page        │
 │ Firebase (FCM v1)  │                       └───────────────┬──────────────────────────┘
 └────────────────────┘                                       │ PDO
                                                      ┌───────▼───────┐
                                                      │ MySQL (65 tb) │
                                                      └───────────────┘
```

### Design principles

- **The server is the single authority.** Balances, fees, limits, rewards and exchange rates are decided and computed by the server. The app only displays what the server returns, and the server never trusts a balance, reward, rate, duration or status sent by a client.
- **One ledger.** The API, the admin pages and the webhooks all read and write the same database and the same rules.
- **No secrets in the APK.** Payment, AI, push and mail keys live only on the server.
- **Money moves exactly once.** Every money write carries an `Idempotency-Key`; credits are applied inside a single database transaction with row locks; provider callbacks are verified and idempotent.
- **No member website.** Login, wallet and tasks live in the app. The site root is a public download page; Terms and Privacy are shown inside the app.
- **Self-custody crypto.** The crypto wallet is generated and signs on the phone. The server never sees a seed phrase or private key.

---

## 2. Repository layout

The project ships as two packages plus the database schema.

### Android package (`Muda-pay-main/`)

```
Muda-pay-main/
├── README.md                          this document
├── build.gradle.kts                   plugin versions (AGP 8.10.1, Kotlin 2.0.21, Google Services 4.4.2)
├── settings.gradle.kts                repositories (Google, Maven Central, JitPack), module :app
├── gradle.properties
├── .github/workflows/
│   ├── android.yml                    CI: builds the debug APK
│   └── create-keystore.yml            one-time signing-key generator
└── app/
    ├── build.gradle.kts               versions, signing, dependencies
    ├── google-services.json           Firebase config (push)
    ├── proguard-rules.pro             R8 rules (web3j, Reown, ...)
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/io/mudapay/mobile/    84 Kotlin files, one per feature (see §7.9)
        └── res/                       drawables (payment-method logos), mipmaps, XML (shortcuts, widget, assistant)
```

### Server package (`www/`)

```
www/
├── index.php, site_lib.php            public download / landing page
├── api/v1/                            API front controller (index.php) + 32 lib_*.php route libraries
├── admin.php, admin_*.php             admin panel (agents, coach, errors, fx, growth, promo, revenue, updates, wallet)
├── *_lib.php                          domain logic (wallet, agents, fx, kyc, invites, promo, release, ...)
├── payfonte_*.php, np_deposit_ipn.php,
│   airtel_*.php, paypal_*.php,
├── cron/                              push_daily, lumicash_sweep, kyc_purge, invite_expire
├── db/                                live config files (never publish) + knowledge/ (Muda AI knowledge base)
├── db.sql                             complete schema (also supplied separately)
├── countries_extra.php,
│   site_countries.php, flags/         country tables and 96x96 flags
├── users/avatars, avatars, id_docs,
│   official_videos, releases          upload folders (each closed by .htaccess where private)
├── wallet_tests/                      PHP tests and Python signing vectors for the wallet
└── assets/                            logos, screenshots, store icons
```

---

## 3. Features

### Money
- Wallet in the member's **own currency**, history with filters, receipts (image / PDF / WhatsApp) and statements.
- **Send money** inside a country or **cross-border** with a live quote.
- **Send to a non-member** (invitation transfer): the money is held, a link and code are shared, and it is credited when the recipient signs up.
- **Payment requests** and **group / split requests** (equal or custom shares).
- **Scan to pay** and **Receive** with dot-style QR codes (`mudapay://pay?...`); **merchant mode** with a verified business name, shop QR, cashback and a live "shop mode" sound.
- **Deposits:** mobile money (Payfonte, Airtel Money, Lumicash), Visa / Mastercard (hosted checkout), PayPal, crypto (NOWPayments).
- **Withdrawals:** mobile money, crypto (USDT), or **cash through MudaPay agents and bureaus**, with an agent locator map.
- **Mandatory 4 to 6 digit transaction PIN** for transfers, requests and withdrawals.
- **Promo codes, vouchers** and merchant cashback.

### Markets
- Crypto prices and charts (16 built-in coins plus "add my coin"), currency rates with flags and history. Information only: no investment advice, no predictions.

### Crypto Wallet
- Built-in non-custodial wallet on **BNB Chain** (BNB and USDT BEP20) and **Connect external wallet** (MetaMask, Trust Wallet, Rainbow, ... through WalletConnect / Reown AppKit).

### Earn and learn
- Weekly login bonus, tasks, official videos, leaderboard, referrals (counted; bonus currently switched off).
- **Muda AI** assistant with text and voice chat, contextual actions (pre-fills Send, opens screens) and AI-written share captions.

### Account and security
- Phone + password and **Continue with Google** (both can be linked to one account).
- App lock (fingerprint / face / PIN), encrypted token storage, optional certificate pinning, `FLAG_SECURE` on sensitive screens.
- **KYC levels 0 to 2** with limits; on-device document scan (OpenCV + ML Kit). A human admin verifies every document: **nothing is ever verified automatically**.
- Connected devices list and remote sign-out, security log, e-mail verification, password reset by e-mail code, delete account in the app and on a public page.
- Support center: tickets and disputes linked to a transaction, with team hours and Muda AI as the fast answer.

### Experience
- 7 languages; dark by default (midnight blue), light and pure-black themes, accent colours.
- Offline cache and retry queue, Lite / data-saver mode, pull to refresh, home-screen widget, quick-settings tiles, app shortcuts.
- Push notifications (FCM, data-only) with per-category switches and background sync after reboot.
- In-app update prompts and a share-the-app screen (APK or invite link).

### Admin
- Members and withdrawals, support and disputes, agents and bureaus (with ID verification), exchange rates and fees, revenue, promo codes and vouchers, app releases, crypto wallet switches, error logs, growth and Muda AI settings.

---

## 4. Countries and payment rails

MudaPay accepts members from **60 countries**: all 54 African countries plus the United Kingdom, United States, France, Bulgaria, Andorra and Czechia. Every country has sign-up, local currency, a flag, a map position, transfers between countries, crypto deposits and Muda AI knowledge.

| Group | Deposit methods | Withdrawal methods |
|---|---|---|
| **Original 10:** Burundi, DR Congo, Kenya, Tanzania, Uganda, Rwanda, Nigeria, Ghana, South Africa, Zimbabwe | Payfonte mobile money (all except Burundi and Zimbabwe), Lumicash (Burundi), Airtel Money (DR Congo), card, crypto | Mobile money (Payfonte payouts, Airtel, manual for Burundi), crypto, agents / bureaus. **Zimbabwe is crypto-only.** |
| **24 further countries with mobile money:** French-speaking West / Central Africa, Southern and East Africa, Gambia, Sierra Leone, Liberia, Angola | Payfonte mobile money (networks in the table below), card, crypto | Mobile money where a payout exists, crypto, agents |
| **20 further African countries** (Algeria, Egypt, Libya, Morocco, Tunisia, Mauritania, Botswana, Malawi, Mauritius, Ethiopia, Somalia, Djibouti, Comoros, Cape Verde, Guinea-Bissau, Central African Republic, São Tomé and Príncipe, Equatorial Guinea, Eritrea, Sudan) | **Card (Visa / Mastercard) + crypto** | **Crypto only** (`'methods' => []`) |
| **UK, USA, France, Bulgaria, Andorra, Czechia** | PayPal, card, crypto | Crypto (USDT) only for now |

### Mobile-money networks added by Payfonte

**French-speaking West and Central Africa**

| Country | Code | Currency | Deposit networks | Withdrawal minimum |
|---|---|---|---|---|
| Senegal | sn | XOF | Orange Money, Wave, Mixx by Yas | 2,900 CFA |
| Ivory Coast | ci | XOF | Orange Money, MTN MoMo, Moov Money, Wave | 2,900 CFA |
| Cameroon | cm | XAF | MTN MoMo, Orange Money | 2,900 FCFA |
| Mali | ml | XOF | Orange Money, Moov Money | 2,900 CFA |
| Burkina Faso | bf | XOF | Orange Money, Moov Money | 2,900 CFA |
| Benin | bj | XOF | MTN MoMo, Moov Money | 2,900 CFA |
| Togo | tg | XOF | Mixx by Yas, Moov Money | 2,900 CFA |
| Niger | ne | XOF | Airtel Money | 2,900 CFA |
| Guinea | gn | GNF | MTN MoMo, Orange Money | 43,500 FG |
| Congo (Brazzaville) | cg | XAF | Airtel Money, MTN MoMo | 2,900 FCFA |
| Gabon | ga | XAF | Airtel Money | 2,900 FCFA |
| Chad | td | XAF | Airtel Money | 2,900 FCFA |

**Southern and East Africa**

| Country | Code | Currency | Deposit networks | Withdrawal minimum |
|---|---|---|---|---|
| Zambia | zm | ZMW | Airtel Money, MTN MoMo, Zamtel Kwacha | 115 ZK |
| Mozambique | mz | MZN | M-Pesa (Vodacom), e-Mola (Movitel) | 320 MT |
| Madagascar | mg | MGA | Airtel Money | 22,500 Ar |
| Namibia | na | NAD | MTN MoMo | 90 N$ |
| Lesotho | ls | LSL | MTN MoMo | 90 L |
| Eswatini | sz | SZL | MTN MoMo | 90 E |
| Seychelles | sc | SCR | Airtel Money | 70 SR |
| South Sudan | ss | SSP | MTN MoMo | 22,500 SSP |

**Other West Africa and Angola**

| Country | Code | Currency | Deposit networks | Withdrawal minimum |
|---|---|---|---|---|
| Gambia | gm | GMD | Wave, AfriMoney, Airtel Money | 360 D |
| Sierra Leone | sl | SLE | Orange Money | 115 Le |
| Liberia | lr | LRD | MTN MoMo | 950 L$ |
| Angola | ao | AOA | PayPay, Multicaixa Express | 4,600 Kz |

**Original countries (Payfonte, Airtel, Lumicash):** DR Congo (Airtel, Orange, M-Pesa, AfriMoney), Kenya (M-Pesa, Airtel), Tanzania (M-Pesa, Airtel, Mixx by Yas, Halopesa), Uganda (MTN, Airtel), Rwanda (MTN, Airtel), Ghana (MTN, AT, Telecel), Nigeria (Airtel, MTN, OPay, PalmPay), South Africa (MTN). Burundi uses Lumicash.

### Where country data lives

| What | Where |
|---|---|
| The 24 mobile-money countries + the card / crypto-only countries: name (EN/FR), dial code, currency, symbol, reference USD rate, rounding, time zone, map position, national phone length, withdrawal minimum and methods, reward amounts, live-feed names | `countries_extra.php` (one table; edit one entry to change a country) |
| Public site country list, home counts and the `/countries` page | `site_countries.php` (the only file to edit when adding a country) |
| Deposit networks, slugs, limits and automatic payout providers | `db/payfonte_config.php` → `PF_COUNTRIES`, `PF_PAYOUT_PROVIDERS` |
| Original-country definitions | `reward_rates.php`, `price_helper.php`, `fx_lib.php`, `flag.php` (the new table is their fallback) |
| Circular flags (96×96 PNG) | `flags/<code>.png` |

### Adding a local mobile-money network to a card + crypto country

Add the country to `PF_COUNTRIES` in `db/payfonte_config.php` and put the network names in the `methods` of `countries_extra.php`. The app then shows the mobile-money tab by itself.

### Country notes

- Default reference rates in `countries_extra.php` are sanity bounds only; live rates come from the feeds. Review `admin_fx.php`, especially for **SSP, GNF, SLE, LRD, AOA, LYD, ETB, MWK, EGP, SOS, SDG and ERN**, where official and street rates differ a lot. Deposits pause by themselves while two live feeds disagree on a currency.
- Payfonte amounts are sent as `value × 100` (`PF_MINOR`). XOF, XAF and GNF have no decimals: if Payfonte wants whole units for them, set `PF_MINOR` to 1 for those countries.
- Phone formats are built from national lengths in `countries_extra.php`. Ivory Coast (10 digits), Benin (10 digits, prefix 01), Congo-Brazzaville and Gabon keep their leading 0 (`keep0`).
- Angola: PayPay is paid automatically; Multicaixa Express is offered for deposits and withdrawals (withdrawals by hand). Payfonte's bank-transfer flow is not offered.
- Payfonte limits (`min` / `max` / `daily` in `PF_COUNTRIES`) default to about 1 / 1,000 / 3,000 USD per payment / day. Payfonte publishes almost none: adjust to your account.
- Europe: France, Andorra and Bulgaria (euro since 1 January 2026) are charged in EUR 1:1; Czechia pays in USD at the confirmed rate.
- A country without a mobile-money payout (for example Zimbabwe) is crypto-only for deposits and withdrawals, and any registered country without a payout row falls back to crypto-only in `GET /withdrawals/rules`.

---

## 5. Quick start

### Server (about 15 minutes)

1. Upload the contents of `www/` to the site root.
2. Make these folders writable by PHP (`chmod 775`) and keep their `.htaccess` files: `releases/`, `users/avatars/`, `avatars/`, `id_docs/`, `db/`.
3. Create an **empty** database and import `db.sql` once in phpMyAdmin.
4. Fill the config files in `db/` with live values (see [§6.4](#64-configuration-files-in-db)).
5. Create the [cron jobs](#65-cron-jobs).
6. Build and sign the APK, then upload it in **admin → App updates**.

### Android

- **Android Studio:** open the project, let Gradle sync, run the `app` configuration.
- **GitHub Actions:** push to `main`; download the `MudaPay-debug-apk` artifact. Add the signing secrets first ([§7.3](#73-build-and-release)).

---

## 6. Server

### 6.1 Requirements

- Apache with `.htaccess` support (the package relies on it to close private folders; `mod_rewrite` is used to block `db/`).
- PHP with these extensions: `zip` (reads the APK), `gd` (profile photos), `openssl`, `curl`, `pdo_mysql`. No specific PHP version is pinned in the code: use a currently supported release.
- MySQL 5.7+ or MariaDB 10.3+, InnoDB, `utf8mb4`.
- `upload_max_filesize` and `post_max_size` above your APK size. The bundled `.user.ini` sets 200M / 210M and `max_execution_time` 300; set the same in the PHP panel if the host ignores it.
- Outbound HTTPS to Google (OAuth certificates, FCM), the payment providers, the FX feeds, market providers and Have I Been Pwned.
- HTTPS on the site. `MP_API_REQUIRE_HTTPS` stays true unless the host hides HTTPS from PHP.
- Root `.htaccess` denies `*.sql`, `*.bak`, `*.log`, `*.ini`, `*.zip`, `*.md`, all dot-files and the `db/` directory.

### 6.2 Install

1. Upload `www/` to the site root.
2. Set folder permissions (see Quick start, step 2).
3. Create an **empty** database, select it in phpMyAdmin and import `db.sql` **once, before the first visit**. It contains every table and no `CREATE DATABASE`. The PHP code does not create or alter tables.
   If the database already holds tables from an older version, delete them first or use a new database: `CREATE TABLE IF NOT EXISTS` never changes an existing table.
4. Fill the config files in `db/`.
5. Create the cron jobs.
6. Open **admin → App updates** and upload the APK.

**When updating an existing site**, uploading a package does not delete files removed from it. Delete leftovers by hand, for example the retired Honeycoin files: `honeycoin_lib.php`, `honeycoin_webhook.php`, `api/v1/lib_honeycoin.php`, `db/honeycoin_config.php`, and the webhook URL in the Honeycoin dashboard (the old `honeycoin_deposits` table can stay as history).

### 6.3 What is in the package

| Area | Files |
|---|---|
| App API | `api/v1/index.php` (front controller) and `api/v1/lib_*.php` (auth, google, account, member, money, media, pin, transfer, promo, invite, request, group, deposit, lumicash, airtel, payfonte, payfonte_card, paypal, agent, kyc, security, errors, update, avatar, merchant, debug, market, support, wallet, core, password) |
| Admin | `admin.php`, `admin_agents.php`, `admin_coach.php`, `admin_errors.php`, `admin_fx.php`, `admin_growth.php`, `admin_promo.php`, `admin_revenue.php`, `admin_updates.php`, `admin_wallet.php` |
| Webhooks and return pages | Payfonte (`payfonte_webhook.php`, `payfonte_return.php`), NOWPayments (`np_deposit_ipn.php`), Airtel (`airtel_callback.php`), PayPal (`paypal_webhook.php`, `PayPal.php`, `paypal_return.php`) |
| Public pages | `index.php` (download page), `about.php`, `countries.php`, `privacy.php`, `delete-account.php` (the page the app links to), `download.php` |
| Cron | `cron/push_daily.php`, `cron/lumicash_sweep.php`, `cron/kyc_purge.php`, `cron/invite_expire.php` |
| Push | `push_fcm.php` |
| Muda AI | `coach_lib.php`, `coach_actions_lib.php`, `task_ai.php`, `db/knowledge/*.md` (the AI knowledge base: not documentation, do not delete) |

Upload folders: `users/avatars`, `avatars`, `official_videos`, `id_docs`, `releases`.

### 6.4 Configuration files in `db/`

Live config files live in `db/`. They hold **real keys**: never publish the package, never commit them, and keep them out of any zip you share. The directory is closed to the web by `.htaccess`. The `*.example` files are templates only: never copy their placeholders over live files.

| File | Purpose |
|---|---|
| `db.php` | Database host, name, user, password |
| `payfonte_config.php` | Payfonte keys, base URL, countries, payout providers |
| `nowpayments_config.php` | NOWPayments API key and IPN secret, deposit limits |
| `paypal_config.php` | PayPal client ID / secret, environment, webhook ID, limits |
| `airtel_config.php` | Airtel Money credentials and base URL |
| `bitlibera_config.php` | Lumicash (BitLibera) credentials, fund phone, limits |
| `rpc_config.php` | Wallet providers: BNB Chain RPC, TronGrid key, explorer API |
| `alpaca_config.php` | Legacy configuration left from the removed investing feature |
| `fcm_config.php` | Firebase service-account path (push) |
| `kyc_config.php` | KYC encryption key (**back it up outside the site**) |
| `mail_config.php` | E-mail driver (`mail`, `smtp` or `resend`), sender, SMTP |
| `notify_config.php` | Site URL only. Telegram / WhatsApp alerts were removed (3.20.7): nothing about members is sent to a messaging service |
| `google_config.php` | `MP_GOOGLE_WEB_CLIENT_ID` |
| `ai_config.php` | AI provider keys for Muda AI and `task_ai.php` |
| `alert_config.php` | `MP_ALERT_EMAIL` for crash and error e-mails |

Runtime files created in `db/`: `fx_cache.json`, `market_cache/`, `np_min_cache.json`, `paypal_token.json`, `.alert_state.json`.
Switch files: `db/api_debug.flag` (debug, see [§15.3](#153-debug-tools)), `db/pwned_check.off` (skip the online breach check).

### 6.5 Cron jobs

| Schedule | Command | Purpose |
|---|---|---|
| Daily | `php cron/push_daily.php` | Daily bonus and streak reminders (push) |
| Every 5 min | `php cron/lumicash_sweep.php` | Re-check Lumicash deposits and retry fund-account forwards |
| Daily | `php cron/kyc_purge.php 30` | Purge photos and details of IDs never accepted (draft / rejected) after N days |
| Optional | `php cron/invite_expire.php` | Refund expired invitations and forfeit expired held promo / welcome bonuses (the API also does this lazily) |

Use your real account path and a PHP CLI executable, for example `php /home/<account>/www/cron/push_daily.php`.

### 6.6 Public download page

`https://<site>/` is the app download page. It reads everything from the published APK (name, version, build, size, minimum Android, icon, SHA-256 of the file and of its signing certificate), so it cannot disagree with the file. It shows "what's new", install steps, a **Download the app (APK)** button (`/download`) and, when set in admin, a Google Play button. English / French follows the browser. `/?ref=<member id>` shows the referral invite code; `/?inv=<CODE>` shows an invitation card (who sent how much, welcome bonus).

Landing-page implementation notes:

- Files: `index.php`, `site_lib.php` (header, drawer, footer, shared CSS, theme switch), `site_countries.php`, `countries.php`, `about.php`.
- Layout: white page, large rounded cards, one pill download button, a phone mockup rising from each card, brand-green bands, tile grid, footer link columns. Font: Inter (Google Fonts, with system fallback). Brand green `#0B8F6A`.
- Light / dark follows the device; a header button and the menu (System / Light / Dark) override it, saved in `localStorage` (`mp_theme`).
- Screens: `assets/screens/{home,activity,rewards,withdraw}_p.{jpg,webp}` are real app screenshots with 90 px of black at the top for the notch. Replace them when the app design changes (same width and top padding).
- Texts are EN / FR through `tr()`; keep them true to the app.

### 6.7 Publishing a new app version (admin → App updates)

1. Build the APK with a **higher `versionCode`** than the last one, signed with the **same keystore** (the page checks both and tells you).
2. Upload it. Name, version, icon, SHA-256 and certificate are read from the file. Add "what's new" (EN / FR) and choose **Optional** or **Required** (with days left).
3. It is live at once: the download page changes and phones on an older build get the in-app update prompt (`GET /api/v1/app/update`).

Versions can be hidden, published, deleted or re-noted at any time, and the page can be switched to **Google Play only**. Code: `release_lib.php`, `api/v1/lib_update.php`, `admin_updates.php`, table `app_releases`.

### 6.8 Admin panel

Entry point `admin.php`, with sub-pages:

| Page | Purpose |
|---|---|
| `admin.php` | Members (photo with REMOVE PHOTO, ban, delete), pending withdrawals, PAY VIA PAYFONTE / PAY VIA AIRTEL / CHECK STATUS / MARK PAID, Airtel balance, Support & disputes |
| `admin_agents.php` | Agents and bureaus, ID verifications, commission settings |
| `admin_fx.php` | Exchange rates, transfer fee, exchange margin, same-country fee, cross-border switch |
| `admin_revenue.php` | Revenue by source and currency, merchant-fee switch |
| `admin_promo.php` | Promo codes, voucher batches (CSV download), merchant cashback cap |
| `admin_updates.php` | App releases |
| `admin_wallet.php` | Crypto wallet on / off switches, USDT limit, provider **Test connection**, last transactions |
| `admin_errors.php` | Crash and error reports |
| `admin_growth.php`, `admin_coach.php` | Growth tools and Muda AI (coach) administration |

Admin alerts (new tickets and disputes, deposits in review, partial payments) used to be sent through `mp_notify_admin`; since 3.20.7 that function does nothing (Telegram / WhatsApp removed), so watch the admin pages instead. Implement it again (for example by e-mail) in `notify.php` to bring alerts back.

---

## 7. Android app

### 7.1 Stack

| | |
|---|---|
| Language / UI | Kotlin 2.0.21, Jetpack Compose (UI 1.7.6, Material 3 1.3.1), Navigation Compose 2.8.5 |
| Build | Android Gradle Plugin 8.10.1, Gradle 8.11.1 (CI), JDK 17, compileSdk / targetSdk 36, minSdk 23 |
| Network | OkHttp 4.12.0 (HTTPS only, optional certificate pinning) |
| Push | Firebase Cloud Messaging (BOM 33.7.0), data-only messages |
| Auth | Credential Manager 1.3.0 + Google ID 1.1.1, AndroidX Biometric 1.1.0 |
| Camera / QR | CameraX 1.3.4, ZXing core 3.5.3 (decoding and dot-QR drawing; no Google Play services needed) |
| KYC scan | OpenCV 4.9.0 (find, crop, straighten, quality) + ML Kit text-recognition 16.0.1 (bundled Latin model, runs on device) |
| Crypto | web3j `crypto` 4.8.7-android (BIP-39 / BIP-32, signing), Reown AppKit (BOM 1.3.3) + Compose Material 2 + Accompanist 0.34.0 |
| Maps | osmdroid 6.1.20 (OpenStreetMap tiles, no API key) |
| Background / widget | WorkManager 2.9.1, Glance app widget 1.1.1 |
| ABIs | `arm64-v8a`, `armeabi-v7a` only (OpenCV native libraries; keeps the APK about 40 MB smaller). For x86 emulators remove the `abiFilters` line or use an arm64 image. |

If `app/libs/core-3.5.3.jar` exists it is used for ZXing (works offline); otherwise the same version is downloaded from Maven. Never both.

### 7.2 Configuration (`Config.kt`)

| Value | Purpose |
|---|---|
| `SITE`, `API_BASE`, `DELETE_URL` | Server address, API base (`SITE/api/v1`) and the public delete-account page |
| `GOOGLE_WEB_CLIENT_ID` | Google OAuth **Web** client ID. Public identifier, not a secret. Must be identical to `MP_GOOGLE_WEB_CLIENT_ID` on the server. Empty hides the Google buttons |
| `CERT_PINS` | Optional TLS public-key pins. Empty list = pinning off |

Other constants: the Reown project ID is `ExternalWallet.PROJECT_ID` (a public ID from dashboard.reown.com; change that one constant when you regenerate it).

#### Certificate pinning (optional)

```bash
openssl s_client -connect <site-host>:443 -servername <site-host> -showcerts </dev/null
# save the ISSUER (intermediate) certificate as ca.pem, then:
openssl x509 -in ca.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
```

Use `sha256/<value>`, pin the **issuing CA** (not the short-lived leaf, which rotates every 60 to 90 days with Let's Encrypt) and **always add a second backup pin**. A wrong pin blocks every user until they update.

### 7.3 Build and release

**Android Studio:** open the project, sync Gradle, run `app`.

**GitHub Actions** (`.github/workflows/android.yml`): runs on push to `main` / `master`, on pull requests and on manual dispatch. It builds the **debug** APK and uploads it as `MudaPay-debug-apk`; on failure it uploads `build-reports` and prints duplicate classes. The step **Show the APK's SHA-1** prints the signing fingerprint for Google Cloud.

Repository secrets (so every build is signed with the same key and push works):

| Secret | Purpose |
|---|---|
| `KEYSTORE_BASE64` | Signing keystore, base64; written to `app/mudapay.keystore` |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Signing values |
| `GOOGLE_SERVICES_JSON` | Firebase config, written to `app/google-services.json` |

**No keystore yet?** Run **Create signing key (run once)** from the Actions tab (keep the repository private). It refuses to run if `KEYSTORE_BASE64` already exists. Copy the four values from the private download into the secrets, add the printed SHA-1 in Firebase, then delete the artifact (it expires after one day).

**Why a stable key matters:** GitHub runners create a new random debug key on every run, which changes the SHA-1 each build and breaks Google sign-in. Keep the same keystore forever: Android refuses updates signed with a different key.

**Release build:** `gradle :app:assembleRelease` (R8 minify and resource shrinking on, signed with the `mudapay` key). CI produces a **debug** build, which Google Play refuses.

Without `app/google-services.json` the app still builds and push stays off. Without a keystore the build falls back to the default debug key.

### 7.4 Google sign-in setup

1. Google Cloud Console → **OAuth consent screen**: fill app name, support e-mail and logo, then **publish** the app (in "Testing" only listed test users can sign in).
2. Credentials → **OAuth client ID → Web application**. Put its ID in `Config.GOOGLE_WEB_CLIENT_ID` and in `db/google_config.php` (`MP_GOOGLE_WEB_CLIENT_ID`) on the server.
3. Credentials → **OAuth client ID → Android**: package `io.mudapay.mobile` and the SHA-1 of the signing key. Create one per key (your CI key, and the Play App Signing key from Play Console → App integrity).
4. Test with a real Google account on a real phone with Google Play services.

Server side needs PHP `openssl` + `curl` (or `allow_url_fopen`) to download Google's public certificates (cached in `admin_settings`). Empty `MP_GOOGLE_WEB_CLIENT_ID` turns the feature off (`503 google_unavailable`).

### 7.5 Push notifications

FCM **data-only** messages. The app builds each notification itself, so category switches work and the same event is never shown twice (de-duplicated by reference + status). Needs `app/google-services.json` (or the `GOOGLE_SERVICES_JSON` secret in CI).

After a reboot or app update, `BootReceiver` restarts the background checks. On some brands (Xiaomi, Tecno, Infinix) the member must also enable "Autostart" for MudaPay; **Settings → Push notifications** has a test button and a battery-setting shortcut.

### 7.6 Offline behaviour and resilience

- Successful reads (profile, dashboard, tasks, videos, history, withdrawals, notifications, referrals, transfers, deposits) are cached **encrypted** on the phone and shown when the network fails.
- Task answers and video rewards made offline are queued for **72 hours**; the server stays the judge.
- Transfers, requests, deposits and withdrawals are **never queued**: they need a live rate, PIN and balance check.
- Money calls carry an `Idempotency-Key` and are retried (up to 3 times) only when the request never reached the server, always with the same key, so nothing executes twice. Reads are retried once.
- **Lite / data-saver mode** (Settings → Lite mode): skips video thumbnails, loads pictures on tap, decodes smaller images. A slow connection offers it once.

### 7.7 Permissions and components

| Permission | Used for |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | API access, offline detection |
| `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` | Push and background sync after reboot |
| `USE_BIOMETRIC` | App lock and wallet unlock |
| `CAMERA` | Scan to pay, KYC document scan |
| `RECORD_AUDIO` | Voice chat with Muda AI |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Agent locator |
| `REQUEST_INSTALL_PACKAGES` | Installing the downloaded update APK |
| `VIBRATE` | Haptics |

Components: `MainActivity`, Muda AI assistant services (`MudaAssistService`, `MudaAssistSessionService`, `MudaAssistRecognitionService`, `AssistActivity`), `MudaFcmService`, `MudaWidgetReceiver`, quick-settings tiles (`SendTile`, `ScanTile`, `ReceiveTile`, `PrivacyTile`), `BootReceiver`.
Deep links: `mudapay://pay` (QR payments), `mudapay-wc://request` (external wallet return), `geo:` (map handoff).

### 7.8 Localization and theming

- Languages: `en`, `fr`, `cs`, `ha`, `pt`, `sw`, `rw` (`AppLanguage` in `Language.kt`). French and English live in `Language.kt`; Czech, Hausa, Portuguese, Swahili and Kinyarwanda have per-language tables (`TrCs`, `TrHa`, `TrPt`, `TrSw`, `TrRw`) read through `TranslationStore`.
- Themes: dark by default (midnight blue), light, pure black, accent colours (Settings → Preferences → Appearance). Design v6: flat tonal cards, large balance, square action tiles, floating bottom bar with a Scan button, list-style Menu, Profile and Settings (Preferences, Security, Notifications, Account).
- Design tokens: `Design.kt`, `AppTheme.kt`, `AuthTheme.kt`.

### 7.9 Screen-to-file map

| Area | Files |
|---|---|
| Entry, routes, theme | `MainActivity.kt`, `MudaApp.kt`, `Config.kt`, `Design.kt`, `AppTheme.kt`, `AuthTheme.kt`, `Language.kt`, `TranslationStore.kt` |
| Network, cache, retry | `Net.kt`, `Offline.kt`, `ErrorReporter.kt`, `DebugLog.kt` |
| Auth | `AuthScreens.kt`, `GoogleAuth.kt`, `ForgotPassword.kt`, `EmailVerify.kt` |
| Home, profile, settings | `HomeScreens.kt`, `SettingsExtras.kt`, `SecurityScreens.kt`, `AppLock.kt`, `Avatars.kt`, `LegalScreens.kt`, `UpdateScreens.kt`, `AppUpdate.kt` |
| Send / receive | `TransferScreens.kt`, `InviteScreens.kt`, `DetailSheet.kt`, `Receipt.kt`, `Statement.kt` |
| Requests and groups | `RequestScreens.kt`, `GroupScreens.kt` |
| Scan / QR | `ScanPay.kt`, `DotQr.kt` |
| Merchant | `Merchant.kt` |
| PIN | `TransactionPin.kt` |
| Deposits | `DepositScreens.kt`, `PayfonteDeposit.kt`, `AirtelDeposit.kt`, `LumicashDeposit.kt`, `CryptoPay.kt`, `PayLogos.kt`, `CryptoIcons.kt`, `MoneyScreens.kt` |
| Withdrawals and agents | `WithdrawalScreens.kt`, `AgentScreens.kt`, `AgentLocator.kt` |
| KYC | `KycScreens.kt`, `KycDocScanner.kt`, `KycOcr.kt` |
| Markets | `MarketScreens.kt` |
| Crypto wallet | `WalletCrypto.kt`, `WalletVault.kt`, `WalletScreens.kt`, `WalletExtras.kt`, `ExternalWallet.kt` |
| Earn | `TaskVideoScreens.kt`, `SocialScreens.kt`, `PromoScreens.kt`, `ReferralScreens.kt`, `ShareApp.kt`, `CaptionEngine.kt` |
| Muda AI | `CoachScreen.kt`, `VoiceChat.kt`, `Assistant.kt`, `AiDesign.kt`, `CloudOrb.kt`, `SpinKit.kt` |
| Support | `SupportScreens.kt` |
| Push, sync, widget, tiles | `Push.kt`, `Notify.kt`, `NotifySync.kt`, `Widget.kt`, `QuickTiles.kt`, `TxSound.kt` |
| Utilities | `Util.kt`, `MudaIcons.kt`, `TrCs.kt`, `TrHa.kt`, `TrPt.kt`, `TrRw.kt`, `TrSw.kt` |

### 7.10 Security notes (app)

- Access token 1 h; refresh token 30 days, rotated on every use. Tokens are stored with an Android Keystore **AES-GCM** key.
- The transaction PIN is never stored on the phone; the server keeps only a hash and locks after 5 wrong tries.
- QR codes carry only a phone number (optional amount / note / shop code). The **server's** name for that number is what the payer confirms; the QR text is never trusted.
- KYC photos and details stay in memory on the phone and go only to the MudaPay server.
- `FLAG_SECURE` blocks screenshots and screen recording on the crypto-wallet screens, and app-wide when the app-lock "hide screen" option is enabled.

---

## 8. Database

`db.sql` is the **only** place where tables are defined: **65 tables**, schema only, no data, no `CREATE DATABASE`. Requires MySQL 5.7+ / MariaDB 10.3+ with InnoDB and `utf8mb4`. Import it once into an empty database **before the first visit**.

Older documents say some tables are "created automatically on first request". That behaviour no longer applies: the schema in `db.sql` is complete and the PHP code does not create or alter tables.

| Domain | Tables |
|---|---|
| Members and account | `users`, `user_languages`, `user_emails`, `user_pins`, `password_resets`, `deleted_accounts`, `member_avatars`, `user_location_settings`, `user_locations`, `location_consent_log` |
| Sessions and security | `api_tokens`, `api_idempotency`, `api_starts`, `api_devices`, `account_security_log`, `api_google_links`, `api_google_nonces`, `api_google_pending` |
| Ledger | `transactions`, `transfers`, `pending_transfers`, `payment_requests`, `payment_request_groups`, `withdrawals`, `platform_revenue`, `referral_rewards` |
| Deposits | `crypto_deposits`, `payfonte_deposits`, `payfonte_payouts`, `payfonte_card_deposits`, `paypal_deposits`, `airtel_deposits`, `airtel_payouts`, `lumicash_deposits` |
| Crypto wallet | `wallet_addresses`, `wallet_txs` |
| Agents and merchants | `agents`, `merchant_profiles` |
| KYC | `id_verifications`, `id_verification_details`, `id_verification_audit` |
| Promotions and earning | `promo_codes`, `promo_redemptions`, `surveys`, `survey_completions`, `daily_checkins`, `leaderboard_winners`, `official_videos`, `video_reactions`, `video_comments` |
| Support and notifications | `support_tickets`, `support_messages`, `feedbacks`, `notifications` |
| Muda AI | `ai_conversations`, `ai_messages`, `ai_lesson_progress` |
| Admin and platform | `admin_settings`, `admin_actions`, `app_releases`, `error_logs`, `mp_push_cron_runs` |

Settings editable without code live in `admin_settings` (for example `fx_rates_json`, `fx_fee_pct`, `fx_spread_pct`, `fx_domestic_fee_pct`, `fx_enabled`, `kyc_limits_json`, `kyc_limits_enabled`, `ai_extra_knowledge`, `support_start`, `support_end`, `support_days`, `support_tz`, `support_force_away`, `support_reply_time`, `agent_request_ttl_h`, `agent_daily_mult`, `wallet_tron_enabled`).

Useful queries:

```sql
-- Lumicash deposits and forwarding state
SELECT reference, amount_bif, sweep_status, sweep_note FROM lumicash_deposits WHERE credited=1 ORDER BY id DESC;

-- Welcome-bonus cost already paid, per receiver country (currency of that country)
SELECT receiver_country, SUM(bonus_amount) FROM pending_transfers WHERE bonus_status='released' GROUP BY receiver_country;

-- Money currently held for invitations
SELECT sender_country, SUM(amount) FROM pending_transfers WHERE status='pending' GROUP BY sender_country;
```

---

## 9. API reference

Base URL: `https://<site>/api/v1`. The app uses only this API.

### 9.1 Conventions

- **HTTPS only**, JSON (multipart for uploads).
- Success: `{"ok":true,"data":…}`. Error: `{"ok":false,"error":{"code":"…","message":"…"}}`.
- **Auth:** `Authorization: Bearer <access_token>` (1 h). Renew with `POST /auth/refresh` (refresh token 30 d, **rotated on every use**; re-using an old refresh token signs the member out everywhere). Only SHA-256 hashes of tokens are stored. If a host strips the header, send the same token in `X-MP-Token`.
- **Idempotency:** money writes need `Idempotency-Key: <16-64 chars>`; a retry with the same key returns the first answer (`400 idempotency_key_required` otherwise).
- **PIN:** transfers, requests, group payments and withdrawals also need the transaction `pin`.
- Sign-in bodies (`/auth/login`, `/auth/google*`) also carry `device_id`, `platform` and `app_version`.
- Every account is active as soon as it is created (no activation fee).
- Provider failures on market routes answer HTTP **424** `market_unavailable` (not 5xx, so they do not flood error e-mails).

#### Optional server constants (define before `lib_core.php` loads, or edit its defaults)

`MP_API_REQUIRE_HTTPS` (default true; if the host hides HTTPS you get `426 https_required`), `MP_API_ACCESS_TTL`, `MP_API_REFRESH_TTL`, `MP_API_TASK_MIN_SECONDS`, `MP_API_VIDEO_MIN_SECONDS`.

### 9.2 Route index

| Area | Routes |
|---|---|
| Auth | `POST /auth/register` · `/auth/login` · `/auth/refresh` · `/auth/logout` · `/auth/password/forgot` · `/auth/password/reset` |
| Google | `POST /auth/google/nonce` · `/auth/google` · `/auth/google/complete` · `/google/link` · `/google/unlink` |
| Member | `GET /me` · `GET /dashboard` · `POST /daily-bonus/claim` · `GET/PATCH /settings` · `POST /settings/password` · `POST /devices` · `POST /account/delete` |
| PIN and e-mail | `GET/POST /account/pin` · `POST /account/pin/change` · `POST /account/pin/disable` (always 403) · `GET /account/email` · `POST /account/email/send` · `POST /account/email/verify` |
| Security | `GET /account/security` · `GET /account/devices` · `POST /account/devices/revoke` · `POST /account/devices/revoke-others` · `GET /account/limits` |
| Profile photo | `GET/POST /account/avatar` · `POST /account/avatar/remove` · `POST /avatars/lookup` · `GET /avatar/{token}` |
| History | `GET /transactions?filter=all\|earnings\|withdrawals\|transfers&cursor=` · `GET /notifications` · `POST /notifications/{id}/read` · `POST /notifications/read-all` |
| Transfers | `GET /transfers/rules` · `POST /transfers/lookup` · `/transfers/quote` · `/transfers` · `GET /transfers?direction=all\|in\|out&cursor=` |
| Invitations | `POST /transfers/invite` · `GET /transfers/invites` · `POST /transfers/invites/{id}/cancel` · `POST /transfers/claim` |
| Requests | `GET/POST /requests` · `POST /requests/{id}/pay` · `/decline` · `/cancel` |
| Groups | `GET/POST /groups` · `GET /groups/{id}` · `POST /groups/{id}/remind` · `/cancel` |
| Merchant | `GET/POST /merchant/me` · `POST /merchant/remove` · `POST /merchant/lookup` |
| Promo | `POST /promo/redeem` · `GET /promo/history` |
| Deposits (crypto) | `GET /deposits/rules` · `POST /deposits/quote` · `POST /deposits` · `GET /deposits` · `GET /deposits/{id}` |
| Deposits (other) | `/payfonte/*` (mobile money and card checkout), `/airtel/*`, `/lumicash/*`, `/paypal/*` |
| Withdrawals | `GET /withdrawals/rules` · `GET/POST /withdrawals` · `POST /withdrawals/{id}/cancel` |
| Agents | `POST /agents/lookup` · `GET /agents/bureaus` · `GET /agents/nearby` · `GET /agent/me` · `POST /agent/apply` · `/agent/availability` · `/agent/location` · `GET /agent/requests` · `POST /agent/requests/{id}/accept\|decline\|confirm` |
| ID verification | `GET /account/id` · `POST /account/id/upload?part=front\|selfie` · `/account/id/back` · `/account/id/details` · `/account/id/submit` |
| Earn | `GET /tasks` · `POST /tasks/{id}/start\|complete` · `GET /videos` · `POST /videos/{id}/reaction` · `GET /leaderboard?period=month\|all` · `GET /referrals` |
| Muda AI | `GET /coach/context` · `GET /coach/conversations[/{id}]` · `POST /coach/messages` |
| Support | `GET/POST /support/tickets` · `GET /support/tickets/{id}` · `POST /support/tickets/{id}/messages` · `/close` · `GET /support/config` |
| Market | `GET /market/coins` · `/market/ticker` · `/market/candles` · `/market/search` · `/market/fx` · `/market/fx/history` · `/market/status` |
| Crypto wallet | `GET /wallet/config` · `POST /wallet/address` · `GET /wallet/balances` · `POST /wallet/prepare` · `POST /wallet/broadcast` · `POST /wallet/track` · `GET /wallet/tx` · `GET /wallet/history` |
| App updates (public) | `GET /app/update` · `GET /app/download/{id}` |
| Errors | `POST /client/errors` (public, throttled) · `GET /debug/errors` (debug switch only) |

### 9.3 Authentication and account

- `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`. Login shares the website's brute-force limits. Register accepts `invite` (see invitations).
- **Google sign-in** (mobile only): `POST /auth/google/nonce`, `/auth/google`, `/auth/google/complete` (takes `invite` too), `/google/link`, `/google/unlink`. Every member can use phone + password **and** Google. Tables `api_google_links`, `api_google_nonces`, `api_google_pending`.
- `GET /me` returns balance data plus `email`, `email_verified`, `pin_required` / `pin_enabled`.
- `GET /dashboard` — totals computed by the server. `POST /daily-bonus/claim`: `claimed` is true only when the **weekly** bonus was just paid (Sunday, after 7 days in a row); `checked_in` is true when today's check-in was just recorded. `daily_bonus` carries `week_days` (Mon..Sun: `done|missed|today|upcoming`), `week_day`, `week_count`, `on_track`, `paid`, `reward`.
- `POST /account/delete` `{password, confirm:true}` (Google-only accounts: `{confirm:true}`). Wrong password = `422`. Refused with `409` while a withdrawal is pending / approved or a payment is under review. The account is archived (like admin.php), then deleted. A public page (`delete-account.php`) offers the same without the app.
- `POST /devices` registers the phone's FCM token and notification choices (`api_devices`); dropped on sign-out, ban or "sign out everywhere".
- **Tasks:** `start` records "opened at"; a completion earlier than `MP_API_TASK_MIN_SECONDS` is rejected. Videos are official videos uploaded in admin (no rewards); `POST /videos/{id}/reaction {reaction: like|dislike|none}` returns new counts.

#### Password policy

New passwords (`/auth/register`, `/auth/password/reset`, `/settings/password`) must have **10 to 72 bytes**, must not be a simple sequence or a very common password (including substitutions like `P@ssw0rd`), must not contain the member's phone number or name, and must not appear in a known breach (Have I Been Pwned *Pwned Passwords*, k-anonymity: only the first 5 characters of the SHA-1 are sent). Refusals: `422 weak_password` (`breached: true` for the breach check). Existing shorter passwords still log in. If the host blocks outgoing HTTPS, create an empty `db/pwned_check.off` to skip the online check; if HIBP is unreachable the online check is skipped, never blocking a sign-up. Code: `api/v1/lib_password.php`.

#### Password reset

`POST /auth/password/forgot {identifier}` (phone or verified e-mail) sends a 6-digit code (10 min, 60 s between codes, 6 per hour) to the **verified** e-mail and always answers `{sent:true, expires_in, resend_in, code_length}` whether or not an account matches (no enumeration). `POST /auth/password/reset {identifier, code, new_password}` checks the code (5 wrong tries lock it, single use), sets the password, signs the member out everywhere, adds a notification and e-mails a security notice. Every failure answers `invalid_code` 422 with the same text.

#### E-mail verification

`GET /account/email`, `POST /account/email/send {email}` (6-digit code, 10 min, 60 s between codes, 6 per hour; changing the address un-verifies it), `POST /account/email/verify {code}` (5 wrong tries lock the code). One verified e-mail can belong to **one** account. Mail settings: `db/mail_config.php` (driver `mail` | `smtp` | `resend`, optional `MP_TRANSFER_REQUIRE_VERIFIED_EMAIL`).

#### Transaction PIN (mandatory, 4 to 6 digits)

Members create the PIN in the app (a dialog on sign-in; also Settings → Transaction PIN) and can change it but never turn it off. Stored as a `password_hash` in `user_pins`. Until a PIN exists, money calls fail with `403 pin_setup_required`.

| Route | Notes |
|---|---|
| `GET /account/pin` | `{enabled, mandatory:true, min:4, max:6, can_use_password, locked_for}` |
| `POST /account/pin {pin, password}` | Create the first PIN; `password` required when the account has one (`422 password_required`). Rejects repeated digits (1111) and plain sequences (1234). `409 pin_exists` if already on |
| `POST /account/pin/change {pin, current_pin \| password}` | `password` = "I forgot my PIN" (accounts that have a password) |
| `POST /account/pin/disable` | Always `403 pin_mandatory` (kept so old app versions get a clear error) |

Errors on money routes: `pin_required` 403, `wrong_pin` 403 (`tries_left`), `pin_locked` 429 (`retry_in` seconds). **5 wrong PINs lock the check for 15 minutes**, counted on the server (a new device or reinstall does not reset it). Google-only accounts without a password cannot use the "forgot PIN" path.

#### Connected devices and account security

Each login is a session with a stable `session_id` kept through token refreshes. `GET /account/devices` lists them (`name, platform, app_version, ip (masked), first_seen, last_seen, current`); `POST /account/devices/revoke {session_id}` signs one out (not the current one: `422 current_device`); `POST /account/devices/revoke-others` signs out the rest. Signing in from a new device creates a notification, a push and an `account_security_log` entry. Old app versions (no `device_id`) are recognised by model name. `GET /account/security` returns `email_verified, pin_enabled, google_linked, has_password, kyc_level, kyc_level_name, id_status, devices, checks[], score, score_max, events[]`.

### 9.4 Transfers

All routes need a signed-in member.

| Route | Notes |
|---|---|
| `GET /transfers/rules` | `{symbol, currency, country, min, daily_limit, sent_today, remaining_today, balance, my_phone, require_verified_email, email, email_verified, cross_border, fee_pct, countries:[{code,name,dial,symbol,currency}]}` |
| `POST /transfers/lookup {phone, country?}` | Masked data only (`{found, name:"Jane D.", phone:"••••421", country, symbol, currency, can_receive, same_currency, cross_border_allowed}`), 30 lookups / 15 min. `country` = receiver's country for national numbers; a `+…` / `00…` number is read from its dial code and wins. If not found it also returns `invite:{allowed, ttl_days, bonus, bonus_symbol, bonus_min_send}` (null when sending to non-members is off) |
| `POST /transfers/quote {country, amount}` | `{cross_border, amount, fee, fee_pct, rate, receive_amount, net_amount, rate_updated, spread_pct, spread, mid_rate, send_symbol, receive_symbol, …}` — what the receiver gets. Shown for same-country transfers too |
| `POST /transfers {phone, amount, country?, note?, expected_receive?, pin}` + `Idempotency-Key` | Instant. `amount` is in the **sender's** currency; the receiver is credited `receive_amount` in hers. If `expected_receive` differs from the server's figure → `409 rate_changed` with a fresh `quote`. Both balances change in **one** database transaction (rows locked in id order). Receiver gets an in-app notification and an FCM push (`payment_transfer_in`) |
| `GET /transfers?direction=all\|in\|out&cursor=` | History. Items carry `amount` / `symbol` in the **caller's** currency, `counter_amount` / `counter_symbol` for the other side, `cross_border`, `fee`, `cashback`, `merchant_fee` (incoming rows), `counterparty` (`"Jane D."`, masked phone, `country`, `avatar`, `verified`) |

Errors: `recipient_not_found` 404, `self_transfer`, `recipient_unavailable`, `different_currency` (only when admin paused cross-border), `unsupported_country`, `transfers_unavailable`, `rate_changed` 409, `below_minimum`, `daily_limit`, `insufficient_balance`, `email_not_verified` (only if enabled), `kyc_limit_tx|day|month`.

**Rules**
- Members can send to any member in a supported country. Each side keeps its own currency: the sender is debited `amount`; the receiver is credited `amount − fee`, converted at the admin rate.
- **Minimum transfer** = 1 % of the *sender's* country withdrawal minimum; **daily cap** = 20 × that minimum, both in the sender's currency.
- Phone matching ignores spaces, `+`, `-`, `()`; it tries the national form, the form with a leading 0 and the forms with the receiver country's dial code, only among members of the receiver's country.
- Transfers are not counted as "earned" anywhere. `transactions.type` includes `transfer_in` / `transfer_out`.

### 9.5 Send to a non-member (invitation transfer)

A member types a number that is not on MudaPay → **Send anyway**. Amount + the usual fee are taken from his balance and **held** in `pending_transfers` (rate and fee locked; nobody is credited yet). He gets a link `https://<site>/?inv=MPXXXXXXXX` and the code to share. The newcomer opens the page, installs the app, creates an account **with that number and country**, types the code in the *Referral or invite code* field → the held amount is credited (shown as a normal received transfer) and a **welcome bonus is reserved**. The sender is notified and automatically becomes his referrer. An existing member can use *Receive → Claim an invitation*. Not claimed within `MP_INVITE_TTL_DAYS`, or cancelled → everything (fee included) goes back.

| Route | Notes |
|---|---|
| `POST /transfers/invite {phone, amount, country?, note?, expected_receive?, pin?}` + `Idempotency-Key` | Number must not belong to any member (`409 recipient_is_member`). Returns `{id, code, link, status, amount, receive_amount, receive_symbol, expires_at, days_left, ttl_days, balance, bonus_offer:{amount, symbol, eligible, min_send}}`. Same checks as a transfer (minimum, KYC / daily limits — **held money counts** —, PIN, e-mail) |
| `GET /transfers/invites` | `{items:[{id, code, link, status: pending\|claimed\|refunded\|expired, phone, amount, symbol, receive_amount, days_left, bonus, …}], open, claimed, held, ttl_days}` (also refunds expired ones) |
| `POST /transfers/invites/{id}/cancel` | Gets the held money back at once (only while unclaimed) |
| `POST /transfers/claim {code}` | Existing member claims money sent to his number (registered phone **and** country must match) → `{claimed, amount, symbol, bonus, bonus_held, bonus_days, bonus_min_deposit, from, reference, balance}` |
| `POST /auth/register {…, invite}` | After creating the account the invitation is claimed; the answer has `invite:{claimed:true, amount, symbol, bonus, from}` or `invite:{claimed:false, code, message}` (a wrong code never blocks registration) |

Errors: `invites_unavailable`, `recipient_is_member`, `already_invited`, `too_many_open`, `rate_changed`, `invalid_invite`, `invite_already_claimed`, `invite_expired`, `wrong_phone`, `wrong_country`.

**Settings** (constants in `reward_rates.php` / `invite_lib.php`):

| Constant | Default | Meaning |
|---|---|---|
| `MP_INVITE_ENABLED` | true | Master switch |
| `MP_INVITE_TTL_DAYS` | 14 | Days the money is held |
| `MP_INVITE_MAX_OPEN` | 10 | Unclaimed invitations per sender |
| `MP_INVITE_BONUS_USD` | 0.25 | Welcome bonus paid **by MudaPay**, converted to the newcomer's currency (0 = none) |
| `MP_INVITE_BONUS_MIN_USD` | 2 | Minimum value sent for the bonus to apply |
| `MP_INVITE_BONUS_MAX_PER_SENDER_30D` | 5 | Bonuses one sender can trigger per 30 days (reserved + paid) |
| `MP_INVITE_BONUS_MIN_DEPOSIT_USD` | 1 | The newcomer's first deposit must be worth at least this to release the bonus (0 = any) |
| `MP_INVITE_BONUS_HOLD_DAYS` | 30 | Days after claiming to make that deposit; later the bonus is forfeited |

**Bonus lifecycle.** At claim the bonus is only *reserved* (`pending_transfers.bonus_status = 'held'`, `bonus_deadline`). `mp_credit_user()` calls `mp_inv_on_deposit()` for every deposit (Lumicash, Airtel, mobile money, card, PayPal, crypto): the first deposit worth at least `MP_INVITE_BONUS_MIN_DEPOSIT_USD` before the deadline pays it once (`released`, `admin_credit` row, notification + push). Past the deadline it becomes `forfeited`.

**Accounting.** The sender's debit is a `transfer_out` ("on hold"); a refund is a `transfer_in` ("Refund: invitation…"); the claim writes a normal `transfers` row (fee / spread revenue is recorded at claim time); the bonus is an `admin_credit` row "Welcome bonus…" written when released.

**Security.** Registration does not verify phone numbers by SMS, so the **code** proves the person got the link; the registered phone and country must also match what the sender typed. The code is drawn from 31^8 values and claim attempts are throttled. Bonus farming with fake numbers is limited by the minimum amount, the per-sender cap and the deposit requirement, but **an SMS check at registration would close it completely** (open item, [§20](#20-known-limits-and-open-items)).

### 9.6 Payment requests and group requests

A member asks another member (same or other supported country) for an amount in **his own** currency; the payer gets an in-app notification + push (`payment_request_in`) and pays with one tap. Paying uses the normal transfer code (`api_tr_execute`: PIN, daily limit, FX + fee, balance lock) and marks the request paid **in the same database transaction**, so it can never be paid twice. The payer pays enough that the requester receives **at least** the requested amount (the fee is grossed up). Requests expire after 7 days (`MP_REQ_TTL_DAYS`).

| Route | Notes |
|---|---|
| `GET /requests?box=in\|out\|all&cursor=` | `{items, next_cursor, pending_in, pin_required}`. Item: `{id, reference, direction, status: pending\|paid\|declined\|cancelled\|expired, amount, symbol, note, counterparty, expires_at, pay?, group?}` |
| `POST /requests {phone, country?, amount, note?}` + `Idempotency-Key` | Minimum = transfer minimum, max 10 open, no duplicate (same payer + amount). Errors: `recipient_not_found`, `self_transfer`, `recipient_unavailable`, `below_minimum`, `too_many_open`, `duplicate_request` |
| `POST /requests/{id}/pay {pin?, expected_pay?}` + `Idempotency-Key` | Payer only. Same errors as transfers plus `request_closed` 409; `rate_changed` 409 returns the fresh request |
| `POST /requests/{id}/decline` · `/cancel` | Payer declines (requester notified) · requester withdraws |

**Group requests / split payments** (`lib_group.php`): a parent row (`payment_request_groups`) owns one ordinary payment request per person (`payment_requests.group_id`). Each person pays or declines through the normal `/requests/{id}/pay|decline` routes, so PIN, limits, FX and the same-transaction guarantee are unchanged. A share appears for the payer in `GET /requests?box=in` with `group:{id,title}`; the requester's shares are listed under `/groups` instead.

| Route | Notes |
|---|---|
| `GET /groups?cursor=` · `GET /groups/{id}` | My groups, newest first · one group |
| `POST /groups {title, note?, split:"equal"\|"custom", total?, include_me?, members:[{phone, country?, amount?}]}` + `Idempotency-Key` | Whole numbers in the requester's currency. `equal`: `total` required, split over the members (+ the requester when `include_me`, default true); everybody pays `floor(total/people)`; the odd remainder is the requester's own (or +1 to the first members when `include_me` is false). `custom`: each member has `amount`; `total` optional. 1–10 members, each share ≥ the transfer minimum, max 5 groups with an unpaid share. All shares are created in **one** transaction. Errors carry the member `index`: `invalid_phone`, `recipient_not_found`, `self_transfer`, `duplicate_member`, `recipient_unavailable`, `invalid_amount`, `below_minimum`; plus `invalid_title`, `no_members`, `too_many_members`, `amounts_exceed_total`, `too_many_open` |
| `POST /groups/{id}/remind` + `Idempotency-Key` | Notifies everybody whose share is open; once every `MP_GRP_REMIND_HOURS` (12) → `429 remind_too_soon`; `409 nothing_to_remind` |
| `POST /groups/{id}/cancel` | Withdraws pending shares; paid shares stay paid |

Group object: `{id, reference, title, note, status: open|complete|closed|cancelled, split, symbol, total, my_share, requested, collected, waiting, counts:{total,paid,pending,declined,cancelled,expired}, created_at, expires_at, last_reminded_at, members:[{request_id, reference, name, phone, country, amount, status, paid_at}]}`. `closed` = nothing left open but not everybody paid.

### Admin console security (server 3.20.8+)

`admin_guard.php` protects `admin.php` and every `admin_*.php` page (include it instead of `session_start()`):

- **Session:** own cookie (`MPADMIN`), HttpOnly, SameSite=Strict, Secure on https, 30 min idle / 8 h maximum, bound to the browser; security headers; admin pages are never cached.
- **No double actions:** every form gets a one-time token (`_once`) automatically; a double click, a refresh that re-sends a form, the back button or a second tab cannot repeat a payment, a credit or a ban. The answer to a form is shown after a redirect (Post/Redirect/Get), and buttons lock while a request runs.
- **Money actions** (pay / reject a withdrawal, Airtel / Payfonte payout, resolve a ticket) are POST buttons (`mp_admin_act_btn`), never links; old `?pay_withdrawal=...` links do nothing. Logout needs its token.
- To add a form to an admin page: just include `<input type="hidden" name="csrf" value="...">` as before; the one-time token is added for you.

### 9.7 Contacts (removed in 3.20.8)

The phone-contacts feature was removed: no `READ_CONTACTS` permission, no `/contacts/*` endpoints, no `user_contacts` table (`db/remove_contacts.sql` deletes it from an existing database).

### 9.8 Merchant (business profiles)

Code: `api/v1/lib_merchant.php`; table `merchant_profiles`.

| Route | What |
|---|---|
| `GET /merchant/me` | `{profile:{business_name, category, city, address, code, since, cashback_pct}\|null, categories:[…], cashback_set_pct, cashback:{enabled,max_pct}}` |
| `POST /merchant/me` | `{business_name (2–40), category, city?, address?, cashback_pct?}` creates or updates; the shop `code` never changes; the server clamps cashback to the admin maximum |
| `POST /merchant/remove` | Deletes the business profile (account and personal QR stay) |
| `POST /merchant/lookup {phone}` | `{merchant:{business_name, category, city, code, cashback_pct}\|null}` — the payer's app shows the verified name before confirming |

How it works:
- Customers pay with the normal `POST /transfers`. Shop QR = `mudapay://pay?v=1&p=<phone>&c=<cc>&m=<shop code>`; `m` is only a hint, the app trusts `/merchant/lookup`, never the QR.
- **Fees:** the customer never pays more than the amount shown, and no same-country transfer fee is charged for a payment to a business. The business pays the merchant fee instead (see [§10](#10-money-rules-fees-limits-rates-rewards)). Cross-border fees still apply to the payer.
- **Cashback:** a business can set "Cashback for customers (%)"; for payments inside the business's own country the customer gets that % back, **funded by the business** (taken from what it receives, after the merchant fee, in the same transaction). Admin switch and cap in `admin_promo.php` (0–30 %, default 10). `transfers.cashback` stores it.
- Sales summary = received transfers of the day, summed on the phone. Payout = the normal withdrawals.
- Shop mode polls `GET /transfers?direction=in` every 4 s and plays a sound once per transfer reference.

### 9.9 Promo codes and vouchers

Admin page: `admin_promo.php`. Code: `promo_lib.php`, `api/v1/lib_promo.php`.

- **Promo codes** (partners, influencers): reusable code with a bonus for the member who types it. Options: amount (USD converted, or fixed local amount), paid **instantly** or **after the first deposit** (minimum deposit, days to deposit — otherwise forfeited), max uses, "new members only (N days)", start / expiry, and an optional **partner** (member phone) who earns a USD reward per redemption when the member's bonus is paid. One promo code per account; one use per code per member; failed guesses throttled (10 per 15 min).
- **Vouchers:** batches of 1–500 single-use codes `MV-XXXX-XXXX-XXXX` with a fixed value and optional expiry. Always instant. CSV download in admin. You pay the value when a voucher is redeemed.

| Route | Notes |
|---|---|
| `POST /promo/redeem {code}` | → `{kind, label, code, amount, symbol, held, min_deposit, days, balance}` (`held`: paid by the first deposit) |
| `GET /promo/history` | `{items:[{code, kind, label, amount, symbol, status: held\|released\|forfeited, deadline, created_at}]}` |

Errors: `invalid_code`, `code_expired`, `code_not_started`, `code_used_up`, `already_redeemed`, `one_promo_only`, `wrong_country`, `new_members_only`, `too_many_attempts`.

### 9.10 Withdrawals, agents and bureaus

Members cash out through mobile money, crypto, or a MudaPay **agent** (a person identified by a code such as `MA7K2QX`) or **bureau** (an office). Code: `agent_lib.php` (rules and money), `api/v1/lib_agent.php` (routes), `admin_agents.php`.

**Flow.** The member picks **Agent** (types the agent's code) or **Bureau** (list) in Withdraw → her balance is debited at once and she gets a **6-digit payout code** → the agent sees the request, taps Accept, hands over the cash, types the member's code → the agent's MudaPay balance is credited **cash paid + commission** and the withdrawal becomes `completed`. Decline, cancel or time-out refund the member.

| Route | Notes |
|---|---|
| `GET /withdrawals/rules` | Methods include `Agent` (and `Bureau` when one is open in the country) plus an `agents` block; `pin_required` |
| `POST /withdrawals {amount, method, account_number? \| agent_code?, pin}` + `Idempotency-Key` | `method:"Agent"\|"Bureau"` returns `201` with the withdrawal item incl. `confirm_code` (only the owner ever sees it) |
| `GET /withdrawals` | Items carry `agent_*`, `cash_amount`, `confirm_code` (while open), `expires_at`, `note`, `reviewed_at`, `paid_at` |
| `POST /withdrawals/{id}/cancel` | Member, only while the agent has not accepted |
| `POST /agents/lookup {code}` · `GET /agents/bureaus` | Public info of an agent / bureau (with `lat` / `lng` when listed) |
| `GET /agents/nearby?lat&lng&kind` | Active, listed agents + bureaus of the member's country, nearest first. `kind=agent\|bureau`. `lat` / `lng` optional (used for this request only, never stored); items carry `distance_km` when sent |
| `GET /agent/me` · `POST /agent/apply` · `POST /agent/availability` | Apply (admin approves; `apply` also takes optional `lat`, `lng`, `listed`), pause / resume |
| `POST /agent/location {lat, lng, listed}` | The agent chooses whether the map shows him and where |
| `GET /agent/requests?scope=open\|history` | The agent's queue (member shown as "Jane D." + masked phone) |
| `POST /agent/requests/{id}/accept` · `/decline {reason?}` · `/confirm {code}` + `Idempotency-Key` | 5 wrong codes lock the request for the admin |

**Rules**
- Agent and member must be in the **same country** (same currency); an agent cannot serve himself; **one open withdrawal per member** (`pending` or `approved`).
- Unanswered requests are refunded after `agent_request_ttl_h` hours (default 24, checked lazily, no cron).
- Agent daily volume = `agent_daily_mult` × the country's withdrawal minimum (default 50×) unless set per agent.
- **Commission** is always paid by the member (deducted from her cash), so MudaPay never creates money. Defaults: 1 % agent, 0 % bureau, per-agent override. MudaPay keeps a share of each commission (see [§10](#10-money-rules-fees-limits-rates-rewards)).
- Withdrawals no longer depend on referrals (the old "2 referrals" rule was removed).
- **Agents must be ID-verified** before applying or being activated: `/agent/apply` answers `403 id_verification_required` until support has verified the ID in `admin_agents.php` ("ID verifications").

**Agent locator.** Only **active** agents that are ticked "shown on agent map" **and** have a position appear. Personal agents opt in themselves (Agent desk) and their pin is shown about **100 m off**; bureaus are exact. The member's position is sent rounded to about 1 km, used for that one request and never stored. For an existing agent or bureau set the lat / lng boxes and the "shown on agent map" tick in the Terms column; the columns `agents.lat`, `lng`, `listed` exist in `db.sql`. In admin, right-click a spot in Google Maps and click the numbers to copy coordinates for a new bureau.

### 9.11 ID verification (KYC) routes

`GET /account/id` (returns `has_back`, `has_details`, `kyc_details_enabled`, `verified`) · `POST /account/id/upload?part=front|selfie` (multipart `file`, optional `doc_type`; 5 MB, JPEG / PNG / WEBP) · `POST /account/id/back` (multipart `file`, `doc_type` in the query) · `POST /account/id/details` · `POST /account/id/submit`.

`POST /account/id/details`: JSON `{doc_type, consent:true, confirmed:{full_name, doc_number, date_of_birth, expiry_date, issue_date, sex, nationality, issuing_country, place_of_birth, address, license_categories}, ocr:{field:{value, source:"mrz"|"label"|"heuristic", confidence}}, mrz_checksum_ok:true|false|null, quality:{front,back}, client:{app_version, ocr_engine, cv_engine, device_sdk}}`. Dates `YYYY-MM-DD`. Errors: `consent_required`, `details_incomplete`, `id_locked` (409), `kyc_unavailable` (503: encryption key not configured). **Neither route can change the review status.** See [§13](#13-kyc-and-identity-verification).

**Verified badge.** A member is "verified" when support checked his ID (`id_verifications.status = 'verified'`, KYC level 2). `GET /account/avatar` returns `verified` (own account); `POST /avatars/lookup` returns `verified:{phone:true}`; member objects (`counterparty`, `recipient`, `member`) get `verified:true`. The app draws `drawable/verified_badge.png` bottom-right of every `MemberAvatar`.

### 9.12 Profile photos

Code: `api/v1/lib_avatar.php`; table `member_avatars`. Needs PHP **GD** (without it the app shows "Profile pictures are not available on this server right now"). The first upload creates `avatars/` with a closing `.htaccess`; pictures leave only through `GET /api/v1/avatar/{token}` (random token, changes on every upload).

| Route | Who | What |
|---|---|---|
| `GET /account/avatar` | signed in | My photo URL (or `null`) and `verified` |
| `POST /account/avatar` | signed in | Multipart `file` → centre-crop, 256×256 JPEG, EXIF removed |
| `POST /account/avatar/remove` | signed in | Delete my photo |
| `POST /avatars/lookup {phones:[…]}` | member | Max 30 → photos of those members |
| `GET /avatar/{token}` | public | The picture |

`index.php` hooks `mp_av_enrich_buffer()` and, on every successful JSON answer, adds `"avatar": "<url>"` to each `counterparty`, `recipient` and `member` object (matched by `id` / `user_id` / `member_id`, else by an unmasked phone; one query per answer) and `"business"` next to a member with a merchant profile. It is fail-safe: any error returns the original answer. Rows with a masked phone and no id keep initials unless the app falls back to `POST /avatars/lookup`. Removing a photo from admin writes a `photo_removed` line in the admin log (if `admin_actions.action` is an ENUM that rejects it, the photo is still removed and only the log line is skipped). A deleted account's picture is no longer served.

### 9.13 Support and disputes

Tickets are **conversations** with the team, optionally **linked to one transaction**. Muda AI is the fast answer while the team is away. Code: `api/v1/lib_support.php`.

| Route | Notes |
|---|---|
| `GET /support/config` | `{staff_online, hours:{start,end,days,timezone}, reply_time, ai_available, reasons[]}` |
| `GET /support/tickets` | `{items:[{id, category, subject, message, status, created_at, updated_at, ref_kind, ref_id, ref_summary, dispute_reason, unread, last_message}], unread}` |
| `POST /support/tickets {category, subject, message, ref_kind?, ref_id?, dispute_reason?}` | `ref_kind` = `tx` \| `withdrawal` \| `transfer` (id or reference) \| `wallet` (txid); the item must belong to the member (`404 transaction_not_found`). `dispute_reason` (`not_received`, `wrong_amount`, `wrong_recipient`, `duplicate`, `unauthorized`, `pending_too_long`, `other`) makes it a **Dispute** and needs a linked item. One open ticket per item (`409 already_open` + `ticket_id`). **Opening a dispute never changes a balance** |
| `GET /support/tickets/{id}` | `{ticket, messages[{id, sender: member\|staff, body, created_at}], staff_online, reply_time, can_reply}`; opening marks the team's answers as read |
| `POST /support/tickets/{id}/messages {message}` | Max 3000 chars. Wakes a resolved ticket up to 14 days after resolution, then `409 ticket_closed` |
| `POST /support/tickets/{id}/close` | The member marks it resolved |

Statuses: `Pending` → `In Progress` → `Resolved`. The team's answer creates an in-app notification and an FCM push `support_reply` (route `Support`, extra `ticket_id`).

**Team workflow.** Admin → Support & disputes → **Team hours** (default Mon–Sat 08:00–18:00, Africa/Bujumbura, usual reply time, "Force away" for holidays). Open tickets are listed disputes first, then new ones, each with a snapshot of the linked transaction and an "open member" link to the User Control Center. Buttons: **SEND – IN PROGRESS**, **SEND & RESOLVE**, **RESOLVE ONLY**. A dispute never moves money by itself: refunds and corrections use the existing admin tools.

**Safety.** The linked item is checked server side (transactions, withdrawals, transfers as sender or receiver, wallet txs). One open ticket per transaction; 10 new tickets and 20 messages per 10 min per member. Muda AI receives the ticket summary only for the member's own ticket and is told never to promise refunds, call a payment lost / fraud, or ask for PIN / OTP / password.

### 9.14 Muda AI (coach)

`GET /coach/context` · `GET /coach/conversations[/{id}]` · `POST /coach/messages` (the app adds `voice` and `lang` for voice chat, and optionally `ticket_id`). Provider keys stay in `db/ai_config.php`; `db/knowledge/*.md` is the knowledge base (plus live reward tables, admin notes and a member snapshot).

`coach_actions_lib.php` makes it a contextual assistant: `reply.action` (and `action` on each history message) is `null`, `{type:"send", status:"ready|pick|none", amount, name, phone, country, note, label, candidates:[…]}` (the app pre-fills Send and **never pays**) or `{type:"open", route, label}`. Quick actions and exact fee / spend answers (`source` = `action` / `insights`, computed from `transfers`, `withdrawals`, `platform_revenue`) skip the AI daily limit. `ai_messages.action` stores the action JSON.

### 9.15 Market

`GET /market/coins` (16 coins: price, 24 h change / high / low / volume, mini chart; 15 s cache) · `/market/ticker?symbol=BTC` (5 s) · `/market/candles?symbol=&range=1D|1W|1M|3M|1Y` (rows `[time_ms, open, high, low, close, volume]`, oldest first; 20 s–30 min) · `/market/search?q=` · `/market/fx` (USD → every supported currency, same three cross-checked feeds as deposits, plus 8 major currencies; 1 h) · `/market/fx/history?quote=BIF&range=1W|1M|3M|1Y` (daily; 6 h) · `/market/status` (only while the debug flag exists).

No provider key needed. The server asks free providers and caches in `db/market_cache/`.

- Crypto prices: Binance (data-api.binance.vision, api.binance.com) → OKX → Bybit → CoinGecko → fawazahmed0 (daily, marked "delayed").
- Crypto candles: Binance → OKX → Bybit → Coinbase Exchange → CoinGecko OHLC (no volume bars).
- FX history: Frankfurter → fawazahmed0 dated files (jsDelivr, then the Cloudflare Pages mirror).
- If every provider fails, the last good answer (up to 48 h old) is returned with `"stale": true`. Some exchanges block data-centre IPs; the chain skips them.
- Coin icons: `crypto_icon.php?s=BTC` (cached 7 days on the phone, files in `crypto_icons/`). Members can add up to 40 extra coins (kept on the phone, sent as `/market/coins?extra=`). Currency flags come from `flags/xx.png`; the 8 major currencies need `eu gb ae cn in ca ch jp`.
- **Adding a built-in coin:** add a line to `mk_coins()` in `lib_market.php` (symbol, name, CoinGecko id) and a colour to `MK_COLORS` in `MarketScreens.kt`. The coin must trade as `SYMBOL/USDT` on Binance or OKX.

### 9.16 App update and error reporting

- `GET /app/update?version_code=39&platform=android&lang=fr` → `{mode: none|optional|required|forced, mandatory, version_name, version_code, kind, notes, deadline, days_left, download_url, is_file, size, sha256}`. `none` when the phone already has the newest published build. `required` = the old app works until `deadline` (Africa/Bujumbura), then becomes `forced`. In "Google Play only" mode `is_file` is false and `download_url` is the store page.
- `GET /app/download/{id}` streams a published APK (resume supported, counted in `downloads`).
- `POST /client/errors {reports:[{type, message, stack, app_version, sdk, device, call, screen, extra, count, uid}]}` (public, throttled, max 5 per call): crash / ANR / native-crash / unexpected-answer reports. Passwords, tokens, e-mails and phone numbers are masked; reports are stored in `error_logs` and e-mailed to `MP_ALERT_EMAIL` (`db/alert_config.php`).

### 9.17 Push messages (FCM HTTP v1, data-only)

Keys: `type`, `route`, `title`, `body`, optional `details` (lines separated by `|`) and `withdrawal_id`; `android.priority = HIGH`. Each type respects the member's category switches (`cat_payment`, `cat_withdrawal`, `cat_bonus`, `cat_streak` in `api_devices`).

| Type | When |
|---|---|
| `withdrawal_paid`, `withdrawal_update`, `withdrawal_rejected` | Admin changes a withdrawal (route `Withdraw`) |
| `payment_deposit`, `payment_deposit_review`, `payment_deposit_expired`, `payment_deposit_failed` | Deposit status (route `Deposit`) |
| `payment_transfer_in`, `payment_transfer_out` | Money received / sent |
| `payment_request_in` | Payment request, group request or reminder (route `Requests`) |
| `credit`, `referral` | Admin credit, leaderboard prize, referral reward |
| `support_reply` | The team answered a ticket (route `Support`) |
| `email_code_sent`, `email_verified` | E-mail verification |
| `daily_bonus`, `streak_risk` | Daily cron reminders (route `Home`) |

### 9.18 QR format

```
mudapay://pay?v=1&p=<phone>&c=<country code>[&a=<amount>][&n=<note>][&m=<shop code>]
```

Older codes with only a phone number (and codes without `c`) are still accepted. The app resolves the number with `POST /transfers/lookup` and shows the **server's** name; `m` is only a hint, and the app trusts `POST /merchant/lookup`.

---

## 10. Money rules: fees, limits, rates, rewards

Rates, fees and switches are edited in **`admin_fx.php`** ("EXCHANGE RATES" in admin) and stored in `admin_settings`. The built-in rates in `fx_lib.php` are only a starting point.

### 10.1 Fees and revenue

| Fee | Default | Range | Charged to | Notes |
|---|---|---|---|---|
| **Same-country transfer fee** (`fx_domestic_fee_pct`) | 0.5 % | 0 – 5 % (0 = free) | Sender | Minimum charge 0.01 so the smallest transfer still pays something. **Not charged on payments to a business** (the business pays the merchant fee instead) or on payment requests. Revenue line "Transfer fee (same country)" |
| **Cross-border transfer fee** (`fx_fee_pct`) | 1.5 % | admin | Sender | |
| **Exchange margin / spread** (`fx_spread_pct`) | 1 % | 0 – 10 % | Receiver side | Receiver is credited at `mid-market rate × (1 − margin)`; already inside the `rate` and `receive_amount` the quote shows. Quote fields: `spread_pct`, `spread`, `mid_rate`. On top of the fee: 1 % + 1.5 % ≈ **2.5 %** total cross-border |
| **Merchant fee** | 0.5 % | switchable, max 5 % | Business | On every payment a business-profile member receives (QR, transfer, request). Merchant is credited `amount − fee`; stored in `transfers.merchant_fee`; shown on its notification and receipt. Set in `admin_revenue.php` |
| **Agent / bureau commission** | 1 % agent, 0 % bureau | per-agent override | Member (deducted from her cash) | MudaPay keeps a share: **20 %** by default ("MudaPay keeps this % of each commission"). Example: commission 1 %, share 20 % → agent 0.8 %, MudaPay 0.2 %. Applies to cash-outs requested after the change; set 0 to give agents the whole commission |
| **Wallet transfers** | none | — | — | MudaPay charges no fee on crypto-wallet transfers |

The **Revenue page** (`admin_revenue.php`) shows totals by source and currency for 1 / 7 / 30 / 90 / 365 days, a rough USD total (using `admin_fx.php` rates) and the latest earnings. Figures are **gross**: provider fees (Payfonte, NOWPayments, ...) and the rewards you pay are not deducted. Table `platform_revenue`.

### 10.2 KYC levels and limits

Levels: **0 Basic** (nothing verified), **1 Standard** (verified e-mail), **2 Verified** (`id_verifications.status = 'verified'`). Default caps in USD (per operation / 24 h / 30 days), converted to the member's currency with the admin rates:

| Level | Send and withdraw | Deposit |
|---|---|---|
| 0 | 25 / 50 / 200 | 25 / 50 / 200 |
| 1 | 100 / 200 / 1,000 | 100 / 200 / 1,000 |
| 2 | 1,000 / 3,000 / 15,000 | 500 / 1,500 / 10,000 |

Change them without code in `admin_settings`: key `kyc_limits_json`, e.g. `{"2":{"send":{"tx":2000,"day":5000,"month":20000}}}` (only the listed values change). Key `kyc_limits_enabled` = `0` switches all limits off. They are an extra ceiling: balance, minimums, PIN and PayPal's `PP_MAX` / `PP_DAILY` still apply. Enforced on transfers, request payments, withdrawals (all methods) and deposits (PayPal, card, crypto, Payfonte, Airtel, Lumicash). Money routes can answer `422` with `kyc_limit_tx`, `kyc_limit_day` or `kyc_limit_month`; `data` holds `kyc_level`, `kyc_upgrade`, `limit_*` and `remaining_*`. `GET /account/limits` returns `level`, `level_name`, `email_verified`, `id_status`, `currency`, `symbol`, `limits[]` (`kind` send / withdraw / deposit, `per_tx`, `day`, `month`, `used_day`, `used_month`, `remaining_*`, `next`) and `levels[]`. Code: `kyc_limits_lib.php`, `api/v1/lib_security.php`.

### 10.3 Exchange rates and deposit pricing

Three independent feeds are read together: `api.exchangerate.fun`, `open.er-api.com`, `currency-api (jsDelivr)`.

- A rate is **confirmed** only when 2+ feeds agree within **1.5 %**; the median of the agreeing feeds is used.
- A rate seen by one feed is accepted for **display**, never for money. A feed that jumps more than 25 % from the last good rate is rejected.
- **Deposits use confirmed rates only**, at most 15 minutes old. No AI estimate, no built-in reference, no stale cache. If the feeds are down or disagree, the app shows "deposits paused for a few minutes" instead of guessing.
- USD price = local amount / rate, rounded **up** to the cent (+ optional fee percentage).
- The rate is **locked on the invoice**. Paid within 2 hours → the quoted local amount is credited. Paid later → re-priced at the live confirmed rate and the **lower** amount is credited.
- Cache file `db/fx_cache.json` stores `conf`, `feeds` and `at` per currency.

### 10.4 Rewards and referrals

Rewards are small extras; MudaPay's main purpose is payments. Reference values (same USD value in every country, converted at the reference rate): task 0.10 USD, video 0.085, like 0.014, share 0.17, weekly bonus 0.011, monthly leaderboard TOP 1 = 20 USD, minimum withdrawal about 5 USD. Rates live in `reward_rates.php`.

**Referral reward — currently switched off** (`MP_REFERRAL_PERCENT = 0` in `reward_rates.php`). Referrals are still counted and a referral is "Active" once the friend made a first deposit, but no bonus is paid. Set `MP_REFERRAL_PERCENT` back to e.g. 5 to pay 5 % of a friend's **first deposit** once per friend. `MP_REFERRAL_MIN_DEPOSIT_USD` (default 0) is the smallest first deposit that qualifies (set e.g. `1` to block fake accounts). Same country: 5 % in the same currency; different countries: converted to the referrer's currency with the `admin_fx.php` rates, without the transfer fee. Whole units for BIF, CDF, TZS, UGX, RWF, NGN, KES; 2 decimals for GHS, ZAR. Paid in the same database transaction as the deposit and recorded in `referral_rewards` (unique per friend, so never paid twice). Withdrawals no longer depend on referrals. The referral list shows names as "Jane D.", masked phones and no balances.

---

## 11. Payment provider integrations

All deposits are credited **once**, only after a server-side verification with the provider, inside a row-locked transaction. Mismatches go to a `review` status (check the admin deposits page; Telegram alerts were removed). Deposits are only offered in countries that also have a payout path, so the money can be withdrawn.

### 11.1 Payfonte (mobile money, cards, payouts)

Deposits (app → Deposit) and automatic payouts (admin → **PAY VIA PAYFONTE**). Amounts are whole units of the member's own currency, credited 1:1. Burundi and Zimbabwe are not covered; Payfonte has no crypto.

**Files:** `payfonte_lib.php`, `payfonte_webhook.php`, `payfonte_return.php`, `payfonte_card_lib.php`, `api/v1/lib_payfonte.php`, `api/v1/lib_payfonte_card.php`, `db/payfonte_config.php`. Tables `payfonte_deposits`, `payfonte_payouts`, `payfonte_card_deposits`.

**Setup**
1. Payfonte dashboard → Settings → Security → API Keys and Webhooks: put the Client ID and Client Secret in `db/payfonte_config.php` (`PF_CLIENT_ID`, `PF_CLIENT_SECRET`). **Sandbox and live have different keys and different hosts:** sandbox keys with `https://sandbox-api.payfonte.com`, live keys with `https://api.payfonte.com` (`PF_BASE`).
2. Webhook URL (same page): `https://<site>/payfonte_webhook.php`. Payfonte signs it with your Client Secret (HMAC-SHA512, header `x-webhook-signature`).
3. Dashboard → Integrations: choose "Use Platform Keys" (or your own) for each network you accept.
4. Withdrawals: set the Disbursements PIN in the Payfonte dashboard, put it in `PF_DISB_PIN`, and fund the disbursement wallet. With the "Authorization URL" mode instead of a PIN, payouts stay manual.
5. Check each network's limits and adjust `min` / `max` / `daily` in `PF_COUNTRIES`.
6. If a secret was ever shown in a screenshot or chat, press "Get New API Secret" and update the config.

**Deposit flow:** `POST /payfonte/deposit {phone, amount, operator}` → `POST /payments/v1/payments/direct-charge`. The app polls `GET /payfonte/deposits/{id}` (the server verifies with `GET /payments/v1/payments/verify/{reference}`, our reference first, then Payfonte's) and shows the step Payfonte asks for: approve on the phone, OTP via `POST /payfonte/deposits/{id}/otp` → `/payments/v1/payments/confirm`, or redirect. Credited **once**, only on `success` with the same amount and currency.

**Payout flow:** admin presses PAY VIA PAYFONTE → validate recipient → `POST /billing/v1/disbursements` (with the PIN). `success` = withdrawal completed + push; `failed` = back to pending; processing or network error = "in progress", press **CHECK STATUS** (the webhook also updates it). A double click cannot send two payouts.

**Technical notes:** every currency is sent as value × 100 (`PF_MINOR`); phone format per network is international digits (e.g. `243991234567`), Nigeria uses the national 0-format (change `phone` in `PF_COUNTRIES` if a network refuses it).

**Card deposits (Visa / Mastercard):** hosted checkout `POST /payments/v1/checkouts` in USD (branch `MPC...` in the webhook); card numbers never reach MudaPay. The member types an amount in his own currency; the server prices it in USD at the confirmed live rate, locks the rate, and credits the locked local amount once Payfonte's verify call says `success` with currency USD and amount ≥ the price. Settings (top of `payfonte_card_lib.php`, override in `db/payfonte_config.php`): `PF_CARD_ENABLED`, `PF_CARD_COUNTRY` (default US), `PF_CARD_MIN_USD` 2, `PF_CARD_MAX_USD` 500, `PF_CARD_DAILY_USD` 1000, `PF_CARD_FEE_PCT` 0. Requires Visa / Mastercard enabled in the Payfonte dashboard and the same webhook URL.

### 11.2 Airtel Money (DR Congo)

Deposit with Airtel Money (USSD push) and **automatic Airtel payouts**. Amounts are whole **FC (CDF)**, credited 1:1.

**Files:** `airtel_lib.php`, `airtel_callback.php`, `api/v1/lib_airtel.php`, `db/airtel_config.php`; number check in `api/v1/lib_money.php` (saved as `+243XXXXXXXXX`); admin buttons PAY VIA AIRTEL / CHECK STATUS / Airtel balance. Tables `airtel_deposits`, `airtel_payouts`.

**Setup**
1. `db/airtel_config.php`: `AIRTEL_CLIENT_ID`, `AIRTEL_CLIENT_SECRET` (Airtel portal → Key Management).
2. `AIRTEL_BASE`: `https://openapiuat.airtel.cd` for **TEST**, `https://openapi.airtel.cd` for **LIVE** with live keys (press **Go Live** in the portal first). **The package currently points to the UAT host.**
3. Portal → your app → Collection-APIs → Settings: callback URL `https://<site>/airtel_callback.php`; do the same for Disbursement-APIs.
4. Message signing: keep it **off** in the portal and `AIRTEL_SIGN = false` (the AES-256-CBC / RSA signing path exists but is unused).
5. Withdrawals by Airtel: put the 4-digit PIN of the Airtel **disbursement wallet** in `AIRTEL_DISB_PIN`. Empty = withdrawals stay manual (MARK PAID).
6. If a Client Secret was ever shared in a chat, rotate it (Update Credentials).

**Deposit flow:** `POST /airtel/deposit {phone, amount}` (Idempotency-Key) → USSD prompt → member enters the Airtel PIN → the app polls `GET /airtel/deposits/{id}`; Airtel's callback also triggers a check. Credited **once**, only when Airtel says **TS** for our own transaction id. `TF` = failed; `TA` = "under review" + Telegram alert (never credited automatically); `TIP` / network errors stay pending. Airtel asks to wait 3 minutes before a status enquiry (`AD_ENQ_AFTER`): the **callback** is the fast path; polling only reaches Airtel after 3 minutes.

**Withdrawal flow:** member requests method "Airtel Money" (number validated: 97 / 98 / 99 …) → admin → Pending Withdrawals → **PAY VIA AIRTEL**. `TS` → completed + push; `TF` → back to pending (retry, pay by hand, or reject + refund); `TA` / `TIP` / unknown → "payout in progress", press **CHECK STATUS**. A double click cannot send two payouts.

**Settings:** `AD_DEP_MIN` 1,000 · `AD_DEP_MAX` 5,000,000 · `AD_DEP_DAILY` 10,000,000 (FC). Endpoint paths are constants (`AD_PATH_*`).

**API used:** OAuth `POST /auth/oauth2/token`; collection `POST /merchant/v2/payments/`; status `GET /standard/v1/payments/{id}` (TS / TF / TA / TIP / TE); disbursement `POST /standard/v2/disbursements/`, status `GET /standard/v2/disbursements/{id}`; balance `GET /standard/v2/users/balance?type=DISB`. Callback body `{transaction:{id, message, status_code, airtel_money_id}}`. The PIN is RSA-2048 OAEP SHA-256 encrypted with Airtel's published public key (built into the config). Airtel error codes (`DP00800001xxx`, `ROUTERxxx`) are turned into clear member messages.

### 11.3 Lumicash (Burundi, via BitLibera)

**Flow:** member enters number and amount → BitLibera sends an OTP → member confirms → BitLibera debits Lumicash and credits the MudaPay merchant balance (`/topup/lumicash`) → the member is credited the same amount in BIF → the amount is **forwarded** from the BitLibera balance to the fund account `LN_FUND_PHONE` with `POST /payouts`.

- The member credit never waits for the forward. A failed forward is retried (new order id each time, max 8) and admins are notified.
- Only deposits credited after forwarding was introduced are forwarded (`sweep_status = 'pending'`).
- Cron every 5 min: `php cron/lumicash_sweep.php` re-checks pending deposits and retries forwards.
- Disable forwarding: set `LN_FUND_PHONE` to `''` in `db/bitlibera_config.php`.
- Limits: min 1,000 / max 5,000,000 BIF per payment, 10,000,000 BIF per 24 h.
- Burundi mobile-money payouts are manual.

### 11.4 NOWPayments (crypto deposits)

1. NOWPayments dashboard → Settings → Payments → **API keys** → create a key.
2. Same page → **Instant payment notifications** → generate the IPN secret.
3. `db/nowpayments_config.php`: `MP_NP_API_KEY` and `MP_NP_IPN_SECRET`.
4. The webhook URL (`https://<site>/np_deposit_ipn.php`) is sent with each invoice, so the URL saved in the dashboard does not matter. Make sure no firewall or Cloudflare rule blocks POSTs to it.
5. An **empty** `MP_NP_API_KEY` switches crypto deposits off.

A member picks an amount in his **own** currency, pays in crypto, and the same amount is added to his balance. Optional knobs (define in `db/nowpayments_config.php`): `MP_DEP_MIN_USD` 0.40 · `MP_DEP_MAX_USD` 500 · `MP_DEP_DAILY_USD` 1000 · `MP_DEP_FEE_PCT` 0 · `MP_DEP_QUOTE_TTL` 7200 s · `MP_DEP_OPEN_MAX` 3 · `MP_DEP_INAPP` true (coin picker + address in the app; false = NOWPayments browser page).

**API:** `GET /deposits/rules` · `POST /deposits/quote {amount}` · `POST /deposits {amount, pay_currency?}` + `Idempotency-Key` → `{id, reference, invoice_url, amount_local, price_usd, rate, status}` (with `pay_currency` it creates a direct payment and returns `pay_address`, `pay_amount`, `pay_label`, `pay_network`, `expires_in` for the in-app CryptoPay screen) · `GET /deposits` · `GET /deposits/{id}` (poll while the member pays; `credited:true` + `balance` when done). Errors: `rate_unavailable` 503, `below_minimum` / `above_maximum` / `invalid_amount` / `daily_limit` 422, `too_many_open` 409, `crypto_unavailable` 503. Push type `payment_deposit`.

**Safety:** webhook HMAC-SHA512 signature plus a fresh `GET /payment/{id}` before anything is credited; credited once (row lock + `credited` flag). Only `finished` payments in USD covering the invoice price are credited; partial payments and mismatches go to `review` with a Telegram alert. Deposits are limited per payment, per day and by number of open invoices.

**Small deposits:** the smallest deposit is `MP_DEP_MIN_USD` (0.40); each coin also has its own network minimum read live from NOWPayments `GET /min-amount` (cached 1 h in `db/np_min_cache.json`) and returned per coin in `coins[].min_usd`. The app blocks a coin whose minimum is above the amount. Cheapest networks first: USDT BEP20, TRX, USDT TRC20, LTC, BNB; ETH / ERC20 are marked costly. If NOWPayments still refuses a payment as "below minimum", your account minimum may be higher than the network minimum: check the dashboard (Store settings) and `db/np_min_cache.json`. Deposits are **not** counted as "earned".

### 11.5 PayPal (UK, USA, Europe and where enabled)

Members add money with a PayPal account (or a card inside PayPal's page); MudaPay never sees the login or card. Amounts are whole units of the member's own currency, credited 1:1 once PayPal confirms the capture.

**Files:** `paypal_lib.php`, `api/v1/lib_paypal.php`, `paypal_return.php`, `paypal_webhook.php` + `PayPal.php` (webhook), `db/paypal_config.php`. Table `paypal_deposits`; token cache `db/paypal_token.json`.

**Setup (sandbox now, live later)**
1. `db/paypal_config.php` currently holds the **SANDBOX** Client ID / Secret (`PP_ENV = 'sandbox'`).
2. Webhook (PayPal Developer dashboard → app → Sandbox Webhooks): `https://<site>/PayPal.php`; all events is fine. Copy its **Webhook ID** into `PP_WEBHOOK_ID`, then set `PP_WEBHOOK_STRICT` to `true` so unsigned calls are refused.
3. Test with the sandbox PERSONAL (buyer) account: app → Deposit → PayPal → amount → pay.
4. **Going live:** open the LIVE app, put its Client ID + Secret in `db/paypal_config.php`, set `PP_ENV` to `'live'`, add the live webhook (same URL) and put its ID in `PP_WEBHOOK_ID`.

**Flow:** `POST /paypal/deposit {amount}` → `POST /v2/checkout/orders` (intent CAPTURE) → the app opens the PayPal approval page → after approval PayPal opens `paypal_return.php`, which re-reads the order, **captures** it and credits once. The app's polling (`GET /paypal/deposits/{id}`, every 6 s) and the webhook do the same idempotent re-read; whichever arrives first wins.

- **Price:** UK members pay in GBP, US members in USD, France / Andorra / Bulgaria in EUR (all 1:1); every other country pays in USD at the confirmed live rate. `pp_plan()` charges the member's own currency when it is GBP, EUR or USD. The approval page opens in the member's language (`pp_locale()`: fr-FR, bg-BG, cs-CZ, en-GB, otherwise en-US).
- **Credit rules:** only when the capture is `COMPLETED`, in the same currency and for at least the asked amount. `PENDING` (PayPal review) waits. A wrong amount goes to `review` with a Telegram alert.
- **Refund / reversal / dispute** after the credit: the deposit is flagged `review` and the admin is alerted. **The balance is not debited automatically.**
- Your PayPal business account must be able to receive EUR (it converts automatically if not).

**Settings** (define in `db/paypal_config.php`): `PP_ENABLED`, `PP_COUNTRIES` (`'*'` or `['gb','us']`), `PP_MIN` 2, `PP_MAX` 500, `PP_DAILY` 1000 (all in the charged currency), `PP_FEE_PCT` 0 (PayPal's merchant fee is about 3.5 %: put it here to pass it on), `PP_QUOTE_TTL`, `PP_OPEN_MAX`.

**API:** `GET /paypal/rules` (`enabled, paused, symbol, currency, charge_currency, rate, min, max, presets, quote_valid_minutes, open[], balance`) · `POST /paypal/quote {amount}` (`amount, charge, charge_currency, rate, same_currency, fee_pct`) · `POST /paypal/deposit {amount}` + `Idempotency-Key` (201 with `invoice_url`) · `GET /paypal/deposits?cursor=` · `GET /paypal/deposits/{id}`.

---

## 12. Crypto Wallet (self-custody)

A wallet built into the app, **separate from the MudaPay balance**: the balance is money in the member's own currency; the wallet is real crypto on the blockchain. Menu → Crypto Wallet.

### 12.1 Scope in this build

| | App | Server |
|---|---|---|
| Chains | **BNB Chain only** (chain id 56): BNB and USDT BEP20 | BNB Chain and Tron routes both exist |
| Tron | **Switched off** (code dormant; re-add the three entries in `Pinned` in `WalletCrypto.kt`) | Off by default (`wallet_tron_enabled` = 0 on the admin Wallet page) |
| External wallet | **Connect external wallet** (WalletConnect / Reown AppKit): MetaMask, Trust Wallet, Rainbow ... approve payments in their own app. **BNB Chain only** (AppKit for Android supports EVM chains only) | — |
| Assets | `bnb`, `usdt_bep20` (18 decimals, USDT contract `0x55d398326f99059fF775485246999027B3197955`) | plus `usdt_trc20` (6 decimals, `TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t`) and `trx` (receive-only) when Tron is on |

**Status: not ready for real money until the checklist in [§12.7](#127-status-and-what-is-still-missing) is done.** The wallet code has been reviewed and unit-tested on the server side, but the app side has not been verified against a real blockchain.

### 12.2 How it works

1. The app creates a **12-word BIP-39 recovery phrase on the phone**, encrypts it with an Android Keystore key (unlocked by fingerprint / face / phone PIN) and shows it once. Address: BNB Chain `m/44'/60'/0'/0/0` (the same as MetaMask / Trust Wallet for the same phrase).
2. The phone derives the addresses and sends **only public addresses** to the server (`POST /wallet/address`).
3. To send, the server returns public data (nonce, gas price, ...; `POST /wallet/prepare`). **The phone signs.** The server relays the signed transaction (`POST /wallet/broadcast`).

The server never receives a phrase or private key; the address endpoint rejects anything with spaces or longer than 80 characters. Consequences:

- MudaPay and its admins **cannot move, freeze, refund or recover** a member's crypto. A member who loses the phrase and the phone loses the funds; support cannot help and must **never** ask for the phrase.
- A server breach cannot drain wallets (there are no keys); it could only show addresses and history.
- Because MudaPay does not hold funds this is usually treated differently from a custodial service, but **rules differ by country**: check licensing, KYC / AML and consumer-protection rules in every country you serve, and Google Play's crypto-wallet policy for your target regions, before going live. This document is not legal advice.

### 12.3 Wallet API

All routes need the member token. Amounts are plain decimal strings; `*_raw` values are integer strings in the smallest unit (wei for BNB / BEP20, sun for TRX / TRC20).

| Route | Purpose |
|---|---|
| `GET /wallet/config` | `{enabled, chains:[{id,name,chain_id,explorer,enabled}], assets:[{id,chain,symbol,name,decimals,contract,can_send}], tron_fee_limit_sun, max_send_usd}` |
| `POST /wallet/address {bsc?, tron?}` | Register public addresses (`invalid_address` if whitespace or > 80 chars) |
| `GET /wallet/balances` | `{balances:[{asset,raw,amount}], errors:{bsc?,tron?}, prices:{BNB,USDT}, updated_at}`. A failing chain is listed in `errors` and its assets omitted. `prices` is display-only (from the Market cache) |
| `POST /wallet/prepare {asset,from,to,amount}` | Data to sign (signs nothing). BNB Chain: `{chain:"bsc", chain_id, nonce, gas_price, gas_limit, fee_raw, native_balance_raw, token_balance_raw, fee_ok, message}`. Tron: `{chain:"tron", ref_block_bytes, ref_block_hash, timestamp_ms, expiration_ms, fee_limit_sun, est_energy, est_fee_sun, trx_balance_raw, token_balance_raw, fee_ok, message}`. `fee_ok:false` carries a readable `message`. Call right before signing (Tron expires 10 min after `timestamp_ms`) |
| `POST /wallet/broadcast {chain,asset,signed,from,to,amount}` + `Idempotency-Key` | `signed` = hex: BNB Chain = raw signed EIP-155 transaction (RLP, legacy gasPrice); Tron = full signed `Transaction` protobuf, relayed with `/wallet/broadcasthex`. → `{txid, status:"pending"}` |
| `POST /wallet/track {asset, txid, from, to, amount}` | Register a transaction made elsewhere (external wallet) so the status screen can follow it |
| `GET /wallet/tx?chain=&txid=` | `{txid, chain, status: pending\|confirmed\|failed, confirmations?}` (own transactions only) |
| `GET /wallet/history?limit=30` | `{items:[{asset, direction: in\|out, amount, counterparty, txid, status, time, chain}], incoming_available}` newest first |

Errors: `wallet_disabled` 503, `rpc_unavailable` 503, `invalid_address` / `invalid_amount` / `invalid_asset` / `invalid_transaction` / `above_maximum` 422, `broadcast_failed` 422 (network refused), `too_many_attempts` 429, `not_found` 404, `idempotency_key_required` 400. The `from` address must be the one registered for that network.

The app **pins** what each asset is (network, contract, decimals, explorer links) inside `Pinned`; the server may only switch assets and networks on or off. Before signing, the app validates the `prepare` data itself (BNB Chain: chain id 56, gas price ≤ 30 gwei, gas limit 21,000 to 250,000; Tron: reference sizes, fee limit 1 to 100 TRX, timestamp within 2 minutes of the phone clock). The app builds BEP20 / TRC20 `transfer(address,uint256)` calls itself (selector `a9059cbb`).

### 12.4 Server files and providers

| File | Role |
|---|---|
| `wallet_lib.php` | Chain access (BNB Chain JSON-RPC, TronGrid), address checks, exact big-number maths |
| `api/v1/lib_wallet.php` | The `/wallet/*` routes |
| `admin_wallet.php` | On / off switches, USDT limit, provider test, last transactions, REFRESH |
| `db/rpc_config.php` | Provider URLs and keys (**live keys: never publish**) |
| `wallet_tests/` | PHP tests (`wallet_lib_test.php`) and signing vectors (`wallet_vectors.py`) |

Tables `wallet_addresses` and `wallet_txs`. It works out of the box on public endpoints (`bsc-dataseed.binance.org`, `api.trongrid.io`) but they are **shared and rate-limited**: with real members you will see "provider is busy". Get your own keys:

1. **BNB Chain:** pick a provider (Ankr, QuickNode, NodeReal, Alchemy, ...), create a BNB Smart Chain **mainnet** endpoint and paste its URL into `MP_WALLET_BSC_RPC`. If the provider uses a header instead of a key in the URL, set `MP_WALLET_BSC_RPC_KEY` (sent as `Authorization: Bearer`).
2. **Tron (only if you switch it on):** create a key at trongrid.io and put it in `MP_WALLET_TRON_KEY` (sent as `TRON-PRO-API-KEY`).
3. Optional: `MP_WALLET_BSC_EXPLORER_API` / `_KEY` (Etherscan-V2 / BscScan compatible) to show **incoming** BNB Chain transfers in History. Empty = History shows what was sent from the app (plus Tron transfers when on).
4. Open **Admin → Crypto wallet → Test connection**; both lines must be green. Switching provider = edit `db/rpc_config.php` only; the admin page shows only each URL's host name, never keys.

**Admin switches:** global on / off, BNB Chain on / off, Tron on / off, optional maximum USDT per transfer. When off, `/wallet/*` returns `wallet_disabled` (config still answers `enabled:false`).

### 12.5 Fees members must understand

- **BNB Chain:** every send (even USDT) is paid in **BNB**. A BNB transfer costs about 0.00002 BNB; a USDT transfer a little more. A member who holds USDT but no BNB cannot send; the app shows an "Add BNB for fees" warning.
- **Tron (when on):** every send is paid in **TRX** through energy and bandwidth. A USDT transfer burns roughly 5–15 TRX, about **twice** that when the receiver never held USDT. `/wallet/prepare` returns `est_fee_sun` and `fee_ok:false` with a message when TRX is too low. The fee cap is `fee_limit_sun` (default 30 TRX, `MP_WALLET_TRON_FEE_LIMIT_SUN`, lowered to the member's TRX balance when smaller).
- MudaPay charges **no fee** on wallet transfers.

### 12.6 Transaction status and testnets

New transactions are saved `pending`. `GET /wallet/tx` and the admin REFRESH button check the chain: BNB Chain receipt status 1 = confirmed, 0 = failed; Tron `receipt.result = SUCCESS` = confirmed. A Tron transaction unknown after 30 minutes and a BNB Chain one unknown after 24 hours are marked `failed` (dropped). The amount, destination and asset stored are what the app reported; **the blockchain is the source of truth.**

The server is **mainnet only** (BSC chain id 56 is fixed). To test with test coins, use a **separate copy** of the site (BSC testnet RPC in `MP_WALLET_BSC_RPC`, Nile for Tron) and a separate test build of the app using the testnet chain id and contracts. Never mix them on production.

**When a member loses the phrase:** nothing on the server can help; there is no copy. If the phone still works, the phrase can be shown again inside the app after unlocking. If both are lost, the funds are lost. Say so clearly and never ask the member for the phrase.

### 12.7 Status and what is still missing

The wallet features below were written and reviewed but **never compiled against, or run on, a real blockchain in this repository's history**. Do these in order:

1. **Run the GitHub Actions build** and fix compile errors. Most likely trouble spots:
   - `ExternalWallet.kt` was written from the Reown docs without a compiler. Unconfirmed: the Reown BOM version, the imports (`com.reown.android.Core`, `CoreClient`, `relay.ConnectionType`, `appkit.client.AppKit`, `Modal`, `appkit.presets.AppKitChainsPresets`, `appkit.ui.appKitGraph`, `openAppKit`), `Modal.Params.Request(method, params)`, `AppKit.disconnect(onSuccess, onError)`, `ExternalWallet.resultDelegate` and the Accompanist version. If it cannot be fixed quickly: delete `ExternalWallet.kt`, the `ExternalWallet` route in `MainActivity.kt`, the "Connect external wallet" row and `onExternal` in `WalletScreens.kt`, and the Reown lines in `app/build.gradle.kts`. The rest builds without it.
   - `WalletCrypto.kt`: the `org.web3j:crypto:4.8.7-android` version is unconfirmed.
2. **Release (R8) build test:** create / restore a wallet on the release APK (the BIP-39 word list is a resource inside web3j).
3. **Deploy the server files**, add your own BNB Chain RPC key in `db/rpc_config.php`, **Test connection** green, switch the wallet and BNB Chain on. Run `php -l` on `api/v1/lib_wallet.php` and `api/v1/index.php`.
4. **Real-money test with very small amounts (mainnet only):**
   - Create a wallet, write the 12 words, pass the backup check; confirm screenshots are blocked on the phrase screens.
   - Restore the same words (second phone or after removing the wallet) and in Trust Wallet / MetaMask: the same BNB Chain address must appear.
   - Receive about 1 USDT (BEP20) and a little BNB for fees; send 0.5 USDT and a small BNB amount to another wallet you own; check BscScan.
   - Failure cases: wrong address, no BNB for the fee, airplane mode during send, cancelling the fingerprint prompt.
   - External wallet: connect MetaMask or Trust Wallet, make sure the network shows "BNB Chain", send a tiny amount, approve in the wallet app, confirm the app returns to MudaPay; test rejecting the request; test with the wallet app installed and not installed.
   - Test on one Android 6–10 phone and one Android 11+ phone.
5. **Reown dashboard:** add the Android package `io.mudapay.mobile` under App ID (and your domain under Domain) to protect the project. When you regenerate the Project ID, change `ExternalWallet.PROJECT_ID`.
6. Legal / licensing check per country and Google Play crypto-wallet declaration; set a maximum USDT per transfer as a launch safety net; brief support (no one can recover a phrase; staff never ask for it); keep `db/rpc_config.php` out of any shared zip.

### 12.8 App-side wallet features and limits

Built: backup-reminder banner (until the 12 words are confirmed), USD total and per-asset USD, hide-balance toggle, transaction status screen (Pending / Confirmed / Failed + hash + View on BscScan, polls `GET /wallet/tx`), send-confirmation bottom sheet (address with first / last 6 characters highlighted, fee in BNB + USD, total), address book (paste / scan QR / save / pick, stored on the phone per account), larger receive QR with COPY / SHARE and a "BNB Chain only" warning, first-time intro, security tips. Code: `WalletCrypto.kt` (keys + signing), `WalletVault.kt` (Keystore), `WalletScreens.kt`, `WalletExtras.kt`, `ExternalWallet.kt`. Needs the `web3j` dependency and the R8 rules in `proguard-rules.pro`.

Known limits: no Tron and no sending TRX; external wallet is BNB Chain only and MudaPay cannot show the result for external-wallet payments unless the transaction hash is captured from the SDK or pasted by the member; the wallet list shows Reown's catalogue and the app blocks sending unless the network is BNB Chain; no testnet mode, no ownership proof for the registered address. On Android 6–10 the key is **not** hardware auth-bound (only the in-app fingerprint / PIN prompt guards it); on Android 11+ the secure hardware enforces it.

---

## 13. KYC and identity verification

Status flow `draft → pending → verified | rejected`, with admin **VERIFY** / **REJECT** in `admin_agents.php` → "ID verifications". The app sends manual photos or uses the **document scan** (OpenCV finds, crops and straightens the document and measures quality; ML Kit reads the text **on the phone**, bundled model, nothing sent to Google). The member **confirms every value**; the app sends nothing until the consent switch is ticked.

### Files

| File | Purpose |
|---|---|
| `kyc_lib.php` | Tables `id_verification_details` + `id_verification_audit`, AES-256-GCM encryption, input whitelist, admin panel, purge |
| `api/v1/lib_kyc.php` | `POST /account/id/back`, `POST /account/id/details` |
| `api/v1/lib_agent.php`, `api/v1/index.php` | Routes; `GET /account/id` |
| `admin_agents.php` | Back image, read details with warning flags, tick-box "I compared the photos…", audit of every image / details view |
| `cron/kyc_purge.php` | Deletes photos and details of IDs never accepted (draft / rejected) after N days (default 30) |
| `db/kyc_config.php.example` | Where the encryption key goes |

### Setup

1. Make sure `id_docs/` is writable by PHP and still has its `.htaccess` (deny all).
2. Create `db/kyc_config.php` from `db/kyc_config.php.example`. Generate the key once: `php -r "echo base64_encode(random_bytes(32)), PHP_EOL;"`. **Back the key up outside the website.** Without it stored details cannot be read. Never commit it. Until it exists the app still sends photos; only the extracted details are skipped (`kyc_details_enabled: false`).
3. PHP needs `openssl`.
4. Daily cron: `php /path/to/www/cron/kyc_purge.php 30`.

### Data handling

- Details (name, number, dates, ...) are stored **encrypted** (AES-256-GCM). The document number also has an HMAC index so the admin is warned when the same number is used on another account. Raw OCR text is not kept: only parsed fields.
- Photos are stored in `id_docs/` (not reachable by URL, streamed only to a logged-in admin). They are **not encrypted at rest**: if your host allows it, move `id_docs/` outside the web root (change `mp_idv_dir()` in `agent_lib.php`) and restrict file permissions.
- Every admin view of an image or details is written to `id_verification_audit`. Verified IDs are never purged automatically: decide your retention period with your legal adviser and add it to `kyc_purge.php` if needed.
- Account deletion removes the details row (foreign-key cascade).

### Never automatic

No code path sets an ID to `verified` except `mp_idv_review()` called from `admin_agents.php` by a logged-in admin. The server treats everything the phone sends (OCR values, quality scores, "MRZ ok") as **advisory text for the reviewer** and ignores any status the client might send.

---

## 14. Security model

| Area | Measure |
|---|---|
| Transport | HTTPS only; optional TLS public-key pinning in the app |
| Tokens | Access 1 h, refresh 30 d rotated on every use (reuse signs out everywhere); only SHA-256 hashes stored; on the phone stored with Android Keystore AES-GCM |
| Passwords | 10–72 bytes, common / sequence / personal-data / breach checks (HIBP k-anonymity); brute-force limits on login |
| Transaction PIN | Mandatory 4–6 digits, hashed (`password_hash`), 5 wrong tries lock 15 min, counted server side |
| Money integrity | Idempotency keys, single-transaction ledger changes with row locks, credits only after verified provider confirmation, double-click-safe payouts |
| Trust boundary | Server ignores client balances / rewards / rates / durations / statuses; QR text and client OCR values are advisory |
| Privacy | Masked phones and "Jane D." names in lookups; location rounded to ~1 km and never stored; agent pins offset ~100 m; PII masked in error reports |
| Devices | Session list, remote sign-out, new-device alert + push + security log |
| KYC | Encrypted details, audit of every admin view, admin-only verification, purge cron |
| Crypto | Keys never leave the phone; server sees only public addresses and signed transactions |
| Files | Private folders closed by `.htaccess`; `db/` blocked at web root; `*.md`, `*.sql`, `*.zip`, `*.ini`, `*.log`, dot-files denied |
| App | App lock (biometric / PIN), `FLAG_SECURE` on sensitive screens, encrypted offline cache |
| Webhooks | Provider signature checks (HMAC-SHA512 for Payfonte and NOWPayments), re-fetch from the provider before credit |
| Rate limits | Lookups 30 / 15 min, contact checks 12 / 15 min, e-mail codes, promo guesses 10 / 15 min, support 10 tickets and 20 messages / 10 min, claim attempts throttled |

---

## 15. Operations

### 15.1 Monitoring and alerts

- Crash, ANR and unexpected-answer reports are uploaded by the app to `POST /client/errors`, stored in `error_logs`, shown in `admin_errors.php` and e-mailed to `MP_ALERT_EMAIL` (`db/alert_config.php`).
- Deposits in review, partial payments and new tickets / disputes are no longer announced on Telegram / WhatsApp (removed in 3.20.7): check them in the admin pages.
- Muda AI answers are grounded in `db/knowledge/mudapay_knowledge.md` (identity, countries, flows, FAQ, troubleshooting, hard rules) and `education_playbook.md`. **Update the knowledge base whenever behaviour changes**, or Muda AI will describe outdated features.

### 15.2 Backups

- **Database:** regular dumps (schema is `db.sql`; data is only in your live database).
- **`db/kyc_config.php`:** back up outside the site; without the key stored ID details are unreadable.
- **Signing keystore:** keep forever; Android refuses updates signed with a different key.
- **`id_docs/`, `avatars/`, `releases/`:** include in file backups.

### 15.3 Debug tools

A temporary switch for diagnosing provider problems.

- Every call to Payfonte, NOWPayments, Airtel and BitLibera records what the provider really answered (HTTP code, curl error, body), with secrets, e-mails and phone numbers masked.
- While the switch exists, failed API answers carry that text in `error.debug`; the app shows it under the normal message. (The separate "MudaPay Debug" launcher icon was removed in 3.19.2 for Google Play.)
- `GET /api/v1/debug/errors` (signed-in member, switch on) returns the newest `error_logs` rows; `GET /api/v1/market/status` shows which market providers answer from your host.
- **On:** `touch db/api_debug.flag`. **Off:** delete the file as soon as you have the answer. **It must not exist in production.**

### 15.4 Maintenance notes

- Cache files in `db/` (`fx_cache.json`, `market_cache/`, `np_min_cache.json`, `paypal_token.json`) are regenerated automatically.
- A deleted account's avatar file is not removed from `avatars/` automatically.
- When updating a site, delete files removed from the package by hand (see [§6.2](#62-install)).

---

## 16. Go-live checklist

### Server

- [ ] **Delete `db/api_debug.flag`.** While it exists, failed API answers carry the providers' raw replies. *(It is present in the current package.)*
- [ ] Database imported from `db.sql` into an empty database **before** the first visit.
- [ ] Folders writable (`releases/`, `users/avatars/`, `avatars/`, `id_docs/`, `db/`), `.htaccess` files intact; HTTPS working.
- [ ] Payfonte: live keys with `PF_BASE` on `https://api.payfonte.com`; webhook URL saved in the dashboard; Disbursements PIN and funded wallet.
- [ ] Airtel: live keys, then `AIRTEL_BASE` on `https://openapi.airtel.cd` *(the config currently points to the UAT host `openapiuat.airtel.cd`)* and **Go Live** in the Airtel portal; callback URL set for Collection and Disbursement.
- [ ] PayPal: live app, `PP_ENV = 'live'`, live webhook ID in `PP_WEBHOOK_ID`, `PP_WEBHOOK_STRICT = true` *(currently sandbox)*.
- [ ] NOWPayments: API key and IPN secret set (an empty key switches crypto deposits off).
- [ ] Crypto Wallet: own BNB Chain RPC (and Tron key if used) in `db/rpc_config.php`, **Test connection** green in `admin_wallet.php`, legal check done, a real test on a small amount (see [§12.7](#127-status-and-what-is-still-missing)).
- [ ] Google: `MP_GOOGLE_WEB_CLIENT_ID` equals the app's `GOOGLE_WEB_CLIENT_ID`.
- [ ] Rates and fees reviewed in `admin_fx.php` (built-in rates are only a starting point).
- [ ] Secrets that were ever shared in a chat or screenshot (Payfonte, Airtel, NOWPayments, Firebase) **rotated**.
- [ ] Cron jobs created.
- [ ] The APK uploaded in admin is a **release** build (not debuggable).
- [ ] `db/kyc_config.php` backed up outside the site.
- [ ] Support team hours set; Muda AI knowledge base up to date.

### Android

- [ ] `versionCode` higher than the published build; APK signed with the production keystore.
- [ ] Built with `assembleRelease` (the CI workflow produces a debug build, which Google Play refuses).
- [ ] `SITE` points to the production server; `GOOGLE_WEB_CLIENT_ID` matches the server; the Google OAuth app is published and the production SHA-1 (CI key and Play App Signing key) is registered.
- [ ] `google-services.json` is the production Firebase config.
- [ ] Upload the APK in **admin → App updates** and check the download page and the in-app update prompt.

---

## 17. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| "Google sign-in is not available right now" | Wrong Web client ID or unregistered SHA-1. Check `adb logcat -s MudaPay`; register the SHA-1 printed by the CI step "Show the APK's SHA-1" |
| "No Google account was found" | The phone has no Google account |
| Google works locally but not on CI builds | CI is not using the stable keystore: set the `KEYSTORE_*` secrets |
| Push never arrives | `google-services.json` missing, `MP_FCM_SERVICE_ACCOUNT_FILE` empty / unreadable, no outbound HTTPS to Google, or battery / Autostart restrictions on Xiaomi / Tecno / Infinix. Use Settings → Push notifications → test and the PHP error log |
| `426 https_required` | Host hides HTTPS from PHP: set `MP_API_REQUIRE_HTTPS` to false |
| 401 although the token is valid | Host strips `Authorization`: the app may send `X-MP-Token` |
| API answers carry provider text in `error.debug` | `db/api_debug.flag` exists: delete it in production |
| APK upload fails | `upload_max_filesize` / `post_max_size` below the APK size, PHP `zip` missing, or `versionCode` not higher / different keystore |
| Profile pictures unavailable | PHP `gd` missing |
| Airtel payout answers `ROUTER116` (incorrect encrypted PIN) | Set `AIRTEL_OAEP_MGF` to `'sha1'` in the config |
| Airtel: only USD accepted on wallets | Amounts here are CDF; contact Airtel to enable CDF |
| Deposits "paused for a few minutes" | Fewer than 2 live FX feeds agree on that currency: check `admin_fx.php` and outbound HTTPS |
| NOWPayments refuses "below minimum" | Account minimum higher than network minimum: check dashboard Store settings and `db/np_min_cache.json` |
| Wallet "provider is busy" | Shared public RPC: add your own keys in `db/rpc_config.php` |
| Wallet send blocked | Not enough BNB for gas (`fee_ok:false`) |
| Password breach check fails / sign-up slow | Host blocks outgoing HTTPS: create `db/pwned_check.off` |
| Withdrawal country shows crypto only | Country has no payout row in `PF_COUNTRIES` / `methods` (by design for card + crypto countries) |
| Stored ID details unreadable | `db/kyc_config.php` key lost or changed |
| Market data stale | All providers failing from your host; last good answer (≤ 48 h) is served with `stale:true` |

---

## 18. Credentials and AI-assistant rules

These rules apply to **any person, AI model, assistant, code generator or automated tool** that edits this project. They have priority over "best practices", security hardening, refactoring and tidying.

> **Never remove, blank, replace, rename, "clean up", mask, move, regenerate or "secure" any credential in this project, even if it looks unsafe, unused, duplicated or a "security risk". Only the project owner may change credentials, and only when he explicitly says so.** If you think a credential is dangerous, say so in your answer, but leave it exactly as it is.

### What counts as a credential

**Android package**
- `app/google-services.json` (Firebase / FCM config): never delete, empty or replace with a placeholder.
- `app/mudapay.keystore` and any `*.keystore` / `*.jks`: the signing key. If lost, Android refuses updates. Never delete, regenerate or replace it.
- Signing values in `app/build.gradle.kts`: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` and the `signingValue(...)` lookup (environment variable or Gradle property). Keep the `signingConfigs` / `mudapay` block working.
- `.github/workflows/android.yml` and `create-keystore.yml`: the `secrets.*` references (`GOOGLE_SERVICES_JSON`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and the steps that decode the keystore and write `google-services.json`.
- `Config.kt` values: `SITE`, `API_BASE`, `DELETE_URL`, `GOOGLE_WEB_CLIENT_ID`, `CERT_PINS`: do not change, empty, or "move to BuildConfig" unless asked.
- Any API key, token, client ID, password, URL with a key, or secret anywhere in the code, resources, `gradle.properties` or `local.properties`.

**Server package** — all files in `db/` hold the **real live keys**; keep them complete and unchanged: `db.php`, `payfonte_config.php`, `nowpayments_config.php`, `paypal_config.php`, `airtel_config.php`, `bitlibera_config.php`, `fcm_config.php`, `kyc_config.php`, `mail_config.php`, `notify_config.php`, `google_config.php`, `rpc_config.php`, `ai_config.php`, `alert_config.php`, plus any OpenRouter / AI keys used by `task_ai.php` and `agent_lib.php`, and any `define(...)` credential, webhook secret, token or password elsewhere in the PHP code, `.htaccess` or `.user.ini`. The `*.example` files are templates only: **never copy their placeholders over the real files.**

### What you must do when editing

1. Keep every credential line **byte-for-byte identical** unless the owner asked to change that exact value.
2. Do **not** replace credentials with placeholders such as `YOUR_KEY_HERE`, `xxxx`, `<secret>`, `""` or `TODO`.
3. Do **not** delete credential files or leave them out of the zip you return.
4. Do **not** move credentials to another file, `.env` or environment variables unless asked; do not refactor config loading in a way that changes where values come from.
5. Do **not** rewrite a whole file from memory if it contains credentials: edit only the lines you need.
6. **Return the project complete:** the final zip / files must contain every credential file that was in the zip you received.
7. **Before you finish,** compare credential files and lines before and after; restore anything missing or changed.
8. Do **not print credentials** in chat answers or documentation; refer to them by file name and variable name.
9. If a task seems to *require* touching a credential, **stop and ask the owner first.**

**Allowed:** adding new code, fixing bugs, adding endpoints or admin pages, changing the UI or database schema, editing cron jobs, bumping `versionCode` / `versionName`, as long as every credential stays untouched. *Edit around the credentials, never through them.*

### Handling the packages

The packages contain **live credentials**: never publish them, never commit `db/*.php` (except `*.example`), keep them out of any zip you share, and rotate any secret that was ever pasted into a chat or screenshot.

---

## 19. Release notes

### App

| Version | Highlights |
|---|---|
| **3.17.2** (current, `versionCode` 39) | Current build |
| **3.16.0** | Support and disputes (tickets linked to a transaction, team hours, Muda AI hand-off) |
| **3.15.0** | Account security, connected devices, KYC-based limits |
| **3.14.x** | Dark mode and appearance settings; currency flags served by the server |
| **3.12 – 3.13** | Markets (crypto charts, currency rates, add-your-own coin) |
| **3.11** | KYC document scan; Honeycoin removed; alphabetical country list; crypto-only countries handled automatically |
| **3.9 – 3.10** | Profile photos, merchant payments and shop mode |
| **3.5** | Full visual redesign |
| **3.0 – 3.4** | Cross-border transfers, mandatory transaction PIN, payment requests, group requests, agents and bureaus, phone contacts (removed in 3.20.8) |
| **2.x** | Scan to pay and dot QR codes, share-the-app and Muda AI captions, voice chat with Muda AI, Continue with Google, push notifications, app lock, offline mode, send / receive money, crypto deposits |

The previous documents did not record exact version numbers for these additions to the 3.1x–3.17 line: PayPal deposits, France / Bulgaria / Andorra / Czechia, invitation transfers, promo codes, vouchers and merchant cashback, the verified badge, the Czech / Hausa / Portuguese / Swahili / Kinyarwanda translations, and the Crypto Wallet with Connect external wallet ([§12](#12-crypto-wallet-self-custody)).

### Server

- Countries: 10 original → 34 (24 mobile-money countries) → 54 (20 card + crypto countries, all of Africa) → 60 (UK, US, France, Bulgaria, Andorra, Czechia via PayPal).
- Referral bonus switched off; "invite 2 friends to withdraw" rule removed.
- Trading removed (Markets are information only); Honeycoin removed; PayPal, invitation transfers, promo codes, KYC limits, devices and support / disputes added.
- Complete schema consolidated into `db.sql` (65 tables).

---

## 20. Known limits and open items

- **Crypto Wallet** is not ready for real money until compiled, release-tested and verified on mainnet with small amounts ([§12.7](#127-status-and-what-is-still-missing)). External-wallet code was written without a compiler.
- **Sandbox / UAT endpoints:** PayPal is configured for sandbox and Airtel for the UAT host in the current package.
- **Phone numbers are not SMS-verified at registration.** This limits how well invitation welcome bonuses can be protected from fake-number farming; an SMS check would close it ([§9.5](#95-send-to-a-non-member-invitation-transfer)).
- **KYC photos are not encrypted at rest** in `id_docs/`; consider moving the folder outside the web root.
- **Retention:** no automatic purge of verified IDs; decide a retention period with a legal adviser.
- **PayPal refunds / disputes** after crediting are flagged for review but do not debit the balance automatically.
- **Referral bonus is off** (`MP_REFERRAL_PERCENT = 0`).
- **Provider limits:** Payfonte limits are defaults, not published values; several currencies (SSP, GNF, SLE, LRD, AOA, LYD, ETB, MWK, EGP, SOS, SDG, ERN) have large official-vs-street gaps and need manual rate review.
- **Lumicash (Burundi):** payouts are manual.
- **OpenStreetMap tiles** power the agent locator; swap the tile source in `AgentLocator.kt` for a paid provider before heavy production use.
- **Legal:** crypto-wallet licensing, KYC / AML, consumer-protection and Google Play policy must be checked per country; nothing in this document is legal advice.

---

## Appendix A: documents merged into this README

This README replaces the following files. Their content is consolidated above; where an older document disagreed with the code or with a newer document, the current code and `db.sql` were treated as the source of truth.

**Android package:** `README.md`, `BACKEND_API_CONTRACT.md`, `WALLET_STATUS_AND_TODO.md`, `README_AI_NEVER_REMOVE_CREDENTIALS.md` (app side).

**Server package:** `README_SERVER.md`, `README_API.md`, `README_AGENTS.md`, `FEES_AND_REVENUE.md`, `SECURITY_AND_LIMITS.md`, `DEPOSIT_SETUP.md`, `NOWPAYMENTS_SETUP.md`, `WALLET_SETUP.md`, `WALLET_STATUS_AND_TODO.md`, `PAYFONTE_SETUP.md`, `PAYFONTE_COUNTRIES.md`, `AIRTEL_SETUP.md`, `LUMICASH_SETUP.md`, `PAYPAL_SETUP.md`, `MARKET_SETUP.md`, `KYC_SETUP.md`, `FCM_PUSH_SETUP.md`, `PROFILE_PHOTO.md`, `MERCHANT.md`, `LITE_AND_QUOTE.md`, `DEBUG_TOOLS.md`, `INVITE_TRANSFERS.md`, `PROMO_CODES.md`, `SUPPORT_SETUP.md`, `LANDING_PAGE.md`, `README_AI_NEVER_REMOVE_CREDENTIALS.md` (server side).

**Not documentation (kept as-is):** `db/knowledge/mudapay_knowledge.md` and `db/knowledge/education_playbook.md` are the Muda AI knowledge base read by `coach_lib.php`.
