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
) {
    val bound: Boolean get() = profileId != null
    val trashed: Boolean get() = trashedAtMs != null
    fun purgeAtMs(): Long? = trashedAtMs?.plus(IdentityBook.TRASH_MS)

    override fun toString(): String = "SavedIdentity(id=$id, app=$app, bound=$bound, trashed=$trashed)"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("app", app).put("handle", handle).put("card", card.toJson())
        .put("password", password).put("profileId", profileId ?: JSONObject.NULL).put("profileLabel", profileLabel ?: JSONObject.NULL)
        .put("wasProfileLabel", wasProfileLabel ?: JSONObject.NULL).put("createdAtMs", createdAtMs).put("updatedAtMs", updatedAtMs)
        .put("trashedAtMs", trashedAtMs ?: JSONObject.NULL).put("origin", origin)

    companion object {
        const val ORIGIN_SIGNUP = "signup"
        private val ID = Regex("^idn_[a-f0-9]{32}$")
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
            )
        }.getOrNull()

        fun validApp(app: String): Boolean = APP.matches(app) && app.length <= 200
    }
}

class IdentityBook(entries: List<SavedIdentity> = emptyList()) {
    private val items = entries.associateBy { it.id }.toMutableMap()

    fun all(): List<SavedIdentity> = items.values.sortedWith(compareBy({ it.app }, { it.handle.lowercase() }))
    fun active(): List<SavedIdentity> = all().filter { !it.trashed }
    fun trash(): List<SavedIdentity> = all().filter { it.trashed }
    fun get(id: String): SavedIdentity? = items[id]

    /** Adds a new account, or updates the one with the same app and handle (a second save of the same account). */
    fun save(identity: SavedIdentity, now: Long): SavedIdentity {
        val same = items.values.firstOrNull { it.app == identity.app && it.handle.equals(identity.handle, ignoreCase = true) && it.handle.isNotBlank() }
        val saved = if (same != null) identity.copy(id = same.id, createdAtMs = same.createdAtMs, updatedAtMs = now, trashedAtMs = null)
            else identity.copy(createdAtMs = now, updatedAtMs = now)
        items[saved.id] = saved
        return saved
    }

    fun bind(id: String, profileId: String, label: String?, now: Long): SavedIdentity? {
        require(validProfile(profileId)) { "Not a Cyclone profile." }
        val current = items[id]?.takeIf { !it.trashed } ?: return null
        return current.copy(profileId = profileId, profileLabel = label?.take(40), wasProfileLabel = null, updatedAtMs = now).also { items[id] = it }
    }

    fun unbind(id: String, now: Long): SavedIdentity? {
        val current = items[id] ?: return null
        if (!current.bound) return current
        return current.copy(profileId = null, profileLabel = null, wasProfileLabel = current.profileLabel, updatedAtMs = now).also { items[id] = it }
    }

    fun moveToTrash(id: String, now: Long): SavedIdentity? =
        items[id]?.copy(trashedAtMs = now, updatedAtMs = now)?.also { items[id] = it }

    fun restore(id: String, now: Long): SavedIdentity? =
        items[id]?.takeIf { it.trashed }?.copy(trashedAtMs = null, updatedAtMs = now)?.also { items[id] = it }

    /** Delete forever: only this phone's copy. */
    fun purge(id: String): Boolean = items[id]?.takeIf { it.trashed }?.let { items.remove(id); true } ?: false

    /** Purges what has been in the trash for [TRASH_DAYS] days. Returns how many went. */
    fun expire(now: Long): Int {
        val gone = items.values.filter { (it.purgeAtMs() ?: Long.MAX_VALUE) <= now }.map { it.id }
        gone.forEach(items::remove)
        return gone.size
    }

    /**
     * The phone's current profiles ([present], ids). A bound profile that is gone was deleted: its identities become
     * not bound, keeping "was: <label>". Main is always present. Returns how many changed.
     */
    fun applyRoster(present: Set<String>, now: Long): Int {
        var changed = 0
        for (identity in items.values.toList()) {
            val profile = identity.profileId ?: continue
            if (profile != MAIN && profile !in present) {
                items[identity.id] = identity.copy(profileId = null, profileLabel = null, wasProfileLabel = identity.profileLabel, updatedAtMs = now)
                changed++
            }
        }
        return changed
    }

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("identities", JSONArray(all().map { it.toJson() }))

    companion object {
        const val TRASH_DAYS = 30
        const val TRASH_MS = TRASH_DAYS * 24L * 60 * 60 * 1000
        const val MAIN = "main"
        private val PROFILE = Regex("^(main|Cyclone_[a-f0-9]{16})$")

        fun validProfile(id: String): Boolean = PROFILE.matches(id)

        fun fromJson(json: JSONObject?): IdentityBook {
            if (json == null || json.optInt("v") != 1) return IdentityBook()
            val list = json.optJSONArray("identities") ?: JSONArray()
            return IdentityBook((0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(SavedIdentity::fromJson) })
        }
    }
}
