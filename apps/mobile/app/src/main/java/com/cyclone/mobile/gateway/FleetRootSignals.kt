package com.cyclone.mobile.gateway

import com.cyclone.mobile.runtime.workspaces.RootStatus

/**
 * Alpha 107: whether this phone is rooted, for the PC's phone list. Read-only and silent: it only looks for the files a
 * root manager leaves behind and never runs `su`, which would make Magisk or KernelSU pop a grant prompt on every status
 * poll. A verified result from [com.cyclone.mobile.runtime.workspaces.RootProbe] (the owner's own root check) wins.
 */
object FleetRootSignals {
    data class Result(val rooted: Boolean, val verified: Boolean, val signals: List<String>)

    private val SU_DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin",
        "/data/local/xbin", "/data/local/bin", "/system/bin/.ext")
    private val MANAGERS = mapOf(
        "magisk" to listOf("/data/adb/magisk", "/sbin/.magisk", "/cache/.disable_magisk", "/dev/.magisk.unblock"),
        "kernelsu" to listOf("/data/adb/ksu", "/data/adb/ksud"),
        "apatch" to listOf("/data/adb/ap", "/data/adb/apd"),
        "superuser" to listOf("/system/app/Superuser.apk", "/system/app/SuperSU.apk"),
    )

    /** Pure: [exists] answers for a path, [path] is the process PATH, [tags] is Build.TAGS. */
    fun detect(exists: (String) -> Boolean, path: String?, tags: String?, probe: RootStatus): Result {
        val signals = mutableListOf<String>()
        val dirs = (SU_DIRS + path.orEmpty().split(':').filter { it.startsWith("/") }).distinct()
        if (dirs.any { exists("$it/su") }) signals += "su"
        for ((name, files) in MANAGERS) if (files.any(exists)) signals += name
        // A test-keys build is a custom or debug ROM, not root on its own: reported, never counted.
        val testKeys = tags.orEmpty().contains("test-keys")
        return when (probe) {
            RootStatus.ROOTED -> Result(true, true, signals + listOfNotNull("test-keys".takeIf { testKeys }))
            else -> Result(signals.isNotEmpty(), false, signals + listOfNotNull("test-keys".takeIf { testKeys }))
        }
    }
}
