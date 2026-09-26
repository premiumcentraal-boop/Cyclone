package com.cyclone.mobile.mind

import com.cyclone.mobile.agent.contract.AgentPageCard

data class MindApp(val packageName: String, val label: String)

enum class MindApproval { APPROVED, DECLINED, TIMED_OUT, CANCELLED, NOT_PENDING }

data class MindOwnerReply(val answered: Boolean, val text: String = "", val waitedMs: Long = 0)

data class MindApprovalReply(val outcome: MindApproval, val waitedMs: Long = 0)

enum class MindSecretOutcome { FILLED, DECLINED, MISSING, FAILED, TIMED_OUT, UNAVAILABLE }

data class MindSecretReply(val outcome: MindSecretOutcome, val waitedMs: Long = 0, val detail: String = "")

/** A value the Mind needs from the owner; [ref] is the field it belongs in, when there is one. */
data class MindValueField(val label: String, val kind: String = "text", val choices: List<String> = emptyList(), val ref: String? = null)

enum class MindValuesOutcome { FILLED, TOOK_OVER, DECLINED, TIMED_OUT, CANCELLED }

data class MindValuesReply(
    val outcome: MindValuesOutcome,
    val values: Map<String, String> = emptyMap(),
    val remember: Boolean = false,
    val waitedMs: Long = 0,
)

data class MindPlanStep(val text: String, val status: String) {
    companion object {
        val STATUSES = listOf("todo", "doing", "done", "skipped")
    }
}

/**
 * The owner as seen by the Mind. Every call may block while the owner decides; implementations return promptly when
 * the mission is stopped and report how long the owner took, which does not count as working time.
 */
interface MindOwnerPort {
    fun ask(question: String, choices: List<String>, timeoutMs: Long): MindOwnerReply
    /** Cyclone has already put the exact action on the approval card; wait for the owner's decision. */
    fun awaitApproval(action: String, timeoutMs: Long): MindApprovalReply
    /** Opens the Secrets Card for this field. The value goes from the owner or the Vault straight into the field. */
    fun fillSecret(page: AgentPageCard, target: MindRef, slot: String, reason: String, timeoutMs: Long): MindSecretReply
    /** The owner took over the phone; wait until they hand it back. */
    fun awaitControl(timeoutMs: Long): MindOwnerReply
    /** Hands the phone to the owner for a step only they can do, and waits until they hand it back. */
    fun takeover(instruction: String, timeoutMs: Long): MindOwnerReply = awaitControl(timeoutMs)
    /** The check-in card: the owner types the values, or takes over and does it by hand. */
    fun fill(reason: String, fields: List<MindValueField>, timeoutMs: Long): MindValuesReply = MindValuesReply(MindValuesOutcome.DECLINED)
    fun status(text: String) {}
    fun plan(steps: List<MindPlanStep>) {}
}

/** A control's box on the screen, in screen pixels, to draw on a screenshot. */
data class MindMark(val ref: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

data class MindNotification(val key: String, val app: String, val title: String, val text: String, val postedAtMs: Long,
    val actions: List<String> = emptyList(), val openable: Boolean = true,
    /** Plan 26: the notification has a reply action (answer without opening the app). */
    val replyable: Boolean = false)

data class MindImage(val dataUrl: String, val width: Int, val height: Int)

/**
 * Prepares a screenshot for the model: draws each ref's box and name on it (so "e7" in the text is the box labelled
 * e7 in the picture) and scales it down to a size vision models read well. Null when the image cannot be prepared.
 */
fun interface MindImageMarker {
    fun mark(pngBase64: String, marks: List<MindMark>, screenWidth: Int, screenHeight: Int): MindImage?
}

/** Facts about the phone that are not on the screen. */
interface MindDevicePort {
    fun apps(): List<MindApp>
    fun now(): String
    fun device(): String
    fun sleep(ms: Long) { Thread.sleep(ms) }
    /** Recent notifications, newest first. Implementations never include secret values. */
    fun notifications(): List<MindNotification> = emptyList()
    /** Why the phone cannot be operated right now (locked, screen off), or null when it can. */
    fun blocker(): String? = null
    /**
     * Plan 21 (Hands): put a draft the Mind wrote on the clipboard for the owner to paste. Never secrets (typing refuses
     * secret fields before a draft can exist). The Mind writes the clipboard; it never reads it.
     */
    fun copy(text: String): Boolean = false

    /**
     * Plan 26 (A42-6, tier 0): reply to a message through its notification, without the screen. The caller has the
     * owner's approval for this exact text. Null when it worked, otherwise why not.
     */
    fun replyNotification(key: String, text: String): String? = "Replying from a notification is not available here."
}
