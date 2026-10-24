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
| `GET /me` | Member profile and activation/account status |
| `GET /dashboard` | Server-calculated balance, rewards, totals, quick status |
| `GET /transactions?cursor=` | Paginated transaction history |
| `GET /payments/instructions` | Current payment methods and activation instructions |
| `POST /payments/proofs` (multipart) | Upload payment proof and return pending review ID |
| `GET /payments/{id}` | Payment proof review state |
| `GET /tasks` | Eligible tasks |
| `POST /tasks/{id}/complete` | Validate task completion and reward idempotently |
| `GET /videos` | Video list and eligibility metadata |
| `POST /videos/{id}/watch-events` | Record validated watch events |
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
- Do not implement an API endpoint that trusts a client-submitted balance, reward amount, activation status, video duration, or withdrawal status.
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
| `payment_approved` / `payment_rejected` | admin reviews activation proof | Home |
| `withdrawal_paid` / `withdrawal_update` / `withdrawal_rejected` | admin changes withdrawal status | Withdraw |
| `daily_bonus` | cron, once a day at the member's morning, only if not claimed | Home |
| `streak_risk` | cron in the evening, only if streak > 0 and not claimed today | Home |

Also skip members whose `categories[x]` is false. Authenticate with a Firebase service-account key kept on the server only.
Never put amounts or phone numbers in a push that is not needed.


## Added in 2.2.0 — Google sign-in (implemented in `htdocs/api/v1/lib_google.php`)

| Method + route | Purpose |
|---|---|
| `POST /auth/google/nonce` | Public. Returns `{nonce}` (single use, 10 min). The app passes it to Google; it comes back inside the ID token. |
| `POST /auth/google` | Body `{id_token, device}`. Server verifies the token (RS256 signature with Google's certificates, `iss`, `aud` = Web client ID, `exp`, `email_verified`, nonce burned). Answers `{status:"signed_in", tokens, user}` or `{status:"needs_profile", profile_token, name, email}`. |
| `POST /auth/google/complete` | Body `{profile_token, full_name, phone, country, referrer?, password?, device}`. Creates the member (unpaid, random unusable password) or, when the phone exists and the correct `password` is sent, links Google to that member. `409 phone_taken` + `can_link:true` when a password is needed. Returns `{status:"signed_in", is_new, tokens, user}`. |

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
