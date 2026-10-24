package io.mudapay.mobile

/**
 * Writes the "legend" (share caption) a member posts together with the MudaPay app.
 *
 * Two sources, same shape:
 *  - [local]: instant, works offline, 3 languages x 4 tones, personalised (first name, own words, invite link).
 *  - [aiPrompt] + [clean]: the same facts sent to Muda AI, whose reply is cleaned and always ends with the invite link.
 *
 * The texts describe MudaPay honestly (learn, tasks, videos, daily bonus, referrals, withdrawals) and never promise income.
 * Pure Kotlin on purpose: no Android classes, so it can be unit-tested anywhere.
 */
object CaptionEngine {
    val TONES = listOf("friendly" to "Friendly", "excited" to "Excited", "pro" to "Professional", "short" to "Short & punchy")
    val LANGS = listOf("en" to "English", "fr" to "Français", "sw" to "Kiswahili")

    class Spec(
        val tone: String = "friendly",
        val lang: String = "en",
        val name: String = "",       // first name of the member (signature)
        val country: String = "",    // display name of the member's country
        val link: String = "",       // invite link
        val note: String = "",       // what the member says about MudaPay, in their own words
        val seed: Int = 0            // changes the variant: "New idea" simply bumps it
    )

    private class Pack(
        val variants: Map<String, List<String>>,   // tone -> templates
        val noteLabel: String,
        val joinLine: String,
        val countryLine: String,
        val honest: String
    )

    // Placeholders: {name} {link} {note} {country}
    private val EN = Pack(
        variants = mapOf(
            "friendly" to listOf(
                "Hey! 👋 I'm using MudaPay and thought of you.\n\nIt's a learning & rewards app where you can:\n📚 complete daily tasks and short lessons\n🎬 watch videos and get rewarded\n🎁 collect a daily bonus\n👥 invite friends and earn referral rewards\n💸 withdraw to mobile money\n\n{note}",
                "Looking for a smart way to use your free time? 💚\n\nMudaPay mixes learning, community and rewards in one app: daily tasks, videos, a daily bonus, referrals and a leaderboard, with withdrawals to mobile money.\n\n{note}"
            ),
            "excited" to listOf(
                "🚀 Your phone can do more than scroll!\n\nMudaPay = learn 📚 + watch 🎬 + share 👥 + earn rewards 💰\nDaily bonus 🎁, leaderboard 🏆, mobile money withdrawals 💸\n\n{note}",
                "🔥 New on my phone and I love it: MudaPay!\n\n✅ Daily tasks & lessons\n✅ Videos that reward you\n✅ Daily streak bonus\n✅ Referral rewards when friends join\n✅ Withdraw to mobile money\n\n{note}"
            ),
            "pro" to listOf(
                "MudaPay is a mobile platform that combines learning activities, community participation and a rewards programme.\n\nMembers complete daily tasks, watch videos, keep a daily streak and invite others, then request withdrawals to mobile money.\n\n{note}"
            ),
            "short" to listOf(
                "Learn, watch & collect rewards with MudaPay 💚 {note}",
                "MudaPay: daily tasks, videos, bonuses and referrals in one app 🚀 {note}"
            )
        ),
        noteLabel = "💬",
        joinLine = "Join with my link 👇",
        countryLine = "Made for members across Africa, including {country}.",
        honest = "Rewards depend on your activity."
    )

    private val FR = Pack(
        variants = mapOf(
            "friendly" to listOf(
                "Coucou ! 👋 J'utilise MudaPay et j'ai pensé à toi.\n\nC'est une application d'apprentissage et de récompenses où tu peux :\n📚 faire des tâches et de courtes leçons chaque jour\n🎬 regarder des vidéos et être récompensé\n🎁 récupérer un bonus quotidien\n👥 inviter des amis et gagner des récompenses de parrainage\n💸 retirer vers le mobile money\n\n{note}",
                "Tu cherches une façon maligne d'utiliser ton temps libre ? 💚\n\nMudaPay réunit apprentissage, communauté et récompenses : tâches du jour, vidéos, bonus quotidien, parrainage et classement, avec retraits vers le mobile money.\n\n{note}"
            ),
            "excited" to listOf(
                "🚀 Ton téléphone peut faire plus que scroller !\n\nMudaPay = apprendre 📚 + regarder 🎬 + partager 👥 + gagner des récompenses 💰\nBonus quotidien 🎁, classement 🏆, retraits mobile money 💸\n\n{note}",
                "🔥 Ma nouvelle appli préférée : MudaPay !\n\n✅ Tâches et leçons du jour\n✅ Des vidéos qui rapportent\n✅ Bonus de série quotidienne\n✅ Récompenses quand tes amis rejoignent\n✅ Retrait vers le mobile money\n\n{note}"
            ),
            "pro" to listOf(
                "MudaPay est une plateforme mobile qui associe activités d'apprentissage, participation communautaire et programme de récompenses.\n\nLes membres réalisent des tâches quotidiennes, regardent des vidéos, gardent une série quotidienne et invitent d'autres personnes, puis demandent un retrait vers le mobile money.\n\n{note}"
            ),
            "short" to listOf(
                "Apprends, regarde et récolte des récompenses avec MudaPay 💚 {note}",
                "MudaPay : tâches, vidéos, bonus et parrainage dans une seule appli 🚀 {note}"
            )
        ),
        noteLabel = "💬",
        joinLine = "Rejoins-moi avec mon lien 👇",
        countryLine = "Pensé pour les membres partout en Afrique, y compris en {country}.",
        honest = "Les récompenses dépendent de ton activité."
    )

    private val SW = Pack(
        variants = mapOf(
            "friendly" to listOf(
                "Habari! 👋 Natumia MudaPay na nimekukumbuka.\n\nNi programu ya kujifunza na zawadi ambapo unaweza:\n📚 kufanya kazi za kila siku na masomo mafupi\n🎬 kutazama video na kupata zawadi\n🎁 kupokea bonasi ya kila siku\n👥 kualika marafiki na kupata zawadi za rufaa\n💸 kutoa pesa kwenda mobile money\n\n{note}",
                "Unatafuta njia nzuri ya kutumia muda wako? 💚\n\nMudaPay inaunganisha kujifunza, jamii na zawadi: kazi za kila siku, video, bonasi ya kila siku, rufaa na orodha ya viongozi, pamoja na kutoa pesa kwenda mobile money.\n\n{note}"
            ),
            "excited" to listOf(
                "🚀 Simu yako inaweza kufanya zaidi ya kuperuzi tu!\n\nMudaPay = jifunze 📚 + tazama 🎬 + shiriki 👥 + pata zawadi 💰\nBonasi ya kila siku 🎁, orodha ya viongozi 🏆, kutoa kwenda mobile money 💸\n\n{note}",
                "🔥 Programu mpya ninayoipenda: MudaPay!\n\n✅ Kazi na masomo ya kila siku\n✅ Video zinazolipa zawadi\n✅ Bonasi ya mfululizo wa kila siku\n✅ Zawadi marafiki wakijiunga\n✅ Toa kwenda mobile money\n\n{note}"
            ),
            "pro" to listOf(
                "MudaPay ni jukwaa la simu linalounganisha shughuli za kujifunza, ushiriki wa jamii na mpango wa zawadi.\n\nWanachama hufanya kazi za kila siku, hutazama video, hudumisha mfululizo wa kila siku na kualika wengine, kisha huomba kutoa pesa kwenda mobile money.\n\n{note}"
            ),
            "short" to listOf(
                "Jifunze, tazama na pata zawadi na MudaPay 💚 {note}",
                "MudaPay: kazi, video, bonasi na rufaa katika programu moja 🚀 {note}"
            )
        ),
        noteLabel = "💬",
        joinLine = "Jiunge kupitia kiungo changu 👇",
        countryLine = "Imetengenezwa kwa wanachama kote Afrika, ikiwemo {country}.",
        honest = "Zawadi hutegemea shughuli zako."
    )

    private fun pack(lang: String) = when (lang) { "fr" -> FR; "sw" -> SW; else -> EN }

    /** Instant caption. Always ends with the invite link (when there is one). */
    fun local(s: Spec): String {
        val p = pack(s.lang)
        val list = p.variants[s.tone] ?: p.variants.getValue("friendly")
        val body = list[Math.floorMod(s.seed, list.size)]
        val note = s.note.trim().replace(Regex("\\s+"), " ").take(240)
        val noteText = if (note.isEmpty()) "" else p.noteLabel + " " + note
        val short = s.tone == "short"
        var text = body.replace("{note}", noteText).trim()
        if (!short) {
            if (s.country.isNotBlank()) text += "\n" + p.countryLine.replace("{country}", s.country.trim())
            text += "\n" + p.honest
        }
        text = text.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return withLink(text, s.link, p.joinLine, s.name, short)
    }

    private fun withLink(text: String, link: String, joinLine: String, name: String, short: Boolean): String {
        val sb = StringBuilder(text)
        if (link.isNotBlank()) {
            sb.append(if (short) "\n" else "\n\n").append(if (short) "👉 " else "$joinLine\n").append(link.trim())
        }
        val n = name.trim()
        if (n.isNotEmpty() && !short) sb.append("\n\n— ").append(n)
        return sb.toString()
    }

    /** Prompt for Muda AI. The reply goes through [clean]. */
    fun aiPrompt(s: Spec): String {
        val language = LANGS.firstOrNull { it.first == s.lang }?.second ?: "English"
        val tone = when (s.tone) {
            "excited" -> "excited and energetic, with a few emojis"
            "pro" -> "professional and trustworthy, calm, at most one emoji"
            "short" -> "very short (under 220 characters) and punchy, with 1-2 emojis"
            else -> "warm, friendly and personal, with a few emojis"
        }
        val sb = StringBuilder()
        sb.append("Write ONE ready-to-post share caption in ").append(language).append(" that a MudaPay member sends to friends together with the app. ")
        sb.append("Tone: ").append(tone).append(". ")
        sb.append("Describe MudaPay accurately: a mobile app for learning, daily tasks, videos, a daily bonus, referrals, a leaderboard and withdrawals to mobile money. ")
        sb.append("Never promise or guarantee income; say rewards depend on activity. ")
        if (s.country.isNotBlank()) sb.append("The member lives in ").append(s.country.trim()).append(". ")
        if (s.note.isNotBlank()) sb.append("Work in the member's own words naturally: \"").append(s.note.trim().take(240)).append("\". ")
        if (s.name.isNotBlank()) sb.append("Sign it with the first name ").append(s.name.trim()).append(" only when the tone is not short. ")
        if (s.link.isNotBlank()) sb.append("Include this invite link exactly once, on its own line: ").append(s.link.trim()).append(". ")
        sb.append("Reply with the caption only: no title, no quotes, no markdown, no explanation. Variation #").append(Math.floorMod(s.seed, 97) + 1).append(".")
        return sb.toString()
    }

    /** Cleans an AI reply into plain post text and makes sure the invite link is present. */
    fun clean(raw: String, link: String, joinLine: String = "👉"): String {
        var t = raw.trim()
        t = t.replace("**", "").replace("__", "").replace("`", "")
        t = t.lines().joinToString("\n") { line ->
            val l = line.trimEnd().replace(Regex("^#{1,3}\\s+"), "")
            if (l.startsWith("- ") || l.startsWith("* ")) "• " + l.substring(2) else l
        }
        t = t.replace(Regex("(?i)^(caption|legend|légende|legende|maelezo)\\s*[:：]\\s*"), "")
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))) t = t.substring(1, t.length - 1).trim()
        t = t.replace(Regex("\\n{3,}"), "\n\n").trim()
        val l = link.trim()
        if (l.isNotEmpty() && !t.contains(l)) t += "\n\n$joinLine $l"
        return t
    }

    fun joinLine(lang: String): String = pack(lang).joinLine
}
