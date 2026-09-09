package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.runtime.workspaces.Workspace
import com.cyclone.mobile.runtime.workspaces.WorkspaceState
import com.cyclone.mobile.ui.ProfileSetupPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CycloneProfilesPage(context: Context, refreshTick: Int) {
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    var setup by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(emptyList<Workspace>()) }
    var error by remember { mutableStateOf("") }

    BackHandler(selectedId != null) { selectedId = null }

    val task by WorkspaceTasks.state.collectAsState()
    val profileSetup by ProfileSetupRuntime.state.collectAsState()
    val revision by Layer2Workspaces.engine.revision.collectAsState()

    LaunchedEffect(refreshTick, revision) {
        withContext(Dispatchers.IO) {
            runCatching {
                Layer2Workspaces.initialize(context)
                Layer2Workspaces.engine.snapshot()
            }
        }.onSuccess {
            profiles = it
            waiting = Layer2Workspaces.engine.queue()
        }.onFailure {
            error = "Profiles couldn't load. Open profile setup to repair."
        }
    }

    fun openForHuman(profile: Workspace) {
        busy = true
        error = ""
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val params = org.json.JSONObject()
                        .put("sessionId", "default-foreground")
                        .put("displayId", 0)
                        .put("id", profile.id)
                    val switched = com.cyclone.mobile.PhoneToolExecutor.execute(
                        context,
                        com.cyclone.mobile.PhoneToolRequest(
                            java.util.UUID.randomUUID().toString(),
                            "workspace.switch",
                            params,
                        ),
                    )
                    if (switched.ok) {
                        com.cyclone.mobile.PhoneToolExecutor.execute(
                            context,
                            com.cyclone.mobile.PhoneToolRequest(
                                java.util.UUID.randomUUID().toString(),
                                "workspace.pause",
                                params,
                            ),
                        )
                    } else switched
                }
                if (!result.ok) {
                    error = "Couldn't hand control to you safely. Review the current task or check profile setup."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Profile opening was interrupted. You can try again."
            } finally {
                busy = false
            }
        }
    }

    val inventory = remember(refreshTick, revision, profileSetup) { WorkspaceTasks.queueDestinations(context) }
    val activeProfiles = profiles.filter { it.state != WorkspaceState.idle || it.id in waiting }
    val visibleProfiles = (if (activeOnly) activeProfiles else profiles).sortedWith(
        compareBy<Workspace> {
            when {
                it.state == WorkspaceState.gated -> 0
                it.state == WorkspaceState.running -> 1
                it.id in waiting -> 2
                it.state == WorkspaceState.paused -> 3
                else -> 4
            }
        }.thenBy { it.label.lowercase() },
    )
    val selected = profiles.firstOrNull { it.id == selectedId }

    if (selected != null) {
        val exactTask = task?.takeIf { UiTask(it).belongsToProfile(selected.id) }
        val stateLabel = profileStateLabel(selected, waiting, exactTask)
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { TextButton(onClick = { selectedId = null }) { Text("‹ Profiles") } }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        CycloneAppIcon(selected.appPackage, Modifier.padding(8.dp).size(36.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(selected.label, style = MaterialTheme.typography.headlineSmall)
                        Text(appLabel(context, selected.appPackage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ProfileStatePill(stateLabel, stateLabel !in setOf("Needs you", "Couldn't load"))
                }
            }

            if (exactTask != null) {
                item { CycloneTaskProgress(exactTask) }
                if (exactTask.steps.isNotEmpty()) {
                    item { CycloneSectionTitle("Recent activity") }
                    items(exactTask.steps.takeLast(6)) { step ->
                        CycloneSimpleCard(Modifier.fillMaxWidth()) {
                            Text(step, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                item {
                    CycloneSimpleCard(Modifier.fillMaxWidth()) {
                        Text(
                            if (selected.id in waiting) "Waiting for Cyclone" else "No active task",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (selected.id in waiting) "Cyclone will rotate into this profile when it is ready." else "This profile is ready for a new task.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Button(
                    enabled = !busy,
                    onClick = { openForHuman(selected) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Opening…" else "Take control")
                }
            }
            if (error.isNotBlank()) {
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        return
    }

    val incompleteInventory = inventory.filter { destination ->
        destination.androidUserId != Layer2Workspaces.currentAndroidUserId() &&
            profiles.none { it.androidUserId == destination.androidUserId }
    }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Profiles", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Accounts and app spaces Cyclone can work in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(onClick = { setup = true }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Add, "Add profile", modifier = Modifier.size(20.dp))
                }
            }
        }

        profileSetup.issue?.takeIf { !profileSetup.busy && !profileSetup.ready }?.let { issue ->
            item {
                CycloneSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(issue.headline, style = MaterialTheme.typography.titleMedium)
                        Text(issue.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (issue.retryUseful) {
                            Button(onClick = { setup = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(issue.action)
                            }
                        } else {
                            Text(issue.action, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        task?.takeIf { UiTask(it).active }?.let { active ->
            item { CycloneSectionTitle("Active now") }
            item { CycloneTaskProgress(active) }
        }

        if (activeProfiles.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Sync, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Cyclone rotates between ${activeProfiles.size} active profiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            CycloneSegmentedControl(
                listOf("All (${profiles.size})", "Active (${activeProfiles.size})"),
                if (activeOnly) 1 else 0,
                { activeOnly = it == 1 },
            )
        }

        if (activeOnly && activeProfiles.isEmpty()) {
            item {
                CycloneSimpleCard(Modifier.fillMaxWidth()) {
                    Text("Nothing active right now", style = MaterialTheme.typography.titleSmall)
                    Text("Profiles with running or queued work will pin here automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (error.isNotEmpty()) {
            item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        items(visibleProfiles, key = { it.id }) { profile ->
            val exactTask = task?.takeIf { UiTask(it).belongsToProfile(profile.id) }
            ProfileCard(
                context = context,
                profile = profile,
                waiting = profile.id in waiting,
                task = exactTask,
                onOpen = { selectedId = profile.id },
            )
        }

        if (!activeOnly && incompleteInventory.isNotEmpty()) {
            item { CycloneSectionTitle("Finish setup") }
            items(incompleteInventory, key = { "profile-${it.androidUserId}" }) { profile ->
                Card(
                    onClick = { setup = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        Modifier.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Person, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(profile.label, style = MaterialTheme.typography.titleSmall)
                            Text("Choose apps to finish this profile", style = MaterialThem…10829 tokens truncated…lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CycloneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) CycloneV32DarkColors else CycloneV32LightColors,
        shapes = CycloneV32Shapes,
        typography = CycloneTypography,
        content = content,
    )
}

enum class CyclonePastel { PRIMARY, LILAC, MINT, LEMON, PEACH, SKY }

@Immutable
data class CyclonePastelColors(val container: Color, val content: Color)

@Composable
fun cyclonePastel(tone: CyclonePastel): CyclonePastelColors {
    // Legacy tone callers intentionally collapse into the shared neutral system.
    return CyclonePastelColors(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
}

@Composable
fun CycloneHeroCard(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: CyclonePastel = CyclonePastel.PRIMARY,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = cyclonePastel(tone)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(22.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
    }
}

@Composable
fun CycloneSectionTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun CycloneStatusPill(label: String, positive: Boolean = true) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun CycloneSimpleCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun CyclonePageIntro(eyebrow: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            eyebrow.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun CycloneV32Theme(content: @Composable () -> Unit) = CycloneTheme(content)

object CycloneColors {
    val Blue = Accent
    val Cyan = Color(0xFF06A9C4)
    val Success = Color(0xFF168653)
}

object CycloneSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Content = 16.dp
    val Page = 18.dp
    val Section = 24.dp
    val ComposerLift = 30.dp
}

@Composable
fun CycloneSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
fun CycloneGlassSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
fun CycloneStatus(label: String, positive: Boolean = true) = CycloneStatusPill(label, positive)
