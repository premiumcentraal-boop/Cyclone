package com.cyclone.mobile.runtime.workspaces

import android.content.Context

/**
 * Plan 57 P2 (alpha.120): the apps every profile is built on. On every switch into a profile, each is installed there
 * with `install-existing` (the same app, nothing downloaded), enabled and verified, and on Magisk its root grant is
 * shared when it had one here.
 *
 * - **Required** (the switch stops without them): Cyclone, the root manager (a hidden Magisk app included) and Shizuku,
 *   when installed here.
 * - **Best effort** (reported, never blocking): Cyclone Cloak, found by its connector id, and up to [MAX_MARKED] apps
 *   the owner marks **Cornerstone** in Profiles. Only apps installed in this profile can be marked.
 */
object ProfileCornerstones {
    const val PREFS = "cyclone_cornerstones"
    const val KEY = "marked"
    const val MAX_MARKED = 12
    const val CLOAK_CONNECTOR_ID = "cyclone-cloak"

    enum class Role { CYCLONE, ROOT_MANAGER, SUPPORT, CLOAK, OWNER }

    data class Item(val packageName: String, val role: Role) {
        val required: Boolean get() = role == Role.CYCLONE || role == Role.ROOT_MANAGER || role == Role.SUPPORT
    }

    val rootManagers = setOf("com.topjohnwu.magisk", "me.weishu.kernelsu", "com.rifsxd.ksunext", "me.bmax.apatch")

    /**
     * The cornerstones for one switch, in install order, each package once (the first role wins). [support] is the
     * installed allowlisted support apps (plus a hidden Magisk app, named by [hiddenMagisk]).
     */
    fun resolve(cyclone: String, support: Set<String>, hiddenMagisk: String?, cloak: String?, marked: Set<String>): List<Item> {
        val items = linkedMapOf<String, Item>()
        fun add(pkg: String?, role: Role) {
            if (pkg != null && ProfileSetupPlan.validPackageName(pkg) && pkg !in items) items[pkg] = Item(pkg, role)
        }
        add(cyclone, Role.CYCLONE)
        support.sorted().forEach { add(it, if (it in rootManagers || it == hiddenMagisk) Role.ROOT_MANAGER else Role.SUPPORT) }
        add(cloak, Role.CLOAK)
        marked.sorted().take(MAX_MARKED).forEach { add(it, Role.OWNER) }
        return items.values.toList()
    }

    /** Whether the owner may mark [pkg]: a real package, not Cyclone, and room left. */
    fun canMark(pkg: String, cyclone: String, marked: Set<String>): Boolean =
        ProfileSetupPlan.validPackageName(pkg) && pkg != cyclone && (pkg in marked || marked.size < MAX_MARKED)

    // On the phone ---------------------------------------------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun marked(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty().filter(ProfileSetupPlan::validPackageName).toSet()

    fun setMarked(context: Context, pkg: String, on: Boolean) {
        val now = marked(context)
        require(!on || canMark(pkg, context.packageName, now)) { "At most $MAX_MARKED apps can be cornerstones." }
        check(prefs(context).edit().putStringSet(KEY, if (on) now + pkg else now - pkg).commit()) { "Couldn't save that." }
    }

    /** Cyclone Cloak's package here, found by its connector id (never by a guessed package name). */
    fun cloakPackage(context: Context): String? = runCatching {
        com.cyclone.mobile.connector.ConnectorDiscovery.discover(context)
            .firstOrNull { it.manifest?.id == CLOAK_CONNECTOR_ID && !it.idConflict }?.packageName
    }.getOrNull()
}
