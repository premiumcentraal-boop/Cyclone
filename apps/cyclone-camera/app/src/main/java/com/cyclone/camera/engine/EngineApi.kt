package com.cyclone.camera.engine

sealed interface EngineState {
    data object OFF : EngineState
    data object ARMED : EngineState
    data object INJECTING : EngineState
    data class ERROR(val reason: String) : EngineState
}

enum class CameraMode { OFF, FRONT, BACK }
enum class SourceType { FILE, STREAM }
enum class IntegrityTier { BASIC, DEVICE, STRONG }
enum class LogLevel { INFO, WARN, ERROR }

data class VideoSource(
    val type: SourceType,
    val uri: String?,
    val url: String?,
    val label: String,
    val resolution: String,
    val fps: Int,
)

data class IntegrityResult(val tier: IntegrityTier, val passed: Boolean)

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val hint: String? = null,
)

data class EngineSettings(
    val resolutionOverride: String? = null,
    val autoDisarmOnLock: Boolean = false,
    val hideAppIcon: Boolean = false,
    val sensorLock: Boolean = false,
    val jitterInjection: Boolean = false,
)

/**
 * Frontend boundary supplied by the future rooted camera engine.
 * Default bodies make missing native wiring fail explicitly while the standalone app uses FakeEngineApi.
 */
interface EngineApi {
    fun getStatus(): EngineState = TODO("Provided by the rooted camera engine")
    fun setMode(mode: CameraMode): Unit = TODO("Provided by the rooted camera engine")
    fun setSource(source: VideoSource): Unit = TODO("Provided by the rooted camera engine")
    fun setLoop(enabled: Boolean): Unit = TODO("Provided by the rooted camera engine")
    fun arm(on: Boolean): Unit = TODO("Provided by the rooted camera engine")
    fun refreshIntegrity(): List<IntegrityResult> = TODO("Provided by the rooted camera engine")
    fun runSetup(onComplete: (Boolean) -> Unit): Unit = TODO("Provided by the rooted camera engine")
    fun getLogs(): List<LogEntry> = TODO("Provided by the rooted camera engine")
}
