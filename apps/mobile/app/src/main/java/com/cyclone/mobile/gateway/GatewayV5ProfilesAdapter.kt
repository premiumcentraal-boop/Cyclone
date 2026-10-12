package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.connector.CycloneCloakProfileBinding
import com.cyclone.mobile.connector.CycloneCloakProfileIdentity
import com.cyclone.mobile.connector.ProfileConfigKey
import com.cyclone.mobile.connector.ProfileConfigStore
import com.cyclone.mobile.runtime.workspaces.ProfileApps
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 43 (T4): the phone's profiles over the gateway.
 *
 * - `profiles.list` lists Profile A and Cyclone's profiles, and which one is in front.
 * - `profiles.cloak` reads only the versioned, display-safe identity fields of bound Cloak profiles.
 * - `profiles.debug` (plan 57 P3) returns the redacted profile debug file and each profile's health, read only.
 * - `profiles.apps {profileId}` lists a profile's apps and, for a Cyclone profile, the apps Profile A could give it.
 * - `profiles.switch {profileId}` puts that profile in front.
 * - `profiles.app {profileId, package, action: install|remove}` changes one Cyclone profile's apps.
 *
 * Only profiles Cyclone created are managed ([ProfileApps] re-checks ownership on every call). Nothing here creates or
 * deletes a profile; that stays on the phone.
 */
internal object GatewayV5ProfilesAdapter {
    @Volatile private var context: Context? = null
    fun install(context: Context) { this.context = context.applicationContext }
    private fun app(): Context = checkNotNull(context) { "profiles adapter not installed" }

    private val PROFILE_ID = Regex("^(main|Cyclone_[a-f0-9]{16})$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    const val MAX_APPS = 500

    /** Seams for JVM tests; production is [ProfileApps] on this phone. */
    internal var profiles: () -> List<ProfileApps.Profile> = { ProfileApps.profiles(app()) }
    internal var cloakIdentities: (List<ProfileApps.Profile>) -> List<CycloneCloakProfileIdentity> = { profileSnapshot ->
        val context = app()
        val validProfiles = profileSnapshot.asSequence()
            .filter { it.ready && !it.inTrash && it.androidUserId != null }
            .map { it.id to it.androidUserId!! }
            .toSet()
        val processUser = ProfileSetupRuntime.currentUserId()
        CycloneCloakProfileBinding.readIdentities(ProfileRegistryStore.records(context)) { key: ProfileConfigKey ->
            ProfileConfigStore.get(context, CycloneCloakProfileBinding.CONNECTOR_ID, processUser, key)
        }.filter { (it.profileId to it.androidUserId) in validProfiles }
    }
    internal var debugFacts: () -> com.cyclone.mobile.runtime.workspaces.ProfileDebugReport.Facts = {
        com.cyclone.mobile.runtime.workspaces.ProfileDebugReport.collect(app())
    }
    internal var labels: () -> Map<String, String> = { ProfileRegistryStore.records(app()).associate { it.id to it.label } }
    internal var apps: (String) -> Pair<List<ProfileApps.App>, List<ProfileApps.App>> = { ProfileApps.apps(app(), it) }
    internal var switchTo: (String) -> Unit = { ProfileApps.switchTo(app(), it) }
    internal var install: (String, String) -> Unit = { id, pkg -> ProfileApps.install(app(), id, pkg) }
    internal var remove: (String, String) -> Unit = { id, pkg -> ProfileApps.remove(app(), id, pkg) }

    fun dispatch(op: String, args: JSONObject): JSONObject = guarded {
        when (op) {
            "profiles.list" -> {
                only(args, emptySet())
                val all = profiles()
                JSONObject().put("profiles", JSONArray(all.map(::profileJson))).put("current", all.firstOrNull { it.current }?.id ?: JSONObject.NULL)
            }
            "profiles.cloak" -> {
                only(args, emptySet())
                val all = profiles()
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("profiles", JSONArray(all.map(::profileJson)))
                    .put("current", all.firstOrNull { it.current }?.id ?: JSONObject.NULL)
                    .put("identities", JSONArray(cloakIdentities(all).map(::cloakIdentityJson)))
            }
            "profiles.debug" -> {
                only(args, emptySet())
                debugJson(debugFacts(), labels())
            }
            "profiles.apps" -> {
                only(args, setOf("profileId"))
                val (installed, available) = apps(profileId(args))
                JSONObject().put("apps", JSONArray(installed.take(MAX_APPS).map(::appJson))).put("available", JSONArray(available.take(MAX_APPS).map(::appJson)))
                    .put("truncated", installed.size > MAX_APPS || available.size > MAX_APPS)
            }
            "profiles.switch" -> {
                only(args, setOf("profileId"))
                val id = profileId(args)
                switchTo(id)
                JSONObject().put("switched", true).put("current", id)
            }
            "profiles.app" -> {
                only(args, setOf("profileId", "package", "action"))
                val id = profileId(args)
                if (id == ProfileApps.MAIN) throw GatewayProtocolException("INVALID_REQUEST", "Profile A's apps are managed on the phone.")
                val pkg = args.optString("package")
                if (!PACKAGE.matches(pkg)) throw GatewayProtocolException("INVALID_REQUEST", "package is an Android package name.")
                when (args.optString("action")) {
                    "install" -> install(id, pkg)
                    "remove" -> remove(id, pkg)
                    else -> throw GatewayProtocolException("INVALID_REQUEST", "action is install or remove.")
                }
                JSONObject().put("done", true)
            }
            else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported profiles operation: $op")
        }
    }

    private fun guarded(block: () -> JSONObject): JSONObject = try {
        block()
    } catch (refused: ProfileApps.Refused) {
        throw GatewayProtocolException(refused.code, refused.message ?: "The phone refused.")
    } catch (protocol: GatewayProtocolException) {
        throw protocol
    } catch (failure: Exception) {
        // A root command that failed (no root, Android refused): say so, never leak command text.
        throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", (failure.message ?: "Profile access failed on the phone.").take(200))
    }

    private fun only(args: JSONObject, keys: Set<String>) {
        if (!keys.containsAll(args.keys().asSequence().toSet())) throw GatewayProtocolException("INVALID_REQUEST", "Unexpected arguments.")
    }

    private fun profileId(args: JSONObject): String {
        val id = args.optString("profileId")
        if (!PROFILE_ID.matches(id)) throw GatewayProtocolException("INVALID_REQUEST", "profileId is main or a Cyclone profile id.")
        return id
    }

    private fun profileJson(p: ProfileApps.Profile): JSONObject = JSONObject()
        .put("id", p.id).put("label", p.label.take(40)).put("emoji", p.emoji?.take(8) ?: JSONObject.NULL)
        .put("color", p.color?.let { String.format("#%08X", it) } ?: JSONObject.NULL)
        .put("ready", p.ready).put("current", p.current).put("inTrash", p.inTrash)
        .put("androidUserId", p.androidUserId ?: JSONObject.NULL)

    const val DEBUG_MAX_CHARS = 900_000

    /**
     * Plan 57 P3: the debug file as the phone saves it (already redacted), each profile's health, and the readable
     * summary. Oldest steps go first when it would be too large for one gateway answer.
     */
    internal fun debugJson(facts: com.cyclone.mobile.runtime.workspaces.ProfileDebugReport.Facts, labels: Map<String, String>): JSONObject {
        val report = com.cyclone.mobile.runtime.workspaces.ProfileDebugReport.json(facts)
        var trimmed = 0
        report.optJSONArray("steps")?.let { steps ->
            var size = report.toString().length
            while (size > DEBUG_MAX_CHARS && steps.length() > 0) {
                size -= steps.get(0).toString().length + 1
                steps.remove(0)
                trimmed++
            }
        }
        val health = JSONArray(facts.inventories.map { inv ->
            val ok = inv.missing.isEmpty() && inv.rootProven != false && inv.cloakApproved != false
            JSONObject().put("profileId", inv.profileId).put("label", (labels[inv.profileId] ?: "A profile").take(60))
                .put("line", inv.line().take(300)).put("ok", ok).put("checkedAt", inv.atMs)
        })
        return JSONObject().put("schemaVersion", 1).put("health", health)
            .put("summary", com.cyclone.mobile.runtime.workspaces.ProfileDebugReport.summary(facts).take(20_000))
            .put("report", report).put("trimmedSteps", trimmed)
    }

    private fun cloakIdentityJson(identity: CycloneCloakProfileIdentity): JSONObject = JSONObject()
        .put("profileId", identity.profileId)
        .put("androidUserId", identity.androidUserId)
        .put("identityVersion", identity.identityVersion ?: JSONObject.NULL)
        .put("name", identity.name ?: JSONObject.NULL)
        .put("manufacturer", identity.manufacturer ?: JSONObject.NULL)
        .put("model", identity.model ?: JSONObject.NULL)
        .put("androidRelease", identity.androidRelease ?: JSONObject.NULL)
        .put("sdkInt", identity.sdkInt ?: JSONObject.NULL)
        .put("boundApps", identity.boundApps)
        .put("conflictingApps", identity.conflictingApps)

    private fun appJson(a: ProfileApps.App): JSONObject = JSONObject().put("package", a.packageName).put("label", a.label.take(80))
}
