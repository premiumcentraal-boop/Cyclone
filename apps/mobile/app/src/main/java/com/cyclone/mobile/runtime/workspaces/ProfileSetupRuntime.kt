package com.cyclone.mobile.runtime.workspaces

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ProfileApp(val label: String, val packageName: String)

/** Plan 57 W7: a profile whose setup was started but not finished. */
data class UnfinishedSetup(val profileId: String, val label: String, val androidUserId: Int?)

data class ProfileSetupStatus(
    val busy: Boolean = false,
    val message: String = "",
    val completed: Int = 0,
    val total: Int = 1,
    val ready: Boolean = false,
    val userId: Int? = null,
    val issue: ProfileSetupFailure? = null,
    val capabilities: ProfileProvisioningCapabilities? = null,
)

/** User-initiated local setup only; no exported service, no model root interface, no deletion. */
object ProfileSetupRuntime {
    private const val PREFS = "cyclone_profile_setup"
    private const val KEY_PLAN_APPS = "plan_apps"
    private const val KEY_APPS = "apps"
    private const val KEY_NAME = "name"
    private const val KEY_PARENT_USER = "parent_user"
    private const val KEY_USER = "user"
    private const val KEY_STAGE = "stage"
    private const val KEY_READY = "ready"
    private const val OUTPUT_LIMIT = 8_192

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ProfileSetupStatus())
    val state = _state.asStateFlow()
    private val cancel = AtomicBoolean(false)
    private var job: Job? = null

    /** Plan 57: the name the owner gave the profile being set up, used in every message ("Creating Profile C…"). */
    @Volatile private var setupLabel: String = "the new profile"

    private fun labelFor(context: Context, store: SharedPreferences): String {
        val id = store.getString(KEY_NAME, null)
        return store.getString(ProfileRegistryStore.JOURNAL_DISPLAY_LABEL, null)?.trim()?.takeIf { it.isNotBlank() }
            ?: id?.let { wanted -> ProfileRegistryStore.records(context).firstOrNull { it.id == wanted }?.label }
            ?: "the new profile"
    }

    @Volatile
    internal var lastCommandResult: ProfileCommandResult? = null
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PLAN_APPS, emptySet()).orEmpty().filter(ProfileSetupPlan::validPackageName).toSet()

    fun existingUser(context: Context): Int? = prefs(context).getInt(KEY_USER, -1).takeIf { it > 0 }

    /** Resume discovery precedes creation; only the exact journaled identity can be adopted. */
    fun refreshExisting(context: Context): Int? {
        val store = prefs(context)
        val name = store.getString(KEY_NAME, null) ?: return existingUser(context)
        if (!ProfileSetupPlan.validProfileName(name)) return null
        val parent = Layer2Workspaces.currentAndroidUserId()
        val execution = executeRoot(ProfileSetupPlan.listUsers())
        if (!execution.result.successful) return existingUser(context)
        val users = ProfileSetupParser.users(execution.result.output)
        val journal = journalSnapshot(store, name, parent, selectedPackages(context))
        val recovered = ProfileRecovery.resolve(journal, parent, users)
        if (recovered is ProfileRecoveryDecision.Resume) {
            commitOrStorageFailure(store.edit().putInt(KEY_USER, recovered.user.id))
            _state.value = _state.value.copy(userId = recovered.user.id, issue = null,
                message = "Your saved profile was found. Continue adding apps.")
            return recovered.user.id
        }
        return null
    }

    /** Preserve the completed journal before planning another profile; failed setup always resumes. */
    @Synchronized fun beginAnotherProfile(context: Context): Boolean {
        check(job?.isActive != true) { "Wait for profile setup to finish." }
        val store = prefs(context)
        ProfileRegistryStore.checkpoint(context, store)
        if (!store.getBoolean(KEY_READY, false)) return false
        check(store.edit().clear().commit()) { "Couldn't save the next profile plan." }
        _state.value = ProfileSetupStatus()
        return true
    }

    /** Plan 57: forgets the setup journal when it belongs to [profileId] (its Android user was cleaned up). */
    @Synchronized fun forgetJournal(context: Context, profileId: String) {
        check(job?.isActive != true) { "Wait for profile setup to finish." }
        val store = prefs(context)
        if (store.getString(KEY_NAME, null) == profileId) {
            check(store.edit().clear().commit()) { "Couldn't save the profile plan." }
            _state.value = ProfileSetupStatus()
        }
    }

    /**
     * Plan 57 W7: the + on the Profiles page always starts a new plan. A ready profile's journal is checkpointed and
     * cleared; an unfinished one is reported (the owner chooses Finish or [discardUnfinished]).
     */
    @Synchronized fun startNewPlan(context: Context): UnfinishedSetup? {
        check(job?.isActive != true) { "Wait for profile setup to finish." }
        val store = prefs(context)
        val name = store.getString(KEY_NAME, null) ?: run { _state.value = ProfileSetupStatus(); return null }
        ProfileRegistryStore.checkpoint(context, store)
        if (store.getBoolean(KEY_READY, false)) {
            check(store.edit().clear().commit()) { "Couldn't save the next profile plan." }
            _state.value = ProfileSetupStatus()
            return null
        }
        val label = labelFor(context, store)
        return UnfinishedSetup(name, label, store.getInt(KEY_USER, -1).takeIf { it > 0 })
    }

    /**
     * Drops an unfinished plan so a new one can start. A plan that never created an Android user is simply forgotten;
     * one that did stays listed for Clean up (its user is removed only there, after the owner's confirm).
     */
    @Synchronized fun discardUnfinished(context: Context) {
        check(job?.isActive != true) { "Wait for profile setup to finish." }
        val store = prefs(context)
        val name = store.getString(KEY_NAME, null) ?: return
        if (!store.getBoolean(KEY_READY, false)) {
            val records = ProfileRegistryStore.records(context)
            if (records.any { it.id == name && it.androidUserId == null }) ProfileRegistryStore.drop(context, name)
        }
        check(store.edit().clear().commit()) { "Couldn't save the profile plan." }
        _state.value = ProfileSetupStatus()
    }

    @Synchronized fun selectProfile(context: Context, id: String) {
        check(job?.isActive != true) { "Wait for profile setup to finish." }
        val store = prefs(context)
        ProfileRegistryStore.checkpoint(context, store)
        val record = ProfileRegistryStore.records(context).single { it.id == id }
        val editor = store.edit().clear().putString(KEY_NAME, record.id).putInt(KEY_PARENT_USER, record.parentUserId)
            .putBoolean("secondary", record.secondaryUser).putStringSet(KEY_PLAN_APPS, record.packages)
            .putString(KEY_STAGE, record.stage).putBoolean(KEY_READY, record.ready)
        record.androidUserId?.let { editor.putInt(KEY_USER, it) }
        commitOrStorageFailure(editor)
        _state.value = ProfileSetupStatus(ready = record.ready, userId = record.androidUserId)
    }

    fun profileAUserId(): Int {
        runRequired(ProfileSetupPlan.verifyRoot())
        return ProfileSetupParser.mainUserId(listUsersRequired())
            ?: fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "Android did not identify the main phone user.")
    }

    fun currentUserId(): Int = Layer2Workspaces.currentAndroidUserId()

    /** Plan 57 W3: the room for another profile, counted as Android counts it. Read-only; needs root. */
    fun room(context: Context): ProfileCapacity.Room {
        runRequired(ProfileSetupPlan.verifyRoot())
        val users = listUsersRequired()
        val max = runBestEffort(ProfileSetupPlan.getMaxUsers())?.let(ProfileSetupParser::maxUsers)
        val types = runBestEffort(ProfileSetupPlan.dumpUsers())?.let(ProfileCapacity::typeLimits).orEmpty()
        return ProfileCapacity.room(users, max, ProfileRegistryStore.records(context), types)
    }

    /** Root-observed human user, distinct from this process's Android user. Read-only. */
    fun visibleProfileIdentity(): Pair<Int, Int> {
        val owner = ProfileSetupParser.mainUserId(listUsersRequired())
            ?: error("Android did not identify the main profile.")
        val current = ProfileSetupParser.currentUserId(runRequired(ProfileSetupPlan.currentUser()))
            ?: error("Android did not identify the current profile.")
        return owner to current
    }

    /**
     * Trusted local UI action. This is not a model tool and cannot bypass an active task.
     *
     * Plan 57 P1: a staged switch with a journal ([ProfileSwitch]): preflight, prepare, carry, arm the dead-man return,
     * switch (waiting adaptively), confirm. Every switch, finished or not, is remembered for Profiles and the debug file.
     */
    fun openProfile(context: Context, profileId: String?, onProgress: (String) -> Unit = {}) {
        val tracker = ProfileSwitch.Tracker()
        val source = currentUserId()
        var target = -1
        var label = "Main"
        var outcome = ProfileSwitch.Outcome.FAILED
        var note: String? = null
        try {
            synchronized(Layer2Workspaces.engine.mutationLock) {
                val user = tracker.stage(ProfileSwitch.Stage.PREFLIGHT) {
                    check(!Layer2Workspaces.gated() && !com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.hasExecutingTask() &&
                        !com.cyclone.mobile.runtime.background.WorkspaceTasks.hasCurrentTask()) { "Finish the current task before switching profiles." }
                    onProgress("Checking profile access…")
                    runRequired(ProfileSetupPlan.verifyRoot())
                    val users = listUsersRequired()
                    if (profileId == null) {
                        ProfileSetupParser.mainUserId(users)
                            ?: fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "Android did not identify the main profile.")
                    } else {
                        ProfileRegistryStore.checkpoint(context, prefs(context))
                        val record = ProfileRegistryStore.records(context).single { it.id == profileId }
                        label = record.label
                        check(!record.inTrash) { "${record.label} is in Recently deleted. Restore it first." }
                        val wanted = record.androidUserId ?: error("This profile still needs setup.")
                        check(record.ready && record.secondaryUser) { "This profile still needs setup." }
                        val exact = users.singleOrNull { it.id == wanted && it.name == record.id }
                        check(exact != null && ProfileRecovery.validOwned(exact, record.parentUserId, true)) { "Profile identity needs repair." }
                        wanted
                    }
                }
                target = user
                if (user != currentUserId() && profileId != null) {
                    tracker.stage(ProfileSwitch.Stage.PREPARE) {
                        onProgress("Preparing $label settings and permissions…")
                        ProfileBootstrapRuntime.prepare(context, user, profileId)
                    }
                }
                if (user != currentUserId()) {
                    // Plan 40 P2: what this Cyclone knows goes with you. Best effort: a carry never blocks the switch.
                    onProgress("Bringing your memory and skills…")
                    val carried = runCatching { ProfileBootstrapRuntime.carry(context, user) }
                    ProfileCarry.noteSent(context, carried.isSuccess)
                    // Plan 57 P2: the carry's counts complete the "Profile C has" line.
                    if (profileId != null) carried.getOrNull()?.let { r ->
                        runCatching {
                            val cloak = r.cloak?.let { name -> runCatching { com.cyclone.mobile.connector.ConnectorCarry.Outcome.valueOf(name) }.getOrNull() }
                            ProfileInventoryStore.update(context, profileId) {
                                it.copy(settings = r.settingsInPlace, skills = r.skillsTotal, files = r.files,
                                    cloakApproved = cloak?.approved ?: it.cloakApproved, cloakNote = cloak?.takeIf { c -> !c.approved }?.text)
                            }
                        }
                    }
                    tracker.note(ProfileSwitch.Stage.CARRY, carried.isSuccess, carried.exceptionOrNull()?.message)
                }
                val activeUser = ProfileSetupParser.currentUserId(runRequired(ProfileSetupPlan.currentUser()))
                check(activeUser == currentUserId() || activeUser == user) { "Your active profile changed. The switch was cancelled." }
                check(!Layer2Workspaces.gated()) { "Resolve the pending review before switching." }
                Layer2Workspaces.engine.clearSelection()
                val nonce = UUID.randomUUID().toString().replace("-", "")
                var hello = false
                if (activeUser != null && activeUser != user) {
                    // The way back first: the target's Cyclone is asked to say hello once it is in front; if it never
                    // does, the root-side timer switches back to where the owner was.
                    val armed = runCatching {
                        ProfileBootstrapRuntime.requestHello(user, nonce)
                        ProfileBootstrapRuntime.armReturn(activeUser, user, nonce)
                    }
                    hello = armed.isSuccess
                    tracker.note(ProfileSwitch.Stage.ARM_RETURN, armed.isSuccess, armed.exceptionOrNull()?.message)
                    tracker.stage(ProfileSwitch.Stage.SWITCH) {
                        onProgress("Asking Android to open the profile…")
                        runRequired(ProfileSetupPlan.switchUser(user))
                        onProgress("Waiting for Android to confirm the switch…")
                        var verified = false
                        for (pause in ProfileSwitch.waitSchedule()) {
                            verified = ProfileSetupParser.currentUserId(runRequired(ProfileSetupPlan.currentUser())) == user
                            if (verified) break
                            Thread.sleep(pause)
                        }
                        check(verified) { "Android hasn't completed switching profiles yet." }
                    }
                }
                outcome = ProfileSwitch.Outcome.DONE
                if (hello) {
                    val arrived = ProfileBootstrapRuntime.helloArrived(user, nonce)
                    tracker.note(ProfileSwitch.Stage.CONFIRM, arrived, if (arrived) null else
                        "Cyclone in $label hasn't said hello yet; the way back stays armed.")
                    if (!arrived) outcome = ProfileSwitch.Outcome.CONFIRM_LATE
                }
                runCatching { com.cyclone.mobile.connector.ConnectorEvents.switched(context, user) }
            }
        } catch (error: Throwable) {
            note = error.message
            throw error
        } finally {
            runCatching {
                ProfileSwitch.remember(ProfileSwitch.Record(System.currentTimeMillis(), source, target, label, tracker.results.toList(),
                    if (tracker.results.isEmpty() && note != null) ProfileSwitch.Outcome.NOT_STARTED else outcome,
                    note?.let { ProfileDebugRedaction.text(it, 200) }))
            }
        }
    }

    /** Plan 57 P1: Android's own user switcher (the last way back). Null when it can't be read. */
    fun userSwitcherOn(): Boolean? = ProfileSwitch.userSwitcherOn(runBestEffort(ProfileSetupPlan.readUserSwitcher()))

    fun enableUserSwitcher() {
        runRequired(ProfileSetupPlan.enableUserSwitcher())
        check(userSwitcherOn() == true) { "Android didn't turn its user switcher on." }
    }

    fun apps(context: Context): List<ProfileApp> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        PackageManager.MATCH_ALL,
    )
        .filter { it.activityInfo.packageName != context.packageName }
        .map { ProfileApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }

    fun stop() {
        cancel.set(true)
    }

    private class SetupFailure(val failure: ProfileSetupFailure) : Exception(failure.reason)
    private class SetupPaused(message: String) : Exception(message)

    private fun boundary() {
        if (cancel.get()) throw SetupPaused("Setup paused. Your progress is saved. You can finish $setupLabel later.")
        if (Layer2Workspaces.gated()) throw SetupPaused("Finish the request waiting for your approval, then continue setting up $setupLabel.")
    }

    @Synchronized
    fun create(context: Context, selected: List<ProfileApp>) {
        if (job?.isActive == true) return
        val ctx = context.applicationContext
        val requested = selected.distinctBy { it.packageName }.filter { ProfileSetupPlan.validPackageName(it.packageName) }
        require(requested.isNotEmpty() && requested.size <= 50)
        cancel.set(false)
        setupLabel = runCatching { labelFor(ctx, prefs(ctx)) }.getOrDefault("the new profile")
        _state.value = ProfileSetupStatus(
            busy = true,
            message = "Checking what $setupLabel needs…",
            total = requested.size + 5,
        )

        job = scope.launch {
            try {
                synchronized(Layer2Workspaces.engine.mutationLock) {
                    Layer2Workspaces.initialize(ctx)
                    boundary()
                    Layer2Workspaces.engine.clearSelection()

                    val store = prefs(ctx)
                    val parentUserId = Layer2Workspaces.currentAndroidUserId()
                    val selectedPackages = requested.map { it.packageName }.toSet()
                    val installedSupportPackages = installedSourcePackages(ctx, ProfileRequiredPackages.supportAllowlist)
                    val requestedPackages = ProfileRequiredPackages.resolve(
                        ctx.packageName,
                        selectedPackages,
                        installedSupportPackages,
                    )
                    val existingName = store.getString(KEY_NAME, null)
                    val profileName = existingName ?: newProfileName().also { name ->
                        commitOrStorageFailure(store.edit().putString(KEY_NAME, name))
                    }
                    if (!ProfileSetupPlan.validProfileName(profileName)) {
                        fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "The saved profile identity is invalid.")
                    }
                    val priorParent = store.getInt(KEY_PARENT_USER, -1).takeIf { it >= 0 }
                    if (priorParent != null && priorParent != parentUserId) {
                        fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                            "The saved profile belongs to a different main user.")
                    }
                    commitOrStorageFailure(
                        store.edit()
                            .putInt(KEY_PARENT_USER, parentUserId)
                            .putStringSet(KEY_PLAN_APPS, selectedPackages)
                            .putBoolean(KEY_READY, false),
                    )

                    val sourcePackages = installedSourcePackages(ctx, requestedPackages)
                    ProfileSelectionVerifier.failure(requestedPackages, sourcePackages)?.let { throw SetupFailure(it) }

                    val shizukuAuthorized = shizukuAuthorized()
                    val userManager = ctx.getSystemService(UserManager::class.java)
                    val activityManager = ctx.getSystemService(ActivityManager::class.java)
                    val managedUsersFeature = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
                    val addManagedProfileRestricted = userManager?.hasUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE) == true
                    val lowRamDevice = activityManager?.isLowRamDevice == true

                    val rootExecution = executeRoot(ProfileSetupPlan.verifyRoot())
                    if (!rootExecution.result.successful) {
                        _state.value = _state.value.copy(capabilities = ProfileProvisioningCapabilities(
                            rootUsable = false,
                            shizukuAuthorized = shizukuAuthorized,
                            shizukuProfileProbeVerified = false,
                            parentUserId = parentUserId,
                            parentCanHostManagedProfile = false,
                            maxUsersReported = null,
                            existingUserCount = 0,
                            existingManagedProfilesForParent = 0,
                            managedUsersFeature = managedUsersFeature,
                            addManagedProfileRestricted = addManagedProfileRestricted,
                            lowRamDevice = lowRamDevice,
                        ))
                        throw SetupFailure(rootExecution.failure ?: ProfileFailureClassifier.local(ProfileSetupFailureKind.ROOT_COMMAND_FAILED))
                    }
                    // Keep the shared workspace runtime's root status synchronized after the stronger typed probe.
                    if (RootProbe.check() != RootStatus.ROOTED) {
                        fail(ProfileSetupFailureKind.ROOT_COMMAND_FAILED, "Root stopped responding after the profile preflight.")
                    }
                    val reportedCurrentUser = ProfileSetupParser.currentUserId(runRequired(ProfileSetupPlan.currentUser()))
                        ?: fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                            "Android did not report the current user id.")
                    var users = listUsersRequired()
                    val maxUsers = runBestEffort(ProfileSetupPlan.getMaxUsers())?.let(ProfileSetupParser::maxUsers)
                    val evaluated = ProfileCapabilityEvaluator.evaluate(
                        rootUsable = true,
                        shizukuAuthorized = shizukuAuthorized,
                        // 4.2.2 deliberately has no generic Shizuku shell fallback. Authorization alone is not readiness.
                        shizukuProfileProbeVerified = false,
                        appUserId = parentUserId,
                        reportedCurrentUserId = reportedCurrentUser,
                        users = users,
                        maxUsersReported = maxUsers,
                        managedUsersFeature = managedUsersFeature,
                        addManagedProfileRestricted = addManagedProfileRestricted,
                        lowRamDevice = lowRamDevice,
                    )
                    _state.value = _state.value.copy(capabilities = evaluated.capabilities)
                    // 4.2.7 profiles are full secondary Android users. Existing work-profile limits,
                    // policies or stale managed profiles must not block this separate provisioning plane.
                    SecondaryUserProvisioningPolicy.failure(
                        appUserId = parentUserId,
                        reportedCurrentUserId = reportedCurrentUser,
                        users = users,
                        maxUsersReported = maxUsers,
                    )?.let { throw SetupFailure(it) }
                    // Plan 57 W3: the phone's own limit for this kind of user, before asking Android to create one.
                    if (store.getInt(KEY_USER, -1) <= 0) {
                        val types = runBestEffort(ProfileSetupPlan.dumpUsers())?.let(ProfileCapacity::typeLimits).orEmpty()
                        when (ProfileCapacity.room(users, maxUsers, ProfileRegistryStore.records(ctx), types).verdict) {
                            ProfileCapacity.Verdict.TYPE_LIMIT -> fail(ProfileSetupFailureKind.USER_TYPE_LIMIT,
                                "This phone's system caps extra users of this kind.")
                            ProfileCapacity.Verdict.TYPE_DISABLED -> fail(ProfileSetupFailureKind.USER_TYPE_DISABLED,
                                "This phone's system has extra users of this kind switched off.")
                            else -> Unit
                        }
                    }

                    val journal = journalSnapshot(store, profileName, parentUserId, selectedPackages)
                    val profileUserId = when (val recovery = ProfileRecovery.resolve(journal, parentUserId, users)) {
                        ProfileRecoveryDecision.Create -> {
                            val secondary = true // Rooted consumer profiles must support whole-user switching.
                            commitOrStorageFailure(store.edit().putBoolean("secondary", secondary))
                            boundary()
                            _state.value = _state.value.copy(message = "Creating $setupLabel…", completed = 1)
                            val execution = executeRoot(if (secondary) ProfileSetupPlan.createSecondaryUser(profileName) else ProfileSetupPlan.createManagedProfile(parentUserId, profileName))
                            if (!execution.result.successful) {
                                if (execution.failure?.kind == ProfileSetupFailureKind.PROFILE_ALREADY_EXISTS) {
                                    users = listUsersRequired()
                                    when (val raced = ProfileRecovery.resolve(journal, parentUserId, users)) {
                                        is ProfileRecoveryDecision.Resume -> raced.user.id
                                        is ProfileRecoveryDecision.Fail -> throw SetupFailure(raced.failure)
                                        // The name isn't on the phone, so it can't "already exist": report Android's refusal as it is.
                                        ProfileRecoveryDecision.Create -> throw SetupFailure(ProfileFailureClassifier.local(
                                            ProfileSetupFailureKind.PROFILE_CREATION_REJECTED, execution.failure.platformMessage))
                                    }
                                } else {
                                    throw SetupFailure(execution.failure ?: ProfileFailureClassifier.local(
                                        ProfileSetupFailureKind.PROFILE_CREATION_REJECTED))
                                }
                            } else {
                                val created = ProfileSetupParser.createdUser(execution.result.output)
                                    ?: fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                                        "Android did not return the new profile's user id.")
                                // Journal immediately: even if verification/start fails, a retry will not create a second profile.
                                commitOrStorageFailure(
                                    store.edit()
                                        .putInt(KEY_USER, created)
                                        .putString(KEY_STAGE, ProfileSetupStage.PROFILE_CREATED.name),
                                )
                                ProfileRegistryStore.checkpoint(ctx, store)
                                users = listUsersRequired()
                                ProfileRecovery.verifyCreated(created, profileName, parentUserId, users, store.getBoolean("secondary", false))?.let { throw SetupFailure(it) }
                                created
                            }
                        }
                        is ProfileRecoveryDecision.Resume -> recovery.user.id
                        is ProfileRecoveryDecision.Fail -> throw SetupFailure(recovery.failure)
                    }

                    if (profileUserId <= 0 || profileUserId == parentUserId) {
                        fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                            "The profile resolved to an unsafe Android user id.")
                    }
                    commitOrStorageFailure(
                        store.edit()
                            .putInt(KEY_USER, profileUserId)
                            .putString(KEY_STAGE, ProfileSetupStage.PROFILE_CREATED.name),
                    )

                    users = listUsersRequired()
                    val exactOwned = users.singleOrNull { it.id == profileUserId && it.name == profileName &&
                        ProfileRecovery.validOwned(it, parentUserId, store.getBoolean("secondary", false)) }
                    if (exactOwned?.id != profileUserId) {
                        fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                            "Android did not list the exact saved profile.")
                    }

                    boundary()
                    _state.value = _state.value.copy(message = "Starting $setupLabel…", completed = 2, userId = profileUserId)
                    runRequired(ProfileSetupPlan.startProfile(profileUserId))
                    val stateOutput = runRequired(ProfileSetupPlan.profileState(profileUserId))
                    if (!stateOutput.contains("RUNNING_UNLOCKED", ignoreCase = true)) {
                        throw SetupFailure(ProfileFailureClassifier.local(
                            ProfileSetupFailureKind.PROFILE_NOT_UNLOCKED,
                            stateOutput,
                        ))
                    }
                    commitOrStorageFailure(store.edit().putString(KEY_STAGE, ProfileSetupStage.PROFILE_STARTED.name))

                    val labels = requested.associateBy { it.packageName }
                    requestedPackages.sorted().forEachIndexed { index, packageName ->
                        boundary()
                        if (packageName !in installedSourcePackages(ctx, setOf(packageName))) {
                            throw SetupFailure(ProfileFailureClassifier.local(
                                ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED,
                                "A selected app is no longer installed in your main profile: $packageName",
                            ))
                        }
                        val label = labels[packageName]?.label ?: appLabel(ctx, packageName)
                        _state.value = _state.value.copy(
                            message = "Adding $label…",
                            completed = index + 3,
                            userId = profileUserId,
                        )
                        var installed = profilePackages(profileUserId, packageName)
                        if (packageName !in installed) {
                            runRequired(ProfileSetupPlan.installExisting(profileUserId, packageName))
                            installed = profilePackages(profileUserId, packageName)
                        }
                        if (packageName !in installed) {
                            throw SetupFailure(ProfileFailureClassifier.local(
                                ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED,
                                "$label is not installed in $setupLabel after Android reported the install step complete.",
                            ))
                        }
                        if (packageName in selectedPackages) {
                            Layer2Workspaces.engine.register(
                                Workspace(
                                    workspaceId(profileUserId, packageName),
                                    label.take(80).ifBlank { packageName.take(80) },
                                    packageName,
                                    profileUserId,
                                ),
                            )
                        }
                    }
                    commitOrStorageFailure(store.edit().putString(KEY_STAGE, ProfileSetupStage.APPS_INSTALLED.name))

                    boundary()
                    _state.value = _state.value.copy(message = "Finishing $setupLabel…", completed = requestedPackages.size + 3)
                    runRequired(ProfileSetupPlan.markSetupComplete(profileUserId))
                    val setupComplete = runRequired(ProfileSetupPlan.readSetupComplete(profileUserId)).trim() == "1"
                    if (!setupComplete) {
                        fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                            "Android did not confirm that setup finished.")
                    }
                    commitOrStorageFailure(store.edit().putString(KEY_STAGE, ProfileSetupStage.SETUP_COMPLETE.name))

                    boundary()
                    users = listUsersRequired()
                    val finalUser = users.singleOrNull { it.id == profileUserId && it.name == profileName }
                    val finalState = runRequired(ProfileSetupPlan.profileState(profileUserId))
                    val finalInstalled = requestedPackages.flatMapTo(linkedSetOf()) { pkg -> profilePackages(profileUserId, pkg) }
                    val expectedWorkspaceIds = selectedPackages.associateWith { workspaceId(profileUserId, it) }
                    val workspaceUsers = Layer2Workspaces.engine.snapshot()
                        .filter { workspace -> workspace.id in expectedWorkspaceIds.values }
                        .groupBy { it.appPackage }
                        .mapValues { (_, workspaces) -> workspaces.map { it.androidUserId }.toSet() }
                    val finalSetupComplete = runRequired(ProfileSetupPlan.readSetupComplete(profileUserId)).trim() == "1"
                    val evidence = ProfileReadyEvidence(
                        user = finalUser,
                        parentUserId = parentUserId,
                        runningUnlocked = finalState.contains("RUNNING_UNLOCKED", ignoreCase = true),
                        selectedPackages = requestedPackages,
                        installedPackages = finalInstalled,
                        workspaceUserIdsByPackage = workspaceUsers,
                        setupComplete = finalSetupComplete,
                        journalUserId = store.getInt(KEY_USER, -1).takeIf { it > 0 },
                        secondaryUser = store.getBoolean("secondary", false),
                        workspacePackages = selectedPackages,
                    )
                    ProfileReadyVerifier.failure(evidence)?.let { throw SetupFailure(it) }

                    commitOrStorageFailure(
                        store.edit()
                            .putBoolean(KEY_READY, true)
                            .putStringSet(KEY_APPS, selectedPackages)
                            .putString(KEY_STAGE, ProfileSetupStage.READY.name),
                    )
                    ProfileRegistryStore.checkpoint(ctx, store)
                    _state.value = ProfileSetupStatus(
                        message = "Profile is ready",
                        completed = requestedPackages.size + 5,
                        total = requestedPackages.size + 5,
                        ready = true,
                        userId = profileUserId,
                        capabilities = evaluated.capabilities,
                    )
                }
            } catch (paused: SetupPaused) {
                _state.value = _state.value.copy(
                    busy = false,
                    ready = false,
                    issue = null,
                    message = paused.message.orEmpty().take(220),
                )
            } catch (error: SetupFailure) {
                val failure = error.failure.named(setupLabel)
                _state.value = _state.value.copy(
                    busy = false,
                    ready = false,
                    issue = failure,
                    message = failure.compactMessage().take(440),
                )
            } catch (error: Exception) {
                val failure = ProfileFailureClassifier.local(
                    ProfileSetupFailureKind.UNKNOWN_PLATFORM_FAILURE,
                    error.message?.take(180),
                ).named(setupLabel)
                _state.value = _state.value.copy(
                    busy = false,
                    ready = false,
                    issue = failure,
                    message = failure.compactMessage().take(440),
                )
            }
        }
    }

    private data class Execution(
        val result: ProfileCommandResult,
        val failure: ProfileSetupFailure?,
    )

    private fun executeRoot(command: ProfileSetupCommand): Execution {
        val shell = ProfileSetupPlan.shell(command)
        val startedAt = System.currentTimeMillis()
        // `dumpsys user` is long; its user types come after the users.
        val outputLimit = if (command.operation == ProfileSetupOperation.DUMP_USERS) 262_144 else OUTPUT_LIMIT
        val process = try {
            ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start()
        } catch (error: IOException) {
            val failure = ProfileFailureClassifier.fromCommand(
                command.operation,
                -127,
                error.message.orEmpty(),
                unavailable = true,
            ) ?: ProfileFailureClassifier.local(ProfileSetupFailureKind.ROOT_UNAVAILABLE)
            return execution(command, -127, error.message.orEmpty(), failure, startedAt)
        }

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < outputLimit) output.appendLine(line)
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        return try {
            val timeoutSeconds = when (command.operation) {
                ProfileSetupOperation.VERIFY_ROOT -> 8L
                // A backup archives whole app folders; a big profile takes minutes, not seconds.
                ProfileSetupOperation.BACKUP_APP_DATA -> 900L
                ProfileSetupOperation.MEASURE_APP_DATA, ProfileSetupOperation.REMOVE_USER, ProfileSetupOperation.STOP_USER -> 120L
                else -> 30L
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.join(500)
                val text = synchronized(output) { output.toString() }
                val failure = ProfileFailureClassifier.fromCommand(
                    command.operation,
                    null,
                    text,
                    timedOut = true,
                ) ?: ProfileFailureClassifier.local(ProfileSetupFailureKind.ROOT_COMMAND_FAILED)
                execution(command, null, text, failure, startedAt)
            } else {
                reader.join(500)
                val text = synchronized(output) { output.toString() }
                val exit = process.exitValue()
                val failure = ProfileFailureClassifier.fromCommand(command.operation, exit, text)
                execution(command, exit, text, failure, startedAt)
            }
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }

    private fun execution(
        command: ProfileSetupCommand,
        exitCode: Int?,
        output: String,
        failure: ProfileSetupFailure?,
        startedAt: Long = System.currentTimeMillis(),
    ): Execution {
        // Plan 57 W1: every privileged step, with Android's own words, for the debug file.
        runCatching {
            ProfileStepJournal.record(command.operation.name, command.tokens().joinToString(" "), exitCode,
                System.currentTimeMillis() - startedAt, output, failure?.kind?.name)
        }
        val result = ProfileCommandResult(
            operation = command.operation,
            exitCode = exitCode,
            output = output.take(if (command.operation == ProfileSetupOperation.DUMP_USERS) 262_144 else OUTPUT_LIMIT),
            classifiedResult = failure?.kind,
            sanitizedPlatformMessage = failure?.platformMessage,
            retryUseful = failure?.retryUseful ?: false,
        )
        lastCommandResult = result
        return Execution(result, failure)
    }

    internal fun runRequired(command: ProfileSetupCommand): String {
        val execution = executeRoot(command)
        execution.failure?.let { throw SetupFailure(it) }
        return execution.result.output
    }

    internal fun runBestEffort(command: ProfileSetupCommand): String? {
        val execution = executeRoot(command)
        return execution.result.output.takeIf { execution.failure == null }
    }

    internal fun listUsersRequired(): List<ProfileUserRecord> {
        val users = ProfileSetupParser.users(runRequired(ProfileSetupPlan.listUsers()))
        if (users.isEmpty()) {
            fail(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                "Android's user list could not be parsed safely.")
        }
        return users
    }

    private fun profilePackages(userId: Int, packageName: String): Set<String> =
        ProfileSetupParser.packages(runRequired(ProfileSetupPlan.listProfilePackage(userId, packageName)))

    private fun journalSnapshot(
        store: SharedPreferences,
        name: String,
        parentUserId: Int,
        selectedPackages: Set<String>,
    ): ProfileJournalSnapshot = ProfileJournalSnapshot(
        profileName = name,
        parentUserId = store.getInt(KEY_PARENT_USER, parentUserId),
        profileUserId = store.getInt(KEY_USER, -1).takeIf { it > 0 },
        selectedPackages = selectedPackages,
        stage = runCatching {
            ProfileSetupStage.valueOf(store.getString(KEY_STAGE, ProfileSetupStage.PLANNED.name).orEmpty())
        }.getOrDefault(ProfileSetupStage.PLANNED),
        secondaryUser = store.getBoolean("secondary", false),
    )

    private fun installedSourcePackages(context: Context, packages: Set<String>): Set<String> =
        packages.filterTo(linkedSetOf()) { packageName ->
            runCatching { context.packageManager.getApplicationInfo(packageName, 0); true }.getOrDefault(false)
        }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun workspaceId(userId: Int, packageName: String): String =
        "profileB-$userId-${UUID.nameUUIDFromBytes(packageName.toByteArray())}"

    private fun shizukuAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun newProfileName(): String =
        "Cyclone_" + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun commitOrStorageFailure(editor: SharedPreferences.Editor) {
        if (!editor.commit()) {
            fail(ProfileSetupFailureKind.STORAGE_FAILURE, "Cyclone couldn't save the setup progress.")
        }
    }

    private fun fail(kind: ProfileSetupFailureKind, detail: String? = null): Nothing =
        throw SetupFailure(ProfileFailureClassifier.local(kind, detail))
}
