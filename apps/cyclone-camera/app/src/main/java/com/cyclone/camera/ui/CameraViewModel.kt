package com.cyclone.camera.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cyclone.camera.data.EngineSettingsStore
import com.cyclone.camera.engine.CameraMode
import com.cyclone.camera.engine.EngineApi
import com.cyclone.camera.engine.EngineSettings
import com.cyclone.camera.engine.EngineState
import com.cyclone.camera.engine.FakeEngineApi
import com.cyclone.camera.engine.IntegrityResult
import com.cyclone.camera.engine.LogEntry
import com.cyclone.camera.engine.LogLevel
import com.cyclone.camera.engine.SourceType
import com.cyclone.camera.engine.VideoSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainTab { HOME, SYSTEM, LOGS }
enum class LogFilter { ALL, INFO, WARN, ERROR }

data class CameraUiState(
    val tab: MainTab = MainTab.HOME,
    val settingsOpen: Boolean = false,
    val engineState: EngineState = EngineState.OFF,
    val mode: CameraMode = CameraMode.OFF,
    val source: VideoSource? = null,
    val loopVideo: Boolean = true,
    val streamExpanded: Boolean = false,
    val streamUrl: String = "",
    val streamUrlInvalid: Boolean = false,
    val integrity: List<IntegrityResult> = emptyList(),
    val setupRunning: Boolean = false,
    val setupComplete: Boolean = false,
    val rebootReady: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val logFilter: LogFilter = LogFilter.ALL,
    val engineSettings: EngineSettings = EngineSettings(),
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val engine: EngineApi = FakeEngineApi()
    private val settingsStore = EngineSettingsStore(application)
    private val _uiState = MutableStateFlow(
        CameraUiState(
            integrity = engine.refreshIntegrity(),
            logs = engine.getLogs(),
        ),
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings -> _uiState.update { it.copy(engineSettings = settings) } }
        }
    }

    fun selectTab(tab: MainTab) = _uiState.update { it.copy(tab = tab, settingsOpen = false) }
    fun openSettings() = _uiState.update { it.copy(settingsOpen = true) }
    fun closeSettings() = _uiState.update { it.copy(settingsOpen = false) }

    fun setMode(mode: CameraMode) {
        engine.setMode(mode)
        syncEngine { it.copy(mode = mode) }
    }

    fun selectFile(uri: Uri) {
        val label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "Selected video"
        val source = VideoSource(SourceType.FILE, uri.toString(), null, label, "1920×1080", 30)
        engine.setSource(source)
        syncEngine { it.copy(source = source, streamExpanded = false, streamUrlInvalid = false) }
    }

    fun toggleStream() = _uiState.update { it.copy(streamExpanded = !it.streamExpanded, streamUrlInvalid = false) }
    fun setStreamUrl(value: String) = _uiState.update { it.copy(streamUrl = value, streamUrlInvalid = false) }

    fun applyStream() {
        val value = _uiState.value.streamUrl.trim()
        if (!isValidStreamUrl(value)) {
            _uiState.update { it.copy(streamUrlInvalid = true) }
            return
        }
        val source = VideoSource(SourceType.STREAM, null, value, value.substringAfter("://"), "Adaptive", 30)
        engine.setSource(source)
        syncEngine { it.copy(source = source, streamExpanded = false, streamUrlInvalid = false) }
    }

    fun toggleLoop() {
        val next = !_uiState.value.loopVideo
        engine.setLoop(next)
        syncEngine { it.copy(loopVideo = next) }
    }

    fun toggleArm() {
        val current = _uiState.value
        val shouldDisarm = current.engineState != EngineState.OFF
        if (!shouldDisarm && current.source == null) {
            _uiState.update { it.copy(engineState = EngineState.ERROR("no source selected")) }
            return
        }
        engine.arm(!shouldDisarm)
        syncEngine()
    }

    fun quickOff() {
        engine.arm(false)
        syncEngine()
    }

    fun refreshIntegrity() = _uiState.update { it.copy(integrity = engine.refreshIntegrity(), logs = engine.getLogs()) }

    fun runSetup() {
        _uiState.update { it.copy(setupRunning = true) }
        viewModelScope.launch {
            delay(650)
            engine.runSetup { success ->
                _uiState.update {
                    it.copy(
                        setupRunning = false,
                        setupComplete = success,
                        rebootReady = success,
                        logs = engine.getLogs(),
                    )
                }
            }
        }
    }

    fun setLogFilter(filter: LogFilter) = _uiState.update { it.copy(logFilter = filter) }
    fun refreshLogs() = _uiState.update { it.copy(logs = engine.getLogs()) }

    fun setResolution(value: String?) = viewModelScope.launch { settingsStore.setResolution(value) }
    fun setAutoDisarm(value: Boolean) = viewModelScope.launch { settingsStore.setAutoDisarm(value) }
    fun setHideIcon(value: Boolean) = viewModelScope.launch { settingsStore.setHideIcon(value) }
    fun setSensorLock(value: Boolean) = viewModelScope.launch { settingsStore.setSensorLock(value) }
    fun setJitter(value: Boolean) = viewModelScope.launch { settingsStore.setJitter(value) }

    fun errorHint(): String? = _uiState.value.logs.firstOrNull { it.level == LogLevel.ERROR }?.hint

    private fun syncEngine(transform: (CameraUiState) -> CameraUiState = { it }) {
        _uiState.update { current ->
            transform(current).copy(engineState = engine.getStatus(), logs = engine.getLogs())
        }
    }
}

fun isValidStreamUrl(value: String): Boolean =
    Regex("^(rtmp|rtmps|rtsp)://[^\\s]+$", RegexOption.IGNORE_CASE).matches(value)
