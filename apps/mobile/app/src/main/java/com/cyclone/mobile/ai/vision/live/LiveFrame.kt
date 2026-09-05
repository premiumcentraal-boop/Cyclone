package com.cyclone.mobile.ai.vision.live

import com.cyclone.mobile.runtime.session.ExecutionSession

enum class FrameSourceType {
    ACCESSIBILITY_SCREENSHOT,
    SCRCPY,
    MEDIA_PROJECTION,
    VIRTUAL_DISPLAY_SURFACE,
}

data class LiveFrame(
    val sessionId: String,
    val displayId: Int,
    val frameId: Long,
    val capturedAtEpochMs: Long,
    val width: Int,
    val height: Int,
    val source: FrameSourceType,
    val payloadHandle: String? = null,
    val correlationId: String? = null,
)

data class FrameSourceStatus(
    val sessionId: String,
    val displayId: Int,
    val type: FrameSourceType,
    val running: Boolean,
    val available: Boolean,
)

interface FrameSource {
    val status: FrameSourceStatus
    fun publishInto(broker: LiveFrameBroker, frame: LiveFrame)
}

class AccessibilityScreenshotFrameSource(
    sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
    displayId: Int = ExecutionSession.DEFAULT_DISPLAY_ID,
) : FrameSource {
    override val status = FrameSourceStatus(
        sessionId = sessionId,
        displayId = displayId,
        type = FrameSourceType.ACCESSIBILITY_SCREENSHOT,
        running = false,
        available = true,
    )

    override fun publishInto(broker: LiveFrameBroker, frame: LiveFrame) {
        broker.publish(frame)
    }

    fun adaptOneShot(
        broker: LiveFrameBroker,
        width: Int,
        height: Int,
        handle: String,
        nowEpochMs: Long,
        frameId: Long,
    ): LiveFrame {
        val frame = LiveFrame(
            sessionId = status.sessionId,
            displayId = status.displayId,
            frameId = frameId,
            capturedAtEpochMs = nowEpochMs,
            width = width,
            height = height,
            source = FrameSourceType.ACCESSIBILITY_SCREENSHOT,
            payloadHandle = handle,
        )
        publishInto(broker, frame)
        return frame
    }
}

interface LiveFrameBroker {
    fun publish(frame: LiveFrame)
    fun latest(sessionId: String): LiveFrame?
    fun latestAfter(sessionId: String, frameId: Long): LiveFrame?
    fun framesSince(sessionId: String, frameId: Long): List<LiveFrame>
    fun clear(sessionId: String)
    fun ageMs(sessionId: String, nowEpochMs: Long): Long?
}

class InMemoryLiveFrameBroker(
    private val capacityPerSession: Int = DEFAULT_CAPACITY,
) : LiveFrameBroker {
    private val lock = Any()
    private val buffers = mutableMapOf<String, ArrayDeque<LiveFrame>>()
    private val nextIds = mutableMapOf<String, Long>()
    var captureInvocations: Int = 0
        private set

    override fun publish(frame: LiveFrame) = synchronized(lock) {
        require(frame.sessionId.isNotBlank())
        require(frame.displayId >= 0)
        val expectedSessionDisplay = buffers[frame.sessionId]?.lastOrNull()?.displayId
        if (expectedSessionDisplay != null && expectedSessionDisplay != frame.displayId) {
            throw IllegalArgumentException("display/session mismatch")
        }
        val q = buffers.getOrPut(frame.sessionId) { ArrayDeque() }
        val next = (nextIds[frame.sessionId] ?: 0L) + 1L
        nextIds[frame.sessionId] = next
        val stored = if (frame.frameId <= 0L) frame.copy(frameId = next) else frame
        q.addLast(stored)
        while (q.size > capacityPerSession) q.removeFirst()
    }

    override fun latest(sessionId: String): LiveFrame? = synchronized(lock) {
        buffers[sessionId]?.lastOrNull()
    }

    override fun latestAfter(sessionId: String, frameId: Long): LiveFrame? = synchronized(lock) {
        buffers[sessionId]?.firstOrNull { it.frameId > frameId }
    }

    override fun framesSince(sessionId: String, frameId: Long): List<LiveFrame> = synchronized(lock) {
        buffers[sessionId]?.filter { it.frameId > frameId }.orEmpty()
    }

    override fun clear(sessionId: String) = synchronized(lock) {
        buffers.remove(sessionId)
        nextIds.remove(sessionId)
        Unit
    }

    override fun ageMs(sessionId: String, nowEpochMs: Long): Long? = synchronized(lock) {
        buffers[sessionId]?.lastOrNull()?.let { nowEpochMs - it.capturedAtEpochMs }
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
