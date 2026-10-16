package com.cyclone.mobile.runtime.safe

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files

/** Inventory-driven capture, including device-protected profile association. Unknown stores abort, never silently skip. */
internal class SafeStores(private val context: Context) {
    private val sealed = SafeSealedStores(context)
    private fun prefsRoot(device: Boolean): File = File(
        if (device) context.createDeviceProtectedStorageContext().applicationInfo.dataDir else context.applicationInfo.dataDir,
        "shared_prefs",
    )
    fun capture(): List<SafeSnapshot.Entry> {
        val result = mutableListOf<SafeSnapshot.Entry>()
        try {
            for (device in listOf(false, true)) {
                prefsRoot(device).listFiles().orEmpty().filter { it.extension == "xml" }.sortedBy { it.name }.forEach { file ->
                    val location = if (device) SafeManifest.Location.DEVICE_PREFS else SafeManifest.Location.PREFS
                    val row = SafeManifest.stores.singleOrNull { it.location == location && it.name == file.nameWithoutExtension }
                        ?: error("SAFE_UNCLASSIFIED_PREFS")
                    if (row.kind == SafeManifest.Kind.SAFE) {
                        check(!Files.isSymbolicLink(file.toPath())) { "SAFE_SYMLINK" }
                        result += SafeSnapshot.Entry("prefs/${if (device) "de" else "ce"}/${file.name}", file.readBytes())
                    }
                }
            }
            val dbs = SafeManifest.stores.filter { it.location == SafeManifest.Location.DATABASE && it.kind == SafeManifest.Kind.SAFE }
            context.databaseList().filterNot { it.endsWith("-wal") || it.endsWith("-shm") || it.endsWith("-journal") }.forEach { name ->
                check(dbs.any { it.name == name }) { "SAFE_UNCLASSIFIED_DATABASE" }
            }
            for (row in dbs) {
                val original = context.getDatabasePath(row.name)
                if (!original.isFile) continue
                val staging = Files.createTempDirectory(context.cacheDir.toPath(), "safe-vacuum-").toFile()
                val target = File(staging, row.name)
                check(!Files.isSymbolicLink(original.toPath())) { "SAFE_DATABASE_STAGING" }
                try {
                    SQLiteDatabase.openDatabase(original.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                        db.execSQL("VACUUM INTO ?", arrayOf(target.path))
                    }
                    result += SafeSnapshot.Entry("databases/${row.name}", target.readBytes())
                } finally { target.delete(); staging.delete() }
            }
            context.filesDir.listFiles().orEmpty().forEach { root ->
                check(SafeManifest.file(root.name) != null) { "SAFE_UNCLASSIFIED_FILES" }
                root.walkTopDown().onEnter { dir ->
                    check(!Files.isSymbolicLink(dir.toPath())) { "SAFE_SYMLINK" }
                    SafeManifest.file(dir.relativeTo(context.filesDir).invariantSeparatorsPath)?.kind != SafeManifest.Kind.NEVER
                }.filter { it.isFile }.forEach { file ->
                    val path = file.relativeTo(context.filesDir).invariantSeparatorsPath
                    val row = SafeManifest.file(path) ?: error("SAFE_UNCLASSIFIED_FILES")
                    check(!Files.isSymbolicLink(file.toPath())) { "SAFE_SYMLINK" }
                    if (row.kind == SafeManifest.Kind.SAFE && !file.name.endsWith(".tmp") && !file.name.endsWith(".part"))
                        result += SafeSnapshot.Entry("files/$path", file.readBytes())
                }
            }
            SafeManifest.stores.filter { it.location == SafeManifest.Location.NO_BACKUP && it.kind == SafeManifest.Kind.SAFE }.forEach { row ->
                File(context.noBackupFilesDir, row.name).walkTopDown().filter { it.isFile }.forEach { file ->
                    check(!Files.isSymbolicLink(file.toPath())) { "SAFE_SYMLINK" }
                    result += SafeSnapshot.Entry("no_backup/${file.relativeTo(context.noBackupFilesDir).invariantSeparatorsPath}", file.readBytes())
                }
            }
            result += sealed.capture()
            return result
        } catch (_: Exception) {
            result.forEach { it.close() }
            throw IllegalStateException("SAFE_CAPTURE_FAILED")
        }
    }

    fun accepts(path: String): Boolean {
        if (!SafeSnapshot.validPath(path)) return false
        return when {
            path.startsWith("sealed/") -> path in setOf("sealed/identities", "sealed/hive", "sealed/provider", "sealed/vault", "sealed/mind", "sealed/trust")
            path.startsWith("files/") -> SafeManifest.file(path.removePrefix("files/"))?.kind == SafeManifest.Kind.SAFE
            path.startsWith("databases/") -> SafeManifest.stores.any { it.location == SafeManifest.Location.DATABASE && it.kind == SafeManifest.Kind.SAFE && "databases/${it.name}" == path }
            path.startsWith("prefs/") -> SafeManifest.stores.any {
                it.kind == SafeManifest.Kind.SAFE &&
                    ((it.location == SafeManifest.Location.PREFS && path == "prefs/ce/${it.name}.xml") ||
                        (it.location == SafeManifest.Location.DEVICE_PREFS && path == "prefs/de/${it.name}.xml"))
            }
            path.startsWith("no_backup/") -> SafeManifest.stores.any { it.location == SafeManifest.Location.NO_BACKUP && it.kind == SafeManifest.Kind.SAFE && path.startsWith("no_backup/${it.name}/") }
            else -> false
        }
    }
    fun restore(input: java.io.InputStream, key: ByteArray): org.json.JSONObject =
        SafeRestore.from(input, key, android.os.Process.myUid() / 100000, ::accepts, ::write)

    private fun write(entry: SafeSnapshot.Entry) {
        if (entry.path.startsWith("sealed/")) sealed.restore(entry) else {
            val path = entry.path
            val target = when {
                path.startsWith("databases/") -> context.getDatabasePath(path.removePrefix("databases/"))
                path.startsWith("files/") -> File(context.filesDir, path.removePrefix("files/"))
                path.startsWith("no_backup/") -> File(context.noBackupFilesDir, path.removePrefix("no_backup/"))
                path.startsWith("prefs/de/") -> File(prefsRoot(true), path.removePrefix("prefs/de/"))
                else -> File(prefsRoot(false), path.removePrefix("prefs/ce/"))
            }
            if (path.startsWith("databases/")) {
                // The startup coordinator must run before any database is opened. Never discard live WAL pages.
                check(listOf("-wal", "-shm", "-journal").none { File(target.path + it).exists() }) { "SAFE_DATABASE_IN_USE" }
            }
            // Do not follow an existing parent symlink during restore.
            var parent: File? = target
            while (parent != null) { check(!Files.isSymbolicLink(parent.toPath())) { "SAFE_SYMLINK" }; parent = parent.parentFile }
            SafeFiles.atomic(target, entry.bytes)
        }
    }
}
