package com.cyclone.mobile.runtime.workspaces

/**
 * Plan 57 P2 (alpha.120): every settings file Cyclone keeps, classified once. A CI guard
 * (`scripts/ci/tests/test_portable_settings_guard.py`) fails the build when a new `getSharedPreferences` file isn't in
 * [table], so nothing starts travelling between profiles, or stops, by accident.
 *
 * - [Kind.CARRY]: travels on every switch, both ways (the profile you come from wins), each value through the secret
 *   filter in [CarryRules.carriesSetting].
 * - [Kind.PER_PROFILE]: belongs to this profile's Cyclone (its identity, its accounts' progress, caches, debug state).
 * - [Kind.NEVER]: secrets, pairing and sessions. Never packed, by design.
 *
 * Pure.
 */
object PortableSettings {
    enum class Kind { CARRY, PER_PROFILE, NEVER }

    /**
     * One settings file. [keys] null means every key; [sets] are the keys whose string-set values may travel; [idArrays]
     * are keys holding a JSON array of objects with an `id`, merged item by item; [maxChars] caps a string value (for an
     * id array, each item).
     */
    data class Entry(
        val file: String,
        val kind: Kind,
        val why: String,
        val keys: Set<String>? = null,
        val sets: Set<String> = emptySet(),
        val idArrays: Set<String> = emptySet(),
        val maxChars: Int = 200,
    )

    private fun carry(file: String, why: String, keys: Set<String>? = null, sets: Set<String> = emptySet(),
                      idArrays: Set<String> = emptySet(), maxChars: Int = 200) = Entry(file, Kind.CARRY, why, keys, sets, idArrays, maxChars)
    private fun mine(file: String, why: String) = Entry(file, Kind.PER_PROFILE, why)
    private fun never(file: String, why: String) = Entry(file, Kind.NEVER, why)

    val table: List<Entry> = listOf(
        // Carried: how Cyclone works for the owner.
        carry("cyclone_drive", "Drive: voice, models, buttons and who it announces",
            sets = setOf("announce_apps", "announce_contacts")),
        carry("cyclone_ui", "The look", keys = setOf("visual_quality")),
        carry("cyclone_hands", "Hands: style, handedness, typos"),
        carry("cyclone_modes", "Modes: speed, listening, silent success, phone model"),
        carry("cyclone_fast", "Fast Path: route, models, sureness"),
        carry("cyclone_planes", "Planes: mode, fallback, background"),
        carry("cyclone_setup_cards", "Setup cards already seen", sets = setOf("seen", "asked")),
        carry("cyclone_user_md", "Owner notes: on/off", keys = setOf("enabled")),
        carry("cyclone_trace_field", "The working indicator"),
        // Routines and their skills merge by id; runs and checkpoints stay with the profile that ran them.
        carry("cyclone_automation_studio", "Automation Studio routines and skills", keys = setOf("automations", "skills"),
            idArrays = setOf("automations", "skills"), maxChars = 32_768),
        carry("cyclone_cornerstones", "The apps you marked Cornerstone", sets = setOf("marked")),
        // Per profile.
        mine("cyclone_profile_inventory", "What each profile had at its last switch, as this Cyclone saw it"),
        mine("cyclone_ai", "AI settings go once at setup (ProfileBootstrapContract.aiKeys); the key is sealed separately"),
        mine("cyclone_openrouter_catalog", "A model list cache, fetched again"),
        mine("cyclone_carry", "This profile's carry state"),
        mine("cyclone_connectors", "Connector approvals are given per profile"),
        mine("cyclone_instagram_cold_dm_leads", "Progress of this profile's own account"),
        mine("cyclone_stock_skill_checkpoints", "Progress of this profile's own accounts"),
        mine("cyclone_page_debug_v293", "Debug sandbox"),
        mine("cyclone_profile_origin", "Which profile this Cyclone is"),
        mine("cyclone_profile_registry", "The profile list, synced by its own merge rules"),
        mine("cyclone_profile_room", "Profile room, main profile only"),
        mine("cyclone_profile_setup", "Setup state of the profile being made"),
        mine("cyclone_run_marks_v1", "Run history"),
        mine("cyclone_workspaces", "Workspace state of this profile"),
        mine("live_phone", "Live phone mode, per PC session"),
        mine("task_card_presentation", "Dismissed task cards"),
        // Never.
        never("cyclone_ai_secrets", "The OpenRouter key (sealed at setup instead)"),
        never("cyclone_vault_secrets_v1", "The vault"),
        never("cyclone_codes", "Codes"),
        never("cyclone_sealed_leases", "Sealed leases"),
        never("cyclone_gateway_trust_v33", "PC pairing"),
        never("cyclone_pc_gateway_v293", "PC sessions"),
        never("cyclone_desktop_gateway_v1", "PC pairing"),
    )

    private val byFile = table.associateBy { it.file }

    fun entry(file: String): Entry? = byFile[file]

    /** The carried files, file → keys (null for every key). */
    val carried: Map<String, Set<String>?> = table.filter { it.kind == Kind.CARRY }.associate { it.file to it.keys }

    /** Owner-facing count of what travels, for the "Profile C has" line. */
    fun carriedFiles(): Int = carried.size
}
