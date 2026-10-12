package com.cyclone.mobile.connector

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.XmlResourceParser
import android.os.Binder
import android.os.IBinder
import com.cyclone.connector.ICycloneConnector
import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.security.MessageDigest

/** A connector app found on this phone, and what the owner sees about it in Settings → Connectors. */
data class DiscoveredConnector(
    val packageName: String,
    val appLabel: String,
    val manifest: ConnectorManifest?,
    val problem: String?,
    val certHistory: List<String>,
    val approval: ConnectorApproval?,
    /** An approval exists for this package and id, but the app is now signed by a key outside the approved lineage. */
    val signerChanged: Boolean,
    /** Another installed app already uses this connector id. */
    val idConflict: Boolean,
)

/**
 * Plan 51: the Android side of connectors. Approvals, entries and the event journal live in Cyclone's private
 * preferences; profile data (`ext`) lives in the profile registry. Nothing here logs request or answer bodies.
 */
object ConnectorRuntime {
    private const val PREFS = "cyclone_connectors"
    private val limiter = ConnectorRateLimiter()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- approvals ---------------------------------------------------------------------------------------------------

    @Synchronized fun approvals(context: Context): List<ConnectorApproval> {
        val array = JSONArray(prefs(context).getString("approvals", "[]"))
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(ConnectorApproval::fromJson) }
    }

    @Synchronized private fun saveApprovals(context: Context, list: List<ConnectorApproval>) {
        check(prefs(context).edit().putString("approvals", JSONArray(list.map { it.toJson() }).toString()).commit()) {
            "Couldn't save the connector approval."
        }
    }

    /** The owner approved [found] for every scope its manifest asks for, at its current signing key. */
    @Synchronized fun approve(context: Context, found: DiscoveredConnector) {
        val manifest = checkNotNull(found.manifest) { found.problem ?: "This connector can't be used." }
        check(!found.idConflict) { "Another app already uses the connector id ${manifest.id}." }
        val cert = checkNotNull(found.certHistory.firstOrNull()) { "Android didn't report this app's signing key." }
        val others = approvals(context).filterNot { it.packageName == found.packageName || it.connectorId == manifest.id }
        saveApprovals(context, others + ConnectorApproval(manifest.id, found.packageName, cert, manifest.scopes, manifest.label, System.currentTimeMillis()))
        setRevoked(context, manifest.id, null)
    }

    // ---- plan 57 P3: Cloak's approval follows the owner into every profile (ConnectorCarry) --------------------------

    @Synchronized private fun revokedAt(context: Context, connectorId: String): Long? =
        JSONObject(prefs(context).getString("revoked", "{}")).optLong(connectorId, -1L).takeIf { it >= 0 }

    @Synchronized private fun setRevoked(context: Context, connectorId: String, at: Long?) {
        val all = JSONObject(prefs(context).getString("revoked", "{}"))
        if (at == null) all.remove(connectorId) else all.put(connectorId, at)
        prefs(context).edit().putString("revoked", all.toString()).apply()
    }

    /** The approvals that travel with a carry (Cloak's only). */
    fun outgoing(context: Context): List<ConnectorApproval> = ConnectorCarry.outgoing(approvals(context))

    /** Takes in carried approvals after re-verifying each against the app installed in this profile. */
    @Synchronized fun adoptCarried(context: Context, carried: List<ConnectorApproval>): Map<String, ConnectorCarry.Outcome> {
        val wanted = carried.filter { it.connectorId in ConnectorCarry.CARRIED }
        if (wanted.isEmpty()) return emptyMap()
        val found = ConnectorDiscovery.discover(context)
        val outcomes = linkedMapOf<String, ConnectorCarry.Outcome>()
        wanted.forEach { approval ->
            val here = found.firstOrNull { it.packageName == approval.packageName }
                ?.let { ConnectorCarry.Here(it.packageName, it.manifest, it.certHistory, it.idConflict) }
            val local = approvals(context).firstOrNull { it.connectorId == approval.connectorId && it.packageName == approval.packageName }
            val outcome = ConnectorCarry.decide(approval, here, local, revokedAt(context, approval.connectorId))
            if (outcome == ConnectorCarry.Outcome.ADOPTED && here != null) {
                val others = approvals(context).filterNot { it.packageName == approval.packageName || it.connectorId == approval.connectorId }
                saveApprovals(context, others + ConnectorCarry.adopted(approval, here))
            }
            outcomes[approval.connectorId] = outcome
        }
        return outcomes
    }

    /**
     * Revoking forgets the approval, the connector's entries and its data on every profile. Plan 57 P3: only the owner's
     * own revoke ([byOwner]) is remembered, so a carried approval doesn't undo it; an uninstall isn't a decision.
     */
    @Synchronized fun revoke(context: Context, connectorId: String, byOwner: Boolean = true) {
        saveApprovals(context, approvals(context).filterNot { it.connectorId == connectorId })
        if (byOwner) setRevoked(context, connectorId, System.currentTimeMillis())
        ProfileConfigStore.revoke(context, connectorId)
        ProfileBehaviorRuntime.revoke(connectorId)
        setEntries(context, connectorId, emptyList())
        runCatching { ProfileRegistryStore.dropExt(context, connectorId) }
    }

    @Synchronized fun revokePackage(context: Context, packageName: String) {
        approvals(context).filter { it.packageName == packageName }.forEach { revoke(context, it.connectorId, byOwner = false) }
    }

    // ---- entries -----------------------------------------------------------------------------------------------------

    @Synchronized fun entries(context: Context, connectorId: String): List<ConnectorEntry> {
        val all = JSONObject(prefs(context).getString("entries", "{}"))
        val array = all.optJSONArray(connectorId) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(ConnectorEntry::fromJson) }
    }

    /** Every approved connector's entries, for the selector (plan 51 K3). */
    @Synchronized fun allEntries(context: Context): Map<ConnectorApproval, List<ConnectorEntry>> =
        approvals(context).associateWith { entries(context, it.connectorId) }.filterValues { it.isNotEmpty() }

    @Synchronized fun setEntries(context: Context, connectorId: String, entries: List<ConnectorEntry>) {
        val all = JSONObject(prefs(context).getString("entries", "{}"))
        if (entries.isEmpty()) all.remove(connectorId) else all.put(connectorId, JSONArray(entries.map { it.toJson() }))
        check(prefs(context).edit().putString("entries", all.toString()).commit()) { "Couldn't save the connector's entries." }
    }

    // ---- the event journal -------------------------------------------------------------------------------------------

    @Synchronized internal fun journal(context: Context): ConnectorJournal {
        val array = JSONArray(prefs(context).getString("journal", "[]"))
        val events = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(ConnectorEvent::fromJson) }
        return ConnectorJournal(events, prefs(context).getLong("journal_next", 1L))
    }

    @Synchronized internal fun record(context: Context, items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val journal = journal(context)
        val now = System.currentTimeMillis()
        items.forEach { (type, id) -> journal.append(type, id, now) }
        prefs(context).edit()
            .putString("journal", JSONArray(journal.events.map { it.toJson() }).toString())
            .putLong("journal_next", journal.nextSeq)
            .apply()
        wakeSoon(context.applicationContext)
    }

    // ---- wakes (plan 51 K3) ------------------------------------------------------------------------------------------

    private val wakeHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var wakePending = false

    /** One data-free wake per burst of events, half a second after the first. The connector then pulls `events`. */
    private fun wakeSoon(context: Context) {
        if (wakePending) return
        wakePending = true
        wakeHandler.postDelayed({
            wakePending = false
            runCatching { wake(context) }
        }, 500)
    }

    /** Explicit broadcasts to approved connectors with `events.profiles` that declare a wake receiver. No payload. */
    fun wake(context: Context): Int {
        var sent = 0
        ConnectorDiscovery.discover(context)
            .filter { it.approval != null && it.manifest != null && !it.idConflict && it.manifest.wakeReceiver != null &&
                ConnectorScope.EVENTS_PROFILES in it.approval.scopes && ConnectorScope.EVENTS_PROFILES in it.manifest.scopes }
            .forEach { c ->
                val receiver = ConnectorManifest.qualify(c.packageName, c.manifest!!.wakeReceiver!!)
                runCatching {
                    context.sendBroadcast(Intent(ConnectorContract.ACTION_WAKE)
                        .setComponent(android.content.ComponentName(c.packageName, receiver))
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
                    sent++
                }
            }
        return sent
    }

    // ---- calls -------------------------------------------------------------------------------------------------------

    fun backend(context: Context): ConnectorBackend = object : ConnectorBackend {
        override fun approvals() = ConnectorRuntime.approvals(context)
        override fun profiles(): List<CycloneProfileRecord> = ProfileRegistryStore.records(context)
        override fun setExt(profileId: String, connectorId: String, json: String?) = ProfileRegistryStore.setExt(context, profileId, connectorId, json)
        override fun entries(connectorId: String) = ConnectorRuntime.entries(context, connectorId)
        override fun setEntries(connectorId: String, entries: List<ConnectorEntry>) = ConnectorRuntime.setEntries(context, connectorId, entries)
        override fun events(since: Long, now: Long) = journal(context).since(since, now)
        override fun startupStatus(uid: Int, key: ProfileConfigKey) = ProfileBehaviorRuntime.status(uid, key)
        override fun config(connectorId: String, callerUser: Int, key: ProfileConfigKey) = ProfileConfigStore.get(context, connectorId, callerUser, key)
        override fun updateConfig(connectorId: String, callerUser: Int, key: ProfileConfigKey, transform: (String?) -> String) = ProfileConfigStore.update(context, connectorId, callerUser, key, transform)
        override fun setConfig(connectorId: String, callerUser: Int, key: ProfileConfigKey, json: String?) = ProfileConfigStore.set(context, connectorId, callerUser, key, json)
        override fun now() = System.currentTimeMillis()
        override fun currentProfile(): String? = current(context)
        override fun requestOpen(connectorId: String, connectorLabel: String, profileId: String, profileLabel: String): String? =
            com.cyclone.mobile.runtime.workspaces.ProfileOpenRequests.ask(context, connectorId, connectorLabel,
                if (profileId == ConnectorEvent.OWNER) com.cyclone.mobile.runtime.workspaces.ProfileApps.MAIN else profileId, profileLabel)
        override fun rootStatus(): JSONObject = ConnectorRootStatus.build(
            runCatching { com.cyclone.mobile.runtime.workspaces.ProfileInventoryStore.all(context) }.getOrDefault(emptyList()),
            runCatching { com.cyclone.mobile.runtime.workspaces.ProfileRoom.cached(context) }.getOrNull(),
        )
    }

    /**
     * Which profile is in front, without a privileged shell: when this Cyclone's own Android user is in front, that one;
     * otherwise the profile Cyclone last switched to, or null when it can't tell (someone switched outside Cyclone).
     */
    fun current(context: Context): String? = runCatching {
        val myUser = android.os.Process.myUid() / 100_000
        val records = ProfileRegistryStore.records(context)
        if (context.getSystemService(android.os.UserManager::class.java).isUserForeground) {
            records.firstOrNull { it.androidUserId == myUser }?.id ?: ConnectorEvent.OWNER
        } else {
            journal(context).events.lastOrNull { it.type == ConnectorEvent.SWITCHED }?.profileId
                ?.takeIf { it != ConnectorEvent.OWNER && records.any { r -> r.id == it } }
        }
    }.getOrNull()

    /** For the PC (`connectors.list`): approved connectors that still match their app, with their entries. */
    fun report(context: Context): JSONObject = ConnectorReport.build(
        ConnectorDiscovery.discover(context).mapNotNull { c -> c.approval?.takeIf { !c.idConflict && c.manifest != null }?.let { it to entries(context, it.connectorId) } },
    )

    fun call(context: Context, uid: Int, request: String?): String =
        ConnectorCore(backend(context), limiter).handle(ConnectorDiscovery.caller(context, uid), request)
}

/** Profile events from the registry and from switches (plan 51 §3.3). */
object ConnectorEvents {
    fun changed(context: Context, before: List<CycloneProfileRecord>, after: List<CycloneProfileRecord>) {
        // Plan 57 P3: a profile under a new Android user id keeps its connector settings; then only gone profiles lose them.
        ProfileConfigLifecycle.moves(before, after).forEach { (id, from, to) -> runCatching { ProfileConfigStore.migrate(context, id, from, to) } }
        ProfileConfigStore.removed(context, after)
        ConnectorRuntime.record(context, ConnectorEvent.diff(before, after))
    }

    fun switched(context: Context, androidUserId: Int) {
        ProfileBehaviorRuntime.switched(androidUserId)
        val id = ProfileRegistryStore.records(context).firstOrNull { it.androidUserId == androidUserId }?.id ?: ConnectorEvent.OWNER
        ConnectorRuntime.record(context, listOf(ConnectorEvent.SWITCHED to id))
    }
}

/** Finds connector apps and tells who a Binder caller is. */
object ConnectorDiscovery {
    fun discover(context: Context): List<DiscoveredConnector> {
        val pm = context.packageManager
        val approvals = ConnectorRuntime.approvals(context)
        val services = pm.queryIntentServices(Intent(ConnectorContract.ACTION_CONNECT),
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
        val found = services.map { service ->
            val (manifest, problem) = runCatching { read(context, service) to null }
                .getOrElse { (null to ((it as? ConnectorException)?.message ?: "Its connector manifest couldn't be read.")) }
            val certs = certHistory(context, service.packageName)
            val approval = manifest?.let { m -> approvals.firstOrNull { it.packageName == service.packageName && it.connectorId == m.id } }
            DiscoveredConnector(
                service.packageName,
                runCatching { service.applicationInfo.loadLabel(pm).toString() }.getOrDefault(service.packageName),
                manifest, problem, certs,
                approval?.takeIf { it.certSha256 in certs },
                signerChanged = approval != null && approval.certSha256 !in certs,
                idConflict = false,
            )
        }
        val ids = found.mapNotNull { it.manifest?.id }
        val duplicated = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        // Approvals for apps that are gone are forgotten (the uninstall receiver is the first line; this is the second).
        approvals.filter { a -> found.none { it.packageName == a.packageName } }.forEach { ConnectorRuntime.revoke(context, it.connectorId, byOwner = false) }
        return found.map { it.copy(idConflict = it.manifest?.id in duplicated) }.sortedBy { it.appLabel.lowercase() }
    }

    /** The caller behind a Binder UID, or null when it isn't exactly one installed connector app. */
    fun caller(context: Context, uid: Int): ConnectorCaller? {
        if (uid / 100_000 != android.os.Process.myUid() / 100_000) return null
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
        if (packages.size != 1) return null // shared user ids are refused
        val packageName = packages.single()
        val service = pm.queryIntentServices(Intent(ConnectorContract.ACTION_CONNECT).setPackage(packageName),
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())).firstOrNull()?.serviceInfo
        val manifest = service?.let { runCatching { read(context, it) }.getOrNull() }
        return ConnectorCaller(uid, packageName, certHistory(context, packageName), manifest)
    }

    private fun read(context: Context, service: ServiceInfo): ConnectorManifest {
        val pm = context.packageManager
        val parser: XmlResourceParser = service.loadXmlMetaData(pm, ConnectorContract.MANIFEST_META)
            ?: throw ConnectorException("BAD_MANIFEST", "It doesn't include a Cyclone connector manifest.")
        parser.use { xml ->
            val resources = pm.getResourcesForApplication(service.applicationInfo)
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG || xml.name != "cyclone-connector") continue
                val attrs = (0 until xml.attributeCount).associate { i ->
                    val raw = xml.getAttributeValue(i)
                    val ref = xml.getAttributeResourceValue(i, 0)
                    xml.getAttributeName(i) to if (ref != 0) runCatching { resources.getString(ref) }.getOrNull() else raw
                }
                return ConnectorManifest.parse(service.packageName, attrs)
            }
        }
        throw ConnectorException("BAD_MANIFEST", "Its connector manifest has no <cyclone-connector> element.")
    }

    /** SHA-256 of the signing certificates, current first, then the earlier keys in its rotation lineage. */
    fun certHistory(context: Context, packageName: String): List<String> = runCatching {
        val info = context.packageManager.getPackageInfo(packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signing = info.signingInfo ?: return emptyList()
        val digest = { bytes: ByteArray -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        if (signing.hasMultipleSigners()) {
            // Several signers have no lineage: the set of them, together, is the identity.
            listOf(digest(signing.apkContentsSigners.map { digest(it.toByteArray()) }.sorted().joinToString(",").toByteArray()))
        } else {
            signing.signingCertificateHistory.map { digest(it.toByteArray()) }.reversed()
        }
    }.getOrDefault(emptyList())
}

/** The Binder door. Exported, but every call is checked: who (from the kernel), approved by the owner, which scope. */
class ConnectorService : Service() {
    private val binder = object : ICycloneConnector.Stub() {
        override fun registerProfileProvider(request: String?, provider: com.cyclone.connector.IProfileBehaviorProvider?): String {
            val uid = Binder.getCallingUid()
            val token = Binder.clearCallingIdentity()
            return try { ProfileBehaviorRuntime.register(applicationContext, uid, request, provider) }
            finally { Binder.restoreCallingIdentity(token) }
        }
        override fun call(request: String?): String {
            val uid = Binder.getCallingUid()
            val token = Binder.clearCallingIdentity()
            return try {
                ConnectorRuntime.call(applicationContext, uid, request)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/** An uninstalled connector loses its approval, entries and profile data at once. */
class ConnectorPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        runCatching { ConnectorRuntime.revokePackage(context.applicationContext, packageName) }
    }
}

/** A connector entry as the profile selector shows it (plan 51 K3). */
data class SelectorEntry(
    val connectorId: String,
    val connectorLabel: String,
    val packageName: String,
    val entry: ConnectorEntry,
) {
    val key: String get() = "connector:$connectorId:${entry.id}"
}

/** Shows approved connectors' entries and opens the connector's own screen from one. */
object ConnectorLauncher {
    const val EXTRA_ENTRY_ID = "com.cyclone.connector.ENTRY_ID"

    /** Entries of connectors that are approved for `selector.contribute` now (current manifest, current key). */
    fun selectorEntries(context: Context): List<SelectorEntry> = runCatching {
        ConnectorDiscovery.discover(context)
            .filter { it.approval != null && !it.idConflict && ConnectorScope.SELECTOR_CONTRIBUTE in it.approval.scopes &&
                it.manifest != null && ConnectorScope.SELECTOR_CONTRIBUTE in it.manifest.scopes }
            .flatMap { c -> ConnectorRuntime.entries(context, c.manifest!!.id).map { SelectorEntry(c.manifest.id, c.manifest.label, c.packageName, it) } }
    }.getOrDefault(emptyList())

    /**
     * Opens the connector's declared entry activity with only the entry id. The activity must be exported and belong to
     * the connector's own package; Cyclone never opens an intent or address a connector supplies.
     */
    fun open(context: Context, item: SelectorEntry): Boolean = runCatching {
        val found = ConnectorDiscovery.discover(context).firstOrNull { it.packageName == item.packageName && it.manifest?.id == item.connectorId }
        val manifest = found?.manifest ?: return false
        if (found.approval == null) return false
        val activity = manifest.entryActivity ?: return false
        val component = android.content.ComponentName(item.packageName, ConnectorManifest.qualify(item.packageName, activity))
        val info = context.packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        if (!info.exported || info.packageName != item.packageName) return false
        context.startActivity(Intent().setComponent(component).putExtra(EXTRA_ENTRY_ID, item.entry.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** The entry's icon from the connector's own resources, or null (the selector then shows the connector's app icon). */
    fun icon(context: Context, item: SelectorEntry): android.graphics.drawable.Drawable? = runCatching {
        val name = item.entry.icon ?: return null
        val res = context.packageManager.getResourcesForApplication(item.packageName)
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "drawable", item.packageName)
        if (id == 0) null else res.getDrawable(id, null)
    }.getOrNull()
}
