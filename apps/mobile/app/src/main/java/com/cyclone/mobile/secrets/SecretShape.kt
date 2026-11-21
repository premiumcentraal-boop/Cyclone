package com.cyclone.mobile.secrets

/**
 * The one secret-shape rule on the phone: a keyword such as `password`, `token` or `api_key`, singular or plural,
 * followed by `:` or `=`. Every text that crosses from the PC or a lab prompt is refused when it matches, so a
 * secret is typed on the phone and never sent.
 *
 * It is the gateway's `INLINE_SECRET` (`desktop_runtime/v5_contract.py`) character for character; Glass adds `bearer`
 * (`services/secretGuards.ts`). `scripts/ci/tests/test_secret_shape_parity.py` keeps the three in step and fails on a
 * new copy.
 */
object SecretShape {
    val INLINE: Regex = Regex(
        "(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(?:text|value))s?\\s*[:=]",
    )
}
