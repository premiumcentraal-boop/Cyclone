package com.cyclone.mobile.runtime.safe

/** No Android/tool entry point. The only caller of this coordinator is the credential-gated owner Activity. */
internal object SafeDelete {
    enum class Step { PC, FOLDER, ROOT, HIVE, PROFILES, MAIN }
    class Confirmation private constructor(internal val issuedAt: Long) {
        private var consumed = false
        @Synchronized internal fun consume(now: Long) {
            check(!consumed && now >= issuedAt && now - issuedAt <= 60000) { "SAFE_DELETE_CONFIRMATION_EXPIRED" }
            consumed = true
        }
        companion object {
            /** Called only in the successful DEVICE_CREDENTIAL callback; never from an Intent or agent. */
            internal fun afterScreenLock(now: Long): Confirmation = Confirmation(now)
        }
    }
    data class Result(val completed: List<Step>, val stoppedAt: Step? = null) {
        val message: String get() = if (stoppedAt == null) "Cyclone data deleted" else "Nothing more was deleted"
    }
    fun run(confirmation: Confirmation, now: Long, perform: (Step) -> Unit): Result {
        confirmation.consume(now)
        val done = mutableListOf<Step>()
        for (step in Step.entries) {
            try { perform(step); done += step } catch (_: Exception) { return Result(done.toList(), step) }
        }
        return Result(done.toList())
    }
}
