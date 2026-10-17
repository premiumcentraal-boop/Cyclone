package com.cyclone.mobile.runtime.safe

/** Explicit persistence inventory. SAFE_SEALED entries require an in-memory adapter, never a raw copy. */
internal object SafeManifest {
    enum class Kind { SAFE, SAFE_SEALED, NEVER }
    enum class Location { PREFS, DEVICE_PREFS, DEVICE_FILES, DATABASE, FILES, NO_BACKUP, KEYSTORE }
    data class Store(val location: Location, val name: String, val kind: Kind, val reason: String)
    private fun prefs(name: String, kind: Kind, reason: String) = Store(Location.PREFS, name, kind, reason)
    private fun devicePrefs(name: String, kind: Kind, reason: String) = Store(Location.DEVICE_PREFS, name, kind, reason)
    private fun deviceFiles(name: String, kind: Kind, reason: String) = Store(Location.DEVICE_FILES, name, kind, reason)
    private fun database(name: String, kind: Kind, reason: String) = Store(Location.DATABASE, name, kind, reason)
    private fun files(name: String, kind: Kind, reason: String) = Store(Location.FILES, name, kind, reason)
    private fun noBackup(name: String, kind: Kind, reason: String) = Store(Location.NO_BACKUP, name, kind, reason)
    private fun alias(name: String, kind: Kind, reason: String) = Store(Location.KEYSTORE, name, kind, reason)
    val stores = listOf(
        prefs("cyclone_drive", Kind.SAFE, "Owner preferences"),
        prefs("cyclone_ui", Kind.SAFE, "Appearance"),
        prefs("cyclone_hands", Kind.SAFE, "Interaction preferences"),
        prefs("cyclone_modes", Kind.SAFE, "Operation preferences"),
        prefs("cyclone_fast", Kind.SAFE, "Fast Path preferences"),
        prefs("cyclone_planes", Kind.SAFE, "Plane preferences"),
        prefs("cyclone_setup_cards", Kind.SAFE, "Seen setup cards"),
        prefs("cyclone_user_md", Kind.SAFE, "Owner notes preferences"),
        prefs("cyclone_trace_field", Kind.SAFE, "Indicator preferences"),
        prefs("cyclone_automation_studio", Kind.SAFE, "Routines and skills"),
        prefs("cyclone_cornerstones", Kind.SAFE, "Essential applications"),
        prefs("cyclone_profile_inventory", Kind.SAFE, "Profile inventory"),
        prefs("cyclone_ai", Kind.SAFE, "AI preferences without provider key"),
        prefs("cyclone_openrouter_catalog", Kind.NEVER, "Rebuilt model cache"),
        prefs("cyclone_carry", Kind.NEVER, "Transient transfer state"),
        prefs("cyclone_connectors", Kind.SAFE, "Per-profile connector configuration"),
        prefs("cyclone_instagram_cold_dm_leads", Kind.SAFE, "Account progress"),
        prefs("cyclone_stock_skill_checkpoints", Kind.SAFE, "Skill progress"),
        prefs("cyclone_page_debug_v293", Kind.NEVER, "Debug cache"),
        devicePrefs("cyclone_profile_origin", Kind.SAFE, "Android user profile association"),
        deviceFiles("switch-wait-", Kind.NEVER, "Transient bootstrap acknowledgement"),
        deviceFiles("root-check-", Kind.NEVER, "Transient root acknowledgement"),
        deviceFiles("profile-bootstrap", Kind.NEVER, "One-use bootstrap exchange"),
        deviceFiles("carry-inbox", Kind.NEVER, "One-use settings transfer"),
        deviceFiles("identity-inbox-", Kind.NEVER, "One-use sealed identity transfer"),
        deviceFiles("account-ask-", Kind.NEVER, "One-use account transfer"),
        deviceFiles("safe-outbox", Kind.NEVER, "Encrypted bootstrap staging; Main authenticates before external commit"),
        prefs("cyclone_profile_registry", Kind.SAFE, "Profile registry"),
        prefs("cyclone_profile_room", Kind.SAFE, "Profile configuration"),
        prefs("cyclone_profile_setup", Kind.NEVER, "Interrupted provisioning state is not resumable"),
        prefs("cyclone_run_marks_v1", Kind.SAFE, "Run history"),
        prefs("cyclone_workspaces", Kind.SAFE, "Workspace configuration"),
        prefs("live_phone", Kind.NEVER, "Transient PC session"),
        prefs("task_card_presentation", Kind.SAFE, "Dismissed cards"),
        prefs("cyclone_ai_secrets", Kind.SAFE_SEALED, "Re-seal OpenRouter key"),
        prefs("cyclone_vault_secrets_v1", Kind.SAFE_SEALED, "Re-seal vault slots"),
        prefs("cyclone_identities_v1", Kind.SAFE_SEALED, "Re-seal saved identities"),
        prefs("cyclone_hive_v1", Kind.SAFE_SEALED, "Re-seal hive keys"),
        prefs("cyclone_codes", Kind.NEVER, "Transient verification channel"),
        prefs("cyclone_sealed_leases", Kind.NEVER, "Expiring secret leases"),
        prefs("cyclone_gateway_trust_v33", Kind.SAFE_SEALED, "Trust metadata retained; new keys require owner re-pairing"),
        prefs("cyclone_pc_gateway_v293", Kind.NEVER, "Bearer sessions must be re-established"),
        prefs("cyclone_desktop_gateway_v1", Kind.SAFE, "Desktop preferences without session credentials"),
        database("cyclone_adaptive_brain_v27.db", Kind.SAFE, "Consistent Brain copy"),
        database("cyclone_brain.db", Kind.SAFE, "Consistent Brain copy"),
        database("cyclone_ai_history.db", Kind.SAFE, "Consistent run history"),
        database("cyclone_app_knowledge_v1.db", Kind.SAFE, "Consistent app knowledge"),
        database("cyclone_page_awareness_v28.db", Kind.SAFE, "Consistent page maps"),
        files("Cyclone Brain", Kind.SAFE, "Brain, skills, routines, missions and owner notes"),
        files("Cyclone Brain/Mind memory.json", Kind.SAFE_SEALED, "Re-seal Mind memory"),
        files("Cyclone Brain/Missions/queue.json", Kind.NEVER, "Transient execution queue"),
        files("atlas", Kind.SAFE, "Learned app graph"),
        files("cyclone_agent_checkpoints", Kind.NEVER, "Transient agent resume state"),
        files("cyclone-teaching-sessions", Kind.SAFE, "Owner teaching evidence"),
        files("cyclone-v292-teaching-corrections", Kind.SAFE, "Owner corrections"),
        files("cyclone-v292-gesture-evidence", Kind.SAFE, "Owner gesture evidence"),
        files("page_debug_v293", Kind.NEVER, "Page debug sandbox"),
        files("guided-recordings", Kind.SAFE, "Owner recordings"),
        files("mapping", Kind.SAFE, "Learned maps"),
        files("manual", Kind.SAFE, "Application manuals"),
        files("cyclone-v31", Kind.SAFE, "Runtime records"),
        files("decide", Kind.SAFE, "Decision lessons and local evaluation"),
        files("health", Kind.NEVER, "Rebuilt health diagnostics"),
        files("profile-backups", Kind.SAFE, "Preserve legacy backup history; current sealed stores have independent re-key adapters"),
        files("profile-room", Kind.SAFE, "Profile room configuration"),
        files("cyclone-playbooks", Kind.SAFE, "Learned playbooks"),
        noBackup("connector-config-v1", Kind.SAFE, "Connector configuration without session credentials"),
        noBackup("profile-debug", Kind.NEVER, "Transient bootstrap diagnostics"),
        noBackup("safe", Kind.NEVER, "Disposable local Safe wrapping state; recovery wraps live in ciphertext copies"),
        alias("cyclone.identities.aes.v1", Kind.SAFE_SEALED, "Identity adapter"),
        alias("cyclone.hive.aes.v1", Kind.SAFE_SEALED, "Hive adapter"),
        alias("cyclone.vault.secrets.aes.v1", Kind.SAFE_SEALED, "Vault adapter"),
        alias("cyclone.openrouter.api_key.v1", Kind.SAFE_SEALED, "Provider adapter"),
        alias("cyclone.mind_memory.v1", Kind.SAFE_SEALED, "Mind memory adapter"),
        alias("cyclone-device-key-v1", Kind.NEVER, "Nonexportable device key; owner pairs a new key"),
        alias("cyclone_gateway_v33_phone_identity", Kind.NEVER, "Nonexportable signing key; owner re-pairs"),
        alias("cyclone.profile.bootstrap.v1", Kind.NEVER, "New bootstrap exchange after reinstall"),
        alias("cyclone.safe.v1", Kind.NEVER, "Local wrapping key; Safe has independent root/hive/recovery wraps"),
    )
    fun file(path: String): Store? = stores.filter { it.location == Location.FILES &&
        (path == it.name || path.startsWith(it.name + "/")) }.maxByOrNull { it.name.length }
}
