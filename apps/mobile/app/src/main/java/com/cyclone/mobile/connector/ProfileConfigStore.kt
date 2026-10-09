package com.cyclone.mobile.connector

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Private, non-backed-up Binder config provider. Each connector installation has its own namespace. */
object ProfileConfigStore {
    private fun root(context: Context) = File(context.noBackupFilesDir, "connector-config-v1")
    private fun namespace(context: Context, connector: String, user: Int): File {
        require(Regex("^[a-z][a-z0-9-]{1,40}$").matches(connector) && user >= 0)
        return File(root(context), "$user/$connector")
    }
    @Synchronized fun get(context: Context, connector: String, user: Int, key: ProfileConfigKey): String? {
        val file = AtomicFile(File(namespace(context, connector, user), key.storageKey() + ".json"))
        return try { file.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }
        catch (_: java.io.FileNotFoundException) { null }
    }
    @Synchronized fun update(context: Context, connector: String, user: Int, key: ProfileConfigKey, transform: (String?) -> String): String {
        val updated = transform(get(context, connector, user, key))
        set(context, connector, user, key, updated)
        return updated
    }
    @Synchronized fun set(context: Context, connector: String, user: Int, key: ProfileConfigKey, json: String?) {
        val dir = namespace(context, connector, user)
        val file = AtomicFile(File(dir, key.storageKey() + ".json"))
        if (json == null) { file.delete(); return }
        check(dir.isDirectory || dir.mkdirs())
        if (!file.baseFile.exists() && dir.listFiles().orEmpty().count { it.extension == "json" } >= 256) {
            throw ConnectorException("BAD_REQUEST", "At most 256 config tuples per connector installation.")
        }
        val out = file.startWrite()
        try { out.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    @Synchronized fun revoke(context: Context, connector: String) {
        root(context).listFiles().orEmpty().forEach { File(it, connector).deleteRecursively() }
    }
    /** Plan 57 P3: tuples go only with their profile (permanently deleted), never with an outdated app list. */
    @Synchronized fun removed(context: Context, profiles: List<com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord>) {
        // Files use hashes, so reconcile by the tuple envelope.
        root(context).walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            if (!ProfileConfigLifecycle.keep(text, profiles)) AtomicFile(file).delete()
        }
    }

    /** Plan 57 P3: removes tuples of apps Android says are gone from that profile. Returns how many went. */
    @Synchronized fun prune(context: Context, profileId: String, androidUserId: Int, installed: Set<String>): Int {
        var removed = 0
        root(context).walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            if (ProfileConfigLifecycle.stale(text, profileId, androidUserId, installed)) { AtomicFile(file).delete(); removed++ }
        }
        return removed
    }

    /** Plan 57 P3: a profile now under another Android user id keeps its tuples; each is rewritten and read back. */
    @Synchronized fun migrate(context: Context, profileId: String, from: Int, to: Int): Int {
        var moved = 0
        root(context).walkTopDown().filter { it.isFile && it.extension == "json" }.toList().forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val (key, rewritten) = ProfileConfigLifecycle.moved(text, profileId, from, to) ?: return@forEach
            val target = AtomicFile(File(file.parentFile, key.storageKey() + ".json"))
            val out = target.startWrite()
            try { out.write(rewritten.toByteArray(Charsets.UTF_8)); target.finishWrite(out) } catch (e: Exception) { target.failWrite(out); throw e }
            if (target.openRead().use { it.readBytes().toString(Charsets.UTF_8) } == rewritten) { AtomicFile(file).delete(); moved++ }
        }
        return moved
    }
}
