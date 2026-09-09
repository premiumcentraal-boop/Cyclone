package com.cyclone.mobile.ui

import android.content.Intent
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.workspaces.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RootFeaturesCard() {
    var show by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val user = ProfileSetupRuntime.existingUser(context)
    Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Person, null, modifier = Modifier.size(28.dp))
            Text("Your app profiles", style = MaterialTheme.typography.headlineSmall)
            Text(if (user == null) "A fresh space for another account. Keep your everyday apps just as they are." else "Your everyday apps and your second profile, together on one phone.")
            Button(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text(if (user == null) "Add a second profile" else "Open profiles") }
        }
    }
    if (show) ProfileSetupPage { show = false }
}

@Composable
fun ProfileSetupPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by ProfileSetupRuntime.state.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(if (ProfileSetupRuntime.existingUser(context) != null) 3 else 0) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<ProfileApp>()) }
    var selected by remember { mutableStateOf(ProfileSetupRuntime.selectedPackages(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var registered by remember { mutableStateOf(emptyList<Workspace>()) }
    fun refresh() { scope.launch {
        withContext(Dispatchers.IO) { runCatching { Layer2Workspaces.initialize(context); Layer2Workspaces.engine.snapshot() }.getOrDefault(emptyList()) }
            .also { registered = it }
    } }
    fun chooseApps() {
        checking = true; message = "Checking your phone…"
        scope.launch {
            val root = withContext(Dispatchers.IO) { RootProbe.check() }
            if (root == RootStatus.ROOTED) {
                withContext(Dispatchers.IO) { ProfileSetupRuntime.refreshExisting(context) }
                apps = withContext(Dispatchers.IO) { ProfileSetupRuntime.apps(context) }
                selected = selected.intersect(apps.map { it.packageName }.toSet())
                page = 1; message = ""
            } else {
                message = "Your phone hasn’t allowed extra profiles. You can keep using Profile A. If your phone already has root access, allow Cyclone when it asks, then try again."
            }
            checking = false
        }
    }
    LaunchedEffect(progress.ready) { if (progress.ready) { page = 3; refresh() } }
    LaunchedEffect(Unit) { refresh() }
    Dialog(onDismissRequest = { if (progress.busy) ProfileSetupRuntime.stop(); onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (progress.busy) ProfileSetupRuntime.stop(); if (page == 1 || page == 2) { page--; message = "" } else onClose() }) { Text("Back") }
                    Text("Your profiles", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { if (progress.busy) ProfileSetupRuntime.stop(); onClose() }) { Icon(Icons.Rounded.Close, "Close profiles") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface))).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(if (page == 0) "YOUR EVERYDAY SPACE" else "A FRESH START", style = MaterialTheme.typography.labelMedium)
                                Text(when { progress.busy -> "Making room for you"; page == 1 -> "Which apps?"; page == 2 -> "Ready to create?"; page == 3 -> "Two spaces. One phone."; else -> "You’re in Profile A" }, style = MaterialTheme.typography.headlineLarge)
                                Text(when { progress.busy -> "We’ll take care of the setup."; page == 1 -> "Pick the apps you want in Profile B."; page == 2 -> "Your selected apps will start fresh in Profile B. You’ll sign in there separately."; page == 3 -> "Choose an app from your second profile below."; else -> "Want a second space for different accounts? Your current apps and photos stay right here." })
                            }
                        }
                    }
                    if (progress.busy) {
                        item {
                            Text(progress.message, style = MaterialTheme.typography.titleMedium)
                            LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = { ProfileSetupRuntime.stop() }) { Text("Pause setup") }
                        }
                    } else when (page) {
                        0 -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { chooseApps() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) { Text("Yes, create Profile B") }
                                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Keep one profile") }
                            }
                        }
                        1 -> {
                            item { OutlinedTextField(query, { query = it }, label = { Text("Find an app") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) }
                            if (apps.isEmpty()) item { Text("No apps found yet. Install the apps you want on this phone first.") }
                            items(apps.filter { it.label.contains(query, true) }, key = { it.packageName }) { app ->
                                Surface(onClick = { selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName }, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        val icon = remember(app.packageName) { runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull() }
                                        AndroidView(factory = { ImageView(it) }, update = { it.setImageDrawable(icon); it.contentDescription = null }, modifier = Modifier.size(40.dp))
                                        Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                        Checkbox(app.packageName in selected, onCheckedChange = { selected = if (it) selected + app.packageName else selected - app.packageName })
                                    }
                                }
                            }
                        }
                        2 -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("${selected.size} apps · separate accounts · fresh app data", style = MaterialTheme.typography.titleMedium)
                                Text("Your existing apps and their data stay in Profile A. Creating another profile uses extra storage. Android may limit how many profiles this phone can have.")
                                Button(onClick = { message = ""; ProfileSetupRuntime.create(context, apps.filter { it.packageName in selected }) }, modifier = Modifier.fillMaxWidth()) { Text(if (ProfileSetupRuntime.existingUser(context) != null) "Continue setup" else "Create Profile B") }
                                TextButton(onClick = { page = 1 }) { Text("Change apps") }
                            }
                        }
                        3 -> {
                            val profileId = ProfileSetupRuntime.existingUser(context)
                            val second = registered.filter { it.androidUserId == profileId }
                            item { Text("PROFILE B", style = MaterialTheme.typography.labelLarge) }
                            if (second.isEmpty()) item { Text("Finish choosing apps to prepare your second profile.") }
                            items(second, key = { it.id }) { workspace ->
                                FilledTonalButton(onClick = {
                                    if (com.cyclone.mobile.CycloneAccessibilityService.instance == null) {
                                        message = "Allow phone control so Cyclone can open the right profile."
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    } else scope.launch {
                                        message = "Opening ${workspace.label}…"
                                        val result = withContext(Dispatchers.IO) {
                                            RootProbe.check()
                                            SessionKernel.switchWorkspace(context, workspace.id)
                                        }
                                        message = if (result.ok) "Opened ${workspace.label} in Profile B" else "Couldn’t safely open that app. Make sure Profile B is on and unlocked, then try again."
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Open ${workspace.label}") }
                            }
                            item {
                                val savedProfiles = com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.records(context)
                                savedProfiles.forEach { saved ->
                                    Text(saved.label)
                                    TextButton(onClick = {
                                        ProfileSetupRuntime.selectProfile(context, saved.id)
                                        selected = ProfileSetupRuntime.selectedPackages(context)
                                        chooseApps()
                                    }) { Text("Manage apps / repair") }
                                    if (saved.secondaryUser && saved.ready) TextButton(onClick = {
                                        scope.launch {
                                            message = withContext(Dispatchers.IO) {
                                                runCatching { ProfileSetupRuntime.openProfile(context, saved.id); "Profile opened" }
                                                    .getOrElse { it.message ?: "Couldn't open profile." }
                                            }
                                        }
                                    }) { Text("Open profile") }
                                }
                                OutlinedButton(onClick = {
                                    runCatching { ProfileSetupRuntime.beginAnotherProfile(context) }
                                        .onSuccess { selected = emptySet(); chooseApps() }
                                        .onFailure { message = it.message.orEmpty() }
                                }, enabled = !checking) { Text("Add profile") }
                            }
                            item {
                                OutlinedButton(onClick = { chooseApps() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) { Text("Add apps / finish setup") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            com.cyclone.mobile.PhoneToolExecutor.execute(context, com.cyclone.mobile.PhoneToolRequest(
                                                "profile-home-${System.nanoTime()}", "workspace.release"))
                                        }
                                        if (result.ok) onClose() else message = "Finish the request waiting for your approval, then return to Profile A."
                                    }
                                }) { Text("Return to Profile A") }
                                TextButton(onClick = onClose) { Text("Done") }
                            }
                        }
                    }
                    if (checking) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (message.isNotBlank()) item { Text(message, style = MaterialTheme.typography.bodyMedium) }
                    if (!progress.busy && !progress.ready && progress.message.isNotBlank()) item {
                        Text(progress.message, color = MaterialTheme.colorScheme.error)
                        if (progress.issue?.retryUseful == false) TextButton(onClick = {
                            runCatching { context.startActivity(Intent("android.settings.USER_SETTINGS")) }
                                .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }) { Text("Open Android profile settings") }
                    }
                }
                if (page == 1 && !progress.busy) Button(onClick = { page = 2 }, enabled = selected.isNotEmpty() && selected.size <= 50,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) { Text("Continue with ${selected.size} apps") }
            }
        }
    }
}
