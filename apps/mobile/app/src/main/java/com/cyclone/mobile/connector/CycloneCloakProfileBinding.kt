package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import org.json.JSONObject

internal data class CycloneCloakBindingReference(
    val profileId: String,
    val androidUserId: Int,
    val packageName: String,
    val cloakProfileId: String,
    /** Plan 57 P3: Cloak's own health report for this app (`config.status.v1`): unknown, ready, degraded or failed. */
    val state: String = "unknown",
)

/** Plan 57 P3: how the Rooted pill reads for a profile's Cloak bindings. */
internal enum class CloakHealth(val pill: String) {
    NATIVE("Native"), ROOTED("Rooted"), CHECK("Rooted · check"), NOT_WORKING("Rooted · not working");

    companion object {
        /** The worst state among the bindings wins: one failed app means the profile is not working as bound. */
        fun of(bindings: List<CycloneCloakBindingReference>): CloakHealth = when {
            bindings.isEmpty() -> NATIVE
            bindings.any { it.state == "failed" } -> NOT_WORKING
            bindings.any { it.state == "degraded" } -> CHECK
            else -> ROOTED
        }
    }
}

/** Display-safe projection of a Cloak identity, grouped from its per-app Cyclone bindings. */
internal data class CycloneCloakProfileIdentity(
    val profileId: String,
    val androidUserId: Int,
    val identityVersion: Int?,
    val name: String?,
    val manufacturer: String?,
    val model: String?,
    val androidRelease: String?,
    val sdkInt: Int?,
    val boundApps: Int,
    val conflictingApps: Int,
)

/** Reads Cyclone Cloak's namespaced per-app binding marker without guessing from labels or device state. */
internal object CycloneCloakProfileBinding {
    const val CONNECTOR_ID = "cyclone-cloak"

    private data class IdentitySnapshot(
        val cloakProfileId: String,
        val identityVersion: Int?,
        val name: String?,
        val manufacturer: String?,
        val model: String?,
        val androidRelease: String?,
        val sdkInt: Int?,
    )

    /**
     * Cloak writes its profile reference and identity summary as the value for each bound profile/app tuple. Cyclone
     * stores it inside an opaque config envelope; validate the envelope tuple before treating it as a binding.
     */
    fun readBindings(
        records: List<CycloneProfileRecord>,
        readConfig: (ProfileConfigKey) -> String?,
    ): List<CycloneCloakBindingReference> = records.asSequence()
        .filter { it.ready && !it.inTrash && it.androidUserId != null }
        .flatMap { record ->
            val userId = record.androidUserId ?: return@flatMap emptySequence()
            record.packages.asSequence().mapNotNull { packageName ->
                val key = ProfileConfigKey(record.id, userId, packageName)
                val text = runCatching { readConfig(key) }.getOrNull() ?: return@mapNotNull null
                val (value, state) = envelope(text, key) ?: return@mapNotNull null
                val cloakProfileId = cleanText(value.opt("cloakProfileId"), 160) ?: return@mapNotNull null
                CycloneCloakBindingReference(record.id, userId, packageName, cloakProfileId, state)
            }
        }
        .toList()

    /** Read only the versioned, public identity fields and choose the most common per-app binding. */
    fun readIdentities(
        records: List<CycloneProfileRecord>,
        readConfig: (ProfileConfigKey) -> String?,
    ): List<CycloneCloakProfileIdentity> = records.asSequence()
        .filter { it.ready && !it.inTrash && it.androidUserId != null }
        .mapNotNull { record ->
            val userId = record.androidUserId ?: return@mapNotNull null
            val snapshots = record.packages.asSequence().mapNotNull { packageName ->
                val key = ProfileConfigKey(record.id, userId, packageName)
                val text = runCatching { readConfig(key) }.getOrNull() ?: return@mapNotNull null
                identitySnapshot(text, key)
            }.toList()
            val winner = snapshots.groupingBy { it }.eachCount().entries
                .sortedWith(
                    compareByDescending<Map.Entry<IdentitySnapshot, Int>> { it.value }
                        .thenBy { it.key.cloakProfileId }
                        .thenBy { it.key.name.orEmpty() }
                        .thenBy { it.key.manufacturer.orEmpty() }
                        .thenBy { it.key.model.orEmpty() }
                        .thenBy { it.key.androidRelease.orEmpty() }
                        .thenBy { it.key.sdkInt ?: -1 },
                ).firstOrNull() ?: return@mapNotNull null
            val selected = winner.key
            val hasMajority = winner.value > snapshots.size - winner.value
            CycloneCloakProfileIdentity(
                profileId = record.id,
                androidUserId = userId,
                identityVersion = selected.identityVersion.takeIf { hasMajority },
                name = selected.name.takeIf { hasMajority },
                manufacturer = selected.manufacturer.takeIf { hasMajority },
                model = selected.model.takeIf { hasMajority },
                androidRelease = selected.androidRelease.takeIf { hasMajority },
                sdkInt = selected.sdkInt.takeIf { hasMajority },
                boundApps = winner.value,
                conflictingApps = snapshots.size - winner.value,
            )
        }
        .sortedWith(compareBy<CycloneCloakProfileIdentity> { it.profileId }.thenBy { it.androidUserId })
        .toList()

    /**
     * Plan 57 P3: the one strict reading of a stored envelope, shared by bindings and identities: the tuple must match
     * exactly (an integer user id, string ids), and `value` must be an object. Returns the value and Cloak's state.
     */
    private fun envelope(text: String, key: ProfileConfigKey): Pair<JSONObject, String>? = runCatching {
        val config = JSONObject(text)
        if (config.opt("profileId") != key.profileId ||
            jsonInt(config.opt("androidUserId")) != key.androidUserId ||
            config.opt("packageName") != key.packageName
        ) return@runCatching null
        val value = config.optJSONObject("value") ?: return@runCatching null
        val state = (config.opt("state") as? String)?.takeIf { it in ProfileConfigRules.STATES } ?: "unknown"
        value to state
    }.getOrNull()

    private fun identitySnapshot(text: String, key: ProfileConfigKey): IdentitySnapshot? = runCatching {
        val (value, _) = envelope(text, key) ?: return@runCatching null
        val cloakProfileId = cleanText(value.opt("cloakProfileId"), 160) ?: return@runCatching null
        val version = jsonInt(value.opt("identityVersion"))?.takeIf { it > 0 }
        // Unknown schemas remain visibly bound but their fields are never interpreted or forwarded.
        val supported = version == 1
        IdentitySnapshot(
            cloakProfileId = cloakProfileId,
            identityVersion = version.takeIf { supported },
            name = cleanText(value.opt("name"), 80).takeIf { supported },
            manufacturer = cleanText(value.opt("manufacturer"), 80).takeIf { supported },
            model = cleanText(value.opt("model"), 80).takeIf { supported },
            androidRelease = cleanText(value.opt("androidRelease"), 40).takeIf { supported },
            sdkInt = jsonInt(value.opt("sdkInt"))?.takeIf { supported && it in 1..1000 },
        )
    }.getOrNull()

    private fun jsonInt(value: Any?): Int? = (value as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    }?.toInt()

    private fun cleanText(value: Any?, limit: Int): String? = (value as? String)
        ?.trim()
        ?.replace(Regex("[\\p{Cntrl}]+"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.take(limit)
        ?.takeIf { it.isNotBlank() }
}
