package io.mudapay.mobile

import android.content.Context

/**
 * Notifications created by what the member does inside the app (and by the sync check).
 * Every one of them is a real system notification, shown even while the app is open.
 */
object Notify {
    private fun sym(): String = Session.user?.str("symbol") ?: ""

    private fun send(m: PushMsg) {
        val c = Push.appOrNull() ?: return
        Push.post(c, m)
    }

    /** A server notification row (credit) was already announced here, so the sync check must not repeat it. */
    private fun skipServerCredit(amount: Double) {
        val c = Push.appOrNull() ?: return
        Push.markShown(c, "credit_amt:${amount.toLong()}")
    }

    private fun balanceLine(balance: Double?): String? = if (balance == null) null else "New balance: ${money(sym(), balance)}"

    private fun masked(account: String, crypto: Boolean): String {
        val a = account.trim()
        if (a.isEmpty()) return ""
        return if (crypto && a.length > 12) a.take(6) + "…" + a.takeLast(4) else "••••" + a.takeLast(4)
    }

    /* ---------------------------------------------------------------- money out */
    fun withdrawalRequested(amount: Double, method: String, account: String, balance: Double?, usd: Double?, coin: String?) {
        val s = sym()
        val crypto = method == "Crypto"
        val where = if (crypto) "in ${(coin ?: "crypto").uppercase()}" else "via $method"
        send(PushMsg(
            "withdrawal_requested",
            "You withdrew ${money(s, amount)}",
            "Your ${money(s, amount)} withdrawal $where is pending review. We'll notify you as soon as it is paid.",
            "Withdraw",
            listOfNotNull(
                "Amount: ${money(s, amount)}",
                if (crypto && usd != null && usd > 0) "Value: ${"%.2f".format(usd)} USD" else null,
                "Method: ${if (crypto) "Crypto ${(coin ?: "").uppercase()}".trim() else method}",
                masked(account, crypto).takeIf { it.isNotEmpty() }?.let { "To: $it" },
                "Status: Pending review",
                balanceLine(balance)
            ),
            subText = "Withdrawal"
        ))
    }

    /* ---------------------------------------------------------------- money in */
    fun taskCompleted(reward: Double, balance: Double?) = send(PushMsg(
        "task_reward", "Task completed 🎉", "+${money(sym(), reward)} was added to your balance.", "Tasks",
        listOfNotNull("Reward: ${money(sym(), reward)}", balanceLine(balance), "More tasks are waiting in the Tasks tab.")
    ))

    fun videoWatched(reward: Double, balance: Double?) = send(PushMsg(
        "video_reward", "Watch reward earned 🎬", "+${money(sym(), reward)} for watching the whole video.", "Videos",
        listOfNotNull("Reward: ${money(sym(), reward)}", balanceLine(balance), "Like the video to earn a little more.")
    ))

    fun videoLiked(reward: Double, balance: Double?) = send(PushMsg(
        "like_reward", "Like reward earned ❤️", "+${money(sym(), reward)} for liking a video.", "Videos",
        listOfNotNull("Reward: ${money(sym(), reward)}", balanceLine(balance))
    ))

    fun dailyBonus(amount: Double, balance: Double?, streak: Int?) {
        skipServerCredit(amount)
        send(PushMsg(
            "daily_bonus_claimed", "Daily bonus added 🔥", "+${money(sym(), amount)} daily bonus is now in your balance.", "Home",
            listOfNotNull(
                "Bonus: ${money(sym(), amount)}",
                if (streak != null && streak > 0) "Login streak: day $streak" else null,
                balanceLine(balance),
                "Come back tomorrow to keep your streak going."
            )
        ))
    }

    fun shareApproved(reward: Double, balance: Double?) {
        skipServerCredit(reward)
        send(PushMsg(
            "share_reward", "Share approved 🎉", "+${money(sym(), reward)} was added for your campaign post.", "Share",
            listOfNotNull("Reward: ${money(sym(), reward)}", balanceLine(balance))
        ))
    }

    fun shareRejected(reason: String) = send(PushMsg(
        "share_rejected", "Share not approved", reason.ifEmpty { "We could not verify your post." }, "Share",
        listOf("Check that the post is public and shows the campaign, then send a new screenshot.")
    ))

    fun shareReceived() = send(PushMsg(
        "share_pending", "Screenshot received", "Your share is being reviewed. You'll be notified with the result.", "Share"
    ))

    /* ---------------------------------------------------------------- account */
    fun paymentProofReceived() = send(PushMsg(
        "payment_received", "Payment proof received", "Your activation proof is waiting for review. We'll notify you as soon as it is approved.", "Home"
    ))

    fun ticketSent(subject: String) = send(PushMsg(
        "ticket_sent", "Support ticket sent", "We received \"${subject.take(60)}\" and will get back to you.", "Support",
        listOf("You can follow the answer in Support center.")
    ))

    fun passwordChanged(firstTime: Boolean) = send(PushMsg(
        "password_changed", if (firstTime) "Password set" else "Password changed",
        "Your MudaPay password was ${if (firstTime) "set" else "updated"} and your other devices were signed out.", "Settings",
        listOf("If this wasn't you, contact Support right away.")
    ))

    fun googleLinked() = send(PushMsg(
        "google_linked", "Google account linked", "You can now sign in to MudaPay with Google or with your phone number.", "Settings"
    ))

    fun googleUnlinked() = send(PushMsg(
        "google_unlinked", "Google account unlinked", "Google sign-in was removed. You will sign in with your phone number and password.", "Settings"
    ))

    fun backOnline(sent: Int, reward: Double, balance: Double?) = send(PushMsg(
        "sync_done", "Back online",
        if (reward > 0) "$sent saved action(s) were sent: +${money(sym(), reward)} added to your balance." else "$sent saved action(s) were sent.",
        "Home", listOfNotNull(balanceLine(balance))
    ))
}
