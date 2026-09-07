package com.cyclone.mobile.runtime.workspace

import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

enum class RootStatus(val label: String) {
    ROOTED("Rooted"),
    NOT_ROOTED("Not rooted"),
    UNKNOWN("Unknown"),
}

data class RootSignals(
    val suOnPath: Boolean? = null,
    val commonSuPath: Boolean? = null,
    val magiskIndicator: Boolean? = null,
    val testKeys: Boolean? = null,
)

object RootStatusMapper {
    fun map(signals: RootSignals): RootStatus {
        val values = listOf(signals.suOnPath, signals.commonSuPath, signals.magiskIndicator, signals.testKeys)
        if (values.any { it == true }) return RootStatus.ROOTED
        return if (values.all { it == false }) RootStatus.NOT_ROOTED else RootStatus.UNKNOWN
    }
}

object AndroidRootDetector {
    fun detect(): RootStatus = RootStatusMapper.map(signals())

    fun signals(): RootSignals = RootSignals(
        suOnPath = runCatching {
            val process = ProcessBuilder("sh", "-c", "command -v su >/dev/null 2>&1").start()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroy()
                null
            } else process.exitValue() == 0
        }.getOrNull(),
        commonSuPath = runCatching {
            listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su").any { File(it).exists() }
        }.getOrNull(),
        magiskIndicator = runCatching {
            listOf("/data/adb/magisk", "/sbin/.magisk", "/debug_ramdisk/.magisk").any { File(it).exists() }
        }.getOrNull(),
        testKeys = runCatching { Build.TAGS?.contains("test-keys") ?: false }.getOrNull(),
    )
}

enum class RootWizardKind {
    CHECK_ROOT,
    MULTI_PROFILE_ISOLATION,
    REGISTER_WORKSPACES,
    TEST_SWITCH,
    MUTATE_LOCK,
}

data class RootWizardState(
    val kind: RootWizardKind,
    val step: Int = 0,
    val totalSteps: Int,
    val done: Boolean = false,
) {
    init {
        require(totalSteps > 0)
        require(step in 0 until totalSteps)
    }

    fun advance(): RootWizardState = if (step + 1 >= totalSteps) copy(done = true)
        else copy(step = step + 1)
}

object RootWizardFlow {
    fun start(kind: RootWizardKind): RootWizardState = RootWizardState(
        kind = kind,
        totalSteps = when (kind) {
            RootWizardKind.CHECK_ROOT -> 2
            RootWizardKind.MULTI_PROFILE_ISOLATION -> 3
            RootWizardKind.REGISTER_WORKSPACES -> 2
            RootWizardKind.TEST_SWITCH -> 3
            RootWizardKind.MUTATE_LOCK -> 2
        },
    )
}
