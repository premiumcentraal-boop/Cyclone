package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ProfileApp(val label: String, val packageName: String)
data class ProfileSetupStatus(val busy: Boolean = false, val message: String = "", val completed: Int = 0,
    val total: Int = 1, val ready: Boolean = false, val userId: Int? = null)

/** User-initiated local setup only; no exported service, no model root interface, no deletion. */
object ProfileSetupRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ProfileSetupStatus())
    val state = _state.asStateFlow()
    private val cancel = AtomicBoolean(false)
    private var job: Job? = null
    private fun prefs(context: Context) = context.getSharedPreferences("cyclone_profile_setup", Context.MODE_PRIVATE)
    fun existingUser(context: Context): Int? = prefs(context).getInt("user", -1).takeIf { it > 0 }
    fun apps(context: Context): List<ProfileApp> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { ProfileApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    fun stop() { cancel.set(true) }
    private fun boundary() {
        check(!cancel.get()) { "Paused. Your progress is saved. You can finish setting up this profile later." }
        check(!Layer2Workspaces.gated()) { "Finish the request waiting for your approval, then continue." }
    }
    @Synchronized fun create(context: Context, selected: List<ProfileApp>) {
        if (job?.isActive == true) return
        val ctx = context.applicationContext
        val choices = selected.distinctBy { it.packageName }.toList()
        require(choices.isNotEmpty() && choices.size <= 50)
        cancel.set(false)
        _state.value = ProfileSetupStatus(busy = true, message = "Preparing your second profile…", total = choices.size + 3)
        job = scope.launch {
            try { synchronized(Layer2Workspaces.engine.mutationLock) {
                Layer2Workspaces.initialize(ctx)
                boundary(); Layer2Workspaces.engine.clearSelection()
                check(RootProbe.check() == RootStatus.ROOTED) { "Your phone hasn’t allowed extra profiles. Allow Cyclone in your root manager, then try again." }
                val available = apps(ctx).associateBy { it.packageName }
                check(choices.all { available.containsKey(it.packageName) }) { "One of these apps is no longer installed. Choose your apps again." }
                val store = prefs(ctx)
                val name = store.getString("name", null) ?: ("Cyclone_" + UUID.randomUUID().toString().replace("-", "").take(16)).also {
                    check(store.edit().putString("name", it).commit()) { "Free some space, then try again." }
                }
                boundary()
                val before = root(listOf("/system/bin/pm", "list", "users"))
                val storedUser = existingUser(ctx)
                var user = ProfileSetupPlan.ownedUser(before, name)
                if (storedUser != null) check(user == storedUser) { "This saved profile is no longer available. Your other profiles were left unchanged." }
                if (user == null) {
                    val created = root(ProfileSetupPlan.create(Layer2Workspaces.currentAndroidUserId(), name))
                    user = ProfileSetupPlan.createdUser(created)
                        ?: error("Android couldn’t create another profile. Your phone may already have its maximum number of profiles.")
                    // Journal before any further command: retry never creates a duplicate profile.
                    check(store.edit().putInt("user", user).commit()) { "Profile created. Reopen setup to recover it." }
                } else check(store.edit().putInt("user", user).commit())
                val id = user
                check(id != Layer2Workspaces.currentAndroidUserId())
                check(ProfileSetupPlan.ownedUser(root(listOf("/system/bin/pm", "list", "users")), name) == id) { "Android couldn’t confirm the new profile." }
                boundary()
                _state.value = _state.value.copy(message = "Starting Profile B…", completed = 1, userId = id)
                root(listOf("/system/bin/am", "start-user", "-w", id.toString()))
                check(root(listOf("/system/bin/am", "get-started-user-state", id.toString())).contains("RUNNING_UNLOCKED")) { "Unlock your phone and turn on Profile B, then try again." }
                boundary()
                choices.forEachIndexed { index, app ->
                    boundary()
                    _state.value = _state.value.copy(message = "Adding ${app.label}…", completed = index + 2)
                    root(ProfileSetupPlan.install(id, app.packageName))
                    val installed = ProfileSetupPlan.packages(root(listOf("/system/bin/pm", "list", "packages", "--user", id.toString(), app.packageName)))
                    check(app.packageName in installed) { "${app.label} couldn’t be added. Tap Try again to continue." }
                    Layer2Workspaces.engine.register(Workspace("profileB-$id-${UUID.nameUUIDFromBytes(app.packageName.toByteArray())}", app.label.take(80), app.packageName, id))
                }
                boundary()
                // This is the newly created profile only. Existing phone settings/data are untouched.
                root(listOf("/system/bin/settings", "--user", id.toString(), "put", "secure", "user_setup_complete", "1"))
                check(root(listOf("/system/bin/settings", "--user", id.toString(), "get", "secure", "user_setup_complete")).trim() == "1") { "Profile setup is not complete yet. Please retry." }
                check(store.edit().putBoolean("ready", true).putStringSet("apps", choices.map { it.packageName }.toSet()).commit())
                _state.value = ProfileSetupStatus(message = "Profile B is ready", completed = choices.size + 3,
                    total = choices.size + 3, ready = true, userId = id)
            } } catch (error: Exception) {
                _state.value = _state.value.copy(busy = false, ready = false,
                    message = error.message?.take(220) ?: "Setup paused. Please try again.")
            }
        }
    }
    private fun root(args: List<String>): String {
        val process = ProcessBuilder("su", "-c", ProfileSetupPlan.shell(args)).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { runCatching { process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { synchronized(output) { if (output.length < 64_000) output.appendLine(it) } }
        } } }.apply { isDaemon = true; start() }
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Android is taking too long. Your progress is saved; try again." }
            reader.join(500)
            val text = synchronized(output) { output.toString() }
            check(process.exitValue() == 0 && !text.contains("Error:", true) && !text.contains("Exception")) {
                "Android couldn’t complete this step. Check that extra profiles are allowed and try again."
            }
            return text
        } finally { process.destroyForcibly(); runCatching { process.inputStream.close() } }
    }
}
