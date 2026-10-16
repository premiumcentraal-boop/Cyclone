package com.cyclone.mobile.runtime.safe

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.UserManager
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Target processes open their own sealed stores. Only Main commits shared root snapshots. */
internal object SafeProfiles {
    fun restore(context: Context): List<Int> {
        val root = SafeRootStore()
        return ProfileRegistryStore.records(context).mapNotNull { it.androidUserId }.filter { it > 0 }.distinct().sorted().filter { user ->
            runCatching {
                // A removed Android user is never recreated or repurposed. Keep its root copies untouched.
                root.installProfile(user)
                if (!root.profileReady(user)) root.startProfile(user)
                check(root.profileReady(user)) { "SAFE_PROFILE_LOCKED" }
                val seq = root.list(user).maxOrNull() ?: error("SAFE_NO_COPY")
                val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
                root.profileStart(user, nonce, seq, restore = true)
                val deadline = android.os.SystemClock.elapsedRealtime() + 120000
                var reply: JSONObject? = null
                while (reply == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    reply = runCatching { root.profileStatus(user, nonce) }.getOrNull()
                    if (reply == null) Thread.sleep(250)
                }
                check(reply != null && reply.getBoolean("ok") && reply.getInt("user") == user && reply.getString("nonce") == nonce)
                root.profileClean(user, nonce)
            }.isFailure
        }
    }
    fun capture(context: Context, safe: ByteArray, phoneId: String): List<Int> {
        val root = SafeRootStore()
        val missed = mutableListOf<Int>()
        val users = ProfileRegistryStore.records(context).mapNotNull { it.androidUserId }.filter { it > 0 }.distinct().sorted()
        for (user in users) {
            var nonce: String? = null
            val file = File.createTempFile("safe-profile-", ".cyc", context.cacheDir)
            try {
                check(root.profileReady(user)) { "SAFE_PROFILE_LOCKED" }
                val seq = maxOf(System.currentTimeMillis(), (root.list(user).maxOrNull() ?: 0) + 1)
                nonce = java.util.UUID.randomUUID().toString().replace("-", "")
                root.profileStart(user, nonce, seq)
                val deadline = android.os.SystemClock.elapsedRealtime() + 120000
                var reply: JSONObject? = null
                while (reply == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    reply = runCatching { root.profileStatus(user, nonce) }.getOrNull()
                    if (reply == null) Thread.sleep(250)
                }
                check(reply != null && reply.getBoolean("ok") && reply.getInt("user") == user &&
                    reply.getString("nonce") == nonce && reply.getLong("seq") == seq) { "SAFE_PROFILE_CAPTURE_FAILED" }
                root.profileCopy(user, nonce, file)
                val (header, entries) = file.inputStream().use { SafeSnapshot.read(it, safe) }
                try { check(header.getString("phoneId") == phoneId && header.getInt("androidUserId") == user && header.getLong("seq") == seq) }
                finally { entries.forEach { it.close() } }
                root.put(user, seq, file, safe)
                SafeRuntime(context).folder()?.let { SafeFolderStore(context, it).put(user, seq, file, safe) }
                root.profileClean(user, nonce)
            } catch (_: Exception) { missed += user }
            finally { file.delete() }
        }
        return missed
    }
}

/** Non-exported bootstrap worker: plaintext and the profile's Keystore stay inside the target user. */
class SafeProfileService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("cyclone-safe", "Cyclone Safe", android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = android.app.Notification.Builder(this, "cyclone-safe").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Saving Cyclone Safe").setContentText("Encrypted local recovery copy").build()
        startForeground(61352, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nonce = intent?.getStringExtra("nonce").orEmpty()
        val seq = intent?.getLongExtra("seq", 0) ?: 0
        val user = android.os.Process.myUid() / 100000
        if (!nonce.matches(Regex("[a-f0-9]{32}")) || seq <= 0 || user <= 0 || intent?.action !in setOf("com.cyclone.SAFE_CAPTURE", "com.cyclone.SAFE_RESTORE")) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY
        }
        val restore = intent?.action == "com.cyclone.SAFE_RESTORE"
        executor.execute {
            val dir = File(createDeviceProtectedStorageContext().filesDir, "safe-outbox").apply { mkdirs() }
            var ok = false
            try {
                check(getSystemService(UserManager::class.java).isUserUnlocked)
                if (restore) {
                    val runtime = SafeRuntime(this)
                    if (runtime.needsStartupCheck()) runtime.restoreRoot() else {
                        // Main may have been uninstalled alone. Do not overwrite a profile copy that survived.
                        val record = SafeRootStore().record()
                        val rootKey = SafeRootStore().key()
                        val safe = try { SafeKey.openRoot(record.getJSONObject("wraps").getJSONObject("root"), rootKey) } finally { rootKey.fill(0) }
                        try { runtime.withKey { existing -> check(java.security.MessageDigest.isEqual(existing, safe)) } }
                        finally { safe.fill(0) }
                    }
                } else {
                    val root = SafeRootStore()
                    // A fresh/reinstalled profile must recover its old Safe before an empty copy can replace it.
                    check(!SafeStartupGate.blocked || root.list(user).isEmpty()) { "SAFE_PROFILE_RESTORE_REQUIRED" }
                    val record = root.record()
                    val rootKey = root.key()
                    val safe = try { SafeKey.openRoot(record.getJSONObject("wraps").getJSONObject("root"), rootKey) } finally { rootKey.fill(0) }
                    var entries: List<SafeSnapshot.Entry> = emptyList()
                    try {
                        entries = SafeStores(this).capture()
                        val target = File(dir, "$nonce.cyc")
                        val part = File(dir, "$nonce.cyc.part")
                        try {
                            FileOutputStream(part).use { out ->
                                SafeSnapshot.write(out, safe, SafeSnapshot.Identity(user, com.cyclone.mobile.runtime.workspaces.ProfileCarry.me(this).first,
                                    record.getString("phoneId"), seq, System.currentTimeMillis()), entries, record.getJSONObject("wraps"))
                                out.fd.sync()
                            }
                            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                            SafeRuntime(this).registerSharedKey(record, safe)
                            SafeSchedule.install(this)
                        } finally { part.delete() }
                    } finally { entries.forEach { it.close() }; safe.fill(0) }
                }
                ok = true
            } catch (_: Exception) { /* Status carries a fixed reason, never an exception or store value. */ }
            finally {
                runCatching { SafeFiles.atomic(File(dir, "$nonce.json"), JSONObject().put("ok", ok).put("user", user)
                    .put("nonce", nonce).put("seq", seq).put("reason", if (ok) "SAFE_COMPLETED" else "SAFE_PROFILE_FAILED").toString().toByteArray()) }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}

/** A profile can ask Main to schedule a capture, never write shared root copies itself. */
class SafeMainService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("cyclone-safe", "Cyclone Safe", android.app.NotificationManager.IMPORTANCE_LOW))
        startForeground(61353, android.app.Notification.Builder(this, "cyclone-safe").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Cyclone Safe").setContentText("Scheduling encrypted recovery copy").build(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (android.os.Process.myUid() / 100000 == 0 && (intent?.getIntExtra("source", 0) ?: 0) in 1..99999)
            runCatching { SafeSchedule.background(applicationContext) }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
        return START_NOT_STICKY
    }
}
