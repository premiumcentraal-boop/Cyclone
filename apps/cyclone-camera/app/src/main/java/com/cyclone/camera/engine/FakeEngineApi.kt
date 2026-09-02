package com.cyclone.camera.engine

import java.time.LocalTime
import java.time.format.DateTimeFormatter

class FakeEngineApi : EngineApi {
    private var status: EngineState = EngineState.OFF
    private var mode: CameraMode = CameraMode.OFF
    private var source: VideoSource? = null
    private var looping = true
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val logs = mutableListOf(
        LogEntry("09:41:02", LogLevel.INFO, "Frontend session started"),
        LogEntry("09:41:03", LogLevel.INFO, "Engine bridge available in demo mode"),
        LogEntry("09:41:04", LogLevel.WARN, "System component is not installed"),
        LogEntry(
            "09:41:05",
            LogLevel.ERROR,
            "No video source selected",
            "Choose a local file or apply a stream URL.",
        ),
    )

    override fun getStatus(): EngineState = status

    override fun setMode(mode: CameraMode) {
        this.mode = mode
        status = when {
            mode == CameraMode.OFF -> EngineState.OFF
            status == EngineState.ARMED || status == EngineState.INJECTING -> EngineState.INJECTING
            else -> status
        }
        append(LogLevel.INFO, "Camera mode set to ${mode.name}")
    }

    override fun setSource(source: VideoSource) {
        this.source = source
        if (status is EngineState.ERROR) status = EngineState.OFF
        append(LogLevel.INFO, "Source changed to ${source.label}")
    }

    override fun setLoop(enabled: Boolean) {
        looping = enabled
        append(LogLevel.INFO, "Loop video ${if (enabled) "enabled" else "disabled"}")
    }

    override fun arm(on: Boolean) {
        status = when {
            !on -> EngineState.OFF
            source == null -> EngineState.ERROR("no source selected")
            mode == CameraMode.OFF -> EngineState.ARMED
            else -> EngineState.INJECTING
        }
        append(LogLevel.INFO, if (on) "Output armed" else "Output disarmed")
    }

    override fun refreshIntegrity(): List<IntegrityResult> = listOf(
        IntegrityResult(IntegrityTier.BASIC, true),
        IntegrityResult(IntegrityTier.DEVICE, true),
        IntegrityResult(IntegrityTier.STRONG, false),
    )

    override fun runSetup(onComplete: (Boolean) -> Unit) {
        append(LogLevel.INFO, "Demo setup completed")
        onComplete(true)
    }

    override fun getLogs(): List<LogEntry> = logs.toList().asReversed()

    private fun append(level: LogLevel, message: String, hint: String? = null) {
        logs += LogEntry(LocalTime.now().format(timeFormat), level, message, hint)
    }
}
