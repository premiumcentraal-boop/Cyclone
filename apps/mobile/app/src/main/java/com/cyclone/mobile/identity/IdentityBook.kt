package com.cyclone.mobile.identity

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Plan 59 (build 1): the accounts this phone keeps, like a password manager. Pure Kotlin, so it is JVM-tested; the
 * Android edge ([IdentityVault]) only encrypts and stores it.
 *
 * - An identity is an app account: its app, handle, the identity card (names, birthday, username, email, phone) and
 *   the password.
 * - It is **bound** to one Cyclone profile. A profile that is gone from the roster turns its identities into **not
 *   bound** ("was: Food"); nothing is erased.
 * - **Delete** moves it to the trash for [TRASH_DAYS] days, then it is purged. Nothing here touches the real account.
 * - **The hive** (run 2b): every change is stamped with when and by which phone, and queued for the hive ([pending]).
 *   An account bound on another phone keeps that binding ([SavedIdentity.boundDevice]); this phone's roster never
 *   unbinds it. The newest version wins ([newer]). Delete forever leaves a tombstone ([gone]), so every phone drops it.
 */
data class IdentityCard(
    val firstName: String = "",
    val lastName: String = "",
    val fullName: String = "",
    val birthday: String = "",
    val username: String = "",
    val email: String = "",
    val phone: String = "",
) {
    fun isEmpty(): Boolean = listOf(firstName, lastName, fullName, birthday, username, email, phone).all { it.isBlank() }

    /** Label → value, in the order the Accounts page shows them; empty ones are left out. */
    fun rows(): List<Pair<String, String>> = listOf(
        "Full name" to fullName, "First name" to firstName, "Last name" to lastName, "Birthday" to birthday,
        "Username" to username, "Email" to email, "Phone number" to phone,
    ).filter { it.second.isNotBlank() }

    fun toJson(): JSONObject = JSONObject().put("firstName", firstName).put("lastName", lastName).put("fullName", fullName)
        .put("birthday", birthday).put("username", username).put("email", email).put("phone", phone)

    companion object {
        private const val MAX = 200

        fun fromJson(json: JSONObject?): IdentityCard = if (json == null) IdentityCard() else IdentityCard(
            clean(json.optString("firstName")), clean(json.optString("lastName")), clean(json.optString("fullName")),
            clean(json.optString("birthday")), clean(json.optString("username")), clean(json.optString("email")), clean(json.optString("phone")),
        )

        /** An Account Setup row's values (first_name, …) as a card. Keys outside the card (gender, choices) are left out. */
        fun fromSignup(values: Map<String, String>): IdentityCard = IdentityCard(
            clean(values["first_name"]), clean(values["last_name"]), clean(values["full_name"]), clean(values["birthday"]),
            clean(values["username"]), clean(values["email"]), clean(values["phone"]),
        )

        private fun clean(value: String?): String = value.orEmpty().replace(Regex("[\\u0000-\\u001F]"), " ").trim().take(MAX)
    }
}

data class SavedIdentity(
    val id: String,
    /** The app's package (com.instagram.android) or a website's host. */
    val app: String,
    val handle: String,
    val card: IdentityCard,
    /** Never logged, never in [toString]. */
    val password: String,
    val profileId: String?,
    val profileLabel: String?,
    val wasProfileLabel: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val trashedAtMs: Long? = null,
    val origin: String = ORIGIN_SIGNUP,
    /** The hive's id of the phone the profile is on (null: this phone, before it joined the hive). */
    val boundDevice: String? = null,
    val boundDeviceName: String? = null,
    /** Who made this version: a phone's hive id or "glass" (blank: this phone, before it joined the hive). */
    val updatedBy: String = "",
    /** Run 2c: the PC's record of the same account (Command Center account id), once Glass linked them. */
    val accountId: String? = null,
) {
    val bound: Boolean get() = profileId != null
    /** Bound to a profile on this phone (or not yet stamped with a phone, which means this one). */
    fun boundHere(me: String): Boolean = bound && (boundDevice == null || boundDevice == me || me.isEmpty())
    val trashed: Boolean get() = trashedAtMs != null
    fun purgeAtMs(): Long? = trashedAtMs?.plus(IdentityBook.TRASH_MS)

    override fun toString(): String = "SavedIdentity(id=$id, app=$app, bound=$bound, trashed=$trashed)"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("app", app).put("handle", handle).put("card", card.toJson())
        .put("password", password).put("profileId", profileId ?: JSONObject.NULL).put("profileLabel", profileLabel ?: JSONObject.NULL)
        .put("wasProfileLabel", wasProfileLabel ?: JSONObject.NULL).put("createdAtMs", createdAtMs).put("updatedAtMs", updatedAtMs)
        .put("trashedAtMs", trashedAtMs ?: JSONObject.NULL).put("origin", origin)
        .put("boundDevice", boundDevice ?: JSONObject.NULL).put("boundDeviceName", boundDeviceName ?: JSONObject.NULL).put("updatedBy", updatedBy)
        .put("accountId", accountId ?: JSONObject.NULL)

    companion object {
        const val ORIGIN_SIGNUP = "signup"
        private val ID = Regex("^idn_[a-f0-9]{32}$")
        private val ACCOUNT_ID = Regex("^[a-z]{1,4}_[A-Za-z0-9_-]{6,40}$")
        private val APP = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_-]+)+$")

        fun newId(): String = "idn_" + UUID.randomUUID().toString().replace("-", "")

        fun fromJson(json: JSONObject): SavedIdentity? = runCatching {
            val id = json.getString("id")
            val app = json.getString("app")
            if (!ID.matches(id) || !APP.matches(app)) return null
            SavedIdentity(
                id = id, app = app, handle = json.optString("handle").take(100), card = IdentityCard.fromJson(json.optJSONObject("card")),
                password = json.optString("password").take(4_096),
                profileId = json.optString("profileId").takeIf { it.isNotBlank() && !json.isNull("profileId") && IdentityBook.validProfile(it) },
                profileLabel = json.optString("profileLabel").takeIf { it.isNotBlank() && !json.isNull("profileLabel") }?.take(40),
                wasProfileLabel = json.optString("wasProfileLabel").takeIf { it.isNotBlank() && !json.isNull("wasProfileLabel") }?.take(40),
                createdAtMs = json.optLong("createdAtMs"), updatedAtMs = json.optLong("updatedAtMs"),
                trashedAtMs = if (json.isNull("trashedAtMs") || !json.has("trashedAtMs")) null else json.optLong("trashedAtMs"),
                origin = json.optString("origin", ORIGIN_SIGNUP).take(20),
                boundDevice = json.optString("boundDevice").takeIf { it.isNotBlank() && !json.isNull("boundDevice") }?.take(120),
                boundDeviceName = json.optString("boundDeviceName").takeIf { it.isNotBlank() && !json.isNull("boundDeviceName") }?.take(60),
                updatedBy = json.optString("updatedBy").take(120),
                accountId = json.optString("accountId").takeIf { !json.isNull("accountId") && ACCOUNT_ID.matches(it) },
            )
        }.getOrNull()

        fun validApp(app: String): Boolean = APP.matches(app) && app.length <= 200
    }
}

/** Run 2c: one profile on this phone used an account in a task. Kept on this phone only, never in the hive. */
data class AccountUseRecord(val profileId: String, val label: String, val count: Int, val lastMs: Long)

class IdentityBook(entries: List<SavedIdentity> = emptyList(), pending: Collection<String> = emptyList(), gone: Map<String, Long> = emptyMap(),
                   uses: Map<String, List<AccountUseRecord>> = emptyMap()) {
    private val items = entries.associateBy { it.id }.toMutableMap()
    /** Ids changed here and not yet kept by the hive. */
    private val pendingIds = pending.toMutableSet()
    /** Deleted forever: id → when. Kept so a late copy never brings it back, and so the hive learns of it. */
    private val goneAt = gone.toMutableMap()
    /** This phone's hive id (blank until it joins) and name: stamped on every change made here. */
    var me: String = ""
    var myName: String = ""

    /** Run 2c: which profiles here used which account in a task (account id → uses, newest first). */
    private val useLog = uses.mapValues { it.value.toMutableList() }.toMutableMap()

    fun uses(id: String): List<AccountUseRecord> = useLog[id].orEmpty().sortedByDescending { it.lastMs }

    /** A task in [profileId] used account [id] (a login filled from it). */
    fun recordUse(id: String, profileId: String, label: String, now: Long) {
        if (id !in items) return
        val list = useLog.getOrPut(id) { mutableListOf() }
        val old = list.firstOrNull { it.profileId == profileId }
        list.remove(old)
        list += AccountUseRecord(profileId, label.take(40), (old?.count ?: 0) + 1, now)
        while (list.size > MAX_USES) list.remove(list.minByOrNull { it.lastMs })
    }

    fun pending(): Set<String> = pendingIds.toSet()
    fun gone(): Map<String, Long> = goneAt.toMap()

    private fun touch(identity: SavedIdentity): SavedIdentity {
        val stamped = identity.copy(updatedBy = me)
        items[stamped.id] = stamped
        pendingIds += stamped.id
        return stamped
    }

    fun all(): List<SavedIdentity> = items.values.sortedWith(compareBy({ it.app }, { it.handle.lowercase() }))
    fun active(): List<SavedIdentity> = all().filter { !it.trashed }
    fun trash(): List<SavedIdentity> = all().filter { it.trashed }
    fun get(id: String): SavedIdentity? = items[id]

    /** Adds a new account, or updates the one with the same app and handle (a second save of the same account). */
    fun save(identity: SavedIdentity, now: Long): SavedIdentity {
        val same = items.values.firstOrNull { it.app == identity.app && it.handle.equals(identity.handle, ignoreCase = true) && it.handle.isNotBlank() }
        val saved = if (same != null) identity.copy(id = same.id, createdAtMs = same.createdAtMs, updatedAtMs = now, trashedAtMs = null)
            else identity.copy(createdAtMs = now, updatedAtMs = now)
        return touch(saved.copy(boundDevice = saved.boundDevice ?: me.ifEmpty { null }.takeIf { saved.bound },
            boundDeviceName = saved.boundDeviceName ?: myName.ifEmpty { null }.takeIf { saved.bound }))
    }

    fun bind(id: String, profileId: String, label: String?, now: Long): SavedIdentity? {
        require(validProfile(profileId)) { "Not a Cyclone profile." }
        val current = items[id]?.takeIf { !it.trashed } ?: return null
        return touch(current.copy(profileId = profileId, profileLabel = label?.take(40), wasProfileLabel = null, updatedAtMs = now,
            boundDevice = me.ifEmpty { null }, boundDeviceName = myName.ifEmpty { null }))
    }

    fun unbind(id: String, now: Long): SavedIdentity? {
        val current = items[id] ?: return null
        if (!current.bound) return current
        return touch(current.copy(profileId = null, profileLabel = null, wasProfileLabel = current.profileLabel, updatedAtMs = now,
            boundDevice = null, boundDeviceName = null))
    }

    fun moveToTrash(id: String, now: Long): SavedIdentity? = items[id]?.let { touch(it.copy(trashedAtMs = now, updatedAtMs = now)) }

    fun restore(id: String, now: Long): SavedIdentity? = items[id]?.takeIf { it.trashed }?.let { touch(it.copy(trashedAtMs = null, updatedAtMs = now)) }

    /** Delete forever: Cyclone's copy, on every phone in the hive. The real account stays. */
    fun purge(id: String, now: Long = System.currentTimeMillis()): Boolean = items[id]?.takeIf { it.trashed }?.let { forget(id, now); true } ?: false

    private fun forget(id: String, now: Long) {
        items.remove(id)
        useLog.remove(id)
        goneAt[id] = now
        pendingIds += id
        while (goneAt.size > MAX_GONE) goneAt.remove(goneAt.minByOrNull { it.value }!!.key)
    }

    /** Purges what has been in the trash for [TRASH_DAYS] days. Returns how many went. */
    fun expire(now: Long): Int {
        val gone = items.values.filter { (it.purgeAtMs() ?: Long.MAX_VALUE) <= now }.map { it.id }
        gone.forEach { forget(it, now) }
        return gone.size
    }

    // ------------------------------------------------------------------ the hive (run 2b)

    /** Joined the hive: everything here goes to it once. */
    fun markAllPending() {
        pendingIds += items.keys
        pendingIds += goneAt.keys
    }

    /** The hive kept [id] as of [at]: it leaves the outbox unless it changed again since. */
    fun acked(id: String, at: Long) {
        val now = items[id]?.updatedAtMs ?: goneAt[id]
        if (now == at) pendingIds -= id
    }

    /** The version this phone pushes for [id]: stamped with this phone when it was made before joining. */
    fun outgoing(id: String): SavedIdentity? {
        val identity = items[id] ?: return null
        if (identity.updatedBy.isNotEmpty() || me.isEmpty()) return identity
        val bound = identity.bound && identity.boundDevice == null
        return identity.copy(updatedBy = me, boundDevice = if (bound) me else identity.boundDevice,
            boundDeviceName = if (bound) myName.ifEmpty { null } else identity.boundDeviceName).also { items[id] = it }
    }

    /** Another phone or Glass wrote [remote]. Kept when it is newer; returns whether it was. */
    fun applyRemote(remote: SavedIdentity): Boolean {
        if (remote.id in goneAt) return false
        val local = items[remote.id]
        if (local != null && !newer(remote, local)) return false
        items[remote.id] = remote
        pendingIds -= remote.id  // an older edit made here lost to it
        return true
    }

    /** Another phone or Glass deleted [id] forever. */
    fun applyGone(id: String, at: Long) {
        items.remove(id)
        useLog.remove(id)
        pendingIds -= id
        goneAt[id] = maxOf(at, goneAt[id] ?: 0)
    }

    /**
     * The phone left the hive: what came from other phones goes. What is bound to this phone's profiles stays, and
     * so does anything the hive hasn't kept yet. Returns how many went.
     */
    fun leaveHive(): Int {
        val drop = items.values.filter { !it.boundHere(me) && it.id !in pendingIds }.map { it.id }
        drop.forEach(items::remove)
        return drop.size
    }

    /**
     * The phone's current profiles ([present], ids). A bound profile that is gone was deleted: its identities become
     * not bound, keeping "was: <label>". Main is always present. Returns how many changed.
     */
    fun applyRoster(present: Set<String>, now: Long): Int {
        var changed = 0
        for (identity in items.values.toList()) {
            val profile = identity.profileId ?: continue
            if (!identity.boundHere(me)) continue  // another phone's profile
            if (profile != MAIN && profile !in present) {
                touch(identity.copy(profileId = null, profileLabel = null, wasProfileLabel = identity.profileLabel, updatedAtMs = now,
                    boundDevice = null, boundDeviceName = null))
                changed++
            }
        }
        return changed
    }

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("identities", JSONArray(all().map { it.toJson() }))
        .put("pending", JSONArray(pendingIds.sorted()))
        .put("gone", JSONObject().apply { goneAt.forEach { (id, at) -> put(id, at) } })
        .put("uses", JSONObject().apply {
            useLog.forEach { (id, list) ->
                put(id, JSONArray(list.map { JSONObject().put("profileId", it.profileId).put("label", it.label).put("count", it.count).put("lastMs", it.lastMs) }))
            }
        })

    companion object {
        const val TRASH_DAYS = 30
        const val TRASH_MS = TRASH_DAYS * 24L * 60 * 60 * 1000
        const val MAIN = "main"
        const val MAX_GONE = 2_000
        const val MAX_USES = 8
        private val PROFILE = Regex("^(main|Cyclone_[a-f0-9]{16})$")

        fun validProfile(id: String): Boolean = PROFILE.matches(id)

        /** The newest version wins: the later time, then the writer's id as the tie-break (as the PC and Glass order them). */
        fun newer(a: SavedIdentity, b: SavedIdentity): Boolean =
            a.updatedAtMs > b.updatedAtMs || (a.updatedAtMs == b.updatedAtMs && a.updatedBy > b.updatedBy)

        fun fromJson(json: JSONObject?): IdentityBook {
            if (json == null || json.optInt("v") != 1) return IdentityBook()
            val list = json.optJSONArray("identities") ?: JSONArray()
            val pending = json.optJSONArray("pending")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val gone = json.optJSONObject("gone")?.let { g -> g.keys().asSequence().associateWith { g.optLong(it) } }.orEmpty()
            val uses = json.optJSONObject("uses")?.let { u ->
                u.keys().asSequence().associateWith { id ->
                    val a = u.optJSONArray(id) ?: JSONArray()
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { r ->
                        AccountUseRecord(r.optString("profileId"), r.optString("label").take(40), r.optInt("count"), r.optLong("lastMs"))
                    } }
                }
            }.orEmpty()
            return IdentityBook((0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(SavedIdentity::fromJson) }, pending, gone, uses)
        }
    }
}
