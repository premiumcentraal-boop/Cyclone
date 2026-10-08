package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import org.json.JSONObject

internal data class CycloneCloakBindingReference(
    val profileId: String,
    val androidUserId: Int,
    val packageName: String,
    val cloakProfileId: String,
)

/** Reads Cyclone Cloak's namespaced per-app binding marker without guessing from labels or device state. */
internal object CycloneCloakProfileBinding {
    const val CONNECTOR_ID = "cyclone-cloak"

    /**
     * Cloak writes `{ "cloakProfileId": ... }` as the value for each bound profile/app tuple. Cyclone stores that
     * value inside its opaque config envelope; validate the envelope tuple before treating it as a binding.
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
                val cloakProfileId = cloakProfileId(text, key) ?: return@mapNotNull null
                CycloneCloakBindingReference(record.id, userId, packageName, cloakProfileId)
            }
        }
        .toList()

    private fun cloakProfileId(text: String, key: ProfileConfigKey): String? = runCatching {
        val config = JSONObject(text)
        val value = config.optJSONObject("value") ?: return@runCatching null
        if (config.optString("profileId") != key.profileId ||
            config.optInt("androidUserId", -1) != key.androidUserId ||
            config.optString("packageName") != key.packageName
        ) return@runCatching null
        value.optString("cloakProfileId").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
