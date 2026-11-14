# MudaPay API — what the app uses

Base URL: `<site>/api/v1` (set in `Config.kt`). The full request / response details, limits and error codes are in the server package (`README_API.md` and the `*_SETUP.md` files). This page is the app-side index.

## Conventions

- HTTPS only, JSON (multipart for uploads). Success `{"ok":true,"data":…}`, error `{"ok":false,"error":{"code":"…","message":"…"}}`.
- Auth: `Authorization: Bearer <access_token>` (1 h). `POST /auth/refresh` rotates the refresh token (30 days); reusing an old refresh token signs the member out everywhere. If a host strips the header, the app may send `X-MP-Token`.
- Money writes send `Idempotency-Key` (16–64 chars). A retry with the same key returns the first answer.
- Transfers, requests, withdrawals and trade orders also send the transaction `pin`.
- The server never trusts a client-sent balance, reward, rate, duration or status. The app only displays server answers.
- No secrets in the APK: payment, AI and push keys live on the server.

## Routes by area

| Area | Routes |
|---|---|
| Auth | `POST /auth/register` · `/auth/login` · `/auth/refresh` · `/auth/logout` · `/auth/password/forgot` · `/auth/password/reset` |
| Google | `POST /auth/google/nonce` · `/auth/google` · `/auth/google/complete` · `/google/link` · `/google/unlink` |
| Member | `GET /me` · `GET /dashboard` · `POST /daily-bonus/claim` · `GET/PATCH /settings` · `POST /settings/password` · `POST /devices` · `POST /account/delete` |
| PIN and e-mail | `GET/POST /account/pin` · `POST /account/pin/change` · `GET /account/email` · `POST /account/email/send` · `POST /account/email/verify` |
| Profile photo | `GET/POST /account/avatar` · `POST /account/avatar/remove` · `POST /avatars/lookup` · `GET /avatar/{token}` |
| History | `GET /transactions?filter=&cursor=` · `GET /notifications` · `POST /notifications/{id}/read` · `POST /notifications/read-all` |
| Transfers | `GET /transfers/rules` · `POST /transfers/lookup` · `POST /transfers/quote` · `POST /transfers` · `GET /transfers?direction=` |
| Requests | `GET/POST /requests` · `POST /requests/{id}/pay` · `/decline` · `/cancel` |
| Group requests | `GET/POST /groups` · `GET /groups/{id}` · `POST /groups/{id}/remind` · `/cancel` |
| Contacts | `POST /contacts/sync` · `/contacts/check` · `/contacts/invite` · `/contacts/clear` · `GET /contacts` |
| Merchant | `GET/POST /merchant/me` · `POST /merchant/remove` · `POST /merchant/lookup` |
| Deposits | `GET /deposits/rules` · `POST /deposits/quote` · `POST /deposits` · `GET /deposits` · `GET /deposits/{id}` (crypto) |
| Mobile-money deposits | `/payfonte/*` (including card checkout), `/airtel/*`, `/lumicash/*` |
| Withdrawals | `GET /withdrawals/rules` · `GET/POST /withdrawals` · `POST /withdrawals/{id}/cancel` |
| Agents | `POST /agents/lookup` · `GET /agents/bureaus` · `GET /agents/nearby` · `GET /agent/me` · `POST /agent/apply` · `/agent/availability` · `/agent/location` · `GET /agent/requests` · `POST /agent/requests/{id}/accept\|decline\|confirm` |
| ID verification | `GET /account/id` · `POST /account/id/upload` · `/account/id/back` · `/account/id/details` · `/account/id/submit` |
| Earn | `GET /tasks` · `POST /tasks/{id}/start\|complete` · `GET /videos` · `POST /videos/{id}/reaction` · `GET /campaigns` · `POST /campaigns/{id}/proofs` · `GET /leaderboard` · `GET /referrals` |
| Muda AI | `GET /coach/context` · `GET /coach/conversations[/{id}]` · `POST /coach/messages` (the app adds `voice` and `lang` for voice chat). Contextual assistant: `reply.action` (and `action` on each history message) is `null` or `{type:"send", status:"ready\|pick\|none", amount, name, phone, country, note, label, candidates:[{name,phone,country}]}` (the app pre-fills Send, never pays) or `{type:"open", route, label}`; quick actions and exact fee/spend answers (`source` = `action` / `insights`) skip the AI daily limit |
| Support | `GET/POST /support/tickets`, `GET /support/tickets/{id}`, `POST /support/tickets/{id}/messages`, `POST /support/tickets/{id}/close`, `GET /support/config` (see "Support & disputes") |
| Market | `GET /market/coins` · `/market/ticker` · `/market/candles` · `/market/fx` · `/market/fx/history` · `/market/search` · `/market/status` |
| Trade | `GET /trade/status` · `/trade/assets` · `/trade/quote` · `/trade/portfolio` · `/trade/orders` · `POST /trade/orders` · `/trade/orders/{id}/cancel` · `GET /trade/convert/quote` · `POST /trade/convert` |
| Invest (Alpaca) | `/trading/*` including `/trading/funding/*` |
| App updates | `GET /app/update` · `GET /app/download/{id}` (public) |
| Errors | `POST /client/errors` (public, throttled): crash and unexpected-answer reports with personal data masked |

Provider failures on market routes answer HTTP 424 `market_unavailable`.

## Push messages (FCM HTTP v1, data-only)

Keys: `type`, `route`, `title`, `body`, optional `details` (lines separated by `|`) and `withdrawal_id`. `android.priority = HIGH`.
The app builds the notification itself and de-duplicates by reference + status. Each type respects the member's category switches.

| Type | When |
|---|---|
| `withdrawal_paid` · `withdrawal_update` · `withdrawal_rejected` | admin changes a withdrawal (route `Withdraw`) |
| `payment_deposit` · `payment_deposit_review` · `payment_deposit_expired` · `payment_deposit_failed` | deposit status (route `Deposit`) |
| `payment_transfer_in` · `payment_transfer_out` | money received / sent |
| `payment_request_in` | payment request, group request or reminder (route `Requests`) |
| `credit` · `referral` · `share_rejected` | admin credit, share approved or rejected, leaderboard prize, referral reward |
| `email_code_sent` · `email_verified` | e-mail verification |
| `daily_bonus` · `streak_risk` | daily cron reminders (route `Home`) |

## QR format

`mudapay://pay?v=1&p=<phone>&c=<country code>[&a=<amount>][&n=<note>][&m=<shop code>]`

Older codes with only a phone number (and codes without `c`) are still accepted. The app resolves the number with `POST /transfers/lookup` and shows the server's name; `m` is only a hint, the app trusts `POST /merchant/lookup`.

## Screen to file map

Send / Receive `TransferScreens.kt` · Requests and groups `RequestScreens.kt`, `GroupScreens.kt` · Contacts `ContactsScreen.kt` · Scan `ScanPay.kt`, `DotQr.kt` · Merchant `Merchant.kt` · Agents `AgentScreens.kt`, `AgentLocator.kt` · KYC `KycScreens.kt`, `KycDocScanner.kt`, `KycOcr.kt` · Deposits `DepositScreens.kt`, `PayfonteDeposit.kt`, `AirtelDeposit.kt`, `LumicashDeposit.kt`, `CryptoPay.kt` · Markets `MarketScreens.kt` · Trade `TradeScreens.kt`, `TradeStatement.kt` · Invest `TradingScreens.kt` · PIN `TransactionPin.kt` · Photos `Avatars.kt` · Muda AI `CoachScreen.kt`, `VoiceChat.kt`, `CaptionEngine.kt` · Push `Push.kt`, `NotifySync.kt`.


## Crypto Wallet (non-custodial, BNB Chain; Tron routes exist but the app has Tron switched off)

The server never receives a seed phrase or a private key. The phone creates the keys, derives the addresses, and **signs**; the server only stores the public addresses, reads chain data and relays signed transactions. All routes need the member token. Amounts are plain decimal strings (`"12.5"`); `*_raw` values are integer strings in the smallest unit (wei for BNB/BEP20 with 18 decimals, sun for TRX/TRC20 with 6).

| Route | Purpose |
|---|---|
| `GET /wallet/config` | `{enabled, chains:[{id,name,chain_id,explorer,enabled}], assets:[{id,chain,symbol,name,decimals,contract,can_send}], tron_fee_limit_sun, max_send_usd}` |
| `POST /wallet/address` `{bsc?, tron?}` | Register public addresses. Anything with whitespace or > 80 chars is refused (`invalid_address`). -> `{ok:true}` |
| `GET /wallet/balances` | `{balances:[{asset,raw,amount}], errors:{bsc?,tron?}, updated_at}`. A failing chain is listed in `errors`, its assets are omitted. |
| `POST /wallet/prepare` `{asset,from,to,amount}` | Data to sign (signs nothing). BNB Chain: `{chain:"bsc",chain_id,nonce,gas_price,gas_limit,fee_raw,native_balance_raw,token_balance_raw,fee_ok,message}`. Tron: `{chain:"tron",ref_block_bytes,ref_block_hash,timestamp_ms,expiration_ms,fee_limit_sun,est_energy,est_fee_sun,trx_balance_raw,token_balance_raw,fee_ok,message}`. `fee_ok:false` carries a readable `message`. Call it right before signing (Tron expires 10 min after `timestamp_ms`). |
| `POST /wallet/broadcast` `{chain,asset,signed,from,to,amount}` + `Idempotency-Key` | `signed` = hex of the signed transaction: BNB Chain = the raw signed EIP-155 transaction (RLP, legacy gasPrice); Tron = the full signed `Transaction` protobuf (`raw_data` + signature), relayed with `/wallet/broadcasthex`. -> `{txid, status:"pending"}` |
| `GET /wallet/tx?chain=&txid=` | `{txid, chain, status:"pending"|"confirmed"|"failed", confirmations?}` (own transactions only) |
| `GET /wallet/history?limit=30` | `{items:[{asset,direction:"in"|"out",amount,counterparty,txid,status,time,chain}], incoming_available}` newest first |

Assets: `bnb` and `usdt_bep20` (BNB Chain, 18 decimals; USDT contract `0x55d398326f99059fF775485246999027B3197955`), `usdt_trc20` (Tron, 6 decimals, `TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t`) and `trx` (receive-only: `can_send:false`). BNB Chain uses chain id 56. The app builds BEP20 / TRC20 `transfer(address,uint256)` calls itself (selector `a9059cbb`).

Errors (`{ok:false,error:{code,message}}`): `wallet_disabled` 503 · `rpc_unavailable` 503 · `invalid_address` / `invalid_amount` / `invalid_asset` / `invalid_transaction` / `above_maximum` 422 · `broadcast_failed` 422 (the network refused; message from the network) · `too_many_attempts` 429 · `not_found` 404 · `idempotency_key_required` 400. The `from` address must be the one registered with `POST /wallet/address` for that network.

App side (`WalletCrypto.kt`, `WalletVault.kt`, `WalletScreens.kt`, route `Wallet`, menu "Crypto Wallet"): what each asset is (network, contract, decimals) and the explorer links are PINNED inside the app (`Pinned`); the server may only switch assets and networks on or off. Before signing, the app checks the `prepare` data itself (BNB Chain: chain id 56, gas price <= 30 gwei, gas limit 21,000 to 250,000; Tron: block reference sizes, fee limit 1 to 100 TRX, timestamp within 2 minutes of the phone clock).

## PayPal deposits (UK, USA and other countries where enabled)
Same shape as the card deposits. UK members are charged in GBP, US members in USD, everyone else in USD at the confirmed rate.

| Method | Route | Notes |
|---|---|---|
| GET  | `/paypal/rules` | `enabled`, `paused`, `symbol`, `currency`, `charge_currency`, `rate`, `min`, `max`, `presets`, `quote_valid_minutes`, `open[]`, `balance` |
| POST | `/paypal/quote` `{amount}` | `amount`, `charge`, `charge_currency`, `rate`, `same_currency`, `fee_pct` |
| POST | `/paypal/deposit` `{amount}` + `Idempotency-Key` | `201` with `invoice_url` (PayPal approval page, opened in the browser) |
| GET  | `/paypal/deposits?cursor=` | history (`charge_text`, `status_text`, `credited`, `credited_amount`) |
| GET  | `/paypal/deposits/{id}` | re-read from PayPal while open (the app polls it every 6 s); `balance` included |


## Security, connected devices and KYC limits (app 3.15.0)
| Method | Path | Returns |
|---|---|---|
| GET  | `/account/security` | `email_verified`, `pin_enabled`, `google_linked`, `has_password`, `kyc_level` (0-2), `kyc_level_name`, `id_status`, `devices`, `checks[]`, `score`, `score_max`, `events[]` (last 20 security events) |
| GET  | `/account/devices` | `items[]`: `session_id`, `name`, `platform`, `app_version`, `ip` (masked), `first_seen`, `last_seen`, `current` |
| POST | `/account/devices/revoke` `{session_id}` | signs one device out (not the current one: `current_device` 422); returns the new `items[]` |
| POST | `/account/devices/revoke-others` | signs out every other device; `revoked` = count |
| GET  | `/account/limits` | `level`, `level_name`, `email_verified`, `id_status`, `currency`, `symbol`, `limits[]` (`kind` send/withdraw/deposit, `per_tx`, `day`, `month`, `used_day`, `used_month`, `remaining_*`, `next`), `levels[]` |

Sign-in bodies (`/auth/login`, `/auth/google*`) also carry `device_id` (random id made at install), `platform` and `app_version`.
Money routes (transfer, request pay, withdrawal, every deposit incl. PayPal) can now answer `422` with `kyc_limit_tx`, `kyc_limit_day` or `kyc_limit_month`; `data` holds `kyc_level`, `kyc_upgrade`, `limit_*` and `remaining_*`.
Countries added: `fr`, `bg`, `ad` (EUR) and `cz` (CZK).

## Support & disputes (app 3.16.0 / server `api/v1/lib_support.php`)
Tickets are **conversations** with the MudaPay team and can be **linked to one transaction**. Muda AI is the fast answer while the team is away.

| Route | Notes |
|---|---|
| `GET /support/config` | `{staff_online, hours{start,end,days,timezone}, reply_time, ai_available, reasons[]}`. Hours are edited in admin > Support (`support_start/end/days/tz/force_away/reply_time` in `admin_settings`). |
| `GET /support/tickets` | `{items:[{id, category, subject, message, status, created_at, updated_at, ref_kind, ref_id, ref_summary, dispute_reason, unread, last_message{sender,body,created_at}}], unread}`. Unread / newest first. |
| `POST /support/tickets` | `{category, subject, message, ref_kind?, ref_id?, dispute_reason?}`. `ref_kind` = `tx` (transactions.id) \| `withdrawal` \| `transfer` (id or reference) \| `wallet` (txid). The item must belong to the member (`404 transaction_not_found` otherwise). `dispute_reason` (not_received, wrong_amount, wrong_recipient, duplicate, unauthorized, pending_too_long, other) makes it a Dispute and needs a linked item; subject is generated if empty. One open ticket per item: `409 already_open` + `ticket_id`. Answer: `{id, status, staff_online, reply_time}`. Opening a dispute NEVER changes a balance. |
| `GET /support/tickets/{id}` | `{ticket, messages[{id,sender:member|staff,body,created_at}], staff_online, reply_time, can_reply}`. The ticket text is message 0. Opening it marks the team's answers as read. |
| `POST /support/tickets/{id}/messages` | `{message}` (max 3000). Wakes a resolved ticket up again (up to 14 days after resolution, then `409 ticket_closed`). |
| `POST /support/tickets/{id}/close` | the member marks it resolved. |
| `POST /coach/messages` | new optional field `ticket_id`: Muda AI also receives a short context about that ticket (never saved, never shown). |

The team's answer creates an in-app notification and an FCM push of type `support_reply` (route `Support`, extra `ticket_id`). Statuses: `Pending` (waiting for the team) → `In Progress` → `Resolved`.
App: History > transaction > "Report a problem", Withdrawal detail, wallet transaction status; Support center shows the team status + "Chat with Muda AI" (opens Muda AI with the ticket attached).

## Send to a non-member (invitation transfer)
`POST /transfers/lookup` answers `found:false` + `invite:{allowed, ttl_days, bonus, bonus_symbol, bonus_min_send}` for a number nobody uses → the Send screen shows `InviteSendSection`
(InviteScreens.kt): `POST /transfers/quote` then `POST /transfers/invite {phone, amount, country, note, expected_receive, pin}` + Idempotency-Key → `{code, link, receive_amount, bonus_offer, ttl_days, balance}`;
`InviteSentCard` shares it (WhatsApp / SMS / chooser). `GET /transfers/invites` + `POST /transfers/invites/{id}/cancel` = "My invitations" under *Sent recently*.
Newcomers type the code in the register field (`invite` in `POST /auth/register` / `/auth/google/complete`; plain numbers still go to `referrer`); existing members use *Receive > Claim an invitation* (`POST /transfers/claim {code}`).
Details, settings and security notes: server `INVITE_TRANSFERS.md`.


## Promo codes, vouchers, merchant cashback
- `POST /promo/redeem {code}` → `{kind,label,code,amount,symbol,held,min_deposit,days,balance}`; `GET /promo/history` → `{items:[{code,kind,label,amount,symbol,status,deadline,created_at}]}`.
- `GET/POST /merchant/me`: `cashback_pct` (send to set; server clamps to admin max); `profile.cashback_pct` = effective %; `cashback{enabled,max_pct}`; `/merchant/lookup` merchant has `cashback_pct`.
- Transfer response and history items carry `cashback` (payer's currency); transfer response also `cashback_pct`.
