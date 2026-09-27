package com.cyclone.mobile.mind.mission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class OwnerRequestKind { QUESTION, APPROVAL, SECRET, CONTROL, VALUES }

/** One value the owner is asked to type on the check-in card. Never a secret: those go to the Secrets Card. */
data class OwnerField(val label: String, val kind: String = "text", val choices: List<String> = emptyList())

/** Something the running mission needs from the owner. Never carries a secret value. */
data class OwnerRequest(
    val id: String,
    val missionId: String,
    val kind: OwnerRequestKind,
    val text: String,
    val choices: List<String> = emptyList(),
    val createdAtMs: Long,
    val fields: List<OwnerField> = emptyList(),
)

sealed class OwnerResponse {
    data class Answer(val text: String) : OwnerResponse()
    data object Approve : OwnerResponse()
    data object Decline : OwnerResponse()
    data object Done : OwnerResponse()
    /** The owner will do it by hand; the mission hands the phone over and waits for "I'm done". */
    data object TakeOver : OwnerResponse()
    /** Values typed on the check-in card, keyed by field label. */
    data class Values(val values: Map<String, String>, val remember: Boolean) : OwnerResponse()
}

data class OwnerWait(val response: OwnerResponse?, val waitedMs: Long, val cancelled: Boolean)

/**
 * One place where a mission waits for its owner. The mission thread posts a request and blocks; the phone UI
 * (notification, request screen, overlay, app) shows [pending] and answers through [respond]. There is at most one
 * open request at a time because a mission is one train of thought.
 */
class OwnerInbox(private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Object()
    private val state = MutableStateFlow<OwnerRequest?>(null)
    private var response: OwnerResponse? = null
    private var owner: String? = null

    val pending: StateFlow<OwnerRequest?> = state

    fun post(missionId: String, kind: OwnerRequestKind, text: String, choices: List<String> = emptyList(),
             fields: List<OwnerField> = emptyList()): OwnerRequest =
        synchronized(lock) {
            val request = OwnerRequest("req-${UUID.randomUUID()}", missionId, kind, text.take(600), choices.take(6), clock(), fields.take(MAX_FIELDS))
            response = null
            owner = request.id
            state.value = request
            request
        }

    /** Returns false when [requestId] is no longer the open request (answered, withdrawn or replaced). */
    fun respond(requestId: String, reply: OwnerResponse): Boolean = synchronized(lock) {
        if (owner != requestId || response != null) return false
        response = reply
        lock.notifyAll()
        true
    }

    /** Blocks until the owner answers, [timeoutMs] passes or [cancelled] turns true; always clears the request. */
    fun await(request: OwnerRequest, timeoutMs: Long, cancelled: () -> Boolean): OwnerWait {
        val started = clock()
        try {
            synchronized(lock) {
                while (true) {
                    if (owner != request.id) return OwnerWait(null, clock() - started, cancelled = true)
                    response?.let { return OwnerWait(it, clock() - started, cancelled = false) }
                    if (cancelled()) return OwnerWait(null, clock() - started, cancelled = true)
                    val left = timeoutMs - (clock() - started)
                    if (left <= 0) return OwnerWait(null, clock() - started, cancelled = false)
                    lock.wait(minOf(left, POLL_MS))
                }
            }
        } finally {
            withdraw(request.id)
        }
    }

    /** Non-blocking: the owner's reply to the open request, if any; the request stays open. */
    fun poll(requestId: String): OwnerResponse? = synchronized(lock) { response.takeIf { owner == requestId } }

    fun withdraw(requestId: String) = synchronized(lock) {
        if (owner == requestId) {
            owner = null
            response = null
            state.value = null
            lock.notifyAll()
        }
    }

    /** Stops whatever the mission is waiting for (the owner pressed Stop). */
    fun withdrawAll() = synchronized(lock) {
        owner = null
        response = null
        state.value = null
        lock.notifyAll()
    }

    private companion object {
        const val POLL_MS = 250L
        const val MAX_FIELDS = 8
    }
}
