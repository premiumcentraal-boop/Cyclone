package com.cyclone.mobile.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.runtime.workspaces.ProfileApp
import com.cyclone.mobile.runtime.workspaces.ProfileAppSelection
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.runtime.workspaces.RootProbe
import com.cyclone.mobile.runtime.workspaces.RootStatus
import com.cyclone.mobile.runtime.workspaces.Workspace
import com.cyclone.mobile.ui.v32.ProfileAppStack
import com.cyclone.mobile.ui.v32.ProfileAvatar
import com.cyclone.mobile.ui.v32.ProfileButton
import com.cyclone.mobile.ui.v32.ProfileButtonStyle
import com.cyclone.mobile.ui.v32.ProfileCalmPage
import com.cyclone.mobile.ui.v32.ProfileGlass
import com.cyclone.mobile.ui.v32.ProfileGroup
import com.cyclone.mobile.ui.v32.ProfileIconTile
import com.cyclone.mobile.ui.v32.ProfileNotice
import com.cyclone.mobile.ui.v32.ProfileNoticeKind
import com.cyclone.mobile.ui.v32.ProfileRow
import com.cyclone.mobile.ui.v32.ProfileRowDivider
import com.cyclone.mobile.ui.v32.ProfileSectionLabel
import com.cyclone.mobile.ui.v32.ProfileStatus
import com.cyclone.mobile.ui.v32.ProfileTextField
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.AskRoundChip
import com.cyclone.mobile.ui.v32.ask.AskTextShadow
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PROFILE_SETUP_PREFS = "cyclone_profile_setup"

@Composable
internal fun ProfileSetup429(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by ProfileSetupRuntime.state.collectAsState()
    val initialRecord = remember {
        ProfileRegistryStore.records(context).firstOrNull { it.androidUserId == ProfileSetupRuntime.existingUser(context) }
    }
    var page by rememberSaveable { mutableIntStateOf(if (ProfileSetupRuntime.existingUser(context) != null) 3 else 0) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<ProfileApp>()) }
    var selected by rememberSaveable(stateSaver = Saver<Set<String>, ArrayList<String>>(
        save = { ArrayList(it) }, restore = { it.toSet() },
    )) { mutableStateOf(ProfileSetupRuntime.selectedPackages(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var registered by remember { mutableStateOf(emptyList<Workspace>()) }
    var records by remember { mutableStateOf(ProfileRegistryStore.records(context)) }
    var onMainProfile by remember { mutableStateOf<Boolean?>(null) }
    var profileName by rememberSaveable {
        mutableStateOf(initialRecord?.label ?: suggestedProfileName(ProfileRegistryStore.records(context).map { it.label }))
    }
    var renaming by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    Layer2Workspaces.initialize(context)
                    Layer2Workspaces.engine.snapshot()
                }.getOrDefault(emptyList())
            }
            registered = snapshot
            records = ProfileRegistryStore.records(context)
            val current = records.firstOrNull { it.androidUserId == ProfileSetupRuntime.existingUser(context) }
            if (current != null && !renaming) profileName = current.label
        }
    }

    fun persistFriendlyName(): Boolean {
        return runCatching {
            val clean = ProfileRegistryStore.cleanLabel(profileName)
            val setupPrefs = context.getSharedPreferences(PROFILE_SETUP_PREFS, Context.MODE_PRIVATE)
            val currentId = setupPrefs.getString("name", null)
            val saved = ProfileRegistryStore.records(context)
            check(saved.none { it.id != currentId && it.label.equals(clean, ignoreCase = true) }) {
                "Another profile already uses that name."
            }
            check(setupPrefs.edit().putString(ProfileRegistryStore.JOURNAL_DISPLAY_LABEL, clean).commit()) {
                "Couldn't save the profile name."
            }
            if (currentId != null && saved.any { it.id == currentId }) {
                ProfileRegistryStore.rename(context, currentId, clean)
            }
            profileName = clean
            message = ""
            true
        }.getOrElse {
            message = it.message ?: "Choose another profile name."
            false
        }
    }

    fun chooseApps() {
        if (checking) return
        if (!persistFriendlyName()) return
        checking = true
        message = "Checking your phone…"
        scope.launch {
            try {
                val root = withContext(Dispatchers.IO) { RootProbe.check() }
                if (root == RootStatus.ROOTED) {
                    withContext(Dispatchers.IO) { ProfileSetupRuntime.refreshExisting(context) }
                    apps = withContext(Dispatchers.IO) { ProfileSetupRuntime.apps(context) }
                    selected = ProfileAppSelection.normalize(apps, selected)
                    page = 1
                    message = ""
                } else {
                    message = "Cyclone needs root access to create another phone profile. Allow the root request, then try again."
                }
            } catch (error: Exception) {
                message = error.message ?: "Couldn't load apps. Try again."
            } finally {
                checking = false
            }
        }
    }

    fun goBack() {
        if (progress.busy) ProfileSetupRuntime.stop()
        else if (page == 1 || page == 2) {
            page -= 1
            message = ""
        } else onClose()
    }

    fun openWholeProfile(record: CycloneProfileRecord) {
        scope.launch {
            message = "Opening ${record.label}…"
            message = withContext(Dispatchers.IO) {
                runCatching {
                    ProfileSetupRuntime.openProfile(context, record.id)
                    "Opened ${record.label}"
                }.getOrElse { it.message ?: "Couldn't open ${record.label}." }
            }
        }
    }

    fun openWorkspace(workspace: Workspace) {
        if (CycloneAccessibilityService.instance == null) {
            message = "Allow phone control so Cyclone can open the right app in this profile."
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        scope.launch {
            message = "Opening ${workspace.label}…"
            val result = withContext(Dispatchers.IO) { SessionKernel.switchWorkspace(context, workspace.id) }
            message = if (result.ok) "Opened ${workspace.label}" else "Couldn't safely open that app in this profile."
        }
    }

    LaunchedEffect(progress.ready, progress.userId) {
        if (progress.ready) {
            persistFriendlyName()
            page = 3
            refresh()
        }
    }
    LaunchedEffect(Unit) {
        onMainProfile = withContext(Dispatchers.IO) {
            runCatching { ProfileSetupRuntime.currentUserId() == ProfileSetupRuntime.profileAUserId() }.getOrNull()
        }
        refresh()
    }
    LaunchedEffect(page) {
        // A saved picker/review page can be restored before its app catalogue is loaded.
        if (page in 1..2 && apps.isEmpty()) {
            checking = true
            try {
                apps = withContext(Dispatchers.IO) { ProfileSetupRuntime.apps(context) }
                selected = ProfileAppSelection.normalize(apps, selected)
            } catch (error: Exception) {
                message = error.message ?: "Couldn't load apps. Try again."
            } finally {
                checking = false
            }
        }
    }

    Dialog(
        onDismissRequest = {
            goBack()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ProfileCalmPage {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                ProfileSetupTopBar(
                    step = if (progress.busy) null else page.takeIf { it in 0..2 },
                    onBack = ::goBack,
                    onClose = {
                        if (progress.busy) ProfileSetupRuntime.stop()
                        onClose()
                    },
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (progress.busy) {
                        CreatingProfileState(profileName, progress.message, progress.completed, progress.total) {
                            ProfileSetupRuntime.stop()
                        }
                    } else {
                        when (page) {
                            0 -> NameProfileStep(
                                name = profileName,
                                onName = { profileName = it },
                                onContinue = ::chooseApps,
                                checking = checking,
                                onMainProfile = onMainProfile,
                                onReturnMain = {
                                    scope.launch {
                                        message = withContext(Dispatchers.IO) {
                                            runCatching {
                                                ProfileSetupRuntime.openProfile(context, null)
                                                "Returned to Main"
                                            }.getOrElse { it.message ?: "Couldn't return to Main." }
                                        }
                                    }
                                },
                            )

                            1 -> ChooseAppsStep(
                                apps = apps,
                                selected = selected,
                                query = query,
                                onQuery = { query = it },
                                onToggle = { pkg, checked -> selected = ProfileAppSelection.toggle(apps, selected, pkg, checked) },
                                onContinue = { if (ProfileAppSelection.canConfirm(apps, selected)) page = 2 },
                            )

                            2 -> ReviewProfileStep(
                                context = context,
                                name = profileName,
                                apps = apps.filter { it.packageName in selected },
                                onCreate = {
                                    if (!checking && ProfileAppSelection.canConfirm(apps, selected) && persistFriendlyName()) {
                                        message = ""
                                        ProfileSetupRuntime.create(context, apps.filter { it.packageName in selected })
                                    }
                                },
                                onChangeApps = { page = 1 },
                                canCreate = !checking && ProfileAppSelection.canConfirm(apps, selected),
                            )

                            else -> {
                                val currentUser = ProfileSetupRuntime.existingUser(context)
                                val record = records.firstOrNull { it.androidUserId == currentUser }
                                    ?: records.firstOrNull { it.label == profileName }
                                val spaces = registered.filter { it.androidUserId == currentUser }
                                ReadyProfileStep(
                                    context = context,
                                    record = record,
                                    profileName = profileName,
                                    workspaces = spaces,
                                    packageNames = record?.packages.orEmpty(),
                                    renaming = renaming,
                                    onRenaming = { renaming = it },
                                    onName = { profileName = it },
                                    onSaveName = {
                                        if (persistFriendlyName()) {
                                            renaming = false
                                            refresh()
                                        }
                                    },
                                    onOpenProfile = { record?.let(::openWholeProfile) },
                                    onOpenWorkspace = ::openWorkspace,
                                    onManageApps = ::chooseApps,
                                    onAddProfile = {
                                        runCatching { ProfileSetupRuntime.beginAnotherProfile(context) }
                                            .onSuccess {
                                                selected = emptySet()
                                                profileName = suggestedProfileName(ProfileRegistryStore.records(context).map { it.label })
                                                page = 0
                                                message = ""
                                                refresh()
                                            }
                                            .onFailure { message = it.message.orEmpty() }
                                    },
                                    onReturnMain = {
                                        scope.launch {
                                            message = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    ProfileSetupRuntime.openProfile(context, null)
                                                    "Returned to Main"
                                                }.getOrElse { it.message ?: "Couldn't return to Main." }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (checking) ProfileNotice("Checking your phone…", ProfileNoticeKind.WORKING)
                    if (message.isNotBlank() && !(checking && message == "Checking your phone…")) {
                        ProfileNotice(message, if (progress.issue != null) ProfileNoticeKind.PROBLEM else ProfileNoticeKind.INFO)
                    }
                    val issue = progress.issue
                    if (!progress.busy && !progress.ready && issue != null) {
                        // Plan 57 W9: the problem card, with the fixes and the debug report.
                        ProfileProblemPanel(
                            issue = issue,
                            onRetry = {
                                val chosen = apps.filter { it.packageName in selected }
                                if (chosen.isNotEmpty() && persistFriendlyName()) {
                                    message = ""
                                    ProfileSetupRuntime.create(context, chosen)
                                } else chooseApps()
                            },
                            onChanged = { refresh() },
                        )
                    }
                }
            }
        }
    }
}

/** Back on the left, the step dots in the middle, Close on the right: round glass chips, as on Home. */
@Composable
private fun ProfileSetupTopBar(step: Int?, onBack: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AskRoundChip("Back", onBack, shineOffset = 0.1f) {
            Icon(Icons.Rounded.ArrowBack, "Back", modifier = Modifier.size(21.dp), tint = AskGlass.Ink)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (step != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = "Step ${step + 1} of 3" }) {
                    repeat(3) { i ->
                        Box(Modifier.size(width = if (i == step) 18.dp else 7.dp, height = 7.dp).clip(ContinuousCapsule)
                            .background(if (i <= step) Color.White else Color.White.copy(alpha = 0.35f)))
                    }
                }
            }
        }
        AskRoundChip("Close", onClose, shineOffset = 0.2f) {
            Icon(Icons.Rounded.Close, null, modifier = Modifier.size(21.dp), tint = AskGlass.Ink)
        }
    }
}

/** A step's big centred title and its one quiet line, with an optional face above it. */
@Composable
private fun StepHeader(title: String, line: String?, face: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        face?.invoke()
        Text(title, color = AskGlass.Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = AskTextShadow))
        if (line != null) {
            Text(line, color = AskGlass.Muted, fontSize = 16.sp, textAlign = TextAlign.Center, style = TextStyle(shadow = AskTextShadow))
        }
    }
}

@Composable
private fun NameProfileStep(
    name: String,
    onName: (String) -> Unit,
    onContinue: () -> Unit,
    checking: Boolean,
    onMainProfile: Boolean?,
    onReturnMain: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                StepHeader("Name your profile", "Its own apps and sign-ins, one tap away.") {
                    ProfileAvatar(name.ifBlank { "?" }, size = 112.dp)
                }
            }
            item {
                ProfileTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) onName(it) },
                    placeholder = "Profile name",
                    large = true,
                )
            }
            if (onMainProfile == false) {
                item { ProfileNotice("New profiles are made from Main.", ProfileNoticeKind.INFO) }
                item { ProfileButton("Back to Main", onReturnMain, Modifier.fillMaxWidth()) }
            }
        }
        ProfileSetupAction("Choose apps", name.isNotBlank() && onMainProfile != false && !checking, onContinue)
    }
}

@Composable
private fun ChooseAppsStep(
    apps: List<ProfileApp>,
    selected: Set<String>,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val essentials = apps.filter { it.automatic }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { StepHeader("Choose apps", "Each one starts fresh here.") }
            item { Spacer(Modifier.height(16.dp)) }
            item { ProfileTextField(query, onQuery, "Search apps", leading = Icons.Rounded.Search) }
            item { Spacer(Modifier.height(14.dp)) }
            if (essentials.isNotEmpty() && query.isBlank()) {
                item {
                    ProfileGroup {
                        ProfileRow(
                            "Essentials",
                            null,
                            subtitle = "Always included",
                            leading = { ProfileIconTile(Icons.Rounded.Check, Color(0xFF30B85A)) },
                            trailing = { ProfileAppStack(essentials.map { it.packageName }) },
                        )
                    }
                }
                item { Spacer(Modifier.height(14.dp)) }
            }
            val filtered = apps.filter { !it.automatic && (it.label.contains(query, true) || it.packageName.contains(query, true)) }
            if (filtered.isEmpty()) {
                item { ProfileNotice("No matching apps.", ProfileNoticeKind.INFO) }
            }
            itemsIndexed(filtered, key = { _, app -> app.packageName }) { index, app ->
                AppPickerRow429(context, app, app.packageName in selected, first = index == 0, last = index == filtered.lastIndex) { checked ->
                    onToggle(app.packageName, checked)
                }
            }
        }
        val optionalCount = ProfileAppSelection.optionalCount(apps, selected)
        Text(
            if (optionalCount > ProfileAppSelection.MAX_OPTIONAL) "Choose up to ${ProfileAppSelection.MAX_OPTIONAL} apps."
            else if (optionalCount == 0) "Essentials only" else "${optionalCount} ${if (optionalCount == 1) "app" else "apps"} + essentials",
            Modifier.fillMaxWidth().padding(top = 10.dp),
            color = if (optionalCount > ProfileAppSelection.MAX_OPTIONAL) ProfileGlass.Destructive else AskGlass.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        ProfileSetupAction("Confirm apps", ProfileAppSelection.canConfirm(apps, selected), onContinue)
    }
}

/** Kept outside the scrolling list and above keyboard/navigation insets on every setup page. */
@Composable
private fun ProfileSetupAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    // Leave the persistent Ask Cyclone activation orb its own space below this action.
    ProfileButton(label, onClick, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 76.dp),
        style = ProfileButtonStyle.PRIMARY, enabled = enabled)
}

/** One app in the picker: a row of one continuous list (rounded at its ends), with a round tick. */
@Composable
private fun AppPickerRow429(context: Context, app: ProfileApp, checked: Boolean, first: Boolean, last: Boolean, onChecked: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(
        topStart = if (first) 22.dp else 0.dp, topEnd = if (first) 22.dp else 0.dp,
        bottomStart = if (last) 22.dp else 0.dp, bottomEnd = if (last) 22.dp else 0.dp,
    )
    Column(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.10f))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(enabled = !app.automatic) { onChecked(!checked) }
                .semantics(mergeDescendants = true) { stateDescription = if (checked) "Selected" else "Not selected" }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileAppIcon429(context, app.packageName, Modifier.size(38.dp))
            Text(app.label, Modifier.weight(1f), color = AskGlass.Ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RoundTick(checked || app.automatic)
        }
        if (!last) ProfileRowDivider(64.dp)
    }
}

/** A grid of app icons with their names under them, like a home screen page. */
@Composable
private fun AppIconGrid(context: Context, packages: List<String>, onOpen: ((String) -> Unit)? = null, max: Int = 12) {
    val shown = packages.take(max)
    ProfileGroup(shineOffset = 0.5f) {
        shown.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                row.forEach { pkg ->
                    val label = profileAppLabel429(context, pkg)
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .then(if (onOpen != null) Modifier.clickable(role = Role.Button) { onOpen(pkg) } else Modifier)
                            .semantics(mergeDescendants = true) { contentDescription = label }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProfileAppIcon429(context, pkg, Modifier.size(46.dp))
                        Text(label, color = AskGlass.Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (packages.size > max) {
            Text("+${packages.size - max} more", Modifier.fillMaxWidth().padding(bottom = 12.dp), color = AskGlass.Muted,
                fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ReviewProfileStep(
    context: Context,
    name: String,
    apps: List<ProfileApp>,
    onCreate: () -> Unit,
    onChangeApps: () -> Unit,
    canCreate: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                StepHeader("Create $name", "${apps.size} ${if (apps.size == 1) "app" else "apps"}, each with its own data") {
                    ProfileAvatar(name, size = 96.dp)
                }
            }
            item { AppIconGrid(context, apps.map { it.packageName }) }
            item { ProfileButton("Change apps", onChangeApps, Modifier.fillMaxWidth(), style = ProfileButtonStyle.QUIET) }
        }
        ProfileSetupAction("Create profile", enabled = canCreate, onClick = onCreate)
    }
}

@Composable
private fun CreatingProfileState(name: String, message: String, completed: Int, total: Int, onStop: () -> Unit) {
    val fraction = if (total <= 0) 0f else completed.toFloat().div(total).coerceIn(0f, 1f)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(140.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.18f),
                strokeWidth = 4.dp,
            )
            ProfileAvatar(name, size = 110.dp)
        }
        StepHeader("Creating $name", message.ifBlank { "Setting up your phone space…" })
        ProfileButton("Pause setup", onStop, style = ProfileButtonStyle.QUIET)
    }
}

@Composable
private fun ReadyProfileStep(
    context: Context,
    record: CycloneProfileRecord?,
    profileName: String,
    workspaces: List<Workspace>,
    packageNames: Set<String>,
    renaming: Boolean,
    onRenaming: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onSaveName: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenWorkspace: (Workspace) -> Unit,
    onManageApps: () -> Unit,
    onAddProfile: () -> Unit,
    onReturnMain: () -> Unit,
) {
    val ready = record?.ready == true
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileAvatar(profileName, record?.emoji, record?.color, size = 104.dp)
                if (renaming) {
                    ProfileTextField(profileName, { if (it.length <= 40) onName(it) }, "Profile name", large = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProfileButton("Cancel", { onRenaming(false) }, style = ProfileButtonStyle.QUIET)
                        ProfileButton("Save name", onSaveName, style = ProfileButtonStyle.PRIMARY)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(profileName, color = AskGlass.Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), style = TextStyle(shadow = AskTextShadow))
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                                .clickable(role = Role.Button) { onRenaming(true) }
                                .semantics { contentDescription = "Rename" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp), tint = AskGlass.Ink) }
                    }
                    ProfileStatus(if (ready) "Ready" else "Setting up", if (ready) AskGlass.Done else AskGlass.Waiting)
                }
            }
        }

        item {
            ProfileButton("Switch to $profileName", onOpenProfile, Modifier.fillMaxWidth(), style = ProfileButtonStyle.PRIMARY,
                icon = Icons.Rounded.SwapHoriz, enabled = ready)
        }

        val packages = if (workspaces.isNotEmpty()) workspaces.map { it.appPackage } else packageNames.sorted()
        if (packages.isNotEmpty()) {
            item { ProfileSectionLabel("Apps") }
            item {
                AppIconGrid(context, packages, max = 24, onOpen = { pkg ->
                    workspaces.firstOrNull { it.appPackage == pkg }?.let(onOpenWorkspace)
                })
            }
        }

        item {
            ProfileGroup(shineOffset = 0.6f) {
                ProfileRow("Edit apps", onManageApps, leading = { ProfileIconTile(Icons.Rounded.Apps, Color(0xFF5E5CE6)) })
                ProfileRowDivider()
                ProfileRow("New profile", onAddProfile, leading = { ProfileIconTile(Icons.Rounded.Add, Color(0xFF3E7BFA)) })
                ProfileRowDivider()
                ProfileRow("Back to Main", onReturnMain, leading = { ProfileIconTile(Icons.Rounded.Home, Color(0xFF30B85A)) })
            }
        }
    }
}

@Composable
private fun ProfileAppIcon429(context: Context, packageName: String, modifier: Modifier = Modifier) {
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: context.getDrawable(com.cyclone.mobile.R.drawable.ic_cyclone_mark_42)
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageDrawable(drawable); it.contentDescription = null },
        modifier = modifier,
    )
}

private fun profileAppLabel429(context: Context, packageName: String): String = runCatching {
    val info = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(info).toString()
}.getOrDefault(packageName)

internal fun suggestedProfileName(taken: List<String>): String =
    com.cyclone.mobile.runtime.workspaces.ProfileProblem.suggestName(taken)
