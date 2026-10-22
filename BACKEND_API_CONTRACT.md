# Proposed MudaPay shared API contract (integration phase)

**Status: contract proposal only. These endpoints do not exist yet and are not called by the current app.** Implement them in the PHP server at `https://muda-pay.42web.io/api/v1/`, reusing the website's existing PDO/database/business rules. Do not modify the web app's current routes to make the APK work.

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
