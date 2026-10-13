package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.runtime.workspaces.ProfilePresentationPolicy
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.runtime.workspaces.Workspace
import com.cyclone.mobile.runtime.workspaces.WorkspaceState
import com.cyclone.mobile.ui.ProfileSetupPage
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.AskPipState
import com.cyclone.mobile.ui.v32.ask.AskRoundChip
import com.cyclone.mobile.ui.v32.ask.AskStatePip
import com.cyclone.mobile.ui.v32.ask.AskTextShadow
import com.cyclone.mobile.ui.v32.ask.AskTile
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ProfileCluster(
    val key: String,
    val recordId: String?,
    val label: String,
    val androidUserId: Int,
    val ready: Boolean,
    val workspaces: List<Workspace>,
    val packages: Set<String>,
    val active: Boolean,
    val waiting: Boolean,
    val current: Boolean = false,
    val owner: Boolean = false,
    val parentUserId: Int? = null,
    val cloakBindings: List<com.cyclone.mobile.connector.CycloneCloakBindingReference> = emptyList(),
) {
    val rootedByCloak: Boolean get() = cloakBindings.isNotEmpty()
    /** Plan 57 P3: the pill follows Cloak's own health report, not only whether a binding exists. */
    val cloakHealth: com.cyclone.mobile.connector.CloakHealth get() = com.cyclone.mobile.connector.CloakHealth.of(cloakBindings)
}

private data class ProfilePageSnapshot(
    val workspaces: List<Workspace>,
    val waiting: Set<String>,
    val records: List<CycloneProfileRecord>,
    val cloakBindings: List<com.cyclone.mobile.connector.CycloneCloakBindingReference>,
)

private data class AppProfileGroup(
    val packageName: String,
    val profiles: List<ProfileCluster>,
) {
    val activeCount: Int get() = profiles.count { it.active }
}

internal fun buildProfileClusters(
    allWorkspaces: List<Workspace>,
    allRecords: List<CycloneProfileRecord>,
    waiting: Set<String>,
    activeWorkspaceId: String?,
    processUser: Int,
    ownerUser: Int?,
    verifiedCurrentUser: Int?,
    foregroundExecuting: Boolean,
    cloakBindings: List<com.cyclone.mobile.connector.CycloneCloakBindingReference> = emptyList(),
): List<ProfileCluster> {
    // Plan 40 P1: profiles in Recently deleted are hidden everywhere but the trash.
    val trashedUsers = allRecords.filter { it.inTrash }.mapNotNull { it.androidUserId }.toSet()
    val records = allRecords.filterNot { it.inTrash }
    val workspaces = allWorkspaces.filterNot { it.androidUserId in trashedUsers }
    val recordByUser = records.mapNotNull { record -> record.androidUserId?.let { it to record } }.toMap()
    val workspacesByUser = workspaces.groupBy { it.androidUserId }
    val userIds = ProfilePresentationPolicy.visibleUsers(workspacesByUser.keys, recordByUser.keys,
        records.map { it.parentUserId }.toSet(), processUser, ownerUser)
    val clusters = userIds.map { userId ->
        val record = recordByUser[userId]
        val spaces = workspacesByUser[userId].orEmpty().sortedBy { it.label.lowercase() }
        val isWaiting = spaces.any { it.id in waiting }
        val isActive = ProfilePresentationPolicy.foregroundActive(userId, processUser, foregroundExecuting) || isWaiting || spaces.any { it.state == WorkspaceState.running || it.state == WorkspaceState.gated } ||
            spaces.any { it.id == activeWorkspaceId }
        ProfileCluster(
            key = record?.id ?: "android-user-$userId",
            recordId = record?.id,
            label = record?.label ?: if (userId == ownerUser || (ownerUser == null && userId == 0)) "Profile A" else "Phone profile",
            androidUserId = userId,
            ready = record?.ready ?: (userId == processUser || userId == ownerUser || spaces.isNotEmpty()),
            workspaces = spaces,
            packages = (record?.packages.orEmpty() + spaces.map { it.appPackage }).toSet(),
            active = isActive,
            waiting = isWaiting,
            current = ProfilePresentationPolicy.isCurrent(userId, verifiedCurrentUser),
            owner = userId == ownerUser,
            parentUserId = record?.parentUserId,
            cloakBindings = cloakBindings.filter { it.profileId == record?.id },
        )
    }.toMutableList()

    records.filter { it.androidUserId == null }.forEach { record ->
        clusters += ProfileCluster(
            key = record.id,
            recordId = record.id,
            label = record.label,
            androidUserId = -1,
            ready = false,
            workspaces = emptyList(),
            packages = record.packages,
            active = false,
            waiting = false,
            parentUserId = record.parentUserId,
        )
    }
    return clusters.distinctBy { it.key }
}

private fun buildAppGroups(profiles: List<ProfileCluster>): List<AppProfileGroup> =
    profiles.flatMap { profile -> profile.packages.map { it to profile } }
        .groupBy({ it.first }, { it.second })
        .map { (pkg, members) -> AppProfileGroup(pkg, members.distinctBy { it.key }) }
        .sortedWith(compareByDescending<AppProfileGroup> { it.activeCount }.thenByDescending { it.profiles.size }.thenBy { it.packageName })

/** Where the Profiles tab is: its home, or one of the pages a row opens. */
private enum class ProfilesScreen { HOME, DETAIL, DETAILS, SHARED_APPS, SHARED_APP, DELETED, ESSENTIALS }

private val WorkingBlue = Color(0xFF64D2FF)

/** A profile's state in one word and its dot colour; null colour means "ready", shown as its app count. */
private fun ProfileCluster.state(): Pair<String, Color?> = when {
    !ready -> "Setting up" to AskGlass.Waiting
    active -> "Working" to WorkingBlue
    current -> "In use" to AskGlass.Done
    else -> ProfileWords.apps(packages.size) to null
}

@Composable
fun CycloneProfilesPage(context: Context, refreshTick: Int, onAsk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(ProfilesScreen.HOME) }
    var selectedProfileKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroupPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var switchMessage by remember { mutableStateOf("") }
    var setup by remember { mutableStateOf(false) }
    // Plan 57 W7: a profile whose setup was started but not finished, when the owner asked for a new one.
    var unfinished by remember { mutableStateOf<com.cyclone.mobile.runtime.workspaces.UnfinishedSetup?>(null) }
    var workspaces by remember { mutableStateOf(emptyList<Workspace>()) }
    var records by remember { mutableStateOf(emptyList<CycloneProfileRecord>()) }
    var cloakBindings by remember { mutableStateOf(emptyList<com.cyclone.mobile.connector.CycloneCloakBindingReference>()) }
    var error by remember { mutableStateOf("") }
    // Plan 40 P1: Recently deleted, the automatic backups, and which dialog is open.
    var backups by remember { mutableStateOf(emptyList<com.cyclone.mobile.runtime.workspaces.ProfileBackup>()) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var looking by remember { mutableStateOf<String?>(null) }
    var debugOpen by remember { mutableStateOf(false) }
    // Plan 40 P2: what the last switch carried here, and whether the last carry out of here arrived.
    var carried by remember { mutableStateOf<com.cyclone.mobile.runtime.workspaces.CarryReport?>(null) }
    var carryFailed by remember { mutableStateOf(false) }
    // Plan 51 K3: entries approved connectors add. Shown after your profiles, marked as theirs.
    var connectorEntries by remember { mutableStateOf(emptyList<com.cyclone.mobile.connector.SelectorEntry>()) }

    var ownerUser by remember { mutableStateOf<Int?>(null) }
    var verifiedCurrentUser by remember { mutableStateOf<Int?>(null) }
    val processUser = ProfileSetupRuntime.currentUserId()
    val overlayActivity by OverlayChromeRuntime.activity.collectAsState()
    val foregroundExecuting = remember(overlayActivity) { OverlayChromeRuntime.hasExecutingTask() }
    LaunchedEffect(refreshTick) {
        verifiedCurrentUser = null
        carried = com.cyclone.mobile.runtime.workspaces.ProfileCarry.lastReport(context)
        carryFailed = com.cyclone.mobile.runtime.workspaces.ProfileCarry.lastSentFailed(context)
        withContext(Dispatchers.IO) { runCatching { ProfileSetupRuntime.visibleProfileIdentity() } }
            .onSuccess { (owner, current) -> ownerUser = owner; verifiedCurrentUser = current }
    }

    val task by WorkspaceTasks.state.collectAsState()
    val profileSetup by ProfileSetupRuntime.state.collectAsState()
    val revision by Layer2Workspaces.engine.revision.collectAsState()

    /** Plan 57 W7: + always starts a new plan; an unfinished one is offered to finish or discard first. */
    fun startNewProfile() {
        runCatching { ProfileSetupRuntime.startNewPlan(context) }
            .onSuccess { open -> if (open == null) setup = true else unfinished = open }
            .onFailure { error = it.message.orEmpty() }
    }

    fun refreshProfiles() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    Layer2Workspaces.initialize(context)
                    val saved = ProfileRegistryStore.records(context)
                    ProfilePageSnapshot(
                        Layer2Workspaces.engine.snapshot(),
                        Layer2Workspaces.engine.queue().toSet(),
                        saved,
                        com.cyclone.mobile.connector.CycloneCloakProfileBinding.readBindings(saved) { key ->
                            com.cyclone.mobile.connector.ProfileConfigStore.get(
                                context,
                                com.cyclone.mobile.connector.CycloneCloakProfileBinding.CONNECTOR_ID,
                                processUser,
                                key,
                            )
                        },
                    )
                }
            }
            loaded.onSuccess { snapshot ->
                workspaces = snapshot.workspaces
                waiting = snapshot.waiting
                records = snapshot.records
                cloakBindings = snapshot.cloakBindings
                error = ""
            }.onFailure {
                error = "Profiles couldn't load. Pull to try again, or open New profile to repair."
            }
            backups = withContext(Dispatchers.IO) { com.cyclone.mobile.runtime.workspaces.ProfileBackups.list(context) }
            connectorEntries = withContext(Dispatchers.IO) { com.cyclone.mobile.connector.ConnectorLauncher.selectorEntries(context) }
        }
    }

    LaunchedEffect(refreshTick, revision, profileSetup.ready, setup) { refreshProfiles() }

    val clusters = remember(workspaces, records, waiting, cloakBindings, task?.workspaceId, ownerUser, verifiedCurrentUser, foregroundExecuting) {
        buildProfileClusters(workspaces, records, waiting, task?.workspaceId, processUser, ownerUser, verifiedCurrentUser, foregroundExecuting, cloakBindings)
    }
    val activeProfiles = clusters.filter { it.active }.sortedBy { it.label.lowercase() }
    val allProfiles = clusters.sortedWith(compareByDescending<ProfileCluster> { it.current }.thenByDescending { it.owner }.thenByDescending { it.active }.thenBy { it.label.lowercase() })
    val appGroups = remember(clusters) { buildAppGroups(clusters) }
    val selectedProfile = clusters.firstOrNull { it.key == selectedProfileKey }
    val looks = remember(records) { records.associateBy { it.id } }
    val trash = remember(records) { com.cyclone.mobile.runtime.workspaces.ProfileTrash.order(records) }
    val ownerOnMain = ownerUser != null && ownerUser == verifiedCurrentUser

    fun back() {
        screen = when (screen) {
            ProfilesScreen.DETAILS -> ProfilesScreen.DETAIL
            ProfilesScreen.SHARED_APP -> ProfilesScreen.SHARED_APPS
            ProfilesScreen.DETAIL -> if (selectedGroupPackage != null) ProfilesScreen.SHARED_APP else ProfilesScreen.HOME
            else -> ProfilesScreen.HOME
        }
        if (screen == ProfilesScreen.HOME) { selectedProfileKey = null; selectedGroupPackage = null }
    }
    BackHandler(screen != ProfilesScreen.HOME) { back() }

    fun showProfile(profile: ProfileCluster) {
        selectedProfileKey = profile.key
        screen = ProfilesScreen.DETAIL
    }

    fun openProfile(profile: ProfileCluster) {
        if (busy) return
        val openingMain = profile.owner
        val recordId = profile.recordId
        if (!profile.ready || (!openingMain && recordId == null)) {
            error = "Finish setting up ${profile.label} before opening it."
            setup = true
            return
        }
        busy = true
        switchMessage = "Getting ${profile.label} ready…"
        error = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ProfileSetupRuntime.openProfile(context, if (openingMain) null else recordId) { message ->
                        scope.launch { switchMessage = message }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.take(160) ?: "Couldn't open ${profile.label}."
            } finally {
                busy = false
            }
        }
    }

    /** Runs a lifecycle step off the main thread, with its progress line, then reloads. */
    fun lifecycle(start: String, step: (onProgress: (String) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        switchMessage = start
        error = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) { step { message -> scope.launch { switchMessage = message } } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.take(200) ?: "That didn't work. Nothing was changed."
            } finally {
                busy = false
                refreshProfiles()
            }
        }
    }

    fun manageProfile(profile: ProfileCluster) {
        val recordId = profile.recordId
        if (recordId != null) {
            runCatching { ProfileSetupRuntime.selectProfile(context, recordId) }
                .onFailure { error = it.message.orEmpty() }
        }
        setup = true
    }

    // The open sheet, if any: edit the look, or a confirmation ("remove:<id>", "delete:<id>", "empty", "backup:<folder>").
    looking?.let { id ->
        records.singleOrNull { it.id == id }?.let { record ->
            ProfileLookDialog(record, onSave = { label, emoji, color ->
                looking = null
                runCatching { com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.setLook(context, id, label, emoji, color) }
                    .onFailure { error = it.message.orEmpty() }
                refreshProfiles()
            }, onDismiss = { looking = null })
        }
    }
    confirm?.let { (kind, target) ->
        val label = records.singleOrNull { it.id == target }?.label ?: "this profile"
        when (kind) {
            "remove" -> ProfileConfirmDialog(ProfileLifecycleCopy.REMOVE_TITLE, ProfileLifecycleCopy.removeBody(label), "Remove",
                onConfirm = {
                    confirm = null
                    selectedProfileKey = null
                    screen = ProfilesScreen.HOME
                    lifecycle("Removing $label…") { com.cyclone.mobile.runtime.workspaces.ProfileLifecycle.remove(context, target) }
                }, onDismiss = { confirm = null })
            "delete" -> ProfileConfirmDialog(ProfileLifecycleCopy.DELETE_TITLE, ProfileLifecycleCopy.deleteBody(label), "Delete",
                onConfirm = {
                    confirm = null
                    lifecycle("Backing up $label…") { progress -> com.cyclone.mobile.runtime.workspaces.ProfileLifecycle.deleteNow(context, target, progress) }
                }, onDismiss = { confirm = null })
            "empty" -> ProfileConfirmDialog(ProfileLifecycleCopy.EMPTY_TITLE, ProfileLifecycleCopy.EMPTY_BODY, "Delete all",
                onConfirm = {
                    confirm = null
                    lifecycle("Emptying Recently deleted…") { progress -> com.cyclone.mobile.runtime.workspaces.ProfileLifecycle.emptyTrash(context, progress) }
                }, onDismiss = { confirm = null })
            "backup" -> ProfileConfirmDialog("Delete this backup?", "It's removed from the phone. This can't be undone.", "Delete",
                onConfirm = {
                    confirm = null
                    lifecycle("Deleting the backup…") { com.cyclone.mobile.runtime.workspaces.ProfileBackups.delete(context, target) }
                }, onDismiss = { confirm = null })
        }
    }
    if (debugOpen) {
        ProfileSheet({ debugOpen = false }, "Debug report") { com.cyclone.mobile.ui.ProfileDebugButtons(profileSetup.issue) }
    }
    unfinished?.let { open ->
        ProfileSheet({ unfinished = null }, "Finish ${open.label}?") {
            Text("${open.label} was started but isn't finished.", Modifier.fillMaxWidth(), color = AskGlass.Muted,
                fontSize = 15.sp, textAlign = TextAlign.Center)
            ProfileButton("Finish ${open.label}", { unfinished = null; setup = true }, Modifier.fillMaxWidth(), style = ProfileButtonStyle.PRIMARY)
            ProfileButton("Start a new one", {
                unfinished = null
                runCatching { ProfileSetupRuntime.discardUnfinished(context) }
                    .onSuccess { setup = true }
                    .onFailure { error = it.message.orEmpty() }
            }, Modifier.fillMaxWidth())
            ProfileButton("Cancel", { unfinished = null }, Modifier.fillMaxWidth(), style = ProfileButtonStyle.QUIET)
        }
    }

    when (screen) {
        ProfilesScreen.DETAIL, ProfilesScreen.DETAILS -> if (selectedProfile != null) {
            if (screen == ProfilesScreen.DETAILS) {
                ProfileIdentityDetails429(profile = selectedProfile, onBack = ::back)
            } else {
                val exactTask = task?.takeIf { current -> selectedProfile.workspaces.any { it.id == current.workspaceId } }
                ProfileDetail429(
                    context = context,
                    profile = selectedProfile,
                    look = selectedProfile.recordId?.let(looks::get),
                    task = exactTask,
                    busy = busy,
                    switchMessage = switchMessage,
                    error = error,
                    backLabel = if (selectedGroupPackage != null) appLabel(context, selectedGroupPackage!!) else "Profiles",
                    onBack = ::back,
                    onOpenProfile = { openProfile(selectedProfile) },
                    onManage = { manageProfile(selectedProfile) },
                    canStartTask = ProfilePresentationPolicy.canStartTask(selectedProfile.androidUserId, processUser, verifiedCurrentUser, busy),
                    onAsk = onAsk,
                    onIdentifiers = { screen = ProfilesScreen.DETAILS },
                    onRename = selectedProfile.recordId?.let { id -> { looking = id } },
                    // The main profile and the one you're in can't be removed; the runtime checks again with root.
                    onRemove = selectedProfile.recordId?.takeIf { !selectedProfile.owner && !selectedProfile.current }?.let { id -> { confirm = "remove" to id } },
                )
            }
        } else LaunchedEffect(Unit) { screen = ProfilesScreen.HOME }

        ProfilesScreen.SHARED_APPS -> SharedAppsPage(context, appGroups, onBack = ::back) { group ->
            selectedGroupPackage = group.packageName
            screen = ProfilesScreen.SHARED_APP
        }

        ProfilesScreen.SHARED_APP -> appGroups.firstOrNull { it.packageName == selectedGroupPackage }?.let { group ->
            AppGroupDetail429(context, group, looks, onBack = ::back, onProfileDetail = ::showProfile)
        } ?: LaunchedEffect(Unit) { screen = ProfilesScreen.SHARED_APPS }

        ProfilesScreen.DELETED -> DeletedPage(
            context = context,
            trash = trash,
            backups = backups,
            busy = busy,
            switchMessage = switchMessage,
            error = error,
            onBack = ::back,
            onRestore = { record -> lifecycle("Restoring ${record.label}…") { com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.restore(context, record.id) } },
            onDelete = { record -> confirm = "delete" to record.id },
            onEmpty = { confirm = "empty" to "" },
            onDeleteBackup = { backup -> confirm = "backup" to backup.folder },
        )

        ProfilesScreen.ESSENTIALS -> com.cyclone.mobile.ui.ProfileEssentialsPage(onBack = ::back)

        ProfilesScreen.HOME -> LazyColumn(
            contentPadding = cyclonePageInsets(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                // R6: the large title, and + for a new profile, the one primary action here.
                CyclonePageHeader(
                    title = "Profiles",
                    subtitle = clusters.firstOrNull { it.current }?.let { "You're in ${it.label}" } ?: "One phone, many spaces",
                    trailing = {
                        AskRoundChip("New profile", { startNewProfile() }, size = 48.dp, shineOffset = 0.2f) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(24.dp), tint = AskGlass.Ink)
                        }
                    },
                )
            }
            item { CyclonePendingRequests() }

            profileSetup.issue?.takeIf { !profileSetup.busy && !profileSetup.ready }?.let { issue ->
                item {
                    // Plan 57 W9: the problem, its fixes and the debug file, as one calm card.
                    com.cyclone.mobile.ui.ProfileProblemPanel(issue, onRetry = { setup = true }, onChanged = { refreshProfiles() })
                }
            }
            if (busy) item { ProfileNotice(switchMessage, ProfileNoticeKind.WORKING) }
            if (error.isNotBlank()) item { ProfileNotice(error, ProfileNoticeKind.PROBLEM) }

            // The profiles themselves: faces in a grid, the one you're in ringed, and a New tile at the end.
            val tiles: List<ProfileCluster?> = allProfiles + listOf(null)
            items(tiles.chunked(3), key = { row -> row.joinToString { it?.key ?: "new" } }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { profile ->
                        Box(Modifier.weight(1f)) {
                            if (profile == null) NewProfileTile { startNewProfile() }
                            else ProfileIdentityCard429(profile, profile.recordId?.let(looks::get)) { showProfile(profile) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (carried != null || carryFailed) item { ProfileCarryNote(carried, carryFailed) }

            if (activeProfiles.isNotEmpty()) {
                item { ProfileSectionLabel("Working now") }
                item {
                    ProfileGroup {
                        activeProfiles.forEachIndexed { index, profile ->
                            if (index > 0) ProfileRowDivider()
                            val exactTask = task?.takeIf { current -> profile.workspaces.any { it.id == current.workspaceId } }
                            ActiveProfileCard429(context, profile, exactTask) {
                                if (exactTask != null) UiTask(exactTask).open(context) else showProfile(profile)
                            }
                        }
                    }
                }
            }

            // Plan 57 §5.4: how full the phone is, and Make room, for the owner on Main.
            if (ownerOnMain) item { com.cyclone.mobile.ui.ProfileRoomCard() }

            item {
                ProfileGroup(shineOffset = 0.55f) {
                    if (appGroups.isNotEmpty()) {
                        ProfileRow("Shared apps", { screen = ProfilesScreen.SHARED_APPS },
                            leading = { ProfileIconTile(Icons.Rounded.Apps, Color(0xFF5E5CE6)) }, value = "${appGroups.size}")
                        ProfileRowDivider()
                    }
                    ProfileRow("In every profile", { screen = ProfilesScreen.ESSENTIALS },
                        leading = { ProfileIconTile(Icons.Rounded.Layers, Color(0xFF30B85A)) })
                    if (trash.isNotEmpty() || backups.isNotEmpty()) {
                        ProfileRowDivider()
                        ProfileRow("Recently deleted", { screen = ProfilesScreen.DELETED },
                            leading = { ProfileIconTile(Icons.Rounded.DeleteOutline, Color(0xFF8E8E93)) },
                            value = if (trash.isNotEmpty()) "${trash.size}" else null)
                    }
                    ProfileRowDivider()
                    ProfileRow("Debug report", { debugOpen = true },
                        leading = { ProfileIconTile(Icons.Rounded.BugReport, Color(0xFFFF9F0A)) })
                }
            }

            if (connectorEntries.isNotEmpty()) {
                item { ProfileSectionLabel("From your connectors") }
                item {
                    ProfileGroup(shineOffset = 0.65f) {
                        connectorEntries.forEachIndexed { index, entry ->
                            if (index > 0) ProfileRowDivider()
                            ConnectorEntryCard429(context, entry) {
                                if (!com.cyclone.mobile.connector.ConnectorLauncher.open(context, entry)) {
                                    error = "${entry.connectorLabel} couldn't be opened. Check it in Settings → Connectors."
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (setup) ProfileSetupPage { setup = false; refreshProfiles() }
}

/** One profile on the home grid: its face (ringed when it's the one you're in), its name and one word of state. */
@Composable
private fun ProfileIdentityCard429(profile: ProfileCluster, look: CycloneProfileRecord?, onOpen: () -> Unit) {
    val (state, dot) = profile.state()
    Column(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(22.dp))
            .clickable(role = Role.Button, onClickLabel = "Open ${profile.label}", onClick = onOpen)
            .profileTileSemantics("${profile.label}. $state")
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
            ProfileAvatar(profile.label, look?.emoji, look?.color, size = 70.dp, ring = profile.current, dimmed = !profile.ready)
        }
        Text(profile.label, color = AskGlass.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(shadow = AskTextShadow))
        if (dot != null) ProfileStatus(state, dot)
        else Text(state, color = AskGlass.Muted, fontSize = 13.sp, maxLines = 1, style = TextStyle(shadow = AskTextShadow))
    }
}

/** The last tile of the grid: an outlined circle with +. */
@Composable
private fun NewProfileTile(onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .profileTileSemantics("New profile")
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(70.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                    .border(1.2.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, null, Modifier.size(30.dp), tint = AskGlass.Ink) }
        }
        Text("New", color = AskGlass.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            style = TextStyle(shadow = AskTextShadow))
        Text(" ", fontSize = 13.sp)
    }
}

/** A profile doing work, as a row: the app it works in, what it's doing, and its state mark. */
@Composable
private fun ActiveProfileCard429(context: Context, profile: ProfileCluster, task: WorkspaceTaskUi?, onOpen: () -> Unit) {
    val activeWorkspace = profile.workspaces.firstOrNull { it.id == task?.workspaceId }
        ?: profile.workspaces.firstOrNull { it.state == WorkspaceState.running || it.state == WorkspaceState.gated }
        ?: profile.workspaces.firstOrNull()
    val packageName = task?.packageName ?: activeWorkspace?.appPackage ?: profile.packages.firstOrNull()
    val app = packageName?.let { appLabel(context, it) } ?: "Profile"
    val needsYou = task?.phase == TaskPhase.REVIEW || profile.workspaces.any { it.state == WorkspaceState.gated }
    ProfileRow(
        title = "$app · ${profile.label}",
        subtitle = task?.subtitle?.takeIf { it.isNotBlank() } ?: if (profile.waiting) "Waiting for its turn" else "Working",
        onClick = onOpen,
        leading = { AskTile { CycloneAppIcon(packageName, Modifier.size(26.dp)) } },
        chevron = false,
        trailing = {
            AskStatePip(
                when {
                    needsYou -> AskPipState.NEEDS_YOU
                    else -> AskPipState.WORKING
                },
            )
        },
    )
}

/** Plan 51 K3: an approved connector's entry. Its icon comes from the connector's own app; a tap opens its screen. */
@Composable
private fun ConnectorEntryCard429(context: Context, item: com.cyclone.mobile.connector.SelectorEntry, onOpen: () -> Unit) {
    val icon = remember(item.key, item.entry.icon) {
        com.cyclone.mobile.connector.ConnectorLauncher.icon(context, item)
            ?.let { runCatching { it.toBitmap(64, 64).asImageBitmap() }.getOrNull() }
    }
    ProfileRow(
        title = item.entry.label,
        subtitle = listOf(item.entry.subtitle, item.entry.statusText).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "From ${item.connectorLabel}" },
        onClick = onOpen,
        leading = {
            AskTile {
                if (icon != null) androidx.compose.foundation.Image(icon, null, Modifier.size(26.dp))
                else CycloneAppIcon(item.packageName, Modifier.size(26.dp))
            }
        },
        trailing = {
            when (item.entry.state) {
                "attention" -> ProfileStatus("Needs you", AskGlass.Waiting)
                "off" -> ProfileStatus("Off", AskGlass.Faint)
            }
        },
    )
}

/** Shared apps: every app that lives in more than one place, with how many profiles hold it. */
@Composable
private fun SharedAppsPage(context: Context, groups: List<AppProfileGroup>, onBack: () -> Unit, onOpen: (AppProfileGroup) -> Unit) {
    LazyColumn(contentPadding = cyclonePageInsets(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CycloneBackRow("Profiles", onBack) }
        item { ProfileTitle("Shared apps", "The same app, across your profiles") }
        item {
            ProfileGroup {
                groups.forEachIndexed { index, group ->
                    if (index > 0) ProfileRowDivider()
                    AppGroupCard429(context, group) { onOpen(group) }
                }
            }
        }
    }
}

@Composable
private fun AppGroupCard429(context: Context, group: AppProfileGroup, onOpen: () -> Unit) {
    ProfileRow(
        title = appLabel(context, group.packageName),
        subtitle = if (group.profiles.size == 1) "1 profile" else "${group.profiles.size} profiles",
        onClick = onOpen,
        leading = { AskTile { CycloneAppIcon(group.packageName, Modifier.size(26.dp)) } },
        trailing = { if (group.activeCount > 0) ProfileStatus("Working", WorkingBlue) },
    )
}

@Composable
private fun AppGroupDetail429(
    context: Context,
    group: AppProfileGroup,
    looks: Map<String, CycloneProfileRecord>,
    onBack: () -> Unit,
    onProfileDetail: (ProfileCluster) -> Unit,
) {
    LazyColumn(contentPadding = cyclonePageInsets(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CycloneBackRow("Shared apps", onBack) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(76.dp).clip(ContinuousRoundedRectangle(22.dp)).background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) { CycloneAppIcon(group.packageName, Modifier.size(52.dp)) }
                Text(appLabel(context, group.packageName), color = AskGlass.Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = AskTextShadow))
                Text(if (group.profiles.size == 1) "In 1 profile" else "In ${group.profiles.size} profiles", color = AskGlass.Muted,
                    fontSize = 15.sp, style = TextStyle(shadow = AskTextShadow))
            }
        }
        item {
            ProfileGroup {
                group.profiles.sortedBy { it.label.lowercase() }.forEachIndexed { index, profile ->
                    if (index > 0) ProfileRowDivider()
                    val look = profile.recordId?.let(looks::get)
                    val (state, dot) = profile.state()
                    ProfileRow(
                        title = profile.label,
                        onClick = { onProfileDetail(profile) },
                        leading = { ProfileAvatar(profile.label, look?.emoji, look?.color, size = 36.dp) },
                        trailing = { if (dot != null) ProfileStatus(state, dot) },
                    )
                }
            }
        }
    }
}

/** Recently deleted: each profile with Restore, then the automatic backups. */
@Composable
private fun DeletedPage(
    context: Context,
    trash: List<CycloneProfileRecord>,
    backups: List<com.cyclone.mobile.runtime.workspaces.ProfileBackup>,
    busy: Boolean,
    switchMessage: String,
    error: String,
    onBack: () -> Unit,
    onRestore: (CycloneProfileRecord) -> Unit,
    onDelete: (CycloneProfileRecord) -> Unit,
    onEmpty: () -> Unit,
    onDeleteBackup: (com.cyclone.mobile.runtime.workspaces.ProfileBackup) -> Unit,
) {
    val now = System.currentTimeMillis()
    LazyColumn(contentPadding = cyclonePageInsets(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CycloneBackRow("Profiles", onBack) }
        item { ProfileTitle("Recently deleted", "Kept ${com.cyclone.mobile.runtime.workspaces.ProfileTrash.TRASH_DAYS} days, then backed up") }
        if (busy) item { ProfileNotice(switchMessage, ProfileNoticeKind.WORKING) }
        if (error.isNotBlank()) item { ProfileNotice(error, ProfileNoticeKind.PROBLEM) }
        if (trash.isEmpty()) {
            item { ProfileNotice("Nothing here.", ProfileNoticeKind.INFO) }
        } else {
            item {
                ProfileGroup {
                    trash.forEachIndexed { index, record ->
                        if (index > 0) ProfileRowDivider()
                        ProfileTrashCard(record, now, busy, onRestore = { onRestore(record) }, onDelete = { onDelete(record) })
                    }
                }
            }
            item { ProfileButton("Delete all", onEmpty, Modifier.fillMaxWidth(), style = ProfileButtonStyle.DESTRUCTIVE, enabled = !busy) }
        }
        if (backups.isNotEmpty()) {
            item { ProfileSectionLabel("Backups") }
            item {
                ProfileGroup {
                    backups.forEachIndexed { index, backup ->
                        if (index > 0) ProfileRowDivider()
                        ProfileBackupRow(context, backup, busy) { onDeleteBackup(backup) }
                    }
                }
            }
        }
    }
}

/**
 * One profile: its face and name large, one primary action (Switch, or Ask when you're already in it), its apps as a
 * grid of icons, and the few things you can change.
 */
@Composable
private fun ProfileDetail429(
    context: Context,
    profile: ProfileCluster,
    look: CycloneProfileRecord?,
    task: WorkspaceTaskUi?,
    busy: Boolean,
    switchMessage: String,
    error: String,
    backLabel: String,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onManage: () -> Unit,
    canStartTask: Boolean,
    onAsk: () -> Unit,
    onIdentifiers: () -> Unit,
    onRename: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var localMessage by remember { mutableStateOf("") }
    val (state, dot) = profile.state()
    val apps: List<Pair<String, Workspace?>> = if (profile.workspaces.isNotEmpty()) {
        profile.workspaces.map { it.appPackage to it }
    } else profile.packages.sorted().map { it to null }
    LazyColumn(
        contentPadding = cyclonePageInsets(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { CycloneBackRow(backLabel, onBack) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileAvatar(profile.label, look?.emoji, look?.color, size = 104.dp, ring = profile.current)
                Text(profile.label, color = AskGlass.Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp),
                    style = TextStyle(shadow = AskTextShadow))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (dot != null) ProfileStatus(state, dot)
                    Text((if (dot != null) "· " else "") + ProfileWords.apps(profile.packages.size), color = AskGlass.Muted, fontSize = 14.sp, maxLines = 1,
                        style = TextStyle(shadow = AskTextShadow))
                    // Cloak is shown only when it's in play; a plain profile needs no label.
                    if (profile.cloakHealth != com.cyclone.mobile.connector.CloakHealth.NATIVE) ProfileSourcePill429(profile.cloakHealth)
                }
            }
        }

        item {
            when {
                !profile.ready -> ProfileButton("Finish setup", onManage, Modifier.fillMaxWidth(), style = ProfileButtonStyle.PRIMARY)
                profile.current -> ProfileButton("Ask Cyclone here", onAsk, Modifier.fillMaxWidth(), style = ProfileButtonStyle.PRIMARY,
                    icon = Icons.Rounded.AutoAwesome, enabled = canStartTask)
                else -> ProfileButton("Switch to ${profile.label}", onOpenProfile, Modifier.fillMaxWidth(),
                    style = ProfileButtonStyle.PRIMARY, icon = Icons.Rounded.SwapHoriz, enabled = !busy)
            }
        }
        if (busy) item { ProfileNotice(switchMessage, ProfileNoticeKind.WORKING) }
        if (error.isNotBlank()) item { ProfileNotice(error, ProfileNoticeKind.PROBLEM) }
        if (localMessage.isNotBlank()) item { ProfileNotice(localMessage, ProfileNoticeKind.INFO) }
        if (task != null) item { CycloneTaskProgress(task) }

        item { ProfileSectionLabel("Apps", action = if (profile.owner) null else "Edit", onAction = onManage) }
        item {
            if (apps.isEmpty()) {
                ProfileNotice(if (profile.owner) "All your phone's apps live here." else "No apps yet. Tap Edit to add some.", ProfileNoticeKind.INFO)
            } else {
                ProfileGroup(shineOffset = 0.5f) {
                    apps.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                            row.forEach { (pkg, workspace) ->
                                val label = appLabel(context, pkg)
                                Column(
                                    Modifier.weight(1f).clip(ContinuousRoundedRectangle(16.dp))
                                        .clickable(enabled = workspace != null, role = Role.Button) {
                                            val space = workspace ?: return@clickable
                                            scope.launch {
                                                localMessage = "Opening $label…"
                                                val result = withContext(Dispatchers.IO) { SessionKernel.switchWorkspace(context, space.id) }
                                                localMessage = if (result.ok) "Opened $label" else "Couldn't open $label in ${profile.label}."
                                            }
                                        }
                                        .profileTileSemantics(label)
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    CycloneAppIcon(pkg, Modifier.size(46.dp))
                                    Text(label, color = AskGlass.Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp))
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        item {
            ProfileGroup(shineOffset = 0.6f) {
                if (onRename != null) {
                    ProfileRow("Name & look", onRename, leading = { ProfileIconTile(Icons.Rounded.Edit, Color(0xFF3E7BFA)) })
                    ProfileRowDivider()
                }
                ProfileRow("Details", onIdentifiers, leading = { ProfileIconTile(Icons.Rounded.Info, Color(0xFF8E8E93)) })
                if (onRemove != null) {
                    ProfileRowDivider()
                    ProfileRow("Remove profile", onRemove, destructive = true, chevron = false,
                        leading = { ProfileIconTile(Icons.Rounded.DeleteOutline, ProfileGlass.Destructive) })
                }
            }
        }
    }
}

/** The ids Cyclone keeps for a profile, each copyable, and what it never reads. */
@Composable
private fun ProfileIdentityDetails429(
    profile: ProfileCluster,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var copyMessage by remember(profile.key) { mutableStateOf("") }
    fun copy(label: String, value: String) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        if (clipboard == null) {
            copyMessage = "Clipboard isn't available."
            return
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        copyMessage = "$label copied."
    }
    val androidUser = profile.androidUserId.takeIf { it >= 0 }

    LazyColumn(
        contentPadding = cyclonePageInsets(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { CycloneBackRow(profile.label, onBack) }
        item { ProfileTitle("Details") }
        item {
            ProfileGroup {
                ProfileIdentifierRow429("Cyclone profile ID", profile.recordId, profile.recordId?.let { { copy("Cyclone profile ID", it) } })
                ProfileRowDivider(14.dp)
                ProfileIdentifierRow429("Android user ID", androidUser?.toString(), androidUser?.let { { copy("Android user ID", it.toString()) } })
                profile.parentUserId?.let { parent ->
                    ProfileRowDivider(14.dp)
                    ProfileIdentifierRow429("Parent Android user ID", parent.toString(), { copy("Parent Android user ID", parent.toString()) })
                }
            }
        }
        if (profile.cloakBindings.isNotEmpty()) {
            item { ProfileSectionLabel("Cyclone Cloak") }
            item {
                ProfileGroup {
                    profile.cloakBindings.sortedBy { it.packageName }.forEachIndexed { index, binding ->
                        if (index > 0) ProfileRowDivider(14.dp)
                        ProfileIdentifierRow429("Cloak profile ID · ${appLabel(context, binding.packageName)}", binding.cloakProfileId,
                            { copy("Cloak profile ID", binding.cloakProfileId) })
                    }
                }
            }
        }
        item {
            ProfileNotice(
                "Cyclone never reads Android ID, IMEI or Device serial number. From Cloak it gets only the profile reference: " +
                    "Cloak's generated device values and live root/module status aren't shared with Cyclone.",
                ProfileNoticeKind.INFO,
            )
        }
        if (copyMessage.isNotBlank()) item { ProfileNotice(copyMessage, ProfileNoticeKind.INFO) }
    }
}

@Composable
private fun ProfileIdentifierRow429(label: String, value: String?, onCopy: (() -> Unit)?) {
    ProfileRow(
        title = value ?: "Not set",
        subtitle = label,
        onClick = onCopy,
        chevron = false,
        trailing = {
            if (onCopy != null) Icon(Icons.Rounded.ContentCopy, "Copy $label", Modifier.size(18.dp), tint = AskGlass.Muted)
        },
    )
}

/** Plan 57 P3: Cloak's health in one short word with its colour (Rooted, Rooted · check, Rooted · not working). */
@Composable
private fun ProfileSourcePill429(health: com.cyclone.mobile.connector.CloakHealth) {
    val warning = health == com.cyclone.mobile.connector.CloakHealth.CHECK || health == com.cyclone.mobile.connector.CloakHealth.NOT_WORKING
    ProfileStatus(health.pill, if (warning) ProfileGlass.Destructive else Color(0xFFBF5AF2))
}
