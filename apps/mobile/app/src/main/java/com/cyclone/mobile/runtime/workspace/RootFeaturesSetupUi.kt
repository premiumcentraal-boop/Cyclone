package com.cyclone.mobile.runtime.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RootFeaturesSetupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootStatus by remember { mutableStateOf(RootStatus.UNKNOWN) }
    var workspaces by remember { mutableStateOf(WorkspaceRuntime.list(context)) }
    var lockHolder by remember { mutableStateOf(WorkspaceRuntime.mutateLockHolder(context)) }
    var activeWizard by remember { mutableStateOf<RootWizardKind?>(null) }

    fun refreshWorkspaceState() {
        workspaces = WorkspaceRuntime.list(context)
        lockHolder = WorkspaceRuntime.mutateLockHolder(context)
    }

    LaunchedEffect(Unit) {
        rootStatus = withContext(Dispatchers.IO) { AndroidRootDetector.detect() }
        refreshWorkspaceState()
    }

    val unlocked = rootStatus == RootStatus.ROOTED
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (unlocked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Root features", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Run multiple app profiles on one phone, then switch Cyclone between them",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                RootStatusChip(rootStatus)
            }

            Text(
                when (rootStatus) {
                    RootStatus.ROOTED -> "Root signals were detected. Layer 2 workspace tools are unlocked; Cyclone still never roots the phone for you."
                    RootStatus.NOT_ROOTED -> "Root was not detected. Workspace setup stays visible, but root-only isolation remains locked."
                    RootStatus.UNKNOWN -> "Cyclone could not confidently determine root state. You can re-check without changing the phone."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            RootActionRow("Check root", "Re-run safe root detection and see what it enables") {
                activeWizard = RootWizardKind.CHECK_ROOT
            }
            RootActionRow(
                "Multi-profile isolation",
                if (unlocked) "Prepare Profile A / B / C with Shelter, Island or your phone's clone feature"
                else "Root required for multi-profile isolation",
                locked = !unlocked,
            ) { activeWizard = RootWizardKind.MULTI_PROFILE_ISOLATION }
            RootActionRow("Register workspaces", "Save a label, app package and Android user ID") {
                activeWizard = RootWizardKind.REGISTER_WORKSPACES
            }
            RootActionRow("Test switch", "Human-run fail-closed switch between two registered workspaces") {
                activeWizard = RootWizardKind.TEST_SWITCH
            }
            RootActionRow(
                "Mutate lock status",
                lockHolder?.let { "Held by $it" } ?: "No workspace currently holds mutation authority",
            ) { activeWizard = RootWizardKind.MUTATE_LOCK }

            if (!unlocked) {
                Text(
                    "Non-root path: OEM Dual Apps / app cloning or Island may still provide separate profiles when your device supports them.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { activeWizard = RootWizardKind.MULTI_PROFILE_ISOLATION }) {
                    Text("Root required for multi-profile isolation")
                }
            }
        }
    }

    activeWizard?.let { kind ->
        RootWizardDialog(
            kind = kind,
            initialRootStatus = rootStatus,
            initialWorkspaces = workspaces,
            onRootStatusChanged = { rootStatus = it },
            onWorkspaceChanged = { refreshWorkspaceState() },
            onDismiss = {
                activeWizard = null
                refreshWorkspaceState()
            },
            onDetectRoot = { callback ->
                scope.launch {
                    callback(withContext(Dispatchers.IO) { AndroidRootDetector.detect() })
                }
            },
        )
    }
}

@Composable
private fun RootStatusChip(status: RootStatus) {
    val container = when (status) {
        RootStatus.ROOTED -> MaterialTheme.colorScheme.secondary
        RootStatus.NOT_ROOTED -> MaterialTheme.colorScheme.surface
        RootStatus.UNKNOWN -> MaterialTheme.colorScheme.surface
    }
    val content = when (status) {
        RootStatus.ROOTED -> MaterialTheme.colorScheme.onSecondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(shape = RoundedCornerShape(999.dp), color = container) {
        Text(
            status.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = content,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun RootActionRow(
    title: String,
    subtitle: String,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (locked) .58f else .88f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (locked) "Locked" else "›", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RootWizardDialog(
    kind: RootWizardKind,
    initialRootStatus: RootStatus,
    initialWorkspaces: List<CycloneWorkspace>,
    onRootStatusChanged: (RootStatus) -> Unit,
    onWorkspaceChanged: () -> Unit,
    onDismiss: () -> Unit,
    onDetectRoot: (((RootStatus) -> Unit) -> Unit),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(kind) { mutableStateOf(RootWizardFlow.start(kind)) }
    var rootStatus by remember(kind, initialRootStatus) { mutableStateOf(initialRootStatus) }
    var workspaces by remember(kind, initialWorkspaces) { mutableStateOf(initialWorkspaces) }
    var message by remember(kind) { mutableStateOf<String?>(null) }
    var busy by remember(kind) { mutableStateOf(false) }
    var label by remember(kind) { mutableStateOf("") }
    var appPackage by remember(kind) { mutableStateOf("") }
    var userId by remember(kind) { mutableStateOf("0") }
    var profileA by remember(kind) { mutableStateOf(false) }
    var profileB by remember(kind) { mutableStateOf(false) }
    var profileC by remember(kind) { mutableStateOf(false) }

    fun refresh() {
        workspaces = WorkspaceRuntime.list(context)
        onWorkspaceChanged()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(wizardTitle(kind)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Step ${state.step + 1} of ${state.totalSteps}", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { (state.step + 1).toFloat() / state.totalSteps.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                WizardStepCard {
                    when (kind) {
                        RootWizardKind.CHECK_ROOT -> when (state.step) {
                            0 -> {
                                Text("Cyclone checks common su paths, Magisk indicators, PATH visibility and build tags. It never executes su or changes root state.")
                                Button(enabled = !busy, onClick = {
                                    busy = true
                                    onDetectRoot { detected ->
                                        rootStatus = detected
                                        onRootStatusChanged(detected)
                                        busy = false
                                        message = "Result: ${detected.label}"
                                        state = state.advance()
                                    }
                                }) { Text(if (busy) "Checking…" else "Check now") }
                            }
                            else -> {
                                RootStatusChip(rootStatus)
                                Text("Root can unlock richer profile-isolation options. Layer 2 switching still keeps one global mutate lock and never bypasses human-confirmation gates.")
                                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }

                        RootWizardKind.MULTI_PROFILE_ISOLATION -> when (state.step) {
                            0 -> {
                                Text("Choose the isolation method your phone supports. Cyclone opens the provider/settings page; installation and profile creation stay user-controlled.")
                                WizardLinkButton("Open Island on Google Play") {
                                    openUrl(context, "https://play.google.com/store/apps/details?id=com.oasisfeng.island")
                                }
                                WizardLinkButton("Open Shelter on F-Droid") {
                                    openUrl(context, "https://f-droid.org/packages/net.typeblog.shelter/")
                                }
                                WizardLinkButton("Open Shelter GitHub") {
                                    openUrl(context, "https://github.com/PeterCxy/Shelter")
                                }
                                WizardLinkButton("Open phone settings for OEM Dual Apps") {
                                    safeOpen(context, Intent(Settings.ACTION_SETTINGS))
                                }
                                Text("No Magisk install, boot patching or root-shell automation is performed.", style = MaterialTheme.typography.bodySmall)
                            }
                            1 -> {
                                Text("Mark profiles as you prepare them outside Cyclone.")
                                ProfileCheck("Profile A ready", profileA) { profileA = it }
                                ProfileCheck("Profile B ready", profileB) { profileB = it }
                                ProfileCheck("Profile C ready", profileC) { profileC = it }
                            }
                            else -> {
                                Text("Isolation remains Layer 1 and manual/external. Register each resulting app package/user as a Cyclone workspace next.")
                                Text("Root status: ${rootStatus.label}", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        RootWizardKind.REGISTER_WORKSPACES -> when (state.step) {
                            0 -> {
                                Text("Register one existing app/profile. Cyclone does not clone or install the app.")
                                OutlinedTextField(label = { Text("Workspace label") }, value = label, onValueChange = { label = it }, singleLine = true)
                                OutlinedTextField(label = { Text("App package") }, value = appPackage, onValueChange = { appPackage = it }, singleLine = true)
                                OutlinedTextField(label = { Text("Android user ID") }, value = userId, onValueChange = { userId = it.filter(Char::isDigit) }, singleLine = true)
                                Button(onClick = {
                                    val parsedUser = userId.toIntOrNull()
                                    val result = runCatching {
                                        require(label.isNotBlank()) { "Enter a workspace label." }
                                        require(appPackage.isNotBlank()) { "Enter an installed app package." }
                                        require(parsedUser != null && parsedUser >= 0) { "Android user ID must be 0 or greater." }
                                        WorkspaceRuntime.register(context, label, appPackage, parsedUser!!)
                                    }
                                    result.onSuccess { saved ->
                                        message = "Saved ${saved.label}"
                                        refresh()
                                        state = state.advance()
                                    }.onFailure { message = it.message ?: "Workspace could not be saved." }
                                }) { Text("Save workspace") }
                                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            else -> {
                                Text("Workspace saved. Add more profiles by running this wizard again.")
                                WorkspaceSummary(workspaces)
                            }
                        }

                        RootWizardKind.TEST_SWITCH -> when (state.step) {
                            0 -> {
                                Text("This is a human-run switch test. Cyclone releases the current mutate lock, launches the target, verifies package/user/display, re-binds observation, then reacquires only after verification.")
                                WorkspaceSummary(workspaces)
                                if (workspaces.size < 2) {
                                    Text("Register at least two workspaces before testing.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            1 -> {
                                val target = workspaces.getOrNull(0)
                                Text(target?.let { "Switch to ${it.label}" } ?: "No first workspace registered")
                                Button(enabled = target != null && !busy, onClick = {
                                    val selected = target ?: return@Button
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { WorkspaceRuntime.switch(context, selected.id) }
                                        busy = false
                                        message = "${result.code}: ${result.message}"
                                        refresh()
                                        if (result.ok) state = state.advance()
                                    }
                                }) { Text(if (busy) "Switching…" else "Switch to first workspace") }
                                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            else -> {
                                val target = workspaces.getOrNull(1)
                                Text(target?.let { "Switch to ${it.label}" } ?: "No second workspace registered")
                                Button(enabled = target != null && !busy, onClick = {
                                    val selected = target ?: return@Button
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { WorkspaceRuntime.switch(context, selected.id) }
                                        busy = false
                                        message = "${result.code}: ${result.message}"
                                        refresh()
                                    }
                                }) { Text(if (busy) "Switching…" else "Switch to second workspace") }
                                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }

                        RootWizardKind.MUTATE_LOCK -> when (state.step) {
                            0 -> {
                                val holder = WorkspaceRuntime.mutateLockHolder(context)
                                Text(holder?.let { "Mutate lock held by $it" } ?: "Mutate lock is free")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = holder != null, onClick = {
                                        val released = WorkspaceRuntime.releaseMutateLock(context)
                                        message = released?.let { "Released $it" } ?: "Lock already free"
                                        refresh()
                                    }) { Text("Release") }
                                    Button(enabled = holder != null, onClick = {
                                        val paused = WorkspaceRuntime.pauseMutateLockHolder(context)
                                        message = paused?.let { "Paused $it" } ?: "Lock already free"
                                        refresh()
                                    }) { Text("Pause") }
                                }
                                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            else -> Text("Only one workspace can own mutation authority. Releasing or pausing never grants another workspace the lock automatically.")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.done || isTerminalStep(state)) {
                Button(onClick = onDismiss) { Text("Done") }
            } else if (kind == RootWizardKind.TEST_SWITCH && state.step > 0 ||
                kind == RootWizardKind.CHECK_ROOT && state.step == 0 ||
                kind == RootWizardKind.REGISTER_WORKSPACES && state.step == 0
            ) {
                TextButton(onClick = onDismiss) { Text("Close") }
            } else {
                Button(onClick = { state = state.advance() }) { Text("Continue") }
            }
        },
        dismissButton = {
            if (!state.done && !isTerminalStep(state)) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun isTerminalStep(state: RootWizardState): Boolean = state.step == state.totalSteps - 1

@Composable
private fun WizardStepCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun WizardLinkButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
private fun ProfileCheck(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun WorkspaceSummary(workspaces: List<CycloneWorkspace>) {
    if (workspaces.isEmpty()) {
        Text("No workspaces registered yet.", style = MaterialTheme.typography.bodySmall)
    } else {
        workspaces.take(6).forEach { workspace ->
            Text(
                "${workspace.label} · ${workspace.appPackage} · user ${workspace.androidUserId} · ${workspace.state.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun wizardTitle(kind: RootWizardKind): String = when (kind) {
    RootWizardKind.CHECK_ROOT -> "Check root"
    RootWizardKind.MULTI_PROFILE_ISOLATION -> "Multi-profile isolation"
    RootWizardKind.REGISTER_WORKSPACES -> "Register workspaces"
    RootWizardKind.TEST_SWITCH -> "Test switch"
    RootWizardKind.MUTATE_LOCK -> "Mutate lock status"
}

private fun openUrl(context: Context, url: String) {
    safeOpen(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun safeOpen(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
