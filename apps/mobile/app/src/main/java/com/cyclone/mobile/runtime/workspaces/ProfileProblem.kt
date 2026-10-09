package com.cyclone.mobile.runtime.workspaces

/**
 * Plan 57 W6/W9: what the profile error screen offers for each failure. Pure, so every failure kind is checked to have
 * its fix buttons and a debug file.
 */
object ProfileProblem {
    enum class Fix { RETRY, DELETE_REMOVED, CLEAN_UP, ALLOW_MORE, ANDROID_USERS, ROOT_MANAGER }

    /** The failures that are about room on the phone: the room panel is shown for them. */
    fun isRoomProblem(kind: ProfileSetupFailureKind?): Boolean = kind in setOf(
        ProfileSetupFailureKind.MAX_USERS_REACHED, ProfileSetupFailureKind.USER_TYPE_LIMIT,
        ProfileSetupFailureKind.USER_TYPE_DISABLED, ProfileSetupFailureKind.MAX_PROFILES_REACHED,
        ProfileSetupFailureKind.PROFILE_CREATION_REJECTED,
    )

    /** Fix buttons, in the order they are offered. The debug file is always offered as well. */
    fun fixes(kind: ProfileSetupFailureKind, room: ProfileCapacity.Room?, roomRefusal: String?): List<Fix> {
        val out = mutableListOf<Fix>()
        if (isRoomProblem(kind)) {
            if (room?.slots?.any { it.kind == ProfileCapacity.SlotKind.CYCLONE_REMOVED } == true) out += Fix.DELETE_REMOVED
            if (room?.slots?.any { it.kind == ProfileCapacity.SlotKind.CYCLONE_UNFINISHED } == true) out += Fix.CLEAN_UP
            if (roomRefusal == null && kind != ProfileSetupFailureKind.USER_TYPE_LIMIT && kind != ProfileSetupFailureKind.USER_TYPE_DISABLED) {
                out += Fix.ALLOW_MORE
            }
            out += Fix.ANDROID_USERS
        }
        if (kind in setOf(ProfileSetupFailureKind.ROOT_DENIED, ProfileSetupFailureKind.ROOT_UNAVAILABLE, ProfileSetupFailureKind.ROOT_COMMAND_FAILED)) {
            out += Fix.ROOT_MANAGER
        }
        if (ProfileFailureClassifier.describe(kind, null, null).retryUseful) out += Fix.RETRY
        return out.distinct()
    }

    /** What Cyclone checked, as ✓/✗ lines, for the top of the error screen. */
    fun checks(kind: ProfileSetupFailureKind, room: ProfileCapacity.Room?, rootManager: String?): List<Pair<Boolean, String>> {
        val rootOk = kind !in setOf(ProfileSetupFailureKind.ROOT_DENIED, ProfileSetupFailureKind.ROOT_UNAVAILABLE, ProfileSetupFailureKind.ROOT_COMMAND_FAILED)
        val lines = mutableListOf(rootOk to (if (rootOk) "Root works" + (rootManager?.let { " ($it)" } ?: "") else "Root isn't working"))
        room?.let { r ->
            val free = r.verdict == ProfileCapacity.Verdict.ROOM
            lines += free to "Places on the phone: ${r.used} of ${r.limit ?: "?"} in use"
            r.secondaryType?.maxAllowed?.takeIf { it >= 0 }?.let { max ->
                lines += (r.fullSecondary < max) to "This kind of user: ${r.fullSecondary} of $max allowed by the phone's system"
            }
            if (r.secondaryType?.enabled == false) lines += false to "This kind of user is switched off on this phone"
        }
        return lines
    }

    /** The confirm sheet's words for Allow more profiles. */
    fun allowText(limit: Int, used: Int, freeGb: Long?, manager: String?): String = buildString {
        append("Let this phone hold up to $limit profiles? It holds $used now. ")
        append("Cyclone sets Android's user limit and keeps it after restarts with a small ${manager?.let { managerName(it) } ?: "root"} module, \"Cyclone profiles\". ")
        append("Each profile uses storage")
        freeGb?.let { append("; you have $it GB free") }
        append(". You can undo it in Profiles → Profile room → Restore default; profiles you have stay.")
        if (freeGb != null && freeGb < 3) append(" Storage is low: new profiles may not fit.")
    }

    fun managerName(manager: String): String = when (manager) {
        "MAGISK" -> "Magisk"
        "KERNELSU" -> "KernelSU"
        "APATCH" -> "APatch"
        else -> manager
    }

    /** The first "Profile B", "Profile C", … not already used by another profile (Recently deleted included). */
    fun suggestName(taken: List<String>): String {
        val used = taken.map { it.trim().lowercase() }.toSet()
        ('B'..'Z').forEach { letter -> "Profile $letter".let { if (it.lowercase() !in used) return it } }
        var n = 27
        while ("profile $n" in used) n++
        return "Profile $n"
    }
}
