package com.cyclone.mobile.mind.one

/**
 * Plan 74 (alpha 149): what kind of need a moment of a run has, and whether it may raise an owner card. A card only when code
 * can prove the owner is the only one who can answer. Everything else Cyclone settles itself, says what it assumed, and goes on.
 * Pure.
 */
enum class NeedKind(val hard: Boolean) {
    /** Consent to something that can't be taken back (a send, a payment, a delete). Always a card. */
    CONSENT(hard = true),
    /** A password, code or other secret. Always a card (the vault asks). */
    SECRET(hard = true),
    /** The owner's hands: a CAPTCHA, a fingerprint, a system sheet. Always a card. */
    HANDS(hard = true),
    /** A fact only the owner knows (a number, a name nobody has in the phone). Always a card. */
    UNKNOWABLE(hard = true),
    /** Which of several things is meant. Settled by assuming a choice, and saying so. */
    CLARIFY(hard = false),
    /** The run is stuck. Settled by recovery, then a hand-over to the captain, then an honest stop. Never a card. */
    STUCK(hard = false),
    /** Whether a result is right. Settled by the done check. */
    VERIFY(hard = false),
    /** A preference that has a default. Settled by the default. */
    PREFERENCE(hard = false),
    /** Text that can be typed another way. Settled by another input method. */
    INPUT(hard = false),
    /** Where to work. The screen by default. */
    PLACE(hard = false),
}

object NeedGate {
    /** A card is raised only for a hard need. */
    fun raisesCard(kind: NeedKind): Boolean = kind.hard

    /**
     * The kind of a question the mind asks: consent when it says so; a choice among two or more answers is a clarification;
     * a question with no choices asks for a fact only the owner has.
     */
    fun kindOf(consent: Boolean, choices: List<String>): NeedKind = when {
        consent -> NeedKind.CONSENT
        choices.count { it.isNotBlank() } >= 2 -> NeedKind.CLARIFY
        else -> NeedKind.UNKNOWABLE
    }
}
