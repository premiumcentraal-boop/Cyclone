package com.cyclone.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.runtime.workspaces.ProfileApp
import com.cyclone.mobile.runtime.workspaces.ProfileCornerstones
import com.cyclone.mobile.runtime.workspaces.ProfileInventory
import com.cyclone.mobile.runtime.workspaces.ProfileInventoryStore
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.ui.v32.CycloneAppIcon
import com.cyclone.mobile.ui.v32.CycloneBackRow
import com.cyclone.mobile.ui.v32.ProfileAvatar
import com.cyclone.mobile.ui.v32.ProfileButton
import com.cyclone.mobile.ui.v32.ProfileButtonStyle
import com.cyclone.mobile.ui.v32.ProfileGlass
import com.cyclone.mobile.ui.v32.ProfileGroup
import com.cyclone.mobile.ui.v32.ProfileNotice
import com.cyclone.mobile.ui.v32.ProfileNoticeKind
import com.cyclone.mobile.ui.v32.ProfileRow
import com.cyclone.mobile.ui.v32.ProfileRowDivider
import com.cyclone.mobile.ui.v32.ProfileSectionLabel
import com.cyclone.mobile.ui.v32.ProfileSheet
import com.cyclone.mobile.ui.v32.ProfileStatus
import com.cyclone.mobile.ui.v32.ProfileTextField
import com.cyclone.mobile.ui.v32.ProfileTitle
import com.cyclone.mobile.ui.v32.appLabel
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.AskTile
import com.cyclone.mobile.ui.v32.cyclonePageInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plan 57 P2 (alpha.120): Profiles → In every profile. The apps every profile is built on (always there), the owner's
 * own additions (Cornerstone marks), and what each profile had at its last switch, with anything missing called out.
 */
@Composable
internal fun ProfileEssentialsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inventories by remember { mutableStateOf<List<Pair<String, ProfileInventory>>>(emptyList()) }
    var marked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var builtIn by remember { mutableStateOf<List<ProfileCornerstones.Item>>(emptyList()) }
    var apps by remember { mutableStateOf<List<ProfileApp>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val labels = runCatching { ProfileRegistryStore.records(context).associate { it.id to it.label } }.getOrDefault(emptyMap())
                Triple(
                    runCatching { ProfileInventoryStore.all(context).map { (labels[it.profileId] ?: "A profile") to it } }.getOrDefault(emptyList()),
                    runCatching { ProfileCornerstones.marked(context) }.getOrDefault(emptySet()),
                    runCatching { ProfileCornerstones.installed(context).filter { it.required } }.getOrDefault(emptyList()),
                )
            }.let { (i, m, b) -> inventories = i; marked = m; builtIn = b }
        }
    }
    LaunchedEffect(Unit) { reload() }
    fun toggle(pkg: String, want: Boolean) {
        note = runCatching { ProfileCornerstones.setMarked(context, pkg, want); null }.getOrElse { it.message }
        marked = runCatching { ProfileCornerstones.marked(context) }.getOrDefault(marked)
    }

    LazyColumn(contentPadding = cyclonePageInsets(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CycloneBackRow("Profiles", onBack) }
        item { ProfileTitle("In every profile", "Added to each one automatically") }

        item { ProfileSectionLabel("Always included") }
        item {
            ProfileGroup {
                builtIn.forEachIndexed { index, app ->
                    if (index > 0) ProfileRowDivider()
                    ProfileRow(appLabel(context, app.packageName), null,
                        leading = { AskTile { CycloneAppIcon(app.packageName, Modifier.size(26.dp)) } },
                        trailing = { Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = AskGlass.Done) })
                }
                if (builtIn.isEmpty()) ProfileRow("Cyclone, your root manager and Shizuku", null)
            }
        }

        item { ProfileSectionLabel("Your additions", action = "Add", onAction = {
            scope.launch {
                apps = withContext(Dispatchers.IO) { runCatching { ProfileSetupRuntime.apps(context) }.getOrDefault(emptyList()) }
                picking = true
            }
        }) }
        item {
            if (marked.isEmpty()) {
                ProfileNotice("Root tools such as an LSPosed manager belong here.", ProfileNoticeKind.INFO)
            } else {
                ProfileGroup {
                    marked.sorted().forEachIndexed { index, pkg ->
                        if (index > 0) ProfileRowDivider()
                        ProfileRow(appLabel(context, pkg), null,
                            leading = { AskTile { CycloneAppIcon(pkg, Modifier.size(26.dp)) } },
                            trailing = { ProfileButton("Remove", { toggle(pkg, false) }, compact = true, style = ProfileButtonStyle.DESTRUCTIVE) })
                    }
                }
            }
        }
        note?.let { item { ProfileNotice(it, ProfileNoticeKind.PROBLEM) } }

        if (inventories.isNotEmpty()) {
            item { ProfileSectionLabel("At the last switch") }
            item {
                ProfileGroup {
                    inventories.forEachIndexed { index, (label, inventory) ->
                        if (index > 0) ProfileRowDivider()
                        val missing = inventory.missing
                        val problem = missing.isNotEmpty() || inventory.cloakNote != null
                        ProfileRow(
                            label,
                            null,
                            subtitle = when {
                                missing.isNotEmpty() -> "Missing: " + missing.joinToString(", ") { it.label }
                                inventory.cloakNote != null -> "Cyclone Cloak: ${inventory.cloakNote}"
                                else -> null
                            },
                            leading = { ProfileAvatar(label, size = 36.dp) },
                            trailing = {
                                if (problem) ProfileStatus(if (missing.isNotEmpty()) "${missing.size} missing" else "Check", ProfileGlass.Destructive)
                                else ProfileStatus("All there", AskGlass.Done)
                            },
                        )
                    }
                }
            }
        }
    }

    if (picking) CornerstonePicker(apps, marked, onToggle = ::toggle, onDone = { picking = false; reload() })
}

/** Pick your additions: a search line and the apps on this phone, each with a round tick. */
@Composable
private fun CornerstonePicker(apps: List<ProfileApp>, marked: Set<String>, onToggle: (String, Boolean) -> Unit, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    ProfileSheet(onDone, "Add to every profile") {
        Text("Up to ${ProfileCornerstones.MAX_MARKED} apps", Modifier, color = AskGlass.Muted, fontSize = 14.sp)
        ProfileTextField(query, { query = it }, "Search apps", leading = Icons.Rounded.Search)
        val shown = apps.filter { !it.automatic && (it.label.contains(query, true) || it.packageName.contains(query, true)) }
        ProfileGroup(Modifier.heightIn(max = 420.dp)) {
            LazyColumn {
                items(shown, key = { it.packageName }) { app ->
                    val on = app.packageName in marked
                    val can = on || marked.size < ProfileCornerstones.MAX_MARKED
                    ProfileRow(
                        app.label,
                        onClick = if (can) ({ onToggle(app.packageName, !on) }) else null,
                        chevron = false,
                        leading = { AskTile { CycloneAppIcon(app.packageName, Modifier.size(26.dp)) } },
                        trailing = { RoundTick(on) },
                    )
                }
            }
        }
        ProfileButton("Done", onDone, Modifier, style = ProfileButtonStyle.PRIMARY)
    }
}

/** A round tick: filled white with a dark check when on, an empty ring when off. */
@Composable
internal fun RoundTick(on: Boolean) {
    Box(
        Modifier.size(26.dp).clip(CircleShape)
            .background(if (on) Color.White else Color.Transparent)
            .border(1.5.dp, if (on) Color.Transparent else Color.White.copy(alpha = 0.45f), CircleShape)
            .semantics { stateDescription = if (on) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), tint = Color(0xFF0B1040))
    }
}
