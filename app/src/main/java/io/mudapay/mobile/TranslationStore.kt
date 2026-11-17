package io.mudapay.mobile

/** Looks up the UI translation for the languages that use per-language tables. */
internal object TranslationStore {
    fun lookup(language: String, value: String): String? = when (language) {
        "cs" -> TrCs.map[value]
        "ha" -> TrHa.map[value]
        "pt" -> TrPt.map[value]
        "sw" -> TrSw.map[value]
        "rw" -> TrRw.map[value]
        else -> null
    }
}

/** French for strings that were used in the app but had no French text yet. */
internal val frenchExtra: Map<String, String> = mapOf(
    "A 4–6 digit PIN is required before every money transfer and withdrawal." to "Un code PIN de 4 à 6 chiffres est requis avant chaque transfert d’argent et retrait.",
    "Account updates" to "Mises à jour du compte",
    "Amount added" to "Montant ajouté",
    "App version 2.5.0" to "Version de l’application 2.5.0",
    "Bring your friends to MudaPay." to "Faites découvrir MudaPay à vos amis.",
    "CONFIRM" to "CONFIRMER",
    "CREATE ACCOUNT" to "CRÉER UN COMPTE",
    "Code valid for" to "Code valable pendant",
    "Create PIN" to "Créer un PIN",
    "Daily limit" to "Limite quotidienne",
    "Deposit successful" to "Dépôt réussi",
    "Describe your issue" to "Décrivez votre problème",
    "Enter the code. The money is taken from your Lumicash account and added to your MudaPay balance, one for one." to "Saisissez le code. L’argent est prélevé sur votre compte Lumicash et ajouté à votre solde MudaPay, un pour un.",
    "Enter your Lumicash number and the amount you want to add." to "Saisissez votre numéro Lumicash et le montant à ajouter.",
    "Insufficient balance." to "Solde insuffisant.",
    "Keep enough money on your Lumicash account to cover the amount (and any Lumicash fee)." to "Gardez assez d’argent sur votre compte Lumicash pour couvrir le montant (et les éventuels frais Lumicash).",
    "LOG IN" to "SE CONNECTER",
    "Never share this code with anyone. MudaPay will never ask for it outside this screen." to "Ne partagez ce code avec personne. MudaPay ne vous le demandera jamais en dehors de cet écran.",
    "No MudaPay account uses this number. Check the number and the country, then try again." to "Aucun compte MudaPay n’utilise ce numéro. Vérifiez le numéro et le pays, puis réessayez.",
    "No tickets yet" to "Aucun ticket pour le moment",
    "Note (optional)" to "Note (facultatif)",
    "Password of this MudaPay account" to "Mot de passe de ce compte MudaPay",
    "Requests to other countries are paused right now." to "Les demandes vers d’autres pays sont suspendues pour le moment.",
    "Resend code" to "Renvoyer le code",
    "Service fee" to "Frais de service",
    "Set a password below to be able to unlink Google." to "Définissez un mot de passe ci-dessous pour pouvoir dissocier Google.",
    "Subject" to "Objet",
    "The code has expired. Ask for a new one." to "Le code a expiré. Demandez-en un nouveau.",
    "The minimum transfer is" to "Le transfert minimum est de",
    "This is above your daily sending limit. You can still send" to "Cela dépasse votre limite d’envoi quotidienne. Vous pouvez encore envoyer",
    "This member cannot receive requests right now." to "Ce membre ne peut pas recevoir de demandes pour le moment.",
    "Who joined and your QR code" to "Qui a rejoint et votre code QR",
    "Who joined with your link" to "Qui a rejoint avec votre lien",
    "You" to "Vous",
    "You receive a confirmation code by SMS on that number." to "Vous recevez un code de confirmation par SMS sur ce numéro.",
    "Your own keys: USDT (BEP20) and BNB" to "Vos propres clés : USDT (BEP20) et BNB",
    "Your rewards, transfers and withdrawals." to "Vos récompenses, transferts et retraits.",
    "Your tickets" to "Vos tickets",
    "A transaction PIN is required to send money or withdraw. Choose 4 to 6 digits. You will need it every time. Do not share it with anyone." to "Un code PIN de transaction est requis pour envoyer de l’argent ou retirer. Choisissez 4 à 6 chiffres. Vous en aurez besoin à chaque fois. Ne le partagez avec personne.",
)
