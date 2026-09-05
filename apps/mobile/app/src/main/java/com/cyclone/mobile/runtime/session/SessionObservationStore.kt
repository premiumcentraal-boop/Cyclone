package com.cyclone.mobile.runtime.session

data class SessionObservationKey(
    val sessionId: String,
    val displayId: Int,
)

data class SessionObservationEnvelope(
    val sessionId: String,
    val displayId: Int,
    val observationId: String,
    val generation: Long,
    val payload: Any?,
    val timestampEpochMs: Long,
)

class SessionObservationStore {
    private val lock = Any()
    private val current = mutableMapOf<SessionObservationKey, SessionObservationEnvelope>()
    private val generations = mutableMapOf<String, Long>()

    fun publish(
        sessionId: String,
        displayId: Int,
        observationId: String,
        payload: Any?,
        timestampEpochMs: Long,
    ): SessionObservationEnvelope = synchronized(lock) {
        require(observationId.isNotBlank())
        val next = (generations[sessionId] ?: 0L) + 1L
        generations[sessionId] = next
        val envelope = SessionObservationEnvelope(
            sessionId = sessionId,
            displayId = displayId,
            observationId = observationId,
            generation = next,
            payload = payload,
            timestampEpochMs = timestampEpochMs,
        )
        current[SessionObservationKey(sessionId, displayId)] = envelope
        envelope
    }

    fun current(sessionId: String, displayId: Int = ExecutionSession.DEFAULT_DISPLAY_ID): SessionObservationEnvelope? =
        synchronized(lock) { current[SessionObservationKey(sessionId, displayId)] }

    fun currentOrLegacy(sessionId: String?): SessionObservationEnvelope? {
        val resolved = sessionId?.takeIf { it.isNotBlank() } ?: ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
        return current(resolved)
    }

    fun associate(sessionId: String, displayId: Int, observationId: String) {
        val existing = current(sessionId, displayId)
            ?: throw SessionIdentityException("no observation for session")
        if (existing.observationId != observationId) {
            throw SessionIdentityException("foreign observation/session mismatch")
        }
        if (existing.displayId != displayId) {
            throw SessionIdentityException("display/session mismatch")
        }
    }
}
