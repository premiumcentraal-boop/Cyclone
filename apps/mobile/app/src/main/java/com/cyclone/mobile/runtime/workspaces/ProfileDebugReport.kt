package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Plan 57 W8: the profile debug file. One zip with `debug.json` (everything Cyclone knows about profiles on this
 * phone and every privileged step with Android's own words) and `summary.txt` (the same, readable).
 *
 * Never in it: keys, tokens, OTPs, passwords, the vault, chats, memories' text, app data or a connector's own data
 * (`ext` values). Every free text passes [ProfileDebugRedaction]. Nothing is sent anywhere: the owner saves or shares
 * the file.
 */
/** Plan 57 W8: shares only `cache/profile-debug/` (the debug file), under its own authority. */
class ProfileDebugFileProvider : androidx.core.content.FileProvider()

object ProfileDebugReport {
    const val AUTHORITY_SUFFIX = ".profile-debug"
    const val SCHEMA = "cyclone.profile-debug/1"

    data class Facts(
        val atMs: Long,
        val app: Map<String, Any?>,
        val rootManager: String?,
        val room: ProfileCapacity.Room?,
        val roomStatus: ProfileRoom.Status?,
        val usersRaw: String?,
        val records: List<CycloneProfileRecord>,
        val journal: Map<String, Any?>,
        val steps: List<ProfileStep>,
        val connectors: List<Map<String, Any?>>,
        val carry: CarryReport?,
        val failure: ProfileSetupFailure?,
        val problems: List<String> = emptyList(),
    )

    // Pure ---------------------------------------------------------------------------------------------------------

    fun json(f: Facts): JSONObject {
        val out = JSONObject().put("schema", SCHEMA).put("at", iso(f.atMs))
        out.put("app", JSONObject(f.app.mapValues { (_, v) -> v ?: JSONObject.NULL }))
        out.put("root", JSONObject().put("manager", f.rootManager ?: JSONObject.NULL).apply {
            f.roomStatus?.let { s ->
                put("fw.max_users", s.property ?: JSONObject.NULL).put("androidLimit", s.androidLimit ?: JSONObject.NULL)
                    .put("moduleLimit", s.moduleLimit ?: JSONObject.NULL).put("moduleDisabled", s.moduleDisabled)
                    .put("raisedByCyclone", s.raisedByCyclone).put("needsApplyAgain", s.needsApplyAgain)
            }
        })
        out.put("users", JSONObject().apply {
            put("raw", f.usersRaw?.let { ProfileDebugRedaction.text(it, 16_384) } ?: JSONObject.NULL)
            f.room?.let { room ->
                put("limit", room.limit ?: JSONObject.NULL).put("used", room.used).put("free", room.free ?: JSONObject.NULL)
                    .put("verdict", room.verdict.name).put("fullSecondary", room.fullSecondary)
                    .put("secondaryType", room.secondaryType?.let {
                        JSONObject().put("type", it.type).put("enabled", it.enabled ?: JSONObject.NULL).put("maxAllowed", it.maxAllowed ?: JSONObject.NULL)
                    } ?: JSONObject.NULL)
                    .put("slots", JSONArray(room.slots.map {
                        JSONObject().put("user", it.userId).put("label", clean(it.label, 60)).put("kind", it.kind.name).put("counted", it.counted)
                    }))
            }
        })
        out.put("registry", JSONArray(f.records.map { r ->
            JSONObject().put("id", r.id).put("label", clean(r.label, 60)).put("user", r.androidUserId ?: JSONObject.NULL)
                .put("parent", r.parentUserId).put("secondary", r.secondaryUser).put("stage", r.stage).put("ready", r.ready)
                .put("inTrash", r.inTrash).put("apps", r.packages.size)
                // A connector's own data is never copied: only which connectors keep some.
                .put("extConnectors", JSONArray(r.ext.keys.sorted()))
        }))
        out.put("journal", JSONObject(f.journal.mapValues { (_, v) -> if (v is String) clean(v, 80) else v ?: JSONObject.NULL }))
        // Redacted again here, whatever the source: the file must never carry a secret.
        out.put("steps", JSONArray(f.steps.map {
            it.copy(command = ProfileDebugRedaction.text(it.command, 400), output = ProfileDebugRedaction.text(it.output, ProfileStepJournal.OUTPUT_LIMIT)).toJson()
        }))
        out.put("connectors", JSONArray(f.connectors.map { JSONObject(it.mapValues { (_, v) -> v ?: JSONObject.NULL }) }))
        out.put("carry", f.carry?.toJson() ?: JSONObject.NULL)
        out.put("error", f.failure?.let { e ->
            JSONObject().put("kind", e.kind.name).put("headline", e.headline).put("reason", e.reason).put("action", e.action)
                .put("android", e.platformMessage?.let { ProfileDebugRedaction.text(it, 400) } ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        out.put("problems", JSONArray(f.problems.map { clean(it, 200) }))
        return out
    }

    fun summary(f: Facts): String = buildString {
        appendLine("Cyclone profile debug file · ${iso(f.atMs)}")
        appendLine("Cyclone ${f.app["versionName"]} (${f.app["versionCode"]}) · ${f.app["manufacturer"]} ${f.app["model"]} · Android ${f.app["release"]} (SDK ${f.app["sdk"]})")
        appendLine()
        f.failure?.let { e ->
            appendLine("Problem: ${e.headline}")
            appendLine("  ${e.reason}")
            e.platformMessage?.let { appendLine("  Android said: ${ProfileDebugRedaction.text(it, 400)}") }
            appendLine()
        }
        f.room?.let { room ->
            appendLine("Room: ${room.used} of ${room.limit ?: "?"} places in use · ${room.verdict.name}")
            appendLine("  ${room.line()}")
            room.secondaryType?.let { appendLine("  Full secondary users: ${room.fullSecondary}; type limit ${it.maxAllowed ?: "?"}, enabled ${it.enabled ?: "?"}") }
        }
        appendLine("Root manager: ${f.rootManager ?: "not found"}")
        f.roomStatus?.let { s ->
            appendLine("  fw.max_users ${s.property ?: "unset"} · Android limit ${s.androidLimit ?: "?"} · Cyclone module ${s.moduleLimit ?: "none"}${if (s.moduleDisabled) " (disabled)" else ""}")
        }
        appendLine()
        appendLine("Profiles:")
        if (f.records.isEmpty()) appendLine("  (none)")
        f.records.forEach { r ->
            appendLine("  ${clean(r.label, 40)} · ${r.id} · user ${r.androidUserId ?: "-"} · ${r.stage}${if (r.ready) " · ready" else ""}${if (r.inTrash) " · Recently deleted" else ""}")
        }
        f.problems.forEach { appendLine("Note: ${clean(it, 200)}") }
        appendLine()
        appendLine("Last steps (newest last):")
        f.steps.takeLast(15).forEach { s ->
            val said = s.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(140).orEmpty()
            appendLine("  ${iso(s.atMs)} ${s.operation} exit=${s.exitCode ?: "-"} ${s.ms}ms ${s.verdict ?: "ok"}${if (said.isNotEmpty()) " · $said" else ""}")
        }
    }.let { ProfileDebugRedaction.text(it, 32_768) }

    fun zip(f: Facts): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("debug.json")); zip.write(json(f).toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("summary.txt")); zip.write(summary(f).toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    fun fileName(atMs: Long): String =
        "cyclone-profile-debug-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atMs)) + ".zip"

    private fun clean(text: String, limit: Int) = ProfileDebugRedaction.text(text, limit)

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    // On the phone -----------------------------------------------------------------------------------------------------

    /** Gathers the facts. Every root read is best effort: a debug file must come out even when root is broken. */
    fun collect(context: Context, failure: ProfileSetupFailure? = ProfileSetupRuntime.state.value.issue): Facts {
        val problems = mutableListOf<String>()
        fun <T> attempt(what: String, block: () -> T): T? = runCatching(block).onFailure { problems += "$what: ${it.message}" }.getOrNull()
        val app = linkedMapOf<String, Any?>(
            "package" to context.packageName,
            "versionName" to attempt("version") { context.packageManager.getPackageInfo(context.packageName, 0).versionName },
            "versionCode" to attempt("version code") { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode },
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "device" to Build.DEVICE,
            "release" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT, "securityPatch" to Build.VERSION.SECURITY_PATCH,
            "build" to Build.DISPLAY, "androidUser" to attempt("user") { ProfileSetupRuntime.currentUserId() },
        )
        val usersRaw = attempt("user list") { ProfileSetupRuntime.runRequired(ProfileSetupPlan.listUsers()) }
        val room = attempt("room") { ProfileSetupRuntime.room(context) }
        val roomStatus = attempt("profile room") { ProfileRoom.status(context) }
        val setup = context.getSharedPreferences("cyclone_profile_setup", Context.MODE_PRIVATE)
        val journal = linkedMapOf<String, Any?>(
            "name" to setup.getString("name", null), "label" to setup.getString(ProfileRegistryStore.JOURNAL_DISPLAY_LABEL, null),
            "user" to setup.getInt("user", -1).takeIf { it > 0 }, "parent" to setup.getInt("parent_user", -1).takeIf { it >= 0 },
            "stage" to setup.getString("stage", null), "ready" to setup.getBoolean("ready", false),
            "secondary" to setup.getBoolean("secondary", false), "apps" to setup.getStringSet("plan_apps", emptySet())?.size,
        )
        val connectors = attempt("connectors") {
            com.cyclone.mobile.connector.ConnectorRuntime.approvals(context).map { a ->
                linkedMapOf<String, Any?>("id" to a.connectorId, "package" to a.packageName, "cert" to a.certSha256.take(12),
                    "scopes" to a.scopes.joinToString(",") { it.wire }, "approvedAt" to a.approvedAt)
            }
        }.orEmpty()
        return Facts(
            atMs = System.currentTimeMillis(), app = app, rootManager = roomStatus?.manager?.name, room = room, roomStatus = roomStatus,
            usersRaw = usersRaw, records = attempt("registry") { ProfileRegistryStore.records(context) }.orEmpty(), journal = journal,
            steps = ProfileStepJournal.snapshot(), connectors = connectors, carry = attempt("carry") { ProfileCarry.lastReport(context) },
            failure = failure, problems = problems,
        )
    }
}
