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
private val PRIVACY_UPDATED = "Last updated: September 2026"

private val TERMS = listOf(
    LegalSection("01", "Acceptance of Terms", body = "By accessing MudaPay (the \"Platform\"), you agree to be bound by these Terms of Service. This platform is managed by Mayuto Group. If you do not agree to these terms, you must cease all use of the platform and delete your account immediately."),
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
    LegalSection("05", "Account Termination", body = "Violation of any terms listed above will result in the immediate termination of your account and forfeiture of any remaining balance. MudaPay is a privacy-protected platform, but we do not protect illegal activities."),
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
    LegalSection(null, "Limitation of Liability", body = "MudaPay and Mayuto Group are not responsible for any direct or indirect losses incurred while using the platform. Use of the service is at your own risk.")
)

private val PRIVACY = listOf(
    LegalSection(null, "Data Minimalism", badge = "SECURE",
        body = "Unlike TikTok or Facebook, we do not track your off-platform activity. MudaPay only collects the essential data required to process your earnings and secure your account.",
        bullets = listOf(
            "Basic Profile Info (Name, Phone)",
            "Regional Location (For Currency Accuracy)",
            "Position exacte (uniquement si vous activez le partage de position)",
            "Wallet Transaction History")),
    LegalSection(null, "Live Location Sharing (Optional)", badge = "OPT-IN", id = "location",
        body = "Le partage de position est désactivé par défaut. Il ne démarre que lorsque vous appuyez sur « Activer le suivi de la localisation » sur la carte MudaPay et autorisez l’accès à la localisation dans votre navigateur ou appareil.",
        bullets = listOf(
            "Ce que nous collectons : la latitude, la longitude et la précision de votre appareil, ainsi que l’heure de chaque mise à jour, tant que le partage est activé.",
            "Qui peut la voir : uniquement les membres qui ont eux aussi activé le partage. Ils voient votre position exacte, votre prénom, votre photo de profil et le drapeau de votre pays. Les membres qui ne l’ont pas activé ne peuvent pas vous voir, et vous ne pouvez pas les voir.",
            "Durée de conservation : nous ne stockons que votre dernière position, jamais un historique de déplacements. Elle est masquée après 15 minutes sans mise à jour et supprimée automatiquement après 24 heures sans mise à jour.",
            "Désactivation : votre position enregistrée est supprimée immédiatement et vous ne voyez plus les autres membres.",
            "Preuve du consentement : nous enregistrons le moment où vous activez ou désactivez le partage et la version de cette politique appliquée. Vous pouvez retirer votre consentement à tout moment en désactivant le partage.",
            "Aucune vente, aucune publicité : nous ne vendons jamais votre position, ne l’utilisons pas pour de la publicité et ne la partageons pas avec des tiers. La carte charge des tuiles et du code depuis des serveurs tiers (OpenStreetMap, CARTO, cdnjs) ; ces services peuvent donc voir votre adresse IP et la zone de carte que vous consultez.",
            "Votre sécurité : toute personne qui voit votre position peut savoir où vous êtes. Ne la partagez que si cela vous convient, et désactivez-la si le fait d’être localisé peut vous mettre en danger.",
            "Suppression du compte : supprimer votre compte supprime aussi vos données de localisation et votre journal de consentement."),
        link = null),
    LegalSection(null, "Phone contacts (Optional)", badge = "OPT-IN", id = "contacts",
        body = "The app never reads your phone book on its own. It happens only when you tap “Load phone contacts” in the Contacts screen, allow the Android contacts permission and confirm.",
        bullets = listOf(
            "What we collect: the name and phone number of each contact (up to 3,000), saved on our server and linked to your account only.",
            "What we use it for: showing you which of your contacts already use MudaPay so you can send them money, and letting you invite the others. Nothing else.",
            "Who can see it: only you. Your contacts are never shown to other members, sold, used for advertising or shared with third parties. A contact who uses MudaPay is shown to you with their public name only.",
            "Removal: “Remove my contacts from MudaPay” in the Contacts screen deletes everything at once, and deleting your account deletes it too.")),
    LegalSection(null, "The Silent-Wallet System",
        body = "Your financial data is encrypted using our Silent-Wallet protocol. We never share your withdrawal history or earning patterns with third-party advertisers. Your money is your business."),
    LegalSection(null, "Crash and error reports", id = "error-reports",
        body = "When the app or the website hits a bug, MudaPay records a technical report so the team can fix it quickly and sends it to the MudaPay developer by e-mail.",
        bullets = listOf(
            "What is in a report: the error message and technical trace, the app version, Android version, phone model, the last screen request (path only) and your member number if you are signed in.",
            "What is never in a report: passwords, PINs, session tokens, e-mail addresses, phone numbers, messages or the content of your requests. These are masked before anything is saved.",
            "Purpose: fixing bugs only. Reports are not used for advertising and are not shared with third parties.")),
    LegalSection(null, "No Third-Party Tracking",
        body = "We do not use tracking pixels or sell your behavioral data to data brokers. We generate revenue through platform transactions, not by selling your privacy."),
    LegalSection(null, "Muda AI conversations",
        body = "Your chats with Muda AI are saved in your account so you can reopen any old chat and continue it. To write answers, the text of your messages is sent to an AI provider. Never type passwords, PINs, OTP codes, card numbers or ID numbers in the chat. MudaPay staff may review conversations to improve answers and prevent abuse. You can rename, pin and delete any chat, or delete all chats, from the history panel in Muda AI. Deleting your account also deletes your chats."),
    LegalSection(null, "Right to be Forgotten",
        body = "When you choose to Delete Your Account in settings, we don't just \"deactivate\" it. Our system performs a definitive wipe, removing your videos, credentials, and wallet history from our active servers instantly."),
    LegalSection(null, "Encryption Standard",
        body = "MudaPay uses Industry-Standard SSL encryption to ensure that the bridge between your mobile device and our servers is impenetrable.")
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
