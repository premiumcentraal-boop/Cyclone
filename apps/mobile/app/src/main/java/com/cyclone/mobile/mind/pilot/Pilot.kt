package com.cyclone.mobile.mind.pilot

/**
 * What code, never a model, decides about risk: the apps Cyclone's quick hands stay out of, and the controls whose press
 * can't be taken back. Used by Instant, OneMind's crew and the recovery ladder.
 *
 * Plan 75 (alpha 145): the Pilot that used to live here (plan 41's Fast mode, retired by plan 69) is gone; OneMind's crew
 * (`mind/one/StepEngine`) does its work. The name stays so every caller keeps reading `Pilot.irreversible`.
 */
object Pilot {
    /** Apps the crew and Instant never act in: banking, payments and authenticators (by package segment or app name). */
    private val KEEP_OFF = Regex("(?i)^(bank|banking|mobilebanking|wallet|paypal|pay|gpay|payconiq|tikkie|authenticator|authy|twofa|revolut|bunq|coinbase|binance|kraken|klarna|ing|rabobank|abnamro|knab|asnbank|snsbank|n26|wise|monzo)$")

    fun keepOff(packageName: String, appLabel: String?): Boolean =
        (packageName.split('.') + appLabel.orEmpty().split(Regex("[^\\p{L}\\p{N}]+"))).any { it.isNotBlank() && KEEP_OFF.matches(it) }

    /** Controls whose press can't be taken back. Code decides this, never a model. */
    private val IRREVERSIBLE = Regex("(?i)\\b(send|sent|pay|payment|buy|order|purchase|checkout|delete|remove|post|publish|share|confirm|" +
        "transfer|subscribe|unsubscribe|book|reserve|submit|accept|agree|allow|sign out|log out|logout|uninstall|install|block|report|" +
        "unfollow|verstuur|verzend|verzenden|betaal|betalen|kopen|bestel|bestellen|verwijder|verwijderen|plaats|plaatsen|bevestig|bevestigen|delen)\\b")

    fun irreversible(label: String): Boolean = IRREVERSIBLE.containsMatchIn(label)
}
