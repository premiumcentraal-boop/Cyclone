package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.pm.PackageManager

/** Read-only package metadata. Never use the setup registry as evidence that an APK is still installed. */
object ProfilePackageInventory {
    const val MAX_OUTPUT_CHARS = 1_048_576
    data class App(val packageName: String, val label: String, val androidUserId: Int, val profileId: String,
        val installed: Boolean = true, val system: Boolean = false, val bundled: Boolean = false)

    fun parse(output: String): Set<String> {
        require(output.length <= MAX_OUTPUT_CHARS) { "Incomplete package inventory" }
        return output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            require(line.startsWith("package:")) { "Incomplete package inventory" }
            line.removePrefix("package:").also { pkg ->
                require(pkg == "android" || ProfileSetupPlan.validPackageName(pkg)) { "Invalid package inventory" }
            }
        }.toSet()
    }

    fun build(profileId: String, user: Int, installed: Set<String>, system: Set<String>, bundled: Set<String>,
        label: (String) -> String): List<App> {
        require(user >= 0)
        return installed.sorted().map { App(it, label(it).take(80), user, profileId,
            system = it in system, bundled = it in bundled) }
    }

    internal fun collect(profileId: String, user: Int, bundled: Set<String>, run: (ProfileSetupCommand) -> String,
        label: (String) -> String): List<App> {
        val installed = parse(run(ProfileSetupPlan.listProfileInventory(user)))
        val system = parse(run(ProfileSetupPlan.listProfileInventory(user, systemOnly = true)))
        return build(profileId, user, installed, system, bundled, label)
    }

    /**
     * Registry packages are user-selected workspaces, not a complete inventory: Essentials were intentionally
     * subtracted during setup. `pm ... -3` is also unsuitable: it hides system apps. This read has neither filter,
     * no installer/launcher/role allowlist, and no `-u` (uninstalled APKs must never be advertised as installed).
     * A failed cross-user read fails the call; it never silently substitutes Main's apps or old registry entries.
     */
    fun read(context: Context, profileId: String): List<App> {
        val record = if (profileId == "owner") null else (ProfileRegistryStore.records(context).singleOrNull { it.id == profileId }
            ?: throw IllegalStateException("Profile not found"))
        if (record != null && record.androidUserId == null) return emptyList()
        val user = record?.androidUserId ?: ProfileSetupRuntime.profileAUserId()
        val bundled = ProfileRequiredPackages.supportAllowlist + context.packageName +
            listOfNotNull(ProfileCornerstones.cloakPackage(context)) +
            (record?.let { ProfileInventoryStore.get(context, it.id)?.apps?.map { app -> app.packageName } }.orEmpty())
        val pm = context.packageManager
        if (record != null) {
            val actual = ProfileSetupRuntime.listUsersRequired().singleOrNull { it.id == user && it.name == record.id }
            check(actual != null && ProfileRecovery.validOwned(actual, record.parentUserId, record.secondaryUser)) {
                "Android profile identity is no longer valid"
            }
        }
        // Use the same user-scoped authority even for Main. PackageManager's caller-visibility filters hide
        // non-launcher packages from Cyclone itself, so getInstalledApplications(0) would repeat the omission.
        return collect(profileId, user, bundled, { ProfileSetupRuntime.runRequired(it) }) { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString() }.getOrDefault(pkg)
        }
    }
}
