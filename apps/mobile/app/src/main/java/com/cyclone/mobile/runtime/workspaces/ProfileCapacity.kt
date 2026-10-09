package com.cyclone.mobile.runtime.workspaces

/**
 * Plan 57 W3: how much room the phone has for another profile, counted the way Android counts it, and which limit is
 * in the way. Pure, so every rule is tested without a phone.
 *
 * Android has two limits (AOSP `UserManagerService`):
 * - **the total**: every alive user except guests, profiles included (work profile, Private Space, clones, unfinished
 *   users), against `getMaxSupportedUsers()` = `fw.max_users` or `config_multiuserMaximumUsers`;
 * - **per user type**: the type's own `maxAllowed` (full secondary users are unlimited on stock Android, a ROM can cap
 *   them) or the type switched off.
 */
object ProfileCapacity {
    const val FULL_SECONDARY = "android.os.usertype.full.SECONDARY"

    enum class SlotKind { MAIN, CYCLONE, CYCLONE_REMOVED, CYCLONE_UNFINISHED, WORK, PRIVATE, CLONE, OTHER_USER, OTHER_PROFILE, GUEST }

    data class Slot(val userId: Int, val label: String, val kind: SlotKind, val counted: Boolean)

    data class TypeLimit(val type: String, val enabled: Boolean?, val maxAllowed: Int?)

    enum class Verdict { ROOM, TOTAL_LIMIT, TYPE_LIMIT, TYPE_DISABLED, UNKNOWN }

    data class Room(
        val limit: Int?,
        val used: Int,
        val slots: List<Slot>,
        val fullSecondary: Int,
        val secondaryType: TypeLimit?,
        val verdict: Verdict,
    ) {
        val free: Int? get() = limit?.let { (it - used).coerceAtLeast(0) }
        /** "Main · Profile B · Work profile · Old test (Recently deleted, still uses a place)". */
        fun line(): String = slots.filter { it.counted }.joinToString(" · ") { slot ->
            when (slot.kind) {
                SlotKind.CYCLONE_REMOVED -> "${slot.label} (Recently deleted, still uses a place)"
                SlotKind.CYCLONE_UNFINISHED -> "${slot.label} (unfinished)"
                else -> slot.label
            }
        }
    }

    fun room(
        users: List<ProfileUserRecord>,
        maxUsers: Int?,
        records: List<CycloneProfileRecord>,
        typeLimits: List<TypeLimit> = emptyList(),
    ): Room {
        val main = ProfileSetupParser.mainUserId(users)
        val slots = users.sortedBy { it.id }.map { user -> slot(user, main, records) }
        val used = slots.count { it.counted }
        val fullSecondary = users.count { it.userType.endsWith("full.SECONDARY", ignoreCase = true) }
        val secondaryType = typeLimits.firstOrNull { it.type.equals(FULL_SECONDARY, ignoreCase = true) }
        val verdict = when {
            secondaryType?.enabled == false -> Verdict.TYPE_DISABLED
            secondaryType?.maxAllowed?.let { it >= 0 && fullSecondary >= it } == true -> Verdict.TYPE_LIMIT
            maxUsers != null && used >= maxUsers -> Verdict.TOTAL_LIMIT
            maxUsers == null -> Verdict.UNKNOWN
            else -> Verdict.ROOM
        }
        return Room(maxUsers, used, slots, fullSecondary, secondaryType, verdict)
    }

    /** What Android's refusal of a create means, using its words first and the count second. */
    fun verdictFor(kind: ProfileSetupFailureKind?, room: Room?): Verdict = when (kind) {
        ProfileSetupFailureKind.MAX_USERS_REACHED -> Verdict.TOTAL_LIMIT
        ProfileSetupFailureKind.USER_TYPE_LIMIT -> Verdict.TYPE_LIMIT
        ProfileSetupFailureKind.USER_TYPE_DISABLED -> Verdict.TYPE_DISABLED
        else -> room?.verdict ?: Verdict.UNKNOWN
    }

    private fun slot(user: ProfileUserRecord, main: Int?, records: List<CycloneProfileRecord>): Slot {
        val type = user.userType.lowercase()
        val record = records.firstOrNull { it.androidUserId == user.id && it.id == user.name }
        val cycloneNamed = ProfileSetupPlan.validProfileName(user.name)
        return when {
            type.endsWith("full.guest") -> Slot(user.id, "Guest", SlotKind.GUEST, counted = false)
            user.id == main -> Slot(user.id, "Main", SlotKind.MAIN, true)
            record != null && record.inTrash -> Slot(user.id, record.label, SlotKind.CYCLONE_REMOVED, true)
            record != null && record.ready && !user.partial -> Slot(user.id, record.label, SlotKind.CYCLONE, true)
            cycloneNamed -> Slot(user.id, record?.label ?: "Unfinished Cyclone profile", SlotKind.CYCLONE_UNFINISHED, true)
            type.endsWith("profile.managed") -> Slot(user.id, "Work profile", SlotKind.WORK, true)
            type.endsWith("profile.private") -> Slot(user.id, "Private space", SlotKind.PRIVATE, true)
            type.endsWith("profile.clone") -> Slot(user.id, "App clones", SlotKind.CLONE, true)
            user.profile -> Slot(user.id, user.name.ifBlank { "Profile ${user.id}" }.take(40), SlotKind.OTHER_PROFILE, true)
            else -> Slot(user.id, user.name.ifBlank { "User ${user.id}" }.take(40), SlotKind.OTHER_USER, true)
        }
    }

    /**
     * The user types from `dumpsys user` (only their name, whether they are enabled and their maximum). Lines look
     * like `mName: android.os.usertype.full.SECONDARY`, `mEnabled: true`, `mMaxAllowed: -1 mMaxAllowedPerParent: -1`.
     */
    fun typeLimits(dumpsys: String): List<TypeLimit> {
        val out = mutableListOf<TypeLimit>()
        var name: String? = null
        var enabled: Boolean? = null
        var max: Int? = null
        fun flush() { name?.let { out += TypeLimit(it, enabled, max) } }
        dumpsys.lineSequence().forEach { raw ->
            val line = raw.trim()
            Regex("^mName:\\s*(android\\.os\\.usertype\\.[A-Za-z0-9_.]+)").find(line)?.let {
                flush()
                name = it.groupValues[1]; enabled = null; max = null
                return@forEach
            }
            if (name == null) return@forEach
            Regex("^mEnabled:\\s*(true|false)").find(line)?.let { enabled = it.groupValues[1] == "true" }
            Regex("^mMaxAllowed:\\s*(-?[0-9]+)").find(line)?.let { max = it.groupValues[1].toIntOrNull() }
        }
        flush()
        return out.distinctBy { it.type }
    }
}
