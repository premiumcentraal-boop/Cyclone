package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.market.*

internal object SkillsNav { var pending by mutableStateOf<String?>(null) }

@Composable
internal fun CycloneSkillsLibraryPage(context: Context, refreshTick: Int, routines: List<AutomationDefinition>,
    onRoutine: (String) -> Unit, onAi: () -> Unit, onTeach: () -> Unit, onAdvanced: () -> Unit, onMarketplace: () -> Unit) {
    val revision by Marketplace.revision.collectAsState()
    val labels = remember(refreshTick) { Marketplace.installedApps(context) }
    val skills = remember(refreshTick, revision) {
        val grounded = Marketplace.skillsWithHealth(context).filter { it.third.state == SkillGroundState.GROUNDED }.map { it.first.id }.toSet()
        Marketplace.available(context).filter { InstagramSkills.byId(it.id) != null || it.id in grounded }
    }
    val apps = remember(labels, skills, routines) { SkillLibrary.apps(labels, skills, routines) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var app by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSkill by rememberSaveable { mutableStateOf<String?>(null) }
    var create by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(SkillsNav.pending) { SkillsNav.pending?.let { selectedSkill = it; SkillsNav.pending = null } }
    BackHandler(app != null || create) { if (create) create = false else app = null }
    val results = SkillLibrary.search(query, apps, skills, routines)
    val searching = query.isNotBlank()
    val activeApp = apps.firstOrNull { it.packageName == app }
    LazyColumn(contentPadding = cyclonePageInsets(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CyclonePageHeader("Skills", "${skills.size} tested or route-verified skills · ${routines.size} routines", trailing = {
            FilledIconButton(onClick = { create = !create }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Add, "Create a routine") }
        }) }
        item { CycloneSimpleCard(Modifier.fillMaxWidth().clickable(onClick = onMarketplace)) {
            Text("Get more skills", style = MaterialTheme.typography.titleSmall)
            Text("Browse the Marketplace and add skills to your library. Instagram starters are already included.", style = MaterialTheme.typography.bodySmall)
        } }
        if (create) item { CycloneSimpleCard(Modifier.fillMaxWidth()) {
            Text("Create a routine", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { create = false; onAi() }) { Text("Describe it to Cyclone") }
            TextButton(onClick = { create = false; onTeach() }) { Text("Teach by doing") }
            TextButton(onClick = { create = false; onAdvanced() }) { Text("Advanced routine builder") }
        } }
        item { CycloneLiquidSearchField(query, { query = it; if (it.isNotBlank()) app = null }, placeholder = "Search", modifier = Modifier.fillMaxWidth()) }
        item { CycloneSegmentedControl(listOf("Apps", "Skills", "Routines"), tab, onSelect = { tab = it; app = null }) }
        if (activeApp != null && !searching) item { CycloneBackRow(activeApp.name) { app = null } }

        fun androidx.compose.foundation.lazy.LazyListScope.skillRows(rows: List<MarketListing>) {
            items(rows, key = { "skill-${it.id}" }) { skill -> LibrarySkillRow(skill) { selectedSkill = skill.id } }
        }
        fun androidx.compose.foundation.lazy.LazyListScope.routineRows(rows: List<AutomationDefinition>) {
            items(rows, key = { "routine-${it.id}" }) { routine -> RoutineListCard(routine, { onRoutine(routine.id) }, { com.cyclone.mobile.automation.AutomationRuntime.router.runManual(routine.id) }) }
        }
        fun androidx.compose.foundation.lazy.LazyListScope.appRows(rows: List<LibraryApp>) {
            items(rows, key = { "app-${it.packageName}" }) { entry ->
                CycloneSimpleCard(Modifier.fillMaxWidth().clickable { query = ""; app = entry.packageName; tab = 0 }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CycloneAppIcon(entry.packageName, Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium)
                            Text("${entry.skills.size} skills · ${entry.routines.size} routines" + if (entry.installed) "" else " · Not installed", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.ChevronRight, "Open app library", Modifier.size(20.dp))
                    }
                }
            }
        }
        when {
            searching -> {
                if (results.empty) item { Text("No apps, skills or routines match your search.") }
                if (results.apps.isNotEmpty()) { item { CycloneSectionTitle("Apps") }; appRows(results.apps) }
                if (results.skills.isNotEmpty()) { item { CycloneSectionTitle("Skills") }; skillRows(results.skills) }
                if (results.routines.isNotEmpty()) { item { CycloneSectionTitle("Routines") }; routineRows(results.routines) }
            }
            activeApp != null -> {
                if (activeApp.skills.isNotEmpty()) { item { CycloneSectionTitle("Skills") }; skillRows(activeApp.skills) }
                if (activeApp.routines.isNotEmpty()) { item { CycloneSectionTitle("Routines") }; routineRows(activeApp.routines) }
                if (activeApp.skills.isEmpty() && activeApp.routines.isEmpty()) item { Text("No verified skills or routines for this app yet. Add one from the Marketplace or teach Cyclone.") }
            }
            tab == 0 -> appRows(apps)
            tab == 1 -> {
                item { Text("Included tested routes and your locally route-verified skills. Added skills that still need verification can be managed in the Marketplace.", style = MaterialTheme.typography.bodySmall) }
                skillRows(skills)
                if (skills.isEmpty()) item { Text("No verified skills yet. Try an included Instagram skill or complete and save a successful run.") }
            }
            else -> {
                routineRows(routines)
                if (routines.isEmpty()) item { Text("No routines yet. Describe one to Cyclone or teach it by doing.") }
            }
        }
    }
    selectedSkill?.let { id ->
        if (id == InstagramSkills.ACCOUNT_SETUP) InstagramAccountSetupSheet(context) { selectedSkill = null }
        else Marketplace.listing(id)?.let { listing ->
            ListingSheet(context, listing, Marketplace.installs(context).get(id), labels) { selectedSkill = null }
        }
    }
}

@Composable
private fun LibrarySkillRow(listing: MarketListing, onClick: () -> Unit) {
    CycloneSimpleCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CycloneAppIcon(listing.apps.firstOrNull(), Modifier.size(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listing.name, style = MaterialTheme.typography.titleSmall)
                Text(listing.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (InstagramSkills.byId(listing.id) != null) "Included · Tested Android route" else "Your skill · Route verified on this phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Rounded.ChevronRight, "Open skill", Modifier.size(20.dp))
        }
    }
}
