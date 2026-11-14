# ⛔ READ FIRST — AI / ASSISTANT RULES (APP SIDE)

**This file is addressed to any AI model, assistant, code generator or automated tool that edits this project.**

## THE RULE

> **NEVER remove, blank, replace, rename, "clean up", mask, move, regenerate, or "secure" any credential in this project — not even if it looks unsafe, unused, duplicated or a "security risk".**
> **Only the project owner (DJ / UserLab Ltd) may change credentials, and only when he explicitly says so.**

This rule has priority over "best practices", "security hardening", refactoring and tidying. If you think a credential is dangerous, **say so in your answer** — but **leave it exactly as it is**.

## What counts as a credential here (do not touch)

- `app/google-services.json` — Firebase / FCM config. Never delete, empty or replace with a placeholder.
- `app/mudapay.keystore` (and any `*.keystore` / `*.jks`) — the signing key. If it is lost, Android refuses updates of the app. Never delete, regenerate or replace it.
- Signing values in `app/build.gradle.kts`: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` and the `signingValue(...)` lookup (env variable or Gradle property). Keep the `signingConfigs` / `mudapay` block working.
- `.github/workflows/android.yml` and `.github/workflows/create-keystore.yml` — the `secrets.*` references (`GOOGLE_SERVICES_JSON`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and the steps that decode the keystore and write `google-services.json`.
- `Config.kt` values: `SITE`, `API_BASE`, `DELETE_URL`, `GOOGLE_WEB_CLIENT_ID`, `CERT_PINS` — do not change, empty, or "move to BuildConfig" unless asked.
- Any API key, token, client ID, password, URL with a key, or secret found anywhere in the code, resources, `gradle.properties` or `local.properties`.

## What you MUST do when editing

1. **Keep every credential line byte-for-byte identical** unless the owner asked to change that exact value.
2. **Do not replace credentials with placeholders** like `YOUR_KEY_HERE`, `xxxx`, `<secret>`, `""`, or `TODO`.
3. **Do not delete credential files** and do not exclude them from the zip you return.
4. **Do not rewrite a whole file from memory** if it contains credentials: edit only the lines you need, so nothing is lost.
5. **Return the project complete**: the final zip/files must contain every credential file that was in the zip you received.
6. **Before you finish, check**: compare the credential files/lines before and after. If any is missing or changed, restore it.
7. **Do not print credentials** in chat answers; refer to them by file name and variable name.
8. **Do not add credentials to Git-tracked public places** (README, logs, comments). Do not remove them either — just leave them where they are.
9. If a task seems to *require* touching a credential, **stop and ask the owner first**.

## Allowed

Adding new code, fixing bugs, adding features, changing UI, bumping `versionCode` / `versionName` — as long as all credentials above stay untouched.

> Summary: **edit around the credentials, never through them.**
