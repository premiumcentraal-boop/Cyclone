package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings

/**
 * Plan 57 P2 (alpha.120): the apps every profile is built on. On every switch into a profile, each is installed there
 * with `install-existing` (the same app, nothing downloaded), enabled and verified, and on Magisk its root grant is
 * shared when it had one here.
 *
 * - **Required** (the switch stops without them): Cyclone, the root manager (a hidden Magisk app included), Shizuku,
 *   Cyclone Cloak and the system basics, when installed here.
 * - **Best effort** (reported, never blocking): up to [MAX_MARKED] apps
 *   the owner marks **Cornerstone** in Profiles. Only apps installed in this profile can be marked.
 */
object ProfileCornerstones {
    const val PREFS = "cyclone_cornerstones"
    const val KEY = "marked"
    const val MAX_MARKED = 12
    const val CLOAK_CONNECTOR_ID = "cyclone-cloak"

    enum class Role { CYCLONE, ROOT_MANAGER, SUPPORT, CLOAK, OWNER }

    data class Item(val packageName: String, val role: Role) {
        val required: Boolean get() = role != Role.OWNER
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

    /** One source of truth for the picker, creation and every later profile bootstrap. No app data is copied. */
    fun installed(context: Context): List<Item> {
        val pm = context.packageManager
        fun present(pkg: String): Boolean = runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
        fun systemPackage(pkg: String?): String? = pkg?.takeIf { ProfileSetupPlan.validPackageName(it) }?.takeIf {
            runCatching {
                pm.getApplicationInfo(it, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }.getOrDefault(false)
        }
        val home = systemPackage(pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName)
        val keyboard = systemPackage(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName })
        val hiddenMagisk = ProfileBootstrapRuntime.hiddenMagiskPackage(context)
        val support = (ProfileRequiredPackages.supportAllowlist + listOfNotNull(hiddenMagisk, home, keyboard))
            .filter(::present).toSet()
        return resolve(context.packageName, support, hiddenMagisk, cloakPackage(context)?.takeIf(::present), marked(context).filter(::present).toSet())
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
        cloakPackage(com.cyclone.mobile.connector.ConnectorDiscovery.discover(context))
    }.getOrNull()

    /** Provision only one unambiguous, valid connector. Existing install/enable/unlock preparation is unchanged. */
    internal fun cloakPackage(connectors: List<com.cyclone.mobile.connector.DiscoveredConnector>): String? =
        connectors.filter { it.manifest?.id == CLOAK_CONNECTOR_ID }.singleOrNull()
            ?.takeIf { !it.idConflict && it.problem == null }?.packageName
}
