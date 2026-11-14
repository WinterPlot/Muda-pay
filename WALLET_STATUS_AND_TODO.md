# MudaPay Crypto Wallet: status and what is still missing (version 3)

NOT ready for real money. Nothing has been compiled or run against a real blockchain yet.

## 1. Scope now
- Built-in non-custodial wallet, **BNB Chain only**: BNB and USDT (BEP20). Keys exist only on the phone.
- **Tron is switched OFF** (replaced by USDT BEP20). Its code is dormant; to switch it on again: re-add the 3 entries in `Pinned` (`WalletCrypto.kt`) and set `wallet_tron_enabled` to 1 on the admin Wallet page.
- **Connect external wallet** (WalletConnect / Reown AppKit): MetaMask, Trust Wallet, Rainbow ... approve MudaPay payments in their own app. BNB Chain only (AppKit for Android supports EVM chains only).
- Reown Project ID is in `ExternalWallet.PROJECT_ID` (public id, not a secret). You said you will regenerate it after testing: change that one constant.

## 2. Files
App (`MudaPay_app_wallet_v3.zip`): new `WalletCrypto.kt`, `WalletVault.kt`, `WalletScreens.kt`, `ExternalWallet.kt`; edited `MainActivity.kt` (routes Wallet, ExternalWallet), `HomeScreens.kt`, `Language.kt`, `LegalScreens.kt`, `app/build.gradle.kts` (web3j + Reown + Material2 + Accompanist), `settings.gradle.kts` (JitPack), `AndroidManifest.xml` (mudapay-wc://request link + wallet queries), `proguard-rules.pro`, `README.md`, `BACKEND_API_CONTRACT.md`.
Server (`MudaPay_SERVER_wallet_v3.zip`): new `wallet_lib.php`, `api/v1/lib_wallet.php`, `admin_wallet.php`, `db/rpc_config.php(.example)`, `WALLET_SETUP.md`; edited `api/v1/index.php`, `admin.php`, `db.sql`, `README_SERVER.md`, `README_API.md`, `db/knowledge/mudapay_knowledge.md`; plus `wallet_tests/`. Tron is off by default on the server too.

## 3. What was checked
PHP syntax OK, 50 PHP tests pass, Python copy of the key/signing logic passes known vectors (BNB Chain address of the Hardhat test phrase, amount conversion; the Tron checks cover dormant code). A reviewer read the three wallet Kotlin files (no blockers); its 5 fixes are applied. Bracket counts balance in all changed Kotlin files. **None of this is a compile.**

## 4. Still missing / must be done by you (in this order)
1. **Run the GitHub Actions build.** Expect compile errors; send them to me. The most likely trouble spots:
   - `ExternalWallet.kt`: written from the Reown docs WITHOUT a compiler. Unconfirmed: the BOM version `com.reown:android-bom:1.6.17`, the imports (`com.reown.android.Core`, `CoreClient`, `relay.ConnectionType`, `appkit.client.AppKit`, `Modal`, `appkit.presets.AppKitChainsPresets`, `appkit.ui.appKitGraph`, `openAppKit`), `Modal.Params.Request(method, params)`, `AppKit.disconnect(onSuccess, onError)`, and the Accompanist version `0.34.0`. If it cannot be fixed quickly: delete `ExternalWallet.kt`, the `ExternalWallet` route in `MainActivity.kt`, the "Connect external wallet" row and `onExternal` in `WalletScreens.kt`, and the Reown lines in `app/build.gradle.kts`. The rest builds without it.
   - `WalletCrypto.kt`: the web3j version `org.web3j:crypto:4.8.7-android` is unconfirmed (change the version if Maven says it is missing).
2. **Release (R8) build test**: create/restore a wallet on the release APK (the BIP-39 word list is a resource inside web3j).
3. **Deploy the server files**, add your own BNB Chain RPC key in `db/rpc_config.php` (see `WALLET_SETUP.md`), open admin > Wallet > "Test connection", turn the wallet and BNB Chain on. Optional: `MP_WALLET_BSC_EXPLORER_API` for incoming history.
4. **Real-money test with very small amounts (mainnet only)**:
   - Create a wallet, write the 12 words, pass the backup check; confirm screenshots are blocked on the phrase screens.
   - Restore the same words (second phone or after removing the wallet) and in Trust Wallet/MetaMask: the same BNB Chain address must appear.
   - Receive about 1 USDT (BEP20) and a little BNB for fees; send 0.5 USDT and a small BNB amount to another wallet you own; check BscScan.
   - Failure cases: wrong address, no BNB for the fee, airplane mode during send, cancelling the fingerprint prompt.
   - External wallet: connect MetaMask or Trust Wallet, make sure the network shows "BNB Chain", send a tiny amount, approve it in the wallet app, confirm the app returns to MudaPay. Check what happens when you reject the request. Test with the wallet app installed and not installed.
   - Test on one Android 6 to 10 phone and one Android 11+ phone.
5. **Reown dashboard**: add the Android package name `io.mudapay.mobile` under App ID (and your domain under Domain) to protect the project, as the dashboard suggests. When you regenerate the Project ID, change `ExternalWallet.PROJECT_ID`.

## 5. Not built / known limits
- No Tron (switched off), no sending TRX.
- External wallet: BNB Chain only. After "Send from this wallet" the member must open the wallet app and approve; MudaPay does not show the transaction result or hash for external-wallet payments. The connected address and network are read from the SDK's text form (fail-safe: if unclear, sending is blocked); replace by typed fields once the first compile confirms them.
- The wallet list (AppKit) shows wallets from Reown's catalog; the chains offered are Reown's default EVM presets and the app blocks sending unless the network is BNB Chain.
- No testnet mode, no USD values, no reminder to back up, no ownership proof for the registered address.
- On Android 6 to 10 the built-in wallet key is not locked to the screen lock by hardware; only the app's fingerprint/PIN prompt guards it (Android 11+ is hardware-enforced).

## 6. Risks
- Lost phrase = lost money; MudaPay cannot recover it. Check the Terms wording and crypto/Play Store rules for your countries with a lawyer.
- The zips contain your existing credentials; keep them private. `db/rpc_config.php` has placeholders only.


## v4 additions (design upgrade) — NOT COMPILED, NOT TESTED
App: backup reminder banner (until the 12 words are confirmed; "I will check my backup later" on create, confirm on Backup page),
USD total + per-asset USD, hide-balance toggle, transaction status screen (Pending/Confirmed/Failed + hash + View on BscScan,
polls GET /wallet/tx), send confirmation bottom sheet (bold address, first/last 6 highlighted, fee in BNB + USD, total),
"Add BNB for fees" warning, address book (paste / scan QR / save / pick, stored on the phone per account), bigger receive QR with
COPY / SHARE + BNB-Chain-only warning, first-time intro (how it works + connect external wallet), styled "Wallet paused", security tips.
New file: WalletExtras.kt. ScanPay.kt: CameraScanner and ScanOverlay are now public (reused to scan an address).
External wallet: the tx hash is captured from the SDK (ExternalWallet.resultDelegate, UNCONFIRMED against the SDK; delete the block
and the AppKit.setDelegate line if it does not compile) or pasted by the member, then POST /wallet/track and the status screen opens.
Server: GET /wallet/balances now also returns "prices" {BNB, USDT} (display only, from the Market cache); new POST /wallet/track
{asset, txid, from, to, amount}. Both unlinted here (no PHP available): run php -l on api/v1/lib_wallet.php and index.php.
