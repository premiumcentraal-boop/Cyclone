package com.cyclone.mobile.runtime.background

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.ai.vision.live.FrameSourceType
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.InputOwner
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WorkspaceRuntime {
    private val lock = Any()
    private var appContext: Context? = null
    @Volatile private var backend: IWorkspaceService? = null
    private data class Entry(val session: ExecutionSession, val lifecycle: WorkspaceLifecycle,
        val reader: ImageReader, val thread: HandlerThread, var remoteGeneration: Long, val sourceRevision: Long)
    private val entries = mutableMapOf<String, Entry>()

    fun connect(context: Context) {
        if (backend?.asBinder()?.isBinderAlive == true) return
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "BACKGROUND_MODE_UNAVAILABLE: enable and authorize Shizuku first"
        }
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                backend = IWorkspaceService.Stub.asInterface(service)
                service?.linkToDeath({ backend = null; invalidateAll() }, 0)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { backend = null; invalidateAll() }
        }
        Shizuku.bindUserService(Shizuku.UserServiceArgs(ComponentName(context, WorkspaceUserService::class.java))
            .daemon(false).processNameSuffix("workspace").version(1), connection)
        check(ready.await(8, TimeUnit.SECONDS) && backend != null) { "BACKGROUND_MODE_UNAVAILABLE: workspace service did not connect" }
    }

    fun create(context: Context, packageName: String): ExecutionSession = synchronized(lock) {
        appContext = context.applicationContext
        connect(context)
        require(packageName != context.packageName && packageName != DeviceState.currentPackage) { "Choose an app that is not on your main screen" }
        val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
            ?: error("No launchable app for that package")
        val id = "workspace-${UUID.randomUUID()}"
        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("cyclone-workspace-frames").apply { start() }
        try {
            val created = checked(backend!!.create(id, reader.surface, 720, 1280, 240))
            val displayId = created.getInt("displayId", -1)
            require(displayId > 0)
            val launched = checked(backend!!.launch(id, component))
            val session = LiveVisionRuntime.sessions.registerOwned(id, displayId, packageName)
            val revision = LiveVisionRuntime.startSource(id, displayId, FrameSourceType.VIRTUAL_DISPLAY_SURFACE)
            val lifecycle = WorkspaceLifecycle(id, displayId).apply { transition(WorkspaceState.BACKGROUND_OK) }
            entries[id] = Entry(session, lifecycle, reader, thread, launched.getLong("generation"), revision)
            reader.setOnImageAvailableListener({ source ->
                runCatching {
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes[0]
                        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        val bitmap = try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        } catch (error: Throwable) { padded.recycle(); throw error }
                        if (bitmap !== padded) padded.recycle()
                        LiveVisionRuntime.publish(id, displayId, FrameSourceType.VIRTUAL_DISPLAY_SURFACE, revision,
                            image.timestamp / 1_000_000, bitmap)
                    }
                }
            }, Handler(thread.looper))
            session
        } catch (error: Exception) {
            runCatching { backend?.close(id) }; reader.close(); thread.quitSafely()
            entries.remove(id)
            if (LiveVisionRuntime.sessions.snapshot().any { it.sessionId == id }) {
                LiveVisionRuntime.stopSource(id); LiveVisionRuntime.sessions.remove(id)
            }
            throw error
        }
    }

    fun requireScope(scope: ExecutionContext): ExecutionSession = synchronized(lock) {
        val entry = entries[scope.sessionId] ?: error("STALE_SESSION: workspace is unavailable")
        check(entry.session.displayId == scope.displayId && scope.displayId > 0)
        checked(backend?.status(scope.sessionId) ?: error("BACKEND_DISCONNECTED"))
        LiveVisionRuntime.sessions.requireSessionDisplay(scope.sessionId, scope.displayId)
    }

    fun observe(scope: ExecutionContext): UiSnapshot = synchronized(lock) {
        val session = requireScope(scope)
        val service = CycloneAccessibilityService.instance ?: error("ACCESSIBILITY_NOT_CONNECTED")
        val snapshot = service.observeDisplay(session.displayId, session.targetPackage.orEmpty())
        check(snapshot.packageName == session.targetPackage) { "FOREGROUND_REQUIRED: target is not observable on its display" }
        snapshot
    }

    fun generation(sessionId: String): Long = synchronized(lock) { entries.getValue(sessionId).lifecycle.generation }
    fun ownsInput(sessionId: String): Boolean = synchronized(lock) {
        entries[sessionId]?.lifecycle?.let { it.owner == InputOwner.CYCLONE && it.state == WorkspaceState.BACKGROUND_OK } == true && backend != null
    }

    fun input(scope: ExecutionContext, generation: Long, kind: Int, coordinates: FloatArray = floatArrayOf(), text: String = ""): Bundle = synchronized(lock) {
        val entry = entries[scope.sessionId] ?: error("STALE_SESSION")
        requireScope(scope)
        entry.lifecycle.requireMutation(WorkspaceLease(scope.sessionId, scope.displayId, generation), true, backend != null)
        val keyguard = appContext?.getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked != false) {
            pause(scope.sessionId)
            error("SCREEN_LOCKED: unlock and resume the task")
        }
        check(LiveVisionRuntime.healthy(scope.sessionId)) { "FRAME_STREAM_STALLED: no fresh workspace vision" }
        try { checked(backend!!.input(scope.sessionId, entry.remoteGeneration, kind, coordinates, text)) }
        finally { LiveVisionRuntime.mutationFinished(scope.sessionId) }
    }

    fun pause(sessionId: String, state: WorkspaceState = WorkspaceState.PAUSED) = synchronized(lock) {
        val entry = entries.getValue(sessionId)
        // Invalidate locally before waiting for the remote in-flight operation to finish.
        entry.lifecycle.transition(state)
        LiveVisionRuntime.sessions.setOwner(sessionId, InputOwner.HUMAN)
        com.cyclone.mobile.gateway.GatewayObservationStore.clear(sessionId)
        entry.remoteGeneration = checked(backend?.revoke(sessionId) ?: error("BACKEND_DISCONNECTED")).getLong("generation")
    }

    fun resume(sessionId: String) = synchronized(lock) {
        val entry = entries.getValue(sessionId)
        check(entry.lifecycle.state == WorkspaceState.PAUSED)
        entry.remoteGeneration = checked(backend?.resume(sessionId) ?: error("BACKEND_DISCONNECTED")).getLong("generation")
        entry.lifecycle.transition(WorkspaceState.BACKGROUND_OK)
        LiveVisionRuntime.sessions.setOwner(sessionId, InputOwner.CYCLONE)
        com.cyclone.mobile.gateway.GatewayObservationStore.clear(sessionId)
    }

    fun handoff(sessionId: String) = synchronized(lock) {
        pause(sessionId, WorkspaceState.WAITING_FOR_CONFIRMATION)
        checked(backend?.handoff(sessionId) ?: error("BACKEND_DISCONNECTED"))
        LiveVisionRuntime.stopSource(sessionId)
        // Session keeps its nonzero identity and HUMAN owner. It never becomes default-foreground.
    }

    fun close(sessionId: String, state: WorkspaceState = WorkspaceState.CANCELLED) = synchronized(lock) {
        val entry = entries.remove(sessionId) ?: return@synchronized
        runCatching { entry.lifecycle.transition(state) }
        LiveVisionRuntime.sessions.setOwner(sessionId, InputOwner.HUMAN)
        runCatching { backend?.revoke(sessionId) }
        runCatching { backend?.close(sessionId) }
        LiveVisionRuntime.stopSource(sessionId)
        com.cyclone.mobile.gateway.GatewayObservationStore.clear(sessionId)
        entry.reader.close(); entry.thread.quitSafely()
        LiveVisionRuntime.sessions.remove(sessionId)
    }

    fun invalidateAll() = synchronized(lock) {
        entries.keys.toList().forEach { close(it, WorkspaceState.FAILED) }
    }
    private fun checked(value: Bundle): Bundle {
        check(value.getBoolean("ok")) { value.getString("error") ?: "BACKGROUND_MODE_UNAVAILABLE" }
        return value
    }
}
