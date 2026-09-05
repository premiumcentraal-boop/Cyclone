package com.cyclone.mobile.runtime.session

enum class ExecutionBackendKind {
    FOREGROUND_ACCESSIBILITY,
    SHIZUKU,
    ROOT,
    VIRTUAL_DISPLAY,
}

enum class InputOwner {
    HUMAN,
    CYCLONE,
    UNOWNED,
}

data class ExecutionSession(
    val sessionId: String,
    val displayId: Int,
    val targetPackage: String? = null,
    val backend: ExecutionBackendKind = ExecutionBackendKind.FOREGROUND_ACCESSIBILITY,
    val inputOwner: InputOwner = InputOwner.HUMAN,
    val executable: Boolean,
    val createdAtEpochMs: Long,
) {
    val isDefaultForeground: Boolean
        get() = sessionId == DEFAULT_FOREGROUND_SESSION_ID && displayId == DEFAULT_DISPLAY_ID

    companion object {
        const val DEFAULT_FOREGROUND_SESSION_ID = "default-foreground"
        const val DEFAULT_DISPLAY_ID = 0

        fun defaultForeground(nowEpochMs: Long = 0L): ExecutionSession = ExecutionSession(
            sessionId = DEFAULT_FOREGROUND_SESSION_ID,
            displayId = DEFAULT_DISPLAY_ID,
            backend = ExecutionBackendKind.FOREGROUND_ACCESSIBILITY,
            inputOwner = InputOwner.HUMAN,
            executable = true,
            createdAtEpochMs = nowEpochMs,
        )
    }
}

data class ExecutionContext(
    val sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
    val displayId: Int = ExecutionSession.DEFAULT_DISPLAY_ID,
    val observationId: String? = null,
    val frameId: Long? = null,
) {
    companion object {
        val DEFAULT = ExecutionContext()
    }
}
