# Proposed MudaPay shared API contract (integration phase)

**Status: contract proposal only. These endpoints do not exist yet and are not called by the current app.** Implement them in the PHP server at `https://openinvest.alwaysdata.net/api/v1/`, reusing the website's existing PDO/database/business rules. Do not modify the web app's current routes to make the APK work.

## General rules

- HTTPS only; JSON request/response except multipart uploads.
- Use the existing database and the same service/business logic as web pages. Do not create a second balance or rewards ledger.
- Never include database credentials, AI-provider keys, payment-provider secrets, or admin secrets in the APK.
- Validate every amount, ownership, reward eligibility, payment state, and withdrawal state on the server. Use transactions and idempotency keys for financial writes.
- Use short-lived bearer tokens with refresh/revocation, or a carefully configured secure session mechanism. Passwords must never be returned or logged.
- Apply rate limits, CSRF protections where cookie auth is used, authorization checks, and generic authentication errors.
- Standard response shape: `{ "ok": true, "data": ... }`; error shape `{ "ok": false, "error": { "code": "...", "message": "..." } }`. Never expose SQL/PHP stack traces.

## Proposed routes

| Method + route | Purpose |
|---|---|
| `POST /auth/register` | Create account using website registration rules and referral attribution |
| `POST /auth/login` | Authenticate member; return access/refresh credentials or secure session |
| `POST /auth/refresh` | Rotate refresh credential |
| `POST /auth/logout` | Revoke session/refresh token |
| `GET /me` | Member profile and account status |
| `GET /dashboard` | Server-calculated balance, rewards, totals, quick status |
| `GET /transactions?cursor=` | Paginated transaction history |
| `GET /tasks` | Eligible tasks |
| `POST /tasks/{id}/complete` | Validate task completion and reward idempotently |
| `GET /videos` | Official video list with counts and my reaction |
| `POST /videos/{id}/reaction` | Like, dislike or clear (no rewards) |
| `GET /campaigns` | Share campaigns and assets |
| `POST /campaigns/{id}/proofs` (multipart) | Upload share proof for review |
| `GET /leaderboard?period=month` | Ranked members and current member rank |
| `GET /withdrawals/rules` | Server-defined minimums, methods, and limits |
| `POST /withdrawals` | Create withdrawal request; server validates available balance and limits |
| `GET /withdrawals` | Member withdrawal history |
| `GET /notifications` | Notifications + unread count |
| `POST /notifications/{id}/read` | Mark notification read |
| `GET /settings` / `PATCH /settings` | Read/update permitted member preferences |
| `GET /support/tickets` / `POST /support/tickets` | Support requests |
| `GET /coach/context` / `POST /coach/messages` | Muda AI with all provider secrets server-side |

## Required API behavior

- Preserve existing web rules from `payment.php`, `withdraw.php`, `task.php`, `video.php`, `share.php`, `share_review.php`, `history.php`, `reward_rates.php`, and related helpers. Refactor shared PHP service functions if needed so both web routes and API call the same logic.
- Do not implement an API endpoint that trusts a client-submitted balance, reward amount, video duration, or withdrawal status.
- Confirm actual schema/state names before implementing status enums. Do not assume the database model matches a generic template.
- Add pagination, upload MIME/size limits, private proof storage, ownership checks, and audit logs.
- Test with a staging database or test accounts before connecting production accounts.


## Added in 2.1.0 (needed by the new app features)

| Method + route | Purpose |
|---|---|
| `POST /devices` | Body `{token, platform:"android", app_version, categories:{payment,withdrawal,bonus,streak}}`. Upsert the FCM token for the signed-in member (a token seen on another account is re-assigned). Delete the row when that session is logged out/revoked. |
| `POST /account/delete` | Body `{password, confirm:true}`. Verify the password (answer **422**, not 401, when wrong), apply your retention/anti-fraud rules (you may refuse, e.g. pending withdrawal, with a clear `message`), then delete or anonymise the member, revoke all tokens, remove device rows. Return `{message}`. |
| `GET /referrals` | `{symbol, summary:{total, active, pending, earned}, items:[{name, phone_masked, joined_at, status:"active"\|"pending", earned}]}`. Mask phone numbers; never return other members' balances. |

`GET /withdrawals` items should also carry, when available: `reference` (or `id`), `account_number` (full or masked, the app shows the last 4), `reviewed_at`, `paid_at`, `note` (rejection reason).

### Sending push (FCM HTTP v1 only; the legacy API is shut down)
Send **data-only** messages so the app controls display and the member's category choices: `data = {type, title, body, route?}`.

| type | when | default screen |
|---|---|---|
| `withdrawal_paid` / `withdrawal_update` / `withdrawal_rejected` | admin changes withdrawal status | Withdraw |
| `daily_bonus` | cron, once a day: reminder to open the app (weekly login bonus) | Home |
| `streak_risk` | cron in the evening: a missed day cancels this week's bonus | Home |

Also skip members whose `categories[x]` is false. Authenticate with a Firebase service-account key kept on the server only.
Never put amounts or phone numbers in a push that is not needed.


## Added in 2.2.0 — Google sign-in (implemented in `htdocs/api/v1/lib_google.php`)

| Method + route | Purpose |
|---|---|
| `POST /auth/google/nonce` | Public. Returns `{nonce}` (single use, 10 min). The app passes it to Google; it comes back inside the ID token. |
| `POST /auth/google` | Body `{id_token, device}`. Server verifies the token (RS256 signature with Google's certificates, `iss`, `aud` = Web client ID, `exp`, `email_verified`, nonce burned). Answers `{status:"signed_in", tokens, user}` or `{status:"needs_profile", profile_token, name, email}`. |
| `POST /auth/google/complete` | Body `{profile_token, full_name, phone, country, referrer?, password?, device}`. Creates the member (active, random unusable password) or, when the phone exists and the correct `password` is sent, links Google to that member. `409 phone_taken` + `can_link:true` when a password is needed. Returns `{status:"signed_in", is_new, tokens, user}`. |

| `POST /google/link` | Signed in. `{id_token}` (nonce from `/auth/google/nonce`). Adds Google to the current account. `409 google_already_linked` / `409 google_in_use`. |
| `POST /google/unlink` | Signed in. `{password}`. `409 password_required` if the account has no password yet; `422 wrong_password`. |

`user` now also carries `google_email` and `google_linked` and `has_password` (false = created with Google, no password chosen).
`POST /settings/password` accepts a missing `current_password` only while `has_password` is false.
**`/account/delete` (built in `lib_account.php`):** when `has_password` is false, accept `{confirm:true}` without `password`.
Errors: `503 google_unavailable` (not configured), `401 invalid_google_token`, `401 google_session_expired`, `409 google_already_linked`, `403 account_banned`.

### Status of the 2.1.0 endpoints
`POST /devices`, `GET /referrals` and `POST /account/delete` are now implemented server-side (`htdocs/api/v1/lib_account.php`).
Still to build: the FCM sender (cron + admin hooks) that reads `api_devices` and honours `cat_payment / cat_withdrawal / cat_bonus / cat_streak`.


## Crypto withdrawals

`GET /withdrawals/rules` also returns `crypto: {enabled, min_usd, local_per_usd, currency, coins:[{code,label,network}]}`.
`POST /withdrawals` accepts `method: "Crypto"` with `crypto_currency` (a coin `code`) and `account_number` = wallet address; the server validates the address for that coin, fixes the USD value at request time and answers with `amount_usd`.
`GET /withdrawals` items add `crypto_currency`, `amount_usd`. Withdrawals are never paid automatically: only an admin can mark one paid or rejected. Error code `invalid_crypto` (422).


## Push payload (2.4.0)
FCM v1, **data-only**, `android.priority = HIGH`. Keys: `type`, `route`, `title`, `body`, optional `details` (lines separated by `|`) and `withdrawal_id`.
The app builds the notification itself (also while it is open) and de-duplicates by `withdrawal_id` + status, so the status check in the app never repeats a push.
`withdrawal_paid` / `withdrawal_rejected` carry the amount, method and reference (see `mp_push_withdrawal()` in `push_fcm.php`).

## v2.7 — send / receive money and e-mail verification
See `README_API.md` in the server package ("Send / receive money + e-mail verification"). App side: `TransferScreens.kt`
(routes `Send`, `Receive`), `EmailVerify.kt` (card at the top of Settings), notification types `payment_transfer_out`,
`payment_transfer_in`, `email_code_sent`, `email_verified` (same notification style as withdrawals; "Payment updates" switch controls the money ones).

## v2.8 — crypto deposits
Implemented in `htdocs/api/v1/lib_deposit.php` (server package, see `DEPOSIT_SETUP.md`). App side: `DepositScreens.kt` (route `Deposit`),
history type `deposit`, push type `payment_deposit`. The app only shows the server quote and polls `GET /deposits/{id}`; it never decides the credited amount.

## v2.9 — scan to pay (no new endpoints)
QR codes only carry the receiver's phone (and optionally an amount / note): `mudapay://pay?v=1&p=%2B<phone>[&a=<amount>][&n=<note>]`. The app resolves them with the existing
`POST /transfers/lookup` (shows the server's name for that number) and pays with `POST /transfers`. The server never trusts the amount in a QR code: the usual minimum, daily limit, balance, email and same-country-or-supported-country checks apply.

## v3.0 — send to other countries
Same screens (`TransferScreens.kt`), new server behaviour (see `README_API.md`, "Send / receive money"). The sender picks the receiver's country (`GET /transfers/rules` → `countries`),
`POST /transfers/lookup {phone, country}` returns the receiver's country/currency, `POST /transfers/quote {country, amount}` returns `{receive_amount, rate, fee, fee_pct}` and
`POST /transfers {phone, amount, country, note, expected_receive}` debits `amount` in the sender's currency and credits `receive_amount` in the receiver's. The app only displays the server quote;
if the rate moved the server answers `rate_changed` (409) with a fresh `quote`. History items carry `symbol`, `counter_amount`, `counter_symbol`, `cross_border`, `counterparty.country`.
QR codes now also carry the receiver's country: `mudapay://pay?v=1&p=<phone>&c=<cc>[&a=…][&n=…]` (old codes without `c` still work for same-country payments).

## v3.1 — transaction PIN
Mandatory 4–6 digit PIN, created on first sign-in (`PinSetupHost`) or in Settings; it cannot be turned off (`TransactionPin.kt`). Server: `GET/POST /account/pin`, `POST /account/pin/change`, `POST /account/pin/disable` (see `README_API.md`, "Transaction PIN").
While it is on, `POST /transfers` and `POST /withdrawals` need `pin` in the body (`pin_required` / `wrong_pin` / `pin_locked` otherwise). The app asks for it after the app-lock check, using `pin_required`
from `GET /transfers/rules` and `GET /withdrawals/rules`; it never stores the PIN. The server stores only a hash and locks the check for 15 minutes after 5 wrong tries.

## v3.2 — payment requests
`RequestScreens.kt` (route `Requests`; also reachable from Receive → REQUEST MONEY and More). Server: `lib_request.php`, table `payment_requests`.
`GET /requests?box=in|out`, `POST /requests {phone, country, amount, note}` (amount in the REQUESTER's currency), `POST /requests/{id}/pay {pin?, expected_pay}` (+ `Idempotency-Key`),
`POST /requests/{id}/decline`, `POST /requests/{id}/cancel`. Paying runs the normal transfer code (PIN, daily limit, FX, balance lock) and closes the request in the same DB transaction.
Items for the payer carry `pay: {amount, symbol, fee, rate, receive_amount, receive_symbol, cross_border}` = what he pays so the requester gets at least the requested amount.
Push type `payment_request_in` (payment category, route `Requests`). Requests expire after 7 days; max 10 open per member.


## v3.4 — pick contacts to send money
New routes (see `lib_contacts.php`): `POST /contacts/sync`, `POST /contacts/check`, `GET /contacts`, `POST /contacts/invite`, `POST /contacts/clear`.
App: `ContactsScreen.kt` (permission `READ_CONTACTS`, requested only after the member taps LOAD PHONE CONTACTS and agrees). Flow: load contacts -> saved on the server -> CHECK FOR FRIENDS -> list of members -> SEND -> amount -> transaction PIN -> `POST /transfers`. Contacts who are not members get an INVITE button (SMS with the member's referral link).


## v3.9 — profile pictures
Server: `lib_avatar.php`, table `member_avatars` (created on first use; the `users` table is not touched). App: `Avatars.kt`.

| Method + route | Auth | Purpose |
|---|---|---|
| `GET /account/avatar` | signed in | `{avatar_url: string\|null}` — the member's own photo |
| `POST /account/avatar` | signed in | multipart, field `file` (JPEG / PNG / WebP, max 6 MB). The server centre-crops, resizes to 256 px, re-encodes as JPEG (EXIF stripped) → `{avatar_url}`. Errors: `no_file`, `bad_image`, `file_too_large` (413), `too_fast` (429), `avatar_unavailable` (503, GD missing) |
| `POST /account/avatar/remove` | signed in | deletes the photo → `{avatar_url: null}` |
| `POST /avatars/lookup` | member | body `{phones:[…]}` (max 30) → `{avatars:{"<phone as sent>": url}}`; only members who have a photo are listed. Phones are matched with or without `+`, spaces and dashes |
| `GET /avatar/{token}` | public | streams the picture. `token` = 32 random hex chars, new with every upload (so the picture is cached for a year and a replaced picture's old link dies). A deleted account's picture is no longer served |

Optional, for extra speed: add `"avatar": mp_av_url_for_user($pdo, $memberId)` (or `mp_av_urls_for_users()` for lists) next to the member's name in `counterparty`, `recipient`, `member` and the `/transfers/lookup` answer. The app uses it when present and skips the lookup request.


## v3.10 — merchant payments
Server: `lib_merchant.php`, table `merchant_profiles` (created on first use; `users` untouched). App: `Merchant.kt` (routes `Merchant`, `ShopMode`), shop QR in `ScanPay.kt` (`PayQr`), verified business on the pay screen (`TransferScreens.kt`), receipts (`Receipt.kt`).

| Method + route | Auth | Purpose |
|---|---|---|
| `GET /merchant/me` | member | `{profile: {business_name, category, city, address, code, since}\|null, categories:[…]}` |
| `POST /merchant/me` | member | `{business_name (2–40), category, city?, address?}` creates or updates the profile; the shop `code` never changes → `{profile}` |
| `POST /merchant/remove` | member | deletes the business profile (account and personal QR stay) → `{profile:null}` |
| `POST /merchant/lookup` | member | `{phone}` → `{merchant:{business_name, category, city, code}\|null}`. The payer's app shows the verified name before confirming |

Money is NOT handled here: customers pay with the normal `POST /transfers` (free for the shop, instant). Shop QR = `mudapay://pay?v=1&p=<phone>&c=<cc>&m=<shop code>`; `m` is only a hint, the app trusts `/merchant/lookup`, never the QR.
Daily sales summary = received transfers of the day (`GET /transfers?direction=in`, summed on the phone). Payout = the normal withdrawals. Shop mode polls `GET /transfers?direction=in` every 4 s while open and plays `TxSound` once per transfer reference (same key as the push, so it never plays twice).
`index.php` output hook (`mp_av_enrich_buffer`) also adds `"business": "<name>"` next to `counterparty` / `recipient` / `member` when that member has a business profile.

## v3.3 — group requests (split payments)
App: `GroupScreens.kt` inside `RequestScreens.kt` (tabs Received / Sent / **Groups** / New; New has a "One person | Split with a group" switch). Server: `lib_group.php`.
`GET /groups`, `GET /groups/{id}`, `POST /groups {title, note?, split: equal|custom, total?, include_me?, members:[{phone, country?, amount?}]}` (+ `Idempotency-Key`),
`POST /groups/{id}/remind` (+ `Idempotency-Key`), `POST /groups/{id}/cancel`. Every member gets an ordinary payment request (its item has `group: {id, title}`),
paid/declined with the existing `/requests/{id}/pay|decline`. The requester sees per-person status (`pending|paid|declined|cancelled|expired`), `collected` / `waiting` totals and can remind or cancel.
Reminders reuse push type `payment_request_in` (route `Requests`). Limits: 10 people per group, 5 open groups per member, one reminder per group every 12 h.


## v3.11 — KYC document scan
App: `KycScreens.kt` (flow + camera), `KycDocScanner.kt` (OpenCV: detect, crop, straighten, quality), `KycOcr.kt` (ML Kit + MRZ/label parsing), opened from the
"SCAN MY DOCUMENT" button of the ID card on the Agent desk. Server: `kyc_lib.php`, `api/v1/lib_kyc.php`; routes `POST /account/id/back`, `POST /account/id/details`
(see `README_API.md`, "KYC add-on"). The phone only suggests values: the member confirms them, a MudaPay admin verifies the document by eye, and the server never trusts
OCR values, quality scores or any client-sent status.


## Market (MarketScreens.kt)
Uses `GET /market/coins` (15 s poll), `/market/ticker?symbol=` (5 s poll on a coin), `/market/candles?symbol=&range=1D|1W|1M|3M|1Y`,
`/market/fx` and `/market/fx/history?quote=&range=1W|1M|3M|1Y`. Provider failures answer HTTP 424 `market_unavailable`.

## Invest (Alpaca Broker API, live) — /trading/*
All routes need a signed-in member. Keys stay on the server. Screens: `TradingScreens.kt` (menu entry "Invest").
- `GET /trading/status` · `GET|POST /trading/account` · `GET /trading/positions`
- `GET /trading/assets?q=&class=us_equity|crypto` · `GET /trading/quote?symbol=` · `GET /trading/bars?symbol=&range=1D|1W|1M|3M|1Y`
- `GET /trading/orders` · `POST /trading/orders` (Idempotency-Key + `pin`) · `POST /trading/orders/{id}/cancel`
