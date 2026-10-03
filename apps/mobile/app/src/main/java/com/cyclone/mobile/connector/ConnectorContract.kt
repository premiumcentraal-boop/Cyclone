package com.cyclone.mobile.connector

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 51: phone connectors, contract `cyclone.connector/1`.
 *
 * A connector is an app the owner installs next to Cyclone. It talks to Cyclone on this phone over Binder
 * ([ConnectorService]) only after the owner approved it by package and signing certificate. Version 1 reads profiles,
 * keeps its own namespaced data on a profile, contributes its own selector entries and pulls profile events. It can't
 * create, switch, change or remove profiles, and nothing it calls reaches the phone executor, the gateway, the vault,
 * sealed codes, the Mind or Brain (guarded in CI).
 *
 * Everything in this file is pure (no Android), so the rules are unit-tested.
 */
object ConnectorContract {
    const val CONTRACT = "cyclone.connector/1"
    const val MAJOR = 1
    const val MINOR = 0
    /** The action a connector's service declares, so Cyclone can find it. */
    const val ACTION_CONNECT = "com.cyclone.connector.CONNECT"
    /** The action a connector binds to on Cyclone. */
    const val ACTION_SERVICE = "com.cyclone.connector.SERVICE"
    /** The data-free broadcast Cyclone sends a connector's wake receiver when new events are waiting. */
    const val ACTION_WAKE = "com.cyclone.connector.WAKE"
    const val MANIFEST_META = "com.cyclone.connector"

    const val EXT_MAX_BYTES = 4 * 1024
    const val EXT_MAX_KEYS = 32
    const val EXT_MAX_DEPTH = 4
    const val ENTRIES_MAX = 8
    const val REQUEST_MAX_BYTES = 64 * 1024
    const val CALLS_PER_SECOND = 20
}

/** What a connector may do. Wire names are part of the contract. */
enum class ConnectorScope(val wire: String, val plain: String) {
    PROFILES_READ("profiles.read", "See your profiles' names and looks"),
    PROFILES_APPS_READ("profiles.apps.read", "See which apps are in each profile"),
    PROFILES_EXT("profiles.ext", "Keep its own small notes on a profile"),
    SELECTOR_CONTRIBUTE("selector.contribute", "Add its own entries to your profiles list"),
    EVENTS_PROFILES("events.profiles", "Hear when profiles are added, changed, switched or removed");

    companion object {
        fun of(wire: String): ConnectorScope? = values().firstOrNull { it.wire == wire }
    }
}

class ConnectorException(val code: String, message: String) : Exception(message)

/** A connector's static manifest (`res/xml/cyclone_connector.xml`), read by [ConnectorDiscovery]. */
data class ConnectorManifest(
    val packageName: String,
    val id: String,
    val label: String,
    val contractMinor: Int,
    val scopes: Set<ConnectorScope>,
    val unknownScopes: List<String>,
    val entryActivity: String?,
    val wakeReceiver: String?,
) {
    companion object {
        private val ID = Regex("^[a-z][a-z0-9-]{1,40}$")
        private val CLASS = Regex("^(\\.|[A-Za-z_][A-Za-z0-9_]*\\.)([A-Za-z_][A-Za-z0-9_]*\\.)*[A-Za-z_][A-Za-z0-9_$]*$")
        private val CONTRACT_RE = Regex("^cyclone\\.connector/(\\d+)(?:\\.(\\d+))?$")

        /** Parses the manifest's attributes. Throws [ConnectorException] with a plain reason when it can't be used. */
        fun parse(packageName: String, attrs: Map<String, String?>): ConnectorManifest {
            val contract = CONTRACT_RE.matchEntire(attrs["contract"].orEmpty().trim())
                ?: throw ConnectorException("BAD_MANIFEST", "It doesn't say which Cyclone connector contract it uses.")
            if (contract.groupValues[1].toInt() != ConnectorContract.MAJOR) {
                throw ConnectorException("UNSUPPORTED_CONTRACT", "It needs connector contract ${contract.groupValues[1]}; this Cyclone speaks ${ConnectorContract.MAJOR}.")
            }
            val id = attrs["id"].orEmpty().trim()
            if (!ID.matches(id)) throw ConnectorException("BAD_MANIFEST", "Its id must be lowercase letters, digits and dashes.")
            val label = attrs["label"].orEmpty().trim().replace(Regex("\\s+"), " ")
            if (label.isEmpty() || label.length > 40) throw ConnectorException("BAD_MANIFEST", "Its name must be 1-40 characters.")
            val words = attrs["scopes"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
            val scopes = words.mapNotNull(ConnectorScope::of).toSet()
            fun component(key: String): String? = attrs[key]?.trim()?.takeIf { it.isNotEmpty() }?.also {
                if (!CLASS.matches(it)) throw ConnectorException("BAD_MANIFEST", "Its $key isn't a class name.")
            }
            return ConnectorManifest(
                packageName, id, label, contract.groupValues[2].toIntOrNull() ?: 0, scopes,
                words.filter { ConnectorScope.of(it) == null }, component("entryActivity"), component("wakeReceiver"),
            )
        }

        /** `.Entry` → `com.acme.app.Entry`. */
        fun qualify(packageName: String, className: String): String =
            if (className.startsWith(".")) packageName + className else className
    }
}

/** An approval the owner gave in Settings → Connectors. */
data class ConnectorApproval(
    val connectorId: String,
    val packageName: String,
    /** SHA-256 (lower-case hex) of the signing certificate the owner approved. */
    val certSha256: String,
    val scopes: Set<ConnectorScope>,
    val label: String,
    val approvedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("connectorId", connectorId).put("package", packageName).put("cert", certSha256)
        .put("scopes", JSONArray(scopes.map { it.wire }.sorted())).put("label", label).put("approvedAt", approvedAt)

    companion object {
        fun fromJson(o: JSONObject): ConnectorApproval? = runCatching {
            val scopes = o.getJSONArray("scopes")
            ConnectorApproval(
                o.getString("connectorId"), o.getString("package"), o.getString("cert"),
                (0 until scopes.length()).mapNotNull { ConnectorScope.of(scopes.getString(it)) }.toSet(),
                o.getString("label"), o.getLong("approvedAt"),
            )
        }.getOrNull()
    }
}

/**
 * Who is calling, from Binder (kernel-provided UID) and the package manager: never from anything the caller says.
 * [certHistory] is the signing-certificate lineage, current first, so a key rotation keeps an approval.
 */
data class ConnectorCaller(val uid: Int, val packageName: String, val certHistory: List<String>, val manifest: ConnectorManifest?)

object ConnectorIdentity {
    /** The approval that covers this caller, or null. Package, connector id and an approved certificate must all match. */
    fun approvalFor(caller: ConnectorCaller, approvals: List<ConnectorApproval>): ConnectorApproval? {
        val manifest = caller.manifest ?: return null
        return approvals.firstOrNull {
            it.packageName == caller.packageName && it.connectorId == manifest.id && it.certSha256 in caller.certHistory
        }
    }

    /** What the caller may use now: approved and still asked for by its current manifest. */
    fun granted(caller: ConnectorCaller, approval: ConnectorApproval?): Set<ConnectorScope> =
        if (approval == null || caller.manifest == null) emptySet() else approval.scopes intersect caller.manifest.scopes

    /** Scopes the current manifest asks for that the owner hasn't approved yet (after an update). */
    fun pending(caller: ConnectorCaller, approval: ConnectorApproval?): Set<ConnectorScope> =
        caller.manifest?.scopes.orEmpty() - approval?.scopes.orEmpty()

    fun fingerprint(sha256: String): String = sha256.uppercase().take(32).chunked(4).joinToString(" ")
}

/** A token bucket per caller UID: [ConnectorContract.CALLS_PER_SECOND] calls a second, bursts up to the same. */
class ConnectorRateLimiter(private val perSecond: Int = ConnectorContract.CALLS_PER_SECOND, private val clock: () -> Long = System::currentTimeMillis) {
    private val buckets = HashMap<Int, Pair<Double, Long>>()

    @Synchronized fun allow(uid: Int): Boolean {
        val now = clock()
        val (tokens, at) = buckets[uid] ?: (perSecond.toDouble() to now)
        val refilled = minOf(perSecond.toDouble(), tokens + (now - at) * perSecond / 1000.0)
        if (refilled < 1.0) {
            buckets[uid] = refilled to now
            return false
        }
        buckets[uid] = (refilled - 1.0) to now
        return true
    }
}
