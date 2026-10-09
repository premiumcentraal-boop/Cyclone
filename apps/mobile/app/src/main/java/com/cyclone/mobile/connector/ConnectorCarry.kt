package com.cyclone.mobile.connector

/**
 * Plan 57 P3 (alpha.121): the owner's approval of Cyclone Cloak follows them into every profile, so Cloak there isn't
 * `NOT_APPROVED` until approved again by hand (plan 57 D12, Cloak handoff CC1). Pure.
 *
 * - **Only Cloak** ([CARRIED]); other connectors are still approved per profile.
 * - **Re-verified here:** the same package, a valid manifest with the same connector id, and the approved signing
 *   certificate in the lineage of the app installed in *this* profile. Otherwise nothing is approved and the reason is
 *   reported.
 * - **The newest decision wins:** a revoke in this profile after the carried approval was given stays a revoke.
 * - **Scopes** are the carried ones that this install's manifest still asks for; never more.
 */
object ConnectorCarry {
    val CARRIED = setOf(CycloneCloakProfileBinding.CONNECTOR_ID)

    enum class Outcome(val approved: Boolean, val text: String) {
        ADOPTED(true, "approved"),
        ALREADY(true, "approved"),
        REVOKED_HERE(false, "revoked in this profile"),
        NOT_INSTALLED(false, "not installed in this profile"),
        NOT_A_CONNECTOR(false, "its connector manifest doesn't match here"),
        SIGNER_DIFFERS(false, "signed with a different key here; approve it again"),
    }

    /** What this install looks like here, from [ConnectorDiscovery]. */
    data class Here(val packageName: String, val manifest: ConnectorManifest?, val certHistory: List<String>, val idConflict: Boolean)

    fun outgoing(approvals: List<ConnectorApproval>): List<ConnectorApproval> = approvals.filter { it.connectorId in CARRIED }

    fun decide(carried: ConnectorApproval, here: Here?, local: ConnectorApproval?, revokedAt: Long?): Outcome = when {
        local != null && here != null && local.certSha256 in here.certHistory -> Outcome.ALREADY
        revokedAt != null && revokedAt >= carried.approvedAt -> Outcome.REVOKED_HERE
        here == null -> Outcome.NOT_INSTALLED
        here.manifest == null || here.idConflict || here.manifest.id != carried.connectorId || here.packageName != carried.packageName ->
            Outcome.NOT_A_CONNECTOR
        carried.certSha256 !in here.certHistory -> Outcome.SIGNER_DIFFERS
        else -> Outcome.ADOPTED
    }

    /** The approval saved here: the carried one, limited to what this install's manifest asks for. */
    fun adopted(carried: ConnectorApproval, here: Here): ConnectorApproval =
        carried.copy(scopes = carried.scopes intersect here.manifest?.scopes.orEmpty(), label = here.manifest?.label ?: carried.label)
}
