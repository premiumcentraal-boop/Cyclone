package com.cyclone.mobile.identity

import android.content.Context
import org.json.JSONObject

/**
 * Plan 59 (build 1): saves an account the moment Account Setup reports it created.
 *
 * The identity is bound to the profile the run used. In Main it goes straight into [IdentityVault]. In another
 * profile it is sealed for Main's Keystore key and handed to Main's Cyclone (one fixed root step in
 * ProfileBootstrapRuntime, the same way a profile switch carries settings), so the account survives that profile
 * being deleted. The PC already holds its own encrypted copy (the vault item Glass made with the password), so a
 * failed handoff loses nothing.
 */
object IdentitySaver {
    /** Seam for JVM tests: hands a sealed identity to Main. Production: ProfileBootstrapRuntime. */
    internal var handToMain: (Context, JSONObject) -> Unit = { context, json ->
        com.cyclone.mobile.runtime.workspaces.ProfileBootstrapRuntime.handIdentityToMain(context, json)
    }

    /** The Cyclone profile this process runs in: (id, label). Main is "main". */
    fun currentProfile(context: Context): Pair<String, String> =
        runCatching { com.cyclone.mobile.runtime.workspaces.ProfileCarry.me(context) }.getOrDefault(IdentityBook.MAIN to "Main")

    enum class Saved { IN_MAIN, HANDED_TO_MAIN, PC_ONLY }

    /**
     * Account Setup made [handle] in [app] with [values] (the row's details) and [password] (taken from the run's
     * one-shot lease, or null when the run never filled one). [password] is wiped here.
     */
    fun accountCreated(context: Context, app: String, handle: String?, values: Map<String, String>, password: CharArray?): Saved {
        try {
            if (!SavedIdentity.validApp(app)) return Saved.PC_ONLY
            val (profileId, label) = currentProfile(context)
            val card = IdentityCard.fromSignup(values)
            val identity = SavedIdentity(
                id = SavedIdentity.newId(), app = app, handle = (handle ?: card.username.ifBlank { card.email }).take(100), card = card,
                password = password?.let { String(it) }.orEmpty(), profileId = profileId.takeIf(IdentityBook::validProfile),
                profileLabel = label.take(40), wasProfileLabel = null, createdAtMs = 0, updatedAtMs = 0,
            )
            return if (profileId == IdentityBook.MAIN) {
                IdentityVault.change(context) { book, now -> book.save(identity, now) }
                Saved.IN_MAIN
            } else {
                val json = identity.toJson()
                try { handToMain(context, json) } finally { json.remove("password") }
                Saved.HANDED_TO_MAIN
            }
        } catch (_: Exception) {
            // Never log the identity. The PC's vault copy still has it.
            return Saved.PC_ONLY
        } finally {
            password?.fill('\u0000')
        }
    }

    /** Main's Cyclone takes in an identity another profile handed it (called by ProfileBootstrapService). */
    fun takeIn(context: Context, json: JSONObject) {
        val identity = SavedIdentity.fromJson(json) ?: throw IllegalArgumentException("Not an identity.")
        IdentityVault.change(context) { book, now -> book.save(identity, now) }
    }
}
