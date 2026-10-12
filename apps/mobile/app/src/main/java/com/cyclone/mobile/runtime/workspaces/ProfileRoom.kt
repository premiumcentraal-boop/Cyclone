package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import com.cyclone.mobile.runtime.workspaces.ProfileSetupPlan.RootManager
import java.io.File

/**
 * Plan 57 §5: profile room on rooted phones. Android's total user limit is the system property `fw.max_users`
 * (`UserManager.getMaxSupportedUsers()` reads it on every call). With the owner's yes, Cyclone raises it with the root
 * manager's own `resetprop` and keeps it after restarts with one small module, `/data/adb/modules/cyclone_profiles`.
 *
 * One property and one module folder, nothing else; every change is read back; Restore default puts it back exactly.
 * It never helps against a per-type limit (that is not this property), and it is never offered then.
 */
object ProfileRoom {
    const val MODULE_ID = "cyclone_profiles"
    // How many profiles the owner can choose to allow. 20 is the recommended default — plenty of room without much
    // storage pressure; 50 and 100 are for a high-storage phone that manages many things at once.
    val CHOICES = listOf(10, 20, 50, 100)
    const val DEFAULT_CHOICE = 20

    data class Status(
        val manager: RootManager?,
        /** `getprop fw.max_users`, or null when unset (Android's own default applies). */
        val property: Int?,
        /** What Android uses now (`pm get-max-users`). */
        val androidLimit: Int?,
        /** The module's limit, or null when there is no Cyclone profiles module. */
        val moduleLimit: Int?,
        val moduleDisabled: Boolean,
        /** Cyclone raised it (and recorded the original to restore). */
        val raisedByCyclone: Boolean,
        /** The module carries the per-type-cap unlock, which only takes effect after the phone restarts. */
        val unlockTypeCap: Boolean = false,
        /** The unlock is written but not yet live (the phone hasn't restarted since). */
        val restartNeeded: Boolean = false,
    ) {
        /** After a restart the module didn't apply (or it was switched off): offer to apply again. */
        val needsApplyAgain: Boolean get() = moduleLimit != null && !moduleDisabled && property != moduleLimit
    }

    // Pure ---------------------------------------------------------------------------------------------------------

    /** The root manager from the folders in `/data/adb` (Magisk first, then KernelSU, then APatch). */
    fun detect(listing: String): RootManager? {
        val names = listing.lineSequence().map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.toSet()
        return when {
            "magisk" in names -> RootManager.MAGISK
            "ksu" in names -> RootManager.KERNELSU
            "ap" in names -> RootManager.APATCH
            else -> null
        }
    }

    /** The limits the owner can pick: above what is in use now, and within the allowed range. */
    fun choices(used: Int): List<Int> = CHOICES.filter { it > used && it in ProfileSetupPlan.ROOM_LIMITS }

    fun defaultChoice(used: Int): Int? = choices(used).let { options -> if (DEFAULT_CHOICE in options) DEFAULT_CHOICE else options.firstOrNull() }

    fun moduleProp(cycloneVersion: String, limit: Int, unlockTypeCap: Boolean = false): String {
        require(limit in ProfileSetupPlan.ROOM_LIMITS)
        val version = cycloneVersion.filter { it.isLetterOrDigit() || it in ".-" }.take(40).ifBlank { "1" }
        val extra = if (unlockTypeCap) " It also unlocks this phone's cap on extra users of this kind (takes effect after a restart); this makes the device debuggable while it is installed." else ""
        return "id=$MODULE_ID\n" +
            "name=Cyclone profiles\n" +
            "version=$version\n" +
            "versionCode=1\n" +
            "author=Cyclone\n" +
            "description=Lets this phone hold up to $limit profiles (Android's user limit, fw.max_users).$extra Added by Cyclone at the owner's request; remove it in Cyclone with Restore default.\n"
    }

    /**
     * The module's `system.prop`. Always carries `fw.max_users`. When [unlockTypeCap] is set (the phone also caps
     * extra users of this kind), it also carries the two properties that turn on Android's own creation override,
     * which the root manager applies at boot; nothing here runs through Cyclone's `resetprop`.
     */
    fun systemProp(limit: Int, unlockTypeCap: Boolean = false): String {
        require(limit in ProfileSetupPlan.ROOM_LIMITS)
        return buildString {
            append("${ProfileSetupPlan.ROOM_PROPERTY}=$limit\n")
            if (unlockTypeCap) {
                append("${ProfileSetupPlan.ROOM_DEBUGGABLE_PROPERTY}=1\n")
                append("${ProfileSetupPlan.ROOM_OVERRIDE_PROPERTY}=1\n")
            }
        }
    }

    fun readNumber(output: String?): Int? = output?.trim()?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull()

    fun readModuleLimit(systemProp: String?): Int? = systemProp?.lineSequence()
        ?.map { it.trim() }?.firstOrNull { it.startsWith(ProfileSetupPlan.ROOM_PROPERTY + "=") }
        ?.substringAfter('=')?.trim()?.toIntOrNull()

    /** Whether the module's `system.prop` carries the creation override (the per-type-cap unlock). */
    fun readModuleUnlock(systemProp: String?): Boolean = systemProp?.lineSequence()
        ?.map { it.trim() }?.any { it == "${ProfileSetupPlan.ROOM_OVERRIDE_PROPERTY}=1" } == true

    /**
     * Whether raising the limit on this phone also needs the per-type-cap unlock: the phone caps extra users of this
     * kind (a finite maximum below the top of the range, or the type switched off). `fw.max_users` alone can't clear
     * that, so the module must also carry the creation override.
     */
    fun needsUnlock(room: ProfileCapacity.Room): Boolean =
        room.secondaryType?.let { it.enabled == false || (it.maxAllowed ?: -1) in 0 until ProfileSetupPlan.ROOM_LIMITS.last } == true

    /** Why Allow can't be offered, or null when it can. The creation override now clears a per-type cap too. */
    fun refusal(room: ProfileCapacity.Room, manager: RootManager?): String? = when {
        manager == null -> "Making room needs Magisk, KernelSU or APatch."
        choices(room.used).isEmpty() -> "This phone already uses ${room.used} places; Cyclone allows up to ${ProfileSetupPlan.ROOM_LIMITS.last}."
        else -> null
    }

    // On the phone ---------------------------------------------------------------------------------------------------

    private const val PREFS = "cyclone_profile_room"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Plan 57 P3: the limit Cyclone last set and whether it raised it, from its own notes. Runs no command. */
    fun cached(context: Context): Pair<Int, Boolean>? = prefs(context).let { p ->
        p.getInt("limit", -1).takeIf { it > 0 }?.let { it to p.getBoolean("raised", false) }
    }

    fun status(context: Context): Status {
        val manager = runCatching { detect(ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomListAdb())) }.getOrNull()
        val property = readNumber(ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomReadProp()))
        val android = ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.getMaxUsers())?.let(ProfileSetupParser::maxUsers)
        val listing = ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomListModule())
        val systemProp = listing?.let { ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomReadModule("system.prop")) }
        val moduleLimit = listing?.let { readModuleLimit(systemProp) }
        val unlock = readModuleUnlock(systemProp)
        val disabled = listing?.lineSequence()?.any { it.trim() == "disable" || it.trim() == "remove" } == true
        return Status(manager, property, android, moduleLimit, disabled, prefs(context).getBoolean("raised", false), unlockTypeCap = unlock)
    }

    /** Raises the limit to [limit] after the owner's confirm, keeps it after restarts, reads both back. */
    fun allow(context: Context, limit: Int, onProgress: (String) -> Unit = {}): Status =
        synchronized(Layer2Workspaces.engine.mutationLock) {
            require(limit in ProfileSetupPlan.ROOM_LIMITS)
            guard()
            onProgress("Checking this phone's root manager…")
            val manager = detect(ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomListAdb()))
            val room = ProfileSetupRuntime.room(context)
            refusal(room, manager)?.let { error(it) }
            check(limit > room.used) { "Pick a number above the ${room.used} places already in use." }
            manager!!
            // Whether this phone also caps extra users of this kind; if so the module carries the creation override.
            val unlock = needsUnlock(room)
            val store = prefs(context)
            if (!store.getBoolean("raised", false)) {
                // Recorded once, so Restore default goes back to how the phone was before Cyclone changed it.
                val original = readNumber(ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomReadProp()))
                check(store.edit().putBoolean("raised", true).putInt("original", original ?: -1).putString("manager", manager.name).commit()) {
                    "Couldn't save the original limit, so nothing was changed."
                }
            }
            onProgress("Setting Android's user limit to $limit…")
            ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomSetProp(manager, limit))
            val property = readNumber(ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomReadProp()))
            val android = ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.getMaxUsers())?.let(ProfileSetupParser::maxUsers)
            check(property == limit && android == limit) {
                "Root didn't apply the new limit (Android reports ${android ?: "nothing"}). Nothing else was changed."
            }
            onProgress(if (unlock) "Keeping it after restarts, and unlocking the per-type cap…" else "Keeping it after restarts…")
            writeModule(context, manager, limit, unlock)
            store.edit().putInt("limit", limit).putString("manager", manager.name).apply()
            // The override only takes effect once the phone restarts (system_server reads the debuggable flag at boot).
            status(context).copy(restartNeeded = unlock)
        }

    /** Puts the phone back as it was before Cyclone raised its limit. Profiles that exist stay. */
    fun restoreDefault(context: Context): Status = synchronized(Layer2Workspaces.engine.mutationLock) {
        guard()
        val store = prefs(context)
        val manager = detect(ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomListAdb()))
            ?: error("Restoring needs the same root manager (Magisk, KernelSU or APatch).")
        // Whether the module we are about to remove carried the per-type-cap unlock (the debuggable flag); if so a
        // restart is needed to turn it back off, so the owner is told.
        val hadUnlock = readModuleUnlock(ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomReadModule("system.prop")))
        val original = store.getInt("original", -1).takeIf { it > 0 }
        ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomClearModule(staging = true))
        ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomClearModule(staging = false))
        ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomResetProp(manager, original))
        check(ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomListModule()) == null) { "The Cyclone profiles module is still there." }
        check(readNumber(ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomReadProp())) == original) {
            "Root didn't put the limit back. Save the debug file to see why."
        }
        check(store.edit().clear().commit()) { "Couldn't save the restored state." }
        // unlockTypeCap carries whether the removed module had the flag, so the UI can tell the owner a restart turns
        // the debuggable flag back off.
        status(context).copy(unlockTypeCap = hadUnlock, restartNeeded = hadUnlock)
    }

    /** Writes the module atomically: Cyclone's own folder, then root copies it to staging, owns, labels and places it. */
    private fun writeModule(context: Context, manager: RootManager, limit: Int, unlockTypeCap: Boolean) {
        val source = File(context.filesDir, "profile-room/$MODULE_ID")
        source.deleteRecursively()
        check(source.mkdirs()) { "Couldn't prepare the module files." }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val moduleProp = moduleProp(version, limit, unlockTypeCap)
        val systemProp = systemProp(limit, unlockTypeCap)
        File(source, "module.prop").writeText(moduleProp)
        File(source, "system.prop").writeText(systemProp)
        val path = source.absolutePath
        check(ProfileSetupPlan.validRoomSource(path)) { "Cyclone's own folder isn't where it should be; nothing was changed." }
        try {
            ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomClearModule(staging = true))
            ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomStageModule(path))
            ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomOwnModule())
            ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomLabelModule())
            ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomClearModule(staging = false))
            ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomPlaceModule())
            val readProp = ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomReadModule("system.prop"))
            val readModule = ProfileSetupRuntime.runRequired(ProfileSetupPlan.roomReadModule("module.prop"))
            check(readProp.trimEnd() == systemProp.trimEnd() && readModule.trimEnd() == moduleProp.trimEnd()) {
                "The module didn't read back as written."
            }
        } catch (error: Exception) {
            // The limit is set for now; without a correct module it would silently vanish at the next restart.
            ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomClearModule(staging = true))
            ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.roomClearModule(staging = false))
            error("The new limit works until the next restart, but Cyclone couldn't save it for after a restart: ${error.message}")
        } finally {
            source.deleteRecursively()
        }
    }

    private fun guard() {
        check(!ProfileSetupRuntime.state.value.busy) { "Wait for profile setup to finish." }
        check(!Layer2Workspaces.gated() && !com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.hasExecutingTask() &&
            !com.cyclone.mobile.runtime.background.WorkspaceTasks.hasCurrentTask()) { "Finish the current task first." }
        ProfileSetupRuntime.runRequired(ProfileSetupPlan.verifyRoot())
        val users = ProfileSetupRuntime.listUsersRequired()
        val main = ProfileSetupParser.mainUserId(users)
        val current = ProfileSetupParser.currentUserId(ProfileSetupRuntime.runRequired(ProfileSetupPlan.currentUser()))
        check(main != null && current == main) { "Open the main profile to change how many profiles this phone holds." }
    }
}
