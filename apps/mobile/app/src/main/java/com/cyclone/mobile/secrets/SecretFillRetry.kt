package com.cyclone.mobile.secrets

/** Retry only before a write. An unverified write must never be silently sent a second time. */
internal object SecretFillRetry {
    fun run(settle: () -> Unit = {}, attempt: () -> SecretFillExecution): SecretFillExecution {
        repeat(3) { index ->
            val result = attempt()
            if (result.performed || result.verified || result.errorCode !in setOf(
                    "STALE_OBSERVATION", "STALE_ELEMENT", "SECRET_TARGET_CHANGED") || index == 2) return result
            settle()
        }
        error("unreachable")
    }
}
