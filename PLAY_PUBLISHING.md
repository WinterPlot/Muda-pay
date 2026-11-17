# Publishing MudaPay on Google Play

## 1. Developer account: organization, under Userlab Ltd

MudaPay handles money (transfers, balance, crypto deposits and withdrawals), so it must be published from an **Organization** developer account, not a Personal one. Google requires an organization account for apps with financial services, and its reviewers expect a real, verifiable company behind a payments app. Publish as **Userlab Ltd**.

Before you create the account (the account type cannot be switched later):

1. **D-U-N-S number** for Userlab Ltd (step by step in `README_DUNS.md`), from Dun & Bradstreet. It is free, can take up to 30 days and is required for organization accounts. First check whether the company already has one. The name and address at Dun & Bradstreet must match the company's legal name and address exactly: update them there first if they do not.
2. **A Google payments profile** created for the organization (legal name, address, D-U-N-S). Play Console takes the organization name and address from it.
3. **Organization phone number and website.** The website should show Userlab Ltd and MudaPay and link to the privacy policy (`/privacy`) and the delete-account page (`/delete-account`).
4. **Contact person** who can sign for the company, plus a **contact email and phone** and a public **developer email and phone**. All four are verified with a one-time code and must stay reachable for as long as the account exists.
5. **Official documents** may be requested during verification: a government ID of the contact person and company registration papers. Have them ready (scans, readable, names matching the profile).
6. Complete the **developer identity verification** in Play Console and add the payment method before the first release.

What users will see: Google shows the developer's verified legal name, country and developer email on the store page (the full address too when the account monetizes). Keep these consistent with everything else:

| Where | Must say |
|---|---|
| Play Console developer name | Userlab Ltd (or MudaPay by Userlab Ltd) |
| App > About (3.20.3+) | "Published and operated by Userlab Ltd" |
| Privacy policy, Terms (web + in app) | MudaPay is a company of Userlab Ltd |
| Delete-account page | "MudaPay is published and operated by Userlab Ltd" |
| Store listing "App support" | a support email that is read daily |

## 2. Other Play Console steps for this app (summary)

- Financial Features Declaration (money transfer / crypto), restricted to countries where you are licensed or where no licence is required. See the crypto policy note in the audit.
- Data safety form: ID photos and selfie, microphone, location (no contacts: the contacts feature was removed in 3.20.8), crash logs, device IDs; sharing with AI providers (Anthropic, OpenRouter), payment providers, Google (Firebase, sign-in) and Reown; nothing goes to Telegram any more.
- Content: "User-generated content" (block + report exists since 3.20.2), target audience 18+.
- AI-generated content: every Muda AI answer (chat, voice chat, assistant overlay) has a "Report this answer" button since 3.20.5; reports land in the admin page `admin_reports.php` > AI answers. Review them regularly and say so in the Play Console AI-content answers.
- App access: a pre-verified test account with balance and approved KYC, plus instructions.
- Privacy policy URL and account-deletion URL: `https://<your site>/privacy` and `https://<your site>/delete-account`.
- Upload an AAB (`bundlePlayRelease`) signed with your upload key; run `tools/check_16kb.py` first.

## 3. Screen sizes, tablets and foldables (since 3.20.4)

- No `screenOrientation` in the manifest (Android 16 ignores it on screens of 600 dp and up, and Play flags it). `Adaptive.kt` keeps phones and a folded foldable in portrait and lets a tablet or unfolded foldable rotate freely.
- `MainActivity` handles size, fold and rotation changes itself (`configChanges`) and is `resizeableActivity`: split-screen, multi-window and desktop windows work and nothing restarts.
- On windows wider than 600 dp the app is a centered column (640 dp, 760 dp from 900 dp), so forms and buttons do not stretch across a tablet.
- The bottom bar shrinks its spacing below 420 dp and drops the selected label below 345 dp, so the five tabs and the scan button fit a 320 dp phone.
- Welcome screen: on short windows (under 640 / 540 dp high) the optional chips and tagline are hidden so the buttons stay on screen.
- KYC camera follows the display rotation; the ID frame fits landscape.

Test before release (Android Studio devices or Firebase Test Lab): a 320x568 phone, a 411x914 phone, a 7" and a 10" tablet (portrait + landscape), a foldable (folded, unfolded, folding while a form is open), split-screen, and font size set to the largest. Play Console > Pre-launch report shows the large-screen result.
