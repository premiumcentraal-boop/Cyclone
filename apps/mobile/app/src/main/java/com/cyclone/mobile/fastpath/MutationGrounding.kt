package com.cyclone.mobile.fastpath

/** Fresh IDs and fingerprints must describe the same execution surface at dispatch. */
object MutationGrounding {
    fun matches(requestedId: String, currentId: String?, observedFingerprint: String?, liveFingerprint: String?,
        expectedSession: String, expectedDisplay: Int, actualSession: String?, actualDisplay: Int?): Boolean =
        requestedId.isNotBlank() && requestedId == currentId &&
            expectedSession == actualSession && expectedDisplay == actualDisplay &&
            !observedFingerprint.isNullOrBlank() && observedFingerprint == liveFingerprint

    fun verifiedTransition(executed: Boolean, changed: Boolean?, expectationVerified: Boolean = false): Boolean =
        executed && (expectationVerified || changed == true)
}
