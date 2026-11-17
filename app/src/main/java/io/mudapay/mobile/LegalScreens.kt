package io.mudapay.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Terms of Service and Privacy Policy, built into the app (no website needed, they work offline).
 * Reached from About; the Terms link to the location section of the Privacy Policy.
 * When the text changes, change it here and ship an app update.
 */

private class LegalSection(val number: String? = null, val title: String, val badge: String? = null, val body: String? = null, val bullets: List<String> = emptyList(), val id: String? = null, val link: Pair<String, String>? = null)

private val TERMS_UPDATED = "Last modified: September 2026"
private val PRIVACY_UPDATED = "Last updated: November 2026"

private val TERMS = listOf(
    LegalSection("01", "Acceptance of Terms", body = "By accessing MudaPay (the \"Platform\"), you agree to be bound by these Terms of Service. MudaPay is a company of Userlab Ltd, which operates this platform. If you do not agree to these terms, you must cease all use of the platform and delete your account immediately."),
    LegalSection("02", "Creator Eligibility", bullets = listOf(
        "You must be at least 18 years of age.",
        "You must reside in one of our supported regions (Burundi, Kenya, TZ, UG, RW, NG, etc.).",
        "Users are permitted only one account per person. Multiple accounts will lead to a definitive ban.")),
    LegalSection("03", "Content Guidelines", body = "To keep MudaPay clean and profitable, you agree NOT to upload:", bullets = listOf(
        "Sexually explicit or pornographic content.",
        "Content that promotes violence or ethnic hatred.",
        "Copyrighted material that you do not own the rights to.",
        "Spam or deceptive clickbait designed to farm views.")),
    LegalSection("04", "Payment & Payouts", body = "Earnings are calculated based on valid views and engagement. Instant Payouts are subject to network availability and regional verification. MudaPay reserves the right to withhold payments if fraudulent activity (bot views, fake likes) is detected."),
    LegalSection("05", "Account Termination", body = "Serious or repeated violations of these terms may lead to suspension or termination of your account. If we close your account, we will tell you why and, unless the law or an investigation prevents it, you can withdraw the balance that belongs to you. We respect your privacy, but we do not protect illegal activity and we may share information with authorities when the law requires it."),
    LegalSection("06", "Location Sharing (Optional)", id = "location",
        body = "Le partage de position sur la carte MudaPay est facultatif et désactivé par défaut. En appuyant sur « Activer le suivi de la localisation » et en autorisant l’accès à la localisation, vous consentez à partager votre position exacte, votre prénom, votre photo de profil et le drapeau de votre pays avec les autres membres qui ont aussi activé le partage, comme décrit dans notre politique de confidentialité.",
        bullets = listOf(
            "Vous pouvez désactiver le partage à tout moment. Votre position enregistrée est alors supprimée.",
            "Vous décidez du moment où il est sûr de partager votre position. Ne l’activez pas si le fait d’être localisé peut vous mettre en danger.",
            "Vous ne devez pas utiliser la position d’un autre membre pour traquer, harceler, menacer, suivre ou localiser quelqu’un contre sa volonté, ni pour commettre une fraude ou tout autre délit. Vous ne devez pas copier, capturer, extraire ou partager les positions des autres membres en dehors de MudaPay.",
            "Toute violation entraîne la résiliation immédiate de votre compte, et nous pouvons signaler tout usage illégal aux autorités.",
            "Les positions proviennent de votre appareil et peuvent être inexactes ou périmées. Le partage de position n’est pas un service d’urgence ni de sécurité.",
            "Nous pouvons modifier, limiter ou suspendre le partage de position à tout moment."),
        link = "Read how we handle location data" to "Privacy"),
    LegalSection("07", "Crypto Wallet (self-custody)", body = "The Crypto Wallet in MudaPay is a non-custodial wallet. Your recovery phrase and private keys are created and stored only on your phone. MudaPay does not have them, cannot see them, and cannot recover, freeze, reverse or move your funds.", bullets = listOf(
        "You alone are responsible for your 12-word recovery phrase. If you lose it and lose or reset your phone, your funds are lost permanently.",
        "Blockchain transactions cannot be undone. Check the address, the coin and the network before you confirm. Funds sent to a wrong address or network are lost.",
        "Network fees are paid to the blockchain (in BNB on BNB Chain), not to MudaPay, and can change at any time.",
        "If you connect an external wallet (WalletConnect), MudaPay only sees its public address while it is connected, never its keys or recovery phrase. Every payment is approved inside that wallet.",
        "The Crypto Wallet is separate from your MudaPay balance. MudaPay does not trade, lend or hold the assets in your wallet.",
        "Crypto assets can lose value. Crypto rules differ from country to country: you are responsible for using the wallet in line with the laws that apply to you.",
        "We may pause the wallet screens or the blockchain connection at any time. This never gives us access to your funds: your phrase still restores them in any compatible wallet app.")),
    LegalSection(null, "Limitation of Liability", body = "MudaPay and Userlab Ltd are not responsible for any direct or indirect losses incurred while using the platform. Use of the service is at your own risk.")
)

private val PRIVACY = listOf(
    LegalSection(null, "What we collect", badge = "SECURE",
        body = "MudaPay collects only what is needed to run your account, move money and keep it safe. We do not track what you do in other apps or websites.",
        bullets = listOf(
            "Profile: your name, phone number, country, language, and your e-mail address and profile photo if you add them.",
            "Account security: your password (stored in a scrambled form we cannot read back), your login sessions and your security settings.",
            "Transactions: payments, requests, deposits, withdrawals, savings and wallet history, including the notes you write.",
            "Identity verification: the ID photos and selfie you upload. They are stored encrypted and reviewed by MudaPay.",
            "Your device: push notification token, app version, Android version and phone model.",
            "Only if you turn it on: your exact location (see the section below).")),
    LegalSection(null, "MudaPay balance", badge = "HELD BY MUDAPAY",
        body = "The MudaPay balance is the money you top up by mobile money, card or crypto deposit. MudaPay holds it for you and processes it, so MudaPay can see and move it according to your instructions. This is different from the self-custody Crypto Wallet below.",
        bullets = listOf(
            "Payment partners receive the details needed to process a deposit or payout that you start.",
            "Money left in your balance when you delete your account is lost, so withdraw it first.")),
    LegalSection(null, "Crypto Wallet", badge = "SELF-CUSTODY",
        body = "Your recovery phrase and private keys never leave your phone and are never sent to MudaPay. To show balances and send your transactions, our server receives:",
        bullets = listOf(
            "Your public wallet address (BNB Chain), which is visible on the blockchain anyway.",
            "Transactions that you signed on your phone, so that we can relay them to the blockchain, and a record of them (asset, amount, addresses, transaction ID, status).",
            "Requests to a blockchain data provider are made by our server, so your phone's network address is not shared with that provider.")),
    LegalSection(null, "Live Location Sharing (Optional)", badge = "OPT-IN", id = "location",
        body = "Location sharing is off by default. It starts only when you tap “Enable location tracking” on the MudaPay map and allow location access in your browser or device.",
        bullets = listOf(
            "What we collect: your device’s latitude, longitude and accuracy, and the time of each update, for as long as sharing is on.",
            "Who can see it: only members who have also turned sharing on. They see your exact position, first name, profile photo and country flag. Members who have not turned it on cannot see you, and you cannot see them.",
            "How long we keep it: we store only your last position, never a history of movements. It is hidden after 15 minutes without an update and deleted automatically after 24 hours without an update.",
            "Turning it off: your saved position is deleted immediately and you no longer see other members.",
            "Proof of consent: we record when you turn sharing on or off and the version of this policy that applied. You can withdraw your consent at any time by turning sharing off.",
            "No sale, no advertising: we never sell your location, use it for advertising or share it with third parties. The map loads tiles and code from third-party servers (OpenStreetMap, CARTO, cdnjs), so those services can see your IP address and the map area you view.",
            "Your safety: anyone who sees your position can tell where you are. Share it only if you are comfortable with that, and turn it off if being located could put you at risk.",
            "Account deletion: deleting your account also deletes your location data and your consent log.")),
    LegalSection(null, "Muda AI conversations",
        body = "Your chats with Muda AI are saved in your account so you can reopen any old chat and continue it. To write answers, the text of your messages, and a short snapshot of the account facts needed to answer (such as your country and balance), is sent to an AI provider: Anthropic, or OpenRouter, which passes the request to a model provider such as OpenAI. Voice chat uses Android’s built-in speech recognition (on most phones provided by Google, which may process your voice under its own policy); MudaPay receives only the text. Never type passwords, PINs, OTP codes, card numbers or ID numbers in the chat. MudaPay staff may review conversations to improve answers and prevent abuse. You can rename, pin and delete any chat, or delete all chats, from the history panel in Muda AI. Deleting your account also deletes your chats."),
    LegalSection(null, "Reporting and blocking",
        body = "Payment requests and transfers carry a note written by another member, so you can protect yourself:",
        bullets = listOf(
            "Block a member from a payment request. A blocked member can no longer send you requests or money and is not told. The list of members you blocked is kept until you unblock them (Settings > Account > Blocked members) or delete your account.",
            "Report a member. We keep your report, its reason and the note you reported so the MudaPay team can review it and act. Your report is erased if you delete your account; a report about another member is kept (without his identity once his account is deleted) until it is reviewed.",
            "Report a Muda AI answer. Every answer has a “Report this answer” button (chat, voice chat and the assistant). We keep your report, its reason, your question and the answer so the team can review them and make the AI safer.")),
    LegalSection(null, "Who we share data with",
        body = "We do not sell your data and we do not show advertising. We share data only as needed to run the service:",
        bullets = listOf(
            "Payment partners: Payfonte, Lumicash (BitLibera), Airtel Money, PayPal and NOWPayments receive the details needed to process a deposit or payout that you start.",
            "Google: Firebase Cloud Messaging delivers push notifications, and Google receives data when you use Google sign-in.",
            "AI providers: Anthropic and OpenRouter (which may pass requests to a model provider such as OpenAI) receive your Muda AI messages, as described above.",
            "Reown (WalletConnect): receives technical connection data only if you connect an external wallet.",
            "E-mail service: sends verification and password-reset codes.",
            "Other members and agents: the person you pay or who pays you sees your public name and what the transaction shows. An agent sees what is needed to complete a cash-out you request.",
            "Authorities: when the law requires it, or to stop fraud and illegal use.")),
    LegalSection(null, "Crash and error reports", id = "error-reports",
        body = "When the app or the website hits a bug, MudaPay records a technical report so the team can fix it quickly and sends it to the MudaPay developer by e-mail.",
        bullets = listOf(
            "What is in a report: the error message and technical trace, the app version, Android version, phone model, the last screen request (path only) and your member number if you are signed in.",
            "What is never in a report: passwords, PINs, session tokens, e-mail addresses, phone numbers, messages or the content of your requests. These are masked before anything is saved.",
            "Purpose: fixing bugs only. Reports are not used for advertising and are not shared with third parties.")),
    LegalSection(null, "Right to be Forgotten",
        body = "When you choose to Delete my account in Settings, you confirm with your password and a code we e-mail to your verified address. We then erase your account: your profile, ID photos and selfie, chats, devices and history are deleted from our servers, and we keep no copy of your name, phone number or password. Payment records that other people or our accounts still need (amounts and dates) are kept, with your name, phone number and notes removed. Money left in your balance is lost, so withdraw it first. Backups are overwritten within a short time."),
    LegalSection(null, "Children",
        body = "MudaPay is for people aged 18 and over. We do not knowingly collect data from children. If you think a child has an account, contact support and we will delete it."),
    LegalSection(null, "Security",
        body = "MudaPay protects the connection between your phone and our servers with industry-standard TLS encryption. ID photos are stored encrypted. No system is perfectly secure, so we also support a password, an optional PIN and biometric lock, and e-mail codes for sensitive actions."),
    LegalSection(null, "Questions about privacy",
        body = "Write to us from Support in the app. The same policy, with our contact e-mail, is on our website at the Privacy page.")
)

@Composable
private fun LegalPage(title: String, updated: String, icon: androidx.compose.ui.graphics.vector.ImageVector, sections: List<LegalSection>, nav: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageIntro(title, updated, icon)
        sections.forEach { s ->
            Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.number?.let { Text(it, color = GreenDark, fontWeight = FontWeight.Black, fontSize = 12.sp) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(L(s.title), color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f, fill = false))
                    s.badge?.let { Spacer(Modifier.width(8.dp)); Text(it, color = GreenDark, fontWeight = FontWeight.Black, fontSize = 10.sp) }
                }
                s.body?.let { Text(L(it), color = Muted, fontSize = 13.sp, lineHeight = 20.sp) }
                s.bullets.forEach { b ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("•", color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(L(b), color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
                s.link?.let { (label, route) -> TextButton(onClick = { nav(route) }, contentPadding = PaddingValues(0.dp)) { Text(L(label), color = GreenDark, fontWeight = FontWeight.Bold, fontSize = 13.sp) } }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun TermsScreen(nav: (String) -> Unit) = LegalPage("Terms of Service", TERMS_UPDATED, MudaIcons.Description, TERMS, nav)

@Composable
fun PrivacyScreen(nav: (String) -> Unit) = LegalPage("Privacy Policy", PRIVACY_UPDATED, MudaIcons.PrivacyTip, PRIVACY, nav)
