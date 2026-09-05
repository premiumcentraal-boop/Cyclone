package com.cyclone.mobile.runtime.session

class SessionIdentityException(message: String) : IllegalArgumentException(message)

class ExecutionSessionStore {
    private val lock = Any()
    private val sessions = linkedMapOf<String, ExecutionSession>()

    init {
        val def = ExecutionSession.defaultForeground()
        sessions[def.sessionId] = def
    }

    fun defaultForeground(): ExecutionSession = synchronized(lock) {
        sessions[ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID]
            ?: ExecutionSession.defaultForeground().also { sessions[it.sessionId] = it }
    }

    fun lookup(sessionId: String?): ExecutionSession = synchronized(lock) {
        if (sessionId.isNullOrBlank()) return@synchronized defaultForeground()
        sessions[sessionId] ?: throw SessionIdentityException("unknown session $sessionId")
    }

    fun registerSynthetic(
        sessionId: String,
        displayId: Int,
        backend: ExecutionBackendKind,
        targetPackage: String? = null,
        inputOwner: InputOwner = InputOwner.CYCLONE,
        nowEpochMs: Long = 0L,
    ): ExecutionSession = synchronized(lock) {
        require(sessionId.isNotBlank()) { "sessionId required" }
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            throw SessionIdentityException("cannot replace default foreground session")
        }
        if (sessions.containsKey(sessionId)) {
            throw SessionIdentityException("duplicate session identity")
        }
        if (displayId < 0) throw SessionIdentityException("invalid displayId")
        val session = ExecutionSession(
            sessionId = sessionId,
            displayId = displayId,
            targetPackage = targetPackage,
            backend = backend,
            inputOwner = inputOwner,
            executable = false,
            createdAtEpochMs = nowEpochMs,
        )
        sessions[sessionId] = session
        session
    }

    fun remove(sessionId: String) = synchronized(lock) {
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            throw SessionIdentityException("cannot remove default foreground session")
        }
        sessions.remove(sessionId)
    }

    fun snapshot(): List<ExecutionSession> = synchronized(lock) { sessions.values.toList() }
}
