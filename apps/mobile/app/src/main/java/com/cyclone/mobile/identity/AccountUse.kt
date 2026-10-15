package com.cyclone.mobile.identity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 59 (build 2, run 2c): a task logs in with an account this phone keeps.
 *
 * - **Which accounts:** the saved accounts for the app on screen, as names and bindings only ([Candidate]). Never a
 *   password. The Mind picks one by its handle when there are several.
 * - **Bound to this profile on this phone:** the login fills at once, from Main's encrypted book. In another profile,
 *   Main hands over that one value, sealed to that profile's key, for that one request (ProfileBootstrapRuntime).
 * - **Anywhere else** (another profile, another phone, or not bound): the run first asks the owner on a confirmation
 *   card. The card names the account, where it is bound, where the agent wants to use it, and the agent's reason. Only
 *   a yes, for that task, lets Main hand it over ([serve] refuses otherwise).
 * - Every hand-over is recorded on this phone ("Used by Food tasks"); the record holds no value.
 *
 * Main is the only place the book is opened. Nothing here logs.
 */
object AccountUse {
    /** The approval card's gate: the Lab never approves it, and the Command Center never batches it. */
    const val GATE = "account"

    /**
     * The confirmation card's words: the account, where it is bound, where the agent wants to use it, and the agent's
     * own reason. Handles and profile names only.
     */
    fun confirmText(appLabel: String, candidate: Candidate, hereLabel: String, reason: String): String =
        "log in to $appLabel as ${candidate.handle.ifBlank { "a saved account" }} in $hereLabel on this phone. " +
            "This account is bound to ${candidate.binding}. Reason: ${reason.ifBlank { "Sign in" }}"

    /** The login fields a saved account can fill. */
    val SLOTS = setOf("password", "username", "email", "phone")

    data class Candidate(
        val id: String,
        val app: String,
        val handle: String,
        /** Bound to the asking profile on this phone: it fills without a card. */
        val here: Boolean,
        /** Where it is bound, in plain words: "Food on Galaxy", "Main on this phone", "not bound". */
        val binding: String,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("app", app).put("handle", handle).put("here", here).put("binding", binding)

        companion object {
            fun fromJson(j: JSONObject): Candidate? = runCatching {
                Candidate(j.getString("id"), j.getString("app"), j.optString("handle").take(100), j.getBoolean("here"), j.optString("binding").take(80))
            }.getOrNull()
        }
    }

    sealed class Pick {
        /** Use [candidate]; [confirm] when it isn't bound to this profile on this phone. */
        data class Use(val candidate: Candidate, val confirm: Boolean) : Pick()
        /** Several fit and none was named: the Mind names one. [text] lists them (handles and bindings only). */
        data class Choose(val text: String) : Pick()
        /** None saved for this app (or none by that name): the Secrets Card as before. */
        object None : Pick()
    }

    /** Pure: which account to use. A named handle wins; else the one bound here; else the only one. */
    fun pick(candidates: List<Candidate>, requested: String?): Pick {
        if (candidates.isEmpty()) return Pick.None
        val name = requested?.trim()?.removePrefix("@")?.lowercase().orEmpty()
        if (name.isNotEmpty()) {
            val match = candidates.filter { it.handle.removePrefix("@").lowercase() == name }
            return match.singleOrNull()?.let { Pick.Use(it, !it.here) } ?: if (match.isEmpty()) Pick.None else Pick.Choose(list(match))
        }
        candidates.filter { it.here }.singleOrNull()?.let { return Pick.Use(it, false) }
        candidates.singleOrNull()?.let { return Pick.Use(it, !it.here) }
        return Pick.Choose(list(candidates))
    }

    private fun list(candidates: List<Candidate>): String = candidates.joinToString("; ") {
        "${it.handle.ifBlank { "(no handle)" }} (${if (it.here) "this profile" else it.binding})"
    }

    /** "Food on Galaxy", "Main on this phone", "Food (this phone)", or "not bound". */
    fun binding(identity: SavedIdentity, me: String, labels: Map<String, String>): String {
        val profile = when (identity.profileId) {
            null -> return "not bound"
            IdentityBook.MAIN -> "Main"
            else -> labels[identity.profileId] ?: identity.profileLabel ?: "a profile"
        }
        return if (identity.boundHere(me)) "$profile on this phone" else "$profile on ${identity.boundDeviceName ?: "another phone"}"
    }

    /** Main, pure on the book: the accounts for [app] as the profile [profileId] here sees them. */
    fun candidatesIn(book: IdentityBook, app: String, profileId: String, labels: Map<String, String>): List<Candidate> =
        book.active().filter { it.app.equals(app, ignoreCase = true) }.map {
            Candidate(it.id, it.app, it.handle, it.boundHere(book.me) && it.profileId == profileId, binding(it, book.me, labels))
        }

    fun valueOf(identity: SavedIdentity, slot: String): String? = when (slot) {
        "password" -> identity.password
        "username" -> identity.card.username.ifBlank { identity.handle.removePrefix("@") }
        "email" -> identity.card.email
        "phone" -> identity.card.phone
        else -> null
    }.takeIf { !it.isNullOrEmpty() }

    /**
     * Main, pure on the book: the value of [slot] of account [id] for a task in [profileId] on this phone, or a
     * refusal code. An account bound elsewhere needs [confirmed] (the owner said yes on the card for this task). A use
     * is recorded on success.
     */
    fun serve(book: IdentityBook, id: String, slot: String, profileId: String, label: String, confirmed: Boolean, now: Long): Result<String> {
        if (slot !in SLOTS) return Result.failure(Refused("SLOT"))
        val identity = book.get(id)?.takeIf { !it.trashed } ?: return Result.failure(Refused("NO_ACCOUNT"))
        val here = identity.boundHere(book.me) && identity.profileId == profileId
        if (!here && !confirmed) return Result.failure(Refused("NEEDS_CONFIRMATION"))
        val value = valueOf(identity, slot) ?: return Result.failure(Refused("NO_VALUE"))
        book.recordUse(id, profileId, label, now)
        return Result.success(value)
    }

    class Refused(val code: String) : Exception(code)

    /**
     * Main answers one request ([kind] list or take), from its own Cyclone or another profile's
     * (ProfileBootstrapService). [request] names the asking profile; [verifiedProfile] is the Cyclone profile Main
     * itself matched to the asking Android user, and is what counts.
     */
    fun answer(context: Context, request: JSONObject, verifiedProfile: Pair<String, String>): JSONObject {
        val labels = runCatching { com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.records(context) }.getOrDefault(emptyList())
            .filter { !it.inTrash }.associate { it.id to it.label }
        val (profileId, label) = verifiedProfile
        return when (request.optString("kind")) {
            "list" -> {
                val app = request.optString("app")
                if (!SavedIdentity.validApp(app)) throw Refused("APP")
                val list = candidatesIn(IdentityVault.book(context), app, profileId, labels)
                JSONObject().put("candidates", JSONArray(list.map { it.toJson() }))
            }
            "take" -> {
                val value = IdentityVault.change(context) { book, now ->
                    serve(book, request.optString("id"), request.optString("slot"), profileId, label, request.optBoolean("confirmed"), now)
                }
                JSONObject().put("value", value.getOrElse { throw it })
            }
            else -> throw Refused("KIND")
        }
    }

    // ------------------------------------------------------------------ the asking side

    /** Seam for JVM tests: asks Main from another profile. Production: one sealed round trip through root. */
    internal var askMain: (Context, JSONObject) -> JSONObject = { context, request ->
        com.cyclone.mobile.runtime.workspaces.ProfileBootstrapRuntime.askMainForAccount(context, request)
    }

    private fun ask(context: Context, request: JSONObject): JSONObject {
        val me = IdentitySaver.currentProfile(context)
        return if (me.first == IdentityBook.MAIN) answer(context, request, me) else askMain(context, request)
    }

    /** The saved accounts for [app], as this profile sees them. Empty when none, or when Main can't be reached. */
    fun candidates(context: Context, app: String): List<Candidate> = runCatching {
        val reply = ask(context, JSONObject().put("kind", "list").put("app", app))
        val list = reply.optJSONArray("candidates") ?: JSONArray()
        (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(Candidate::fromJson) }
    }.getOrDefault(emptyList())

    /** The value of [slot] of account [id], once, for this task. The caller fills it and wipes it. */
    fun take(context: Context, id: String, slot: String, confirmed: Boolean): CharArray? = runCatching {
        val reply = ask(context, JSONObject().put("kind", "take").put("id", id).put("slot", slot).put("confirmed", confirmed))
        reply.optString("value").takeIf { it.isNotEmpty() }?.toCharArray()
    }.getOrNull()
}
