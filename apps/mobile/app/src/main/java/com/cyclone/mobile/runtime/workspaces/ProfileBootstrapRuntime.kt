package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Base64
import com.cyclone.mobile.ai.OpenRouterSecretStore
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/** Trusted, user-initiated profile preparation. No shell API is exported to an AI or IPC client. */
internal object ProfileBootstrapRuntime {
    private const val PKG = ProfileBootstrapContract.PACKAGE
    private const val SERVICE = "$PKG/.runtime.workspaces.ProfileBootstrapService"
    private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    private const val MAX_OUTPUT = 262144
    const val CARRY_ACTION = "com.cyclone.PROFILE_CARRY"
    const val HELLO_ACTION = "com.cyclone.PROFILE_HELLO"
    const val ROOT_CHECK_ACTION = "com.cyclone.PROFILE_ROOT_CHECK"

    fun prepare(context: Context, target: Int, profile: String) = prepareInternal(context, target, profile, false)

    /**
     * Plan 40 P2: brings what this profile's Cyclone knows (memory, skills, settings) into [target]'s, in any
     * direction, the main profile included. The bundle is sealed for [target]'s Keystore key and bound to this switch;
     * [target]'s Cyclone takes it in and answers with the nonce. Callers treat a failure as "nothing was carried".
     */
    fun carry(context: Context, target: Int): CarryReport {
        require(context.packageName == PKG)
        val source = ProfileSetupRuntime.currentUserId()
        require(target >= 0 && target != source)
        check(run("/system/bin/am", "get-started-user-state", "$target").contains("RUNNING_UNLOCKED")) { "That profile is locked." }
        val targetUid = packageUid(target, PKG)
        val folder = "/data/user_de/$target/$PKG/files"
        run("/system/bin/rm", "-f", "$folder/profile-bootstrap-public.txt", "$folder/carry-inbox.json", "$folder/carry-result.json")
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE)
        val publicKey = poll { runCatching { run("/system/bin/cat", "$folder/profile-bootstrap-public.txt").trim().takeIf { it.isNotBlank() } }.getOrNull() }
        val nonce = UUID.randomUUID().toString()
        val plain = ProfileCarry.pack(context).toString().toByteArray(Charsets.UTF_8)
        val sealed = try { ProfileTransferCipher.seal(publicKey, plain, CarryRules.cipherContext(target, nonce)) } finally { plain.fill(0) }
        val envelope = JSONObject().put("source", source).put("target", target).put("nonce", nonce).put("bundle", sealed)
        writePrivate("$folder/carry-inbox.json", targetUid, envelope.toString())
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE, "-a", CARRY_ACTION)
        return poll(tries = 150) {
            val ack = runCatching { JSONObject(run("/system/bin/cat", "$folder/carry-result.json")) }.getOrNull()
            if (ack?.optString("nonce") == nonce && ack.optInt("user", -1) == target) {
                check(ack.optBoolean("ok")) { "The other profile's Cyclone couldn't take in what was carried." }
                CarryReport.fromJson(ack.getJSONObject("report"))
            } else null
        }
    }

    fun requestRepair(context: Context) {
        val target = ProfileSetupRuntime.currentUserId()
        require(target > 0)
        val owner = ProfileSetupRuntime.profileAUserId()
        check(owner != target) { "You are already in the main profile." }
        run("/system/bin/am", "start-user", "-w", "$owner")
        run("/system/bin/am", "start-foreground-service", "--user", "$owner", "-n", SERVICE,
            "-a", "com.cyclone.PROFILE_REPAIR", "--ei", "target", "$target")
    }

    fun reportRepairFailure(target: Int) {
        if (target > 0 && run("/system/bin/am", "get-current-user").trim() == target.toString()) {
            run("/system/bin/am", "start", "--user", "$target", "-n", "$PKG/.ui.ProfileRescueActivity", "--ez", "repair_failed", "true")
        }
    }

    fun repairFromOwner(context: Context, target: Int) {
        check(!com.cyclone.mobile.runtime.background.WorkspaceTasks.hasCurrentTask() &&
            !com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.hasExecutingTask() && !Layer2Workspaces.gated()) { "Finish the current task first." }
        val source = ProfileSetupRuntime.currentUserId()
        val record = ProfileRegistryStore.records(context).single { it.androidUserId == target && it.parentUserId == source }
        val users = ProfileSetupParser.users(run("/system/bin/cmd", "user", "list", "--all", "--verbose"))
        check(users.any { it.id == target && it.name == record.id && ProfileRecovery.validOwned(it, source, true) })
        synchronized(Layer2Workspaces.engine.mutationLock) { prepareInternal(context, target, record.id, true) }
        run("/system/bin/am", "start", "--user", "$target", "-n", "$PKG/.MainActivity")
    }

    private fun prepareInternal(context: Context, target: Int, profile: String, repairingActiveTarget: Boolean) {
        require(context.packageName == PKG && ProfileSetupPlan.validProfileName(profile))
        val source = ProfileSetupRuntime.currentUserId()
        require(target > 0 && target != source)
        check(run("/system/bin/am", "get-current-user").trim() == (if (repairingActiveTarget) target else source).toString()) { "Open Cyclone in your active phone profile before preparing another." }
        // Plan 57 P1: which root manager this phone uses, and a hidden (renamed) Magisk app, so it comes along too.
        val manager = runCatching { ProfileRoom.detect(run("/system/bin/ls", "/data/adb")) }.getOrNull()
        val hiddenMagisk = if (manager == ProfileSetupPlan.RootManager.MAGISK) hiddenMagiskPackage(context) else null
        val installedSupport = (ProfileRequiredPackages.supportAllowlist + listOfNotNull(hiddenMagisk)).filter { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }.toSet()
        // Plan 57 P2: the cornerstones. Cyclone, the root manager and Shizuku are required; Cyclone Cloak and the apps the
        // owner marked are installed too, best effort, and reported.
        val items = ProfileCornerstones.resolve(PKG, installedSupport, hiddenMagisk,
            runCatching { ProfileCornerstones.cloakPackage(context) }.getOrNull(), ProfileCornerstones.marked(context))
        val checks = linkedMapOf<String, ProfileInventory.App>()
        items.forEach { item ->
            val label = runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(item.packageName, 0)).toString().take(40)
            }.getOrNull()
            if (label == null && item.role != ProfileCornerstones.Role.CYCLONE) {
                checks[item.packageName] = ProfileInventory.App(item.packageName, item.role, item.packageName.substringAfterLast('.'), false,
                    note = "Not installed in this profile")
                return@forEach
            }
            val installed = runCatching {
                run("/system/bin/cmd", "package", "install-existing", "--user", "$target", item.packageName)
                check(ProfileSetupParser.packages(run("/system/bin/pm", "list", "packages", "--user", "$target", item.packageName)).contains(item.packageName)) {
                    "A required app wasn't installed in this profile."
                }
                run("/system/bin/pm", "enable", "--user", "$target", item.packageName)
            }
            if (item.required) installed.getOrThrow()
            checks[item.packageName] = ProfileInventory.App(item.packageName, item.role, label ?: "Cyclone", installed.isSuccess,
                note = if (installed.isSuccess) null else "Android didn't install it")
        }
        // Plan 57 P3: connector settings for apps Android says are gone from this profile go too. Only the main profile's
        // Cyclone (the registry owner) prunes, and only from a real listing (one that shows Cyclone itself).
        runCatching {
            if (source == ProfileSetupRuntime.profileAUserId()) {
                val there = ProfileSetupParser.packages(run("/system/bin/pm", "list", "packages", "--user", "$target")).toSet()
                if (PKG in there) com.cyclone.mobile.connector.ProfileConfigStore.prune(context, profile, target, there)
            }
        }
        run("/system/bin/am", "start-user", "-w", "$target")
        check(run("/system/bin/am", "get-started-user-state", "$target").contains("RUNNING_UNLOCKED")) {
            "Unlock this profile once so Android can open its protected settings."
        }
        val targetUid = packageUid(target, PKG)
        val sourceUid = context.applicationInfo.uid
        if (manager == ProfileSetupPlan.RootManager.MAGISK) {
            val shareable = checks.values.filter { it.installed && it.role != ProfileCornerstones.Role.CYCLONE }.map { it.packageName }.toSet()
            val required = items.filter { it.required && it.role != ProfileCornerstones.Role.CYCLONE }.map { it.packageName }.toSet()
            val shared = prepareMagisk(source, target, sourceUid, targetUid, shareable, required)
            shareable.forEach { pkg -> checks[pkg]?.let { checks[pkg] = it.copy(rootShared = pkg in shared) } }
        }

        val folder = "/data/user_de/$target/$PKG/files"
        // Force-stop only the destination Cyclone process before import, never another app.
        run("/system/bin/am", "force-stop", "--user", "$target", PKG)
        run("/system/bin/rm", "-f", "$folder/profile-bootstrap-public.txt", "$folder/profile-bootstrap-result.json", "$folder/profile-bootstrap.json")
        mirrorGrants(context, target)
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE)
        val publicKey = poll { runCatching { run("/system/bin/cat", "$folder/profile-bootstrap-public.txt").trim().takeIf { it.isNotBlank() } }.getOrNull() }
        val nonce = UUID.randomUUID().toString()
        val preferences = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).all
        val ai = JSONObject()
        ProfileBootstrapContract.portablePreferences(preferences).forEach { (key, value) -> ai.put(key, value) }
        val payload = JSONObject().put("source", source).put("target", target).put("profile", profile).put("nonce", nonce)
            .put("ai", ai).put("profiles", context.getSharedPreferences("cyclone_profile_registry", Context.MODE_PRIVATE).getString("profiles", "[]"))
        payload.put("permissions", org.json.JSONArray((ProfileBootstrapContract.permissions + SHIZUKU_PERMISSION)
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }))
        payload.put("overlay", Settings.canDrawOverlays(context))
        payload.put("accessibility", containsCyclone(Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty(), "CycloneAccessibilityService"))
        payload.put("listener", containsCyclone(Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty(), "CycloneNotificationListener"))
        val apiKey = OpenRouterSecretStore.read(context)
        if (apiKey.isNotBlank()) {
            val bytes = apiKey.toByteArray(Charsets.UTF_8)
            try { payload.put("key", ProfileTransferCipher.encrypt(publicKey, bytes)) } finally { bytes.fill(0) }
        }
        writePrivate("$folder/profile-bootstrap.json", targetUid, payload.toString())
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE)
        poll {
            val ack = runCatching { JSONObject(run("/system/bin/cat", "$folder/profile-bootstrap-result.json")) }.getOrNull()
            if (ack?.optString("nonce") == nonce && ack.optInt("user") == target && ack.optBoolean("ok")) "ready" else null
        }
        // Plan 57 P1: prove root from the target's own Cyclone, whatever the root manager. When it can't be set by
        // command (KernelSU, APatch, or a Magisk grant missing), the owner is told the one step to do by hand.
        val label = runCatching { ProfileRegistryStore.records(context).firstOrNull { it.id == profile }?.label }.getOrNull() ?: "this profile"
        val rooted = rootFromTarget(target)
        // Plan 57 P2: what this profile has, for Profiles and the debug file (the carry adds its counts).
        runCatching {
            val previous = ProfileInventoryStore.get(context, profile)
            ProfileInventoryStore.save(context, ProfileInventory(profile, System.currentTimeMillis(),
                runCatching { context.packageManager.getPackageInfo(PKG, 0).versionName }.getOrNull(), manager?.name, rooted,
                checks.values.toList(), previous?.settings ?: -1, previous?.skills ?: -1, previous?.files ?: -1,
                previous?.cloakApproved, previous?.cloakNote))
        }
        check(rooted) { ProfileSwitch.rootGuidance(manager, label) }
    }

    /**
     * Asks the target's Cyclone to run `su -c id` itself and report. Truthful for every root manager: it is the very
     * check the target's Cyclone needs to switch back.
     */
    private fun rootFromTarget(target: Int): Boolean {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val file = "/data/user_de/$target/$PKG/files/root-check-$nonce.json"
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE, "-a", ROOT_CHECK_ACTION, "--es", "nonce", nonce)
        val answer = runCatching {
            poll(tries = 75) {
                runCatching { JSONObject(run("/system/bin/cat", file)) }.getOrNull()?.takeIf { it.optString("nonce") == nonce }
            }
        }.getOrNull()
        runCatching { run("/system/bin/rm", "-f", file) }
        return answer?.optBoolean("ok") == true
    }

    /** Magisk's hidden app (its package is random after "Hide the Magisk app"), read from Magisk's own database. */
    private fun hiddenMagiskPackage(context: Context): String? = runCatching {
        val out = run("magisk", "--sqlite", "SELECT value FROM strings WHERE key='requester';")
        Regex("value=([A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+)").find(out)?.groupValues?.get(1)
            ?.takeIf { ProfileSetupPlan.validPackageName(it) && it != PKG }
            ?.takeIf { runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess }
    }.getOrNull()

    /**
     * Plan 57 P1: asks the target's Cyclone to say hello once its profile is in front (it waits for that), and waits
     * until it says it is listening. A profile that is locked, or whose Cyclone can't start, never gets a return armed:
     * the owner might still be typing its PIN.
     */
    fun requestHello(target: Int, nonce: String) {
        require(target >= 0 && ProfileSwitch.validNonce(nonce))
        val folder = "/data/user_de/$target/$PKG/files"
        execute("/system/bin/rm -f $folder/switch-hello-* $folder/switch-wait-*")
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE, "-a", HELLO_ACTION, "--es", "nonce", nonce)
        val listening = waitForFile("$folder/switch-wait-$nonce", seconds = 5)
        runCatching { run("/system/bin/rm", "-f", "$folder/switch-wait-$nonce") }
        check(listening) { "Cyclone in that profile isn't listening yet, so no automatic way back was set." }
    }

    /** Waits up to [seconds] for the target's hello (one bounded command, one journal step). */
    fun helloArrived(target: Int, nonce: String, seconds: Int = 18): Boolean = waitForFile(ProfileSwitch.helloPath(target, nonce), seconds)

    private fun waitForFile(path: String, seconds: Int): Boolean {
        require(path.matches(Regex("/data/user_de/[0-9]+/com\\.cyclone\\.mobile/files/switch-(hello|wait)-[a-f0-9]{32}")) && seconds in 1..18)
        return runCatching {
            execute("i=0; while [ \$i -lt ${seconds * 4} ]; do [ -f $path ] && exit 0; sleep 0.25; i=\$((i+1)); done; exit 1")
        }.isSuccess
    }

    /** Plan 57 P1: the dead-man return, a fixed root-side timer (see [ProfileSwitch.returnCommand]). */
    fun armReturn(source: Int, target: Int, nonce: String) {
        execute(ProfileSwitch.returnCommand(source, target, nonce))
    }

    /** Shares Magisk's grants into [target]; returns the apps in [support] whose grant was shared. */
    private fun prepareMagisk(source: Int, target: Int, sourceUid: Int, targetUid: Int, support: Set<String>, required: Set<String>): Set<String> {
        check(runCatching { run("magisk", "-V").trim().toInt() >= 24000 }.getOrDefault(false)) {
            "Automatic profile root setup needs Magisk 24 or newer. Update Magisk, then switch again."
        }
        val allowed = "SELECT policy FROM policies WHERE uid=$sourceUid AND policy=2 AND (until=0 OR until>strftime('%s','now'));"
        check(run("magisk", "--sqlite", allowed).contains("policy=2")) { "Cyclone needs a saved root grant in this profile before sharing that access." }
        val mode = run("magisk", "--sqlite", "SELECT value FROM settings WHERE key='multiuser_mode';")
        if (!mode.contains("value=1") && !mode.contains("value=2")) {
            run("magisk", "--sqlite", "INSERT OR REPLACE INTO settings (key,value) VALUES ('multiuser_mode',2);")
        }
        fun inherit(uid: Int, destination: Int) {
            run("magisk", "--sqlite", "INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) SELECT $destination,policy,until,logging,notification FROM policies WHERE uid=$uid AND policy=2 AND (until=0 OR until>strftime('%s','now'));")
        }
        inherit(sourceUid, targetUid)
        // Only installed cornerstones with an existing owner grant inherit it. Plan 57 P2: Cloak and the owner's marked
        // apps are best effort; a failure there never stops the switch.
        val shared = mutableSetOf<String>()
        support.forEach { pkg ->
            val result = runCatching {
                val from = packageUid(source, pkg)
                val had = run("magisk", "--sqlite", "SELECT policy FROM policies WHERE uid=$from AND policy=2 AND (until=0 OR until>strftime('%s','now'));").contains("policy=2")
                inherit(from, packageUid(target, pkg))
                had
            }
            if (pkg in required) result.getOrThrow()
            if (result.getOrDefault(false)) shared += pkg
        }
        return shared
    }

    private fun mirrorGrants(context: Context, target: Int) {
        (ProfileBootstrapContract.permissions + SHIZUKU_PERMISSION).forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                run("/system/bin/pm", "grant", "--user", "$target", PKG, permission)
            }
        }
        if (Settings.canDrawOverlays(context)) run("/system/bin/cmd", "appops", "set", "--user", "$target", PKG, "SYSTEM_ALERT_WINDOW", "allow")
        if (context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms())
            run("/system/bin/cmd", "appops", "set", "--user", "$target", PKG, "SCHEDULE_EXACT_ALARM", "allow")
        if (context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(PKG))
            run("/system/bin/cmd", "deviceidle", "whitelist", "+$PKG")
        if (context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT))
            run("/system/bin/cmd", "role", "add-role-holder", "--user", "$target", android.app.role.RoleManager.ROLE_ASSISTANT, PKG)
        val ownAccessibility = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
        if (containsCyclone(ownAccessibility, "CycloneAccessibilityService")) {
            val existing = run("/system/bin/settings", "--user", "$target", "get", "secure", "enabled_accessibility_services").trim()
            run("/system/bin/settings", "--user", "$target", "put", "secure", "enabled_accessibility_services",
                ProfileBootstrapContract.mergeServiceList(existing, ProfileBootstrapContract.ACCESSIBILITY))
            run("/system/bin/settings", "--user", "$target", "put", "secure", "accessibility_enabled", "1")
        }
        val ownListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        if (containsCyclone(ownListeners, "CycloneNotificationListener")) {
            run("/system/bin/cmd", "notification", "allow_listener", ProfileBootstrapContract.LISTENER, "$target")
        }
    }

    private fun containsCyclone(list: String, service: String) = list.split(':').any {
        it == "$PKG/.$service" || it == "$PKG/$PKG.$service"
    }

    private fun packageUid(user: Int, pkg: String): Int {
        val text = run("/system/bin/pm", "list", "packages", "-U", "--user", "$user", pkg)
        val uid = Regex("package:" + Regex.escape(pkg) + "\\s+uid:(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        check(uid != null && ProfileBootstrapContract.userId(uid) == user) { "Android couldn't verify a required app's profile identity." }
        return uid
    }

    private fun <T : Any> poll(tries: Int = 30, read: () -> T?): T {
        repeat(tries) { read()?.let { return it }; Thread.sleep(200) }
        error("Cyclone couldn't verify the receiving profile. Your current profile was kept open.")
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun run(vararg args: String): String = execute(args.joinToString(" ", transform = ::quote))

    private fun writePrivate(path: String, uid: Int, data: String) {
        require(path.matches(Regex("/data/user_de/[0-9]+/com\\.cyclone\\.mobile/files/(profile-bootstrap|carry-inbox)\\.json")))
        execute("umask 077; cat > ${quote(path)} && chown $uid:$uid ${quote(path)} && restorecon ${quote(path)}", data)
    }

    private fun execute(command: String, input: String? = null): String {
        val startedAt = System.currentTimeMillis()
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(output) { if (output.length < MAX_OUTPUT) output.appendLine(line) }
            } } }
        }.apply { isDaemon = true; start() }
        try {
            process.outputStream.bufferedWriter().use { writer -> input?.let(writer::write) }
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            reader.join(500)
            val text = synchronized(output) { output.toString() }
            val exit = if (finished) process.exitValue() else null
            val ok = finished && exit == 0 && !text.lineSequence().any { it.trim().startsWith("Error:") }
            // Plan 57 W1: the command shape and Android's answer, never the input (it can carry sealed bundles).
            runCatching {
                ProfileStepJournal.record("BOOTSTRAP", command, exit, System.currentTimeMillis() - startedAt, text,
                    if (!finished) "TIMED_OUT" else if (ok) null else "FAILED")
            }
            check(finished) { "Profile preparation timed out. No switch was made." }
            check(ok) {
                "Android couldn't complete a required profile setup step. No switch was made."
            }
            return text
        } finally { process.destroyForcibly() }
    }
}
